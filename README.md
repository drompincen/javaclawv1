# JavaClaw

**A continuous project intelligence engine for engineering managers.** JavaClaw is a multi-agent orchestration platform built with Java 21, Spring AI, LangGraph4j, and MongoDB. It turns raw meeting notes, Jira exports, and Confluence designs into structured threads, sprint objectives, capacity reports, and operational readiness checklists — then keeps them honest through scheduled reconciliation.

## The Story

David opens JavaClaw early in the morning. He pastes his meeting notes — a mix of architecture decisions, open questions, and action items — into the Intake panel and hits enter. The system first saves the raw content as project memory, then the Triage agent — reading memories from yesterday's intakes — classifies the content, organizes it into topics, and decides which downstream agents should process it. No keyword matching, no hardcoded rules — the LLM reasons about the content.

The Thread agent takes over. It sees the existing threads for this project, recognizes that "Evidence Service" was discussed yesterday, and appends the new decisions to the existing thread instead of creating a duplicate. For genuinely new topics — "Payment Service Phase 1 Architecture", "Operational Readiness (ORR)" — it creates new threads with distilled key points, decisions, and action items.

Later, he pastes a Jira export, a Confluence design page, and a Smartsheet plan. The Triage agent recognizes ticket data, timeline milestones, and design content — routing each to the right specialist. The system layers intelligence: tickets are grouped, phases are extracted, objectives are synthesized, and a Delta Pack appears showing "Missing Epic: Evidence Service", "Milestone drift: March 8 -> March 15", "Owner mismatch: Alice vs Bob", "Unmapped tickets: 6". The system isn't just organizing — it's challenging inconsistencies across sources.

The next morning, David asks "What are our objectives for this sprint?" The Objective agent doesn't just count tickets — it reads the "train of thought" in the threads. While there are 10 tickets for "Payment Facade," the threads show the team is stuck on "Evidence API." It marks that objective as AT_RISK despite the good ticket count. David asks "Who is working on what?" and the Resource agent — remembering Alice's last three "overloaded" flags — suggests a permanent reassignment to Bob. He asks "Create a plan from the current threads" and a three-phase execution path emerges with entry conditions, exit criteria, and linked milestones.

Days pass. The scheduler triggers the Reconcile Agent overnight. It reads its own memory from last week's reconciliation: "MISSING_EPIC for J-104 was flagged." It checks again — still unresolved. It escalates the severity. A new delta appears: "The Triage agent just processed a note saying 'Vendor delayed by 2 weeks.' DATE_DRIFT detected." The system kept the project honest without David.

Weeks later, David notices the system uses consistent naming, recalls past decisions, and avoids repeating earlier mistakes. Overnight, the memory janitor noticed two conflicting dates for the payment gateway launch — the older "tentative" date is now contradicted by a confirmed date in the latest thread. It purged the stale memory. This morning, the system only references the truth. After a week of heavy brainstorming, 150 small memories accumulated. The distiller ran a compression pass — synthesizing them into 10 executive summaries and deleting the fragments. The system's intelligence stays sharp.

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
     │  18 documents │  │  DTOs    │  │ 46 SPI │  │   Replica Set     │
     │  19 repos     │  │  Enums   │  │ tools  │  │   18 collections  │
     │  Change       │  │  Events  │  │        │  │   Change Streams  │
     │  Streams      │  │  WS msgs │  │        │  │                   │
     └───────────────┘  └──────────┘  └────────┘  └───────────────────┘
