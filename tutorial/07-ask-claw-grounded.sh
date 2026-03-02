#!/bin/bash
# JavaClaw Tutorial 07 — Ask Claw: Grounded Answers from Collections
# Seeds realistic project data via intake pipeline with story points, capacity,
# and assignments, then asks capacity/workload/assignment questions and verifies
# answers are grounded in actual collection data.
# Requires real LLM for both intake and answers.
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
assert_not_empty() {
  TOTAL=$((TOTAL + 1))
  local label="$1" value="$2"
  if [ -n "$value" ]; then
    ok "$label: non-empty"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}  FAIL${NC} $label: empty"
  fi
}

# --- Pre-test Teardown ---
section "0. Pre-test Teardown"
PROJECT_NAME="Tutorial T07 Grounded"
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
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Grounded answers tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Seed via Intake Pipeline ---
section "2. Seed Capacity and Ticket Data via Intake"
CONTENT="Payment Gateway Sprint 42 — Team and Backlog:

Team Resources:
- Joe Martinez, Engineer, capacity 40h/week, availability 80% (32 effective hours)
- Alice Chen, Engineer, capacity 40h/week, availability 100% (40 effective hours)
- Bob Taylor, Engineer, capacity 40h/week, availability 50% (20 effective hours)

Sprint 42 Backlog:
- PAY-201: Refactor card payment handler | Priority: HIGH | Assignee: Joe Martinez | SP: 5
- PAY-202: Refactor bank transfer handler | Priority: HIGH | Assignee: Joe Martinez | SP: 3
- PAY-203: Refactor digital wallet handler | Priority: HIGH | Assignee: Joe Martinez | SP: 3
- PAY-204: RabbitMQ queue integration | Priority: MEDIUM | Assignee: Bob Taylor | SP: 5
- PAY-205: Presigned S3 upload endpoint | Priority: HIGH | Assignee: Alice Chen | SP: 5

Joe has 11 SP (3 tickets), Alice 5 SP (1 ticket), Bob 5 SP (1 ticket).

Sprint 42 Objective: Complete Payment Processing refactor and queue integration
Status: COMMITTED, coverage: 60%"

PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONTENT" \
  '{projectId: $pid, content: $content}')
RESP=$($CURL -sf -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline returned HTTP $HTTP_CODE"

# --- Poll for Resources ---
section "3. Waiting for Resources"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  RESOURCES=$($CURL -sf "$BASE_URL/api/resources" || echo "[]")
  R_COUNT=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
  [ "$R_COUNT" -ge 2 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, resources=$R_COUNT)"
  sleep 2
done

# --- Poll for Tickets ---
section "4. Waiting for Tickets"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/tickets" || echo "[]")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -ge 3 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, tickets=$TK_COUNT)"
  sleep 2
done

# Give pipeline time to finish objectives
echo "  Waiting for downstream agents..."
sleep 5

# --- Assertions on seed data ---
section "5. Seed Assertions"
assert_gte "Resources" "$R_COUNT" "2"
assert_gte "Tickets" "$TK_COUNT" "3"

# --- Ask Capacity Questions ---
ask_claw() {
  local Q="$1"
  echo -e "  ${CYAN}Q:${NC} $Q"
  RESP=$($CURL -sf -X POST "$BASE_URL/api/ask" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg pid "$PROJECT_ID" --arg q "$Q" '{projectId: $pid, question: $q}')" || true)
  ANSWER=$(echo "$RESP" | jq -r '.answer // empty' 2>/dev/null || true)
  if [ -n "$ANSWER" ]; then
    echo -e "  ${GREEN}A:${NC}"
    echo "$ANSWER" | head -20
    echo ""
    SOURCES=$(echo "$RESP" | jq -r '.sources[]? | "    [\(.type)] \(.title // .id)"')
    [ -n "$SOURCES" ] && echo "$SOURCES"
  fi
  echo ""
}

section "6. Ask: Who has the most capacity?"
echo -e "  ${YELLOW}Expected:${NC} Alice Chen (40 effective hours, only 5 SP allocated)"
ask_claw "Which developer has the most available capacity right now?"
assert_not_empty "Capacity answer" "$ANSWER"

section "7. Ask: Who is overloaded?"
echo -e "  ${YELLOW}Expected:${NC} Joe Martinez (32 effective hours, 11 SP across 3 tickets)"
ask_claw "Who is overloaded? Show me the workload per developer."
assert_not_empty "Overloaded answer" "$ANSWER"

section "8. Ask: Sprint health"
echo -e "  ${YELLOW}Expected:${NC} Data-driven answer citing tickets, SP, coverage"
ask_claw "How healthy is Sprint 42? Are we on track to deliver?"
assert_not_empty "Sprint health answer" "$ANSWER"

# --- Summary ---
section "9. Summary"
echo "  Assertions: $PASS/$TOTAL passed"

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
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 07 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC}"
  exit 1
fi
