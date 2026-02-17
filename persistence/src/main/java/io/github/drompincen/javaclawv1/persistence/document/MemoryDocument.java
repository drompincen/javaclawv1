package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "memories")
@CompoundIndex(name = "scope_key", def = "{'scope': 1, 'key': 1}")
@CompoundIndex(name = "project_scope", def = "{'projectId': 1, 'scope': 1}")
@CompoundIndex(name = "thread_scope", def = "{'threadId': 1, 'scope': 1}")
public class MemoryDocument {

    @Id
    private String memoryId;
    private MemoryScope scope;
    private String projectId;
    private String sessionId;
    private String threadId;
    private String key;
    private String content;
    private List<String> tags;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public enum MemoryScope {
        GLOBAL, PROJECT, SESSION, THREAD
    }

    public MemoryDocument() {}

    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }

    public MemoryScope getScope() { return scope; }
    public void setScope(MemoryScope scope) { this.scope = scope; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
