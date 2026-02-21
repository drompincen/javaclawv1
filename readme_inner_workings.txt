JavaClaw v1 — Inner Workings
=============================

This document explains how JavaClaw's agent pipelines work, how agents
communicate, how memories are kept, and which use cases are deterministic
vs. agentic. It also compares JavaClaw with OpenClaw and Claude Code.


1. AGENT PIPELINES
==================

Everything runs through one engine: AgentGraphBuilder. It is the single
execution core for all LLM-driven work. Higher-level orchestrators
(IntakePipelineService, ExecutionEngineService) build on top of it —
they are not alternative paths.

The execution stack:

  REST API or Scheduler
       │
       ▼
  ┌──────────────────┐  spawns sessions, manages lifecycle
  │    AgentLoop     │  (distributed locks, dual-lookup, state restore)
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐  THE engine — every LLM call goes through here
  │ AgentGraphBuilder│
  └──────────────────┘
       │
       ▼
  ┌──────────────────┐
  │    LlmService    │  Anthropic / OpenAI / TestMode (swappable)
  └──────────────────┘


A) The Graph Engine (AgentGraphBuilder)
---------------------------------------
The core pattern is controller → specialist → checker:

  User message
       │
       ▼
  ┌──────────┐   JSON routing decision
  │ CONTROLLER│──→ {"delegate":"pm", "subTask":"create sprint plan"}
  └──────────┘   or {"respond":"direct answer here"}
       │
       ▼
  ┌──────────┐   Executes tools, produces output
  │ SPECIALIST│──→ (pm, coder, generalist, thread-agent, etc.)
  └──────────┘
       │
       ▼
  ┌──────────┐   {"pass":true/false, "feedback":"..."}
  │  CHECKER  │──→ If fail: feedback injected, loop retries (up to 3x)
  └──────────┘

Key details:
- Controller and checker run on "isolated forks" — their internal state
  is discarded. Only the routing decision (controller) or pass/fail
  verdict (checker) crosses back to the main state.
- The specialist runs on a separate fork; its final output is merged back
  as a single assistant message.
- Each specialist step can invoke tools (create_ticket, merge_threads,
  compute_coverage, etc.). Tool calls are parsed from XML in the LLM
  response. Up to 50 tool steps per interactive session, 5 per pipeline.
- If the checker fails, its feedback is injected as a user message and
  the specialist re-runs. Three retries max.
- A "forced agent" mode bypasses controller and checker entirely — the
  intake pipeline uses this to drive each phase with a specific agent
  (skips straight to the specialist, no routing or checking overhead).

Built in: AgentGraphBuilder.java (runtime module)


B) Intake Pipeline — built ON TOP of the graph
-----------------------------------------------
IntakePipelineService is a higher-level orchestrator. It does NOT bypass
AgentGraphBuilder — each phase spawns a full agent session via AgentLoop,
which calls graphBuilder.runGraph() internally.

  IntakePipelineService
       │
       ├─ Phase 0: Save raw content → memories (deterministic, no graph)
       │
       ├─ Phase 1: agentLoop.startAsync(triageSession)
       │              └─→ AgentGraphBuilder.runGraph() with forced agent "intake-triage"
       │
       ├─ Phase 2: agentLoop.startAsync(generalistSession)
       │              └─→ AgentGraphBuilder.runGraph() with forced agent "generalist"
       │
       ├─ Phase 5: agentLoop.startAsync(objectiveSession)   [if TICKETS/PLAN]
       │              └─→ AgentGraphBuilder.runGraph() with forced agent "objective-agent"
       │
       ├─ Phase 6: agentLoop.startAsync(reconcileSession)   [if TICKETS/PLAN]
       │              └─→ AgentGraphBuilder.runGraph() with forced agent "reconcile-agent"
       │
       └─ Phase 7: agentLoop.startAsync(resourceSession)    [if RESOURCES/TICKETS]
                      └─→ AgentGraphBuilder.runGraph() with forced agent "resource-agent"

Phases execute sequentially — each waits for the previous to complete
(with per-phase timeouts: 90s triage, 300s generalist, etc.).

