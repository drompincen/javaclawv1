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

public class UnassignResourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");
        String resourceId = input.path("resourceId").asText(null);
        String ticketId = input.path("ticketId").asText(null);

        if (resourceId == null || resourceId.isBlank()) return ToolResult.failure("'resourceId' is required");
        if (ticketId == null || ticketId.isBlank()) return ToolResult.failure("'ticketId' is required");

        Optional<ThingDocument> existing = thingService.findOneByPayloadFields(
                ThingCategory.RESOURCE_ASSIGNMENT,
                Map.of("resourceId", resourceId, "ticketId", ticketId));
        if (existing.isEmpty()) {
            return ToolResult.failure("No assignment found for resource " + resourceId + " on ticket " + ticketId);
        }

        Map<String, Object> p = existing.get().getPayload();
        double freedCapacity = p.get("percentageAllocation") != null
                ? ((Number) p.get("percentageAllocation")).doubleValue() : 0;
        thingService.deleteById(existing.get().getId());

        stream.progress(100, "Unassigned resource " + resourceId + " from ticket " + ticketId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("success", true);
        result.put("freedCapacity", freedCapacity);
        return ToolResult.success(result);
    }
}
