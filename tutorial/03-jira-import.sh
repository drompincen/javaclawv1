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

# --- Create Project ---
section "1. Create Project"
PROJECT=$(curl -s -X POST "$BASE_URL/api/projects" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Tutorial Jira Import","description":"Jira + meeting notes import demo","tags":["tutorial","jira"]}')
PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
[ "$PROJECT_ID" != "null" ] && ok "Project: $PROJECT_ID" || fail "Project creation failed"

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
TICKET_COUNT=0
while IFS=$'\t' read -r KEY EPIC SUMMARY ASSIGNEE STATUS PRIORITY SP; do
  [ "$KEY" = "Key" ] && continue  # skip header
  # Clean whitespace
  KEY=$(echo "$KEY" | xargs); SUMMARY=$(echo "$SUMMARY" | xargs)
  ASSIGNEE=$(echo "$ASSIGNEE" | xargs); STATUS=$(echo "$STATUS" | xargs)
  PRIORITY=$(echo "$PRIORITY" | xargs)
  TICKET=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg t "$KEY: $SUMMARY" --arg d "Epic: $EPIC, Assignee: $ASSIGNEE, SP: $SP" --arg p "$PRIORITY" \
      '{title: $t, description: $d, priority: $p}')")
  TID=$(echo "$TICKET" | jq -r '.ticketId')
  ok "$KEY — $SUMMARY ($TID)"
  TICKET_COUNT=$((TICKET_COUNT + 1))
done < <(sed 's/  */\t/g' "$SCRIPT_DIR/sample-data/jira-export.txt")
ok "Seeded $TICKET_COUNT tickets"

# --- Verify Tickets ---
section "4. Verify Tickets"
TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TOTAL=$(echo "$TICKETS" | jq 'length')
ok "Project has $TOTAL ticket(s)"
echo "$TICKETS" | jq -r '.[] | "  [\(.status)] \(.title)"'

# --- Submit Meeting Notes to Pipeline ---
section "5. Submit Meeting Notes to Intake Pipeline"
NOTES=$(cat "$SCRIPT_DIR/sample-data/meeting-notes-kyc.txt")
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

# --- Check Blindspots ---
section "7. Check Blindspots"
BLINDSPOTS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
B_COUNT=$(echo "$BLINDSPOTS" | jq 'length')
if [ "$B_COUNT" -gt 0 ]; then
  ok "$B_COUNT blindspot(s) detected"
  echo "$BLINDSPOTS" | jq -r '.[] | "  [\(.severity)] \(.title) — \(.category)"'
else
  warn "No blindspots yet — agents may still be analyzing"
fi

# --- Check Delta Packs ---
section "8. Check Delta Packs"
DELTAS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/delta-packs")
D_COUNT=$(echo "$DELTAS" | jq 'length')
if [ "$D_COUNT" -gt 0 ]; then
  ok "$D_COUNT delta pack(s)"
  echo "$DELTAS" | jq -r '.[] | "  \(.summary[:80])"'
else
  warn "No delta packs yet — reconciliation may still be running"
fi

# --- Summary ---
section "9. Summary"
echo "  Project:    $PROJECT_ID"
echo "  Resources:  3 (Joe, Alice, Bob)"
echo "  Tickets:    $TICKET_COUNT"
echo "  Threads:    ${T_COUNT:-0}"
echo "  Blindspots: ${B_COUNT:-0}"
echo "  Delta Packs: ${D_COUNT:-0}"
echo ""
echo "  Key insight: Joe is assigned 5 of 7 tickets (22 story points)."
echo "  The capacity analysis should flag this as an overload risk."

echo -e "\n${GREEN}DONE${NC} — Tutorial 03 complete."