Routing has a two-layer design:
1. LLM-driven: the triage agent classifies the input
2. Deterministic safety net: keyword scan (detectContentSignals) catches
   under-routing — e.g., if the LLM misses "capacity" or Jira key
   patterns, the deterministic layer forces the right route.

Built in: IntakePipelineService.java (runtime module)


C) The one exception: Ask Claw
-------------------------------
AskController is the ONLY endpoint that bypasses the graph entirely.
It calls llmService.blockingResponse() directly — no AgentLoop, no
AgentGraphBuilder, no sessions, no tools. See section 4 for details.


D) Test mode: same engine, swapped LLM
---------------------------------------
In scenario tests, AgentGraphBuilder still runs fully — orchestration
logic, tool parsing, tool execution, state management, events. The only
thing swapped is the LLM provider: TestModeLlmService returns predefined
responseFallback strings from the scenario JSON instead of calling Claude.
The graph never knows the difference.


2. AGENT-TO-AGENT COMMUNICATION
================================

Agents never call each other directly. All communication flows through
MongoDB collections:

  ┌────────┐     ┌─────────┐     ┌────────┐
  │Agent A │────→│ MongoDB │────→│Agent B │
  └────────┘     └─────────┘     └────────┘

The shared collections are:

  messages     — Chat history. Each agent session reads its messages on
                 start and persists new assistant messages on completion.

  sessions     — Session state (CREATED → ACTIVE → COMPLETED/FAILED).
                 The intake pipeline polls session status to know when
                 each phase finishes.

  checkpoints  — AgentState snapshots stored as JSON strings. Used for
                 crash recovery and state restoration.

  memories     — Long-term knowledge (see section 3). Agents read
                 project memories as context and write new memories via
                 the memory tool.

  events       — Server-Sent Events (SSE). Every meaningful step emits
                 a typed event (AGENT_SWITCHED, TOOL_CALL_STARTED,
                 MODEL_TOKEN_DELTA, INTAKE_PIPELINE_COMPLETED, etc.)
                 streamed to the UI in real time.

  tickets, threads, objectives, resources, etc.
               — Domain objects. One agent creates them (via tools),
                 another reads them (via context or tools).

This architecture means:
- No in-process coupling between agents
- Any agent can be restarted independently
- The pipeline can be debugged by inspecting MongoDB collections
- Race conditions are handled via optimistic locking (sessions)
  and distributed leases (scheduler)


3. HOW MEMORIES ARE KEPT
=========================

Memory Document Structure (MemoryDocument.java):

  memoryId    — UUID primary key
  scope       — GLOBAL | PROJECT | SESSION | THREAD
  projectId   — Links PROJECT-scoped memories to a project
  threadId    — Links THREAD-scoped memories to a thread
  sessionId   — Links SESSION-scoped memories
  key         — Logical name (e.g., "intake-a1b2c3d4", "sprint-plan")
  content     — Free-text markdown
  tags        — String list for filtering
  createdBy   — Agent ID or "intake-pipeline"
  expiresAt   — MongoDB TTL index; auto-deleted when expired

Memory Scopes:

  GLOBAL    — Shared across all projects and sessions. Rare.
  PROJECT   — Shared across all sessions within a project.
              Intake raw content is stored here (7-day TTL).
  SESSION   — Private to a single session.
  THREAD    — Linked to a specific thread; persists across sessions.

Who writes memories:
- IntakePipelineService: writes raw content as PROJECT-scoped memory
  in Phase 0.
- DistillerService: auto-distills completed sessions into SESSION or
  THREAD-scoped memories (summaries of what happened).
- Agent tools: any specialist with the "memory" tool can read/write
  memories during execution.
- Memory REST endpoints: CRUD via /api/memories.

