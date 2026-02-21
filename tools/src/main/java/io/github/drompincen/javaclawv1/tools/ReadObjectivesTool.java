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

public class ReadObjectivesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "read_objectives"; }
    @Override public String description() { return "Read all objectives for a project."; }

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

        List<ThingDocument> objectives = thingService.findByProjectAndCategory(projectId, ThingCategory.OBJECTIVE);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ThingDocument o : objectives) {
            Map<String, Object> p = o.getPayload();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("objectiveId", o.getId());
            n.put("outcome", (String) p.get("outcome"));
            n.put("status", p.get("status") != null ? p.get("status").toString() : "UNKNOWN");
            @SuppressWarnings("unchecked")
            List<String> ticketIds = (List<String>) p.get("ticketIds");
            n.put("ticketCount", ticketIds != null ? ticketIds.size() : 0);
            arr.add(n);
        }
        stream.progress(100, "Read " + objectives.size() + " objectives");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("objectives", arr);
        result.put("count", objectives.size());
        return ToolResult.success(result);
    }
}
