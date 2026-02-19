package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class UnassignResourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceAssignmentRepository resourceAssignmentRepository;

    @Override public String name() { return "unassign_resource"; }
    @Override public String description() { return "Remove a resource assignment from a ticket."; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("resourceId").put("type", "string");
        props.putObject("ticketId").put("type", "string");
        schema.putArray("required").add("resourceId").add("ticketId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setResourceAssignmentRepository(ResourceAssignmentRepository resourceAssignmentRepository) {
        this.resourceAssignmentRepository = resourceAssignmentRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceAssignmentRepository == null) return ToolResult.failure("Assignment repository not available");
        String resourceId = input.path("resourceId").asText(null);
        String ticketId = input.path("ticketId").asText(null);

        if (resourceId == null || resourceId.isBlank()) return ToolResult.failure("'resourceId' is required");
        if (ticketId == null || ticketId.isBlank()) return ToolResult.failure("'ticketId' is required");

        Optional<ResourceAssignmentDocument> existing = resourceAssignmentRepository.findByResourceIdAndTicketId(resourceId, ticketId);
        if (existing.isEmpty()) {
            return ToolResult.failure("No assignment found for resource " + resourceId + " on ticket " + ticketId);
        }

        double freedCapacity = existing.get().getPercentageAllocation();
        resourceAssignmentRepository.delete(existing.get());

        stream.progress(100, "Unassigned resource " + resourceId + " from ticket " + ticketId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("success", true);
        result.put("freedCapacity", freedCapacity);
        return ToolResult.success(result);
    }
}
