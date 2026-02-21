package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

public class ComputeCoverageTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "compute_coverage"; }

    @Override public String description() {
        return "Compute objective coverage for a project. Cross-references objectives with ticket statuses " +
               "and identifies unmapped tickets.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("sprintName").put("type", "string")
                .put("description", "Optional sprint filter");
        schema.putArray("required").add("projectId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String sprintName = input.path("sprintName").asText(null);

        List<ThingDocument> objectives = (sprintName != null && !sprintName.isBlank())
                ? thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.OBJECTIVE,
                        "sprintName", sprintName)
                : thingService.findByProjectAndCategory(projectId, ThingCategory.OBJECTIVE);

        List<ThingDocument> allTickets = thingService.findByProjectAndCategory(projectId, ThingCategory.TICKET);
        Map<String, ThingDocument> ticketMap = allTickets.stream()
                .collect(Collectors.toMap(ThingDocument::getId, t -> t, (a, b) -> a));

        // Track which tickets are mapped to objectives
        Set<String> mappedTicketIds = new HashSet<>();

        ArrayNode objectiveResults = MAPPER.createArrayNode();
        for (ThingDocument obj : objectives) {
            Map<String, Object> p = obj.getPayload();
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("objectiveId", obj.getId());
            entry.put("outcome", p.get("outcome") != null ? p.get("outcome").toString() : "");
            entry.put("status", p.get("status") != null ? p.get("status").toString() : "UNKNOWN");

            @SuppressWarnings("unchecked")
            List<String> tids = p.get("ticketIds") != null ? (List<String>) p.get("ticketIds") : List.of();
            int done = 0, inProgress = 0, open = 0;
            for (String tid : tids) {
                mappedTicketIds.add(tid);
                ThingDocument t = ticketMap.get(tid);
                if (t != null && t.getPayload().get("status") != null) {
                    switch (t.getPayload().get("status").toString()) {
                        case "DONE" -> done++;
                        case "IN_PROGRESS" -> inProgress++;
                        default -> open++;
                    }
                } else {
                    open++;
                }
            }

            entry.put("totalTickets", tids.size());
            entry.put("doneTickets", done);
            entry.put("inProgressTickets", inProgress);
            entry.put("openTickets", open);

            double coverage = tids.isEmpty() ? 0 : ((double)(done + inProgress) / tids.size()) * 100;
            entry.put("computedCoverage", Math.round(coverage * 10) / 10.0);

            objectiveResults.add(entry);
        }

        // Find unmapped tickets
        ArrayNode unmapped = MAPPER.createArrayNode();
        for (ThingDocument t : allTickets) {
            if (!mappedTicketIds.contains(t.getId())) {
                Map<String, Object> tp = t.getPayload();
                ObjectNode ut = MAPPER.createObjectNode();
                ut.put("ticketId", t.getId());
                ut.put("title", (String) tp.get("title"));
                ut.put("status", tp.get("status") != null ? tp.get("status").toString() : "UNKNOWN");
                unmapped.add(ut);
            }
        }

        stream.progress(100, "Coverage computed for " + objectives.size() + " objectives");

        ObjectNode result = MAPPER.createObjectNode();
        result.set("objectives", objectiveResults);
        result.set("unmappedTickets", unmapped);
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
