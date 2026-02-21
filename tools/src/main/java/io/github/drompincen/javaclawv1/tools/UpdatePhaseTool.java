package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class UpdatePhaseTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "update_phase"; }

    @Override public String description() {
        return "Update a project phase: change status, modify entry/exit criteria, update dates.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("phaseId").put("type", "string");
        props.putObject("status").put("type", "string")
                .put("description", "PENDING, NOT_STARTED, ACTIVE, IN_PROGRESS, COMPLETED, BLOCKED");
        ObjectNode entry = props.putObject("entryCriteria");
        entry.put("type", "array"); entry.putObject("items").put("type", "string");
        ObjectNode exit = props.putObject("exitCriteria");
        exit.put("type", "array"); exit.putObject("items").put("type", "string");
        props.putObject("startDate").put("type", "string");
        props.putObject("endDate").put("type", "string");
        schema.putArray("required").add("phaseId");
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

        String phaseId = input.path("phaseId").asText(null);
        if (phaseId == null || phaseId.isBlank()) return ToolResult.failure("'phaseId' is required");

        var doc = thingService.findById(phaseId, ThingCategory.PHASE).orElse(null);
        if (doc == null) return ToolResult.failure("Phase not found: " + phaseId);

        Map<String, Object> updates = new LinkedHashMap<>();
        ArrayNode updatedFields = MAPPER.createArrayNode();

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                updates.put("status", PhaseStatus.valueOf(statusStr.toUpperCase()).name());
                updatedFields.add("status");
            } catch (IllegalArgumentException ignored) {}
        }

        if (input.has("entryCriteria") && input.get("entryCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("entryCriteria").forEach(n -> criteria.add(n.asText()));
            updates.put("entryCriteria", criteria);
            updatedFields.add("entryCriteria");
        }

        if (input.has("exitCriteria") && input.get("exitCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("exitCriteria").forEach(n -> criteria.add(n.asText()));
            updates.put("exitCriteria", criteria);
            updatedFields.add("exitCriteria");
        }

        String startDate = input.path("startDate").asText(null);
        if (startDate != null) {
            updates.put("startDate", Instant.parse(startDate + (startDate.contains("T") ? "" : "T00:00:00Z")).toString());
            updatedFields.add("startDate");
        }
        String endDate = input.path("endDate").asText(null);
        if (endDate != null) {
            updates.put("endDate", Instant.parse(endDate + (endDate.contains("T") ? "" : "T00:00:00Z")).toString());
            updatedFields.add("endDate");
        }

        thingService.mergePayload(doc, updates);
        stream.progress(100, "Phase updated: " + phaseId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("phaseId", phaseId);
        result.set("updatedFields", updatedFields);
        return ToolResult.success(result);
    }
}
