#!/bin/bash
# Run browser UI tests for a tutorial group.
#
# Usage:
#   bash tutorial/run-tutorial-ui-tests.sh --group 1 --port 18080
#   bash tutorial/run-tutorial-ui-tests.sh --group 1 --port 18080 --project-id <PID>
#   bash tutorial/run-tutorial-ui-tests.sh --group 1-2 --port 18080
#
# Requires: node, npm, chromium/chrome + chromedriver
# Server must already be running on the specified port.
set -euo pipefail

GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; DIM='\033[90m'; NC='\033[0m'

TESTS_DIR="gateway/src/main/resources/static/tests"
TEST_FILE="tutorial-ui.test.mjs"

# ── Parse arguments ───────────────────────────────────────────────
PORT=18080
GROUP=""
PROJECT_ID=""
EXTRA_ARGS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --group|-g)
      GROUP="$2"
      shift 2
      ;;
    --port|-p)
      PORT="$2"
      shift 2
      ;;
    --project-id)
      PROJECT_ID="$2"
      shift 2
      ;;
    *)
      echo -e "${RED}Unknown argument: $1${NC}"
      echo "Usage: $0 --group 1|2|1-2 [--port PORT] [--project-id PID]"
      exit 1
      ;;
  esac
done

if [ -z "$GROUP" ]; then
  echo -e "${RED}--group is required (1, 2, or range like 1-2)${NC}"
  echo "Usage: $0 --group 1|2|1-2 [--port PORT] [--project-id PID]"
  exit 1
fi

# ── Resolve group range ──────────────────────────────────────────
START_GROUP="$GROUP"
END_GROUP="$GROUP"

if [[ "$GROUP" =~ ^([1-2])-([1-2])$ ]]; then
  START_GROUP=${BASH_REMATCH[1]}
  END_GROUP=${BASH_REMATCH[2]}
  if [ "$START_GROUP" -gt "$END_GROUP" ]; then
    echo -e "${RED}Invalid range: start ($START_GROUP) > end ($END_GROUP)${NC}"
    exit 1
  fi
elif [[ ! "$GROUP" =~ ^[1-2]$ ]]; then
  echo -e "${RED}Invalid group: '$GROUP'. Use 1, 2, or a range like 1-2.${NC}"
  exit 1
fi

# ── Ensure npm dependencies ──────────────────────────────────────
echo -e "${DIM}Checking npm dependencies in ${TESTS_DIR}...${NC}"
if [ ! -d "${TESTS_DIR}/node_modules" ]; then
  echo -e "${CYAN}Installing npm dependencies...${NC}"
  (cd "${TESTS_DIR}" && npm install)
fi

# ── Build project-id flag ────────────────────────────────────────
PID_FLAG=""
if [ -n "$PROJECT_ID" ]; then
  PID_FLAG="--project-id $PROJECT_ID"
fi

# ── Run tests for each group ─────────────────────────────────────
TOTAL_EXIT=0

echo ""
echo "============================================================"
echo " Tutorial UI Tests — Group(s) ${START_GROUP}-${END_GROUP} | Port ${PORT}"
echo "============================================================"
echo ""

for G in $(seq "$START_GROUP" "$END_GROUP"); do
  echo -e "${CYAN}── Running UI tests for group ${G} ──${NC}"
  if node "${TESTS_DIR}/${TEST_FILE}" --group "$G" --port "$PORT" $PID_FLAG; then
    echo -e "${GREEN}Group ${G} UI tests passed.${NC}"
  else
    echo -e "${RED}Group ${G} UI tests failed.${NC}"
    TOTAL_EXIT=1
  fi
  echo ""
done

if [ "$TOTAL_EXIT" -eq 0 ]; then
  echo -e "${GREEN}ALL UI TESTS PASSED${NC}"
else
  echo -e "${RED}SOME UI TESTS FAILED${NC}"
fi

exit $TOTAL_EXIT
