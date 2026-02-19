# JavaClaw

**A continuous project intelligence engine for engineering managers.** JavaClaw is a multi-agent orchestration platform built with Java 21, Spring AI, LangGraph4j, and MongoDB. It turns raw meeting notes, Jira exports, and Confluence designs into structured threads, sprint objectives, capacity reports, and operational readiness checklists — then keeps them honest through scheduled reconciliation.

## The Story

David opens JavaClaw early in the morning. He pastes his meeting notes — a mix of architecture decisions, open questions, and action items — into the Intake panel and hits enter. Within seconds, the system organizes the chaos into structured threads: "KYC-SVC Phase 1 Architecture", "Evidence Service Discussion", "Operational Readiness (ORR)". Each thread has a clean title, grouped related thoughts, and merged duplicates.

Later, he pastes a Jira export, a Confluence design page, and a Smartsheet plan. The system layers intelligence: tickets are grouped, phases are extracted, objectives are synthesized, and a Delta Pack appears showing "Missing Epic: Evidence Service", "Milestone drift: March 8 -> March 15", "Owner mismatch: Alice vs Bob", "Unmapped tickets: 6". The system isn't just organizing — it's challenging inconsistencies across sources.

The next morning, David asks "What are our objectives for this sprint?" The system computes — not guesses — and responds: "Deliver KYC-SVC facade and tooling — 72% covered. Prepare operational readiness — 40% covered, high risk." He asks "Who is working on what?" and learns Alice is overloaded while Bob has capacity. He asks "Create a plan from the current threads" and a three-phase execution path emerges with entry conditions, exit criteria, and linked milestones.

Days pass. The scheduler triggers the Reconcile Agent overnight. When David returns, new insights appear: a milestone slipped, a ticket was added without an objective, a duplicate thread was created. The system kept the project honest without him.

David never "manages tickets." He pastes information, asks questions, triggers intent. The system structures, aligns, challenges, and executes.

## Architecture

```
                    ┌──────────────────────────────────────────┐
                    │     Web Cockpit (Bloomberg-style UI)      │
                    │  Green-on-black, keyboard-driven panels   │
                    │  Intake | Threads | Objectives | Schedule │
                    └─────────────────────┬────────────────────┘
                                          │ REST + WebSocket
                                          ▼
                    ┌──────────────────────────────────────────┐
                    │              gateway                      │
                    │   24 REST controllers + WebSocket         │
                    │   Cockpit static assets (HTML/JS/CSS)     │
                    └─────────────────────┬────────────────────┘
                                          │
                    ┌──────────────────────┴────────────────────┐
                    │                runtime                     │
                    │   Multi-agent orchestration engine         │
                    │   Controller → Specialist → Checker loop   │
                    │   Intake pipeline, scheduler, distiller    │
                    │   LLM integration (Anthropic / OpenAI)     │
                    └──────────┬──────────┬──────────┬──────────┘
                               │          │          │
              ┌────────────────┤          │          ├────────────────┐
              ▼                ▼          ▼          ▼                ▼
     ┌──────────────┐  ┌──────────┐  ┌────────┐  ┌───────────────────┐
     │  persistence  │  │ protocol │  │ tools  │  │     MongoDB       │
     │  32 documents │  │  DTOs    │  │ 46 SPI │  │   Replica Set     │
     │  35 repos     │  │  Enums   │  │ tools  │  │   32 collections  │
     │  Change       │  │  Events  │  │        │  │   Change Streams  │
     │  Streams      │  │  WS msgs │  │        │  │                   │
     └───────────────┘  └──────────┘  └────────┘  └───────────────────┘
```

### Module Overview

| Module | Purpose |
|---|---|
| **protocol** | Shared DTOs (Java records), enums (AgentRole, SessionStatus, TicketStatus, MilestoneStatus, etc.), event types (40+), WebSocket message contracts. Pure Java — no Spring dependencies. |
| **persistence** | 32 MongoDB document classes, 35 repository interfaces, `ChangeStreamService` for real-time reactive streaming. Covers agents, projects, threads, tickets, objectives, phases, milestones, checklists, resources, delta packs, blindspots, schedules, and more. |
| **runtime** | Multi-agent engine: `AgentLoop` orchestrates sessions, `AgentGraphBuilder` runs the controller→specialist→checker loop, `IntakePipelineService` chains triage→thread creation→distillation, scheduler engine for automated agent runs, scenario test framework with 36 E2E tests. |
| **tools** | 46 built-in tools loaded via Java SPI: file I/O, shell, git, JBang, Python, Excel, memory, HTTP, project management (tickets, phases, milestones, objectives, checklists, resources, delta packs, blindspots). |
| **gateway** | Spring Boot REST + WebSocket server. 24 controllers for all domain objects. Serves the web cockpit UI as static assets. |

## Multi-Agent System

JavaClaw uses a **controller → specialist → checker** orchestration pattern. The controller analyzes the user's task and routes to the best specialist. The specialist executes using tools and LLM. The checker validates the result — if it fails, the loop retries (max 3 retries). For pipeline operations (forced agent routing), the controller and checker are bypassed entirely.

### 15 Built-in Agents

