package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ModelConfig;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolPolicy;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "sessions")
public class SessionDocument {

    @Id
    private String sessionId;

    @Indexed
    private String threadId;

    private Instant createdAt;

    @Indexed(direction = org.springframework.data.mongodb.core.index.IndexDirection.DESCENDING)
    private Instant updatedAt;

    private SessionStatus status;
    private ModelConfig modelConfig;
    private ToolPolicy toolPolicy;
    private String currentCheckpointId;
    private Map<String, String> metadata;

    public SessionDocument() {}

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public ModelConfig getModelConfig() { return modelConfig; }
    public void setModelConfig(ModelConfig modelConfig) { this.modelConfig = modelConfig; }

    public ToolPolicy getToolPolicy() { return toolPolicy; }
    public void setToolPolicy(ToolPolicy toolPolicy) { this.toolPolicy = toolPolicy; }

    public String getCurrentCheckpointId() { return currentCheckpointId; }
    public void setCurrentCheckpointId(String currentCheckpointId) { this.currentCheckpointId = currentCheckpointId; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
