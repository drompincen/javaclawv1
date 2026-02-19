package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ChecklistProgressTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChecklistRepository checklistRepository;

    @Override public String name() { return "checklist_progress"; }

    @Override public String description() {
        return "Report progress on checklists for a project: completion percentage, " +
               "overdue items, unassigned items. Optionally filter by phaseId or status.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("phaseId").put("type", "string").put("description", "Optional phase filter");
        props.putObject("status").put("type", "string").put("description", "Optional status filter: PENDING, IN_PROGRESS, COMPLETED");
        schema.putArray("required").add("projectId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    public void setChecklistRepository(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (checklistRepository == null) {
            return ToolResult.failure("Checklist repository not available");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String statusStr = input.path("status").asText(null);
        String phaseId = input.path("phaseId").asText(null);

        List<ChecklistDocument> checklists;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                ChecklistStatus status = ChecklistStatus.valueOf(statusStr.toUpperCase());
                checklists = checklistRepository.findByProjectIdAndStatus(projectId, status);
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid status: " + statusStr);
            }
        } else {
            checklists = checklistRepository.findByProjectId(projectId);
        }

        if (phaseId != null && !phaseId.isBlank()) {
            checklists = checklists.stream()
                    .filter(c -> phaseId.equals(c.getPhaseId()))
                    .toList();
        }

        ArrayNode results = MAPPER.createArrayNode();
        for (ChecklistDocument cl : checklists) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("checklistId", cl.getChecklistId());
            entry.put("title", cl.getName());
            entry.put("status", cl.getStatus() != null ? cl.getStatus().name() : "UNKNOWN");

            List<ChecklistDocument.ChecklistItem> items = cl.getItems() != null ? cl.getItems() : List.of();
            long complete = items.stream().filter(ChecklistDocument.ChecklistItem::isChecked).count();
            int total = items.size();
            double percentDone = total == 0 ? 0 : ((double) complete / total) * 100;

            entry.put("complete", complete);
            entry.put("total", total);
            entry.put("percentDone", Math.round(percentDone * 10) / 10.0);

            long unassigned = items.stream()
                    .filter(i -> !i.isChecked() && (i.getAssignee() == null || i.getAssignee().isBlank()))
                    .count();
            entry.put("unassignedItems", unassigned);

            results.add(entry);
        }

        stream.progress(100, "Progress computed for " + checklists.size() + " checklists");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("projectId", projectId);
        result.set("checklists", results);
        result.put("totalChecklists", checklists.size());
        return ToolResult.success(result);
    }
}
