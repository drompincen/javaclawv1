#!/bin/bash
# JavaClaw Tutorial 05 — Reconciliation
# Seeds multi-source data, creates delta packs and blindspots manually,
# then queries and updates them. Works in any mode (all CRUD).
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }
ok()      { echo -e "${GREEN}  OK${NC} $1"; }
fail()    { echo -e "${RED}  FAIL${NC} $1"; exit 1; }

# --- Create Project ---
section "1. Create Project"
PROJECT=$(curl -s -X POST "$BASE_URL/api/projects" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Tutorial Reconciliation","description":"Delta pack and blindspot demo","tags":["tutorial","reconcile"]}')
PROJECT_ID=$(echo "$PROJECT" | jq -r '.projectId')
ok "Project: $PROJECT_ID"

# --- Create a Reconciliation Run ---
section "2. Create Reconciliation Run"
RECON=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/reconciliations" \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"JIRA_EXCEL","status":"DRAFT"}')
RECON_ID=$(echo "$RECON" | jq -r '.reconciliationId')
ok "Reconciliation: $RECON_ID (DRAFT)"

# --- Update Reconciliation with Conflicts ---
section "3. Add Conflicts to Reconciliation"
curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/reconciliations/$RECON_ID" \
  -H 'Content-Type: application/json' \
  -d '{
    "status": "COMPLETE",
    "conflicts": [
      {"field":"status","sourceValue":"Done","ticketValue":"TODO","resolution":null},
      {"field":"assignee","sourceValue":"Bob","ticketValue":"Joe","resolution":null}
    ]
  }' > /dev/null
ok "Reconciliation updated with 2 conflicts"

# --- Create Blindspots ---
section "4. Create Blindspots"
BS1=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Status mismatch on KYC-103",
    "description":"Jira says Done but internal tracker says TODO — needs investigation",
    "category":"PROCESS",
    "severity":"HIGH",
    "owner":"joe"
  }')
BS1_ID=$(echo "$BS1" | jq -r '.blindspotId')
ok "Blindspot: Status mismatch ($BS1_ID)"

BS2=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Owner mismatch on KYC-104",
    "description":"Jira shows Bob but capacity plan shows Joe — reassignment not tracked",
    "category":"RESOURCE",
    "severity":"MEDIUM",
    "owner":"alice"
  }')
BS2_ID=$(echo "$BS2" | jq -r '.blindspotId')
ok "Blindspot: Owner mismatch ($BS2_ID)"

BS3=$(curl -s -X POST "$BASE_URL/api/projects/$PROJECT_ID/blindspots" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"No test coverage for S3 upload",
    "description":"Presigned URL endpoint has zero integration tests",
    "category":"TECHNICAL",
    "severity":"HIGH"
  }')
BS3_ID=$(echo "$BS3" | jq -r '.blindspotId')
ok "Blindspot: Missing tests ($BS3_ID)"

# --- List Blindspots ---
section "5. List All Blindspots"
BLINDSPOTS=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
echo "$BLINDSPOTS" | jq -r '.[] | "  [\(.severity)] [\(.status)] \(.title) — owned by \(.owner // "unassigned")"'

# --- Filter by Severity ---
section "6. Filter: HIGH Severity"
HIGH=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots?severity=HIGH" 2>/dev/null || \
       echo "$BLINDSPOTS" | jq '[.[] | select(.severity=="HIGH")]')
H_COUNT=$(echo "$HIGH" | jq 'length')
ok "$H_COUNT HIGH-severity blindspot(s)"

# --- Update Blindspot Status ---
section "7. Resolve a Blindspot"
curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/blindspots/$BS1_ID" \
  -H 'Content-Type: application/json' \
  -d '{"status":"INVESTIGATING"}' > /dev/null
ok "Blindspot $BS1_ID → INVESTIGATING"

curl -s -X PUT "$BASE_URL/api/projects/$PROJECT_ID/blindspots/$BS1_ID" \
  -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED"}' > /dev/null
ok "Blindspot $BS1_ID → RESOLVED"

# --- Verify Final State ---
section "8. Final Blindspot Status"
FINAL=$(curl -s "$BASE_URL/api/projects/$PROJECT_ID/blindspots")
echo "$FINAL" | jq -r '.[] | "  [\(.status)] \(.title)"'

OPEN=$(echo "$FINAL" | jq '[.[] | select(.status=="OPEN")] | length')
RESOLVED=$(echo "$FINAL" | jq '[.[] | select(.status=="RESOLVED")] | length')
ok "$OPEN open, $RESOLVED resolved"

# --- Summary ---
section "9. Summary"
echo "  Reconciliation demonstrates cross-referencing data from"
echo "  multiple sources (Jira export vs internal state) and detecting:"
echo "    - Status mismatches (Jira says Done, tracker says TODO)"
echo "    - Owner mismatches (reassignment not propagated)"
echo "    - Technical gaps (missing test coverage)"
echo ""
echo "  Blindspots flow through: OPEN → INVESTIGATING → RESOLVED"

echo -e "\n${GREEN}DONE${NC} — Tutorial 05 complete."
