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

public class ReadChecklistsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "read_checklists"; }
    @Override public String description() { return "Read all checklists for a project."; }

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

    @SuppressWarnings("unchecked")
    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ThingDocument> checklists = thingService.findByProjectAndCategory(projectId, ThingCategory.CHECKLIST);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ThingDocument c : checklists) {
            Map<String, Object> p = c.getPayload();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("checklistId", c.getId());
            n.put("name", (String) p.get("name"));
            n.put("status", p.get("status") != null ? p.get("status").toString() : "UNKNOWN");
            List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
            n.put("itemCount", items != null ? items.size() : 0);
            arr.add(n);
        }
        stream.progress(100, "Read " + checklists.size() + " checklists");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("checklists", arr);
        result.put("count", checklists.size());
        return ToolResult.success(result);
    }
}
