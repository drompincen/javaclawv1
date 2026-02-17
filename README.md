# JavaClaw

**An AI-powered engineering manager's cockpit.** JavaClaw is a Claude Code-like system built with Java 21, Spring AI, LangGraph4j, and MongoDB. It combines multi-agent orchestration, persistent memory, real-time event streaming, and a Bloomberg terminal-style UI into a personal assistant that can read codebases, manage projects, track teams, and execute code — all from your keyboard.

## Vision

JavaClaw is designed to be the **single pane of glass** for an engineering manager or tech lead who needs to:

- **Understand codebases** — Read, document, and explain code across multiple projects
- **Manage projects and sprints** — Create tickets, track sprint objectives week-by-week, mark-to-market on Jira tickets
- **Track team capacity** — See who's working on what, who has bandwidth, assign resources across projects
- **Integrate with existing tools** — Read Jira ticket exports (Excel), check Confluence designs, cross-reference with codebase state
- **Execute code on the fly** — Run Java (via JBang) or Python scripts directly from the agent
- **Remember context** — Persistent memory across sessions so the AI remembers your preferences, project patterns, and team dynamics
- **Work with multiple agents** — A controller routes tasks to specialists (coder, reviewer, analyst), each with their own skills and toolset
- **Stay keyboard-driven** — Bloomberg terminal aesthetic with green-on-black, every action accessible via shortcut keys (F1-F12, Ctrl combos)
- **Bridge the web gap** — When agents need current information, they ask you to search; your browser opens automatically and you paste results back

This is not just a chatbot — it's an **orchestration platform** where AI agents collaborate via MongoDB change streams, persist their knowledge, and loop through quality checks before delivering results.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  javaclawui.java (JBang Bloomberg Terminal UI)              │
│  Green-on-black, keyboard-driven, F1-F12 shortcuts          │
│  Sessions | Chat | Agent Pane | Search Request | Memory     │
└────────────────────────────┬────────────────────────────────┘
                             │ REST + WebSocket
         ┌───────────────────┼───────────────────┐
         ▼                                       ▼
┌─────────────────┐                   ┌─────────────────────┐
│     gateway      │                   │         ui          │
│  REST + WebSocket│                   │  Swing + FlatLaf    │
│   (headless)     │                   │   (Spring + Swing)  │
└────────┬─────────┘                   └──────────┬──────────┘
         │                                        │
         └──────────────┬─────────────────────────┘
                        ▼
              ┌───────────────────┐
              │      runtime      │
              │  Multi-Agent      │
              │   Orchestration   │
              │  Controller →     │
              │   Specialist →    │
              │    Checker loop   │
              │  LLM (Spring AI)  │
              │  Memory system    │
              │  Checkpointing    │
              └────────┬──────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
┌──────────────┐ ┌──────────┐ ┌─────────────┐
│  persistence │ │ protocol │ │    tools     │
│  MongoDB     │ │  DTOs    │ │  16 tools    │
│  Documents   │ │  Enums   │ │  via SPI     │
│  Repos       │ │  Events  │ │  Excel, Py,  │
│  Change      │ │  WS msgs │ │  JBang, Git, │
│  Streams     │ │          │ │  Memory, ... │
└──────┬───────┘ └──────────┘ └─────────────┘
       │
       ▼
