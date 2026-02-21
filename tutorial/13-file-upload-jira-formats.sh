#!/bin/bash
# JavaClaw Tutorial 13 — File Upload: Multi-Format Jira Import
# Uploads Jira data in 3 formats (CSV, JSON, TXT) via multipart POST and REST pipeline,
# then verifies that the LLM-powered intake pipeline creates the correct tickets, threads,
# and memories regardless of input format.
# Requires real LLM — the intake pipeline uses agents (triage, generalist) to parse content.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
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

# Snapshot baseline counts
BASELINE_TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')
BASELINE_THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
ok "Baseline: $BASELINE_TICKETS tickets, $BASELINE_THREADS threads"

# ===================================================================
# PART A: Upload CSV via multipart POST -> Pipeline -> Verify
# ===================================================================
section "PART A: CSV File Upload via Multipart POST"

section "A1. Upload CSV File"
UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$SCRIPT_DIR/sample-data/jira-export.csv")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Upload count" "$UPLOAD_COUNT" "1"

CSV_FILE_PATH=$(echo "$UPLOAD_RESP" | jq -r '.[0].filePath')
CSV_CONTENT_TYPE=$(echo "$UPLOAD_RESP" | jq -r '.[0].contentType')
assert_eq "Content type" "$CSV_CONTENT_TYPE" "csv"
ok "File path: $CSV_FILE_PATH"

section "A2. Verify Upload"
UPLOAD_ID=$(echo "$UPLOAD_RESP" | jq -r '.[0].uploadId')
UPLOAD_NAME=$(echo "$UPLOAD_RESP" | jq -r '.[0].fileName')
assert_contains "Filename" "$UPLOAD_NAME" "jira-export.csv"
ok "Upload ID: $UPLOAD_ID"

section "A3. Submit CSV to Pipeline"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg fp "$CSV_FILE_PATH" \
  '{projectId: $pid, content: "Process the uploaded CSV file — it contains Jira ticket data in CSV format with columns Key, Epic, Summary, Assignee, Status, Priority, SP.", filePaths: [$fp]}')
PIPELINE_RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "A4. Wait for Pipeline (CSV)"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -gt "$BASELINE_TICKETS" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for tickets ($ATTEMPTS/$MAX_ATTEMPTS, currently $TK_COUNT)"
  sleep 2
done

section "A5. Assert CSV Results"
THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "CSV threads exist" "$T_COUNT" "$((BASELINE_THREADS + 1))"

TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TK_COUNT=$(echo "$TICKETS" | jq 'length')
assert_gte "CSV tickets exist" "$TK_COUNT" "$((BASELINE_TICKETS + 3))"

# Check ticket content — LLM should have extracted key info from CSV
ALL_TITLES=$(echo "$TICKETS" | jq -r '.[].title' | tr '\n' ' ')
assert_contains "Has card payment handler" "$ALL_TITLES" "card"
assert_contains "Has bank transfer handler" "$ALL_TITLES" "bank"

echo ""
echo "  Threads ($T_COUNT):"
echo "$THREADS" | jq -r '.[] | "    [\(.lifecycle // .status)] \(.title)"'
echo "  Tickets ($TK_COUNT):"
echo "$TICKETS" | jq -r '.[] | "    [\(.status)] \(.title)"' | tail -10

# Update baseline after CSV
POST_CSV_TICKETS=$TK_COUNT
POST_CSV_THREADS=$T_COUNT

# ===================================================================
# PART B: Upload JSON via multipart POST -> Pipeline -> Verify
# ===================================================================
section "PART B: JSON File Upload via Multipart POST"

section "B1. Upload JSON File"
UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/api/intake/upload" \
  -F "projectId=$PROJECT_ID" \
  -F "files=@$SCRIPT_DIR/sample-data/jira-export.json")
UPLOAD_COUNT=$(echo "$UPLOAD_RESP" | jq 'length')
assert_eq "Upload count" "$UPLOAD_COUNT" "1"

JSON_FILE_PATH=$(echo "$UPLOAD_RESP" | jq -r '.[0].filePath')
JSON_CONTENT_TYPE=$(echo "$UPLOAD_RESP" | jq -r '.[0].contentType')
assert_eq "Content type" "$JSON_CONTENT_TYPE" "json"

