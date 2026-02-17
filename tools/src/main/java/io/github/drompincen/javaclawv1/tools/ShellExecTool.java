package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ShellExecTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "shell_exec"; }
    @Override public String description() { return "Execute a shell command"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string").put("description", "Shell command to execute");
        props.putObject("timeout_seconds").put("type", "integer").put("description", "Timeout in seconds (default 30)");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.EXEC_SHELL); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        try {
            String command = input.get("command").asText();
            int timeout = input.has("timeout_seconds") ? input.get("timeout_seconds").asInt() : 30;

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .directory(ctx.workingDirectory().toFile())
                    .redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        stream.stdoutDelta(line + "\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrThread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        stream.stderrDelta(line + "\n");
                    }
                } catch (Exception ignored) {}
            });

            stdoutThread.start();
            stderrThread.start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure("Command timed out after " + timeout + " seconds");
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            return ToolResult.success(MAPPER.valueToTree(Map.of(
                    "exitCode", process.exitValue(),
                    "stdout", stdout.toString(),
                    "stderr", stderr.toString())));
        } catch (Exception e) {
            return ToolResult.failure("Shell exec failed: " + e.getMessage());
        }
    }
}
