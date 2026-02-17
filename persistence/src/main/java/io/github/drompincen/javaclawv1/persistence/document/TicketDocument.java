package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
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
    private String assignedResourceId;
    private List<String> linkedThreadIds;
    private List<String> blockedBy;
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

    public String getAssignedResourceId() { return assignedResourceId; }
    public void setAssignedResourceId(String assignedResourceId) { this.assignedResourceId = assignedResourceId; }

    public List<String> getLinkedThreadIds() { return linkedThreadIds; }
    public void setLinkedThreadIds(List<String> linkedThreadIds) { this.linkedThreadIds = linkedThreadIds; }

    public List<String> getBlockedBy() { return blockedBy; }
    public void setBlockedBy(List<String> blockedBy) { this.blockedBy = blockedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
