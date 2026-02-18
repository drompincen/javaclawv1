# TODO — JavaClaw v1

## E2E Multi-Agent Orchestration Tests

The multi-agent loop (controller → specialist → checker) needs E2E validation with real JBang scenarios
and JUnit tests that exercise tool execution, not just mocked LLM responses.

### ~~1. JBang Scenario E2E Tests (Manual / CI)~~ DONE

> **Status**: DONE — all 12 scenarios pass against live MongoDB + JBang server

Run each built-in scenario against a live server + MongoDB:

```bash
# Generalist (no tools, pure text)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-general.json

# PM (delegation, no tools)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-pm.json

# Coder (delegation + tool calls: read_file)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-coder.json

# Coder exec (delegation + write_file + shell_exec)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-coder-exec.json

# Java + Python time execution (write_file + shell_exec)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-exec-time.json

# Filesystem tools (list_directory + search_files)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-fs-tools.json

# Git tools (git_status + git_diff)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-git-tools.json

# JBang exec tool (jbang_exec — inline Java compilation + execution)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-jbang-exec.json

# Python exec tool (python_exec — inline Python execution)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-python-exec.json

# Memory tool (store + recall + delete)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-memory.json

# PM tools (create_ticket + create_idea)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-pm-tools.json

# HTTP tool (http_get — network calls)
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-http.json
```

**Pass criteria**: All steps pass, logs show `[controller] Delegating to '...'`, `[checker] Result: pass=true`.

**Results (2026-02-18)**:
| Scenario | Steps | Tools Exercised | Result |
|----------|-------|-----------------|--------|
| `scenario-general.json` | 2/2 | _(none — pure text)_ | PASS |
| `scenario-pm.json` | 4/4 | _(none — pure text)_ | PASS |
| `scenario-coder.json` | 3/3 | `read_file` | PASS |
| `scenario-coder-exec.json` | 6/6 | `write_file`, `shell_exec` | PASS |
| `scenario-exec-time.json` | 3/3 | `write_file`, `shell_exec` | PASS |
| `scenario-fs-tools.json` | 4/4 | `list_directory`, `search_files` | PASS |
| `scenario-git-tools.json` | 4/4 | `git_status`, `git_diff` | PASS |
| `scenario-jbang-exec.json` | 2/2 | `jbang_exec` | PASS |
| `scenario-python-exec.json` | 2/2 | `python_exec` | PASS |
| `scenario-memory.json` | 3/3 | `memory` (store/recall/delete) | PASS |
| `scenario-pm-tools.json` | 4/4 | `create_ticket`, `create_idea` | PASS |
| `scenario-http.json` | 2/2 | `http_get` | PASS |
| **Total** | **39/39** | **13 of 16 tools** | **ALL PASS** |

**Tools not tested via scenarios** (3 remaining):
| Tool | Reason |
|------|--------|
| `git_commit` | Actually commits — risky for automated testing |
| `human_search` | Interactive — opens browser, polls for user input |
| `excel` | Needs .xlsx fixture file; read/write/list_sheets operations |

### ~~2. Java Execution E2E Test (JUnit)~~ DONE

> **Status**: DONE — `CodeExecutionTest.javaTimeUtil_writesAndExecutes_outputMatchesClock`

Writes `JavaClawTimeTest.java` to temp dir, executes via `jbang.cmd` (Windows) / `jbang` (Linux)
using ProcessBuilder, asserts output `CURRENT_TIME=yyyy-MM-dd HH:mm` is within 2 minutes of
`LocalDateTime.now()`. Skips gracefully if JBang is not installed (`@EnabledIf`).

### ~~3. Python Execution E2E Test (JUnit)~~ DONE

> **Status**: DONE — `CodeExecutionTest.pythonTimeUtil_writesAndExecutes_outputMatchesClock`

Writes `javaclaw_time_test.py` to temp dir, executes via auto-detected Python
using ProcessBuilder, asserts output `CURRENT_TIME=yyyy-MM-dd HH:mm` is within 2 minutes.
On Windows, detects real Python (filtering MS Store alias stubs) or falls back to `wsl python3`
with automatic path translation (`C:\...` → `/mnt/c/...`). Skips gracefully if no Python found.

### ~~4. Combined Agent Orchestration + Execution Test~~ DONE

> **Status**: DONE — `AgentGraphBuilderExecFlowTest` (2 tests)

