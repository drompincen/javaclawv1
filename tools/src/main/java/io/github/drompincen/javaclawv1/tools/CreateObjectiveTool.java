package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateObjectiveTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        // Dedup: skip if objective with same outcome already exists for this project
        Optional<ThingDocument> existing = thingService.findByProjectCategoryAndPayloadFieldIgnoreCase(
                projectId, ThingCategory.OBJECTIVE, "outcome", title);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("objectiveId", existing.get().getId());
            result.put("status", "already_exists");
            result.put("projectId", projectId);
            return ToolResult.success(result);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", title);

        String description = input.path("description").asText(null);
        if (description != null && !description.isBlank()) {
            payload.put("measurableSignal", description);
        }

        String week = input.path("week").asText(null);
        if (week != null && !week.isBlank()) {
            payload.put("sprintName", week);
        }

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                payload.put("status", ObjectiveStatus.valueOf(statusStr.toUpperCase()).name());
            } catch (IllegalArgumentException e) {
                payload.put("status", ObjectiveStatus.PROPOSED.name());
            }
        } else {
            payload.put("status", ObjectiveStatus.PROPOSED.name());
        }

        ThingDocument thing = thingService.createThing(projectId, ThingCategory.OBJECTIVE, payload);

        stream.progress(100, "Objective created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("objectiveId", thing.getId());
        result.put("outcome", title);
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