Who reads memories:
- Intake pipeline: loads prior PROJECT memories into triage and
  generalist prompts (so the LLM knows what's already in the project).
- AskController: includes all project memories in the grounded Q&A
  context.
- Agent sessions: loaded into AgentState on startup.

TTL Expiry:
  MongoDB automatically deletes documents when now > expiresAt.
  Intake raw content expires after 7 days. Distilled summaries
  have no TTL (persist indefinitely).


4. DETERMINISTIC vs. AGENTIC USE CASES
========================================

┌──────────────────────────────┬──────────────┬──────────────────────────┐
│ Use Case                     │ Type         │ How It Works             │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Ask Claw (POST /api/ask)     │ Deterministic│ Fetches ALL project data │
│                              │ context +    │ from 11 MongoDB          │
│                              │ single LLM   │ collections, pre-computes│
│                              │ call         │ effectiveHours,          │
│                              │              │ allocatedSP, assignment  │
│                              │              │ summary. Single          │
│                              │              │ llmService.blockingResponse│
│                              │              │ — no tools, no agents.   │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Context commands             │ Fully        │ "whereami", "use project │
│ (whereami, use project X)    │ deterministic│ X", "use thread X" —     │
│                              │              │ handled in AgentLoop     │
│                              │              │ without any LLM call.    │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ CRUD endpoints               │ Fully        │ REST controllers in      │
│ (tickets, resources, etc.)   │ deterministic│ gateway module. No LLM.  │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Intake Pipeline              │ Hybrid       │ Phase 0 is deterministic.│
│ (meeting notes, Jira import) │              │ Routing is LLM + keyword │
│                              │              │ safety net. Phases 1-7   │
│                              │              │ each spawn agent sessions│
│                              │              │ with LLM + tool calls.   │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Interactive chat              │ Fully        │ Full controller →        │
│ (user sends message)         │ agentic      │ specialist → checker     │
│                              │              │ loop via AgentGraphBuilder│
│                              │              │ with tool execution.     │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Scheduled reconciliation     │ Fully        │ ExecutionEngineService   │
│ (cron jobs)                  │ agentic      │ spawns agent sessions    │
│                              │              │ for reconcile, resource, │
│                              │              │ objective, checklist,    │
│                              │              │ plan agents on schedule. │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Distillation                 │ LLM-driven   │ DistillerService runs    │
│ (session summary)            │              │ after session completion,│
│                              │              │ asks LLM to summarize    │
│                              │              │ into memory documents.   │
├──────────────────────────────┼──────────────┼──────────────────────────┤
│ Agent bootstrap              │ Fully        │ Seeds 16 agents + 6     │
│ (startup)                    │ deterministic│ cron schedules into      │
│                              │              │ MongoDB on @PostConstruct│
└──────────────────────────────┴──────────────┴──────────────────────────┘

Design principle: Deterministic where correctness matters (data assembly,
capacity math, assignment resolution), agentic where intelligence matters
(classification, summarization, multi-step reasoning).


5. THE 16 DEFAULT AGENTS
=========================

Seeded by AgentBootstrapService on startup:

  controller        — CONTROLLER role. Routes to specialists.
  reviewer          — CHECKER role. Validates specialist output.
  coder             — SPECIALIST. Code generation, file operations.
  pm                — SPECIALIST. Project management, ticket creation.
  generalist        — SPECIALIST. Swiss-army agent for intake hydration.
  reminder          — SPECIALIST. Memory management, reminders.
  distiller         — SPECIALIST. Session summarization.
  thread-extractor  — SPECIALIST. Extracts threads from messages.
  thread-agent      — SPECIALIST. Thread CRUD, merge operations.
  objective-agent   — SPECIALIST. Sprint coverage computation.
  checklist-agent   — SPECIALIST. Checklist creation and tracking.
  intake-triage     — SPECIALIST. Content classification.
  plan-agent        — SPECIALIST. Phase and milestone creation.
  reconcile-agent   — SPECIALIST. Delta packs, drift detection.
  resource-agent    — SPECIALIST. Capacity analysis, assignments.
  ask-claw          — SPECIALIST. Memory-only access for Q&A.

6 default CRON schedules run these agents automatically:
  - reconcile-agent: weekdays 9am
  - resource-agent: weekdays 9am
  - objective-agent: weekdays 9am
  - checklist-agent: weekdays 9am
  - plan-agent: Mondays 10am
  - thread-extractor: weekdays 6pm


6. THE CODER AGENT — CAPABILITIES
===================================

The coder is the most powerful specialist agent. It has unrestricted
tool access (allowedTools: "*") and is described as a "senior software
engineer with the ability to READ FILES, WRITE FILES, and EXECUTE CODE."

The controller routes to the coder when the user's request involves
coding, debugging, file editing, running programs, or any technical
task. The reviewer (checker) validates the coder's output before it
reaches the user.

A) File System — Read, Write, Search
-------------------------------------

  read_file       Read any file by path. Returns full content.
                  Risk: READ_ONLY (no approval needed)

  list_directory  List files and subdirectories in a path.
                  Returns names, sizes, types.
                  Risk: READ_ONLY

  search_files    Glob-pattern search (e.g., **/*.java, src/**/Test*)
                  Risk: READ_ONLY

  write_file      Create or overwrite a file with arbitrary content.
                  Auto-creates parent directories.
                  Risk: WRITE_FILES (requires user approval)

