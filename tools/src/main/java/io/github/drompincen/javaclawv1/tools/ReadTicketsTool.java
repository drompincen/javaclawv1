package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ReadTicketsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TicketRepository ticketRepository;

    @Override public String name() { return "read_tickets"; }
    @Override public String description() { return "Read all tickets for a project."; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        schema.putArray("required").add("projectId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (ticketRepository == null) return ToolResult.failure("Ticket repository not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<TicketDocument> tickets = ticketRepository.findByProjectId(projectId);
        ArrayNode arr = MAPPER.createArrayNode();
        for (TicketDocument t : tickets) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("ticketId", t.getTicketId());
            n.put("title", t.getTitle());
            n.put("status", t.getStatus() != null ? t.getStatus().name() : "UNKNOWN");
            n.put("assignee", t.getAssignedResourceId());
            arr.add(n);
        }
        stream.progress(100, "Read " + tickets.size() + " tickets");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tickets", arr);
        result.put("count", tickets.size());
        return ToolResult.success(result);
    }
}