┌──────────────┐
│   MongoDB    │
│  Replica Set │
│  (rs0)       │
│  Agents,     │
│  Memories,   │
│  Events,     │
│  Sessions    │
└──────────────┘
```

### Module Overview

| Module | Purpose |
|---|---|
| **protocol** | Shared DTOs, enums (AgentRole, SessionStatus, ToolRiskProfile), event types (40+), WebSocket message contracts. Pure Java records — no Spring dependencies. |
| **persistence** | MongoDB documents (20+ collections), repositories, and `ChangeStreamService` for real-time reactive streaming. Includes MemoryDocument, AgentDocument, MessageDocument with multimodal support. |
| **runtime** | Multi-agent engine: `AgentLoop` orchestrates sessions, `AgentGraphBuilder` runs the controller→specialist→checker loop, `LlmService` integrates with Anthropic/OpenAI, `AgentBootstrapService` seeds default agents, `ReminderScheduler` handles recurring timers. |
| **tools** | 17 built-in tools loaded via Java SPI: file I/O, shell, git, JBang, Python, Excel, memory, HTTP, human search, project management. |
| **gateway** | Spring Boot REST + WebSocket server. Controllers for sessions, agents, specs, tools, reminders, memory, search responses, and API key configuration. |
| **ui** | Swing desktop application with FlatLaf. Project sidebar, tabbed views (Thread, Board, Dashboard, Resources, Ideas). Connects directly to MongoDB. |

## Key Features

### Multi-Agent Orchestration

JavaClaw uses a **controller → specialist → checker** pattern:

1. **Controller agent** analyzes the user's task and decides which specialist should handle it
2. **Specialist agent** (e.g., coder) executes the task using tools
3. **Checker agent** (reviewer) validates the result — if it fails, loops back to controller (max 3 retries)

Agents are defined in MongoDB and can be created/modified via REST API. Five default agents are seeded on first startup:

| Agent | Role | Purpose |
|---|---|---|
| `controller` | CONTROLLER | Routes tasks, delegates to specialists |
| `coder` | SPECIALIST | Writes code, runs shell commands, uses JBang/Python |
| `reviewer` | CHECKER | Reviews output, runs tests, validates correctness |
| `pm` | SPECIALIST | Project management — planning, tickets, milestones, stakeholder tracking |
| `distiller` | SPECIALIST | Distills completed sessions into persistent memories automatically |

The PM agent has access to `create_ticket`, `create_idea`, `memory`, `excel`, `read_file`, `list_directory`, and `search_files`. The distiller agent runs automatically after each session completes, extracting key topics and outcomes into THREAD or SESSION-scoped memories. If you upgrade from an older database, missing agents are automatically seeded on startup.

Agent-to-agent communication happens via MongoDB change streams — real-time, persistent, and observable from the UI.

### Persistent Memory

Agents can **store and recall knowledge** across sessions using the `memory` tool:

- **GLOBAL** scope — shared across all projects (e.g., "user prefers Java 21")
- **PROJECT** scope — tied to a project (e.g., "this repo uses Gradle")
- **SESSION** scope — tied to a standalone session
- **THREAD** scope — tied to a thread (e.g., "sprint planning decisions from this thread")

The **distiller agent** automatically runs after each session completes, extracting a summary of the conversation topic and outcome. Thread-bound sessions produce THREAD-scoped memories; standalone sessions produce SESSION-scoped memories. This ensures valuable context is preserved without manual intervention.

Memory is stored in MongoDB with text search, tag-based filtering, and key-based upsert. Before each LLM call, relevant memories are loaded into context so agents always have background knowledge.

### Tool System (16 Built-in Tools)

Tools implement the `Tool` SPI interface and are discovered via `ServiceLoader`:

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
| `memory` | WRITE_FILES | Store/recall/delete persistent memories |
| `human_search` | BROWSER_CONTROL | Request human to perform web search, opens browser |
| `create_ticket` | WRITE_FILES | Create project tickets |
| `create_idea` | WRITE_FILES | Create project ideas |

Tools with `WRITE_FILES` or `EXEC_SHELL` risk require user approval. Tool output streams in real-time via `ToolStream` callbacks.

### Bloomberg Terminal UI (`javaclawui.java`)

A JBang single-file Swing application with a retro green-on-black Bloomberg terminal aesthetic:

```
┌──────────────────────────────────────────────────────────────────────┐
│ JAVACLAW v1.0                              localhost:8080     15pt   │
├────────────┬───────────────────────────────┬─────────────────────────┤
│ NAVIGATOR  │                               │                         │
│            │    CHAT / CONVERSATION        │    AGENTS / ACTIVITY    │
│ PROJECTS   │                               │    (280px)              │
│ ├─ MyProj  │ You: analyze this code        │ [controller] IDLE       │
│ │ ├─Threads│ [coder]: Looking at...        │ [coder]  RUNNING        │
│ │ │ └─T1   │ Tool: read_file               │ [reviewer] IDLE         │
│ │ ├─Ideas  │ [coder]: The code has...      │                         │
│ │ ├─Tickets│ [reviewer]: PASS              │ STEP 3 | read_file      │
│ │ ├─Designs│                               │ Tokens: ~2.4k           │
│ │ ├─Plans  │                               │                         │
│ │ └─Score  │                               │                         │
│ STANDALONE │ SEARCH REQUEST FROM AGENT     │                         │
│ ├─ abc123  │ Search: "Spring AI multimodal"│                         │
│ └─ def456  │ [OPEN BROWSER] [paste area]   │                         │
│            │ [SUBMIT RESULTS TO AGENT]     │                         │
│[+PROJECT]  ├───[ATT:2 files]──┬────┬───────┴─────────────────────────┤
│[+SESSION]  │ > _              │SEND│                                  │
├────────────┴──────────────────┴────┴─────────────────────────────────┤
│ F1:Help F2:Project F3:Run F4:Pause F5:Tools F6:Nav F7:Chat F8:Timer │
│ F9:File F10:Agents F11:Search F12:Memory ^K:Keys ^H:Tutorial ^+/-   │
└──────────────────────────────────────────────────────────────────────┘
```

**Keyboard shortcuts:**

| Key | Action |
|-----|--------|
| `F1` | Toggle help overlay (all shortcuts listed) |
| `F2` | Create new project |
| `F3` | Run agent on current thread/session |
| `F4` | Pause agent |
| `F5` | Show available tools dialog |
| `F6` | Focus project navigator |
| `F7` / `Ctrl+L` | Focus chat input |
| `F8` | Timer/reminder manager |
| `F9` | Attach file (reads content locally) |
| `F10` | Toggle agent pane |
| `F11` | Toggle search request pane |
| `F12` | Memory browser dialog |
| `Ctrl+N` | Create standalone session |
| `Ctrl+T` | New thread in current project |
| `Ctrl+H` | Show tutorial / help guide |
| `Ctrl+=` | Increase font size |
| `Ctrl+-` | Decrease font size |
| `Ctrl+0` | Reset font to 15pt default |
| `Ctrl+V` | Paste image from clipboard (auto-detect) |
| `Ctrl+K` | Configure API keys (in-memory only) |
| `Enter` | Send message + auto-run agent (auto-creates session if needed) |
| `Ctrl+R` | Refresh project navigator |
| `Ctrl+W` | Clear chat display |
| `Escape` | Close any overlay/dialog |
| `Up/Down` | Navigate tree or input history |

### Built-in Tutorial (Ctrl+H)

JavaClaw includes a step-by-step interactive tutorial that appears on first launch and can be re-opened anytime with `Ctrl+H`. The tutorial walks through all major capabilities in 6 steps:

**Step 1: Welcome to JavaClaw**

JavaClaw is a Bloomberg-style AI terminal for engineering managers. It combines project management, AI-powered chat threads, and tool execution in a single interface. Navigate with Enter/Right to advance, Left to go back, or Escape to close.

**Step 2: Projects & Navigation**

The left sidebar is your Project Navigator:
- `F2` — Create a new project
- `Ctrl+N` — Create a standalone session
- `Ctrl+T` — New thread in current project
- `F6` — Focus the navigator

Each project contains Threads, Ideas, Tickets, Designs, Plans, and a Scorecard. Click folders to open artifact dialogs.

**Step 3: Chat & Agent Interaction**

Select a thread or session, or just type and press Enter — a session is auto-created if needed:
- `Enter` — Send message + auto-run the agent
- `F3` — Re-run agent on current thread
- `F4` — Pause agent
- `F9` — Attach files to your message
- `Ctrl+V` — Paste images from clipboard

Agent responses stream in real-time via WebSocket.

**Step 4: Tools & Agents**

JavaClaw agents can use tools to help you:
- Read/Write files (WSL + Windows paths supported)
- Run JBang and Python scripts
- Search the web (human-in-the-loop)
- Read Excel spreadsheets
- Manage persistent memory

Shortcuts: `F5` (view tools), `F10` (toggle agent pane), `F11` (toggle search pane).

**Step 5: Keyboard Shortcuts**

Essential shortcuts for power users:
- `F1` — Full keyboard reference
- `Ctrl+=` / `Ctrl+-` / `Ctrl+0` — Font size control
- `Ctrl+K` — Configure API keys
- `Ctrl+R` — Refresh navigator
- `Ctrl+W` — Clear chat display
- `F8` — Timer/reminder manager
- `F12` — Memory browser

**Step 6: You're Ready!**

Start by creating a project (`F2`) and adding a thread (`Ctrl+T`) to begin chatting with the AI. Or create a standalone session (`Ctrl+N`) for quick, project-free interactions. Press `Ctrl+H` anytime to re-open this tutorial.

The tutorial is auto-shown on first launch (detected via `~/.javaclaw/tutorial-seen` marker file). After first viewing, it is only shown on demand via `Ctrl+H`.

---

### Project-Centric Navigation

JavaClaw organizes work around **projects** — each project is a container for related conversations, ideas, tickets, designs, and planning artifacts:

```
PROJECTS                    [+ PROJECT]
├── My Project  ACTIVE
│   ├── Threads (3)         ← AI chat conversations
│   │   ├── Thread-1 IDLE
│   │   └── Thread-2 RUNNING
│   ├── Ideas (5)           ← Brainstorming, promotable to tickets
│   ├── Tickets (2)         ← Work items with status/priority
│   ├── Designs (1)         ← Design documents
│   ├── Plans               ← Milestones and ticket linkage
│   └── Scorecard           ← Health metrics for the project
STANDALONE SESSIONS         [+ SESSION]
├── abc12345  IDLE
└── def67890  COMPLETED
```

- **Threads** are project-scoped AI chat sessions. They use the same agent loop as standalone sessions but are organized under a project.
- **Ideas** can be promoted to **Tickets** with a single click.
- **Tickets** have status (OPEN/IN_PROGRESS/DONE/CANCELLED), priority (LOW/MEDIUM/HIGH/CRITICAL), and resource assignment.
- **Designs** track design documents with source attribution and versioning.
- **Scorecard** provides project health metrics at a glance.
- **Plans** link milestones to tickets for sprint planning.

Standalone sessions remain available for quick, project-free interactions.

### Font Size Adjustment

The default font is 15pt (up from 13pt). Adjust dynamically:
- `Ctrl+=` — Increase font size (max 24pt)
- `Ctrl+-` — Decrease font size (min 10pt)
- `Ctrl+0` — Reset to 15pt default

The current font size is displayed in amber in the header bar. Font preference is persisted via the backend (`POST /api/config/font-size`).

### WSL/Windows Path Support

When running in WSL, JavaClaw automatically translates file paths between Windows and Linux formats:
- `C:\Users\drom\file.txt` → `/mnt/c/Users/drom/file.txt`
- Backslash paths are detected and converted automatically
- Falls back to the original path if translation fails

This works for `read_file`, `write_file`, and `list_directory` tools.

### Logging & LLM Metrics

All system logs and LLM interactions are persisted to MongoDB for debugging and metrics:

| Endpoint | Description |
|---|---|
| `GET /api/logs` | System logs (filter by level, sessionId) |
| `GET /api/logs/errors` | Error logs only |
| `GET /api/logs/llm-interactions` | All LLM calls with token counts |
| `GET /api/logs/llm-interactions/metrics` | Aggregate metrics (total interactions, tokens, avg duration, error rate) |

Every LLM call records: sessionId, agentId, provider, model, message count, prompt tokens, completion tokens, duration (ms), success/failure, and error message.

### Human Search Requestor

When agents need current web information:

1. Agent calls `human_search` tool with a query
2. **User's default browser opens** automatically with the Google search URL
3. Search request pane appears in the UI (F11)
4. User browses results, copies content (`Ctrl+A`, `Ctrl+C`)
5. User pastes into the search pane (`Ctrl+V`) and clicks **SUBMIT**
6. Content is returned to the agent, which continues processing

This bridges the gap when direct web search APIs are blocked or require authentication.

### Excel Integration

The `excel` tool supports:
- **Reading** — Extract data from .xlsx/.xls files, any sheet, with row limits
- **Writing** — Create or update Excel files with arrays of data
- **Listing sheets** — Get sheet names and row counts

Perfect for working with Jira exports, sprint data, team capacity spreadsheets, and generating reports.

### Timers and Reminders

Create recurring or one-shot reminders:
- "Check build status 3 times a day" → fires every 8 hours
- "Review PR queue daily at 9 AM" → one-shot recurring
- Managed via F8 dialog or REST API
- Recurring reminders automatically re-arm after firing

### Multimodal Messages (Image Paste)

Paste images from clipboard (`Ctrl+V`) and send them to the model for analysis. Images are sent as base64 PNG in multimodal message parts. The model receives both the image and any text you type.

### API Key Management

`Ctrl+K` opens a dialog to paste Anthropic or OpenAI API keys. Keys are stored **in-memory only** on the server — never written to config files or disk. Safe from accidental git commits.

## How It Works

### Data Model: Projects > Threads > Sessions

JavaClaw organizes work in a hierarchy:

```
Projects (M:N with threads)
└── Threads (belong to 1+ projects via projectIds)
    └── Sessions (optionally linked to a thread via threadId)

