#!/bin/bash
# JavaClaw Tutorial 03 — File Upload: CSV Jira Import
# Uploads Jira CSV via multipart POST, submits to intake pipeline,
# and verifies that the LLM-powered pipeline creates tickets and threads.
# Requires real LLM — the intake pipeline uses agents (triage, generalist) to parse content.
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UPLOAD_DIR="$SCRIPT_DIR/sample-data"
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
assert_eq() {
  TOTAL=$((TOTAL + 1))
  local label="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    ok "$label: $actual == $expected"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}  FAIL${NC} $label: expected $expected, got $actual"
  fi
}
assert_contains() {
  TOTAL=$((TOTAL + 1))
  local label="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -qi "$needle"; then
    ok "$label: contains '$needle'"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}  FAIL${NC} $label: does not contain '$needle'"
  fi
}

# --- Pre-test Teardown ---
section "0. Pre-test Teardown"
PROJECT_NAME="Tutorial T03 File Upload"
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
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"File upload tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Upload CSV File ---
section "2. Upload CSV File"
UPLOAD_RESP=$($CURL -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$UPLOAD_DIR/jira-export.csv")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Upload count" "$UPLOAD_COUNT" "1"

CSV_FILE_PATH=$(echo "$UPLOAD_RESP" | jq -r '.[0].filePath')
CSV_CONTENT_TYPE=$(echo "$UPLOAD_RESP" | jq -r '.[0].contentType')
assert_eq "Content type" "$CSV_CONTENT_TYPE" "csv"
ok "File path: $CSV_FILE_PATH"

UPLOAD_NAME=$(echo "$UPLOAD_RESP" | jq -r '.[0].fileName')
assert_contains "Filename" "$UPLOAD_NAME" "jira-export.csv"

# --- Submit CSV to Pipeline ---
section "3. Submit CSV to Pipeline"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg fp "$CSV_FILE_PATH" \
  '{projectId: $pid, content: "Process the uploaded CSV — Jira tickets with Key, Epic, Summary, Assignee, Status, Priority, SP columns.", filePaths: [$fp]}')
PIPELINE_RESP=$($CURL -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

# --- Wait for Pipeline ---
section "4. Wait for Pipeline"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -ge 2 ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for tickets ($ATTEMPTS/$MAX_ATTEMPTS, currently $TK_COUNT)"
  sleep 2
done

# --- Assert Results ---
section "5. Assert Results"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "Threads" "$T_COUNT" "1"

TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TK_COUNT=$(echo "$TICKETS" | jq 'length')
assert_gte "Tickets" "$TK_COUNT" "2"

ALL_TITLES=$(echo "$TICKETS" | jq -r '.[].title' | tr '\n' ' ')
assert_contains "Has card-related ticket" "$ALL_TITLES" "card"

echo ""
echo "  Threads ($T_COUNT):"
echo "$THREADS" | jq -r '.[] | "    [\(.lifecycle // .status)] \(.title)"'
echo "  Tickets ($TK_COUNT):"
echo "$TICKETS" | jq -r '.[] | "    [\(.status)] \(.title)"' | tail -10

# --- Summary ---
section "Summary"
echo "  Project:    $PROJECT_ID"
echo "  Threads:    $T_COUNT"
echo "  Tickets:    $TK_COUNT"
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
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 03 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC}"
  exit 1
fi
