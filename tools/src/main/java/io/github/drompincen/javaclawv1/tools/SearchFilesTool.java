package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.util.Set;
import java.util.stream.Collectors;

public class SearchFilesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "search_files"; }
    @Override public String description() { return "Search for files matching a glob pattern"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "Glob pattern (e.g. **/*.java)");
        props.putObject("path").put("type", "string").put("description", "Base directory");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "array"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String pattern = input.get("pattern").asText();
            String basePath = input.has("path") ? input.get("path").asText() : ".";
            Path base = ctx.workingDirectory().resolve(basePath).normalize();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            var results = java.nio.file.Files.walk(base, 10)
                    .filter(p -> matcher.matches(base.relativize(p)))
                    .map(p -> base.relativize(p).toString())
                    .limit(100)
                    .collect(Collectors.toList());
            return ToolResult.success(MAPPER.valueToTree(results));
        } catch (Exception e) {
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }
}
