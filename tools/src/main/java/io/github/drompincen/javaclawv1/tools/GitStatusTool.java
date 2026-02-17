package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

public class GitStatusTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "git_status"; }
    @Override public String description() { return "Show git working tree status"; }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode().put("type", "object");
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "string"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(ctx.workingDirectory().toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return ToolResult.success(MAPPER.valueToTree(output));
        } catch (Exception e) {
            return ToolResult.failure("git status failed: " + e.getMessage());
        }
    }
}