Standalone Sessions (threadId = null)
```

- **Sessions** (`/api/sessions`) — Standalone or thread-bound conversations, stored in the `sessions` collection. Sessions with a `threadId` belong to that thread; sessions without one are standalone.
- **Threads** (`/api/projects/{pid}/threads`) — Project-scoped conversations, stored in the `threads` collection. A thread can belong to multiple projects (M:N via `projectIds` list).
- **Projects** — Top-level containers for threads, tickets, ideas, designs, plans, and scorecards.

Both sessions and threads share the `messages` collection — a thread's `threadId` is used as the `sessionId` when storing messages. The AgentLoop performs a **dual-lookup**: it first checks `SessionRepository`, then falls back to `ThreadRepository`. This means the agent loop, checkpoint system, and event streaming all work identically for both.

Sessions are **ephemeral by default** — the distiller agent decides what's worth persisting as memory after each session completes.

From the UI's perspective, when the user selects a thread, `ChatPanel` stores both `currentSessionId` (= threadId) and `currentProjectId`, then routes API calls to the thread endpoints. When the user selects a standalone session, `currentProjectId` is null and API calls go to session endpoints.

### Agent Loop

1. A **session** or **thread** is created via REST API or the UI (or auto-created on first Enter)
2. The user sends a **message**
3. Calling `/run` (or auto-run on Enter) starts the **AgentLoop** which:
   - Acquires a distributed **lock** on the session (60s TTL, auto-renewed)
   - Loads the latest **checkpoint** (or starts fresh)
   - Loads **relevant memories** (GLOBAL + PROJECT scope) into agent context
   - Enters the **multi-agent orchestration loop**:
     - **Controller** analyzes the task and delegates (or answers directly)
     - **Specialist** executes using tools (max 50 steps)
     - **Checker** validates the result
     - If checker rejects → loops back to controller (max 3 retries)
   - Emits events at every step (tokens, tool calls, agent switches)
   - Saves **checkpoints** after each step
   - Sets session status to `COMPLETED` or `FAILED`
   - Releases the lock

### Event Sourcing

Every action is recorded as an **EventDocument** with a session-scoped monotonic sequence number. The `EventChangeStreamTailer` watches MongoDB's change stream for new inserts and broadcasts them to WebSocket subscribers.

40+ event types including: `USER_MESSAGE_RECEIVED`, `MODEL_TOKEN_DELTA`, `TOOL_CALL_PROPOSED`, `TOOL_RESULT`, `AGENT_DELEGATED`, `AGENT_CHECK_PASSED`, `AGENT_CHECK_FAILED`, `MEMORY_STORED`, `SEARCH_REQUESTED`, `REMINDER_TRIGGERED`, and more.

## Why MongoDB

- **Change Streams** — Real-time agent-to-agent communication without a separate message broker
- **Document Model** — Agent state, memories, and tool results are naturally hierarchical
- **TTL Indexes** — Session locks auto-expire, no background reaper needed
- **Flexible Schema** — Tool outputs and event payloads can hold arbitrary JSON
- **Text Search** — Memory recall uses MongoDB's built-in text search
- **Replica Set** — Required for change streams; a single-node replica set works for development

### Collections

| Collection | Purpose |
|---|---|
| events | Event sourcing (sessionId + seq, unique compound) |
| messages | Chat messages with multimodal support |
| sessions | Agent sessions with status tracking |
| checkpoints | Agent state snapshots |
| agents | Agent definitions (controller, coder, reviewer, pm, custom) |
| memories | Persistent memory (GLOBAL/PROJECT/SESSION scope) |
| reminders | Recurring and one-shot timers |
| projects | Project definitions with status and tags |
| threads | Project-scoped AI chat sessions |
| tickets | Work items with status, priority, assignment |
| ideas | Brainstorming items, promotable to tickets |
| designs | Design documents with versioning |
| plans | Sprint plans with milestones |
| scorecards | Project health metrics |
| resources | Team members and capacity |
| logs | System logs (INFO/WARN/ERROR) |
| llm_interactions | LLM call metrics (tokens, duration, success) |
| specs | Technical specifications |
| approvals, resource_assignments, locks | Operations |

## Prerequisites

- **Java 21+** (Eclipse Temurin recommended)
- **Docker** (for MongoDB)
- **JBang** (recommended — install from https://www.jbang.dev/download/)
- **Maven 3.9+** (only for multi-module builds; `mvnw` wrapper included)
- **API Key** — Set `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` environment variable
- **Python 3** (optional — for `python_exec` tool)

## Quick Start with JBang (Recommended)

### Step 1: Start MongoDB

```bash
docker compose up -d mongodb
# Wait for healthy status:
docker compose ps
```

### Step 2: Set your LLM API key

```bash
export ANTHROPIC_API_KEY=sk-ant-...
# Or use the UI: Ctrl+K to paste keys at runtime
```

### Step 3: Start the server

```bash
jbang javaclaw.java --headless
```

### Step 4: Launch the Bloomberg terminal UI

```bash
jbang javaclawui.java
```

The terminal UI connects to `http://localhost:8080`. On first launch, a **built-in tutorial** walks you through all features. Press **F2** to create your first project, **Ctrl+T** to add a thread, type a message, and press **Enter** — the agent runs automatically. Or just type and press Enter without selecting anything — a standalone session is auto-created. Press **Ctrl+H** to re-open the tutorial anytime, and **F1** for the full shortcut reference.