Typical workflow:
  1. User: "What does the TicketController do?"
  2. Coder calls read_file on TicketController.java
  3. Coder explains the code
  4. User: "Add a PATCH endpoint for priority"
  5. Coder calls write_file with the modified source (approval required)


B) Code Execution — Java, Python, Shell
-----------------------------------------

  jbang_exec      Write and execute Java code in a single step.
                  Parameters: code (String), timeout_seconds (default 60)
                  Auto-compiles with Java 21+, cleans up temp files.
                  Risk: EXEC_SHELL (requires user approval)

  python_exec     Write and execute Python code.
                  Parameters: code (String), timeout_seconds (default 60)
                  Auto-detects python3 or python. Cleans up temp files.
                  Risk: EXEC_SHELL (requires user approval)

  shell_exec      Execute any shell command.
                  Parameters: command (String), timeout_seconds (default 30)
                  Returns: { exitCode, stdout, stderr }
                  Risk: EXEC_SHELL (requires user approval)

All three return structured output with exit code, stdout, and stderr.

Example — "Write a Fibonacci program and run it":
  1. Coder calls jbang_exec with Java Fibonacci code
  2. System compiles and runs it, returns output
  3. Coder presents the result

Example — "Run the tests":
  1. Coder calls shell_exec with "mvn test -pl runtime"
  2. System returns test output
  3. Coder summarizes pass/fail


C) Version Control — Git Operations
-------------------------------------

  git_status      Show working tree status (porcelain format).
                  Risk: READ_ONLY

  git_diff        Show diff of changes. Optional: staged=true for
                  staged-only diff.
                  Risk: READ_ONLY

  git_commit      Stage all changes (git add -A) and commit.
                  Parameters: message (String)
                  Risk: WRITE_FILES (requires user approval)

Example — "What changed since last commit?":
  1. Coder calls git_status → sees modified files
  2. Coder calls git_diff → sees actual changes
  3. Coder summarizes the diff


D) Network — HTTP Requests
----------------------------

  http_get        Make GET requests to any URL.
                  Returns: { status, body } (first 10KB)
                  10s connect timeout, 30s request timeout.
                  Risk: NETWORK_CALLS (no approval needed)

Example — "Check if the health endpoint is up":
  1. Coder calls http_get on http://localhost:8080/health
  2. Coder reports the status


E) Data — Excel/Spreadsheet
-----------------------------

  excel           Read and write .xlsx/.xls files.
                  Operations: read, write, list_sheets
                  Parameters: file_path, sheet_name, range, data
                  Risk: READ_ONLY (read) / WRITE_FILES (write)

Example — "Parse the Jira export spreadsheet":
  1. Coder calls excel with operation=list_sheets
  2. Coder calls excel with operation=read, sheet_name, range
  3. Coder presents the data in a table


F) Memory — Persistent Knowledge
----------------------------------

  memory          Store, recall, and delete persistent memories.
                  Operations: store, recall, delete
                  Scopes: GLOBAL, PROJECT, SESSION, THREAD
                  Auto-expiration: SESSION=24h, THREAD=7d, PROJECT=30d
                  Risk: WRITE_FILES (store/delete require approval)

