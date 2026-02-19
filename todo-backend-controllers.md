# Backend Controllers Needed for Web UI

The web cockpit at `gateway/src/main/resources/static/` consumes the following REST endpoints.
Controllers marked **EXISTS** are already implemented. Controllers marked **NEEDED** must be created.

---

## 1. ObjectiveController — **NEEDED**

**File**: `gateway/src/main/java/io/github/drompincen/javaclawv1/gateway/controller/ObjectiveController.java`

**Consumed by**: `js/views/objectives.js`

| Method | Path | Used by |
|--------|------|---------|
| `GET` | `/api/projects/{pid}/objectives` | `api.objectives.list(pid)` — renders objective table |
| `GET` | `/api/projects/{pid}/objectives/{oid}` | `api.objectives.get(pid, id)` — inspector detail |
| `POST` | `/api/projects/{pid}/objectives` | `api.objectives.create(pid, data)` — future use |
| `PUT` | `/api/projects/{pid}/objectives/{oid}` | `api.objectives.update(pid, id, data)` — future use |
| `DELETE` | `/api/projects/{pid}/objectives/{oid}` | `api.objectives.delete(pid, id)` — future use |

**Uses**: `ObjectiveRepository`, `ObjectiveDocument`, `ObjectiveDto`, `ObjectiveStatus`

**Pattern**: Follow `ChecklistController` — constructor-inject repo, `@RequestMapping("/api/projects/{projectId}/objectives")`, CRUD with `toDto()` conversion.

**DTO fields** (from `ObjectiveDto` record):
```
objectiveId, projectId, sprintName, outcome, measurableSignal,
risks (List<String>), threadIds, ticketIds, coveragePercent (Double),
status (ObjectiveStatus), startDate, endDate, createdAt, updatedAt
```

---

## 2. PhaseController — **NEEDED**

**File**: `gateway/src/main/java/io/github/drompincen/javaclawv1/gateway/controller/PhaseController.java`

**Consumed by**: `js/views/plans.js`

| Method | Path | Used by |
|--------|------|---------|
| `GET` | `/api/projects/{pid}/phases` | `api.phases.list(pid)` — renders phase cards |
| `GET` | `/api/projects/{pid}/phases/{phaseId}` | `api.phases.get(pid, id)` — inspector detail |
| `POST` | `/api/projects/{pid}/phases` | `api.phases.create(pid, data)` — future use |
| `PUT` | `/api/projects/{pid}/phases/{phaseId}` | `api.phases.update(pid, id, data)` — future use |
| `DELETE` | `/api/projects/{pid}/phases/{phaseId}` | `api.phases.delete(pid, id)` — future use |

**Uses**: `PhaseRepository`, `PhaseDocument`, `PhaseDto`, `PhaseStatus`

**Notes**: Use `findByProjectIdOrderBySortOrder` for list endpoint.

**DTO fields** (from `PhaseDto` record):
```
phaseId, projectId, name, description, entryCriteria (List<String>),
exitCriteria (List<String>), checklistIds, objectiveIds,
status (PhaseStatus), sortOrder (int), startDate, endDate, createdAt, updatedAt
```

---

## 3. LinkController — **NEEDED**

**File**: `gateway/src/main/java/io/github/drompincen/javaclawv1/gateway/controller/LinkController.java`

**Consumed by**: `js/views/links.js`

| Method | Path | Used by |
|--------|------|---------|
| `GET` | `/api/projects/{pid}/links` | `api.links.list(pid)` — renders link category grid |
| `GET` | `/api/projects/{pid}/links?category=X` | `api.links.list(pid, {category})` — filtered |
| `GET` | `/api/projects/{pid}/links?bundleId=X` | `api.links.list(pid, {bundleId})` — filtered |
| `GET` | `/api/projects/{pid}/links/{linkId}` | `api.links.get(pid, id)` — inspector detail |
| `POST` | `/api/projects/{pid}/links` | `api.links.create(pid, data)` — future use |
| `PUT` | `/api/projects/{pid}/links/{linkId}` | `api.links.update(pid, id, data)` — future use |
| `DELETE` | `/api/projects/{pid}/links/{linkId}` | `api.links.delete(pid, id)` — future use |