```

### Module Overview

| Module | Purpose |
|---|---|
| **protocol** | Shared DTOs (Java records), enums (AgentRole, SessionStatus, TicketStatus, MilestoneStatus, etc.), event types (40+), WebSocket message contracts. Pure Java — no Spring dependencies. |
| **persistence** | 18 MongoDB document classes, 19 repository interfaces, `ChangeStreamService` for real-time reactive streaming. Domain entities (tickets, objectives, phases, resources, etc.) stored in a unified `things` collection via `ThingDocument` with flexible `Map<String, Object>` payload. Infrastructure collections (agents, sessions, messages, events, checkpoints, memories, schedules) remain separate. |
| **runtime** | Multi-agent engine: `AgentLoop` orchestrates sessions, `AgentGraphBuilder` runs the controller→specialist→checker loop, `IntakePipelineService` chains memory-first ingest→LLM-driven triage→context-aware thread creation→alignment→reconciliation, scheduler engine for automated agent runs, `ThingService` for unified domain entity CRUD, scenario test framework with 50 E2E tests. |
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
| `distiller` | SPECIALIST | Session-level auto-distillation + nightly memory compression and cleanup (pipeline thread distillation handled by thread-agent) | memory |
| `thread-extractor` | SPECIALIST | Extracts action items, ideas, and tickets from threads | read_thread_messages, create_ticket, create_idea |
| `thread-agent` | SPECIALIST | Context-aware thread management — sees existing threads + memories, distills content into threads directly, merges overlapping topics, appends to existing threads on re-intake | create_thread, rename_thread, merge_threads |
| `objective-agent` | SPECIALIST | Reads thread content + ticket state + memories of past analyses; reasons about actual risk beyond ticket counts; tracks coverage trends across runs | compute_coverage, update_objective, memory |
| `checklist-agent` | SPECIALIST | Generates ORR and release readiness checklists | create_checklist, checklist_progress |
| `intake-triage` | SPECIALIST | Classifies raw content and decides routing via structured output block (THREAD/TICKETS/PLAN/RESOURCES); reads project memories to recognize updates to prior topics | classify_content, memory |
| `plan-agent` | SPECIALIST | Creates execution phases and milestones from threads | create_phase, create_milestone, generate_plan_artifact |
| `reconcile-agent` | SPECIALIST | System Auditor — reads all agent memories, tracks deltas over time, identifies recurring vs. resolved findings, correlates intake content with milestone/plan state | read_tickets, read_objectives, create_delta_pack, memory |
| `resource-agent` | SPECIALIST | Tracks historical load patterns across runs, suggests structural reassignments, detects chronic overload via memory of past capacity snapshots | read_resources, capacity_report, suggest_assignments, memory |

Agents are defined in MongoDB and auto-seeded on first startup. Missing agents are backfilled on subsequent starts.

### The Contextual Specialist Pattern (Stateful Intelligence Loop)

This design shifts JavaClaw from a series of "blind" scripts to a **Stateful Intelligence Loop**. Every specialist agent follows the same 4-step execution pattern — whether invoked by the intake pipeline, triggered by the scheduler, or called on-demand by a user:

| Step | Action | Benefit |
|---|---|---|
| **1. Context Prep** | Load current DB entities + relevant project memories into the prompt | No more "fresh start" syndrome — the agent knows what happened yesterday |
| **2. Reasoning** | LLM compares new input vs. existing state | Recognizes that a new paste is an update to a prior topic, not a new topic |
| **3. Structured Output** | Agent returns parseable blocks with intent, updates, and routing decisions | Java code simply executes the "orders" — no hardcoded if/else routing |
| **4. Feedback Loop** | Agent saves its own analysis summary back to memory | Other agents can see what this one found; next run builds on the last |

**Project Memory as the "subconscious":** The `memories` collection acts as the system's accumulated intelligence. Every intake saves raw content as memory. Every agent reads relevant memories before acting. Every agent writes what it learned after acting. This creates a feedback loop where each interaction makes the system smarter about the project.

**LLMs make ALL routing decisions:** No Java code decides what type of content was pasted. The triage agent reasons about the content with full context and outputs structured routing decisions. The pipeline is a pure executor of LLM decisions.

**Agents build on each other's work:** The objective agent can see that the reconciler flagged a coverage gap yesterday. The reconciler can see that the objective agent created a new objective this morning. The resource agent remembers that Alice was overloaded for 3 consecutive runs. This cross-agent memory sharing is what turns isolated tool calls into continuous project intelligence.

### Intake Pipeline — How Content Reaches the Right Collections

The intake pipeline is the primary way data enters JavaClaw. A user pastes raw content — meeting notes, Confluence pages, Jira exports, Smartsheet plans, or any combination — into the Intake panel and hits enter. The system follows a **memory-first** architecture: content is always saved as project memory before any agent processes it. The triage agent — not keyword matching — decides which downstream agents run. The pipeline executes up to 6 phases, each following the Contextual Specialist Pattern.

#### Entry Point

`POST /api/intake/pipeline` with `{projectId, content, filePaths[]}` triggers `IntakePipelineService.startPipeline()`. This creates a source session for UI tracking and launches the pipeline asynchronously. Files can also be uploaded via `POST /api/intake/upload` (multipart), which saves them to disk and creates `uploads` documents with status `INBOX`.

#### Phase 0: Memory-First Ingest (always)

Before any agent runs, the pipeline saves the raw content as a `PROJECT`-scoped memory with 7-day TTL. This is the "memory-first" principle — nothing enters the system without being remembered. Subsequent intakes accumulate as memories, giving the triage agent and all downstream agents visibility into what the project has received before.

**Collections written:**
- `memories` — One document per intake. Fields: `scope: PROJECT`, `key: intake-{pipelineId}`, `tags: [intake, raw-content]`, `expiresAt: now + 7 days`

#### Phase 1: Triage (intake-triage agent)

The triage agent receives the raw content along with **existing project memories** from prior intakes. It calls `classify_content` — a pattern-matching tool that detects content type and extracts metadata (dates, people, Jira keys). The LLM then reasons about the content with full context: it can recognize that a new paste is an update to a topic from yesterday's intake, not a brand new topic.

The triage agent organizes content into distinct topics (decisions, open questions, action items per topic) and outputs a **structured routing block**:

```
### Routes
THREAD: yes/no — topics, ideas, architecture discussions, meeting notes, designs
TICKETS: yes/no — Jira exports, task lists, bug reports, ticket dumps
PLAN: yes/no — Smartsheet plans, milestone schedules, phase definitions, timelines
RESOURCES: yes/no — team assignments, capacity data, allocation spreadsheets
```

This routing block drives which downstream phases run. The LLM decides — not Java code. No keyword matching (`contains("JIRA")`), no hardcoded rules. File path hints (e.g., `.xlsx` files) are included in the prompt as context for the LLM, not as routing overrides.

**Collections written:** None directly. The triage output is a text classification + routing decision passed to subsequent phases. The triage agent's session and messages are written to `sessions`, `messages`, and `events`.

#### Phase 2: Thread Creation (thread-agent) — always runs

The thread agent receives the raw content + triage output, along with **existing threads for the project** and **project memories from recent intakes**. This context allows it to make intelligent decisions:

- If a topic matches an existing thread's title/subject, it calls `create_thread` with the SAME title — the tool automatically appends distilled key points to the existing thread and merges new decisions and actions
- If a topic is genuinely new, it creates a new thread with distilled content
- If topics overlap significantly, it merges them into a single thread

The thread agent **distills** content directly — it doesn't just pass raw text through. It extracts concise key points, decisions, and action items, writing organized markdown into the thread's `content` field. This replaces the need for a separate distillation phase.

Each `create_thread` call:
1. Checks for duplicates by title (case-insensitive) — returns `updated_existing` if appending to an existing thread
2. Creates a `ThreadDocument` with `projectIds`, `title`, `lifecycle: ACTIVE`, `content` (distilled markdown), `decisions[]`, and `actions[]`
3. Seeds the organized markdown as the first message in the thread (stored in `messages` with `sessionId == threadId`, `role: assistant`, `agentId: thread-agent`)

**Collections written:**
- `threads` — One document per topic (or updated existing). Fields populated: `threadId`, `projectIds[]`, `title`, `lifecycle`, `content` (distilled markdown), `decisions[]` (text + date), `actions[]` (text + assignee + status=OPEN), `createdAt`
- `messages` — One seed message per thread with the organized content

#### Phase 3: PM Agent (conditional — runs if triage routed TICKETS: yes)

The triage agent's routing block determines whether ticket data was identified. If `TICKETS: yes`, the PM agent processes the ticket data.

If file paths are provided, the agent first calls `excel` to read the spreadsheet. Then it calls `create_ticket` once per ticket found.

Each `create_ticket` call creates a `TicketDocument` with the original Jira key preserved in the title (e.g., "J-101: Build Evidence API"). Tickets without an epic are flagged as orphaned work in the description.

**Collections written:**
- `things` (category: TICKET) — One document per ticket. Payload fields: `title` (includes Jira key), `description` (epic, status, owner info), `priority` (HIGH/MEDIUM/LOW), `status: TODO`

#### Phase 4: Plan Agent (conditional — runs if triage routed PLAN: yes)

Runs in parallel with Phase 3 if the triage agent identified plan/timeline data (`PLAN: yes`). The plan agent calls `create_phase` for each phase and `create_milestone` for each milestone.

**Collections written:**
- `things` (category: PHASE) — One document per phase. Payload fields: `name`, `description`, `sortOrder`, `status: PENDING`
- `things` (category: MILESTONE) — One document per milestone. Payload fields: `name`, `targetDate`, `owner`, `status: UPCOMING`

#### Phase 5: Objective Agent (conditional — runs when project state warrants alignment analysis)

After PM and Plan agents complete (or when triggered by the scheduler), the objective agent follows the Contextual Specialist Pattern:

1. **Context Prep:** Loads existing objectives, existing threads (titles + content summaries), existing tickets, and memories tagged `coverage-analysis` from prior runs
2. **Reasoning:** The LLM doesn't just count tickets — it reads the "train of thought" in threads. It can realize that while there are 10 tickets for "Payment Facade," the threads show the team is stuck on "Evidence API," marking that objective as AT_RISK despite the good ticket count
3. **Structured Output:** Calls `compute_coverage` to get raw numbers, then creates/updates objectives with reasoned assessments, flagging blindspots and gaps
4. **Feedback Loop:** Saves a `coverage-analysis` memory so the next run can track trends ("coverage improved from 60% to 72%")

**Collections written:**
- `things` (category: OBJECTIVE) — One document per derived objective. Payload fields: `outcome`, `ticketIds[]`, `threadIds[]`, `coveragePercent`, `status: PROPOSED`
- `memories` — Coverage analysis summary for next run

#### Phase 6: Reconcile Agent (conditional — System Auditor)

The reconcile agent is the most powerful agent in the system because it has access to the memory of all other agents. It follows the Contextual Specialist Pattern:

1. **Context Prep:** Loads all project data (tickets, objectives, phases, milestones) plus memories from **previous reconciliation runs** and **other agents' recent outputs** (e.g., the objective agent's coverage analysis from Phase 5)
2. **Reasoning:** Cross-references sources and reasons about mismatches. Can correlate a triage intake note ("vendor delayed 2 weeks") with a milestone date to detect DATE_DRIFT. Can track recurring deltas ("this OWNER_MISMATCH was flagged 3 consecutive runs — escalating severity")
3. **Structured Output:** Calls `create_delta_pack` with all detected deltas, including resolution status for previously flagged items. For critical findings, calls `create_blindspot`
4. **Feedback Loop:** Saves a `reconciliation-summary` memory so the next run knows what was found, what was resolved, and what persists

Example deltas from the Story 2 pipeline test:
- **MISSING_EPIC** (HIGH): "J-104 has no epic assignment" — Jira vs Jira
- **DATE_DRIFT** (MEDIUM): "Design date 2026-02-10 vs milestone 2026-03-15" — Confluence vs Smartsheet
- **OWNER_MISMATCH** (HIGH): "Alice owns tickets, Bob owns milestone" — Jira vs Smartsheet
- **ORPHANED_WORK** (MEDIUM): "J-104 not mapped to any epic or objective" — Jira vs (none)

**Collections written:**
- `things` (category: DELTA_PACK) — One document per reconciliation run. Payload fields: `deltas[]` (deltaType, severity, title, description, sourceA, sourceB, suggestedAction), `summary` (totalDeltas, bySeverity, byType), `status: FINAL`
- `things` (category: BLINDSPOT) — One document per critical finding. Payload fields: `title`, `category` (e.g., MISSING_OWNER), `severity`, `description`, `status: OPEN`
- `memories` — Reconciliation summary for next run

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
Phase 0: memory-first ingest
        │  saves raw content as PROJECT memory (7-day TTL)
        ├─ creates → memories (raw-content, scope: PROJECT)
        │
        ▼
Phase 1: intake-triage (LLM-driven routing)
        │  loads project memories for context
        │  calls classify_content (metadata extraction)
        │  LLM organizes topics + outputs routing block
        │  (THREAD: yes/no, TICKETS: yes/no, PLAN: yes/no, RESOURCES: yes/no)
        │
        ▼
Phase 2: thread-agent (always runs)
        │  loads existing threads + project memories
        │  distills content into threads directly
        │  calls create_thread per topic (appends if existing)
        ├─ creates/updates → threads (distilled content, decisions[], actions[])
        ├─ creates → messages (seed content per thread)
        │
        ├──────────────────────────────────────────────┐
        ▼                                              ▼
Phase 3: pm (TICKETS: yes)              Phase 4: plan-agent (PLAN: yes)
        │  calls create_ticket                    │  calls create_phase
        ├─ creates → things (TICKET)              ├─ creates → things (PHASE)
        │                                         │  calls create_milestone
        │                                         ├─ creates → things (MILESTONE)
        │                                         │
        └──────────────┬───────────────────────────┘
                       ▼
             Phase 5: objective-agent
                       │  loads existing objectives + threads + memories
                       │  calls compute_coverage, reasons about risk
                       ├─ creates → things (OBJECTIVE, with coveragePercent)
                       ├─ creates → memories (coverage-analysis summary)
                       │
                       ▼
             Phase 6: reconcile-agent (System Auditor)
                       │  loads all data + previous reconciliation memories
                       │  reasons about deltas, tracks recurring findings
                       │  calls create_delta_pack
                       ├─ creates → things (DELTA_PACK, drift report)
                       │  calls create_blindspot (for critical items)
                       ├─ creates → things (BLINDSPOT, risk flags)
                       ├─ creates → memories (reconciliation summary)
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

The scenario test framework validates this end-to-end: `scenario-story-2-pipeline.json` runs the full pipeline with mock LLM responses and asserts that threads, tickets, phases, milestones, objectives, delta packs, and blindspots all appear in the correct collections with the expected data. `scenario-story-1-intake.json` tests pure meeting-note intake (3 topics, THREAD-only routing), and `scenario-story-1-reintake.json` tests the append-to-existing-thread flow (memory-first context enables update recognition).

### Scheduled Agent Execution

Seven default schedules are seeded on startup:

| Agent | Schedule | Priority | Purpose |
|---|---|---|---|
| distiller (cleanup) | 1am daily | 7 (highest) | Memory janitor — purges contradicted/stale memories, compresses fragments into executive summaries |
| reconcile-agent | 9am weekdays | 6 | Drift detection — reads previous reconciliation memory, tracks recurring vs. resolved deltas |
| resource-agent | 9am weekdays | 5 | Capacity check — reads past capacity snapshots, detects chronic overload patterns |
| objective-agent | 9am weekdays | 5 | Coverage analysis — reads thread content + past analyses, tracks coverage trends |
| checklist-agent | 9am weekdays | 5 | Stale checklist detection |
| plan-agent | 10am Mondays | 4 | Weekly milestone/phase status check |
| thread-extractor | 6pm weekdays | 4 | End-of-day unextracted artifact sweep |

Scheduled agents follow the same **Contextual Specialist Pattern** as pipeline agents — they load context + memories before executing. The 9am reconcile run can see what the 9am objective run found 5 minutes earlier. The 1am distiller cleanup reads all recent memories and compresses or purges them so the morning agents start with clean, sharp context.

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
└── things ─────────── via projectId + thingCategory
    ├── TICKET ─────── self-referential hierarchy (parentTicketId)
    │   └── RESOURCE_ASSIGNMENT ─ via payload.ticketId
    ├── OBJECTIVE ──── links to tickets + threads
    ├── PHASE ──────── ordered by payload.sortOrder
    │   ├── MILESTONE ─ via payload.phaseId
    │   └── CHECKLIST ─ via payload.phaseId
    ├── RESOURCE ───── team members
    ├── IDEA ────────── promotable to tickets
    ├── UPLOAD ─────── full-text searchable
    ├── INTAKE ─────── raw classifications
    ├── DELTA_PACK ─── drift reports
    ├── BLINDSPOT ──── risk flags
    ├── RECONCILIATION ─ source-to-ticket mapping
    ├── LINK ────────── curated URLs
    ├── REMINDER ───── timers
    └── CHECKLIST_TEMPLATE ─ reusable templates (global)

agents (global) ────── agent_schedules, future_executions, past_executions
memories (scoped) ──── GLOBAL / PROJECT / SESSION / THREAD
sessions (ephemeral) ─ deleted on restart, linked to threads via threadId
```

