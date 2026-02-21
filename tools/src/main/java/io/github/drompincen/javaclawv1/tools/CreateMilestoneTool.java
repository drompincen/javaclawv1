package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateMilestoneTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        String targetDateStr = input.path("targetDate").asText(null);

        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");
        if (targetDateStr == null || targetDateStr.isBlank()) return ToolResult.failure("'targetDate' is required");

        // Dedup: skip if milestone with same name already exists for this project
        var existing = thingService.findByProjectCategoryAndNameIgnoreCase(projectId, ThingCategory.MILESTONE, name);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("milestoneId", existing.get().getId());
            result.put("name", name);
            result.put("status", "already_exists");
            return ToolResult.success(result);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", input.path("description").asText(null));
        payload.put("targetDate", Instant.parse(targetDateStr + (targetDateStr.contains("T") ? "" : "T00:00:00Z")).toString());
        payload.put("status", MilestoneStatus.UPCOMING.name());
        payload.put("phaseId", input.path("phaseId").asText(null));
        payload.put("owner", input.path("owner").asText(null));

        if (input.has("objectiveIds") && input.get("objectiveIds").isArray()) {
            List<String> ids = new ArrayList<>();
            input.get("objectiveIds").forEach(n -> ids.add(n.asText()));
            payload.put("objectiveIds", ids);
        } else {
            payload.put("objectiveIds", List.of());
        }

        payload.put("ticketIds", List.of());
        payload.put("dependencies", List.of());

        var thing = thingService.createThing(projectId, ThingCategory.MILESTONE, payload);
        stream.progress(100, "Milestone created: " + name);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("milestoneId", thing.getId());
        result.put("name", name);
        return ToolResult.success(result);
    }
}
