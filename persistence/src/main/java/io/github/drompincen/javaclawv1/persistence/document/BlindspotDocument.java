package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.BlindspotCategory;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotSeverity;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "blindspots")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class BlindspotDocument {

    @Id
    private String blindspotId;
    @Indexed
    private String projectId;
    private String projectName;
    private String title;
    private String description;
    @Indexed
    private BlindspotCategory category;
    private BlindspotSeverity severity;
    @Indexed
    private BlindspotStatus status;
    private String owner;
    private List<Map<String, String>> sourceRefs;
    private String deltaPackId;
    private String reconcileRunId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    public BlindspotDocument() {}

    public String getBlindspotId() { return blindspotId; }
    public void setBlindspotId(String blindspotId) { this.blindspotId = blindspotId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BlindspotCategory getCategory() { return category; }
    public void setCategory(BlindspotCategory category) { this.category = category; }
    public BlindspotSeverity getSeverity() { return severity; }
    public void setSeverity(BlindspotSeverity severity) { this.severity = severity; }
    public BlindspotStatus getStatus() { return status; }
    public void setStatus(BlindspotStatus status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public List<Map<String, String>> getSourceRefs() { return sourceRefs; }
    public void setSourceRefs(List<Map<String, String>> sourceRefs) { this.sourceRefs = sourceRefs; }
    public String getDeltaPackId() { return deltaPackId; }
    public void setDeltaPackId(String deltaPackId) { this.deltaPackId = deltaPackId; }
    public String getReconcileRunId() { return reconcileRunId; }
    public void setReconcileRunId(String reconcileRunId) { this.reconcileRunId = reconcileRunId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