| Agent | Role | Purpose | Key Tools |
|---|---|---|---|
| `controller` | CONTROLLER | Routes tasks to the best specialist | `*` (all) |
| `coder` | SPECIALIST | Writes code, runs shell commands, reads/writes files | `*` (all) |
| `reviewer` | CHECKER | Validates output quality and correctness | read_file, search_files, shell_exec, memory |
| `pm` | SPECIALIST | Project management — tickets, planning, stakeholder tracking | create_ticket, create_idea, memory, excel |
| `generalist` | SPECIALIST | General questions, brainstorming, advice | memory, read_file |
| `reminder` | SPECIALIST | Natural language timer/reminder creation | memory, read_file |
| `distiller` | SPECIALIST | Distills completed sessions into persistent memories | memory |
| `thread-extractor` | SPECIALIST | Extracts action items, ideas, and tickets from threads | read_thread_messages, create_ticket, create_idea |
| `thread-agent` | SPECIALIST | Creates, renames, merges threads; attaches evidence | create_thread, rename_thread, merge_threads |
| `objective-agent` | SPECIALIST | Derives sprint objectives, computes ticket coverage | compute_coverage, update_objective |
| `checklist-agent` | SPECIALIST | Generates ORR and release readiness checklists | create_checklist, checklist_progress |
| `intake-triage` | SPECIALIST | Classifies intake content and dispatches to agents | classify_content, dispatch_agent |
| `plan-agent` | SPECIALIST | Creates execution phases and milestones from threads | create_phase, create_milestone, generate_plan_artifact |
| `reconcile-agent` | SPECIALIST | Detects drift, mismatches, and gaps across data sources | read_tickets, read_objectives, create_delta_pack |
| `resource-agent` | SPECIALIST | Maps people to tickets, computes capacity and load | read_resources, capacity_report, suggest_assignments |

Agents are defined in MongoDB and auto-seeded on first startup. Missing agents are backfilled on subsequent starts.

### Intake Pipeline — How Content Reaches the Right Collections

The intake pipeline is the primary way data enters JavaClaw. A user pastes raw content — meeting notes, Confluence pages, Jira exports, Smartsheet plans, or any combination — into the Intake panel and hits enter. The system then runs up to 7 phases, each handled by a specialist agent that writes to specific MongoDB collections using tools.

#### Entry Point

`POST /api/intake/pipeline` with `{projectId, content, filePaths[]}` triggers `IntakePipelineService.startPipeline()`. This creates a source session for UI tracking and launches the pipeline asynchronously. Files can also be uploaded via `POST /api/intake/upload` (multipart), which saves them to disk and creates `uploads` documents with status `INBOX`.

#### Phase 1: Triage (intake-triage agent)

The triage agent receives the raw content and calls `classify_content` — a pattern-matching tool that detects the content type without LLM assistance:

| Pattern Detected | Classification |
|---|---|
| Jira keys (ABC-123) + status headers | `JIRA_DUMP` |
| "Confluence" or "space key" or structured decisions | `CONFLUENCE_EXPORT` |
| "milestone" + "owner" + "status" | `SMARTSHEET_EXPORT` |
| "meeting" + "attendees" or "agenda" | `MEETING_NOTES` |
| "background" + "proposal" + "alternatives" | `DESIGN_DOC` |
| 3+ lines starting with "http" | `LINK_LIST` |
| None of the above | `FREE_TEXT` |

The tool also extracts dates (regex `\d{4}-\d{2}-\d{2}`), people (via `@Name` or `Assignee: Name` patterns), and Jira project keys. The triage agent then organizes the content into distinct topics with structured output (decisions, open questions, action items per topic).

**Collections written:** None directly. The triage output is a text classification passed to subsequent phases. The triage agent's session and messages are written to `sessions`, `messages`, and `events`.

#### Phase 2: Thread Creation (thread-agent)

The thread agent receives the raw content + triage output and calls `create_thread` once per distinct topic. For example, meeting notes about "KYC Architecture", "Evidence Service", and "Operational Readiness" produce 3 threads.

Each `create_thread` call:
1. Checks for duplicates by title (case-insensitive) — if a thread with the same title already exists in the project, it appends the new content to the existing thread and merges decisions and actions, returning `updated_existing`
2. Creates a `ThreadDocument` with `projectIds`, `title`, `lifecycle: ACTIVE`, `decisions[]`, and `actions[]`
3. Seeds the organized markdown as the first message in the thread (stored in `messages` with `sessionId == threadId`, `role: assistant`, `agentId: thread-agent`)

**Collections written:**
- `threads` — One document per topic. Fields populated: `threadId`, `projectIds[]`, `title`, `lifecycle`, `decisions[]` (text + date), `actions[]` (text + assignee + status=OPEN), `createdAt`
- `messages` — One seed message per thread with the organized content

#### Phase 3: PM Agent (conditional — runs if Jira data detected)

The pipeline inspects the triage output for Jira signals (`JIRA`, `JIRA_DUMP`, `TICKET` keywords, or `.xlsx`/`.xls` file paths). If detected, the PM agent processes Jira ticket data.

If file paths are provided, the agent first calls `excel` to read the spreadsheet. Then it calls `create_ticket` once per ticket found.

Each `create_ticket` call creates a `TicketDocument` with the original Jira key preserved in the title (e.g., "J-101: Build Evidence API"). Tickets without an epic are flagged as orphaned work in the description.

**Collections written:**
- `tickets` — One document per ticket. Fields populated: `ticketId`, `projectId`, `title` (includes Jira key), `description` (epic, status, owner info), `priority` (HIGH/MEDIUM/LOW), `status: TODO`, `createdAt`

#### Phase 4: Plan Agent (conditional — runs if Smartsheet data detected)

Runs in parallel with Phase 3 if Smartsheet/milestone data is detected. The plan agent calls `create_phase` for each phase and `create_milestone` for each milestone.

**Collections written:**
- `phases` — One document per phase. Fields: `phaseId`, `projectId`, `name`, `description`, `sortOrder`, `status: PENDING`, `createdAt`
- `milestones` — One document per milestone. Fields: `milestoneId`, `projectId`, `name`, `targetDate`, `owner`, `status: UPCOMING`, `createdAt`

#### Phase 5: Objective Agent (conditional — runs if Phases 3 or 4 ran)

After PM and Plan agents complete, the objective agent synthesizes sprint objectives from the ticket and thread data. It calls `compute_coverage` which reads all tickets and objectives for the project, then computes what percentage of each objective is backed by tickets.

