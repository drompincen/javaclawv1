#!/bin/bash
# JavaClaw Tutorial 01 — Quickstart
# Creates a project, session, sends a message, lists everything.
# Works in any mode (testmode or real LLM).
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

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
[ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ] || fail "Project creation failed"

# --- List Projects ---
section "3. List Projects"
COUNT=$($CURL -s "$BASE_URL/api/projects" | jq 'length')
ok "Found $COUNT project(s)"

# --- Get Project ---
section "4. Get Project Details"
NAME=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID" | jq -r '.name')
ok "Project name: $NAME"

# --- Create Session ---
section "5. Create Session"
SESSION=$($CURL -s -X POST "$BASE_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$PROJECT_ID\"}")
SESSION_ID=$(echo "$SESSION" | jq -r '.sessionId')
[ "$SESSION_ID" != "null" ] && ok "Session created: $SESSION_ID" || fail "Session creation failed"

# --- Send Message ---
section "6. Send Message"
MSG=$($CURL -s -X POST "$BASE_URL/api/sessions/$SESSION_ID/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Hello JavaClaw! What can you do?","role":"user"}')
MSG_ID=$(echo "$MSG" | jq -r '.messageId')
[ "$MSG_ID" != "null" ] && ok "Message sent: $MSG_ID" || fail "Message send failed"

# --- List Messages ---
section "7. List Messages"
MESSAGES=$($CURL -s "$BASE_URL/api/sessions/$SESSION_ID/messages")
MSG_COUNT=$(echo "$MESSAGES" | jq 'length')
ok "Session has $MSG_COUNT message(s)"
echo "$MESSAGES" | jq -r '.[] | "  [\(.role)] \(.content[:80])"'

# --- Create Thread ---
section "8. Create Thread"
THREAD=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Getting Started","summary":"Initial exploration of the platform"}')
THREAD_ID=$(echo "$THREAD" | jq -r '.threadId')
[ "$THREAD_ID" != "null" ] && ok "Thread created: $THREAD_ID" || fail "Thread creation failed"

# --- List Threads ---
section "9. List Threads"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
ok "Project has $T_COUNT thread(s)"
echo "$THREADS" | jq -r '.[] | "  - \(.title) [\(.status)]"'

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 01 complete. You created a project, session, thread, and message."
