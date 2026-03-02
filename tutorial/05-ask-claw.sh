#!/bin/bash
# JavaClaw Tutorial 05 — Ask Claw
# Seeds project data via intake pipeline, then asks natural language questions.
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
PROJECT_NAME="Tutorial T05 Ask Claw"
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
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Ask Claw tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Seed via Intake Pipeline ---
section "2. Seed Project Data via Intake"
CONTENT="Payment Gateway Sprint 42 Status:

Thread: Payment Processing Refactor
Splitting monolith into strategy pattern. Joe owns refactor, 3 handlers. Target: Sprint 42.

Ticket: PAY-103 — Refactor digital wallet handler | Assignee: Joe | SP: 3 | Priority: HIGH | IN_PROGRESS

Risk: No load test plan for payment processing — HIGH severity gap.

Sprint 42 Objective: Complete Payment Processing refactor
Status: COMMITTED, coverage 60%. Key risk: Joe's workload."

PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONTENT" \
  '{projectId: $pid, content: $content}')
RESP=$($CURL -sf -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline returned HTTP $HTTP_CODE"

# --- Poll for Threads ---
section "3. Waiting for Threads"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  THREADS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/threads" || echo "[]")
  T_COUNT=$(echo "$THREADS" | jq 'length')
  [ "$T_COUNT" -ge 1 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS)"
  sleep 2
done
assert_gte "Threads" "$T_COUNT" "1"

# Give pipeline time to finish creating objectives/blindspots
echo "  Waiting for downstream agents..."
sleep 10

# --- Ask Questions ---
ask_claw() {
  local Q="$1"
  echo -e "  ${CYAN}Q:${NC} $Q"
  RESP=$($CURL -sf -X POST "$BASE_URL/api/ask" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg pid "$PROJECT_ID" --arg q "$Q" '{projectId: $pid, question: $q}')" || true)
  ANSWER=$(echo "$RESP" | jq -r '.answer // empty' 2>/dev/null || true)
  if [ -n "$ANSWER" ]; then
    echo -e "  ${GREEN}A:${NC} $ANSWER" | head -5
    SOURCES=$(echo "$RESP" | jq -r '.sources[]? | "    [\(.type)] \(.title // .id)"')
    [ -n "$SOURCES" ] && echo "$SOURCES"
  fi
  echo ""
}

section "4. Ask: Current Risks"
ask_claw "What are the current risks for this project?"
assert_not_empty "Risk answer" "$ANSWER"

section "5. Ask: Joe's Workload"
ask_claw "How loaded is Joe? What is he working on?"
assert_not_empty "Joe workload answer" "$ANSWER"

section "6. Ask: Sprint Status"
ask_claw "What is the status of Sprint 42 objectives?"
assert_not_empty "Sprint status answer" "$ANSWER"

# --- Summary ---
section "7. Summary"
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
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 05 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC}"
  exit 1
fi
