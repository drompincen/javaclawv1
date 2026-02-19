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

public class UpdateChecklistTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChecklistRepository checklistRepository;

    @Override public String name() { return "update_checklist"; }

    @Override public String description() {
        return "Update a checklist: check/uncheck items, assign items, add/remove items, update notes. " +
               "Auto-transitions status based on item completion.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("checklistId").put("type", "string");
        ObjectNode ops = props.putObject("operations");
        ops.put("type", "array");
        ops.put("description", "List of operations: check, uncheck, assign, add, remove, update_notes");
        ObjectNode opSchema = ops.putObject("items");
        opSchema.put("type", "object");
        ObjectNode opProps = opSchema.putObject("properties");
        opProps.putObject("action").put("type", "string")
                .put("description", "check, uncheck, assign, add, remove, update_notes");
        opProps.putObject("itemIndex").put("type", "integer")
                .put("description", "0-based index of the item (not needed for 'add')");
        opProps.putObject("value").put("type", "string")
                .put("description", "Assignee name (for assign), text (for add), notes (for update_notes)");
        schema.putArray("required").add("checklistId").add("operations");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setChecklistRepository(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (checklistRepository == null) {
            return ToolResult.failure("Checklist repository not available");
        }

        String checklistId = input.path("checklistId").asText(null);
        if (checklistId == null || checklistId.isBlank()) return ToolResult.failure("'checklistId' is required");

        ChecklistDocument doc = checklistRepository.findById(checklistId).orElse(null);
        if (doc == null) return ToolResult.failure("Checklist not found: " + checklistId);

        JsonNode opsNode = input.get("operations");
        if (opsNode == null || !opsNode.isArray()) return ToolResult.failure("'operations' must be an array");

        List<ChecklistDocument.ChecklistItem> items = doc.getItems() != null
                ? new ArrayList<>(doc.getItems()) : new ArrayList<>();

        int applied = 0;
        for (JsonNode op : opsNode) {
            String action = op.path("action").asText("");
            int idx = op.path("itemIndex").asInt(-1);
            String value = op.path("value").asText(null);

            switch (action) {
                case "check" -> {
                    if (idx >= 0 && idx < items.size()) { items.get(idx).setChecked(true); applied++; }
                }
                case "uncheck" -> {
                    if (idx >= 0 && idx < items.size()) { items.get(idx).setChecked(false); applied++; }
                }
                case "assign" -> {
                    if (idx >= 0 && idx < items.size() && value != null) { items.get(idx).setAssignee(value); applied++; }
                }
                case "add" -> {
                    if (value != null && !value.isBlank()) {
                        ChecklistDocument.ChecklistItem item = new ChecklistDocument.ChecklistItem();
                        item.setItemId(UUID.randomUUID().toString());
                        item.setText(value);
                        item.setChecked(false);
                        items.add(item);
                        applied++;
                    }
                }
                case "remove" -> {
                    if (idx >= 0 && idx < items.size()) { items.remove(idx); applied++; }
                }
                case "update_notes" -> {
                    if (idx >= 0 && idx < items.size() && value != null) { items.get(idx).setNotes(value); applied++; }
                }
            }
        }

        doc.setItems(items);

        // Auto-transition status
        if (!items.isEmpty()) {
            boolean allChecked = items.stream().allMatch(ChecklistDocument.ChecklistItem::isChecked);
            if (allChecked) {
                doc.setStatus(ChecklistStatus.COMPLETED);
            } else {
                doc.setStatus(ChecklistStatus.IN_PROGRESS);
            }
        }

        doc.setUpdatedAt(Instant.now());
        checklistRepository.save(doc);

        long complete = items.stream().filter(ChecklistDocument.ChecklistItem::isChecked).count();
        stream.progress(100, "Checklist updated: " + applied + " operations applied");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("checklistId", checklistId);
        result.put("operationsApplied", applied);
        result.put("itemsComplete", complete);
        result.put("itemsTotal", items.size());
        result.put("status", doc.getStatus().name());
        return ToolResult.success(result);
    }
}
