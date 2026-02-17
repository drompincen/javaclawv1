package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "resource_assignments")
@CompoundIndex(name = "resource_idx", def = "{'resourceId': 1}")
public class ResourceAssignmentDocument {

    @Id
    private String assignmentId;
    private String resourceId;
    private String ticketId;
    private double percentageAllocation;

    public ResourceAssignmentDocument() {}

    public String getAssignmentId() { return assignmentId; }
    public void setAssignmentId(String assignmentId) { this.assignmentId = assignmentId; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public double getPercentageAllocation() { return percentageAllocation; }
    public void setPercentageAllocation(double percentageAllocation) { this.percentageAllocation = percentageAllocation; }
}
