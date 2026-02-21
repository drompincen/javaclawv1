#!/bin/bash
# JavaClaw Tutorial 09 — Checklists and Plans
# Creates phases, milestones, checklists with items, tracks progress.
# Works in any mode (all CRUD).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_NAME="Tutorial Payment Gateway"
PROJECT_ID=$(curl -s "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$(curl -s -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Payment Gateway tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Create Phases ---
section "2. Create Phases"
PHASE1=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/phases" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Development",
    "description": "Feature development and unit testing",
    "entryCriteria": ["Requirements approved", "Design reviewed"],
    "exitCriteria": ["All unit tests passing", "Code reviewed"],
    "status": "IN_PROGRESS",
    "sortOrder": 1
  }')
P1_ID=$(echo "$PHASE1" | jq -r '.phaseId')
ok "Phase: Development ($P1_ID)"

PHASE2=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/phases" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "ORR",
    "description": "Operational Readiness Review",
    "entryCriteria": ["Development complete", "All tests green"],
    "exitCriteria": ["ORR checklist 100%", "Sign-off from team lead"],
    "status": "NOT_STARTED",
    "sortOrder": 2
  }')
P2_ID=$(echo "$PHASE2" | jq -r '.phaseId')
ok "Phase: ORR ($P2_ID)"

# --- Create Milestone ---
section "3. Create Milestone"
MILESTONE=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/milestones" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"ORR Sign-off\",
    \"description\": \"All checklist items verified, production readiness confirmed\",
    \"status\": \"UPCOMING\",
    \"phaseId\": \"$P2_ID\",
    \"owner\": \"alice\"
  }")
MS_ID=$(echo "$MILESTONE" | jq -r '.milestoneId')
ok "Milestone: ORR Sign-off ($MS_ID)"

# --- Create Checklist ---
section "4. Create ORR Checklist"
CHECKLIST=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/checklists" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"PCI Compliance Checklist — Payment Gateway\",
    \"phaseId\": \"$P2_ID\",
    \"items\": [
      {\"text\": \"Load test results documented\", \"assignee\": \"joe\"},
      {\"text\": \"Runbook written and reviewed\", \"assignee\": \"joe\"},
      {\"text\": \"Monitoring dashboards configured\", \"assignee\": \"alice\"},
      {\"text\": \"Alerting rules verified\", \"assignee\": \"alice\"},
      {\"text\": \"Rollback procedure tested\", \"assignee\": \"bob\"},
      {\"text\": \"Incident response plan approved\", \"assignee\": \"bob\"}
    ],
    \"status\": \"IN_PROGRESS\"
  }")
CL_ID=$(echo "$CHECKLIST" | jq -r '.checklistId')
ok "Checklist: $CL_ID (6 items)"

# --- Show Checklist Items ---
section "5. Checklist Items"
CL_DATA=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/checklists/$CL_ID")
echo "$CL_DATA" | jq -r '.items[] | "  [\(if .checked then "x" else " " end)] \(.text) — \(.assignee)"'

# --- Check Off Items ---
section "6. Check Off Completed Items"
ITEMS=$(echo "$CL_DATA" | jq '.items')
UPDATED_ITEMS=$(echo "$ITEMS" | jq '
  [.[] | if .text == "Load test results documented" or .text == "Monitoring dashboards configured"
   then .checked = true else . end]')
curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/checklists/$CL_ID" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --argjson items "$UPDATED_ITEMS" '{items: $items}')" > /dev/null
ok "Checked off 2 items (load test + monitoring)"

# --- Show Updated Progress ---
section "7. Updated Progress"
CL_UPDATED=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/checklists/$CL_ID")
echo "$CL_UPDATED" | jq -r '.items[] | "  [\(if .checked then "x" else " " end)] \(.text) — \(.assignee)"'
CHECKED=$(echo "$CL_UPDATED" | jq '[.items[] | select(.checked)] | length')
TOTAL=$(echo "$CL_UPDATED" | jq '.items | length')
ok "Progress: $CHECKED/$TOTAL items complete"

# --- List Phases ---
section "8. Phase Overview"
PHASES=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/phases")
echo "$PHASES" | jq -r '.[] | "  [\(.status)] \(.name)"'

# --- List Milestones ---
section "9. Milestone Status"
MILESTONES=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/milestones")
echo "$MILESTONES" | jq -r '.[] | "  [\(.status)] \(.name) — owner: \(.owner // "unassigned")"'

# --- Summary ---
section "10. Summary"
echo "  Project planning hierarchy:"
echo "    Phases     -> high-level stages (Development, ORR, Production)"
echo "    Milestones -> key dates within phases"
echo "    Checklists -> detailed items with assignees"
echo ""
echo "  ORR Checklist: $CHECKED/$TOTAL complete"
echo "  Next: Complete remaining 4 items before ORR Sign-off milestone"

echo -e "\n${GREEN}DONE${NC} — Tutorial 09 complete."
