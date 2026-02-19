package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class UpdatePhaseTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PhaseRepository phaseRepository;

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

    public void setPhaseRepository(PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (phaseRepository == null) return ToolResult.failure("Phase repository not available");

        String phaseId = input.path("phaseId").asText(null);
        if (phaseId == null || phaseId.isBlank()) return ToolResult.failure("'phaseId' is required");

        PhaseDocument doc = phaseRepository.findById(phaseId).orElse(null);
        if (doc == null) return ToolResult.failure("Phase not found: " + phaseId);

        ArrayNode updatedFields = MAPPER.createArrayNode();

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                doc.setStatus(PhaseStatus.valueOf(statusStr.toUpperCase()));
                updatedFields.add("status");
            } catch (IllegalArgumentException ignored) {}
        }

        if (input.has("entryCriteria") && input.get("entryCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("entryCriteria").forEach(n -> criteria.add(n.asText()));
            doc.setEntryCriteria(criteria);
            updatedFields.add("entryCriteria");
        }

        if (input.has("exitCriteria") && input.get("exitCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("exitCriteria").forEach(n -> criteria.add(n.asText()));
            doc.setExitCriteria(criteria);
            updatedFields.add("exitCriteria");
        }

        String startDate = input.path("startDate").asText(null);
        if (startDate != null) {
            doc.setStartDate(Instant.parse(startDate + (startDate.contains("T") ? "" : "T00:00:00Z")));
            updatedFields.add("startDate");
        }
        String endDate = input.path("endDate").asText(null);
        if (endDate != null) {
            doc.setEndDate(Instant.parse(endDate + (endDate.contains("T") ? "" : "T00:00:00Z")));
            updatedFields.add("endDate");
        }

        doc.setUpdatedAt(Instant.now());
        phaseRepository.save(doc);
        stream.progress(100, "Phase updated: " + phaseId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("phaseId", phaseId);
        result.set("updatedFields", updatedFields);
        return ToolResult.success(result);
    }
}
