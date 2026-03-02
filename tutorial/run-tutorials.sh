#!/bin/bash
# JavaClaw Tutorial Runner — Orchestrates all 8 tutorials with parallel group support.
# Usage: bash tutorial/run-tutorials.sh [--group 1|2|1-2] [--port PORT] [--parallel]
set -euo pipefail

# ── Defaults ──
GROUPS="1-2"
PORT=8080
PARALLEL=false

while [ $# -gt 0 ]; do
  case "$1" in
    --group)   GROUPS="$2"; shift 2 ;;
    --port)    PORT="$2"; shift 2 ;;
    --parallel) PARALLEL=true; shift ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

BASE_URL="http://localhost:$PORT"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR=$(mktemp -d)

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'; BOLD='\033[1m'

# ── Group definitions ──
GROUP1_TUTORIALS=("01-intake-meeting-notes" "02-intake-jira-import" "03-intake-file-upload" "04-intake-conversations")
GROUP1_NAME="Intake Pipelines & File Upload"
GROUP2_TUTORIALS=("05-ask-claw" "06-agent-orchestration" "07-ask-claw-grounded" "08-ask-claw-resourcing")
GROUP2_NAME="Agent Orchestration & Ask Claw"

# ── Health check ──
echo -e "${CYAN}Health check: ${NC}GET $BASE_URL/api/projects"
HTTP_CODE=$(curl -sf -o /dev/null -w '%{http_code}' "$BASE_URL/api/projects" 2>/dev/null || echo "000")
if [ "$HTTP_CODE" != "200" ]; then
  echo -e "${RED}FATAL${NC} Server not reachable at $BASE_URL (HTTP $HTTP_CODE)"
  exit 1
fi
echo -e "${GREEN}  OK${NC} Server is running"

# ── Global LLM usage reset ──
curl -sf -X POST "$BASE_URL/api/logs/llm-usage/reset" -o /dev/null 2>/dev/null || true

# ── Run a single group ──
# Writes results to $LOG_DIR/group-N.results (one line per tutorial: EXIT_CODE DURATION NAME)
# Writes LLM snapshot to $LOG_DIR/group-N.llm
run_group() {
  local GROUP_NUM="$1"
  local -n TUTORIALS_REF="GROUP${GROUP_NUM}_TUTORIALS"
  local -n GROUP_NAME_REF="GROUP${GROUP_NUM}_NAME"

  # LLM snapshot before
  local LLM_BEFORE
  LLM_BEFORE=$(curl -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')
  local CALLS_BEFORE TOKENS_BEFORE
  CALLS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalCalls // 0')
  TOKENS_BEFORE=$(echo "$LLM_BEFORE" | jq -r '.totalTokens // 0')

  local RESULTS_FILE="$LOG_DIR/group-${GROUP_NUM}.results"
  > "$RESULTS_FILE"

  for TUTORIAL in "${TUTORIALS_REF[@]}"; do
    local SCRIPT="$SCRIPT_DIR/${TUTORIAL}.sh"
    if [ ! -f "$SCRIPT" ]; then
      echo "1 0 $TUTORIAL MISSING" >> "$RESULTS_FILE"
      continue
    fi

    local START_TIME
    START_TIME=$(date +%s)
    local EXIT_CODE=0
    BASE_URL="$BASE_URL" bash "$SCRIPT" > "$LOG_DIR/${TUTORIAL}.log" 2>&1 || EXIT_CODE=$?
    local END_TIME
    END_TIME=$(date +%s)
    local DURATION=$((END_TIME - START_TIME))
    echo "$EXIT_CODE $DURATION $TUTORIAL" >> "$RESULTS_FILE"
  done

  # LLM snapshot after
  local LLM_AFTER
  LLM_AFTER=$(curl -sf "$BASE_URL/api/logs/llm-usage" || echo '{}')
  local CALLS_AFTER TOKENS_AFTER
  CALLS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalCalls // 0')
  TOKENS_AFTER=$(echo "$LLM_AFTER" | jq -r '.totalTokens // 0')

  local LLM_FILE="$LOG_DIR/group-${GROUP_NUM}.llm"
  echo "$((CALLS_AFTER - CALLS_BEFORE)) $((TOKENS_AFTER - TOKENS_BEFORE))" > "$LLM_FILE"
}

# ── Determine which groups to run ──
RUN_GROUP1=false
RUN_GROUP2=false
case "$GROUPS" in
  1)    RUN_GROUP1=true ;;
  2)    RUN_GROUP2=true ;;
  1-2|all) RUN_GROUP1=true; RUN_GROUP2=true ;;
  *) echo "Invalid --group value: $GROUPS (use 1, 2, or 1-2)"; exit 1 ;;
esac

# ── Execute groups ──
GLOBAL_START=$(date +%s)

if $RUN_GROUP1 && $RUN_GROUP2 && $PARALLEL; then
  echo -e "\n${BOLD}Running groups 1 and 2 in parallel...${NC}"
  run_group 1 > "$LOG_DIR/group-1-stdout.log" 2>&1 &
  PID1=$!
  run_group 2 > "$LOG_DIR/group-2-stdout.log" 2>&1 &
  PID2=$!
  wait $PID1 || true
  wait $PID2 || true
