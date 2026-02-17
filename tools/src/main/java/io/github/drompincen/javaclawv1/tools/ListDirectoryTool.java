package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

public class ListDirectoryTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "list_directory"; }
    @Override public String description() { return "List files and directories in a path"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Directory path to list");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "array"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String dirPath = input.get("path").asText();
            Path wslResolved = WslPathHelper.resolve(dirPath);
            Path resolved = wslResolved.isAbsolute() ? wslResolved : ctx.workingDirectory().resolve(dirPath).normalize();
            var entries = Files.list(resolved)
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.toList());
            return ToolResult.success(MAPPER.valueToTree(entries));
        } catch (Exception e) {
            return ToolResult.failure("Failed to list directory: " + e.getMessage());
        }
    }
}
