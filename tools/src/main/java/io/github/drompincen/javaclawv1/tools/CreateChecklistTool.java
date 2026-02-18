package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateChecklistTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChecklistRepository checklistRepository;

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
        props.putObject("sourceThreadId").put("type", "string")
                .put("description", "Thread ID where this checklist was identified");
        schema.putArray("required").add("projectId").add("name").add("items");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }

    public void setChecklistRepository(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (checklistRepository == null) {
            return ToolResult.failure("Checklist repository not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");

        JsonNode itemsNode = input.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return ToolResult.failure("'items' must be a non-empty array");
        }

        List<ChecklistDocument.ChecklistItem> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            ChecklistDocument.ChecklistItem item = new ChecklistDocument.ChecklistItem();
            item.setItemId(UUID.randomUUID().toString());
            item.setText(itemNode.path("text").asText(""));
            String assignee = itemNode.path("assignee").asText(null);
            if (assignee != null && !assignee.isBlank()) {
                item.setAssignee(assignee);
            }
            item.setChecked(false);
            items.add(item);
        }

        ChecklistDocument doc = new ChecklistDocument();
        doc.setChecklistId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setItems(items);
        doc.setStatus(ChecklistStatus.IN_PROGRESS);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        checklistRepository.save(doc);
        stream.progress(100, "Checklist created: " + name + " (" + items.size() + " items)");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("checklistId", doc.getChecklistId());
        result.put("itemCount", items.size());
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
