package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

public class GitCommitTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "git_commit"; }
    @Override public String description() { return "Stage all changes and commit with a message"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("message").put("type", "string").put("description", "Commit message");
        schema.putArray("required").add("message");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "string"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String message = input.get("message").asText();
            var dir = ctx.workingDirectory().toFile();

            // Stage
            new ProcessBuilder("git", "add", "-A").directory(dir).start().waitFor();

            // Commit
            ProcessBuilder pb = new ProcessBuilder("git", "commit", "-m", message)
                    .directory(dir).redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                return ToolResult.failure("git commit failed: " + output);
            }
            return ToolResult.success(MAPPER.valueToTree(output));
        } catch (Exception e) {
            return ToolResult.failure("git commit failed: " + e.getMessage());
        }
    }
}
