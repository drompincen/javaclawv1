package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ChecklistCategory;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateChecklistTemplateTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "create_checklist_template"; }

    @Override public String description() {
        return "Create a reusable checklist template (ORR, release readiness, onboarding, etc.) " +
               "with predefined items and default assignee roles.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string");
        props.putObject("description").put("type", "string");
        props.putObject("category").put("type", "string")
                .put("description", "ORR, RELEASE_READINESS, ONBOARDING, SPRINT_CLOSE, DEPLOYMENT, ROLLBACK, CUSTOM");
        ObjectNode items = props.putObject("items");
        items.put("type", "array");
        ObjectNode itemSchema = items.putObject("items");
        itemSchema.put("type", "object");
        ObjectNode itemProps = itemSchema.putObject("properties");
        itemProps.putObject("text").put("type", "string");
        itemProps.putObject("defaultAssigneeRole").put("type", "string");
        itemProps.putObject("required").put("type", "boolean");
        props.putObject("projectId").put("type", "string")
                .put("description", "Optional project scope; null = global template");
        schema.putArray("required").add("name").add("category").add("items");
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
            return ToolResult.failure("ThingService not available");
        }

        String name = input.path("name").asText(null);
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");

        String categoryStr = input.path("category").asText(null);
        if (categoryStr == null || categoryStr.isBlank()) return ToolResult.failure("'category' is required");

        ChecklistCategory category;
        try {
            category = ChecklistCategory.valueOf(categoryStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid category: " + categoryStr);
        }

        JsonNode itemsNode = input.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return ToolResult.failure("'items' must be a non-empty array");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int sortOrder = 1;
        for (JsonNode itemNode : itemsNode) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", itemNode.path("text").asText(""));
            item.put("defaultAssigneeRole", itemNode.path("defaultAssigneeRole").asText(null));
            item.put("required", itemNode.path("required").asBoolean(false));
            item.put("sortOrder", sortOrder++);
            items.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", input.path("description").asText(null));
        payload.put("category", category.name());
        payload.put("items", items);

        String projectId = input.path("projectId").asText(null);
        var thing = thingService.createThing(projectId, ThingCategory.CHECKLIST_TEMPLATE, payload);
        stream.progress(100, "Template created: " + name + " (" + items.size() + " items)");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("templateId", thing.getId());
        result.put("name", name);
        result.put("itemCount", items.size());
        return ToolResult.success(result);
    }
}
