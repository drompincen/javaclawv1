#!/bin/bash
# JavaClaw Tutorial 03 — Jira Import
# Seeds resources, submits meeting notes + Jira export, verifies tickets and detects Joe's overload.
# Requires real LLM for pipeline — CRUD sections work in any mode.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_NAME="Tutorial Payment Gateway"
PROJECT_ID=$(curl -s "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$(curl -s -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Payment Gateway tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi
[ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ] || fail "Project creation failed"

# --- Seed Resources ---
section "2. Seed Team Resources"
ROSTER="$SCRIPT_DIR/sample-data/team-roster.json"
for i in 0 1 2; do
  MEMBER=$(jq ".[$i] + {projectId: \"$PROJECT_ID\"}" "$ROSTER")
  RES=$(curl -s -X POST "$BASE_URL/api/resources" \
    -H 'Content-Type: application/json' \
    -d "$MEMBER")
  NAME=$(echo "$RES" | jq -r '.name')
  RES_ID=$(echo "$RES" | jq -r '.resourceId')
  ok "Resource: $NAME ($RES_ID)"
done

# --- Seed Tickets from Jira Export ---
section "3. Seed Jira Tickets"
create_ticket() {
  local TITLE="$1" DESC="$2" PRIO="$3"
  TICKET=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg t "$TITLE" --arg d "$DESC" --arg p "$PRIO" \
      '{title: $t, description: $d, priority: $p}')")
  TID=$(echo "$TICKET" | jq -r '.ticketId')
  ok "$TITLE ($TID)"
}

create_ticket "PAY-101: Refactor card payment handler"    "Epic: Payment Processing | Assignee: Joe | SP: 5"     "HIGH"
create_ticket "PAY-102: Refactor bank transfer handler"   "Epic: Payment Processing | Assignee: Joe | SP: 3"     "HIGH"
create_ticket "PAY-103: Refactor digital wallet handler"  "Epic: Payment Processing | Assignee: Joe | SP: 3"     "HIGH"
create_ticket "PAY-104: Spike RabbitMQ queue integration" "Epic: Webhook Integration | Assignee: Bob | SP: 5"    "MEDIUM"
create_ticket "PAY-105: Implement retry/backoff consumer" "Epic: Webhook Integration | Assignee: Joe | SP: 8"    "HIGH"
create_ticket "PAY-106: Single-page wizard wireframes"    "Epic: Merchant Onboarding | Assignee: Joe | SP: 3"    "MEDIUM"
create_ticket "PAY-107: Presigned S3 upload endpoint"     "Epic: Merchant Onboarding | Assignee: Alice | SP: 5"  "HIGH"
TICKET_COUNT=7

# --- Verify Tickets ---
section "4. Verify Tickets"
TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TOTAL=$(echo "$TICKETS" | jq 'length')
ok "Project has $TOTAL ticket(s)"
echo "$TICKETS" | jq -r '.[] | "  [\(.status)] \(.title)"'

# --- Submit Meeting Notes to Pipeline ---
section "5. Submit Meeting Notes to Intake Pipeline"
NOTES=$(cat "$SCRIPT_DIR/sample-data/meeting-notes-payments.txt")
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$NOTES" \
  '{projectId: $pid, content: $content}')
RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline returned HTTP $HTTP_CODE"

# --- Poll for Threads ---
section "6. Waiting for Threads"
ATTEMPTS=0; MAX_ATTEMPTS=30
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
  T_COUNT=$(echo "$THREADS" | jq 'length')
  [ "$T_COUNT" -gt 0 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS)"
  sleep 2
done
[ "$T_COUNT" -gt 0 ] && ok "$T_COUNT thread(s) created" || warn "No threads — pipeline may need real LLM"

# --- Wait for full pipeline to complete (objectives, reconciliation) ---
section "7. Waiting for Pipeline Completion"
echo "  Waiting for objective + reconcile agents to finish..."
sleep 15

# --- Check Blindspots ---
section "8. Check Blindspots"
BLINDSPOTS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
B_COUNT=$(echo "$BLINDSPOTS" | jq 'length')
if [ "$B_COUNT" -gt 0 ]; then
  ok "$B_COUNT blindspot(s) detected"
  echo "$BLINDSPOTS" | jq -r '.[] | "  [\(.severity)] \(.title) — \(.category)"'
else
  warn "No blindspots yet — agents may still be analyzing"
fi

# --- Check Delta Packs ---
section "9. Check Delta Packs"
DELTAS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/delta-packs")
D_COUNT=$(echo "$DELTAS" | jq 'length')
if [ "$D_COUNT" -gt 0 ]; then
  ok "$D_COUNT delta pack(s)"
  echo "$DELTAS" | jq -r '.[] | "  \(.summary[:80])"'
else
  warn "No delta packs yet — reconciliation may still be running"
fi

# --- Check Objectives ---
section "10. Check Objectives"
OBJECTIVES=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
if [ "$O_COUNT" -gt 0 ]; then
  ok "$O_COUNT objective(s) created"
  echo "$OBJECTIVES" | jq -r '.[] | "  [\(.status)] \(.outcome[:70])"'
else
  warn "No objectives yet"
fi

# --- Summary ---
section "11. Summary"
echo "  Project:     $PROJECT_ID"
echo "  Resources:   3 (Joe, Alice, Bob)"
echo "  Tickets:     $TICKET_COUNT"
echo "  Threads:     ${T_COUNT:-0}"
echo "  Objectives:  ${O_COUNT:-0}"
echo "  Blindspots:  ${B_COUNT:-0}"
echo "  Delta Packs: ${D_COUNT:-0}"
echo ""
echo "  Key insight: Joe is assigned 5 of 7 tickets (22 story points)."
echo "  The capacity analysis should flag this as an overload risk."

echo -e "\n${GREEN}DONE${NC} — Tutorial 03 complete."
