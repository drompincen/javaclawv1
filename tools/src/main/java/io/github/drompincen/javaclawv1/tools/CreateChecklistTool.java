package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.document.ChecklistTemplateDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistTemplateRepository;
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
    private ChecklistTemplateRepository checklistTemplateRepository;

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

    public void setChecklistRepository(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    public void setChecklistTemplateRepository(ChecklistTemplateRepository checklistTemplateRepository) {
        this.checklistTemplateRepository = checklistTemplateRepository;
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

        String templateId = input.path("templateId").asText(null);
        String phaseId = input.path("phaseId").asText(null);

        List<ChecklistDocument.ChecklistItem> items = new ArrayList<>();

        // If template provided, pre-populate items from template
        if (templateId != null && !templateId.isBlank() && checklistTemplateRepository != null) {
            ChecklistTemplateDocument template = checklistTemplateRepository.findById(templateId).orElse(null);
            if (template != null && template.getItems() != null) {
                for (ChecklistTemplateDocument.TemplateItem ti : template.getItems()) {
                    ChecklistDocument.ChecklistItem item = new ChecklistDocument.ChecklistItem();
                    item.setItemId(UUID.randomUUID().toString());
                    item.setText(ti.getText());
                    item.setChecked(false);
                    items.add(item);
                }
            }
        }

        // Add explicit items (appended after template items if any)
        JsonNode itemsNode = input.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
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
        }

        if (items.isEmpty()) {
            return ToolResult.failure("No items provided — either supply 'items' array or a valid 'templateId'");
        }

        ChecklistDocument doc = new ChecklistDocument();
        doc.setChecklistId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setTemplateId(templateId);
        doc.setPhaseId(phaseId);
        doc.setItems(items);
        doc.setStatus(ChecklistStatus.IN_PROGRESS);

        String sourceThreadId = input.path("sourceThreadId").asText(null);
        if (sourceThreadId != null && !sourceThreadId.isBlank()) {
            doc.setSourceThreadId(sourceThreadId);
        }

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
