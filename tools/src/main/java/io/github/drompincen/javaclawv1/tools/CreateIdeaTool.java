package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateIdeaTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "create_idea"; }
    @Override public String description() { return "Create a new idea in the project"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("content").put("type", "string");
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

        // Dedup: skip if idea with same title already exists for this project
        List<ThingDocument> existing = thingService.findByProjectAndCategory(projectId, ThingCategory.IDEA);
        for (ThingDocument doc : existing) {
            String existingTitle = doc.payloadString("title");
            if (existingTitle != null && existingTitle.equalsIgnoreCase(title)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("ideaId", doc.getId());
                result.put("status", "already_exists");
                result.put("projectId", projectId);
                return ToolResult.success(result);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", input.path("content").asText(null));
        payload.put("status", IdeaDto.IdeaStatus.NEW.name());

        ThingDocument thing = thingService.createThing(projectId, ThingCategory.IDEA, payload);
        stream.progress(100, "Idea created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("ideaId", thing.getId());
        result.put("status", "created");
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
