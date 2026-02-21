package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class AssignResourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");
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
        Optional<ThingDocument> res = thingService.findById(resourceId, ThingCategory.RESOURCE);
        if (res.isEmpty()) return ToolResult.failure("Resource not found: " + resourceId);
        resourceName = (String) res.get().getPayload().get("name");

        // Verify ticket exists
        String ticketTitle = "unknown";
        Optional<ThingDocument> ticket = thingService.findById(ticketId, ThingCategory.TICKET);
        if (ticket.isEmpty()) return ToolResult.failure("Ticket not found: " + ticketId);
        ticketTitle = (String) ticket.get().getPayload().get("title");

        // Check for existing assignment
        Optional<ThingDocument> existing = thingService.findOneByPayloadFields(
                ThingCategory.RESOURCE_ASSIGNMENT,
                Map.of("resourceId", resourceId, "ticketId", ticketId));
        if (existing.isPresent()) {
            return ToolResult.failure("Resource " + resourceId + " is already assigned to ticket " + ticketId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resourceId);
        payload.put("ticketId", ticketId);
        payload.put("percentageAllocation", allocationPercent);
        ThingDocument assignment = thingService.createThing(projectId, ThingCategory.RESOURCE_ASSIGNMENT, payload);

        stream.progress(100, "Assigned " + resourceName + " to " + ticketTitle);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("assignmentId", assignment.getId());
        result.put("resourceName", resourceName);
        result.put("ticketTitle", ticketTitle);
        result.put("allocationPercent", allocationPercent);
        return ToolResult.success(result);
    }
}
