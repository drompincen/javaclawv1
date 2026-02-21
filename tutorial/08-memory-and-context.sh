#!/bin/bash
# JavaClaw Tutorial 08 — Memory and Context
# Store, recall, and delete memories across scopes (GLOBAL, PROJECT, THREAD).
# Works in any mode (all CRUD).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project + Thread ---
section "1. Setup"
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

THREAD=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Processing Refactor"}')
THREAD_ID=$(echo "$THREAD" | jq -r '.threadId')
ok "Thread: $THREAD_ID"

# --- Store Global Memory ---
section "2. Store GLOBAL Memory"
M1=$(curl -s -X POST "$BASE_URL/api/memories" \
  -H 'Content-Type: application/json' \
  -d '{
    "scope": "GLOBAL",
    "key": "team-convention",
    "content": "All API endpoints must return JSON. Use kebab-case for URL paths.",
    "createdBy": "user",
    "tags": ["convention","api"]
  }')
M1_ID=$(echo "$M1" | jq -r '.memoryId // .id')
ok "Global memory stored: $M1_ID"

# --- Store Project Memory ---
section "3. Store PROJECT Memory"
M2=$(curl -s -X POST "$BASE_URL/api/memories" \
  -H 'Content-Type: application/json' \
  -d "{
    \"scope\": \"PROJECT\",
    \"key\": \"architecture-decision\",
    \"content\": \"Payment Processing uses strategy pattern. One handler per payment method.\",
    \"projectId\": \"$PROJECT_ID\",
    \"createdBy\": \"user\",
    \"tags\": [\"architecture\",\"payments\"]
  }")
M2_ID=$(echo "$M2" | jq -r '.memoryId // .id')
ok "Project memory stored: $M2_ID"

# --- Store Thread Memory ---
section "4. Store THREAD Memory"
M3=$(curl -s -X POST "$BASE_URL/api/memories" \
  -H 'Content-Type: application/json' \
  -d "{
    \"scope\": \"THREAD\",
    \"key\": \"refactor-status\",
    \"content\": \"Card payment handler done. Bank transfer handler in progress. Digital wallet handler not started.\",
    \"projectId\": \"$PROJECT_ID\",
    \"sessionId\": \"$THREAD_ID\",
    \"createdBy\": \"user\",
    \"tags\": [\"status\",\"refactor\"]
  }")
M3_ID=$(echo "$M3" | jq -r '.memoryId // .id')
ok "Thread memory stored: $M3_ID"

# --- Recall All Memories ---
section "5. Recall All Memories"
ALL=$(curl -s "$BASE_URL/api/memories")
echo "$ALL" | jq -r '.[] | "  [\(.scope)] \(.key): \(.content[:60])..."'
ok "$(echo "$ALL" | jq 'length') total memories"

# --- Recall by Scope ---
section "6. Recall PROJECT Scope"
PROJ_MEMS=$(curl -s "$BASE_URL/api/memories?scope=PROJECT&projectId=$PROJECT_ID")
echo "$PROJ_MEMS" | jq -r '.[] | "  \(.key): \(.content[:60])..."'

# --- Recall by Query ---
section "7. Search by Query"
SEARCH=$(curl -s "$BASE_URL/api/memories?query=strategy+pattern")
S_COUNT=$(echo "$SEARCH" | jq 'length')
ok "Found $S_COUNT result(s) for 'strategy pattern'"
echo "$SEARCH" | jq -r '.[] | "  [\(.scope)] \(.key): \(.content[:60])..."'

# --- Delete Memory ---
section "8. Delete a Memory"
curl -s -X DELETE "$BASE_URL/api/memories/$M3_ID" > /dev/null
ok "Deleted thread memory: $M3_ID"

REMAINING=$(curl -s "$BASE_URL/api/memories" | jq 'length')
ok "$REMAINING memories remaining"

# --- Summary ---
section "9. Summary"
echo "  Memory scopes and TTL:"
echo "    GLOBAL  — no expiry, visible everywhere"
echo "    PROJECT — 30 day TTL, scoped to one project"
echo "    THREAD  — 7 day TTL, scoped to one thread"
echo "    SESSION — 24 hour TTL, scoped to one session"
echo ""
echo "  Agents use memories to maintain context across conversations."
echo "  The distiller auto-creates memories from completed sessions."

echo -e "\n${GREEN}DONE${NC} — Tutorial 08 complete."