section "B2. Submit JSON to Pipeline"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg fp "$JSON_FILE_PATH" \
  '{projectId: $pid, content: "Process the uploaded JSON file — it contains a Jira REST API export with ticket fields including summary, status, priority, assignee, story points, and parent epic.", filePaths: [$fp]}')
PIPELINE_RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "B3. Wait for Pipeline (JSON)"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -gt "$POST_CSV_TICKETS" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for tickets ($ATTEMPTS/$MAX_ATTEMPTS, currently $TK_COUNT)"
  sleep 2
done

section "B4. Assert JSON Results"
THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "JSON threads exist" "$T_COUNT" "$POST_CSV_THREADS"

TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TK_COUNT=$(echo "$TICKETS" | jq 'length')
assert_gte "JSON tickets exist" "$TK_COUNT" "$((POST_CSV_TICKETS + 3))"

echo ""
echo "  Threads ($T_COUNT):"
echo "$THREADS" | jq -r '.[] | "    [\(.lifecycle // .status)] \(.title)"'
echo "  Tickets ($TK_COUNT):"
echo "$TICKETS" | jq -r '.[] | "    [\(.status)] \(.title)"' | tail -10

# Update baseline after JSON
POST_JSON_TICKETS=$TK_COUNT
POST_JSON_THREADS=$T_COUNT

# ===================================================================
# PART C: Plain text Jira data via REST pipeline (no file upload)
# ===================================================================
section "PART C: Plain Text Jira Data via REST Pipeline"

section "C1. Submit Text Content to Pipeline"
JIRA_TEXT=$(cat "$SCRIPT_DIR/sample-data/jira-export.txt")
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$JIRA_TEXT" \
  '{projectId: $pid, content: $content}')
PIPELINE_RESP=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE_RESP" | tail -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || warn "Pipeline HTTP $HTTP_CODE"

section "C2. Wait for Pipeline (Text)"
ATTEMPTS=0; MAX_ATTEMPTS=45
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
  TK_COUNT=$(echo "$TICKETS" | jq 'length')
  [ "$TK_COUNT" -gt "$POST_JSON_TICKETS" ] && break
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting for tickets ($ATTEMPTS/$MAX_ATTEMPTS, currently $TK_COUNT)"
  sleep 2
done

section "C3. Assert Text Results"
THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
T_COUNT=$(echo "$THREADS" | jq 'length')
assert_gte "Text threads exist" "$T_COUNT" "$POST_JSON_THREADS"

TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets")
TK_COUNT=$(echo "$TICKETS" | jq 'length')
assert_gte "Text tickets exist" "$TK_COUNT" "$((POST_JSON_TICKETS + 3))"

echo ""
echo "  Threads ($T_COUNT):"
echo "$THREADS" | jq -r '.[] | "    [\(.lifecycle // .status)] \(.title)"'
echo "  Tickets ($TK_COUNT):"
echo "$TICKETS" | jq -r '.[] | "    [\(.status)] \(.title)"' | tail -10

# ===================================================================
# SUMMARY
# ===================================================================
section "SUMMARY"
FINAL_TICKETS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/tickets" | jq 'length')
FINAL_THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads" | jq 'length')
FINAL_MEMORIES=$(curl -s "$BASE_URL/api/memories?projectId=$PROJECT_ID" | jq 'length')
echo "  Project: $PROJECT_ID"
echo "  Threads:  $FINAL_THREADS (was $BASELINE_THREADS)"
echo "  Tickets:  $FINAL_TICKETS (was $BASELINE_TICKETS)"
echo "  Memories: $FINAL_MEMORIES"
echo "  3 formats tested: CSV upload, JSON upload, text REST pipeline"
echo ""
echo "  Assertions: $PASS/$TOTAL passed"

if [ "$PASS" -eq "$TOTAL" ]; then
  echo -e "\n${GREEN}ALL ASSERTIONS PASSED${NC} — Tutorial 13 complete."
else
  echo -e "\n${YELLOW}$((TOTAL - PASS)) assertion(s) failed${NC} — check pipeline and LLM output."
  exit 1
fi
