package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

public class CreateIdeaTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        return ToolResult.success(MAPPER.valueToTree("Idea creation delegated to runtime service"));
    }
}
