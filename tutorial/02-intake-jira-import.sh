#!/bin/bash
# JavaClaw Tutorial 02 — Intake: Jira Import
# Submits team roster + Jira tickets as a single intake payload.
# The pipeline triages and creates resources, tickets, threads, blindspots, and objectives.
# Requires real LLM — designed for demos and documentation.
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

PASS=0; TOTAL=0
assert_gte() {
  TOTAL=$((TOTAL + 1))
  local label="$1" actual="$2" expected="$3"
  if [ "$actual" -ge "$expected" ]; then
    ok "$label: $actual >= $expected"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}  FAIL${NC} $label: expected >= $expected, got $actual"
  fi
}

# --- Pre-test Teardown ---
section "0. Pre-test Teardown"
PROJECT_NAME="Tutorial T02 Jira Import"
EXISTING_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -n "$EXISTING_ID" ] && [ "$EXISTING_ID" != "null" ]; then
  $CURL -sf -X DELETE "$BASE_URL/api/projects/$EXISTING_ID/data" -o $DEVNULL 2>/dev/null || true
  ok "Cleaned leftover data for project $EXISTING_ID"
else
  ok "No leftover data"
fi
LLM_BEFORE=$($CURL -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -sf -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Jira import tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi
[ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ] || fail "Project creation failed"

# --- Submit Structured Intake ---
section "2. Submit Team + Jira Tickets via Intake"
CONTENT="Team Resources:
- Joe Martinez, Engineer, capacity 40h, availability 80% (32 effective hours)
- Alice Chen, Engineer, capacity 40h, availability 100% (40 effective hours)
- Bob Taylor, Engineer, capacity 40h, availability 50% (20 effective hours)

Jira Sprint Backlog (Sprint 42):
- PAY-201: Refactor card payment handler | Assignee: Joe | SP: 5 | Priority: HIGH
- PAY-202: Refactor bank transfer handler | Assignee: Joe | SP: 3 | Priority: HIGH
- PAY-203: Refactor digital wallet handler | Assignee: Joe | SP: 3 | Priority: HIGH
- PAY-204: RabbitMQ queue integration | Assignee: Bob | SP: 5 | Priority: MEDIUM
- PAY-205: Presigned S3 upload endpoint | Assignee: Alice | SP: 5 | Priority: HIGH

Joe has 11 SP (3 tickets), Alice 5 SP (1 ticket), Bob 5 SP (1 ticket)."

PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONTENT" \
  '{projectId: $pid, content: $content}')
RESP=$($CURL -sf -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline returned HTTP $HTTP_CODE"

# --- Poll for Resources ---
section "3. Waiting for Resources"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  RESOURCES=$($CURL -sf "$BASE_URL/api/resources" || echo "[]")
  R_COUNT=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
  [ "$R_COUNT" -ge 2 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for resources ($ATTEMPTS/$MAX_ATTEMPTS, currently $R_COUNT)"
  sleep 2
done

# --- Poll for Tickets ---
section "4. Waiting for Tickets"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/tickets" || echo "[]")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -ge 3 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for tickets ($ATTEMPTS/$MAX_ATTEMPTS, currently $TK_COUNT)"
  sleep 2
done

# --- Poll for Threads ---
section "5. Waiting for Threads"
ATTEMPTS=0; MAX_ATTEMPTS=30
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  THREADS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/threads" || echo "[]")
  T_COUNT=$(echo "$THREADS" | jq 'length')
  [ "$T_COUNT" -ge 1 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for threads ($ATTEMPTS/$MAX_ATTEMPTS, currently $T_COUNT)"
  sleep 2
done

# --- Wait for full pipeline ---
section "6. Waiting for Pipeline Completion"
echo "  Waiting for objective + reconcile agents to finish..."
sleep 15

# --- Assertions ---
section "7. Assertions"
assert_gte "Resources" "$R_COUNT" "2"
assert_gte "Tickets" "$TK_COUNT" "3"
assert_gte "Threads" "$T_COUNT" "1"

# --- Check Blindspots ---
section "8. Check Blindspots"
BLINDSPOTS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/blindspots" || echo "[]")
B_COUNT=$(echo "$BLINDSPOTS" | jq 'length')
if [ "$B_COUNT" -gt 0 ]; then
  ok "$B_COUNT blindspot(s) detected"
  echo "$BLINDSPOTS" | jq -r '.[] | "  [\(.severity)] \(.title) — \(.category)"'
else
  warn "No blindspots yet"
fi

# --- Check Objectives ---
section "9. Check Objectives"
OBJECTIVES=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/objectives" || echo "[]")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
if [ "$O_COUNT" -gt 0 ]; then
  ok "$O_COUNT objective(s) created"
  echo "$OBJECTIVES" | jq -r '.[] | "  [\(.status)] \(.outcome[:70])"'
else
  warn "No objectives yet"
fi

# --- Summary ---
section "10. Summary"
echo "  Project:     $PROJECT_ID"
echo "  Resources:   ${R_COUNT:-0}"
echo "  Tickets:     ${TK_COUNT:-0}"
echo "  Threads:     ${T_COUNT:-0}"
echo "  Objectives:  ${O_COUNT:-0}"
echo "  Blindspots:  ${B_COUNT:-0}"
echo "  Assertions:  $PASS/$TOTAL passed"

# --- LLM Usage ---
section "LLM Usage"
LLM_AFTER=$($CURL -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')
CALLS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalCalls // 0')
CALLS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalCalls // 0')
TOKENS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalTokens // 0')
TOKENS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalTokens // 0')
echo "  LLM calls:  $((CALLS_AFTER - CALLS_BEFORE))"
echo "  Tokens:     $((TOKENS_AFTER - TOKENS_BEFORE))"

# --- Teardown ---
section "Teardown"
$CURL -sf -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL 2>/dev/null || true
ok "Cleaned project data"

if [ "$PASS" -eq "$TOTAL" ]; then
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 02 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC}"
  exit 1
fi
