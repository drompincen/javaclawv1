package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class UpdateObjectiveTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "update_objective"; }

    @Override public String description() {
        return "Update an existing objective's fields: outcome, measurableSignal, risks, ticketIds, coverage, status.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("objectiveId").put("type", "string");
        props.putObject("outcome").put("type", "string");
        props.putObject("measurableSignal").put("type", "string");
        ObjectNode risks = props.putObject("risks");
        risks.put("type", "array");
        risks.putObject("items").put("type", "string");
        ObjectNode ticketIds = props.putObject("ticketIds");
        ticketIds.put("type", "array");
        ticketIds.put("description", "Ticket IDs to append to existing list");
        ticketIds.putObject("items").put("type", "string");
        props.putObject("coveragePercent").put("type", "number");
        props.putObject("status").put("type", "string")
                .put("description", "NOT_STARTED, IN_PROGRESS, COMPLETED, AT_RISK, BLOCKED");
        schema.putArray("required").add("objectiveId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available — ensure MongoDB is connected");
        }

        String objectiveId = input.path("objectiveId").asText(null);
        if (objectiveId == null || objectiveId.isBlank()) return ToolResult.failure("'objectiveId' is required");

        ThingDocument obj = thingService.findById(objectiveId, ThingCategory.OBJECTIVE).orElse(null);
        if (obj == null) {
            return ToolResult.failure("Objective not found: " + objectiveId);
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        ArrayNode updatedFields = MAPPER.createArrayNode();

        String outcome = input.path("outcome").asText(null);
        if (outcome != null && !outcome.isBlank()) {
            updates.put("outcome", outcome);
            updatedFields.add("outcome");
        }

        String signal = input.path("measurableSignal").asText(null);
        if (signal != null && !signal.isBlank()) {
            updates.put("measurableSignal", signal);
            updatedFields.add("measurableSignal");
        }

        if (input.has("risks") && input.get("risks").isArray()) {
            var newRisks = new ArrayList<String>();
            input.get("risks").forEach(r -> newRisks.add(r.asText()));
            updates.put("risks", newRisks);
            updatedFields.add("risks");
        }

        if (input.has("ticketIds") && input.get("ticketIds").isArray()) {
            @SuppressWarnings("unchecked")
            List<String> existing = obj.getPayload().get("ticketIds") != null
                    ? new ArrayList<>((List<String>) obj.getPayload().get("ticketIds"))
                    : new ArrayList<>();
            input.get("ticketIds").forEach(t -> {
                String tid = t.asText();
                if (!existing.contains(tid)) existing.add(tid);
            });
            updates.put("ticketIds", existing);
            updatedFields.add("ticketIds");
        }

        if (input.has("coveragePercent")) {
            updates.put("coveragePercent", input.path("coveragePercent").asDouble());
            updatedFields.add("coveragePercent");
        }

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                updates.put("status", ObjectiveStatus.valueOf(statusStr.toUpperCase()).name());
                updatedFields.add("status");
            } catch (IllegalArgumentException ignored) {}
        }

        thingService.mergePayload(obj, updates);

        stream.progress(100, "Objective updated: " + objectiveId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("objectiveId", objectiveId);
        result.set("updatedFields", updatedFields);
        return ToolResult.success(result);
    }
}
