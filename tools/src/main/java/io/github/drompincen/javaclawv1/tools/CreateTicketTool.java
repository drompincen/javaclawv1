package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateTicketTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "create_ticket"; }
    @Override public String description() { return "Create a new ticket in the project management system"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("description").put("type", "string");
        props.putObject("priority").put("type", "string").put("description", "LOW, MEDIUM, HIGH, CRITICAL");
        props.putObject("owner").put("type", "string")
                .put("description", "Person who owns this ticket (e.g. from Jira Owner field)");
        props.putObject("storyPoints").put("type", "integer")
                .put("description", "Story point estimate for this ticket");
        props.putObject("sourceThreadId").put("type", "string")
                .put("description", "Thread ID where this ticket was identified");
        schema.putArray("required").add("projectId").add("title");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        // Dedup: skip if ticket with same title already exists for this project
        Optional<ThingDocument> existing = thingService.findByProjectCategoryAndTitleIgnoreCase(
                projectId, ThingCategory.TICKET, title);
        if (existing.isPresent()) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("ticketId", existing.get().getId());
            result.put("status", "already_exists");
            result.put("projectId", projectId);
            return ToolResult.success(result);
        }

        String priorityStr = input.path("priority").asText("MEDIUM");
        TicketDto.TicketPriority priority;
        try {
            priority = TicketDto.TicketPriority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            priority = TicketDto.TicketPriority.MEDIUM;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("description", input.path("description").asText(null));
        payload.put("status", TicketDto.TicketStatus.TODO.name());
        payload.put("priority", priority.name());
        String owner = input.path("owner").asText(null);
        if (owner != null && !owner.isBlank()) {
            payload.put("owner", owner);
        }
        int sp = input.path("storyPoints").asInt(0);
        if (sp > 0) {
            payload.put("storyPoints", sp);
        }

        String sourceThreadId = input.path("sourceThreadId").asText(null);
        if (sourceThreadId != null && !sourceThreadId.isBlank()) {
            payload.put("linkedThreadIds", List.of(sourceThreadId));
        }

        ThingDocument thing = thingService.createThing(projectId, ThingCategory.TICKET, payload);
        stream.progress(100, "Ticket created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("ticketId", thing.getId());
        result.put("status", "created");
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
