#!/bin/bash
# Run JavaClaw scenario tests in a single JVM via multi-scenario mode
# This is ~10x faster than spawning separate JVMs per scenario
#
# Usage:
#   bash scenario-testing/run-scenarios.sh              # run ALL scenarios
#   bash scenario-testing/run-scenarios.sh --group 1    # run group 1 only (Foundations)
#   bash scenario-testing/run-scenarios.sh --group 2    # run group 2 only (Tools & Single Agents)
#   bash scenario-testing/run-scenarios.sh --group 3    # run group 3 only (Pipelines & Multi-Agent)
#   bash scenario-testing/run-scenarios.sh --group 4    # run group 4 only (E2E Stories & Context Assembly)
#   bash scenario-testing/run-scenarios.sh --group 1-2  # run groups 1 through 2
#   bash scenario-testing/run-scenarios.sh --group 1-3  # run groups 1 through 3
#   bash scenario-testing/run-scenarios.sh --port 19090 # override port (default 18080)
#   bash scenario-testing/run-scenarios.sh --group 2 --port 19090

SCENARIO_DIR="scenario-testing/scenarios"

# ── Group definitions ─────────────────────────────────────────────
# Group 1: Foundations — basic agent loop, simple tools, no DB assertions
GROUP_1=(
  scenario-general
  scenario-coder
  scenario-pm
  scenario-memory
  scenario-fs-tools
  scenario-git-tools
  scenario-http
  scenario-jbang-exec
  scenario-python-exec
  scenario-exec-time
  scenario-pm-tools
  scenario-intake-triage
  scenario-intake-pipeline
)

# Group 2: Tools & Single Agents — v2 assertions, specialist agents, multi-step tools
GROUP_2=(
  scenario-memory-v2
  scenario-fs-tools-v2
  scenario-pm-tools-v2
  scenario-coder-exec
  scenario-excel-weather
  scenario-checklist-agent
  scenario-objective-agent
  scenario-plan-agent
  scenario-reconcile-agent
  scenario-resource-agent
  scenario-thread-agent
  scenario-extraction-v2
  scenario-unallocated-resources
  scenario-unassigned-tickets
)

# Group 3: Pipelines & Multi-Agent — intake pipelines, agent chaining, re-intake
GROUP_3=(
  scenario-generalist-intake
  scenario-generalist-seeded
  scenario-all-agents-seeded
  scenario-story-1-intake
  scenario-story-1-reintake
  scenario-thread-update-on-reintake
  scenario-thread-intake-v2
  scenario-file-upload
  scenario-agent-merge
  scenario-thread-merge
  scenario-story-9-memory
  scenario-story-3-sprint-objectives
  scenario-story-4-resource-load
)

# Group 4: E2E Stories & Context Assembly — full pipelines, scheduled agents, ask-claw
GROUP_4=(
  scenario-story-1-full-pipeline
  scenario-story-2-alignment
  scenario-story-2-pipeline
  scenario-story-5-plan-creation
  scenario-story-6-checklist
  scenario-story-7-scheduled-reconcile
  scenario-story-8-ondemand-agents
  scenario-story-10-daily-reset
  scenario-ask-claw
  scenario-ask-claw-capacity
  scenario-ask-claw-resources
  scenario-ask-claw-sprint-health
  scenario-ask-claw-utilization
)

GROUP_NAMES=(
  [1]="Foundations"
  [2]="Tools & Single Agents"
  [3]="Pipelines & Multi-Agent"
  [4]="E2E Stories & Context Assembly"
)

# ── Parse arguments ───────────────────────────────────────────────
PORT=18080
GROUP_FILTER=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --group|-g)
      GROUP_FILTER="$2"
      shift 2
      ;;
    --port|-p)
      PORT="$2"
      shift 2
      ;;
    *)
      # Legacy: bare number as first arg = port
      if [[ "$1" =~ ^[0-9]+$ ]]; then
        PORT="$1"
      else
        echo "Unknown argument: $1"
        echo "Usage: $0 [--group 1|2|3|4|1-2|1-3|1-4] [--port PORT]"
        exit 1
      fi
      shift
      ;;
  esac
done

# ── Resolve which groups to run ───────────────────────────────────
START_GROUP=1
END_GROUP=4

if [ -n "$GROUP_FILTER" ]; then
  if [[ "$GROUP_FILTER" =~ ^([1-4])-([1-4])$ ]]; then
    START_GROUP=${BASH_REMATCH[1]}
    END_GROUP=${BASH_REMATCH[2]}
    if [ "$START_GROUP" -gt "$END_GROUP" ]; then
      echo "Invalid range: start ($START_GROUP) > end ($END_GROUP)"
      exit 1
    fi
  elif [[ "$GROUP_FILTER" =~ ^[1-4]$ ]]; then
    START_GROUP=$GROUP_FILTER
    END_GROUP=$GROUP_FILTER
  else
    echo "Invalid group: '$GROUP_FILTER'. Use 1, 2, 3, 4, or a range like 1-3."
    exit 1
  fi
fi

# ── Build scenario list from selected groups ──────────────────────
SCENARIOS=()
for G in $(seq $START_GROUP $END_GROUP); do
  case $G in
    1) SCENARIOS+=("${GROUP_1[@]}") ;;
    2) SCENARIOS+=("${GROUP_2[@]}") ;;
    3) SCENARIOS+=("${GROUP_3[@]}") ;;
    4) SCENARIOS+=("${GROUP_4[@]}") ;;
  esac
done

# ── Build --scenario flags ────────────────────────────────────────
SCENARIO_ARGS=""
for S in "${SCENARIOS[@]}"; do
  SCENARIO_ARGS="$SCENARIO_ARGS --scenario ${SCENARIO_DIR}/${S}.json"
done

# ── Print header ──────────────────────────────────────────────────
echo "============================================================"
if [ "$START_GROUP" -eq "$END_GROUP" ]; then
  echo " JavaClaw Scenario Tests — Group $START_GROUP: ${GROUP_NAMES[$START_GROUP]}"
else
  echo " JavaClaw Scenario Tests — Groups $START_GROUP-$END_GROUP"
fi
echo " Scenarios: ${#SCENARIOS[@]}  |  Port: $PORT"
echo "============================================================"

if [ "$START_GROUP" -eq 1 ] && [ "$END_GROUP" -eq 4 ]; then
  echo " Running ALL groups (53 scenarios)"
else
  for G in $(seq $START_GROUP $END_GROUP); do
    case $G in
      1) COUNT=${#GROUP_1[@]} ;;
      2) COUNT=${#GROUP_2[@]} ;;
      3) COUNT=${#GROUP_3[@]} ;;
      4) COUNT=${#GROUP_4[@]} ;;
    esac
    echo "  Group $G: ${GROUP_NAMES[$G]} ($COUNT scenarios)"
  done
fi
echo ""

# ── Run ───────────────────────────────────────────────────────────
jbang javaclaw.java --testMode --port $PORT $SCENARIO_ARGS
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
  echo ""
  echo "ALL SCENARIOS PASSED"
else
  echo ""
  echo "SOME SCENARIOS FAILED (exit code: $EXIT_CODE)"
fi

exit $EXIT_CODE
