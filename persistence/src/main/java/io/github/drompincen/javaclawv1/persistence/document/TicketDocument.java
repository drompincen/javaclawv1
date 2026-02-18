package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.protocol.api.TicketType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "tickets")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class TicketDocument {

    @Id
    private String ticketId;
    private String projectId;
    private String title;
    private String description;
    private TicketDto.TicketStatus status;
    private TicketDto.TicketPriority priority;
    private TicketType type;
    @Indexed
    private String parentTicketId;
    private String assignedResourceId;
    private List<String> linkedThreadIds;
    private List<String> blockedBy;
    private List<String> objectiveIds;
    private String phaseId;
    private List<String> evidenceLinks;
    private String externalRef;
    private String owner;
    private Instant lastExternalSync;
    private Instant createdAt;
    private Instant updatedAt;

    public TicketDocument() {}

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TicketDto.TicketStatus getStatus() { return status; }
    public void setStatus(TicketDto.TicketStatus status) { this.status = status; }

    public TicketDto.TicketPriority getPriority() { return priority; }
    public void setPriority(TicketDto.TicketPriority priority) { this.priority = priority; }

    public TicketType getType() { return type; }
    public void setType(TicketType type) { this.type = type; }

    public String getParentTicketId() { return parentTicketId; }
    public void setParentTicketId(String parentTicketId) { this.parentTicketId = parentTicketId; }

    public String getAssignedResourceId() { return assignedResourceId; }
    public void setAssignedResourceId(String assignedResourceId) { this.assignedResourceId = assignedResourceId; }

    public List<String> getLinkedThreadIds() { return linkedThreadIds; }
    public void setLinkedThreadIds(List<String> linkedThreadIds) { this.linkedThreadIds = linkedThreadIds; }

    public List<String> getBlockedBy() { return blockedBy; }
    public void setBlockedBy(List<String> blockedBy) { this.blockedBy = blockedBy; }

    public List<String> getObjectiveIds() { return objectiveIds; }
    public void setObjectiveIds(List<String> objectiveIds) { this.objectiveIds = objectiveIds; }

    public String getPhaseId() { return phaseId; }
    public void setPhaseId(String phaseId) { this.phaseId = phaseId; }

    public List<String> getEvidenceLinks() { return evidenceLinks; }
    public void setEvidenceLinks(List<String> evidenceLinks) { this.evidenceLinks = evidenceLinks; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public Instant getLastExternalSync() { return lastExternalSync; }
    public void setLastExternalSync(Instant lastExternalSync) { this.lastExternalSync = lastExternalSync; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
