package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "milestones")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class MilestoneDocument {

    @Id
    private String milestoneId;
    @Indexed
    private String projectId;
    private String name;
    private String description;
    private Instant targetDate;
    private Instant actualDate;
    private MilestoneStatus status;
    @Indexed
    private String phaseId;
    private List<String> objectiveIds;
    private List<String> ticketIds;
    private String owner;
    private List<String> dependencies;
    private Instant createdAt;
    private Instant updatedAt;

    public MilestoneDocument() {}

    public String getMilestoneId() { return milestoneId; }
    public void setMilestoneId(String milestoneId) { this.milestoneId = milestoneId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getTargetDate() { return targetDate; }
    public void setTargetDate(Instant targetDate) { this.targetDate = targetDate; }

    public Instant getActualDate() { return actualDate; }
    public void setActualDate(Instant actualDate) { this.actualDate = actualDate; }

    public MilestoneStatus getStatus() { return status; }
    public void setStatus(MilestoneStatus status) { this.status = status; }

    public String getPhaseId() { return phaseId; }
    public void setPhaseId(String phaseId) { this.phaseId = phaseId; }

    public List<String> getObjectiveIds() { return objectiveIds; }
    public void setObjectiveIds(List<String> objectiveIds) { this.objectiveIds = objectiveIds; }

    public List<String> getTicketIds() { return ticketIds; }
    public void setTicketIds(List<String> ticketIds) { this.ticketIds = ticketIds; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
