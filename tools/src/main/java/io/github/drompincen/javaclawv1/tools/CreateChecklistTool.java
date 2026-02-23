package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateChecklistTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "create_checklist"; }

    @Override public String description() {
        return "Create a new checklist for a project with named items. Each item can have text, " +
               "an assignee, and starts unchecked. Use for tracking TODOs, action items, or process steps.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("name").put("type", "string")
                .put("description", "Name/title of the checklist");
        ObjectNode items = props.putObject("items");
        items.put("type", "array");
        items.put("description", "List of checklist items");
        ObjectNode itemSchema = items.putObject("items");
        itemSchema.put("type", "object");
        ObjectNode itemProps = itemSchema.putObject("properties");
        itemProps.putObject("text").put("type", "string");
        itemProps.putObject("assignee").put("type", "string");
        props.putObject("templateId").put("type", "string")
                .put("description", "Optional template ID to pre-populate items from");
        props.putObject("phaseId").put("type", "string")
                .put("description", "Optional phase ID to link checklist to");
        props.putObject("sourceThreadId").put("type", "string")
                .put("description", "Thread ID where this checklist was identified");
        schema.putArray("required").add("projectId").add("name");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");

        // Dedup: skip if checklist with same name already exists for this project
        Optional<ThingDocument> existing = thingService.findByProjectCategoryAndNameIgnoreCase(
                projectId, ThingCategory.CHECKLIST, name);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("checklistId", existing.get().getId());
            result.put("status", "already_exists");
            result.put("projectId", projectId);
            return ToolResult.success(result);
        }

        String templateId = input.path("templateId").asText(null);
        String phaseId = input.path("phaseId").asText(null);

        List<Map<String, Object>> items = new ArrayList<>();

        // If template provided, pre-populate items from template
        if (templateId != null && !templateId.isBlank()) {
            var template = thingService.findById(templateId, ThingCategory.CHECKLIST_TEMPLATE).orElse(null);
            if (template != null) {
                List<Map<String, Object>> templateItems =
                        (List<Map<String, Object>>) template.getPayload().get("items");
                if (templateItems != null) {
                    for (Map<String, Object> ti : templateItems) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("itemId", UUID.randomUUID().toString());
                        item.put("text", ti.get("text"));
                        item.put("checked", false);
                        items.add(item);
                    }
                }
            }
        }

        // Add explicit items (appended after template items if any)
        JsonNode itemsNode = input.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("itemId", UUID.randomUUID().toString());
                item.put("text", itemNode.path("text").asText(""));
                String assignee = itemNode.path("assignee").asText(null);
                if (assignee != null && !assignee.isBlank()) {
                    item.put("assignee", assignee);
                }
                item.put("checked", false);
                items.add(item);
            }
        }

        if (items.isEmpty()) {
            return ToolResult.failure("No items provided — either supply 'items' array or a valid 'templateId'");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("templateId", templateId);
        payload.put("phaseId", phaseId);
        payload.put("items", items);
        payload.put("status", ChecklistStatus.IN_PROGRESS.name());

        String sourceThreadId = input.path("sourceThreadId").asText(null);
        if (sourceThreadId != null && !sourceThreadId.isBlank()) {
            payload.put("sourceThreadId", sourceThreadId);
        }

        var thing = thingService.createThing(projectId, ThingCategory.CHECKLIST, payload);
        stream.progress(100, "Checklist created: " + name + " (" + items.size() + " items)");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("checklistId", thing.getId());
        result.put("itemCount", items.size());
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
