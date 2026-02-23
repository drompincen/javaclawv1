#!/bin/bash
# JavaClaw Tutorial 14 — Upload Conversations with Objectives & Resources
# Uploads planning conversations (text and JSON) that contain objectives and resource
# allocation data. Tests both REST text intake and file upload paths. Verifies that the
# LLM-powered pipeline creates the correct objectives, resources, threads, and tickets.
# Requires real LLM — agents parse unstructured/semi-structured content into domain objects.
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

# Snapshot baseline counts
BASELINE_OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives" | jq 'length')
BASELINE_RESOURCES=$($CURL -s "$BASE_URL/api/resources" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
BASELINE_THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
ok "Baseline: $BASELINE_OBJECTIVES objectives, $BASELINE_RESOURCES resources, $BASELINE_THREADS threads"

# ===================================================================
# PART A: REST Text — Conversation with objectives & resources
# ===================================================================
section "PART A: REST Text Pipeline — Sprint Planning Conversation"

section "A1. Submit Conversation Text to Pipeline"
CONV_TEXT=$(cat "$SCRIPT_DIR/sample-data/conversation-objectives-resources.txt")
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$CONV_TEXT" \
  '{projectId: $pid, content: $content}')
PIPELINE_RESP=$($CURL -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "A2. Wait for Pipeline"
echo "  Waiting for objectives + resources agents to process..."
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
  O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
  RESOURCES=$($CURL -s "$BASE_URL/api/resources")
  R_COUNT=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
  [ "$O_COUNT" -gt "$BASELINE_OBJECTIVES" ] && [ "$R_COUNT" -gt "$BASELINE_RESOURCES" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, objectives=$O_COUNT, resources=$R_COUNT)"
  sleep 2
done

section "A3. Assert Text Results — Threads"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "Threads created" "$T_COUNT" "$((BASELINE_THREADS + 1))"
echo "  Threads:"
echo "$THREADS" | jq -r '.[] | "    [\(.lifecycle // .status)] \(.title)"'

section "A4. Assert Text Results — Objectives"
OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
assert_gte "Objectives created" "$O_COUNT" "$((BASELINE_OBJECTIVES + 2))"

ALL_OUTCOMES=$(echo "$OBJECTIVES" | jq -r '.[].outcome // .[].title // empty' | tr '\n' ' ')
assert_contains "Has Payment Processing objective" "$ALL_OUTCOMES" "Payment"
assert_contains "Has Webhook objective" "$ALL_OUTCOMES" "Webhook\|queue\|RabbitMQ"

echo "  Objectives:"
echo "$OBJECTIVES" | jq -r '.[] | "    [\(.status)] \(.outcome[:70])"'

section "A5. Assert Text Results — Resources"
RESOURCES=$($CURL -s "$BASE_URL/api/resources")
PROJECT_RESOURCES=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)]')
R_COUNT=$(echo "$PROJECT_RESOURCES" | jq 'length')
assert_gte "Resources created" "$R_COUNT" "$((BASELINE_RESOURCES + 2))"

ALL_NAMES=$(echo "$PROJECT_RESOURCES" | jq -r '.[].name' | tr '\n' ' ')
assert_contains "Has Joe" "$ALL_NAMES" "Joe"
assert_contains "Has Alice" "$ALL_NAMES" "Alice"

echo "  Resources:"
echo "$PROJECT_RESOURCES" | jq -r '.[] | "    \(.name) — \(.role) — capacity: \(.capacity), availability: \(.availability)"'

section "A6. Assert Text Results — Memories"
MEMORIES=$($CURL -s "$BASE_URL/api/memories?projectId=$PROJECT_ID")
M_COUNT=$(echo "$MEMORIES" | jq 'length')
assert_gte "Memories created" "$M_COUNT" "1"

# Update baselines after Part A
POST_A_OBJECTIVES=$O_COUNT
POST_A_RESOURCES=$R_COUNT
POST_A_THREADS=$T_COUNT

# ===================================================================
# PART B: File Upload TXT — Same conversation as file
# ===================================================================
section "PART B: File Upload TXT — Conversation File"

section "B1. Upload TXT File"
UPLOAD_RESP=$($CURL -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$UPLOAD_DIR/conversation-objectives-resources.txt")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Upload count" "$UPLOAD_COUNT" "1"

TXT_FILE_PATH=$(echo "$UPLOAD_RESP" | jq -r '.[0].filePath')
TXT_CONTENT_TYPE=$(echo "$UPLOAD_RESP" | jq -r '.[0].contentType')
assert_eq "Content type" "$TXT_CONTENT_TYPE" "text"
ok "File path: $TXT_FILE_PATH"

section "B2. Submit to Pipeline with File"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg fp "$TXT_FILE_PATH" \
  '{projectId: $pid, content: "Process the uploaded sprint planning conversation. It contains objectives, resource allocations, and action items.", filePaths: [$fp]}')
PIPELINE_RESP=$($CURL -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "B3. Wait for Pipeline (TXT file)"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
  O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
  [ "$O_COUNT" -gt "$POST_A_OBJECTIVES" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, objectives=$O_COUNT)"
  sleep 2
done

section "B4. Assert TXT File Results"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "TXT threads exist" "$T_COUNT" "$POST_A_THREADS"

OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
assert_gte "TXT objectives exist" "$O_COUNT" "$((POST_A_OBJECTIVES + 2))"

RESOURCES=$($CURL -s "$BASE_URL/api/resources")
PROJECT_RESOURCES=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)]')
R_COUNT=$(echo "$PROJECT_RESOURCES" | jq 'length')
assert_gte "TXT resources exist" "$R_COUNT" "$POST_A_RESOURCES"

