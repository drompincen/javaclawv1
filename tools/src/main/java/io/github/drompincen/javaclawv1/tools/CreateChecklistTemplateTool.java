package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistTemplateDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistTemplateRepository;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateChecklistTemplateTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChecklistTemplateRepository checklistTemplateRepository;

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

    public void setChecklistTemplateRepository(ChecklistTemplateRepository checklistTemplateRepository) {
        this.checklistTemplateRepository = checklistTemplateRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (checklistTemplateRepository == null) {
            return ToolResult.failure("ChecklistTemplate repository not available");
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

        List<ChecklistTemplateDocument.TemplateItem> items = new ArrayList<>();
        int sortOrder = 1;
        for (JsonNode itemNode : itemsNode) {
            ChecklistTemplateDocument.TemplateItem item = new ChecklistTemplateDocument.TemplateItem();
            item.setText(itemNode.path("text").asText(""));
            item.setDefaultAssigneeRole(itemNode.path("defaultAssigneeRole").asText(null));
            item.setRequired(itemNode.path("required").asBoolean(false));
            item.setSortOrder(sortOrder++);
            items.add(item);
        }

        ChecklistTemplateDocument doc = new ChecklistTemplateDocument();
        doc.setTemplateId(UUID.randomUUID().toString());
        doc.setName(name);
        doc.setDescription(input.path("description").asText(null));
        doc.setCategory(category);
        doc.setItems(items);
        doc.setProjectId(input.path("projectId").asText(null));
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        checklistTemplateRepository.save(doc);
        stream.progress(100, "Template created: " + name + " (" + items.size() + " items)");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("templateId", doc.getTemplateId());
        result.put("name", name);
        result.put("itemCount", items.size());
        return ToolResult.success(result);
    }
}