The agent derives high-level objectives (e.g., "Deliver Evidence Service", "Complete Onboarding Flow") and maps existing tickets to them, reporting coverage percentages and flagging unmapped tickets.

**Collections written:**
- `objectives` — One document per derived objective. Fields: `objectiveId`, `projectId`, `outcome`, `ticketIds[]`, `threadIds[]`, `coveragePercent`, `status: PROPOSED`, `createdAt`

#### Phase 6: Reconcile Agent (conditional — runs if Phase 5 ran)

The reconcile agent cross-references all project data by calling `read_tickets`, `read_objectives`, and `read_phases` to load everything, then analyzes mismatches.

It calls `create_delta_pack` with all detected deltas. A delta pack is a structured report containing individual deltas, each with a type, severity, title, description, the two conflicting sources, and a suggested action. For critical findings, it also calls `create_blindspot` to flag individual risk items.

Example deltas from the Story 2 pipeline test:
- **MISSING_EPIC** (HIGH): "J-104 has no epic assignment" — Jira vs Jira
- **DATE_DRIFT** (MEDIUM): "Design date 2026-02-10 vs milestone 2026-03-15" — Confluence vs Smartsheet
- **OWNER_MISMATCH** (HIGH): "Alice owns tickets, Bob owns milestone" — Jira vs Smartsheet
- **ORPHANED_WORK** (MEDIUM): "J-104 not mapped to any epic or objective" — Jira vs (none)

**Collections written:**
- `delta_packs` — One document per reconciliation run. Fields: `deltaPackId`, `projectId`, `deltas[]` (deltaType, severity, title, description, sourceA, sourceB, suggestedAction), `summary` (totalDeltas, bySeverity, byType), `status: FINAL`, `createdAt`
- `blindspots` — One document per critical finding. Fields: `blindspotId`, `projectId`, `title`, `category` (e.g., MISSING_OWNER), `severity`, `description`, `status: OPEN`, `createdAt`

#### Phase 7: Distillation (DistillerService)

The pipeline queries `threads` for all threads created during this pipeline run (by comparing `createdAt` against the pipeline start time). For each new thread, `DistillerService.distillThread()` writes distilled content back to the thread's `content` field (persistent knowledge) and creates a THREAD-scoped memory with a 7-day TTL (expiring summary).

The distiller extracts:
- Thread title and summary
- All decisions (text + who decided)
- All action items (text + assignee)
- Thread message content (truncated to 500 chars per message)

This is stored as a single `MemoryDocument` with scope `THREAD`, tagged `auto-distilled, thread-decisions, intake-pipeline`.

**Collections written:**
- `memories` — One document per thread. Fields: `memoryId`, `scope: THREAD`, `threadId`, `projectId`, `key` (e.g., "thread-distill-abc12345"), `content` (structured markdown), `tags`, `createdBy: distiller`, `createdAt`

#### Complete Data Flow Diagram

```
User pastes content
        │
        ▼
POST /api/intake/pipeline
        │
        ├─ creates → sessions (source session, status: RUNNING)
        ├─ creates → messages (raw content as first user message)
        │
        ▼
Phase 1: intake-triage
        │  calls classify_content (pattern matching, no DB write)
        │  produces structured topic list
        │
        ▼
Phase 2: thread-agent
        │  calls create_thread per topic
        ├─ creates → threads (one per topic, with decisions[] and actions[])
        ├─ creates → messages (seed content per thread, sessionId = threadId)
        │
        ├──────────────────────────────────────────────┐
        ▼                                              ▼
Phase 3: pm (if Jira data)              Phase 4: plan-agent (if Smartsheet)
        │  calls create_ticket                    │  calls create_phase
        ├─ creates → tickets                      ├─ creates → phases
        │                                         │  calls create_milestone
        │                                         ├─ creates → milestones
        │                                         │
        └──────────────┬───────────────────────────┘
                       ▼
             Phase 5: objective-agent
                       │  calls compute_coverage
                       ├─ creates → objectives (with coveragePercent)
                       │
                       ▼
             Phase 6: reconcile-agent
                       │  reads tickets, objectives, phases
                       │  calls create_delta_pack
                       ├─ creates → delta_packs (drift report)
                       │  calls create_blindspot (for critical items)
                       ├─ creates → blindspots (risk flags)
                       │
                       ▼
             Phase 7: distiller
                       │  reads new threads from Phase 2
                       ├─ creates → memories (THREAD-scoped, one per thread)
                       │
                       ▼
             Pipeline complete
                       ├─ updates → sessions (source session → COMPLETED)
                       └─ creates → messages (summary message)
```

#### What the Cockpit UI Sees

The web cockpit fetches data from the same REST endpoints that the pipeline wrote to:

- **Threads panel** (`GET /api/projects/{pid}/threads`) — Shows the threads created in Phase 2
- **Tickets panel** (`GET /api/projects/{pid}/tickets`) — Shows tickets from Phase 3
- **Objectives panel** (`GET /api/projects/{pid}/objectives`) — Shows objectives with coverage % from Phase 5
- **Reconcile panel** (`GET /api/projects/{pid}/delta-packs`) — Shows the delta pack from Phase 6
- **Blindspots panel** (`GET /api/projects/{pid}/blindspots`) — Shows risk items from Phase 6
- **Schedule panel** (`GET /api/schedules`) — Shows when agents will run again automatically

The scenario test framework validates this end-to-end: `scenario-story-2-pipeline.json` runs the full 7-phase pipeline with mock LLM responses and asserts that threads, tickets, phases, milestones, objectives, delta packs, and blindspots all appear in the correct collections with the expected data.

### Scheduled Agent Execution

Six default schedules are seeded on startup:

