package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "objectives")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
@CompoundIndex(name = "project_sprint", def = "{'projectId': 1, 'sprintName': 1}")
public class ObjectiveDocument {

    @Id
    private String objectiveId;
    @Indexed
    private String projectId;
    private String sprintName;
    private String outcome;
    private String measurableSignal;
    private List<String> risks;
    private List<String> threadIds;
    private List<String> ticketIds;
    private Double coveragePercent;
    private ObjectiveStatus status;
    private Instant startDate;
    private Instant endDate;
    private Instant createdAt;
    private Instant updatedAt;

    public ObjectiveDocument() {}

    public String getObjectiveId() { return objectiveId; }
    public void setObjectiveId(String objectiveId) { this.objectiveId = objectiveId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSprintName() { return sprintName; }
    public void setSprintName(String sprintName) { this.sprintName = sprintName; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getMeasurableSignal() { return measurableSignal; }
    public void setMeasurableSignal(String measurableSignal) { this.measurableSignal = measurableSignal; }

    public List<String> getRisks() { return risks; }
    public void setRisks(List<String> risks) { this.risks = risks; }

    public List<String> getThreadIds() { return threadIds; }
    public void setThreadIds(List<String> threadIds) { this.threadIds = threadIds; }

    public List<String> getTicketIds() { return ticketIds; }
    public void setTicketIds(List<String> ticketIds) { this.ticketIds = ticketIds; }

    public Double getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(Double coveragePercent) { this.coveragePercent = coveragePercent; }

    public ObjectiveStatus getStatus() { return status; }
    public void setStatus(ObjectiveStatus status) { this.status = status; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
