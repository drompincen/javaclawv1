#!/bin/bash
# JavaClaw Tutorial 04 — Sprint Planning
# Seeds threads, tickets, objectives, resources; shows coverage and capacity analysis.
# Works in any mode (all CRUD, no LLM pipeline).
set -euo pipefail

# WSL detection: use curl.exe to reach Windows-hosted server
if grep -qi microsoft /proc/version 2>/dev/null; then
  CURL="curl.exe"
else
  CURL="curl"
fi

BASE_URL=${BASE_URL:-http://localhost:8080}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
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

# --- Seed Threads ---
section "2. Seed Work Threads"
THREAD1=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Processing Refactor","summary":"Split monolithic handler into strategy pattern"}')
T1=$(echo "$THREAD1" | jq -r '.threadId'); ok "Thread: Payment Processing Refactor ($T1)"

THREAD2=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Webhook Integration","summary":"RabbitMQ queue with retry/backoff consumer"}')
T2=$(echo "$THREAD2" | jq -r '.threadId'); ok "Thread: Webhook Integration ($T2)"

THREAD3=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Merchant Onboarding Redesign","summary":"Reduce 14-step flow to 7-step wizard"}')
T3=$(echo "$THREAD3" | jq -r '.threadId'); ok "Thread: Merchant Onboarding Redesign ($T3)"

# --- Seed Tickets ---
section "3. Seed Tickets"
for TITLE in "Refactor card payment handler" "Refactor bank transfer handler" "Refactor digital wallet handler" \
             "RabbitMQ spike" "Retry/backoff consumer" "Onboarding wireframes" "S3 presigned upload"; do
  TK=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"$TITLE\",\"description\":\"Sprint 42 ticket\",\"priority\":\"HIGH\"}")
  TK_ID=$(echo "$TK" | jq -r '.ticketId')
  ok "Ticket: $TITLE ($TK_ID)"
done
TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TICKET_IDS=$(echo "$TICKETS" | jq -r '[.[].ticketId]')

# --- Create Objectives ---
section "4. Create Sprint Objectives"
OBJ1=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg t1 "$T1" --argjson tids "$TICKET_IDS" '{
    sprintName: "Sprint 42",
    outcome: "Complete Payment Processing refactor — all 3 handlers split",
    measurableSignal: "3 handler tickets closed",
    risks: ["Tight timeline if Joe is overloaded"],
    threadIds: [$t1],
    ticketIds: ($tids[:3]),
    status: "COMMITTED"
  }')")
O1=$(echo "$OBJ1" | jq -r '.objectiveId'); ok "Objective 1: Payment Processing ($O1)"

OBJ2=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg t2 "$T2" --argjson tids "$TICKET_IDS" '{
    sprintName: "Sprint 42",
    outcome: "Webhook integration queue prototype validated",
    measurableSignal: "Spike complete, load test passing",
    risks: ["Dependency on Stripe sandbox access"],
    threadIds: [$t2],
    ticketIds: ($tids[3:5]),
    status: "COMMITTED"
  }')")
O2=$(echo "$OBJ2" | jq -r '.objectiveId'); ok "Objective 2: Webhook Integration ($O2)"

OBJ3=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg t3 "$T3" --argjson tids "$TICKET_IDS" '{
    sprintName: "Sprint 42",
    outcome: "Onboarding wizard design approved",
    measurableSignal: "Wireframes reviewed, API draft complete",
    risks: ["UX review may push to Sprint 43"],
    threadIds: [$t3],
    ticketIds: ($tids[5:7]),
    status: "PROPOSED"
  }')")
O3=$(echo "$OBJ3" | jq -r '.objectiveId'); ok "Objective 3: Onboarding ($O3)"

# --- List Objectives ---
section "5. Sprint 42 Objectives"
OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives?sprintName=Sprint%2042")
echo "$OBJECTIVES" | jq -r '.[] | "  [\(.status)] \(.outcome) — coverage: \(.coveragePercent // 0)%"'

# --- Seed Resources ---
section "6. Seed Resources"
for i in 0 1 2; do
  MEMBER=$(jq ".[$i] + {projectId: \"$PROJECT_ID\"}" "$SCRIPT_DIR/sample-data/team-roster.json")
  RES=$($CURL -s -X POST "$BASE_URL/api/resources" \
    -H 'Content-Type: application/json' \
    -d "$MEMBER")
  ok "Resource: $(echo "$RES" | jq -r '.name') (availability: $(echo "$RES" | jq -r '.availability'))"
done

# --- List Resources ---
section "7. Resource Overview"
RESOURCES=$($CURL -s "$BASE_URL/api/resources")
echo "$RESOURCES" | jq -r '.[] | "  \(.name) — \(.role) — capacity: \(.capacity), availability: \(.availability)"'

# --- Summary ---
section "8. Sprint Planning Summary"
OBJ_COUNT=$(echo "$OBJECTIVES" | jq 'length')
TK_COUNT=$(echo "$TICKETS" | jq 'length')
echo "  Sprint:     Sprint 42"
echo "  Objectives: $OBJ_COUNT"
echo "  Tickets:    $TK_COUNT"
echo "  Resources:  3 (Joe, Alice, Bob)"
echo ""
echo "  Observations:"
echo "    - 3 objectives cover all 7 tickets"
echo "    - Payment Processing objective is COMMITTED with 3 linked tickets"
echo "    - Onboarding objective is PROPOSED (needs UX review)"
echo "    - Bob has only 0.6 availability (shared with another team)"

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 04 complete."