### Collections Reference (18)

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

#### Projects and Threads

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `projects` | ProjectDocument | Root aggregate — top-level container for all project data | projectId, name, status (ACTIVE/ARCHIVED/TEMPLATE), tags[] |
| `threads` | ThreadDocument | Persistent knowledge store — distilled project content organized by topic | threadId, projectIds[] (M:N), title, lifecycle (DRAFT/ACTIVE/CLOSED/MERGED), summary, content (distilled markdown), evidence[], decisions[], actions[] |

#### Things — Unified Domain Collection

All 16 domain entity types are stored in a single `things` collection using `ThingDocument`. Each document has a `thingCategory` discriminator and a flexible `Map<String, Object> payload` holding all type-specific fields. REST API URLs and DTOs remain unchanged — the unification is an internal storage optimization.

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `things` | ThingDocument | All domain entities (tickets, objectives, resources, phases, etc.) | id, projectId, projectName, thingCategory (16 values), payload (Map), createDate, updateDate |

**16 ThingCategory values and their payload fields:**

| Category | Purpose | Key Payload Fields |
|---|---|---|
| `TICKET` | Work items with self-referential hierarchy | title, status (TODO/IN_PROGRESS/REVIEW/DONE/BLOCKED), priority (LOW-CRITICAL), type (INITIATIVE/EPIC/STORY/SUBTASK), parentTicketId, assignedResourceId, objectiveIds[], externalRef (Jira key), storyPoints |
| `OBJECTIVE` | Sprint objectives with measurable coverage | sprintName, outcome, measurableSignal, coveragePercent, status (PROPOSED/COMMITTED/ACHIEVED/MISSED/DROPPED/AT_RISK), threadIds[], ticketIds[] |
| `PHASE` | Execution phases with entry/exit criteria | name, entryCriteria[], exitCriteria[], status (PENDING/ACTIVE/COMPLETED/BLOCKED), sortOrder |
| `MILESTONE` | Delivery milestones linked to phases | phaseId, name, targetDate, actualDate, status (UPCOMING/ON_TRACK/AT_RISK/MISSED/COMPLETED), objectiveIds[], ticketIds[], dependencies[] |
| `CHECKLIST` | Operational readiness and release checklists | phaseId, name, items[] (text, assignee, checked, notes), status (PENDING/IN_PROGRESS/COMPLETED) |
| `CHECKLIST_TEMPLATE` | Reusable checklist templates (global or project-scoped) | category (ORR/RELEASE_READINESS/ONBOARDING/SPRINT_CLOSE/DEPLOYMENT/ROLLBACK/CUSTOM), items[] |
| `RESOURCE` | Team members with skills and capacity | name, email, role (ENGINEER/DESIGNER/PM/QA), skills[], capacity (units), availability (0.0-1.0) |
| `RESOURCE_ASSIGNMENT` | Links resources to tickets with allocation | resourceId, ticketId, percentageAllocation |
| `IDEA` | Brainstorming items, promotable to tickets | title, status (NEW/REVIEWED/PROMOTED/ARCHIVED), promotedToTicketId |
| `DELTA_PACK` | Drift reports comparing data sources | deltas[] (deltaType, severity, title, sourceA, sourceB, suggestedAction), summary, status (DRAFT/FINAL/SUPERSEDED) |
| `BLINDSPOT` | Individual risk items found during reconciliation | title, category, severity (LOW-CRITICAL), status (OPEN/ACKNOWLEDGED/RESOLVED/DISMISSED) |
| `RECONCILIATION` | Source-to-ticket mapping with conflict detection | mappings[] (sourceRow, ticketId, matchType), conflicts[] (field, sourceValue, ticketValue, resolution) |
| `INTAKE` | Raw content received for processing | sourceType, classifiedAs, dispatchedTo[], status (RECEIVED/PROCESSING/DISPATCHED/FAILED) |
| `UPLOAD` | Processed documents with extracted metadata (full-text searchable) | source, title, content (text-indexed), people[], systems[], threadId, status (INBOX/THREADED/ARCHIVED) |
| `LINK` | External URLs grouped by category and bundled | url, title, category, bundleId, threadIds[], objectiveIds[], phaseIds[] |
| `REMINDER` | Recurring and one-shot timers | message, type (TIME_BASED/CONDITION_BASED), triggerAt, recurring, intervalSeconds |