Example — "Remember that we use Spring Boot 3.3.1":
  1. Coder calls memory with operation=store, scope=PROJECT
  2. Fact persists across future sessions


G) Project Management Tools
-----------------------------

The coder also has access to ALL domain tools (create_ticket,
create_thread, create_objective, merge_threads, compute_coverage, etc.)
because its allowedTools is "*". In practice, the controller usually
routes PM tasks to the pm or generalist agent instead, but the coder
can do it if asked directly.


H) Security: The Approval Gate
-------------------------------

The coder can read anything freely, but destructive actions require
human approval before execution:

  ┌─────────────────┬──────────────┬─────────────────────┐
  │ Risk Profile     │ Approval     │ Tools               │
  ├─────────────────┼──────────────┼─────────────────────┤
  │ READ_ONLY       │ No           │ read_file,          │
  │                 │              │ list_directory,     │
  │                 │              │ search_files,       │
  │                 │              │ git_status, git_diff│
  │                 │              │ excel (read),       │
  │                 │              │ http_get            │
  ├─────────────────┼──────────────┼─────────────────────┤
  │ WRITE_FILES     │ Yes (5min    │ write_file,         │
  │                 │  timeout)    │ git_commit,         │
  │                 │              │ memory (store/del), │
  │                 │              │ excel (write)       │
  ├─────────────────┼──────────────┼─────────────────────┤
  │ EXEC_SHELL      │ Yes (5min    │ shell_exec,         │
  │                 │  timeout)    │ jbang_exec,         │
  │                 │              │ python_exec         │
  └─────────────────┴──────────────┴─────────────────────┘

When a tool requires approval:
  1. AgentGraphBuilder emits APPROVAL_REQUESTED event
  2. UI shows the tool call and asks user to approve/deny
  3. If approved: tool executes, result fed back to coder
  4. If denied or timeout: "Tool call denied or timed out"
  5. Coder receives the denial and adjusts its approach

This means the coder can plan multi-step operations (read → analyze →
write → execute → verify) but the user stays in control of anything
that modifies the filesystem or runs commands.


I) What You Can Ask the Coder To Do
-------------------------------------

  "Read the pom.xml and explain the module structure"
  "Find all classes that implement the Tool interface"
  "Write a unit test for TicketController"
  "Run mvn test and tell me what failed"
  "Write a Python script to analyze the Jira export CSV"
  "Create a JBang script that calls the health endpoint"
  "Check git status and commit with message 'fix: capacity calc'"
  "Search for all TODO comments in the codebase"
  "Read the meeting notes file and summarize the action items"
  "Write a shell script to seed test data via curl"
  "Fetch https://api.example.com/status and show me the response"
  "Compare the two config files and list the differences"

The coder handles the full development lifecycle: explore → understand →
modify → test → commit. The reviewer validates each step. The user
approves any writes or executions.


7. EXECUTION INFRASTRUCTURE
=============================

AgentLoop (AgentLoop.java):
  The async execution wrapper. For each session:
  1. Acquires distributed lock via SessionLockService
  2. Dual-lookup: tries SessionRepository first, then ThreadRepository
     (same ID space, different semantics)
  3. Loads/restores AgentState from MongoCheckpointSaver
  4. Loads all messages from MongoDB (including multimodal parts)
  5. Checks for context commands (short-circuit, no LLM)
  6. Delegates to AgentGraphBuilder.runGraph()
  7. Persists new messages, updates session status
  8. Releases lock in finally block

ExecutionEngineService (scheduler):
  Polls MongoDB for due FutureExecutionDocuments every 5 seconds.
  Claims via optimistic CAS-style locking with 90-second leases.
  Spawns virtual threads (Java 21) for each execution.
  Handles retries with exponential backoff, stale lease recovery,
  and past execution history recording.

