package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "phases")
@CompoundIndex(name = "project_sort", def = "{'projectId': 1, 'sortOrder': 1}")
public class PhaseDocument {

    @Id
    private String phaseId;
    @Indexed
    private String projectId;
    private String name;
    private String description;
    private List<String> entryCriteria;
    private List<String> exitCriteria;
    private List<String> checklistIds;
    private List<String> objectiveIds;
    private PhaseStatus status;
    private int sortOrder;
    private Instant startDate;
    private Instant endDate;
    private Instant createdAt;
    private Instant updatedAt;

    public PhaseDocument() {}

    public String getPhaseId() { return phaseId; }
    public void setPhaseId(String phaseId) { this.phaseId = phaseId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getEntryCriteria() { return entryCriteria; }
    public void setEntryCriteria(List<String> entryCriteria) { this.entryCriteria = entryCriteria; }

    public List<String> getExitCriteria() { return exitCriteria; }
    public void setExitCriteria(List<String> exitCriteria) { this.exitCriteria = exitCriteria; }

    public List<String> getChecklistIds() { return checklistIds; }
    public void setChecklistIds(List<String> checklistIds) { this.checklistIds = checklistIds; }

    public List<String> getObjectiveIds() { return objectiveIds; }
    public void setObjectiveIds(List<String> objectiveIds) { this.objectiveIds = objectiveIds; }

    public PhaseStatus getStatus() { return status; }
    public void setStatus(PhaseStatus status) { this.status = status; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
