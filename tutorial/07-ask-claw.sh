#!/bin/bash
# JavaClaw Tutorial 07 — Ask Claw
# Seeds project data, then asks natural language questions.
# Requires real LLM for answers — seeds work in any mode.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create + Seed Project ---
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

# Seed a thread
curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Processing Refactor","summary":"Splitting monolith into strategy pattern. Joe owns refactor, 3 handlers. Target: Sprint 42."}' > /dev/null
ok "Thread: Payment Processing"

# Seed a ticket
curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"PAY-103: Refactor digital wallet handler","description":"Joe, IN_PROGRESS, 3 SP","priority":"HIGH"}' > /dev/null
ok "Ticket: PAY-103"

# Seed a blindspot
curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{"title":"No load test for payment processing service","category":"MISSING_TEST_SIGNAL","severity":"HIGH"}' > /dev/null
ok "Blindspot: Missing load test"

# Seed an objective
curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d "{\"sprintName\":\"Sprint 42\",\"outcome\":\"Complete Payment Processing refactor\",\"status\":\"COMMITTED\"}" > /dev/null
ok "Objective: Sprint 42"

# --- Ask Questions ---
ask_claw() {
  local Q="$1"
  echo -e "  ${CYAN}Q:${NC} $Q"
  RESP=$(curl -s -X POST "$BASE_URL/api/ask" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg pid "$PROJECT_ID" --arg q "$Q" '{projectId: $pid, question: $q}')")
  ANSWER=$(echo "$RESP" | jq -r '.answer // empty')
  if [ -n "$ANSWER" ]; then
    echo -e "  ${GREEN}A:${NC} $ANSWER" | head -5
    SOURCES=$(echo "$RESP" | jq -r '.sources[]? | "    [\(.type)] \(.title // .id)"')
    [ -n "$SOURCES" ] && echo "$SOURCES"
  else
    warn "No answer — LLM may not be running"
  fi
  echo ""
}

section "2. Ask: Current Risks"
ask_claw "What are the current risks for this project?"

section "3. Ask: Joe's Workload"
ask_claw "How loaded is Joe? What is he working on?"

section "4. Ask: Sprint Status"
ask_claw "What is the status of Sprint 42 objectives?"

# --- Summary ---
section "5. Summary"
echo "  /api/ask assembles context from all project collections:"
echo "    threads, objectives, tickets, blindspots, resources, memories"
echo "  Then sends the question + context to the LLM for a grounded answer."
echo "  Responses include source references so you can trace the data."

echo -e "\n${GREEN}DONE${NC} — Tutorial 07 complete."
