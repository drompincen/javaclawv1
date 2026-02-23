#!/bin/bash
# JavaClaw Tutorial 05 — Reconciliation
# Seeds multi-source data, creates blindspots manually,
# then queries and updates them. Works in any mode (all CRUD).
set -euo pipefail

CURL="curl"
DEVNULL="/dev/null"

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Find or Create Project ---
section "1. Find or Create Project"
PROJECT_NAME="Tutorial Payment Gateway"
PROJECT_ID=$($CURL -s "$BASE_URL/api/projects" | jq -r --arg name "$PROJECT_NAME" \
  '.[] | select(.name == $name) | .projectId' | head -1)
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "null" ]; then
  PROJECT=$($CURL -s -X POST "$BASE_URL/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$PROJECT_NAME\",\"description\":\"Payment Gateway tutorial project\",\"tags\":[\"tutorial\"]}")
  PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
  ok "Project created: $PROJECT_ID"
else
  ok "Project found: $PROJECT_ID"
fi

# --- Create Blindspots ---
section "2. Create Blindspots"
BS1=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Status mismatch on PAY-103",
    "description":"Jira says Done but internal tracker says TODO — needs investigation",
    "category":"STALE_ARTIFACT",
    "severity":"HIGH",
    "owner":"joe"
  }')
BS1_ID=$(echo "$BS1" | jq -r '.blindspotId')
ok "Blindspot: Status mismatch ($BS1_ID)"

BS2=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Owner mismatch on PAY-104",
    "description":"Jira shows Bob but capacity plan shows Joe — reassignment not tracked",
    "category":"MISSING_OWNER",
    "severity":"MEDIUM",
    "owner":"alice"
  }')
BS2_ID=$(echo "$BS2" | jq -r '.blindspotId')
ok "Blindspot: Owner mismatch ($BS2_ID)"

BS3=$($CURL -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"No test coverage for S3 upload",
    "description":"Presigned URL endpoint has zero integration tests",
    "category":"MISSING_TEST_SIGNAL",
    "severity":"HIGH"
  }')
BS3_ID=$(echo "$BS3" | jq -r '.blindspotId')
ok "Blindspot: Missing tests ($BS3_ID)"

# --- List Blindspots ---
section "3. List All Blindspots"
BLINDSPOTS=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
echo "$BLINDSPOTS" | jq -r '.[] | "  [\(.severity)] [\(.status)] \(.title) — owned by \(.owner // "unassigned")"'

# --- Filter by Category ---
section "4. Filter: MISSING_TEST_SIGNAL Category"
FILTERED=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots?category=MISSING_TEST_SIGNAL")
F_COUNT=$(echo "$FILTERED" | jq 'length')
ok "$F_COUNT blindspot(s) in MISSING_TEST_SIGNAL category"

# --- Update Blindspot Status ---
section "5. Resolve a Blindspot"
$CURL -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/blindspots/$BS1_ID" \
  -H 'Content-Type: application/json' \
  -d '{"status":"ACKNOWLEDGED"}' > /dev/null
ok "Blindspot $BS1_ID -> ACKNOWLEDGED"

$CURL -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/blindspots/$BS1_ID" \
  -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED"}' > /dev/null
ok "Blindspot $BS1_ID -> RESOLVED"

# --- Verify Final State ---
section "6. Final Blindspot Status"
FINAL=$($CURL -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
echo "$FINAL" | jq -r '.[] | "  [\(.status)] \(.title)"'

OPEN=$(echo "$FINAL" | jq '[.[] | select(.status=="OPEN")] | length')
RESOLVED=$(echo "$FINAL" | jq '[.[] | select(.status=="RESOLVED")] | length')
ok "$OPEN open, $RESOLVED resolved"

# --- Summary ---
section "7. Summary"
echo "  Reconciliation demonstrates cross-referencing data from"
echo "  multiple sources (Jira export vs internal state) and detecting:"
echo "    - Status mismatches (Jira says Done, tracker says TODO)"
echo "    - Owner mismatches (reassignment not propagated)"
echo "    - Technical gaps (missing test coverage)"
echo ""
echo "  Blindspot statuses: OPEN -> ACKNOWLEDGED -> RESOLVED or DISMISSED"

# --- Teardown ---
section "Teardown"
$CURL -s -X DELETE "$BASE_URL/api/projects/$PROJECT_ID/data" -o $DEVNULL
ok "Cleaned project data for next tutorial"

echo -e "\n${GREEN}DONE${NC} — Tutorial 05 complete."