| Agent | Schedule | Priority |
|---|---|---|
| reconcile-agent | 9am weekdays | 6 (highest) |
| resource-agent | 9am weekdays | 5 |
| objective-agent | 9am weekdays | 5 |
| checklist-agent | 9am weekdays | 5 |
| plan-agent | 10am Mondays | 4 |
| thread-extractor | 6pm weekdays | 4 |

Schedules support CRON expressions, fixed times, intervals, and immediate execution. The scheduler engine computes future executions, acquires distributed locks, and tracks results in `past_executions`.

## MongoDB Data Model

JavaClaw uses MongoDB as its single data store — no separate message broker, cache, or search index. The database is named `javaclaw` and requires a replica set (`rs0`) for change streams.

### Why MongoDB

- **Change Streams** — Real-time agent-to-agent communication and UI event streaming without a separate message broker
- **Document Model** — Agent state, memories, tool results, and event payloads are naturally hierarchical
- **TTL Indexes** — Session locks auto-expire after 60 seconds, no background reaper needed
- **Flexible Schema** — Tool outputs, event payloads, and delta packs hold arbitrary JSON
- **Text Search** — Memory recall and upload search use MongoDB's built-in full-text search
- **Replica Set** — Required for change streams; a single-node replica set works for development

### Entity Relationship Overview

```
projects (root aggregate)
├── threads ────────── M:N via projectIds[]
│   ├── messages ──── via sessionId (= threadId for threads)
│   ├── events ────── via sessionId
│   ├── checkpoints ─ via sessionId
│   └── approvals ─── via threadId
├── tickets ────────── via projectId (self-referential hierarchy)
│   └── resource_assignments ─ via ticketId
├── objectives ─────── via projectId
├── phases ─────────── via projectId (ordered by sortOrder)
│   ├── milestones ── via phaseId
│   └── checklists ── via phaseId
├── ideas ──────────── via projectId
├── uploads ─────────── via projectId
├── intakes ─────────── via projectId
├── delta_packs ────── via projectId
├── blindspots ─────── via projectId
├── reconciliations ── via projectId
├── links ──────────── via projectId
├── resources ──────── via projectId
└── reminders ──────── via projectId

agents (global) ────── agent_schedules, future_executions, past_executions
memories (scoped) ──── GLOBAL / PROJECT / SESSION / THREAD
sessions (ephemeral) ─ deleted on restart, linked to threads via threadId
```

### Collections Reference (32)

#### Core Orchestration

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `agents` | AgentDocument | Agent definitions with system prompts and tool policies | agentId, role (CONTROLLER/SPECIALIST/CHECKER), systemPrompt, allowedTools[], enabled |
| `sessions` | SessionDocument | Ephemeral agent execution contexts (deleted on restart) | sessionId, threadId (nullable), projectId, status (IDLE/RUNNING/PAUSED/FAILED/COMPLETED) |
| `messages` | MessageDocument | Chat messages with multimodal support (text + images) | sessionId, seq, role (user/assistant/system), content, parts[] (type, mediaType, data), agentId |
| `events` | EventDocument | Event sourcing — every action as a monotonic sequence | sessionId, seq (unique compound), type (40+ EventTypes), payload, timestamp |
| `checkpoints` | CheckpointDocument | Agent state snapshots for resume/replay | sessionId, stepNo, state (JSON string), eventOffset |
| `locks` | LockDocument | Distributed session locks with TTL auto-expiry (60s) | lockId (= sessionId), owner, expiresAt (TTL index) |
| `approvals` | ApprovalDocument | Human-in-the-loop tool approval requests | threadId, toolName, toolInput, status (PENDING/APPROVED/DENIED) |
| `testPrompts` | TestPromptDocument | Test mode prompt/response pairs for scenario testing | agentId, sessionId, prompt, llmResponse, responseFallback |

**How sessions and threads share messages:** Both sessions and threads store messages in the `messages` collection keyed by `sessionId`. For threads, `sessionId == threadId`. The `AgentLoop` performs a dual-lookup — `SessionRepository` first, then `ThreadRepository` — so the agent loop, checkpointing, and event streaming work identically for both.

#### Project Management

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `projects` | ProjectDocument | Root aggregate — top-level container for all project data | projectId, name, status (ACTIVE/ARCHIVED/TEMPLATE), tags[] |
| `threads` | ThreadDocument | Persistent knowledge store — distilled project content organized by topic | threadId, projectIds[] (M:N), title, lifecycle (DRAFT/ACTIVE/CLOSED/MERGED), summary, content (distilled markdown), evidence[], decisions[], actions[] |
| `tickets` | TicketDocument | Work items with self-referential hierarchy | ticketId, projectId, title, status (TODO/IN_PROGRESS/REVIEW/DONE/BLOCKED), priority (LOW-CRITICAL), type (INITIATIVE/EPIC/STORY/SUBTASK), parentTicketId, assignedResourceId, objectiveIds[], externalRef (Jira key) |
| `ideas` | IdeaDocument | Brainstorming items, promotable to tickets | ideaId, projectId, title, status (NEW/REVIEWED/PROMOTED/ARCHIVED), promotedToTicketId |
| `objectives` | ObjectiveDocument | Sprint objectives with measurable coverage | objectiveId, projectId, sprintName, outcome, measurableSignal, coveragePercent, status (PROPOSED/COMMITTED/ACHIEVED/MISSED/DROPPED), threadIds[], ticketIds[] |
| `phases` | PhaseDocument | Execution phases with entry/exit criteria | phaseId, projectId, name, entryCriteria[], exitCriteria[], status (PENDING/ACTIVE/COMPLETED/BLOCKED), sortOrder |
| `milestones` | MilestoneDocument | Delivery milestones linked to phases | milestoneId, projectId, phaseId, name, targetDate, actualDate, status (UPCOMING/ON_TRACK/AT_RISK/MISSED/COMPLETED), objectiveIds[], ticketIds[], dependencies[] |
| `checklists` | ChecklistDocument | Operational readiness and release checklists | checklistId, projectId, phaseId, name, items[] (text, assignee, checked, notes), status (PENDING/IN_PROGRESS/COMPLETED) |
| `checklist_templates` | ChecklistTemplateDocument | Reusable checklist templates (global or project-scoped) | templateId, category (ORR/RELEASE_READINESS/ONBOARDING/SPRINT_CLOSE/DEPLOYMENT/ROLLBACK/CUSTOM), items[] |

