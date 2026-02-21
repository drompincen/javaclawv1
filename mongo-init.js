// MongoDB initialization script for JavaClaw v2
// Creates indexes on all collections
// Domain entities live in the unified "things" collection

db = db.getSiblingDB('javaclaw');

// ---- Infrastructure collections ----

// Events: unique per session + seq
db.events.createIndex({ sessionId: 1, seq: 1 }, { unique: true });

// Checkpoints: session + step descending
db.checkpoints.createIndex({ sessionId: 1, stepNo: -1 });

// Sessions: updated descending for listing
db.sessions.createIndex({ updatedAt: -1 });

// Messages: session + seq
db.messages.createIndex({ sessionId: 1, seq: 1 }, { unique: true });

// Locks: TTL index for auto-expiry
db.locks.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 });

// Projects
db.projects.createIndex({ updatedAt: -1 });
db.projects.createIndex({ status: 1 });

// Threads: project + updated descending
db.threads.createIndex({ projectId: 1, updatedAt: -1 });

// Approvals: thread + status
db.approvals.createIndex({ threadId: 1, status: 1 });

// Memories: scope + key, content text search, TTL for expiring summaries
db.memories.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 });
db.memories.createIndex({ scope: 1, key: 1 });
db.memories.createIndex({ projectId: 1, scope: 1 });
db.memories.createIndex({ sessionId: 1, scope: 1 });
db.memories.createIndex({ content: "text", key: "text" });
db.memories.createIndex({ tags: 1 });

// Agents
db.agents.createIndex({ role: 1 });
db.agents.createIndex({ enabled: 1 });

// Agent schedules
db.agent_schedules.createIndex({ agentId: 1, enabled: 1 });

// Future executions
db.future_executions.createIndex({ scheduledAt: 1 });

// Past executions
db.past_executions.createIndex({ executedAt: -1 });

// LLM interactions
db.llm_interactions.createIndex({ sessionId: 1, timestamp: -1 });

// Logs
db.logs.createIndex({ level: 1, timestamp: -1 });

// ---- Things: unified domain collection ----
// All domain entities (tickets, objectives, resources, phases, etc.)

// Universal indexes
db.things.createIndex({ projectId: 1, thingCategory: 1 });
db.things.createIndex({ projectId: 1, thingCategory: 1, "payload.status": 1 });

// Category-specific partial filter indexes
db.things.createIndex({ projectId: 1, "payload.sprintName": 1 },
    { partialFilterExpression: { thingCategory: "OBJECTIVE" } });
db.things.createIndex({ projectId: 1, "payload.sortOrder": 1 },
    { partialFilterExpression: { thingCategory: "PHASE" } });
db.things.createIndex({ "payload.resourceId": 1 },
    { partialFilterExpression: { thingCategory: "RESOURCE_ASSIGNMENT" } });
db.things.createIndex({ "payload.ticketId": 1 },
    { partialFilterExpression: { thingCategory: "RESOURCE_ASSIGNMENT" } });
db.things.createIndex({ "payload.phaseId": 1 },
    { partialFilterExpression: { thingCategory: { $in: ["CHECKLIST", "MILESTONE"] } } });
db.things.createIndex({ "payload.deltaPackId": 1 },
    { partialFilterExpression: { thingCategory: "BLINDSPOT" } });
db.things.createIndex({ "payload.triggered": 1, "payload.triggerAt": 1 },
    { partialFilterExpression: { thingCategory: "REMINDER" } });
db.things.createIndex({ "payload.title": "text", "payload.content": "text" },
    { partialFilterExpression: { thingCategory: "UPLOAD" } });
db.things.createIndex({ "payload.parentTicketId": 1 },
    { partialFilterExpression: { thingCategory: "TICKET" } });
db.things.createIndex({ projectId: 1, "payload.tags": 1 },
    { partialFilterExpression: { thingCategory: "IDEA" } });

print("JavaClaw v2 indexes created successfully");
