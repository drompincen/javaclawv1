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

public class ReadResourcesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "read_resources"; }
    @Override public String description() { return "Read all resources for a project."; }

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ThingDocument> resources = thingService.findByProjectAndCategory(projectId, ThingCategory.RESOURCE);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ThingDocument r : resources) {
            Map<String, Object> p = r.getPayload();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("resourceId", r.getId());
            n.put("name", (String) p.get("name"));
            n.put("role", p.get("role") != null ? p.get("role").toString() : "UNKNOWN");
            n.put("capacity", p.get("capacity") != null ? ((Number) p.get("capacity")).intValue() : 0);
            n.put("availability", p.get("availability") != null ? ((Number) p.get("availability")).doubleValue() : 1.0);
            ArrayNode skillsArr = n.putArray("skills");
            @SuppressWarnings("unchecked")
            List<String> skills = (List<String>) p.get("skills");
            if (skills != null) skills.forEach(skillsArr::add);
            arr.add(n);
        }
        stream.progress(100, "Read " + resources.size() + " resources");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("resources", arr);
        result.put("count", resources.size());
        return ToolResult.success(result);
    }
}
