package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MilestoneDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MilestoneRepository;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class UpdateMilestoneTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MilestoneRepository milestoneRepository;

    @Override public String name() { return "update_milestone"; }

    @Override public String description() {
        return "Update a milestone: change status, target/actual dates, owner, or link tickets.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("milestoneId").put("type", "string");
        props.putObject("status").put("type", "string")
                .put("description", "UPCOMING, ON_TRACK, AT_RISK, MISSED, COMPLETED");
        props.putObject("targetDate").put("type", "string");
        props.putObject("actualDate").put("type", "string");
        props.putObject("owner").put("type", "string");
        ObjectNode tids = props.putObject("ticketIds");
        tids.put("type", "array"); tids.put("description", "Ticket IDs to append");
        tids.putObject("items").put("type", "string");
        schema.putArray("required").add("milestoneId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setMilestoneRepository(MilestoneRepository milestoneRepository) {
        this.milestoneRepository = milestoneRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (milestoneRepository == null) return ToolResult.failure("Milestone repository not available");

        String milestoneId = input.path("milestoneId").asText(null);
        if (milestoneId == null || milestoneId.isBlank()) return ToolResult.failure("'milestoneId' is required");

        MilestoneDocument doc = milestoneRepository.findById(milestoneId).orElse(null);
        if (doc == null) return ToolResult.failure("Milestone not found: " + milestoneId);

        ArrayNode updatedFields = MAPPER.createArrayNode();

        String statusStr = input.path("status").asText(null);
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                doc.setStatus(MilestoneStatus.valueOf(statusStr.toUpperCase()));
                updatedFields.add("status");
            } catch (IllegalArgumentException ignored) {}
        }

        String targetDate = input.path("targetDate").asText(null);
        if (targetDate != null) {
            doc.setTargetDate(Instant.parse(targetDate + (targetDate.contains("T") ? "" : "T00:00:00Z")));
            updatedFields.add("targetDate");
        }

        String actualDate = input.path("actualDate").asText(null);
        if (actualDate != null) {
            doc.setActualDate(Instant.parse(actualDate + (actualDate.contains("T") ? "" : "T00:00:00Z")));
            updatedFields.add("actualDate");
        }

        String owner = input.path("owner").asText(null);
        if (owner != null && !owner.isBlank()) {
            doc.setOwner(owner);
            updatedFields.add("owner");
        }

        if (input.has("ticketIds") && input.get("ticketIds").isArray()) {
            var existing = doc.getTicketIds() != null ? new ArrayList<>(doc.getTicketIds()) : new ArrayList<String>();
            input.get("ticketIds").forEach(t -> {
                String tid = t.asText();
                if (!existing.contains(tid)) existing.add(tid);
            });
            doc.setTicketIds(existing);
            updatedFields.add("ticketIds");
        }

        doc.setUpdatedAt(Instant.now());
        milestoneRepository.save(doc);
        stream.progress(100, "Milestone updated: " + milestoneId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("milestoneId", milestoneId);
        result.set("updatedFields", updatedFields);
        return ToolResult.success(result);
    }
}