### JBang CLI Flags

| Flag | Description | Default |
|---|---|---|
| `--headless` | REST gateway only (no UI) | off |
| `--mongo <uri>` | Custom MongoDB connection URI | `mongodb://localhost:27017/javaclaw?replicaSet=rs0` |
| `--port <port>` | HTTP server port | `8080` |
| `--url <url>` | (UI only) Backend URL | `http://localhost:8080` |

## REST API

Base URL: `http://localhost:8080`

### Sessions

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/sessions` | Create a new agent session |
| `GET` | `/api/sessions` | List all sessions (newest first) |
| `GET` | `/api/sessions/{id}` | Get session details |
| `POST` | `/api/sessions/{id}/messages` | Send a message (text or multimodal) |
| `POST` | `/api/sessions/{id}/run` | Start the multi-agent loop |
| `POST` | `/api/sessions/{id}/pause` | Pause execution |
| `POST` | `/api/sessions/{id}/resume` | Resume execution |

### Agents

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/agents` | List all agents |
| `GET` | `/api/agents/{id}` | Get agent details |
| `POST` | `/api/agents` | Create a custom agent |
| `PUT` | `/api/agents/{id}` | Update agent (system prompt, tools, skills) |
| `DELETE` | `/api/agents/{id}` | Delete agent |

