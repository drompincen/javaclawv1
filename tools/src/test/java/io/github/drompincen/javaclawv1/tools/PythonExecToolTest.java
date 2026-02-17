package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PythonExecToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private PythonExecTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new PythonExecTool();
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("python_exec");
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.EXEC_SHELL);
        assertThat(tool.description()).contains("Python");
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void inputSchemaRequiresCode() {
        var schema = tool.inputSchema();
        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("code");
    }

    @Test
    void inputSchemaHasExpectedProperties() {
        var props = tool.inputSchema().get("properties");
        assertThat(props.has("code")).isTrue();
        assertThat(props.has("timeout_seconds")).isTrue();
        assertThat(props.has("python_cmd")).isTrue();
    }

    static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            try {
                Process p = new ProcessBuilder("python", "--version").start();
                return p.waitFor() == 0;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    @Test
    @EnabledIf("pythonAvailable")
    void executesSimplePythonCode(@TempDir Path tempDir) {
        ObjectNode input = mapper.createObjectNode();
        input.put("code", "print('hello from python')");
        input.put("timeout_seconds", 10);

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("exitCode").asInt()).isEqualTo(0);
        assertThat(result.output().get("stdout").asText()).contains("hello from python");
    }

    @Test
    @EnabledIf("pythonAvailable")
    void capturesStderrOnError(@TempDir Path tempDir) {
        ObjectNode input = mapper.createObjectNode();
        input.put("code", "import sys; sys.exit(1)");
        input.put("timeout_seconds", 10);

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("exitCode").asInt()).isEqualTo(1);
    }
}
