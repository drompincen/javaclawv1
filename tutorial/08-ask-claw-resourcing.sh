#!/bin/bash
# JavaClaw Tutorial 08 — Ask Claw: Resources, Utilization, Sprint Health
# Tests three grounded Ask Claw questions with REAL LLM.
# Seeds all data via intake pipeline.
# FAILS if no LLM API key is configured.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}

CURL="curl"
DEVNULL="/dev/null"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
PASS=0; FAIL_COUNT=0
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  PASS${NC} $1"; PASS=$((PASS+1)); }
fail()    { echo -e "${RED}  FAIL${NC} $1"; FAIL_COUNT=$((FAIL_COUNT+1)); }
die()     { echo -e "${RED}  FATAL${NC} $1"; exit 1; }

# ── Pre-test Teardown ──
section "0. Pre-test Teardown"
PROJECT_NAME="Tutorial T08 Resourcing"
EXISTING_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -n "$EXISTING_ID" ] && [ "$EXISTING_ID" != "null" ]; then
  $CURL -sf -X DELETE "$BASE_URL/api/projects/$EXISTING_ID/data" -o $DEVNULL 2>/dev/null || true
  ok "Cleaned leftover data for project $EXISTING_ID"
else
  ok "No leftover data"
fi
LLM_BEFORE=$($CURL -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')

# ── Pre-flight: LLM connectivity check ──
section "1. Pre-flight: LLM Connectivity Check"
PROVIDER=$($CURL -sf "$BASE_URL/api/config/provider" 2>/dev/null | jq -r '.provider // empty' || true)
if [ -z "$PROVIDER" ]; then
  die "Server not reachable at $BASE_URL — start the server first"
fi
if echo "$PROVIDER" | grep -qi "no api key\|none"; then
  die "No LLM API key configured. Set ANTHROPIC_API_KEY and restart the server."
fi
ok "LLM provider: $PROVIDER"

# ── Find or Create Project ──
section "2. Find or Create Project"
PROJECT_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -sf -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Resourcing tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# ── Seed via Intake Pipeline ──
section "3. Seed Sprint Data via Intake"
CONTENT="API Gateway Project — Sprint 15 Planning:

Team Resources:
- Maria Santos, Engineer, capacity 40h/week, availability 100% (40 effective hours)
- Kevin Wu, Engineer, capacity 40h/week, availability 80% (32 effective hours)
- Nadia Petrov, Designer, capacity 40h/week, availability 60% (24 effective hours)
- Omar Hassan, Engineer, capacity 40h/week, availability 50% (20 effective hours)

Sprint 15 Backlog:
- T-501: API gateway setup | Priority: HIGH | Assignee: Maria Santos | SP: 8
- T-502: Auth middleware | Priority: HIGH | Assignee: Maria Santos | SP: 5
- T-503: Dashboard UI | Priority: MEDIUM | Assignee: Nadia Petrov | SP: 3
- T-504: Load testing | Priority: HIGH | UNASSIGNED | SP: 5

Maria has 13 SP (2 tickets), Nadia 3 SP (1 ticket), Kevin 0 SP, Omar 0 SP.
T-504 is UNASSIGNED (5 SP).

Sprint 15 Objective: Deliver API gateway with auth
Status: COMMITTED, coverage: 45%, risk: Maria overloaded

Blindspot: No load test plan — T-504 is unassigned, no performance baseline.
Severity: HIGH"

PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONTENT" \
  '{projectId: $pid, content: $content}')
RESP=$($CURL -sf -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || fail "Pipeline returned HTTP $HTTP_CODE"

# ── Poll for Resources ──
section "4. Waiting for Resources"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  RESOURCES=$($CURL -sf "$BASE_URL/api/resources" || echo "[]")
  R_COUNT=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
  [ "$R_COUNT" -ge 3 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, resources=$R_COUNT)"
  sleep 2
done
[ "$R_COUNT" -ge 3 ] && ok "$R_COUNT resource(s) created" || fail "Only $R_COUNT resources after $MAX_ATTEMPTS attempts"

# ── Poll for Tickets ──
section "5. Waiting for Tickets"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/tickets" || echo "[]")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -ge 3 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, tickets=$TK_COUNT)"
  sleep 2
done
[ "$TK_COUNT" -ge 3 ] && ok "$TK_COUNT ticket(s) created" || fail "Only $TK_COUNT tickets after $MAX_ATTEMPTS attempts"

# Give pipeline time to finish all phases
echo "  Waiting for downstream agents to finish..."
sleep 15

# ── Helper: Ask and validate ──
ask_and_validate() {
  local QUESTION="$1"
  shift
  local EXPECTED_KEYWORDS=("$@")

  echo -e "  ${CYAN}Q:${NC} $QUESTION"
  RESP=$($CURL -sf -X POST "$BASE_URL/api/ask" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg pid "$PROJECT_ID" --arg q "$QUESTION" '{projectId: $pid, question: $q}')" 2>/dev/null || true)

  ANSWER=$(echo "$RESP" | jq -r '.answer // empty' 2>/dev/null || true)
  if [ -z "$ANSWER" ]; then
    fail "No answer returned — LLM call failed"
    return
  fi

  if echo "$ANSWER" | grep -q "No API key is configured"; then
    fail "LLM returned onboarding message instead of real answer"
    return
  fi

  echo "$ANSWER" | head -15
  echo "  ..."
  echo ""

  for KW in "${EXPECTED_KEYWORDS[@]}"; do
    if echo "$ANSWER" | grep -qi "$KW"; then
      ok "Response contains: $KW"
    else
      fail "Response MISSING expected keyword: $KW"
    fi
  done
}

# ── Test 1: How many resources ──
section "6. Ask: How many resources on this project?"
ask_and_validate "how many resources on this project" \
  "Maria" "Kevin" "Nadia" "Omar"

# ── Test 2: Who is not utilized ──
section "7. Ask: Who is not utilized?"
ask_and_validate "tell me who is not utilized" \
  "Kevin" "Omar"

# ── Test 3: Sprint health ──
section "8. Ask: Give me the sprint health"
ask_and_validate "give me the sprint health" \
  "Sprint 15" "Maria" "unassigned"

# ── LLM Usage ──
section "LLM Usage"
LLM_AFTER=$($CURL -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')
CALLS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalCalls // 0')
CALLS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalCalls // 0')
TOKENS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalTokens // 0')
TOKENS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalTokens // 0')
echo "  LLM calls:  $((CALLS_AFTER - CALLS_BEFORE))"
echo "  Tokens:     $((TOKENS_AFTER - TOKENS_BEFORE))"

# ── Results ──
section "Results"
TOTAL=$((PASS + FAIL_COUNT))
echo -e "  Passed: ${GREEN}$PASS${NC} / $TOTAL"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo -e "  Failed: ${RED}$FAIL_COUNT${NC}"
  echo ""
  die "Tutorial 08 FAILED — $FAIL_COUNT assertion(s) failed"
else
  echo -e "\n${GREEN}DONE${NC} — Tutorial 08 complete. All assertions passed."
fi