**Tickets form a hierarchy:** INITIATIVE → EPIC → STORY → SUBTASK via the `parentTicketId` field. Tickets link to objectives, phases, and threads. The `externalRef` field holds Jira issue keys for imported tickets.

**Objectives drive alignment:** The objective-agent computes `coveragePercent` by mapping tickets and threads to each objective. Stories 3 and 4 test this — the agent evaluates threads (what's being discussed), tickets (what's planned), and milestones (what must be delivered) to produce coverage percentages and identify orphaned work.

**Phases and milestones create execution structure:** The plan-agent creates phases with entry/exit criteria and links milestones to them. Story 5 tests this flow — "Create a plan from the current threads" produces Phase 1: Facade + Tooling, Phase 2: Threaded Execution, Phase 3: Operational Readiness.

#### Resources and Capacity

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `resources` | ResourceDocument | Team members with skills and capacity | resourceId, projectId, name, email, role (ENGINEER/DESIGNER/PM/QA), skills[], capacity (units), availability (0.0-1.0) |
| `resource_assignments` | ResourceAssignmentDocument | Links resources to tickets with allocation | assignmentId, resourceId, ticketId, projectId, percentageAllocation |

**Capacity planning:** The resource-agent maps people → tickets → objectives to compute load. Story 4 tests this — "Who is working on what?" reveals Alice at 138% (overloaded), Bob at 88% (5h spare), with rebalancing recommendations.

#### Reconciliation and Drift Detection

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `delta_packs` | DeltaPackDocument | Drift reports comparing data sources | deltaPackId, projectId, deltas[] (deltaType, severity, title, sourceA, sourceB, suggestedAction), status (DRAFT/FINAL/SUPERSEDED) |
| `blindspots` | BlindspotDocument | Individual risk items found during reconciliation | blindspotId, projectId, category, severity (LOW-CRITICAL), status (OPEN/ACKNOWLEDGED/RESOLVED/DISMISSED) |
| `reconciliations` | ReconciliationDocument | Source-to-ticket mapping with conflict detection | reconciliationId, projectId, mappings[] (sourceRow, ticketId, matchType), conflicts[] (field, sourceValue, ticketValue, resolution) |

**Delta types:** MISSING_EPIC, DATE_DRIFT, OWNER_MISMATCH, SCOPE_MISMATCH, DEPENDENCY_MISMATCH, COVERAGE_GAP, ORPHANED_WORK, CAPACITY_OVERLOAD, STALE_ARTIFACT, PRIORITY_MISMATCH, STATUS_MISMATCH

**Blindspot categories:** ORPHANED_TICKET, UNCOVERED_OBJECTIVE, EMPTY_PHASE, UNASSIGNED_WORK, MISSING_OWNER, DEPENDENCY_RISK, CAPACITY_GAP, SCOPE_OVERLAP, MISSING_TEST_SIGNAL, STALE_ARTIFACT

Story 7 tests scheduled reconciliation — the reconcile-agent runs automatically overnight and produces a delta pack with "Payment Gateway milestone at risk" and "notification owner mismatch". Story 2 tests the full alignment flow after intake — the system challenges inconsistencies across Confluence, Jira, and Smartsheet data.

#### Intake and Uploads

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `intakes` | IntakeDocument | Raw content received for processing | intakeId, projectId, sourceType (CONFLUENCE_EXPORT/JIRA_DUMP/SMARTSHEET_EXPORT/MEETING_NOTES/FREE_TEXT/...), classifiedAs, dispatchedTo[], status (RECEIVED/PROCESSING/DISPATCHED/FAILED) |
| `uploads` | UploadDocument | Processed documents with extracted metadata (full-text searchable) | uploadId, projectId, source, title, content (text-indexed), people[], systems[], threadId, status (INBOX/THREADED/ARCHIVED) |
| `links` | LinkDocument | External URLs grouped by category and bundled | linkId, projectId, url, title, category, bundleId, threadIds[], objectiveIds[], phaseIds[] |

#### Memory

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `memories` | MemoryDocument | Expiring conversation summaries (full-text searchable) | memoryId, scope (GLOBAL/PROJECT/SESSION/THREAD), key, content, tags[], projectId, sessionId, threadId, createdBy, expiresAt (TTL) |

**Four memory scopes with TTL:**
- **GLOBAL** — Shared across all projects (e.g., "user prefers Java 21") — **never expires**
- **PROJECT** — Tied to a project (e.g., "this repo uses Gradle") — **expires after 30 days**
- **SESSION** — Tied to a standalone session — **expires after 24 hours**
- **THREAD** — Tied to a thread (e.g., "sprint planning decisions") — **expires after 7 days**

**Threads vs Memories:** Threads are the persistent knowledge store — content comes in, gets distilled, and old ideas are replaced by new ones via the `content` field. Memories are expiring conversation summaries that rotate and disappear via MongoDB TTL indexes. The distiller writes content back to threads (persistent) and creates memories as temporary summaries (expiring).

The distiller agent automatically runs after each session completes, extracting summaries and storing them as scoped memories with TTL. Story 9 tests memory persistence — the distiller stores an S3 storage decision and later recalls it when asked "What did we decide about evidence storage?"

