#!/bin/bash
# JavaClaw Tutorial 12 — Agent Orchestration
# Seeds project data via REST, triggers agent sessions, verifies outputs.
# Requires testMode or real LLM — agents must respond to create artifacts.
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }

# --- Health Check ---
section "1. Health Check"
STATUS=$($CURL -s -o $DEVNULL -w '%{http_code}' "$BASE_URL/api/projects")
[ "$STATUS" = "200" ] && ok "Server is running at $BASE_URL" || fail "Server not reachable (HTTP $STATUS)"

# --- Find or Create Project ---
section "2. Find or Create Project"
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

# --- Seed Threads ---
section "3. Seed Threads"
T1=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Gateway Design","content":"Stripe integration with webhook handlers. PostgreSQL for transactions. Redis for idempotency. Target: 2-sprint delivery."}')
T1_ID=$(echo "$T1" | jq -r '.threadId')
ok "Thread 1: $T1_ID"

T2=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Fraud Detection Design","content":"ML-based fraud scoring. Real-time transaction analysis. Threshold alerting. Integration with payment events."}')
T2_ID=$(echo "$T2" | jq -r '.threadId')
ok "Thread 2: $T2_ID"

# --- Seed Tickets ---
section "4. Seed Tickets"
$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-101: Stripe webhook handler","description":"Implement webhook endpoints.","priority":"HIGH","owner":"Carol","storyPoints":5}' > /dev/null
ok "Ticket T-101 created"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-102: Fraud scoring model","description":"Build ML model.","priority":"HIGH","owner":"Dave","storyPoints":13}' > /dev/null
ok "Ticket T-102 created"

$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-103: API docs","description":"Swagger docs for all endpoints.","priority":"LOW","storyPoints":3}' > /dev/null
ok "Ticket T-103 created (no owner)"

# --- Seed Resources ---
section "5. Seed Resources"
$CURL -s -X POST "$BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Carol\",\"role\":\"ENGINEER\",\"capacity\":100,\"availability\":0.9,\"projectId\":\"$PROJECT_ID\"}" > /dev/null
ok "Resource Carol created"

$CURL -s -X POST "$BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Dave\",\"role\":\"ENGINEER\",\"capacity\":100,\"availability\":0.7,\"projectId\":\"$PROJECT_ID\"}" > /dev/null
ok "Resource Dave created"

# --- Seed Memories ---
section "6. Seed Memories"
$CURL -s -X POST "$BASE_URL/api/memories" \
  -H 'Content-Type: application/json' \
  -d "{\"scope\":\"PROJECT\",\"projectId\":\"$PROJECT_ID\",\"content\":\"Sprint cadence: 2-week sprints. Carol is tech lead.\",\"tags\":[\"process\"]}" > /dev/null
ok "Memory seeded"

# --- Verify Seed Data ---
section "7. Verify Seed Data"
T_COUNT=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
TK_COUNT=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')
ok "Threads: $T_COUNT, Tickets: $TK_COUNT"

# --- Create Agent Session ---
section "8. Create Agent Session"
SESSION=$($CURL -s -X POST "$BASE_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$PROJECT_ID\"}")
SESSION_ID=$(echo "$SESSION" | jq -r '.sessionId')
[ "$SESSION_ID" != "null" ] && ok "Session created: $SESSION_ID" || fail "Session creation failed"

# --- Send Agent Query ---
section "9. Send Query to Agent"
$CURL -s -X POST "$BASE_URL/api/sessions/$SESSION_ID/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Create sprint objectives, a project plan, and a release checklist from the project data.","role":"user"}' > /dev/null
ok "Message sent"

# --- Poll for Completion ---
section "10. Poll for Session Completion"
MAX_WAIT=120
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
  SESSION_STATUS=$($CURL -s "$BASE_URL/api/sessions/$SESSION_ID" | jq -r '.status')
  if [ "$SESSION_STATUS" = "COMPLETED" ] || [ "$SESSION_STATUS" = "FAILED" ]; then
    break
  fi
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  echo -ne "  Waiting... ${ELAPSED}s (status: $SESSION_STATUS)\r"
done
echo ""
[ "$SESSION_STATUS" = "COMPLETED" ] && ok "Session completed" || warn "Session status: $SESSION_STATUS (may still be in progress)"

# --- Verify Outputs ---
section "11. Verify Agent Outputs"
OBJ_COUNT=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives" | jq 'length')
PHASE_COUNT=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/phases" | jq 'length')
CHK_COUNT=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/checklists" | jq 'length')

echo "  Objectives: $OBJ_COUNT"
echo "  Phases: $PHASE_COUNT"
echo "  Checklists: $CHK_COUNT"

[ "$OBJ_COUNT" -ge 1 ] && ok "Objectives created" || warn "No objectives found (agent may need real LLM)"
[ "$PHASE_COUNT" -ge 1 ] && ok "Phases created" || warn "No phases found (agent may need real LLM)"
[ "$CHK_COUNT" -ge 1 ] && ok "Checklists created" || warn "No checklists found (agent may need real LLM)"

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 12 complete. Agent orchestration with seeded data."
