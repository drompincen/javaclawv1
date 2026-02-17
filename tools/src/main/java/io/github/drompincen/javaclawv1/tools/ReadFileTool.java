package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class ReadFileTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "read_file"; }
    @Override public String description() { return "Read the contents of a file"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "File path to read");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "string"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String filePath = input.get("path").asText();
            Path resolved;
            Path wslResolved = WslPathHelper.resolve(filePath);
            if (wslResolved.isAbsolute()) {
                resolved = wslResolved;
            } else {
                resolved = ctx.workingDirectory().resolve(filePath).normalize();
            }
            // Fallback: try WSL-translated path if primary doesn't exist
            if (!Files.exists(resolved) && WslPathHelper.isWsl()) {
                Path alternate = WslPathHelper.resolve(filePath);
                if (Files.exists(alternate)) resolved = alternate;
            }
            String content = Files.readString(resolved);
            return ToolResult.success(MAPPER.valueToTree(content));
        } catch (Exception e) {
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }
}