#### Scheduling and Execution

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `agent_schedules` | AgentScheduleDocument | CRON/interval schedule definitions for automated agent runs | scheduleId, agentId, scheduleType (CRON/FIXED_TIMES/INTERVAL/IMMEDIATE), cronExpr, projectScope (GLOBAL/PROJECT), executorPolicy (maxConcurrent, priority, maxAttempts) |
| `future_executions` | FutureExecutionDocument | Upcoming scheduled runs with distributed locking | executionId, agentId, scheduledAt, execStatus (READY/PENDING/RUNNING/SKIPPED/CANCELLED), priority, lockOwner, attempt |
| `past_executions` | PastExecutionDocument | Completed run history with metrics | pastExecutionId, agentId, resultStatus (SUCCESS/FAIL/CANCELLED/SKIPPED), durationMs, llmMetrics (tokens, cost), toolCallSummary, responseSummary |

Story 10 tests the daily reset flow — CRON schedules are created, future executions are computed, and the PM summarizes what's scheduled for today. Story 7 tests the full cycle: schedule → trigger → reconcile → report.

#### Observability

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `logs` | LogDocument | System logs with level filtering | level (DEBUG/INFO/WARN/ERROR), source, sessionId, message, stackTrace |
| `llm_interactions` | LlmInteractionDocument | LLM call metrics for cost and performance tracking | sessionId, agentId, provider, model, promptTokens, completionTokens, durationMs, success |
| `reminders` | ReminderDocument | Recurring and one-shot timers | reminderId, projectId, message, type (TIME_BASED/CONDITION_BASED), triggerAt, recurring, intervalSeconds |

### Index Strategy

Key indexes defined in `mongo-init.js` and via Spring Data annotations:

| Collection | Index | Purpose |
|---|---|---|
| events | `{sessionId, seq}` unique | Event ordering and deduplication |
| messages | `{sessionId, seq}` unique | Message ordering |
| checkpoints | `{sessionId, stepNo}` unique | Step-level state snapshots |
| locks | `expiresAt` TTL | Auto-expiry of distributed locks |
| tickets | `{projectId, status}`, `parentTicketId` | Status queries and hierarchy traversal |
| objectives | `{projectId, status}`, `{projectId, sprintName}` | Sprint-scoped objective lookup |
| memories | `{content, key}` text, `{scope, key}`, `{projectId, scope}`, `expiresAt` TTL | Full-text search, scope-filtered recall, auto-expiry of temporary summaries |
| uploads | `{title, content}` text | Full-text search across uploaded documents |
| future_executions | `{execStatus, scheduledAt}` | Pickup queue for scheduler |
| agent_schedules | `{agentId, projectId}` unique | One schedule per agent per project |

## Tool System (46 Built-in Tools)

Tools implement the `Tool` SPI interface and are discovered via `ServiceLoader`:

### File and Code Tools

| Tool | Risk | Description |
|---|---|---|
| `read_file` | READ_ONLY | Read file contents |
| `write_file` | WRITE_FILES | Write/create files |
| `list_directory` | READ_ONLY | List directory contents |
| `search_files` | READ_ONLY | Search files by pattern |
| `shell_exec` | EXEC_SHELL | Execute shell commands |
| `git_status` | READ_ONLY | Git status |
| `git_diff` | READ_ONLY | Git diff |
| `git_commit` | WRITE_FILES | Git commit |
| `http_get` | NETWORK_CALLS | HTTP GET requests |
| `jbang_exec` | EXEC_SHELL | Write and execute Java code via JBang |
| `python_exec` | EXEC_SHELL | Write and execute Python scripts |
| `excel` | READ_ONLY + WRITE_FILES | Read/write Excel files (.xlsx/.xls) via Apache POI |

### Memory and Search Tools

| Tool | Risk | Description |
|---|---|---|
| `memory` | WRITE_FILES | Store/recall/delete persistent memories (GLOBAL/PROJECT/SESSION/THREAD scope) |
| `human_search` | BROWSER_CONTROL | Request human to perform web search, opens browser |

### Project Management Tools

| Tool | Risk | Description |
|---|---|---|
| `create_ticket` | WRITE_FILES | Create project tickets (with hierarchy support) |
| `create_idea` | WRITE_FILES | Create project ideas |
| `create_thread` | WRITE_FILES | Create project threads |
| `rename_thread` | WRITE_FILES | Rename an existing thread |
| `merge_threads` | WRITE_FILES | Merge two threads into one |
| `attach_evidence` | WRITE_FILES | Attach evidence references to threads |
| `read_thread_messages` | READ_ONLY | Read messages from a thread |
| `create_reminder` | WRITE_FILES | Create time-based or condition-based reminders |

### Planning and Objectives Tools

| Tool | Risk | Description |
|---|---|---|
| `create_phase` | WRITE_FILES | Create execution phases with entry/exit criteria |
| `update_phase` | WRITE_FILES | Update phase status and criteria |
| `create_milestone` | WRITE_FILES | Create delivery milestones linked to phases |
| `update_milestone` | WRITE_FILES | Update milestone status and dates |
| `generate_plan_artifact` | WRITE_FILES | Generate a consolidated plan document |
| `update_objective` | WRITE_FILES | Update objective status and coverage |
| `compute_coverage` | READ_ONLY | Compute ticket coverage percentage for objectives |

### Checklist Tools

| Tool | Risk | Description |
|---|---|---|
| `create_checklist` | WRITE_FILES | Create operational readiness or release checklists |
| `update_checklist` | WRITE_FILES | Update checklist item status |
| `checklist_progress` | READ_ONLY | Compute checklist completion progress |
| `create_checklist_template` | WRITE_FILES | Create reusable checklist templates |

### Resource Management Tools