DefaultLlmService:
  Single LLM gateway for the entire system. Supports Anthropic
  (Claude Sonnet 4.5) and OpenAI (GPT-4o). Features:
  - Lazy model creation for runtime-configured API keys
  - Exponential backoff retry (3 attempts: 2s/4s/8s)
  - Streaming (for user-facing) and blocking (for controller/checker)
  - Multimodal message support (images, media)


8. HOW JAVACLAW DIFFERS FROM OPENCLAW
=======================================

OpenClaw (github.com/nicepkg/openclaw) is a TypeScript/Node.js personal
AI assistant. Key architectural differences:

  ┌─────────────────────┬──────────────────────┬───────────────────────┐
  │                     │ JavaClaw             │ OpenClaw              │
  ├─────────────────────┼──────────────────────┼───────────────────────┤
  │ Language            │ Java 21, Spring Boot │ TypeScript, Node.js   │
  │ LLM Framework       │ Spring AI 1.0.0-M6   │ PI Agent Framework    │
  │ Persistence         │ MongoDB 7 (replica)  │ JSONL files (local)   │
  │ Agent Pattern       │ Controller→Specialist│ Multi-agent routing   │
  │                     │ →Checker triad       │ via Gateway control   │
  │                     │                      │ plane                 │
  │ Primary Use Case    │ Engineering manager  │ Personal assistant    │
  │                     │ intelligence engine  │ across 11+ messaging  │
  │                     │ (sprints, capacity,  │ channels (Slack,      │
  │                     │ tickets, resources)  │ Discord, Telegram,    │
  │                     │                      │ WhatsApp, etc.)       │
  │ Model Support       │ Anthropic + OpenAI   │ 2000+ models via      │
  │                     │ (2 providers)        │ multi-provider router │
  │ Memory              │ 4-scope MongoDB with │ Two-tier: session     │
  │                     │ TTL indexes          │ JSONL + long-term     │
  │                     │                      │ file-based            │
  │ UI                  │ Bloomberg terminal   │ Channel-native (each  │
  │                     │ (green-on-black,     │ platform's own UI)    │
  │                     │ keyboard-driven)     │                       │
  │ Scheduling          │ Built-in cron engine │ No built-in scheduler │
  │                     │ with distributed     │                       │
  │                     │ leasing              │                       │
  │ Domain Model        │ Rich: projects,      │ Minimal: channels,    │
  │                     │ threads, tickets,    │ conversations,        │
  │                     │ objectives, sprints, │ memories              │
  │                     │ resources, capacity, │                       │
  │                     │ checklists, phases   │                       │
  │ Tool Execution      │ SPI-based Tool       │ Plugin-based actions  │
  │                     │ interface + registry │ + MCP tools           │
  │ License             │ MIT                  │ MIT                   │
  └─────────────────────┴──────────────────────┴───────────────────────┘

In short: OpenClaw is a channel-centric personal assistant that connects
to messaging platforms. JavaClaw is a domain-specific intelligence engine
for engineering management with deep project data modeling.


9. HOW JAVACLAW DIFFERS FROM CLAUDE CODE
==========================================

Claude Code is Anthropic's official CLI tool for AI-assisted coding.

  ┌─────────────────────┬──────────────────────┬───────────────────────┐
  │                     │ JavaClaw             │ Claude Code           │
  ├─────────────────────┼──────────────────────┼───────────────────────┤
  │ Architecture        │ Multi-agent with     │ Single-agent reactive │
  │                     │ controller routing,  │ loop: user → LLM →   │
  │                     │ specialist execution,│ tool → result → LLM  │
  │                     │ checker validation   │                       │
  │ Agent Count         │ 16 specialized agents│ 1 agent (the CLI)    │
  │ Primary Domain      │ Engineering manager  │ Software engineering  │
  │                     │ workflows: sprints,  │ (code editing, git,  │
  │                     │ capacity planning,   │ file operations,      │
  │                     │ ticket tracking,     │ debugging)            │
  │                     │ resource allocation  │                       │
  │ Data Model          │ MongoDB with 15+     │ File-based context   │
  │                     │ collections: tickets,│ (CLAUDE.md, project  │
  │                     │ threads, objectives, │ files, conversation   │
  │                     │ resources, checklists│ history)              │
  │ State Persistence   │ MongoDB (sessions,   │ Conversation log +   │
  │                     │ checkpoints,         │ auto-memory directory │
  │                     │ memories with TTL)   │                       │
  │ Scheduling          │ Built-in cron engine │ None (on-demand only) │
  │ Intake Processing   │ 7-phase pipeline:    │ User messages only    │
  │                     │ triage → hydrate →   │ (no classification    │
  │                     │ objectives → reconcile│ or multi-phase        │
  │                     │ → resources          │ processing)           │
  │ Tool System         │ SPI-based Java tools │ Built-in tools (Read, │
  │                     │ (create_ticket,      │ Write, Edit, Bash,    │
  │                     │ compute_coverage,    │ Glob, Grep, etc.) +   │
  │                     │ merge_threads, etc.) │ MCP extensibility     │
  │ Verification        │ Checker agent        │ User review (no       │
  │                     │ validates output     │ automated checker)    │
  │ UI                  │ Bloomberg terminal   │ Terminal CLI + IDE    │
  │                     │ + web cockpit        │ integrations          │
  │ Proactive Work      │ Yes — scheduled      │ No — only responds to │
  │                     │ reconciliation,      │ user input            │
  │                     │ capacity analysis,   │                       │
  │                     │ drift detection      │                       │
  │ Model               │ Anthropic + OpenAI   │ Anthropic only        │
  └─────────────────────┴──────────────────────┴───────────────────────┘

The fundamental difference: Claude Code is a reactive coding assistant
that responds to developer commands. JavaClaw is a proactive intelligence
engine that continuously monitors project health, detects drift, computes
capacity, reconciles plans against reality, and provides grounded answers
from structured data — all without waiting for the user to ask.

JavaClaw is to engineering management what a Bloomberg terminal is to
finance: a real-time information system with AI augmentation, not just
a chatbot.


10. DATA FLOW EXAMPLE: "Which developer has the most capacity?"
================================================================

To illustrate the deterministic vs. agentic distinction, here's the exact
flow when a user asks this question via Ask Claw:

  1. POST /api/ask { projectId, question }
  2. AskController.buildProjectContext() queries MongoDB:
     - threads, objectives, tickets, blindspots, resources,
       resource_assignments, checklists, phases, milestones,
       delta_packs, memories (11 collections)
  3. For each resource, JAVA CODE computes:
     - effectiveHours = capacity × availability
     - allocatedSP = sum of storyPoints from assigned tickets
  4. Builds structured text context:
       RESOURCE CAPACITY SUMMARY:
       Joe Martinez: effectiveHours=32, allocatedSP=19, tickets=4
       Alice Chen: effectiveHours=40, allocatedSP=5, tickets=1
       Bob Taylor: effectiveHours=20, allocatedSP=5, tickets=1
  5. Single LLM call with instruction: "Use ONLY the project data below"
  6. LLM reads pre-computed numbers and composes a human-readable answer
  7. Return { answer, sources }

No agents. No tool calls. No multi-step reasoning. The LLM's job is
presentation and synthesis — all the data work is done deterministically
in Java. This is why the answer is grounded: the LLM can't hallucinate
numbers that are already computed.

Compare this with the agentic flow for intake:

  1. POST /api/intake { projectId, content: "meeting notes..." }
  2. Phase 1: Triage agent classifies content (LLM decides routing)
  3. Phase 2: Generalist agent reads the content and calls tools:
     - create_thread("Payment Handler Refactor")
     - create_ticket("PAY-201", "Refactor card handler", sp=5)
     - create_ticket("PAY-202", ...)
     - create_resource("Joe Martinez", capacity=40)
  4. Phase 5: Objective agent calls compute_coverage()
  5. Phase 6: Reconcile agent calls create_delta_pack()
  6. Phase 7: Resource agent calls capacity_report()

Here the LLM is making decisions: what threads to create, what tickets
to extract, how to classify content. Tool calls are the mechanism by
which LLM decisions become persistent state in MongoDB.
