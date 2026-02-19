package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MilestoneDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MilestoneRepository;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateMilestoneTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MilestoneRepository milestoneRepository;

    @Override public String name() { return "create_milestone"; }

    @Override public String description() {
        return "Create a project milestone with target date, linked phase, and objectives.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("name").put("type", "string");
        props.putObject("description").put("type", "string");
        props.putObject("targetDate").put("type", "string").put("description", "ISO date or datetime");
        props.putObject("phaseId").put("type", "string");
        ObjectNode objIds = props.putObject("objectiveIds");
        objIds.put("type", "array"); objIds.putObject("items").put("type", "string");
        props.putObject("owner").put("type", "string");
        schema.putArray("required").add("projectId").add("name").add("targetDate");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setMilestoneRepository(MilestoneRepository milestoneRepository) {
        this.milestoneRepository = milestoneRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (milestoneRepository == null) return ToolResult.failure("Milestone repository not available");

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        String targetDateStr = input.path("targetDate").asText(null);

        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");
        if (targetDateStr == null || targetDateStr.isBlank()) return ToolResult.failure("'targetDate' is required");

        // Dedup: skip if milestone with same name already exists for this project
        var existing = milestoneRepository.findFirstByProjectIdAndNameIgnoreCase(projectId, name);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("milestoneId", existing.get().getMilestoneId());
            result.put("name", name);
            result.put("status", "already_exists");
            return ToolResult.success(result);
        }

        MilestoneDocument doc = new MilestoneDocument();
        doc.setMilestoneId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setDescription(input.path("description").asText(null));
        doc.setTargetDate(Instant.parse(targetDateStr + (targetDateStr.contains("T") ? "" : "T00:00:00Z")));
        doc.setStatus(MilestoneStatus.UPCOMING);
        doc.setPhaseId(input.path("phaseId").asText(null));
        doc.setOwner(input.path("owner").asText(null));

        if (input.has("objectiveIds") && input.get("objectiveIds").isArray()) {
            List<String> ids = new ArrayList<>();
            input.get("objectiveIds").forEach(n -> ids.add(n.asText()));
            doc.setObjectiveIds(ids);
        } else {
            doc.setObjectiveIds(List.of());
        }

        doc.setTicketIds(List.of());
        doc.setDependencies(List.of());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        milestoneRepository.save(doc);
        stream.progress(100, "Milestone created: " + name);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("milestoneId", doc.getMilestoneId());
        result.put("name", name);
        return ToolResult.success(result);
    }
}