| Tool | Risk | Description |
|---|---|---|
| `read_resources` | READ_ONLY | List team members and their capacity |
| `read_tickets` | READ_ONLY | Read project tickets |
| `read_objectives` | READ_ONLY | Read project objectives |
| `read_phases` | READ_ONLY | Read project phases |
| `read_checklists` | READ_ONLY | Read project checklists |
| `assign_resource` | WRITE_FILES | Assign a resource to a ticket |
| `unassign_resource` | WRITE_FILES | Remove a resource assignment |
| `capacity_report` | READ_ONLY | Generate capacity and load analysis |
| `suggest_assignments` | READ_ONLY | AI-suggested resource-to-ticket assignments |

### Reconciliation Tools

| Tool | Risk | Description |
|---|---|---|
| `create_delta_pack` | WRITE_FILES | Create a drift/mismatch report (delta pack) |
| `create_blindspot` | WRITE_FILES | Flag a risk item (blindspot) |

### Intake Tools

| Tool | Risk | Description |
|---|---|---|
| `classify_content` | READ_ONLY | Classify intake content type and extract metadata |
| `dispatch_agent` | WRITE_FILES | Dispatch content to a specialist agent for processing |

Tools with `WRITE_FILES` or `EXEC_SHELL` risk require user approval. Tool output streams in real-time via `ToolStream` callbacks.

## Scenario Test Framework

JavaClaw includes a deterministic E2E test framework where every agent response is pre-scripted. Tests verify the full pipeline: project creation → agent execution → MongoDB state → REST API responses.

### Running Scenarios

```bash
# Single scenario
jbang javaclaw.java --headless --scenario runtime/src/test/resources/scenario-pm-tools-v2.json

# All 36 scenarios (single JVM, ~10x faster)
bash run-scenarios.sh
```

### V2 Scenario Features

The V2 schema (schemaVersion: 2) supports:

- **Step types:** `context` (agent conversation), `seed` (REST API data seeding), `pipeline` (forced agent routing)
- **Seed actions:** Pre-populate project data via REST POST before running agent steps
- **Assertion types:** `sessionStatus`, `events` (containsTypes, minCounts), `mongo` (collection queries with countGte/exists), `messages` (anyAssistantContains), `http` (REST API response validation with status, body, jsonPath, jsonArrayMinSize)

### 36 Built-in Scenarios

| Scenario | What It Tests |
|---|---|
| **V1 Basic (13)** | General chat, coder, PM, memory, file tools, git, JBang, Python, HTTP, Excel |
| **V2 Framework (3)** | PM tools, memory, file tools with V2 assertions |
| **Agent-Specific (10)** | Thread agent, objective agent, checklist agent, intake triage, plan agent, reconcile agent, resource agent, thread intake, extraction, intake pipeline |
| **Story E2E (10)** | Stories 2-10: alignment pipeline, sprint objectives, resource load, plan creation, checklist generation, scheduled reconcile, on-demand agents, memory persistence, daily schedule reset |

Each story scenario seeds prerequisite data, runs agents with mock responses, and asserts both MongoDB state and REST API responses — verifying that the cockpit UI would display the correct data.

### Maven Unit Tests

66 Maven tests across all modules (unit + integration via embedded MongoDB):

```bash
cmd.exe /c "mvnw.cmd test"   # Windows/WSL
./mvnw test                   # Linux/Mac
```

## Quick Start

### Step 1: Start MongoDB

```bash
docker compose up -d mongodb
docker compose ps   # Wait for healthy status
```

### Step 2: Set your LLM API key

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

### Step 3: Start the server

```bash
jbang javaclaw.java --headless
```

Open `http://localhost:8080` in your browser for the web cockpit.

### JBang CLI Flags

| Flag | Description | Default |
|---|---|---|
| `--headless` | REST gateway only (no desktop UI) | off |
| `--testmode` | Test mode with deterministic LLM (no API key needed) | off |
| `--scenario <file>` | Scenario-based E2E test (implies `--testmode`) | off |
| `--api-key <key>` | Set API key (auto-detects Anthropic vs OpenAI) | none |
| `--mongo <uri>` | Custom MongoDB connection URI | `mongodb://localhost:27017/javaclaw?replicaSet=rs0` |
| `--port <port>` | HTTP server port | `8080` |

## REST API

Base URL: `http://localhost:8080`

### Sessions and Conversations

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/sessions` | Create a new agent session |
| `GET` | `/api/sessions` | List all sessions (newest first) |
| `POST` | `/api/sessions/{id}/messages` | Send a message (text or multimodal) |
| `POST` | `/api/sessions/{id}/run` | Start the multi-agent loop |
| `POST` | `/api/sessions/{id}/pause` | Pause execution |

### Projects

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects` | Create a project |
| `GET` | `/api/projects` | List all projects |
| `PUT` | `/api/projects/{id}` | Update project |
| `DELETE` | `/api/projects/{id}` | Delete project |

### Threads (Project-Scoped)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/threads` | Create a thread |
| `GET` | `/api/projects/{pid}/threads` | List threads for project |
| `POST` | `/api/projects/{pid}/threads/{tid}/messages` | Send message to thread |
| `POST` | `/api/projects/{pid}/threads/{tid}/run` | Run agent on thread |

### Tickets, Objectives, Phases, Milestones, Checklists

All follow the same sub-resource pattern under `/api/projects/{pid}/...`:

| Resource | Endpoint Pattern | Operations |
|---|---|---|
| Tickets | `/api/projects/{pid}/tickets` | CRUD + status filter |
| Objectives | `/api/projects/{pid}/objectives` | CRUD + sprint filter |
| Phases | `/api/projects/{pid}/phases` | CRUD + status filter |
| Milestones | `/api/projects/{pid}/milestones` | CRUD + status filter |
| Checklists | `/api/projects/{pid}/checklists` | CRUD |
| Ideas | `/api/projects/{pid}/ideas` | CRUD + promote to ticket |
| Links | `/api/projects/{pid}/links` | CRUD |
| Reconciliations | `/api/projects/{pid}/reconciliations` | CRUD |
| Delta Packs | `/api/projects/{pid}/delta-packs` | CRUD |
| Blindspots | `/api/projects/{pid}/blindspots` | CRUD + status filter |

### Resources, Agents, Memory, Schedules

| Method | Endpoint | Description |
|---|---|---|
| `GET/POST/PUT/DELETE` | `/api/resources` | Team members and capacity |
| `GET/POST/PUT/DELETE` | `/api/agents` | Agent definitions |
| `GET/POST/DELETE` | `/api/memories` | Persistent memories (filter by scope, query) |
| `GET/POST/PUT/DELETE` | `/api/schedules` | Agent schedule definitions |
| `POST` | `/api/executions/trigger` | Trigger immediate agent execution |

### Intake

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/intake` | Submit raw content for intake processing |
| `POST` | `/api/projects/{pid}/intake/pipeline` | Run full intake pipeline (triage → threads → distill) |
| `GET` | `/api/projects/{pid}/intake` | List intake records |

### Observability

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/logs` | System logs (filter: level, sessionId, limit) |
| `GET` | `/api/logs/llm-interactions` | LLM call metrics |
| `GET` | `/api/logs/llm-interactions/metrics` | Aggregate LLM metrics |
| `GET` | `/api/tools` | List available tools with schemas |

### WebSocket

Connect to `ws://localhost:8080/ws`:

```json
{"type": "SUBSCRIBE_SESSION", "sessionId": "<id>"}
{"type": "SUBSCRIBE_PROJECT", "projectId": "<id>"}
```

40+ event types streamed in real-time: `USER_MESSAGE_RECEIVED`, `MODEL_TOKEN_DELTA`, `TOOL_CALL_STARTED`, `TOOL_RESULT`, `AGENT_DELEGATED`, `AGENT_CHECK_PASSED`, `MEMORY_STORED`, `TICKET_CREATED`, and more.

## Developer Guide

### How to Add a New Tool

1. Create a class in `tools/src/main/java/.../tools/` implementing `io.github.drompincen.javaclawv1.runtime.tools.Tool`
2. Implement: `name()`, `description()`, `riskProfiles()`, `inputSchema()`, `execute(ctx, input, stream)`
3. Register via SPI: add to `tools/src/main/resources/META-INF/services/io.github.drompincen.javaclawv1.runtime.tools.Tool`
4. Auto-discovered by `ToolRegistry` at startup

### How to Add a New Collection

1. Create a `Document` class in `persistence/src/main/java/.../persistence/document/`
2. Create a `MongoRepository` interface in `.../persistence/repository/`
3. Create a DTO record in `protocol/src/main/java/.../protocol/api/`
4. Create a REST controller in `gateway/src/main/java/.../gateway/controller/`
5. Add indexes to `mongo-init.js`

### Key File Paths

| File | Purpose |
|---|---|
| `javaclaw.java` | JBang server launcher (single file, includes all modules) |
| `runtime/.../agent/AgentLoop.java` | Core agent orchestration — dual-lookup for sessions and threads |
| `runtime/.../agent/graph/AgentGraphBuilder.java` | Controller → specialist → checker loop |
| `runtime/.../agent/AgentBootstrapService.java` | Seeds 15 default agents on startup |
| `runtime/.../agent/IntakePipelineService.java` | Chains triage → thread creation → distillation |
| `runtime/.../agent/llm/ScenarioRunner.java` | E2E scenario test playback engine |
| `persistence/.../stream/EventChangeStreamTailer.java` | MongoDB change stream → WebSocket bridge |
| `gateway/.../websocket/JavaClawWebSocketHandler.java` | WebSocket handler for event streaming |
| `run-scenarios.sh` | Run all 36 scenario tests in a single JVM |
| `stories.txt` | 10 user stories describing the full workflow |

### Common Pitfalls

| Pitfall | Details |
|---|---|
| **`mvnw spring-boot:run -pl gateway` doesn't recompile** | Always run `mvnw install -DskipTests` first |
| **Change streams require replica set** | MongoDB must run with `--replSet rs0` |
| **Messages collection is shared** | Sessions and threads both use `messages`, keyed by `sessionId`. For threads, `sessionId == threadId` |
| **Sessions are ephemeral** | Deleted on startup. Threads and all project data persist |
| **Thread IDs need dual-lookup** | `AgentLoop` checks `SessionRepository` first, then `ThreadRepository` |
| **Lock TTL is 60s** | Distributed session lock expires after 60 seconds |
| **WSL path translation** | `WslPathHelper` auto-converts `C:\...` to `/mnt/c/...` on WSL |
| **Windows ARM64 testing** | Flapdoodle embedded MongoDB forced to `os.arch=amd64` via `TestMongoConfiguration` |

## Prerequisites

- **Java 21+** (Eclipse Temurin recommended)
- **Docker** (for MongoDB)
- **JBang** (recommended — install from https://www.jbang.dev/download/)
- **Maven 3.9+** (only for multi-module builds; `mvnw` wrapper included)
- **API Key** — Set `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` environment variable
- **Python 3** (optional — for `python_exec` tool)

## LLM Configuration

| Provider | Env Variable | Default Model |
|---|---|---|
| Anthropic | `ANTHROPIC_API_KEY` | `claude-sonnet-4-5-20250929` |
| OpenAI | `OPENAI_API_KEY` | `gpt-4o` |

Set provider: `JAVACLAW_LLM_PROVIDER=anthropic` (default) or `openai`.

## License

MIT License