**Tickets form a hierarchy:** INITIATIVE → EPIC → STORY → SUBTASK via the `parentTicketId` payload field. Tickets link to objectives, phases, and threads. The `externalRef` field holds Jira issue keys for imported tickets.

**Objectives drive alignment:** The objective-agent computes `coveragePercent` by mapping tickets and threads to each objective. The agent evaluates threads (what's being discussed), tickets (what's planned), and milestones (what must be delivered) to produce coverage percentages and identify orphaned work.

**Phases and milestones create execution structure:** The plan-agent creates phases with entry/exit criteria and links milestones to them. "Create a plan from the current threads" produces phases with linked milestones and entry/exit criteria.

**Capacity planning:** The resource-agent maps people → tickets → objectives to compute load. "Who is working on what?" reveals load percentages per resource with rebalancing recommendations.

**Delta types:** MISSING_EPIC, DATE_DRIFT, OWNER_MISMATCH, SCOPE_MISMATCH, DEPENDENCY_MISMATCH, COVERAGE_GAP, ORPHANED_WORK, CAPACITY_OVERLOAD, STALE_ARTIFACT, PRIORITY_MISMATCH, STATUS_MISMATCH

**Blindspot categories:** ORPHANED_TICKET, UNCOVERED_OBJECTIVE, EMPTY_PHASE, UNASSIGNED_WORK, MISSING_OWNER, DEPENDENCY_RISK, CAPACITY_GAP, SCOPE_OVERLAP, MISSING_TEST_SIGNAL, STALE_ARTIFACT

