#!/bin/bash
# JavaClaw Tutorial 06 — Agent Orchestration
# Seeds project data via intake pipeline, triggers agent sessions, verifies outputs.
# Requires real LLM — agents must respond to create artifacts.
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }

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
PROJECT_NAME="Tutorial T06 Agent Orch"
EXISTING_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -n "$EXISTING_ID" ] && [ "$EXISTING_ID" != "null" ]; then
  $CURL -sf -X DELETE "$BASE_URL/api/projects/$EXISTING_ID/data" -o $DEVNULL 2>/dev/null || true
  ok "Cleaned leftover data for project $EXISTING_ID"
else
  ok "No leftover data"
fi
LLM_BEFORE=$($CURL -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')

# --- Health Check ---
section "1. Health Check"
STATUS=$($CURL -sf -o $DEVNULL -w '%{http_code}' "$BASE_URL/api/projects")
[ "$STATUS" = "200" ] && ok "Server is running at $BASE_URL" || fail "Server not reachable (HTTP $STATUS)"

# --- Find or Create Project ---
section "2. Find or Create Project"
PROJECT_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -sf -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Agent orchestration tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Seed via Intake Pipeline ---
section "3. Seed Threads, Tickets, and Resources via Intake"
CONTENT="Payment Gateway Project — Sprint Planning:

Design Thread: Payment Gateway Design
Stripe integration with webhook handlers. PostgreSQL for transactions. Redis for idempotency.

Design Thread: Fraud Detection Design
ML-based fraud scoring. Real-time transaction analysis. Threshold alerting.

Team Resources:
- Carol, Engineer, capacity 100h, availability 90%
- Dave, Engineer, capacity 100h, availability 70%

Sprint Backlog:
- T-101: Stripe webhook handler | Priority: HIGH | Assignee: Carol | SP: 5
- T-102: Fraud scoring model | Priority: HIGH | Assignee: Dave | SP: 13
- T-103: API docs | Priority: LOW | Unassigned | SP: 3"

PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONTENT" \
  '{projectId: $pid, content: $content}')
RESP=$($CURL -sf -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline returned HTTP $HTTP_CODE"

# --- Poll for Threads and Tickets ---
section "4. Waiting for Threads"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  T_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/threads" 2>/dev/null | jq 'length' || echo 0)
  [ "$T_COUNT" -ge 1 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, threads=$T_COUNT)"
  sleep 2
done

section "5. Waiting for Tickets"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TK_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/tickets" 2>/dev/null | jq 'length' || echo 0)
  [ "$TK_COUNT" -ge 2 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, tickets=$TK_COUNT)"
  sleep 2
done

# Brief wait for downstream agents
echo "  Waiting for pipeline to settle..."
sleep 5

# --- Seed Memories (memories are exempt from guard) ---
section "6. Seed Memories"
$CURL -sf -X POST "$BASE_URL/api/memories" \
  -H 'Content-Type: application/json' \
  -d "{\"scope\":\"PROJECT\",\"projectId\":\"$PROJECT_ID\",\"content\":\"Sprint cadence: 2-week sprints. Carol is tech lead.\",\"tags\":[\"process\"]}" > /dev/null
ok "Memory seeded"

# --- Verify Seed Data ---
section "7. Verify Seed Data"
T_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
TK_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')
ok "Threads: $T_COUNT, Tickets: $TK_COUNT"

# --- Agent Session with Retry ---
run_agent_session() {
  local ATTEMPT=$1
  echo -e "  ${CYAN}Attempt $ATTEMPT${NC}"

  SESSION=$($CURL -sf -X POST "$BASE_URL/api/sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"projectId\":\"$PROJECT_ID\"}")
  SESSION_ID=$(echo "$SESSION" | jq -r '.sessionId')
  [ "$SESSION_ID" = "null" ] && return 1

  $CURL -sf -X POST "$BASE_URL/api/sessions/$SESSION_ID/messages" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":\"For project $PROJECT_ID, create sprint objectives, a project plan with phases, and a release checklist from the existing project data. Use projectId $PROJECT_ID for all tool calls.\",\"role\":\"user\"}" > /dev/null

  $CURL -sf -X POST "$BASE_URL/api/sessions/$SESSION_ID/run" > /dev/null

  MAX_WAIT=120
  ELAPSED=0
  while [ $ELAPSED -lt $MAX_WAIT ]; do
    SESSION_STATUS=$($CURL -sf "$BASE_URL/api/sessions/$SESSION_ID" | jq -r '.status')
    if [ "$SESSION_STATUS" = "COMPLETED" ] || [ "$SESSION_STATUS" = "FAILED" ]; then
      break
    fi
    sleep 3
    ELAPSED=$((ELAPSED + 3))
    echo -ne "  Waiting... ${ELAPSED}s (status: $SESSION_STATUS)\r"
  done
  echo ""

  OBJ_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/objectives" | jq 'length')
  PHASE_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/phases" | jq 'length')
  CHK_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/checklists" | jq 'length')

  [ "$OBJ_COUNT" -ge 1 ] && [ "$PHASE_COUNT" -ge 1 ] && [ "$CHK_COUNT" -ge 1 ]
}

section "8. Agent Session (with retry)"
SESSION_OK=false
for ATTEMPT in 1 2; do
  if run_agent_session "$ATTEMPT"; then
    SESSION_OK=true
    ok "Session produced artifacts on attempt $ATTEMPT"
    break
  else
    warn "Attempt $ATTEMPT: Objectives=$OBJ_COUNT Phases=$PHASE_COUNT Checklists=$CHK_COUNT"
    [ $ATTEMPT -lt 2 ] && echo "  Retrying..."
  fi
done

# --- Verify Outputs & Assertions ---
section "9. Assertions"
OBJ_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/objectives" | jq 'length')
PHASE_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/phases" | jq 'length')
CHK_COUNT=$($CURL -sf "$BASE_URL/api/projects/$PROJECT_ID/checklists" | jq 'length')

echo "  Objectives: $OBJ_COUNT"
echo "  Phases: $PHASE_COUNT"
echo "  Checklists: $CHK_COUNT"

assert_gte "Objectives" "$OBJ_COUNT" "1"
assert_gte "Phases" "$PHASE_COUNT" "1"
assert_gte "Checklists" "$CHK_COUNT" "1"

# --- Summary ---
section "10. Summary"
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
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 06 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC}"
  exit 1
fi
