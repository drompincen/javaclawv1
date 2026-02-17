package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

public class GitDiffTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "git_diff"; }
    @Override public String description() { return "Show git diff of changes"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("staged").put("type", "boolean").put("description", "Show staged changes only");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "string"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            boolean staged = input.has("staged") && input.get("staged").asBoolean();
            var cmd = staged ? new String[]{"git", "diff", "--cached"} : new String[]{"git", "diff"};
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(ctx.workingDirectory().toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return ToolResult.success(MAPPER.valueToTree(output));
        } catch (Exception e) {
            return ToolResult.failure("git diff failed: " + e.getMessage());
        }
    }
}
