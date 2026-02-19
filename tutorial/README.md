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

MongoDB must be running as a replica set (`rs0`). The Docker setup handles this:

```bash
docker compose up -d   # starts javaclaw-mongo with replica set
```

## Starting the Server

**Real LLM mode** (requires `ANTHROPIC_API_KEY`):

```bash
jbang javaclaw.java --headless
```

**Test mode** (no LLM, uses mock responses — best for tutorials 01, 04-10):

```bash
jbang javaclaw.java --headless --testmode
```

The server starts on `http://localhost:8080` by default. Override with `--port 9090`.

## Tutorial Map

| # | Script | What You Learn | LLM Required? |
|---|--------|---------------|---------------|
| 01 | [Quickstart](01-quickstart.sh) | Health check, projects, sessions, messages | No |
| 02 | [Intake: Meeting Notes](02-intake-meeting-notes.sh) | Intake pipeline, triage, thread creation | Yes |
| 03 | [Jira Import](03-jira-import.sh) | Resources, tickets, delta packs, blindspots | Yes |
| 04 | [Sprint Planning](04-sprint-planning.sh) | Objectives, coverage, capacity analysis | No |
| 05 | [Reconciliation](05-reconciliation.sh) | Delta packs, drift detection, blindspots | No |
| 06 | [Scheduling](06-scheduling.sh) | CRON schedules, executions, triggers | No |
| 07 | [Ask Claw](07-ask-claw.sh) | Natural language Q&A over project data | Yes |
| 08 | [Memory & Context](08-memory-and-context.sh) | Store/recall/delete memories across scopes | No |
| 09 | [Checklists & Plans](09-checklists-and-plans.sh) | Phases, milestones, checklists, plan artifacts | No |
| 10 | [Code & Tools](10-code-and-tools.sh) | Direct tool invocation: JBang, Python, shell | No |

**LLM Required = Yes** means the script submits content through the AI pipeline. In `--testmode`, these scripts will time out waiting for LLM responses. They are designed for real demos or reading as documentation.

**LLM Required = No** means the script only calls CRUD endpoints and works in any mode.

## Running a Tutorial

```bash
# Start server first, then in another terminal:
bash tutorial/01-quickstart.sh

# Override server URL:
BASE_URL=http://localhost:9090 bash tutorial/01-quickstart.sh
```

Each script:
- Creates its own project (standalone, no cross-dependencies)
- Prints colored section headers and success/fail indicators
- Ends with `DONE` or an error summary
- Uses `set -euo pipefail` for fail-fast behavior

## Sample Data

The `sample-data/` folder contains internally consistent test data:

| File | Content |
|------|---------|
| [meeting-notes-kyc.txt](sample-data/meeting-notes-kyc.txt) | KYC Architecture Review — 3 topics, 3 attendees |
| [meeting-notes-onboarding.txt](sample-data/meeting-notes-onboarding.txt) | Client Onboarding Design — 2 topics |
| [jira-export.txt](sample-data/jira-export.txt) | 7 Jira tickets (Joe assigned 5/7) |
| [team-roster.json](sample-data/team-roster.json) | 3 team members with skills + capacity |

All data references the same fictional team (Joe, Alice, Bob) working on a KYC platform.

## Scenario Tests

For automated testing with mock LLM responses, see [scenarios/README.md](scenarios/README.md).
The scenario framework validates all 41 scenario files covering every tool and agent workflow.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused` | Start the server first: `jbang javaclaw.java --headless` |
| `jq: command not found` | Install jq: `apt install jq` or `brew install jq` |
| Pipeline timeout (02, 03) | These need real LLM — run without `--testmode` |
| MongoDB connection error | Check `docker ps` — container `javaclaw-mongo` must be running |
| Port conflict | Use `--port 9090` and `BASE_URL=http://localhost:9090` |