#### Memory

| Collection | Document | Purpose | Key Fields |
|---|---|---|---|
| `memories` | MemoryDocument | Project intelligence store — agent outputs, intake snapshots, analysis summaries (full-text searchable) | memoryId, scope (GLOBAL/PROJECT/SESSION/THREAD), key, content, tags[], projectId, sessionId, threadId, createdBy, expiresAt (TTL), lastEvaluatedAt |

**Four memory scopes with TTL:**
- **GLOBAL** — Shared across all projects (e.g., "user prefers Java 21") — **never expires**
- **PROJECT** — Tied to a project (e.g., "this repo uses Gradle") — **expires after 30 days**
- **SESSION** — Tied to a standalone session — **expires after 24 hours**
- **THREAD** — Tied to a thread (e.g., "sprint planning decisions") — **expires after 7 days**

**Threads vs Memories:** Threads are the persistent knowledge store — content comes in, gets distilled, and old ideas are replaced by new ones via the `content` field. Memories are expiring summaries, analysis outputs, and intake snapshots that rotate and disappear via MongoDB TTL indexes.

**Memory-First Intake:** All raw content entering the intake pipeline is first saved as a PROJECT-scoped memory with 7-day TTL (Phase 0). This means the triage agent and all downstream agents can reference prior intakes when classifying and organizing new content. Each subsequent intake builds on the project's accumulated context.

