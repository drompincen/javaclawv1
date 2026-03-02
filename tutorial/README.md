
# JavaClaw Tutorial

Hands-on walkthrough of the JavaClaw platform — an AI assistant for engineering managers.

## Prerequisites

| Requirement | Version | Check |
|-------------|---------|-------|
| Java | 21+ | `java -version` |
| JBang | 0.114+ | `jbang --version` |
| MongoDB | 7+ | `mongosh --eval "db.version()"` |
| curl | any | `curl --version` |
| jq | 1.6+ | `jq --version` |
| ANTHROPIC_API_KEY | required | `echo $ANTHROPIC_API_KEY` |

MongoDB must be running as a replica set (`rs0`). The Docker setup handles this:

```bash
docker compose up -d   # starts javaclaw-mongo with replica set
```

## Starting the Server

**Real LLM mode** (required for all tutorials):

```bash
jbang javaclaw.java --headless
```

The server starts on `http://localhost:8080` by default. Override with `--port 9090`.

**testMode guard:** In live mode, direct POST to domain entity endpoints (threads, tickets, objectives, resources, blindspots, phases, milestones, checklists) returns 403. All entity creation must flow through `POST /api/intake/pipeline`. In testMode (`--testmode`), all endpoints are open for scenario testing.

## Tutorial Map

All 8 tutorials require a real LLM (ANTHROPIC_API_KEY). They exercise the full intake pipeline and agent orchestration.

| # | Script | What You Learn | Group |
|---|--------|---------------|-------|
| 01 | [Intake: Meeting Notes](01-intake-meeting-notes.sh) | Intake pipeline, triage, thread creation, memories | 1 |
| 02 | [Intake: Jira Import](02-intake-jira-import.sh) | Intake-seeded resources, tickets, blindspots, objectives | 1 |
| 03 | [Intake: File Upload](03-intake-file-upload.sh) | CSV/JSON/TXT file upload and extraction | 1 |
| 04 | [Intake: Conversations](04-intake-conversations.sh) | Conversation upload with objectives and resources | 1 |
| 05 | [Ask Claw](05-ask-claw.sh) | Natural language Q&A over intake-seeded data | 2 |
| 06 | [Agent Orchestration](06-agent-orchestration.sh) | Multi-agent controller→specialist→checker with intake-seeded data | 2 |
| 07 | [Ask Claw: Grounded Answers](07-ask-claw-grounded.sh) | Capacity, workload, assignment questions grounded in data | 2 |
| 08 | [Ask Claw: Resourcing](08-ask-claw-resourcing.sh) | Resource utilization, sprint health with keyword validation | 2 |

### Group 1: Intake Pipelines & File Upload (tutorials 01-04)

Tests the full intake path: submit/upload → triage → generalist hydration → entity persistence. Covers meeting notes, Jira data (text + file upload), multi-format files (CSV/JSON/TXT), and conversation parsing with objectives and resources.

### Group 2: Agent Orchestration & Ask Claw (tutorials 05-08)

Multi-agent sessions with controller→specialist→checker orchestration, and Ask Claw grounded Q&A. All project data is seeded via the intake pipeline. Tests agent artifact creation and LLM answer quality with keyword validation.

## Running a Tutorial

```bash
# Start server first, then in another terminal:
bash tutorial/01-intake-meeting-notes.sh

# Override server URL:
BASE_URL=http://localhost:9090 bash tutorial/01-intake-meeting-notes.sh
```

Each script:
- Creates its own project (standalone, no cross-dependencies)
- Seeds all data through the intake pipeline (no direct CRUD)
- Prints colored section headers and success/fail indicators
- Ends with `DONE` or an error summary
- Uses `set -euo pipefail` for fail-fast behavior

## Running UI Tests

After running a tutorial group, verify the browser UI reflects the created entities:

```bash
# Group 1 (after tutorials 01-04):
bash tutorial/run-tutorial-ui-tests.sh --group 1 --port 8080

# Group 2 (after tutorials 05-08):
bash tutorial/run-tutorial-ui-tests.sh --group 2 --port 8080

# Both groups:
bash tutorial/run-tutorial-ui-tests.sh --group 1-2 --port 8080
```

Requires: node, npm, chromium/chrome + chromedriver.

## Sample Data

The `sample-data/` folder contains internally consistent test data:

| File | Content |
|------|---------|
| [meeting-notes-payments.txt](sample-data/meeting-notes-payments.txt) | Payment Gateway Architecture Review — 3 topics, 3 attendees |
| [meeting-notes-onboarding.txt](sample-data/meeting-notes-onboarding.txt) | Merchant Onboarding Design — 2 topics |
| [jira-export.txt](sample-data/jira-export.txt) | 7 Jira tickets (Joe assigned 5/7) |
| [jira-export.csv](sample-data/jira-export.csv) | CSV Jira export — 7 tickets |
| [jira-export.json](sample-data/jira-export.json) | JSON Jira REST API export — 7 tickets |
| [team-roster.json](sample-data/team-roster.json) | 3 team members with skills + capacity |
| [conversation-objectives-resources.txt](sample-data/conversation-objectives-resources.txt) | Sprint 42 planning — 3 objectives, 3 resources |
| [conversation-objectives-resources.json](sample-data/conversation-objectives-resources.json) | Same planning data in structured JSON |

All data references the same fictional teams working on payment gateways.

## Scenario Tests

For automated testing with mock LLM responses (no API key needed), see the scenario testing framework.
Scenario tests use `--testmode` which bypasses the testMode guard, allowing direct CRUD for data seeding.

```bash
bash scenario-testing/run-scenarios.sh --group 1 --port 18080
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused` | Start the server first: `jbang javaclaw.java --headless` |
| `jq: command not found` | Install jq: `apt install jq` or `brew install jq` |
| Pipeline timeout | Ensure `ANTHROPIC_API_KEY` is set — all tutorials require a real LLM |
| 403 on direct POST | Expected in live mode — use intake pipeline instead of direct CRUD |
| MongoDB connection error | Check `docker ps` — container `javaclaw-mongo` must be running |
| Port conflict | Use `--port 9090` and `BASE_URL=http://localhost:9090` |
