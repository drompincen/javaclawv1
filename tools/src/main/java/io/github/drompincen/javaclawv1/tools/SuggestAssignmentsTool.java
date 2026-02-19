package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

public class SuggestAssignmentsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceRepository resourceRepository;
    private ResourceAssignmentRepository resourceAssignmentRepository;
    private TicketRepository ticketRepository;

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

    public void setResourceRepository(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }
    public void setResourceAssignmentRepository(ResourceAssignmentRepository resourceAssignmentRepository) {
        this.resourceAssignmentRepository = resourceAssignmentRepository;
    }
    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceRepository == null) return ToolResult.failure("Resource repository not available");
        if (resourceAssignmentRepository == null) return ToolResult.failure("Assignment repository not available");
        if (ticketRepository == null) return ToolResult.failure("Ticket repository not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String specificTicketId = input.path("ticketId").asText(null);

        // Load resources and compute current allocation
        List<ResourceDocument> resources = resourceRepository.findByProjectId(projectId);
        List<ResourceAssignmentDocument> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
        Map<String, Double> currentAllocation = allAssignments.stream()
                .collect(Collectors.groupingBy(ResourceAssignmentDocument::getResourceId,
                        Collectors.summingDouble(ResourceAssignmentDocument::getPercentageAllocation)));

        // Find unassigned tickets
        List<TicketDocument> tickets = ticketRepository.findByProjectId(projectId);
        Set<String> assignedTicketIds = allAssignments.stream()
                .map(ResourceAssignmentDocument::getTicketId)
                .collect(Collectors.toSet());

        List<TicketDocument> unassignedTickets;
        if (specificTicketId != null && !specificTicketId.isBlank()) {
            unassignedTickets = tickets.stream()
                    .filter(t -> t.getTicketId().equals(specificTicketId))
                    .collect(Collectors.toList());
        } else {
            unassignedTickets = tickets.stream()
                    .filter(t -> !assignedTicketIds.contains(t.getTicketId()))
                    .collect(Collectors.toList());
        }

        // Sort by priority (CRITICAL first)
        unassignedTickets.sort((a, b) -> {
            int pa = priorityScore(a);
            int pb = priorityScore(b);
            return Integer.compare(pb, pa);
        });

        ArrayNode suggestionsArr = MAPPER.createArrayNode();
        for (TicketDocument ticket : unassignedTickets) {
            ObjectNode suggestion = MAPPER.createObjectNode();
            suggestion.put("ticketId", ticket.getTicketId());
            suggestion.put("ticketTitle", ticket.getTitle());
            suggestion.put("ticketPriority", ticket.getPriority() != null ? ticket.getPriority().name() : "UNKNOWN");

            // Score each resource
            List<ScoredResource> scored = new ArrayList<>();
            for (ResourceDocument r : resources) {
                double allocated = currentAllocation.getOrDefault(r.getResourceId(), 0.0);
                double available = Math.max(0, r.getCapacity() - allocated);
                if (available <= 0) continue; // Skip overloaded

                double score = 0;
                // Capacity score: more available = higher score (max 50)
                score += Math.min(50, available * 0.5);
                // Role match score (max 20)
                if (r.getRole() != null && ticket.getTitle() != null) {
                    String titleLower = ticket.getTitle().toLowerCase();
                    if (r.getRole().name().equals("ENGINEER") && (titleLower.contains("implement") || titleLower.contains("build") || titleLower.contains("develop"))) {
                        score += 20;
                    } else if (r.getRole().name().equals("QA") && (titleLower.contains("test") || titleLower.contains("qa"))) {
                        score += 20;
                    }
                }
                // Skill match not possible here without ticket skills, but availability matters
                scored.add(new ScoredResource(r.getResourceId(), r.getName(), score, available));
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

    private int priorityScore(TicketDocument t) {
        if (t.getPriority() == null) return 0;
        return switch (t.getPriority()) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private record ScoredResource(String resourceId, String name, double score, double available) {}
}