### Memory

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/memories` | List memories (filter: `?scope=`, `?query=`) |
| `GET` | `/api/memories/{id}` | Get memory details |
| `POST` | `/api/memories` | Create memory |
| `DELETE` | `/api/memories/{id}` | Delete memory |

### Reminders

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/reminders` | Create a timer/reminder |
| `GET` | `/api/reminders?sessionId=X` | List reminders |
| `DELETE` | `/api/reminders/{id}` | Delete reminder |

### Search

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/search/response` | Submit human search results back to agent |

### Projects

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects` | Create a project |
| `GET` | `/api/projects` | List all projects (newest first) |
| `GET` | `/api/projects/{id}` | Get project details |
| `PUT` | `/api/projects/{id}` | Update project |
| `DELETE` | `/api/projects/{id}` | Delete project |

### Threads (Project-Scoped)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/threads` | Create a thread |
| `GET` | `/api/projects/{pid}/threads` | List threads for project |
| `GET` | `/api/projects/{pid}/threads/{tid}` | Get thread details |
| `POST` | `/api/projects/{pid}/threads/{tid}/messages` | Send message to thread |
| `POST` | `/api/projects/{pid}/threads/{tid}/run` | Run agent on thread |
| `POST` | `/api/projects/{pid}/threads/{tid}/pause` | Pause thread agent |

### Ideas

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/ideas` | Create an idea |
| `GET` | `/api/projects/{pid}/ideas` | List ideas for project |
| `GET` | `/api/projects/{pid}/ideas/{id}` | Get idea details |
| `PUT` | `/api/projects/{pid}/ideas/{id}` | Update idea |
| `POST` | `/api/projects/{pid}/ideas/{id}/promote` | Promote idea to ticket |

### Tickets

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/tickets` | Create a ticket |
| `GET` | `/api/projects/{pid}/tickets` | List tickets (filter: `?status=`) |
| `GET` | `/api/projects/{pid}/tickets/{id}` | Get ticket details |
| `PUT` | `/api/projects/{pid}/tickets/{id}` | Update ticket |
| `DELETE` | `/api/projects/{pid}/tickets/{id}` | Delete ticket |

### Designs

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/designs` | Create a design |
| `GET` | `/api/projects/{pid}/designs` | List designs for project |
| `GET` | `/api/projects/{pid}/designs/{id}` | Get design details |
| `PUT` | `/api/projects/{pid}/designs/{id}` | Update design |
| `DELETE` | `/api/projects/{pid}/designs/{id}` | Delete design |

### Scorecard

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/projects/{pid}/scorecard` | Get project scorecard |
| `PUT` | `/api/projects/{pid}/scorecard` | Upsert project scorecard |

### Plans

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/projects/{pid}/plans` | Create a plan |
| `GET` | `/api/projects/{pid}/plans` | List plans for project |
| `GET` | `/api/projects/{pid}/plans/{id}` | Get plan details |
| `PUT` | `/api/projects/{pid}/plans/{id}` | Update plan |
| `DELETE` | `/api/projects/{pid}/plans/{id}` | Delete plan |

### Resources

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/resources` | Create a resource |
| `GET` | `/api/resources` | List all resources |
| `GET` | `/api/resources/{id}` | Get resource details |
| `PUT` | `/api/resources/{id}` | Update resource |
| `DELETE` | `/api/resources/{id}` | Delete resource |
| `GET` | `/api/resources/{id}/assignments` | Get resource assignments |

