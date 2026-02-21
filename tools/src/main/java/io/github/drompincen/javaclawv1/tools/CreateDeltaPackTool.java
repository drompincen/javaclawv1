package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class CreateDeltaPackTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "create_delta_pack"; }

    @Override public String description() {
        return "Create a delta pack documenting all mismatches found during reconciliation. " +
               "Records deltas with type, severity, and suggested actions.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("projectName").put("type", "string");
        ObjectNode deltas = props.putObject("deltas");
        deltas.put("type", "array");
        ObjectNode deltaSchema = deltas.putObject("items");
        deltaSchema.put("type", "object");
        ObjectNode dp = deltaSchema.putObject("properties");
        dp.putObject("deltaType").put("type", "string");
        dp.putObject("severity").put("type", "string");
        dp.putObject("title").put("type", "string");
        dp.putObject("description").put("type", "string");
        dp.putObject("suggestedAction").put("type", "string");
        schema.putArray("required").add("projectId").add("deltas");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        JsonNode deltasNode = input.get("deltas");
        if (deltasNode == null || !deltasNode.isArray()) return ToolResult.failure("'deltas' must be an array");

        List<Map<String, Object>> deltas = new ArrayList<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();

        for (JsonNode dn : deltasNode) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("deltaType", dn.path("deltaType").asText("UNKNOWN"));
            d.put("severity", dn.path("severity").asText("MEDIUM"));
            d.put("title", dn.path("title").asText(""));
            d.put("description", dn.path("description").asText(""));
            d.put("sourceA", dn.path("sourceA").asText(null));
            d.put("sourceB", dn.path("sourceB").asText(null));
            d.put("fieldName", dn.path("fieldName").asText(null));
            d.put("valueA", dn.path("valueA").asText(null));
            d.put("valueB", dn.path("valueB").asText(null));
            d.put("suggestedAction", dn.path("suggestedAction").asText(null));
            d.put("autoResolvable", dn.path("autoResolvable").asBoolean(false));
            deltas.add(d);

            bySeverity.merge((String) d.get("severity"), 1, Integer::sum);
            byType.merge((String) d.get("deltaType"), 1, Integer::sum);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDeltas", deltas.size());
        summary.put("bySeverity", bySeverity);
        summary.put("byType", byType);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", input.path("projectName").asText(null));
        payload.put("deltas", deltas);
        payload.put("status", "FINAL");
        payload.put("summary", summary);

        var thing = thingService.createThing(projectId, ThingCategory.DELTA_PACK, payload);
        stream.progress(100, "Delta pack created: " + deltas.size() + " deltas");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("deltaPackId", thing.getId());
        result.put("totalDeltas", deltas.size());
        return ToolResult.success(result);
    }
}
