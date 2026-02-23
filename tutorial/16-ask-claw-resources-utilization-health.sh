#!/bin/bash
# JavaClaw Tutorial 16 — Ask Claw: Resources, Utilization, Sprint Health
# Tests three grounded Ask Claw questions with REAL LLM.
# FAILS if no LLM API key is configured.
set -eu

BASE_URL=${BASE_URL:-http://localhost:8080}

CURL="curl"
DEVNULL="/dev/null"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
PASS=0; FAIL_COUNT=0
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  PASS${NC} $1"; PASS=$((PASS+1)); }
fail()    { echo -e "${RED}  FAIL${NC} $1"; FAIL_COUNT=$((FAIL_COUNT+1)); }
die()     { echo -e "${RED}  FATAL${NC} $1"; exit 1; }

# ── Pre-flight: LLM connectivity check ──
section "0. Pre-flight: LLM Connectivity Check"
PROVIDER=$($CURL -sf "$BASE_URL/api/config/provider" 2>/dev/null | jq -r '.provider // empty' || true)
if [ -z "$PROVIDER" ]; then
  die "Server not reachable at $BASE_URL — start the server first"
fi
if echo "$PROVIDER" | grep -qi "no api key\|none"; then
  # Try to pull key from Windows OS env and push to server
  if command -v cmd.exe &>/dev/null; then
    KEY=$(cmd.exe /c "powershell -Command [Environment]::GetEnvironmentVariable('ANTHROPIC_API_KEY','User')" 2>/dev/null | tr -d '\r\n' || true)
    if [ -n "$KEY" ] && [ "$KEY" != "" ]; then
      $CURL -sf -X POST "$BASE_URL/api/config/keys" \
        -H 'Content-Type: application/json' \
        -d "{\"anthropicKey\":\"$KEY\"}" > /dev/null
      PROVIDER=$($CURL -sf "$BASE_URL/api/config/provider" | jq -r '.provider // empty')
    fi
  fi
  if echo "$PROVIDER" | grep -qi "no api key\|none"; then
    die "No LLM API key configured. Set ANTHROPIC_API_KEY env var or use --api-key / Ctrl-K. Tutorial tests REQUIRE a real LLM."
  fi
fi
ok "LLM provider: $PROVIDER"

# ── Find or Create Project ──
section "1. Find or Create Project"
PROJECT_NAME="Tutorial Ask Claw Test"
PROJECT_ID=$($CURL -sf "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1 || true)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -sf -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Ask Claw test project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# ── Seed Resources ──
section "2. Seed Resources"
for R in \
  '{"name":"Maria Santos","role":"ENGINEER","capacity":40,"availability":1.0}' \
  '{"name":"Kevin Wu","role":"ENGINEER","capacity":40,"availability":0.8}' \
  '{"name":"Nadia Petrov","role":"DESIGNER","capacity":40,"availability":0.6}' \
  '{"name":"Omar Hassan","role":"ENGINEER","capacity":40,"availability":0.5}'
do
  NAME=$(echo "$R" | jq -r '.name')
  $CURL -sf -X POST "$BASE_URL/api/resources" \
    -H 'Content-Type: application/json' \
    -d "$(echo "$R" | jq --arg pid "$PROJECT_ID" '. + {projectId: $pid}')" > /dev/null
  ok "Resource: $NAME"
done

# ── Seed Tickets ──
section "3. Seed Tickets"
$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-501: API gateway setup","priority":"HIGH","owner":"Maria Santos","storyPoints":8}' > /dev/null
ok "Ticket: T-501 (Maria, 8 SP)"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-502: Auth middleware","priority":"HIGH","owner":"Maria Santos","storyPoints":5}' > /dev/null
ok "Ticket: T-502 (Maria, 5 SP)"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-503: Dashboard UI","priority":"MEDIUM","owner":"Nadia Petrov","storyPoints":3}' > /dev/null
ok "Ticket: T-503 (Nadia, 3 SP)"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-504: Load testing","priority":"HIGH","storyPoints":5}' > /dev/null
ok "Ticket: T-504 (UNASSIGNED, 5 SP)"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/tickets" \
  -H 'Content-Type: application/json' \
  -d '{"title":"T-505: CI/CD pipeline","priority":"MEDIUM","storyPoints":3}' > /dev/null
ok "Ticket: T-505 (UNASSIGNED, 3 SP)"

# ── Seed Objectives, Blindspots, Phases, Milestones ──
section "4. Seed Objectives, Blindspots, Phases, Milestones"
$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/objectives" \
  -H 'Content-Type: application/json' \
  -d '{"sprintName":"Sprint 15","outcome":"Deliver API gateway with auth","status":"COMMITTED","coveragePercent":45,"risks":["Maria overloaded"]}' > /dev/null
ok "Objective: Sprint 15 — API gateway"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{"title":"No load test plan","description":"T-504 unassigned and no performance baseline","category":"MISSING_TEST_SIGNAL","severity":"HIGH","status":"OPEN"}' > /dev/null
ok "Blindspot: No load test plan"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/phases" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Development","status":"IN_PROGRESS","sortOrder":1,"startDate":"2026-02-17T00:00:00Z","endDate":"2026-03-03T00:00:00Z"}' > /dev/null
ok "Phase: Development"

$CURL -sf -X POST "$BASE_URL/api/projects/$PROJECT_ID/milestones" \
  -H 'Content-Type: application/json' \
  -d '{"name":"API Gateway Launch","status":"ON_TRACK","targetDate":"2026-03-03T00:00:00Z","owner":"Maria Santos"}' > /dev/null
ok "Milestone: API Gateway Launch"

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

  # Check for onboarding message (means no real LLM)
  if echo "$ANSWER" | grep -q "No API key is configured"; then
    fail "LLM returned onboarding message instead of real answer — API key not working"
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
section "5. Ask: How many resources on this project?"
ask_and_validate "how many resources on this project" \
  "Maria" "Kevin" "Nadia" "Omar"

# ── Test 2: Who is not utilized ──
section "6. Ask: Who is not utilized?"
ask_and_validate "tell me who is not utilized" \
  "Kevin" "Omar"

# ── Test 3: Sprint health ──
section "7. Ask: Give me the sprint health"
ask_and_validate "give me the sprint health" \
  "Sprint 15" "Maria" "unassigned"

# ── Results ──
section "Results"
TOTAL=$((PASS + FAIL_COUNT))
echo -e "  Passed: ${GREEN}$PASS${NC} / $TOTAL"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo -e "  Failed: ${RED}$FAIL_COUNT${NC}"
  echo ""
  die "Tutorial 16 FAILED — $FAIL_COUNT assertion(s) failed"
else
  echo -e "\n${GREEN}DONE${NC} — Tutorial 16 complete. All assertions passed."
fi