### Logs & LLM Metrics

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/logs` | System logs (filter: `?level=`, `?sessionId=`, `?limit=`) |
| `GET` | `/api/logs/errors` | Error logs only |
| `GET` | `/api/logs/llm-interactions` | LLM interaction records (filter: `?sessionId=`, `?agentId=`) |
| `GET` | `/api/logs/llm-interactions/metrics` | Aggregate LLM metrics |

### Configuration

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/config/keys` | Set API keys (in-memory only) |
| `GET` | `/api/config/keys` | Get masked API key status |
| `POST` | `/api/config/font-size` | Set font size preference (10-24) |
| `GET` | `/api/config/font-size` | Get current font size |

### Tools

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/tools` | List available tools with schemas |
| `GET` | `/api/tools/{name}` | Get tool descriptor |
| `POST` | `/api/tools/{name}/invoke` | Execute a tool directly |

### Specs

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/specs` | List specs (filter: `?tag=`, `?q=`) |
| `POST` | `/api/specs` | Create spec |
| `PUT` | `/api/specs/{id}` | Update spec |

### WebSocket

Connect to `ws://localhost:8080/ws`:

```json
{"type": "SUBSCRIBE_SESSION", "sessionId": "<id>"}
{"type": "UNSUBSCRIBE", "sessionId": "<id>"}
```

Events pushed as:
```json
{"type": "EVENT", "sessionId": "<id>", "payload": {"type": "MODEL_TOKEN_DELTA", "seq": 42}}
```

## Developer Guide

This section is for LLMs and developers continuing work on the project.

### How to Add a New Tool

1. Create a class in `tools/src/main/java/.../tools/` implementing `io.github.drompincen.javaclawv1.runtime.tools.Tool`
2. Implement required methods:
   - `name()` — unique tool identifier (e.g., `"my_tool"`)
   - `description()` — what the tool does (shown to LLM)
   - `riskProfile()` — `READ_ONLY`, `WRITE_FILES`, `EXEC_SHELL`, `NETWORK_CALLS`, or `BROWSER_CONTROL`
   - `parameters()` — JSON Schema describing input parameters
   - `execute(Map<String, Object> params, ToolContext ctx)` — returns `ToolResult`
3. Register via SPI: add fully qualified class name to `tools/src/main/resources/META-INF/services/io.github.drompincen.javaclawv1.runtime.tools.Tool`
4. The tool is auto-discovered by `ToolRegistry` at startup — no other wiring needed

### How to Add a New REST Endpoint

1. Create a controller class in `gateway/src/main/java/.../gateway/controller/`
2. Annotate with `@RestController` and `@RequestMapping("/api/your-path")`
3. Inject repositories from the `persistence` module
4. Follow the pattern of existing controllers (e.g., `TicketController` for CRUD, `SessionController` for run/pause)
5. If the entity needs persistence, create a `Document` class in `persistence/src/main/java/.../persistence/document/` and a `MongoRepository` interface in `.../persistence/repository/`
6. For new DTOs, add records in `protocol/src/main/java/.../protocol/api/`

### How to Add a New Agent

Agents can be created at runtime via the REST API:

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "analyst",
    "name": "Analyst",
    "description": "Data analysis specialist",
    "systemPrompt": "You are a data analyst. Use Excel and Python tools to analyze data.",
    "skills": ["data analysis", "visualization", "reporting"],
    "allowedTools": ["excel", "python_exec", "read_file", "memory"],
    "role": "SPECIALIST",
    "enabled": true
  }'
```

Or add to `AgentBootstrapService.bootstrap()` for built-in agents that seed on first startup.

### How to Add a New UI Panel/Dialog

The UI is a single JBang file (`javaclawui.java`). To add a new dialog:

1. Create a static inner class extending `JDialog` (follow `TimerDialog` or `ApiKeyDialog` patterns)
2. Use `Theme` constants for colors, `TERM_FONT` for fonts
3. Register a keyboard shortcut in `MainPanel.registerShortcuts()`:
   ```java
   bind(im, am, "ctrl SOMETHING", "actionName", e -> showYourDialog());
   ```
4. Add the shortcut to `HELP_ROWS` and the status bar labels

### Testing & Verification

```bash
# Run all tests (103+)
./mvnw test

# Compile only (faster)
./mvnw compile

# Start gateway (requires MongoDB running)
./mvnw spring-boot:run -pl gateway

# Verify agents seeded (should show 4: controller, coder, reviewer, pm)
curl -s http://localhost:8080/api/agents | python3 -m json.tool

# End-to-end test flow
curl -X POST http://localhost:8080/api/projects -H "Content-Type: application/json" -d '{"name":"Test","description":"e2e"}'
# Use returned projectId:
curl -X POST http://localhost:8080/api/projects/<pid>/threads -H "Content-Type: application/json" -d '{"title":"Thread 1"}'
# Use returned threadId:
curl -X POST http://localhost:8080/api/projects/<pid>/threads/<tid>/messages -H "Content-Type: application/json" -d '{"content":"Hello","role":"user"}'
curl -X POST http://localhost:8080/api/projects/<pid>/threads/<tid>/run