**Agent Feedback Loop:** Every specialist agent writes what it learned back to memory after running. This creates **cumulative intelligence** where each run builds on the last:
- **Objective agent** saves `coverage-analysis` memories — next run can track trends ("coverage improved from 60% to 72%")
- **Reconciler** saves `reconciliation-summary` memories — next run knows which deltas were previously flagged, which are resolved, which are recurring
- **Resource agent** saves `capacity-snapshot` memories — detects chronic overload patterns ("Alice flagged overloaded 3 consecutive runs")
- **Triage agent** reads all of these when classifying new content — can correlate a new intake note with a recently flagged delta

**Memory Lifecycle — Ingest, Accumulate, Compress, Forget, Expire:**
1. **Ingest** — Raw content saved as memory on every intake (Phase 0)
2. **Accumulate** — Agents write analysis summaries, creating a growing knowledge base
3. **Compress** — The distiller runs a nightly "Compression Pass" (1 AM): reads all recent fragments, synthesizes into executive summaries, deletes the fragments. Keeps the context window sharp and prevents bloat from heavy brainstorming days
4. **Forget** — The memory janitor (same nightly run) identifies counter-factual memories — e.g., an old "tentative date" contradicted by a newer confirmed date in a thread — and purges them. The system only references truth, not stale context
5. **Expire** — MongoDB TTL indexes automatically remove memories past their `expiresAt` timestamp

