package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

public class ComputeCoverageTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ObjectiveRepository objectiveRepository;
    private TicketRepository ticketRepository;

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

    public void setObjectiveRepository(ObjectiveRepository objectiveRepository) {
        this.objectiveRepository = objectiveRepository;
    }

    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (objectiveRepository == null || ticketRepository == null) {
            return ToolResult.failure("Repositories not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        String sprintName = input.path("sprintName").asText(null);

        List<ObjectiveDocument> objectives = (sprintName != null && !sprintName.isBlank())
                ? objectiveRepository.findByProjectIdAndSprintName(projectId, sprintName)
                : objectiveRepository.findByProjectId(projectId);

        List<TicketDocument> allTickets = ticketRepository.findByProjectId(projectId);
        Map<String, TicketDocument> ticketMap = allTickets.stream()
                .collect(Collectors.toMap(TicketDocument::getTicketId, t -> t, (a, b) -> a));

        // Track which tickets are mapped to objectives
        Set<String> mappedTicketIds = new HashSet<>();

        ArrayNode objectiveResults = MAPPER.createArrayNode();
        for (ObjectiveDocument obj : objectives) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("objectiveId", obj.getObjectiveId());
            entry.put("outcome", obj.getOutcome() != null ? obj.getOutcome() : "");
            entry.put("status", obj.getStatus() != null ? obj.getStatus().name() : "UNKNOWN");

            List<String> tids = obj.getTicketIds() != null ? obj.getTicketIds() : List.of();
            int done = 0, inProgress = 0, open = 0;
            for (String tid : tids) {
                mappedTicketIds.add(tid);
                TicketDocument t = ticketMap.get(tid);
                if (t != null && t.getStatus() != null) {
                    switch (t.getStatus()) {
                        case DONE -> done++;
                        case IN_PROGRESS -> inProgress++;
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
        for (TicketDocument t : allTickets) {
            if (!mappedTicketIds.contains(t.getTicketId())) {
                ObjectNode ut = MAPPER.createObjectNode();
                ut.put("ticketId", t.getTicketId());
                ut.put("title", t.getTitle());
                ut.put("status", t.getStatus() != null ? t.getStatus().name() : "UNKNOWN");
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
