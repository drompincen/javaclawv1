// MongoDB initialization script for JavaClaw v2
// Creates indexes on all collections

db = db.getSiblingDB('javaclaw');

// Events: unique per session + seq
db.events.createIndex({ sessionId: 1, seq: 1 }, { unique: true });

// Checkpoints: session + step descending
db.checkpoints.createIndex({ sessionId: 1, stepNo: -1 });

// Specs: tag multikey index + text search on title
db.specs.createIndex({ tags: 1 });
db.specs.createIndex({ title: "text" });

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

// Tickets: project + status
db.tickets.createIndex({ projectId: 1, status: 1 });

// Ideas: project + tags
db.ideas.createIndex({ projectId: 1, tags: 1 });

// Approvals: thread + status
db.approvals.createIndex({ threadId: 1, status: 1 });

// Resource assignments: resource
db.resource_assignments.createIndex({ resourceId: 1 });

// Reminders: project + trigger time
db.reminders.createIndex({ projectId: 1, triggerAt: 1 });

// Designs: project
db.designs.createIndex({ projectId: 1 });

// Scorecards: unique project
db.scorecards.createIndex({ projectId: 1 }, { unique: true });

// Memories: scope + key, content text search
db.memories.createIndex({ scope: 1, key: 1 });
db.memories.createIndex({ projectId: 1, scope: 1 });
db.memories.createIndex({ sessionId: 1, scope: 1 });
db.memories.createIndex({ content: "text", key: "text" });
db.memories.createIndex({ tags: 1 });

// Agents
db.agents.createIndex({ role: 1 });
db.agents.createIndex({ enabled: 1 });

print("JavaClaw v2 indexes created successfully");
