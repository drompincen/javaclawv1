package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

public class UpdateObjectiveTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ObjectiveRepository objectiveRepository;

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

    public void setObjectiveRepository(ObjectiveRepository objectiveRepository) {
        this.objectiveRepository = objectiveRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (objectiveRepository == null) {
            return ToolResult.failure("Objective repository not available — ensure MongoDB is connected");
        }

        String objectiveId = input.path("objectiveId").asText(null);
        if (objectiveId == null || objectiveId.isBlank()) return ToolResult.failure("'objectiveId' is required");

        ObjectiveDocument obj = objectiveRepository.findById(objectiveId).orElse(null);
        if (obj == null) {
            return ToolResult.failure("Objective not found: " + objectiveId);
        }

        ArrayNode updatedFields = MAPPER.createArrayNode();

        String outcome = input.path("outcome").asText(null);
        if (outcome != null && !outcome.isBlank()) {
            obj.setOutcome(outcome);
            updatedFields.add("outcome");
        }

        String signal = input.path("measurableSignal").asText(null);
        if (signal != null && !signal.isBlank()) {
            obj.setMeasurableSignal(signal);
            updatedFields.add("measurableSignal");
        }

        if (input.has("risks") && input.get("risks").isArray()) {
            var newRisks = new ArrayList<String>();
            input.get("risks").forEach(r -> newRisks.add(r.asText()));
            obj.setRisks(newRisks);
            updatedFields.add("risks");
        }

        if (input.has("ticketIds") && input.get("ticketIds").isArray()) {
            var existing = obj.getTicketIds() != null ? new ArrayList<>(obj.getTicketIds()) : new ArrayList<String>();
            input.get("ticketIds").forEach(t -> {
                String tid = t.asText();
                if (!existing.contains(tid)) existing.add(tid);
            });
            obj.setTicketIds(existing);
            updatedFields.add("ticketIds");
        }

        if (input.has("coveragePercent")) {
            obj.setCoveragePercent(input.path("coveragePercent").asDouble());
            updatedFields.add("coveragePercent");
        }

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                obj.setStatus(ObjectiveStatus.valueOf(statusStr.toUpperCase()));
                updatedFields.add("status");
            } catch (IllegalArgumentException ignored) {}
        }

        obj.setUpdatedAt(Instant.now());
        objectiveRepository.save(obj);

        stream.progress(100, "Objective updated: " + objectiveId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("objectiveId", objectiveId);
        result.set("updatedFields", updatedFields);
        return ToolResult.success(result);
    }
}
