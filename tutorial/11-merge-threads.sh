#!/bin/bash
# JavaClaw Tutorial 11 — Merge Threads
# Creates 2 threads with messages, merges them, verifies combined content.
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
    -d "{\"name\":\"$PROJECT_NAME\"}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Create Thread 1 ---
section "3. Create Thread 1"
T1=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Architecture","content":"Architecture decisions for payment service using Kafka and PostgreSQL."}')
T1_ID=$(echo "$T1" | jq -r '.threadId')
[ "$T1_ID" != "null" ] && ok "Thread 1 created: $T1_ID" || fail "Thread 1 creation failed"

# --- Create Thread 2 ---
section "4. Create Thread 2"
T2=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment Implementation","content":"Implementation details for payment service endpoints and testing."}')
T2_ID=$(echo "$T2" | jq -r '.threadId')
[ "$T2_ID" != "null" ] && ok "Thread 2 created: $T2_ID" || fail "Thread 2 creation failed"

# --- Post Messages to Thread 1 ---
section "5. Post Messages to Thread 1"
$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads/$T1_ID/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"We should use event sourcing for payment state changes.","role":"user"}' > /dev/null
ok "Message 1 sent to thread 1"

# --- Post Messages to Thread 2 ---
section "6. Post Messages to Thread 2"
$CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads/$T2_ID/messages" \
  -H 'Content-Type: application/json' \
  -d '{"content":"REST endpoints need rate limiting for compliance.","role":"user"}' > /dev/null
ok "Message 1 sent to thread 2"

# --- Merge Threads ---
section "7. Merge Threads"
MERGE_RESULT=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/threads/merge" \
  -H 'Content-Type: application/json' \
  -d "{\"sourceThreadIds\":[\"$T1_ID\",\"$T2_ID\"],\"targetTitle\":\"Payment Architecture & Implementation\"}")
MERGED_TITLE=$(echo "$MERGE_RESULT" | jq -r '.title')
[ "$MERGED_TITLE" = "Payment Architecture & Implementation" ] && ok "Merged thread title: $MERGED_TITLE" || fail "Merge failed: $MERGE_RESULT"

# --- Verify Merged Content ---
section "8. Verify Merged Content"
MERGED_CONTENT=$(echo "$MERGE_RESULT" | jq -r '.content')
echo "$MERGED_CONTENT" | grep -q "Architecture decisions" && ok "Contains thread 1 content" || fail "Missing thread 1 content"
echo "$MERGED_CONTENT" | grep -q "Implementation details" && ok "Contains thread 2 content" || fail "Missing thread 2 content"

# --- Verify Source Thread Marked MERGED ---
section "9. Verify Source Thread Lifecycle"
T2_LIFECYCLE=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads/$T2_ID" | jq -r '.lifecycle')
[ "$T2_LIFECYCLE" = "MERGED" ] && ok "Source thread lifecycle: MERGED" || fail "Expected MERGED, got: $T2_LIFECYCLE"

# --- Verify Merged From ---
section "10. Verify Merge Metadata"
MERGED_FROM=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads/$T1_ID" | jq -r '.mergedFromThreadIds | length')
[ "$MERGED_FROM" -ge 1 ] && ok "mergedFromThreadIds count: $MERGED_FROM" || fail "No mergedFromThreadIds on target"

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 11 complete. Threads merged with combined content and messages."
