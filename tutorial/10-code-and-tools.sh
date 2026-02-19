#!/bin/bash
# JavaClaw Tutorial 10 — Code and Tools
# Directly invokes registered tools: list tools, read files, shell commands.
# Works in any mode (direct tool invocation, no LLM).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
warn()    { echo -e "${YELLOW:-\033[0;33m}  WARN${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- List All Tools ---
section "1. List All Registered Tools"
TOOLS=$(curl -s "$BASE_URL/api/tools")
TOOL_COUNT=$(echo "$TOOLS" | jq 'length')
ok "$TOOL_COUNT tools registered"
echo "$TOOLS" | jq -r '.[].name' | sort | head -20
echo "  ... (showing first 20)"

# --- Get Tool Descriptor ---
section "2. Tool Descriptor: shell_exec"
DESCRIPTOR=$(curl -s "$BASE_URL/api/tools/shell_exec")
echo "$DESCRIPTOR" | jq '{name, description}' 2>/dev/null || echo "  $DESCRIPTOR"

# --- Invoke: Shell Exec ---
section "3. Invoke: shell_exec"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/shell_exec/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"command": "echo Hello from JavaClaw && date"}')
echo "$RESULT" | jq -r '.output // .error // .' 2>/dev/null || echo "  $RESULT"
SUCCESS=$(echo "$RESULT" | jq -r '.success // empty')
[ "$SUCCESS" = "true" ] && ok "Shell command executed" || warn "Check output above"

# --- Invoke: Read File ---
section "4. Invoke: read_file"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/read_file/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"path": "README.md", "maxLines": 10}')
OUTPUT=$(echo "$RESULT" | jq -r '.output // empty')
if [ -n "$OUTPUT" ]; then
  ok "Read first 10 lines of README.md"
  echo "$OUTPUT" | head -5
  echo "  ..."
else
  warn "read_file returned empty (README.md may not exist in working dir)"
fi

# --- Invoke: Git Status ---
section "5. Invoke: git_status"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/git_status/invoke" \
  -H 'Content-Type: application/json' \
  -d '{}')
OUTPUT=$(echo "$RESULT" | jq -r '.output // empty')
if [ -n "$OUTPUT" ]; then
  ok "Git status retrieved"
  echo "$OUTPUT" | head -10
else
  warn "git_status returned empty"
fi

# --- Invoke: JBang Exec ---
section "6. Invoke: jbang_exec (Java snippet)"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/jbang_exec/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"code": "public class Main { public static void main(String[] args) { System.out.println(\"Java \" + System.getProperty(\"java.version\")); } }"}')
OUTPUT=$(echo "$RESULT" | jq -r '.output // .error // .' 2>/dev/null)
if echo "$OUTPUT" | grep -q "Java"; then
  ok "JBang executed Java snippet"
  echo "  $OUTPUT"
else
  warn "JBang may not be available: $OUTPUT"
fi

# --- Invoke: Python Exec ---
section "7. Invoke: python_exec (Python snippet)"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/python_exec/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"code": "import sys; print(f\"Python {sys.version_info.major}.{sys.version_info.minor}\")"}')
OUTPUT=$(echo "$RESULT" | jq -r '.output // .error // .' 2>/dev/null)
if echo "$OUTPUT" | grep -q "Python"; then
  ok "Python executed"
  echo "  $OUTPUT"
else
  warn "Python may not be available: $OUTPUT"
fi

# --- Invoke: List Directory ---
section "8. Invoke: list_directory"
RESULT=$(curl -s -X POST "$BASE_URL/api/tools/list_directory/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"path": "."}')
OUTPUT=$(echo "$RESULT" | jq -r '.output // empty')
if [ -n "$OUTPUT" ]; then
  ok "Directory listing"
  echo "$OUTPUT" | head -10
  echo "  ..."
else
  warn "list_directory returned empty"
fi

# --- Summary ---
section "9. Summary"
echo "  Tools can be invoked directly via POST /api/tools/{name}/invoke"
echo "  or by agents during conversations."
echo ""
echo "  Tool categories ($TOOL_COUNT total):"
echo "    File System  — read_file, write_file, list_directory, search_files"
echo "    Execution    — shell_exec, jbang_exec, python_exec"
echo "    Git          — git_status, git_diff, git_commit"
echo "    PM           — create_thread, create_ticket, create_idea, create_reminder"
echo "    Planning     — create_checklist, create_phase, create_milestone"
echo "    Analysis     — capacity_report, compute_coverage, suggest_assignments"
echo "    Reconcile    — create_delta_pack, create_blindspot"
echo "    Memory       — memory (store/recall/delete)"

echo -e "\n${GREEN}DONE${NC} — Tutorial 10 complete."