The `lastEvaluatedAt` field tracks when the memory janitor last reviewed each memory, preventing redundant evaluation.

Story 9 tests memory persistence — the distiller stores an S3 storage decision and later recalls it when asked "What did we decide about evidence storage?"

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

### Index Strategy

Key indexes defined in `mongo-init.js` and via Spring Data annotations:

| Collection | Index | Purpose |
|---|---|---|
| events | `{sessionId, seq}` unique | Event ordering and deduplication |
| messages | `{sessionId, seq}` unique | Message ordering |
| checkpoints | `{sessionId, stepNo}` desc | Step-level state snapshots |
| locks | `expiresAt` TTL | Auto-expiry of distributed locks |
| memories | `{content, key}` text, `{scope, key}`, `{projectId, scope}`, `expiresAt` TTL | Full-text search, scope-filtered recall, auto-expiry |
| things | `{projectId, thingCategory}` | Universal category-scoped queries |
| things | `{projectId, thingCategory, "payload.status"}` | Status-filtered queries |
| things | Category-specific partial filter indexes (9) | OBJECTIVE by sprintName, PHASE by sortOrder, RESOURCE_ASSIGNMENT by resourceId/ticketId, CHECKLIST/MILESTONE by phaseId, BLINDSPOT by deltaPackId, REMINDER by triggered+triggerAt, UPLOAD text search on title+content, TICKET by parentTicketId, IDEA by tags |
| future_executions | `{scheduledAt}` | Pickup queue for scheduler |
| agent_schedules | `{agentId, enabled}` | Active schedule lookup |

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