**Uses**: `LinkRepository`, `LinkDocument`, `LinkDto`

**Notes**: The `list` endpoint should support `@RequestParam(required = false)` for `category` and `bundleId`.

**DTO fields** (from `LinkDto` record):
```
linkId, projectId, url, title, category, description,
pinned (boolean), bundleId, threadIds, objectiveIds, phaseIds,
tags (List<String>), createdAt, updatedAt
```

---

## 4. ReconciliationController — **NEEDED**

**File**: `gateway/src/main/java/io/github/drompincen/javaclawv1/gateway/controller/ReconciliationController.java`

**Consumed by**: `js/views/reconcile.js`

| Method | Path | Used by |
|--------|------|---------|
| `GET` | `/api/projects/{pid}/reconciliations` | `api.reconciliations.list(pid)` — renders delta pack |
| `GET` | `/api/projects/{pid}/reconciliations/{rid}` | `api.reconciliations.get(pid, id)` — inspector detail |

**Uses**: `ReconciliationRepository`, `ReconciliationDocument`, `ReconciliationDto`, `ReconciliationStatus`

**Notes**: Read-only for now (reconciliation runs are created by the reconcile agent). Optionally add `POST` for manual creation.

**DTO fields** (from `ReconciliationDto` record):
```
reconciliationId, projectId, sourceUploadId, sourceType,
mappings (List<MappingEntry>), conflicts (List<ConflictEntry>),
status (ReconciliationStatus), createdAt, updatedAt
```

**Nested records**:
- `MappingEntry(sourceRow, ticketId, matchType)`
- `ConflictEntry(field, sourceValue, ticketValue, resolution)`

---

## 5. WebSocket: SUBSCRIBE_PROJECT — **NEEDED**

**File**: Modify `gateway/src/main/java/io/github/drompincen/javaclawv1/gateway/websocket/JavaClawWebSocketHandler.java`

**Consumed by**: `js/ws.js` → `subscribeProject(projectId)`

**What to add**:
1. Add a new map: `Map<String, Set<WebSocketSession>> projectSubscriptions`
2. In `handleTextMessage`, handle `SUBSCRIBE_PROJECT` type:
   ```java
   } else if ("SUBSCRIBE_PROJECT".equals(type)) {
       String projectId = node.path("projectId").asText();
       projectSubscriptions.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(session);
       session.sendMessage(new TextMessage(
           objectMapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "projectId", projectId))));
   }
   ```
3. In `onEvent`, after session-level broadcasting, also broadcast to project subscribers:
   - Look up the session's `projectId` via `SessionRepository.findById(event.getSessionId())`
   - If the session has a `projectId`, broadcast to all `projectSubscriptions.get(projectId)`
4. Clean up project subscriptions in `afterConnectionClosed`

**Also modify**: `protocol/src/main/java/...protocol/ws/WsMessageType.java` — add `SUBSCRIBE_PROJECT` to the enum.

---

## Already Existing Controllers (no work needed)

| Controller | Path | Status |
|-----------|------|--------|
| `ProjectController` | `/api/projects` | EXISTS |
| `ThreadController` | `/api/projects/{pid}/threads` | EXISTS |
| `TicketController` | `/api/projects/{pid}/tickets` | EXISTS |
| `IdeaController` | `/api/projects/{pid}/ideas` | EXISTS |
| `ChecklistController` | `/api/projects/{pid}/checklists` | EXISTS |
| `ReminderController` | `/api/projects/{pid}/reminders` | EXISTS |
| `SessionController` | `/api/sessions` | EXISTS |
| `AgentController` | `/api/agents` | EXISTS |
| `ResourceController` | `/api/projects/{pid}/resources` | EXISTS |
| `MemoryController` | `/api/projects/{pid}/memories` | EXISTS |

---

## Priority Order

1. **ObjectiveController** + **PhaseController** — these are the most visible empty workspaces
2. **LinkController** — the LinkHub grid will show "No links" without it
3. **ReconciliationController** — reconcile view gracefully handles missing data
4. **SUBSCRIBE_PROJECT** — activity stream works without it but only shows session-scoped events
