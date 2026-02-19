# JavaClaw Scenario Tests

Automated tests that validate every tool, agent, and workflow using mock LLM responses.

## How Scenario Tests Work

1. The server starts in `--testmode` with `--scenario <file.json>` flags
2. Each scenario file defines: seed data, mock LLM responses (with tool calls), and HTTP assertions
3. The `ScenarioRunner` replays mock responses instead of calling the real LLM
4. After the agent loop completes, HTTP assertions verify the expected state

## Running All Scenarios

```bash
# From project root — runs all 41 scenarios in a single JVM (~2 min)
bash run-scenarios.sh

# Override port (default 18080):
bash run-scenarios.sh 19090
```

## Running a Single Scenario

```bash
jbang javaclaw.java --testmode --port 18080 \
  --scenario runtime/src/test/resources/scenario-general.json
```

## Scenario File Format

```json
{
  "name": "scenario-name",
  "description": "What this scenario tests",
  "seed": {
    "projects": [...],
    "threads": [...],
    "tickets": [...],
    "resources": [...],
    "objectives": [...]
  },
  "exchanges": [
    {
      "userMessage": "User input text",
      "mockResponse": "LLM text response with tool_use blocks"
    }
  ],
  "httpAssertions": [
    {
      "method": "GET",
      "path": "/api/projects/{projectId}/threads",
      "assertions": [
        { "jsonPath": "$.length()", "operator": "gte", "value": 2 }
      ]
    }
  ]
}
```

## Full Scenario Table

| # | Scenario File | Category | What It Tests |
|---|--------------|----------|---------------|
| 1 | `scenario-general` | Agent | Basic controller agent conversation |
| 2 | `scenario-coder` | Agent | Coder agent with code generation |
| 3 | `scenario-pm` | Agent | PM agent project management |
| 4 | `scenario-pm-tools` | Tools | PM tools: create thread, ticket, idea |
| 5 | `scenario-memory` | Tools | Memory store/recall/delete |
| 6 | `scenario-coder-exec` | Tools | Code execution (JBang, Python, shell) |
| 7 | `scenario-fs-tools` | Tools | File system: read, write, list, search |
| 8 | `scenario-git-tools` | Tools | Git: status, diff, commit |
| 9 | `scenario-http` | Tools | HTTP GET tool |
| 10 | `scenario-jbang-exec` | Tools | JBang Java execution |
| 11 | `scenario-exec-time` | Tools | Execution time tracking |
| 12 | `scenario-python-exec` | Tools | Python script execution |
| 13 | `scenario-excel-weather` | Tools | Excel parsing tool |
| 14 | `scenario-extraction-v2` | Pipeline | LLM extraction: tickets, objectives from threads |
| 15 | `scenario-pm-tools-v2` | Tools | PM tools v2: checklists, phases, milestones |
| 16 | `scenario-memory-v2` | Tools | Memory v2: scoped recall, TTL behavior |
| 17 | `scenario-fs-tools-v2` | Tools | File system v2: advanced search patterns |
| 18 | `scenario-thread-intake-v2` | Pipeline | Thread intake with classification |
| 19 | `scenario-thread-agent` | Agent | Thread management agent |
| 20 | `scenario-objective-agent` | Agent | Objective creation and coverage |
| 21 | `scenario-checklist-agent` | Agent | Checklist creation from threads |
| 22 | `scenario-intake-triage` | Pipeline | Content triage classification |
| 23 | `scenario-plan-agent` | Agent | Plan artifact generation |
| 24 | `scenario-reconcile-agent` | Agent | Reconciliation with delta packs |
| 25 | `scenario-resource-agent` | Agent | Resource assignment and capacity |
| 26 | `scenario-intake-pipeline` | Pipeline | Full intake pipeline: triage → threads → distill |
| 27 | `scenario-story-1-intake` | Story | Story 1: Initial meeting notes intake |
| 28 | `scenario-story-1-reintake` | Story | Story 1: Re-intake with updated notes |
| 29 | `scenario-story-1-full-pipeline` | Story | Story 1: Complete intake pipeline end-to-end |
| 30 | `scenario-story-2-alignment` | Story | Story 2: Thread alignment after intake |
| 31 | `scenario-story-2-pipeline` | Story | Story 2: Pipeline with Jira import |
| 32 | `scenario-story-3-sprint-objectives` | Story | Story 3: Sprint objective creation |
| 33 | `scenario-story-4-resource-load` | Story | Story 4: Resource load detection |
| 34 | `scenario-story-5-plan-creation` | Story | Story 5: Plan artifact generation |
| 35 | `scenario-story-6-checklist` | Story | Story 6: ORR checklist creation |
| 36 | `scenario-story-7-scheduled-reconcile` | Story | Story 7: Scheduled reconciliation |
| 37 | `scenario-story-8-ondemand-agents` | Story | Story 8: On-demand agent dispatch |
| 38 | `scenario-story-9-memory` | Story | Story 9: Cross-scope memory usage |
| 39 | `scenario-story-10-daily-reset` | Story | Story 10: Daily reset and cleanup |
| 40 | `scenario-thread-update-on-reintake` | Pipeline | Thread update during re-intake |
| 41 | `scenario-ask-claw` | Agent | Ask Claw Q&A with context assembly |

## Adding a New Scenario

1. Create `runtime/src/test/resources/scenario-<name>.json`
2. Define seed data, mock exchanges, and HTTP assertions
3. Add the scenario name to `run-scenarios.sh` SCENARIOS array
4. Run: `bash run-scenarios.sh`

## Scenario Categories

| Category | Count | Description |
|----------|-------|-------------|
| Agent | 8 | Agent conversation and orchestration |
| Tools | 12 | Individual tool invocation |
| Pipeline | 5 | Multi-step pipelines (intake, extraction) |
| Story | 10 | End-to-end user stories |
| **Total** | **41** | |
