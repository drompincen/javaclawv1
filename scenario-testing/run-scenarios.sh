#!/bin/bash
# Run all JavaClaw scenario tests in a single JVM via multi-scenario mode
# This is ~10x faster than spawning separate JVMs per scenario

SCENARIO_DIR="scenario-testing/scenarios"

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
  scenario-pm-tools-v2
  scenario-memory-v2
  scenario-fs-tools-v2
  scenario-thread-intake-v2
  scenario-thread-agent
  scenario-objective-agent
  scenario-checklist-agent
  scenario-intake-triage
  scenario-plan-agent
  scenario-reconcile-agent
  scenario-resource-agent
  scenario-intake-pipeline
  scenario-ask-claw
  scenario-ask-claw-capacity
  scenario-ask-claw-resources
  scenario-ask-claw-utilization
  scenario-ask-claw-sprint-health
  scenario-thread-merge
  scenario-agent-merge
  scenario-thread-update-on-reintake
  scenario-unassigned-tickets
  scenario-unallocated-resources
  scenario-all-agents-seeded
  scenario-generalist-seeded
  scenario-story-1-intake
  scenario-story-1-reintake
  scenario-story-1-full-pipeline
  scenario-story-2-alignment
  scenario-story-2-pipeline
  scenario-story-3-sprint-objectives
  scenario-story-4-resource-load
  scenario-story-5-plan-creation
  scenario-story-6-checklist
  scenario-story-7-scheduled-reconcile
  scenario-story-8-ondemand-agents
  scenario-story-9-memory
  scenario-story-10-daily-reset
  scenario-file-upload
  scenario-generalist-intake
)

PORT=${1:-18080}

# Build --scenario flags for each scenario file
SCENARIO_ARGS=""
for S in "${SCENARIOS[@]}"; do
  SCENARIO_ARGS="$SCENARIO_ARGS --scenario ${SCENARIO_DIR}/${S}.json"
done

echo "============================================================"
echo " JavaClaw Multi-Scenario Test Runner — ${#SCENARIOS[@]} scenarios"
echo " Port: $PORT"
echo "============================================================"
echo ""

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
