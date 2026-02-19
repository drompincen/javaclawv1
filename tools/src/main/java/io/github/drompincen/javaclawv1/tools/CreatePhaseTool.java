package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreatePhaseTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PhaseRepository phaseRepository;

    @Override public String name() { return "create_phase"; }

    @Override public String description() {
        return "Create a new project phase with entry/exit criteria. Phases define the lifecycle stages of a project.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("name").put("type", "string");
        props.putObject("description").put("type", "string");
        ObjectNode entry = props.putObject("entryCriteria");
        entry.put("type", "array"); entry.putObject("items").put("type", "string");
        ObjectNode exit = props.putObject("exitCriteria");
        exit.put("type", "array"); exit.putObject("items").put("type", "string");
        props.putObject("sortOrder").put("type", "integer");
        props.putObject("startDate").put("type", "string").put("description", "ISO date");
        props.putObject("endDate").put("type", "string").put("description", "ISO date");
        schema.putArray("required").add("projectId").add("name").add("sortOrder");
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

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");

        // Dedup: skip if phase with same name already exists for this project
        var existing = phaseRepository.findFirstByProjectIdAndNameIgnoreCase(projectId, name);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("phaseId", existing.get().getPhaseId());
            result.put("name", name);
            result.put("status", "already_exists");
            return ToolResult.success(result);
        }

        PhaseDocument doc = new PhaseDocument();
        doc.setPhaseId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setDescription(input.path("description").asText(null));
        doc.setSortOrder(input.path("sortOrder").asInt(1));
        doc.setStatus(PhaseStatus.PENDING);

        if (input.has("entryCriteria") && input.get("entryCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("entryCriteria").forEach(n -> criteria.add(n.asText()));
            doc.setEntryCriteria(criteria);
        }
        if (input.has("exitCriteria") && input.get("exitCriteria").isArray()) {
            List<String> criteria = new ArrayList<>();
            input.get("exitCriteria").forEach(n -> criteria.add(n.asText()));
            doc.setExitCriteria(criteria);
        }

        String startDate = input.path("startDate").asText(null);
        if (startDate != null) doc.setStartDate(Instant.parse(startDate + (startDate.contains("T") ? "" : "T00:00:00Z")));
        String endDate = input.path("endDate").asText(null);
        if (endDate != null) doc.setEndDate(Instant.parse(endDate + (endDate.contains("T") ? "" : "T00:00:00Z")));

        doc.setChecklistIds(List.of());
        doc.setObjectiveIds(List.of());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        phaseRepository.save(doc);
        stream.progress(100, "Phase created: " + name);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("phaseId", doc.getPhaseId());
        result.put("name", name);
        return ToolResult.success(result);
    }
}
