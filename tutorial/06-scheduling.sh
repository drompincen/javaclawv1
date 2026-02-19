#!/bin/bash
# JavaClaw Tutorial 06 — Scheduling
# Creates CRON schedules, lists executions, triggers immediate runs.
# Works in any mode (all CRUD).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_NAME="Tutorial KYC Platform"
PROJECT_ID=$(curl -s "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$(curl -s -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"KYC Platform tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Create CRON Schedule ---
section "2. Create Daily Reconciliation Schedule"
SCHED1=$(curl -s -X POST "$BASE_URL/api/schedules" \
  -H 'Content-Type: application/json' \
  -d "{
    \"agentId\": \"controller\",
    \"enabled\": true,
    \"timezone\": \"America/New_York\",
    \"scheduleType\": \"CRON\",
    \"cronExpr\": \"0 9 * * MON-FRI\",
    \"projectScope\": \"SPECIFIC\",
    \"projectId\": \"$PROJECT_ID\"
  }")
S1_ID=$(echo "$SCHED1" | jq -r '.scheduleId')
ok "Schedule: Daily 9AM Mon-Fri ($S1_ID)"
echo "$SCHED1" | jq '{scheduleId, cronExpr, enabled, nextExecution}'

# --- Create Interval Schedule ---
section "3. Create Hourly Check Schedule"
SCHED2=$(curl -s -X POST "$BASE_URL/api/schedules" \
  -H 'Content-Type: application/json' \
  -d '{
    "agentId": "controller",
    "enabled": true,
    "timezone": "UTC",
    "scheduleType": "INTERVAL",
    "intervalMinutes": 60,
    "projectScope": "GLOBAL"
  }')
S2_ID=$(echo "$SCHED2" | jq -r '.scheduleId')
ok "Schedule: Every 60 min ($S2_ID)"

# --- List Schedules ---
section "4. List All Schedules"
SCHEDULES=$(curl -s "$BASE_URL/api/schedules")
echo "$SCHEDULES" | jq -r '.[] | "  [\(.scheduleType)] \(.cronExpr // (.intervalMinutes | tostring) + "min") — enabled: \(.enabled)"'

# --- Check Future Executions ---
section "5. Future Executions"
FUTURE=$(curl -s "$BASE_URL/api/executions/future")
F_COUNT=$(echo "$FUTURE" | jq 'length')
ok "$F_COUNT future execution(s) scheduled"
echo "$FUTURE" | jq -r '.[:5][] | "  \(.scheduledAt) — \(.agentId) [\(.execStatus)]"' 2>/dev/null || echo "  (none visible yet)"

# --- Trigger Immediate Execution ---
section "6. Trigger Immediate Execution"
TRIGGER=$(curl -s -X POST "$BASE_URL/api/executions/trigger" \
  -H 'Content-Type: application/json' \
  -d "{\"agentId\": \"controller\", \"projectId\": \"$PROJECT_ID\"}")
ok "Triggered immediate execution"
echo "$TRIGGER" | jq '{agentId, projectId, scheduledAt}' 2>/dev/null || echo "  $TRIGGER"

# --- Check Past Executions ---
section "7. Past Executions"
sleep 2  # brief wait for execution to register
PAST=$(curl -s "$BASE_URL/api/executions/past?projectId=$PROJECT_ID")
P_COUNT=$(echo "$PAST" | jq 'length' 2>/dev/null || echo "0")
ok "$P_COUNT past execution(s)"
echo "$PAST" | jq -r '.[:5][] | "  \(.startedAt // .scheduledAt) — \(.resultStatus // .execStatus) (\(.durationMs // 0)ms)"' 2>/dev/null || true

# --- Disable Schedule ---
section "8. Disable Schedule"
curl -s -X PUT "$BASE_URL/api/schedules/$S1_ID" \
  -H 'Content-Type: application/json' \
  -d '{"enabled": false}' > /dev/null
ok "Schedule $S1_ID disabled"

# --- Cleanup ---
section "9. Cleanup Schedules"
curl -s -X DELETE "$BASE_URL/api/schedules/$S1_ID" > /dev/null
curl -s -X DELETE "$BASE_URL/api/schedules/$S2_ID" > /dev/null
ok "Both schedules deleted"

# --- Summary ---
section "10. Summary"
echo "  Scheduling supports:"
echo "    - CRON expressions (e.g., '0 9 * * MON-FRI')"
echo "    - Fixed intervals (e.g., every 60 minutes)"
echo "    - Immediate triggers for on-demand runs"
echo "    - Per-project or global scope"
echo "    - Future execution preview and cancellation"

echo -e "\n${GREEN}DONE${NC} — Tutorial 06 complete."