else
  if $RUN_GROUP1; then
    echo -e "\n${BOLD}Running Group 1: $GROUP1_NAME${NC}"
    run_group 1
  fi
  if $RUN_GROUP2; then
    echo -e "\n${BOLD}Running Group 2: $GROUP2_NAME${NC}"
    run_group 2
  fi
fi

GLOBAL_END=$(date +%s)
GLOBAL_ELAPSED=$((GLOBAL_END - GLOBAL_START))

# ── Report ──
format_duration() {
  local SECS=$1
  if [ "$SECS" -ge 60 ]; then
    printf "%dm%02ds" $((SECS / 60)) $((SECS % 60))
  else
    printf "%ds" "$SECS"
  fi
}

TOTAL_PASS=0
TOTAL_FAIL=0
TOTAL_COUNT=0

echo ""
echo "============================================================"
echo " TUTORIAL TEST RESULTS"
echo "============================================================"

print_group_report() {
  local GROUP_NUM="$1"
  local -n GN_REF="GROUP${GROUP_NUM}_NAME"
  local RESULTS_FILE="$LOG_DIR/group-${GROUP_NUM}.results"
  local LLM_FILE="$LOG_DIR/group-${GROUP_NUM}.llm"

  if [ ! -f "$RESULTS_FILE" ]; then
    return
  fi

  local G_PASS=0 G_FAIL=0 G_TOTAL=0 G_TIME=0

  echo ""
  echo -e " ${BOLD}Group $GROUP_NUM: $GN_REF${NC}"

  while IFS=' ' read -r EXIT_CODE DURATION NAME REST; do
    G_TOTAL=$((G_TOTAL + 1))
    G_TIME=$((G_TIME + DURATION))
    if [ "$EXIT_CODE" -eq 0 ]; then
      G_PASS=$((G_PASS + 1))
      printf "   ${GREEN}PASS${NC}  %-35s %6s\n" "$NAME" "$(format_duration "$DURATION")"
    else
      G_FAIL=$((G_FAIL + 1))
      printf "   ${RED}FAIL${NC}  %-35s %6s\n" "$NAME" "$(format_duration "$DURATION")"
    fi
  done < "$RESULTS_FILE"

  local LLM_CALLS=0 LLM_TOKENS=0
  if [ -f "$LLM_FILE" ]; then
    read -r LLM_CALLS LLM_TOKENS < "$LLM_FILE"
  fi

  echo "   ──────────────────────────────────────"
  printf "   Group %d: %d/%d passed | %s | %d LLM calls | %s tokens\n" \
    "$GROUP_NUM" "$G_PASS" "$G_TOTAL" "$(format_duration $G_TIME)" "$LLM_CALLS" \
    "$(printf "%'d" "$LLM_TOKENS" 2>/dev/null || echo "$LLM_TOKENS")"

  TOTAL_PASS=$((TOTAL_PASS + G_PASS))
  TOTAL_FAIL=$((TOTAL_FAIL + G_FAIL))
  TOTAL_COUNT=$((TOTAL_COUNT + G_TOTAL))
}

$RUN_GROUP1 && print_group_report 1
$RUN_GROUP2 && print_group_report 2

# Global LLM totals
GLOBAL_LLM_CALLS=0
GLOBAL_LLM_TOKENS=0
for GN in 1 2; do
  LLM_FILE="$LOG_DIR/group-${GN}.llm"
  if [ -f "$LLM_FILE" ]; then
    read -r C T < "$LLM_FILE"
    GLOBAL_LLM_CALLS=$((GLOBAL_LLM_CALLS + C))
    GLOBAL_LLM_TOKENS=$((GLOBAL_LLM_TOKENS + T))
  fi
done

echo ""
echo "============================================================"
printf " TOTAL: %d/%d passed | %s elapsed | %d calls | %s tokens\n" \
  "$TOTAL_PASS" "$TOTAL_COUNT" "$(format_duration $GLOBAL_ELAPSED)" "$GLOBAL_LLM_CALLS" \
  "$(printf "%'d" "$GLOBAL_LLM_TOKENS" 2>/dev/null || echo "$GLOBAL_LLM_TOKENS")"
echo "============================================================"

# Show log paths for failed tutorials
if [ "$TOTAL_FAIL" -gt 0 ]; then
  echo ""
  echo -e "${YELLOW}Failed tutorial logs:${NC}"
  for GN in 1 2; do
    RESULTS_FILE="$LOG_DIR/group-${GN}.results"
    [ -f "$RESULTS_FILE" ] || continue
    while IFS=' ' read -r EXIT_CODE DURATION NAME REST; do
      if [ "$EXIT_CODE" -ne 0 ]; then
        echo "  $LOG_DIR/${NAME}.log"
      fi
    done < "$RESULTS_FILE"
  done
fi

# Cleanup
rm -rf "$LOG_DIR"

# Exit code
if [ "$TOTAL_FAIL" -gt 0 ]; then
  exit 1
else
  exit 0
fi
