#!/bin/bash
# JavaClaw Tutorial 02 — Intake: Meeting Notes
# Submits meeting notes through the intake pipeline (triage → thread creation → distill).
# Requires real LLM or will time out — designed for demos and documentation.
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

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
[ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ] || fail "Project creation failed"

# --- Load Meeting Notes ---
section "2. Load Meeting Notes"
NOTES=$(cat "$SCRIPT_DIR/sample-data/meeting-notes-payments.txt")
ok "Loaded Payment Gateway Architecture Review notes ($(echo "$NOTES" | wc -l) lines)"

# --- Submit to Intake Pipeline ---
section "3. Submit to Intake Pipeline"
PAYLOAD=$(jq -n --arg pid "$PROJECT_ID" --arg content "$NOTES" \
  '{projectId: $pid, content: $content}')
PIPELINE=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/intake/pipeline" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
HTTP_CODE=$(echo "$PIPELINE" | tail -1)
BODY=$(echo "$PIPELINE" | head -n -1)
[ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ] && ok "Pipeline started (HTTP $HTTP_CODE)" || fail "Pipeline failed (HTTP $HTTP_CODE)"
PIPELINE_ID=$(echo "$BODY" | jq -r '.pipelineId // .sessionId // empty')
[ -n "$PIPELINE_ID" ] && ok "Pipeline/Session ID: $PIPELINE_ID"

# --- Poll for Thread Creation ---
section "4. Waiting for Pipeline (threads to appear)"
echo "  The intake pipeline triages content and creates threads..."
echo "  This may take 30-60 seconds with a real LLM."
ATTEMPTS=0
MAX_ATTEMPTS=30
while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  THREADS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/threads")
  T_COUNT=$(echo "$THREADS" | jq 'length')
  if [ "$T_COUNT" -gt 0 ]; then
    ok "Found $T_COUNT thread(s) created by the pipeline"
    break
  fi
  ATTEMPTS=$((ATTEMPTS + 1))
  echo "  ... waiting ($ATTEMPTS/$MAX_ATTEMPTS)"
  sleep 2
done
[ "$T_COUNT" -gt 0 ] || warn "No threads created after ${MAX_ATTEMPTS} attempts — LLM may not be running"

# --- Show Created Threads ---
section "5. Created Threads"
echo "$THREADS" | jq -r '.[] | "  - \(.title) [\(.status)]"'

# --- Check Memories ---
section "6. Check Memories"
MEMORIES=$(curl -s "$BASE_URL/api/memories?projectId=$PROJECT_ID")
M_COUNT=$(echo "$MEMORIES" | jq 'length')
if [ "$M_COUNT" -gt 0 ]; then
  ok "Distiller created $M_COUNT memory(ies)"
  echo "$MEMORIES" | jq -r '.[] | "  [\(.scope)] \(.key): \(.content[:80])..."'
else
  warn "No memories yet — distillation may still be running"
fi

# --- Summary ---
section "7. Summary"
echo "  Project:  $PROJECT_ID"
echo "  Threads:  $T_COUNT"
echo "  Memories: $M_COUNT"
echo ""
echo "  The intake pipeline:"
echo "    1. Triaged the meeting notes (classified topics)"
echo "    2. Created a thread per topic"
echo "    3. Distilled key decisions and actions into memories"

echo -e "\n${GREEN}DONE${NC} — Tutorial 02 complete."
