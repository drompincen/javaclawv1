#!/bin/bash
# JavaClaw Tutorial 15 — Ask Claw: Grounded Answers from Collections
# Seeds realistic project data with story points, capacity, and assignments,
# then asks capacity/workload/assignment questions and verifies answers
# are grounded in actual collection data.
# Requires real LLM for answers — seeds work in any mode.
set -euo pipefail

# WSL detection: use curl.exe to reach Windows-hosted server
if grep -qi microsoft /proc/version 2>/dev/null; then
  CURL="curl.exe"
else
  CURL="curl"
fi

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_NAME="Tutorial Payment Gateway"
PROJECT_ID=$($CURL -s "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -s -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Payment Gateway tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Seed Resources with Different Capacities ---
section "2. Seed Team Resources"

$CURL -s -X POST "$BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Joe Martinez\",\"role\":\"ENGINEER\",\"capacity\":40,\"availability\":0.8,\"projectId\":\"$PROJECT_ID\"}" > /dev/null
ok "Resource: Joe Martinez (capacity=40, availability=0.8, effectiveHours=32)"

$CURL -s -X POST "$BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Alice Chen\",\"role\":\"ENGINEER\",\"capacity\":40,\"availability\":1.0,\"projectId\":\"$PROJECT_ID\"}" > /dev/null
ok "Resource: Alice Chen (capacity=40, availability=1.0, effectiveHours=40)"

$CURL -s -X POST "$BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Bob Taylor\",\"role\":\"ENGINEER\",\"capacity\":40,\"availability\":0.5,\"projectId\":\"$PROJECT_ID\"}" > /dev/null
ok "Resource: Bob Taylor (capacity=40, availability=0.5, effectiveHours=20)"

# --- Seed Tickets with Story Points ---
section "3. Seed Tickets with Story Points"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-201: Refactor card payment handler","priority":"HIGH","owner":"Joe Martinez","storyPoints":5}' > /dev/null
ok "Ticket: PAY-201 (Joe, 5 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-202: Refactor bank transfer handler","priority":"HIGH","owner":"Joe Martinez","storyPoints":3}' > /dev/null
ok "Ticket: PAY-202 (Joe, 3 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-203: Refactor digital wallet handler","priority":"HIGH","owner":"Joe Martinez","storyPoints":3}' > /dev/null
ok "Ticket: PAY-203 (Joe, 3 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-204: RabbitMQ queue integration spike","priority":"MEDIUM","owner":"Bob Taylor","storyPoints":5}' > /dev/null
ok "Ticket: PAY-204 (Bob, 5 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-205: Implement retry/backoff consumer","priority":"HIGH","owner":"Joe Martinez","storyPoints":8}' > /dev/null
ok "Ticket: PAY-205 (Joe, 8 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-206: Merchant onboarding wizard wireframes","priority":"MEDIUM","storyPoints":3}' > /dev/null
ok "Ticket: PAY-206 (UNASSIGNED, 3 SP)"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-207: Presigned S3 upload endpoint","priority":"HIGH","owner":"Alice Chen","storyPoints":5}' > /dev/null
ok "Ticket: PAY-207 (Alice, 5 SP)"

# --- Seed Objective ---
section "4. Seed Sprint Objective"
$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d '{"sprintName":"Sprint 42","outcome":"Complete Payment Processing refactor and webhook integration","status":"COMMITTED","coveragePercent":60}' > /dev/null
ok "Objective: Sprint 42 — Payment Processing"

# --- Ask Capacity Questions ---
ask_claw() {
  local Q="$1"
  echo -e "  ${CYAN}Q:${NC} $Q"
  RESP=$($CURL -s -X POST "$BASE_URL/api/ask" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg pid "$PROJECT_ID" --arg q "$Q" '{projectId: $pid, question: $q}')")
  ANSWER=$(echo "$RESP" | jq -r '.answer // empty')
  if [ -n "$ANSWER" ]; then
    echo -e "  ${GREEN}A:${NC}"
    echo "$ANSWER" | head -20
    echo ""
    SOURCES=$(echo "$RESP" | jq -r '.sources[]? | "    [\(.type)] \(.title // .id)"')
    [ -n "$SOURCES" ] && echo "$SOURCES"
  else
    warn "No answer — LLM may not be running"
  fi
  echo ""
}

section "5. Ask: Who has the most capacity?"
echo -e "  ${YELLOW}Expected:${NC} Alice Chen (40 effective hours, only 5 SP allocated)"
echo -e "  ${YELLOW}Why:${NC} Context now shows effectiveHours, allocatedSP, and RESOURCE CAPACITY SUMMARY"
ask_claw "Which developer has the most available capacity right now?"

section "6. Ask: Who is overloaded?"
echo -e "  ${YELLOW}Expected:${NC} Joe Martinez (32 effective hours, 19 SP across 4 tickets)"
ask_claw "Who is overloaded? Show me the workload per developer."

section "7. Ask: Unassigned tickets"
echo -e "  ${YELLOW}Expected:${NC} PAY-206 (3 SP, MEDIUM priority, no owner)"
ask_claw "Are there any unassigned tickets? Who should pick them up?"

section "8. Ask: Sprint health"
echo -e "  ${YELLOW}Expected:${NC} Data-driven answer citing 7 tickets, 32 total SP, 60% coverage"
ask_claw "How healthy is Sprint 42? Are we on track to deliver?"

# --- Summary ---
section "9. Summary"
echo "  All answers above should be GROUNDED in collection data:"
echo "    - effectiveHours computed from capacity * availability"
echo "    - allocatedSP computed from ticket storyPoints per resource"
echo "    - RESOURCE CAPACITY SUMMARY section in every ask-claw context"
echo "    - Explicit assignee=NAME or assignee=UNASSIGNED on every ticket"
echo "    - ASSIGNMENT SUMMARY with exact counts"
echo ""
echo "  The LLM never has to guess — every data point comes from MongoDB."

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 15 complete."
