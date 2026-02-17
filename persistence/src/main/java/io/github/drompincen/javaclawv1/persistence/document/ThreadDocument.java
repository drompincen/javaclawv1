package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ModelConfig;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolPolicy;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "threads")
@CompoundIndex(name = "project_updated", def = "{'projectIds': 1, 'updatedAt': -1}")
public class ThreadDocument {

    @Id
    private String threadId;
    private List<String> projectIds;
    @Deprecated
    private String projectId;
    private String title;
    private SessionStatus status;
    private ModelConfig modelConfig;
    private ToolPolicy toolPolicy;
    private String currentCheckpointId;
    private Instant createdAt;
    private Instant updatedAt;

    public ThreadDocument() {}

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public List<String> getProjectIds() { return projectIds; }
    public void setProjectIds(List<String> projectIds) { this.projectIds = projectIds; }

    @Deprecated
    public String getProjectId() { return projectId; }
    @Deprecated
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public List<String> getEffectiveProjectIds() {
        if (projectIds != null && !projectIds.isEmpty()) return projectIds;
        return projectId != null ? List.of(projectId) : List.of();
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public ModelConfig getModelConfig() { return modelConfig; }
    public void setModelConfig(ModelConfig modelConfig) { this.modelConfig = modelConfig; }

    public ToolPolicy getToolPolicy() { return toolPolicy; }
    public void setToolPolicy(ToolPolicy toolPolicy) { this.toolPolicy = toolPolicy; }

    public String getCurrentCheckpointId() { return currentCheckpointId; }
    public void setCurrentCheckpointId(String id) { this.currentCheckpointId = id; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
