package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PythonExecTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "python_exec"; }
    @Override public String description() {
        return "Write and execute Python code. Provide Python source code and it will be saved to a temp file and run. " +
               "Useful for data analysis, scripting, text processing, API calls, and quick prototyping. " +
               "Supports pip packages if pre-installed on the system.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("code").put("type", "string").put("description", "Python source code to execute");
        props.putObject("timeout_seconds").put("type", "integer").put("description", "Timeout in seconds (default 60)");
        props.putObject("python_cmd").put("type", "string").put("description", "Python command to use (default: auto-detect python3 or python)");
        schema.putArray("required").add("code");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.EXEC_SHELL); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        Path tempFile = null;
        try {
            String code = input.get("code").asText();
            int timeout = input.has("timeout_seconds") ? input.get("timeout_seconds").asInt() : 60;
            String pythonCmd = input.has("python_cmd") ? input.get("python_cmd").asText() : detectPython();

            // Write code to temp file
            String fileName = ".jclaw_py_" + UUID.randomUUID().toString().substring(0, 8) + ".py";
            tempFile = ctx.workingDirectory().resolve(fileName);
            Files.writeString(tempFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonCmd, tempFile.toAbsolutePath().toString())
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
                return ToolResult.failure("Python execution timed out after " + timeout + " seconds");
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            return ToolResult.success(MAPPER.valueToTree(Map.of(
                    "exitCode", process.exitValue(),
                    "stdout", stdout.toString(),
                    "stderr", stderr.toString())));
        } catch (Exception e) {
            return ToolResult.failure("Python exec failed: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    private String detectPython() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        // Try python3 first (Linux/macOS), then python (Windows/fallback)
        for (String cmd : isWindows ? new String[]{"python", "python3"} : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return isWindows ? "python" : "python3";
    }
}
