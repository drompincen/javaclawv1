#!/bin/bash
# Run all JavaClaw scenario tests in a single JVM via multi-scenario mode
# This is ~10x faster than spawning separate JVMs per scenario

SCENARIO_DIR="runtime\\src\\test\\resources"

SCENARIOS=(
  scenario-general
  scenario-coder
  scenario-pm
  scenario-pm-tools
  scenario-memory
  scenario-coder-exec
  scenario-fs-tools
  scenario-git-tools
  scenario-http
  scenario-jbang-exec
  scenario-exec-time
  scenario-python-exec
  scenario-excel-weather
  scenario-extraction-v2
  scenario-thread-agent
  scenario-objective-agent
  scenario-checklist-agent
  scenario-intake-triage
  scenario-plan-agent
  scenario-reconcile-agent
  scenario-resource-agent
  scenario-intake-pipeline
)

PORT=${1:-18080}

# Build --scenario flags for each scenario file
SCENARIO_ARGS=""
for S in "${SCENARIOS[@]}"; do
  SCENARIO_ARGS="$SCENARIO_ARGS --scenario ${SCENARIO_DIR}\\${S}.json"
done

echo "============================================================"
echo " JavaClaw Multi-Scenario Test Runner — ${#SCENARIOS[@]} scenarios"
echo " Port: $PORT"
echo "============================================================"
echo ""

cmd.exe /c "jbang.cmd javaclaw.java --testmode --port $PORT $SCENARIO_ARGS"
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
  echo ""
  echo "ALL SCENARIOS PASSED"
else
  echo ""
  echo "SOME SCENARIOS FAILED (exit code: $EXIT_CODE)"
fi

exit $EXIT_CODE
