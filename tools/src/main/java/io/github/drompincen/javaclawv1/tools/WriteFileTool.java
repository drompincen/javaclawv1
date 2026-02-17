package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class WriteFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "write_file"; }
    @Override public String description() { return "Write content to a file (creates or overwrites)"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "File path to write");
        props.putObject("content").put("type", "string").put("description", "Content to write");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "string"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String filePath = input.get("path").asText();
            String content = input.get("content").asText();
            Path wslResolved = WslPathHelper.resolve(filePath);
            Path resolved = wslResolved.isAbsolute() ? wslResolved : ctx.workingDirectory().resolve(filePath).normalize();
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            return ToolResult.success(MAPPER.valueToTree("Written " + content.length() + " chars to " + filePath));
        } catch (Exception e) {
            return ToolResult.failure("Failed to write file: " + e.getMessage());
        }
    }
}
