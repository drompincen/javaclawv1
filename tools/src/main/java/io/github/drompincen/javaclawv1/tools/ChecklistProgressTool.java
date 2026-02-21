package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ChecklistProgressTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String statusStr = input.path("status").asText(null);
        String phaseId = input.path("phaseId").asText(null);

        List<ThingDocument> checklists;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                ChecklistStatus status = ChecklistStatus.valueOf(statusStr.toUpperCase());
                checklists = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.CHECKLIST,
                        "status", status.name());
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid status: " + statusStr);
            }
        } else {
            checklists = thingService.findByProjectAndCategory(projectId, ThingCategory.CHECKLIST);
        }

        if (phaseId != null && !phaseId.isBlank()) {
            checklists = checklists.stream()
                    .filter(c -> phaseId.equals(c.getPayload().get("phaseId")))
                    .toList();
        }

        ArrayNode results = MAPPER.createArrayNode();
        for (ThingDocument cl : checklists) {
            Map<String, Object> p = cl.getPayload();
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("checklistId", cl.getId());
            entry.put("title", (String) p.get("name"));
            entry.put("status", p.get("status") != null ? p.get("status").toString() : "UNKNOWN");

            List<Map<String, Object>> items = p.get("items") != null
                    ? (List<Map<String, Object>>) p.get("items") : List.of();
            long complete = items.stream().filter(i -> Boolean.TRUE.equals(i.get("checked"))).count();
            int total = items.size();
            double percentDone = total == 0 ? 0 : ((double) complete / total) * 100;

            entry.put("complete", complete);
            entry.put("total", total);
            entry.put("percentDone", Math.round(percentDone * 10) / 10.0);

            long unassigned = items.stream()
                    .filter(i -> !Boolean.TRUE.equals(i.get("checked"))
                            && (i.get("assignee") == null || i.get("assignee").toString().isBlank()))
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