**Test 1: `fullFlow_specialistWritesFile_checkerPasses`**
- Mock controller delegates to coder (code-fenced JSON — tests `stripCodeFences()`)
- Mock specialist returns `<tool_call>` with `write_file` (streaming)
- Real WriteFileTool writes to disk
- Specialist loops back with final text
- Mock checker passes (silent call)
- Asserts: file created on disk, content correct, tool result event emitted, checker passed,
  MODEL_TOKEN_DELTA emitted for specialist only, blockingResponse used for controller/checker

**Test 2: `fullFlow_checkerRejects_specialistRetries_fileRewritten`**
- Same pipeline but checker rejects first attempt ("content too short")
- Specialist retries, writes longer content
- Checker passes on second attempt
- Asserts: file contains second (longer) content, CHECK_FAILED + CHECK_PASSED events emitted,
  specialist called 4 times (2 per attempt)

### ~~5. Scenario: Java + Python Time Assertion~~ DONE

> **Status**: DONE — `runtime/src/test/resources/scenario-exec-time.json`

3-step scenario: write Java time util → write Python time util → clean up temp files.
Each step uses `write_file` tool calls with proper content. Run via:
```bash
jbang --fresh javaclaw.java --testmode --headless --scenario runtime/src/test/resources/scenario-exec-time.json
```

---

## Bugs Fixed (Reference)

These bugs were fixed — the tests above validate they stay fixed:

| Bug | Fix | Test Coverage |
|-----|-----|---------------|
| Specialist never executes (code-fenced JSON) | `stripCodeFences()` in all parse methods | `AgentGraphBuilderTest` (3 code fence tests) + `AgentGraphBuilderExecFlowTest` (code-fenced controller) |
| Internal messages leak into chat | `callLlmForAgentSilent()` for controller/checker | `AgentGraphBuilderTest.controllerAndCheckerDoNotEmitTokenDeltas` + `ExecFlowTest` |
| Empty agent IDs in chat labels | `chatPanel.setCurrentAgent(to)` on AGENT_SWITCHED | `javaclawui.java` line 446 |
| Missing INFO logs | Added log.info for delegation, check result, streaming | Visible in all test output |
| Infinite tool-call loop in test mode | Skip scenario response when `hasToolResults` + `<tool_call>` | `TestModeLlmService` line 85 |

## Test Coverage Summary

| Test File | Tests | What It Covers |
|-----------|-------|----------------|
| `AgentGraphBuilderTest` | 12 | Full orchestration, code fences, retry, silent/streaming, mocked tool calls |
| `AgentGraphBuilderExecFlowTest` | 2 | Full orchestration with **real** WriteFileTool disk I/O + retry |
| `CodeExecutionTest` | 2 | Java via JBang + time assertion; Python via WSL detection + time assertion |
| `TestModeLlmServiceTest` | 9 | Scenario matching, fallback, prompt persistence |
| `TestResponseGeneratorTest` | 17 | Controller routing, reviewer logic, tool call generation |
| `AgentStateTest` | 6 | State immutability, copy semantics |
| **Total runtime module** | **68** | |

## Tool Coverage Summary

| Tool | JUnit Test | JBang Scenario | Status |
|------|-----------|----------------|--------|
| `read_file` | `AgentGraphBuilderTest` | `scenario-coder.json` | Covered |
| `write_file` | `AgentGraphBuilderExecFlowTest` | `scenario-coder-exec.json`, `scenario-exec-time.json` | Covered |
| `list_directory` | — | `scenario-fs-tools.json` | Covered |
| `search_files` | — | `scenario-fs-tools.json` | Covered |
| `shell_exec` | — | `scenario-coder-exec.json`, `scenario-exec-time.json` | Covered |
| `git_status` | — | `scenario-git-tools.json` | Covered |
| `git_diff` | — | `scenario-git-tools.json` | Covered |
| `git_commit` | — | — | **Not tested** (risky) |
| `http_get` | — | `scenario-http.json` | Covered |
| `create_ticket` | — | `scenario-pm-tools.json` | Covered |
| `create_idea` | — | `scenario-pm-tools.json` | Covered |
| `jbang_exec` | `CodeExecutionTest` | `scenario-jbang-exec.json` | Covered |
| `python_exec` | `CodeExecutionTest` | `scenario-python-exec.json` | Covered |
| `memory` | — | `scenario-memory.json` | Covered |
| `excel` | — | — | **Not tested** (needs fixture) |
| `human_search` | — | — | **Not tested** (interactive) |
| **Total** | | | **13/16 covered** |
