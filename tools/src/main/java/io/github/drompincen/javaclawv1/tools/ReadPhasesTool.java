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

public class ReadPhasesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "read_phases"; }
    @Override public String description() { return "Read all phases for a project, ordered by sortOrder."; }

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

        List<ThingDocument> phases = thingService.findByProjectAndCategorySorted(
                projectId, ThingCategory.PHASE, "payload.sortOrder", true);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ThingDocument p : phases) {
            Map<String, Object> payload = p.getPayload();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("phaseId", p.getId());
            n.put("name", (String) payload.get("name"));
            n.put("status", payload.get("status") != null ? payload.get("status").toString() : "UNKNOWN");
            n.put("sortOrder", payload.get("sortOrder") != null ? ((Number) payload.get("sortOrder")).intValue() : 0);
            arr.add(n);
        }
        stream.progress(100, "Read " + phases.size() + " phases");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("phases", arr);
        result.put("count", phases.size());
        return ToolResult.success(result);
    }
}
