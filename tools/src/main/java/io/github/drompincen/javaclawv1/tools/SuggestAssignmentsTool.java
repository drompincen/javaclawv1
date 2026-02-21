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

public class SuggestAssignmentsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "suggest_assignments"; }
    @Override public String description() { return "Suggest resource assignments for unassigned tickets based on skills, capacity, and priority."; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("ticketId").put("type", "string").put("description", "Optional: suggest for specific ticket only");
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
        if (thingService == null) return ToolResult.failure("ThingService not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String specificTicketId = input.path("ticketId").asText(null);

        // Load resources and compute current allocation
        List<ThingDocument> resources = thingService.findByProjectAndCategory(projectId, ThingCategory.RESOURCE);
        List<ThingDocument> allAssignments = thingService.findByProjectAndCategory(projectId, ThingCategory.RESOURCE_ASSIGNMENT);
        Map<String, Double> currentAllocation = allAssignments.stream()
                .collect(Collectors.groupingBy(
                        a -> (String) a.getPayload().get("resourceId"),
                        Collectors.summingDouble(a -> {
                            Object alloc = a.getPayload().get("percentageAllocation");
                            return alloc != null ? ((Number) alloc).doubleValue() : 0;
                        })));

        // Find unassigned tickets
        List<ThingDocument> tickets = thingService.findByProjectAndCategory(projectId, ThingCategory.TICKET);
        Set<String> assignedTicketIds = allAssignments.stream()
                .map(a -> (String) a.getPayload().get("ticketId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<ThingDocument> unassignedTickets;
        if (specificTicketId != null && !specificTicketId.isBlank()) {
            unassignedTickets = tickets.stream()
                    .filter(t -> t.getId().equals(specificTicketId))
                    .collect(Collectors.toList());
        } else {
            unassignedTickets = tickets.stream()
                    .filter(t -> !assignedTicketIds.contains(t.getId()))
                    .collect(Collectors.toList());
        }

        // Sort by priority (CRITICAL first)
        unassignedTickets.sort((a, b) -> Integer.compare(priorityScore(b), priorityScore(a)));

        ArrayNode suggestionsArr = MAPPER.createArrayNode();
        for (ThingDocument ticket : unassignedTickets) {
            Map<String, Object> tp = ticket.getPayload();
            ObjectNode suggestion = MAPPER.createObjectNode();
            suggestion.put("ticketId", ticket.getId());
            suggestion.put("ticketTitle", (String) tp.get("title"));
            suggestion.put("ticketPriority", tp.get("priority") != null ? tp.get("priority").toString() : "UNKNOWN");

            List<ScoredResource> scored = new ArrayList<>();
            for (ThingDocument r : resources) {
                Map<String, Object> p = r.getPayload();
                int capacity = p.get("capacity") != null ? ((Number) p.get("capacity")).intValue() : 0;
                double allocated = currentAllocation.getOrDefault(r.getId(), 0.0);
                double available = Math.max(0, capacity - allocated);
                if (available <= 0) continue;

                double score = 0;
                score += Math.min(50, available * 0.5);
                String role = p.get("role") != null ? p.get("role").toString() : "";
                String title = tp.get("title") != null ? tp.get("title").toString() : "";
                String titleLower = title.toLowerCase();
                if (role.equals("ENGINEER") && (titleLower.contains("implement") || titleLower.contains("build") || titleLower.contains("develop"))) {
                    score += 20;
                } else if (role.equals("QA") && (titleLower.contains("test") || titleLower.contains("qa"))) {
                    score += 20;
                }
                scored.add(new ScoredResource(r.getId(), (String) p.get("name"), score, available));
            }

            scored.sort((a, b) -> Double.compare(b.score, a.score));

            if (!scored.isEmpty()) {
                ScoredResource best = scored.get(0);
                ObjectNode suggested = suggestion.putObject("suggestedResource");
                suggested.put("resourceId", best.resourceId);
                suggested.put("name", best.name);
                suggested.put("matchScore", Math.round(best.score));
                suggested.put("availableCapacity", best.available);

                ArrayNode alternates = suggestion.putArray("alternateResources");
                for (int i = 1; i < Math.min(3, scored.size()); i++) {
                    ScoredResource alt = scored.get(i);
                    ObjectNode altNode = MAPPER.createObjectNode();
                    altNode.put("resourceId", alt.resourceId);
                    altNode.put("name", alt.name);
                    altNode.put("matchScore", Math.round(alt.score));
                    alternates.add(altNode);
                }
            }

            suggestionsArr.add(suggestion);
        }

        stream.progress(100, "Generated " + suggestionsArr.size() + " assignment suggestions");

        ObjectNode result = MAPPER.createObjectNode();
        result.set("suggestions", suggestionsArr);
        result.put("count", suggestionsArr.size());
        return ToolResult.success(result);
    }

    private int priorityScore(ThingDocument t) {
        Object priority = t.getPayload().get("priority");
        if (priority == null) return 0;
        return switch (priority.toString()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private record ScoredResource(String resourceId, String name, double score, double available) {}
}