# All 50 scenarios (single JVM, ~10x faster)
bash run-scenarios.sh
```

### V2 Scenario Features

The V2 schema (schemaVersion: 2) supports:

- **Step types:** `context` (agent conversation), `seed` (REST API data seeding), `pipeline` (forced agent routing)
- **Seed actions:** Pre-populate project data via REST POST before running agent steps
- **Assertion types:** `sessionStatus`, `events` (containsTypes, minCounts), `mongo` (collection queries with countGte/exists), `messages` (anyAssistantContains), `http` (REST API response validation with status, body, jsonPath, jsonArrayMinSize)

### 50 Built-in Scenarios

| Scenario | What It Tests |
|---|---|
| **V1 Basic (13)** | General chat, coder, PM, memory, file tools, git, JBang, Python, HTTP, Excel |
| **V2 Framework (3)** | PM tools, memory, file tools with V2 assertions |
| **Agent-Specific (10)** | Thread agent, objective agent, checklist agent, intake triage, plan agent, reconcile agent, resource agent, thread intake, extraction, intake pipeline |
| **Story E2E (10)** | Stories 2-10: alignment pipeline, sprint objectives, resource load, plan creation, checklist generation, scheduled reconcile, on-demand agents, memory persistence, daily schedule reset |
| **Tool Coverage (14)** | Per-tool scenario tests for all domain tools: create/read tickets, objectives, resources, phases, milestones, checklists, ideas, blindspots, delta packs, uploads, links, reminders, reconciliations, thread merge |

Each story scenario seeds prerequisite data, runs agents with mock responses, and asserts both MongoDB state and REST API responses — verifying that the cockpit UI would display the correct data.

### Maven Unit Tests

272 Maven tests across all modules (unit + integration via embedded MongoDB):

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

### How to Add a New Entity Type

All domain entities use the unified `things` collection. To add a new entity type:

1. Add a new value to `ThingCategory` enum in `protocol/src/main/java/.../protocol/api/ThingCategory.java`
2. Create a DTO record in `protocol/src/main/java/.../protocol/api/` for the REST API contract
3. Create a REST controller in `gateway/src/main/java/.../gateway/controller/` using `ThingService` for CRUD
4. (Optional) Add partial-filter indexes to `mongo-init.js` for category-specific query fields
5. (Optional) Create a tool in `tools/` if agents need to create/read this entity type

No new Document classes or Repository interfaces needed — `ThingDocument` and `ThingRepository` handle all domain entities.

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
| `run-scenarios.sh` | Run all 50 scenario tests in a single JVM |
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