# Update baselines
POST_B_OBJECTIVES=$O_COUNT
POST_B_THREADS=$T_COUNT

# ===================================================================
# PART C: File Upload JSON — Structured conversation file
# ===================================================================
section "PART C: File Upload JSON — Structured Conversation"

section "C1. Upload JSON File"
UPLOAD_RESP=$($CURL -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$UPLOAD_DIR/conversation-objectives-resources.json")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Upload count" "$UPLOAD_COUNT" "1"

JSON_FILE_PATH=$(echo "$UPLOAD_RESP" | jq -r '.[0].filePath')
JSON_CONTENT_TYPE=$(echo "$UPLOAD_RESP" | jq -r '.[0].contentType')
assert_eq "Content type" "$JSON_CONTENT_TYPE" "json"

section "C2. Submit to Pipeline with JSON File"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg fp "$JSON_FILE_PATH" \
  '{projectId: $pid, content: "Process the uploaded JSON file containing sprint planning data with objectives, resource allocations, and action items.", filePaths: [$fp]}')
PIPELINE_RESP=$($CURL -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "C3. Wait for Pipeline (JSON file)"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
  O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
  [ "$O_COUNT" -gt "$POST_B_OBJECTIVES" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, objectives=$O_COUNT)"
  sleep 2
done

section "C4. Assert JSON File Results"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "JSON threads exist" "$T_COUNT" "$POST_B_THREADS"

OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
assert_gte "JSON objectives exist" "$O_COUNT" "$((POST_B_OBJECTIVES + 2))"

RESOURCES=$($CURL -s "$BASE_URL/api/resources")
PROJECT_RESOURCES=$(echo "$RESOURCES" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)]')
R_COUNT=$(echo "$PROJECT_RESOURCES" | jq 'length')
assert_gte "JSON resources exist" "$R_COUNT" "$POST_A_RESOURCES"

ALL_OUTCOMES=$(echo "$OBJECTIVES" | jq -r '.[].outcome // .[].title // empty' | tr '\n' ' ')
assert_contains "JSON has Payment objective" "$ALL_OUTCOMES" "Payment"

echo "  Objectives:"
echo "$OBJECTIVES" | jq -r '.[] | "    [\(.status)] \(.outcome[:70])"'
echo "  Resources:"
echo "$PROJECT_RESOURCES" | jq -r '.[] | "    \(.name) — \(.role)"'

# Update baselines
POST_C_OBJECTIVES=$O_COUNT
POST_C_TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')

# ===================================================================
# PART D: Multi-file Upload — CSV + TXT in one request
# ===================================================================
section "PART D: Multi-File Upload — CSV + TXT Together"

section "D1. Upload Both Files"
UPLOAD_RESP=$($CURL -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$UPLOAD_DIR/jira-export.csv" \
  -F "files=@$UPLOAD_DIR/conversation-objectives-resources.txt")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Multi-upload count" "$UPLOAD_COUNT" "2"
ok "Uploaded 2 files"

section "D2. Submit to Pipeline with Both Files"
FP_ARRAY=$(echo "$UPLOAD_RESP" | jq '[.[].filePath]')
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --argjson fps "$FP_ARRAY" \
  '{projectId: $pid, content: "Process both uploaded files. The CSV contains Jira tickets and the TXT contains a sprint planning conversation with objectives and resource allocations.", filePaths: $fps}')
PIPELINE_RESP=$($CURL -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "D3. Wait for Pipeline (Multi-file)"
ATTEMPTS=0; MAX_ATTEMPTS=60
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
  O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
  [ "$TK_COUNT" -gt "$POST_C_TICKETS" ] && [ "$O_COUNT" -gt "$POST_C_OBJECTIVES" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS, tickets=$TK_COUNT, objectives=$O_COUNT)"
  sleep 2
done

section "D4. Assert Multi-File Results"
THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "Multi threads exist" "$T_COUNT" "1"

TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TK_COUNT=$(echo "$TICKETS" | jq 'length')
assert_gte "Multi tickets exist" "$TK_COUNT" "$((POST_C_TICKETS + 3))"

OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives")
O_COUNT=$(echo "$OBJECTIVES" | jq 'length')
assert_gte "Multi objectives exist" "$O_COUNT" "$((POST_C_OBJECTIVES + 1))"

echo ""
echo "  Threads: $T_COUNT"
echo "  Tickets: $TK_COUNT"
echo "  Objectives: $O_COUNT"

# ===================================================================
# SUMMARY
# ===================================================================
section "FINAL SUMMARY"
FINAL_OBJECTIVES=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/objectives" | jq 'length')
FINAL_RESOURCES=$($CURL -s "$BASE_URL/api/resources" | jq --arg pid "$PROJECT_ID" '[.[] | select(.projectId == $pid)] | length')
FINAL_TICKETS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')
FINAL_THREADS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
echo ""
echo "  Project: $PROJECT_ID"
echo "  Part A (REST text):       objectives, resources via text pipeline"
echo "  Part B (TXT file upload): objectives, resources via file upload"
echo "  Part C (JSON file upload): objectives, resources via JSON upload"
echo "  Part D (Multi-file):      tickets + objectives from CSV + TXT"
echo ""
echo "  Final totals: $FINAL_THREADS threads, $FINAL_TICKETS tickets, $FINAL_OBJECTIVES objectives, $FINAL_RESOURCES resources"
echo "  Assertions: $PASS/$TOTAL passed"

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

if [ "$PASS" -eq "$TOTAL" ]; then
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 14 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC} — check pipeline and LLM output."
  exit 1
fi