# Verify JBang UI compiles (on Windows)
jbang javaclawui.java --url http://localhost:8080
```

### Common Pitfalls

| Pitfall | Details |
|---|---|
| **Thread IDs need dual-lookup** | `AgentLoop` checks both `SessionRepository` and `ThreadRepository`. If you add code that looks up sessions by ID, also check threads. |
| **Lock TTL is 60s** | The distributed session lock expires after 60 seconds. If debugging takes longer, the lock expires and another loop could start. |
| **Change streams require replica set** | MongoDB must run with `--replSet rs0`. Without it, `EventChangeStreamTailer` fails silently and no WebSocket events flow. |
| **JBang UI compiles separately** | `javaclawui.java` is not part of the Maven build. Changes to it won't be caught by `./mvnw compile`. Test it with `jbang javaclawui.java`. |
| **Messages collection is shared** | Both sessions and threads store messages in the same `messages` collection, keyed by `sessionId`. For threads, `sessionId` = `threadId`. |
| **Auto-run on Enter** | `sendMessage()` in ChatPanel auto-creates a session if none exists, sends the message, then auto-runs the agent. The old Ctrl+Enter-to-run pattern is removed. |
| **PM agent auto-seed** | `AgentBootstrapService.ensurePmAgent()` adds the PM agent even if other agents already exist. Safe to run on upgraded databases. |
| **API key placeholders** | `application.yml` uses `sk-ant-placeholder-set-real-key-via-ctrl-k` as default. This passes Spring AI's `hasText` validation but won't authenticate. Set real keys via env vars or Ctrl+K. |
| **WSL path translation** | `WslPathHelper` converts `C:\...` to `/mnt/c/...` in file tools. Only active when running on WSL (detected via `/proc/version`). |

### Key File Paths

| File | Purpose |
|---|---|
| `runtime/src/main/java/.../agent/AgentLoop.java` | Core agent orchestration — dual-lookup for sessions and threads |
| `runtime/src/main/java/.../agent/AgentGraphBuilder.java` | LangGraph4j graph: controller → specialist → checker loop |
| `runtime/src/main/java/.../agent/AgentBootstrapService.java` | Seeds 4 default agents on first startup |
| `runtime/src/main/java/.../agent/llm/AnthropicLlmService.java` | Anthropic API integration via Spring AI |
| `runtime/src/main/java/.../agent/llm/OpenAiLlmService.java` | OpenAI API integration via Spring AI |
| `runtime/src/main/java/.../tools/ToolRegistry.java` | SPI tool discovery and registration |
| `persistence/src/main/java/.../stream/EventChangeStreamTailer.java` | MongoDB change stream → WebSocket bridge |
| `gateway/src/main/java/.../websocket/JavaClawWebSocketHandler.java` | WebSocket handler for event streaming |
| `gateway/src/main/java/.../controller/SessionController.java` | Session CRUD + run/pause |
| `gateway/src/main/java/.../controller/ThreadController.java` | Thread CRUD + run/pause (project-scoped) |
| `javaclawui.java` | JBang Bloomberg terminal UI (single file) |
| `javaclaw.java` | JBang server launcher (single file) |
| `docker-compose.yml` | MongoDB + gateway Docker setup |
| `mongo-init.js` | MongoDB index initialization |

### Event Types Reference

35 event types in `protocol/src/main/java/.../protocol/event/EventType.java`:

**Core Agent**: `USER_MESSAGE_RECEIVED`, `AGENT_STEP_STARTED`, `MODEL_TOKEN_DELTA`, `TOOL_CALL_PROPOSED`, `TOOL_CALL_APPROVED`, `TOOL_CALL_DENIED`, `TOOL_CALL_STARTED`, `TOOL_STDOUT_DELTA`, `TOOL_STDERR_DELTA`, `TOOL_PROGRESS`, `TOOL_RESULT`, `AGENT_STEP_COMPLETED`, `CHECKPOINT_CREATED`, `SESSION_STATUS_CHANGED`, `ERROR`

**Multi-Agent**: `AGENT_DELEGATED`, `AGENT_RESPONSE`, `AGENT_CHECK_REQUESTED`, `AGENT_CHECK_PASSED`, `AGENT_CHECK_FAILED`, `AGENT_SWITCHED`

**Memory**: `MEMORY_STORED`, `MEMORY_RECALLED`

**Human Interaction**: `SEARCH_REQUESTED`, `SEARCH_RESPONSE_SUBMITTED`

**Project Management**: `TICKET_CREATED`, `TICKET_UPDATED`, `IDEA_PROMOTED`, `REMINDER_TRIGGERED`, `APPROVAL_REQUESTED`, `APPROVAL_RESPONDED`, `RESOURCE_ASSIGNED`

## Roadmap

### Next Up

These features are actively planned and will be implemented soon:

#### Context Menus (Right-Click Actions)
Right-click on projects and threads in the navigator tree to access quick actions:
- **Projects** — Rename, delete (with confirmation warning), add/manage resources, create thread, view scorecard
- **Threads** — Delete, archive, move to another project, copy thread ID
- Eliminates the need to navigate through menus or remember keyboard shortcuts for common operations

#### Agent Observability & Response Fix
Currently agent responses appear blank regardless of whether an API key is configured. This needs to be fixed alongside better observability:
- **Fix blank responses** — Ensure the agent loop produces visible output with both fake and real LLM providers
- **Call logging** — Log which agent is active, what task it's working on, and how long each call takes
- **Duration tracking** — Display elapsed time per agent step in the agent pane
- **Status visibility** — Show "Coder is analyzing your request..." style messages in the UI during agent work

#### Project & Thread Management
Full lifecycle management from the UI:
- **Delete projects** — With a confirmation warning dialog ("This will permanently delete the project and all its threads, tickets, ideas, and designs")
- **Delete threads** — Remove threads from a project with confirmation
- **Add resources to projects** — Assign team members (engineers, designers, PMs, QA) to projects directly from the navigator context menu
- **Resource capacity view** — See who is assigned where and at what allocation percentage

#### File Tool Verification
Validate that the file tools (read_file, write_file, list_directory, search_files) work end-to-end from the UI:
- Test creating files via the agent
- Test listing local directories
- Ensure WSL path translation works correctly for all file operations
- Surface tool results clearly in the chat panel

#### Jira Excel Import via Agent
Ask the agent to read an Excel or CSV file exported from Jira and automatically create tickets in a project:
- **Attach the export** — Use F9 or drag-and-drop to attach a `.xlsx` / `.csv` file
- **Agent reads it** — The agent uses the `excel` tool to parse rows and columns (story title, description, priority, status, assignee)
- **Auto-creates tickets** — Each row becomes a ticket in the current project via `create_ticket`
- **Resource mapping** — Match assignee names to existing resources in the system
- **Summary report** — Agent provides a summary of how many tickets were imported and any rows that couldn't be parsed

This enables rapid project bootstrapping from existing Jira boards without manual re-entry.

### Future Vision

- **Confluence integration** — Read designs and compare against implementation
- **Jira API integration** — Direct Jira REST API access (not just Excel imports)
- **Sprint management** — Week-by-week objective tracking, velocity metrics, burndown
- **Mark-to-market** — Compare planned vs. actual ticket completion per sprint
- **Team management** — Track directs, see who has capacity, auto-suggest assignments
- **Multi-project dashboard** — Manage multiple projects simultaneously
- **RAG/Embedding memory** — Semantic search over memories using vector embeddings
- **Code documentation agent** — Auto-generate docs by reading entire codebases
- **Approval workflows** — Human-in-the-loop for risky agent actions
- **Custom agent creation via UI** — Define new specialist agents with custom prompts

## Alternative: Maven Multi-Module Build

```bash
docker compose up -d mongodb
./mvnw spring-boot:run -pl gateway       # headless REST + WebSocket
./mvnw spring-boot:run -pl ui            # Swing desktop app
```

## Alternative: Docker Compose

```bash
docker compose up -d    # MongoDB + gateway
```

## LLM Configuration

| Provider | Env Variable | Default Model |
|---|---|---|
| Anthropic | `ANTHROPIC_API_KEY` | `claude-sonnet-4-5-20250929` |
| OpenAI | `OPENAI_API_KEY` | `gpt-4o` |

Set provider: `JAVACLAW_LLM_PROVIDER=anthropic` (default) or `openai`.

Or paste keys at runtime via `Ctrl+K` in the UI (stored in-memory only, never on disk).

## Project Structure

```
javaclawv1/
├── javaclaw.java              # JBang server (self-contained Spring Boot)
├── javaclawui.java            # JBang Bloomberg terminal UI client
├── docker-compose.yml         # MongoDB + gateway services
├── Dockerfile                 # Multi-stage gateway build
├── mongo-init.js              # Collection indexes (20+ collections)
├── pom.xml                    # Parent POM (Java 21, Spring Boot 3.2.5)
├── protocol/                  # Shared types (pure Java, no Spring)
│   └── api/                   # DTOs: SessionDto, AgentRole, ReminderDto, ToolRiskProfile, ...
│   └── event/                 # EventType enum (40+ types)
│   └── ws/                    # WebSocket message contracts
├── persistence/               # MongoDB data layer
│   └── document/              # AgentDocument, MemoryDocument, MessageDocument, ...
│   └── repository/            # 20+ MongoRepository interfaces
│   └── stream/                # ChangeStreamService, EventChangeStreamTailer
├── runtime/                   # Multi-agent engine
│   └── agent/                 # AgentLoop, AgentGraphBuilder, AgentBootstrapService
│   └── agent/llm/             # AnthropicLlmService, OpenAiLlmService
│   └── checkpoint/            # CheckpointService
│   └── lock/                  # SessionLockService (distributed TTL)
│   └── reminder/              # ReminderScheduler (recurring + one-shot)
│   └── tools/                 # Tool SPI: Tool, ToolRegistry, ToolContext, ToolResult
├── tools/                     # 17 built-in tool implementations
│   └── ReadFileTool, WriteFileTool, ShellExecTool, JBangExecTool,
│       PythonExecTool, ExcelTool, MemoryTool, HumanSearchTool,
│       GitStatusTool, GitDiffTool, GitCommitTool, HttpGetTool,
│       ListDirectoryTool, SearchFilesTool, CreateTicketTool, CreateIdeaTool
├── gateway/                   # REST + WebSocket server
│   └── controller/            # Session, Agent, Memory, Reminder, Search,
│                              # Config, Spec, Tool controllers
│   └── websocket/             # JavaClawWebSocketHandler
└── ui/                        # Swing desktop app (FlatLaf)
    └── view/                  # MainWindow, ThreadView, BoardView,
                               # DashboardView, ResourcesView, IdeasView
```

## Diagnostics

### Check MongoDB
```bash
docker ps --filter name=javaclaw-mongo
docker exec javaclaw-mongo mongosh --quiet --eval "rs.status().ok"
```

### Check Gateway
```bash
curl -s http://localhost:8080/api/sessions | python3 -m json.tool
curl -s http://localhost:8080/api/agents | python3 -m json.tool
curl -s http://localhost:8080/api/tools | python3 -m json.tool
```

### Common Issues

| Problem | Fix |
|---|---|
| MongoDB not reachable | `docker compose up -d mongodb` and wait for healthy |
| Change streams not available | Ensure MongoDB is running as replica set (`--replSet rs0`) |
| API key errors | Set env var or use `Ctrl+K` in UI to paste keys |
| Lock acquisition failed | Locks auto-expire after 60s; wait or restart |
| Port 8080 in use | Use `--port 9090` or kill existing process |

## License

MIT License
