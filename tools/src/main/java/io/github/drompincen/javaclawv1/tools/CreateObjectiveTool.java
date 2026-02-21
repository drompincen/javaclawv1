package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class CreateObjectiveTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ObjectiveRepository objectiveRepository;

    @Override public String name() { return "create_objective"; }

    @Override public String description() {
        return "Create a new sprint objective for a project. Maps title to outcome and description to measurableSignal.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("title").put("type", "string").put("description", "Objective title (stored as outcome)");
        props.putObject("description").put("type", "string").put("description", "Details (stored as measurableSignal)");
        props.putObject("week").put("type", "string").put("description", "Sprint/week label (stored as sprintName)");
        props.putObject("status").put("type", "string")
                .put("description", "PROPOSED, COMMITTED, ACHIEVED, MISSED, DROPPED");
        schema.putArray("required").add("projectId").add("title");
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

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        ObjectiveDocument doc = new ObjectiveDocument();
        doc.setObjectiveId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setOutcome(title);

        String description = input.path("description").asText(null);
        if (description != null && !description.isBlank()) {
            doc.setMeasurableSignal(description);
        }

        String week = input.path("week").asText(null);
        if (week != null && !week.isBlank()) {
            doc.setSprintName(week);
        }

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                doc.setStatus(ObjectiveStatus.valueOf(statusStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                doc.setStatus(ObjectiveStatus.PROPOSED);
            }
        } else {
            doc.setStatus(ObjectiveStatus.PROPOSED);
        }

        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        objectiveRepository.save(doc);

        stream.progress(100, "Objective created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("objectiveId", doc.getObjectiveId());
        result.put("outcome", title);
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
