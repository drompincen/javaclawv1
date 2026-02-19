package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class AssignResourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceRepository resourceRepository;
    private ResourceAssignmentRepository resourceAssignmentRepository;
    private TicketRepository ticketRepository;

    @Override public String name() { return "assign_resource"; }
    @Override public String description() { return "Assign a resource to a ticket with an allocation percentage."; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("resourceId").put("type", "string");
        props.putObject("ticketId").put("type", "string");
        props.putObject("projectId").put("type", "string");
        props.putObject("allocationPercent").put("type", "number");
        schema.putArray("required").add("resourceId").add("ticketId").add("projectId").add("allocationPercent");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setResourceRepository(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }
    public void setResourceAssignmentRepository(ResourceAssignmentRepository resourceAssignmentRepository) {
        this.resourceAssignmentRepository = resourceAssignmentRepository;
    }
    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceAssignmentRepository == null) return ToolResult.failure("Assignment repository not available");
        String resourceId = input.path("resourceId").asText(null);
        String ticketId = input.path("ticketId").asText(null);
        String projectId = input.path("projectId").asText(null);
        double allocationPercent = input.path("allocationPercent").asDouble(0);

        if (resourceId == null || resourceId.isBlank()) return ToolResult.failure("'resourceId' is required");
        if (ticketId == null || ticketId.isBlank()) return ToolResult.failure("'ticketId' is required");
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (allocationPercent <= 0 || allocationPercent > 100) return ToolResult.failure("'allocationPercent' must be between 1 and 100");

        // Verify resource exists
        String resourceName = "unknown";
        if (resourceRepository != null) {
            Optional<ResourceDocument> res = resourceRepository.findById(resourceId);
            if (res.isEmpty()) return ToolResult.failure("Resource not found: " + resourceId);
            resourceName = res.get().getName();
        }

        // Verify ticket exists
        String ticketTitle = "unknown";
        if (ticketRepository != null) {
            Optional<TicketDocument> ticket = ticketRepository.findById(ticketId);
            if (ticket.isEmpty()) return ToolResult.failure("Ticket not found: " + ticketId);
            ticketTitle = ticket.get().getTitle();
        }

        // Check for existing assignment
        Optional<ResourceAssignmentDocument> existing = resourceAssignmentRepository.findByResourceIdAndTicketId(resourceId, ticketId);
        if (existing.isPresent()) {
            return ToolResult.failure("Resource " + resourceId + " is already assigned to ticket " + ticketId);
        }

        ResourceAssignmentDocument doc = new ResourceAssignmentDocument();
        doc.setAssignmentId(UUID.randomUUID().toString());
        doc.setResourceId(resourceId);
        doc.setTicketId(ticketId);
        doc.setProjectId(projectId);
        doc.setPercentageAllocation(allocationPercent);
        resourceAssignmentRepository.save(doc);

        stream.progress(100, "Assigned " + resourceName + " to " + ticketTitle);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("assignmentId", doc.getAssignmentId());
        result.put("resourceName", resourceName);
        result.put("ticketTitle", ticketTitle);
        result.put("allocationPercent", allocationPercent);
        return ToolResult.success(result);
    }
}
