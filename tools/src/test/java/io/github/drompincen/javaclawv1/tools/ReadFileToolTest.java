package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ReadFileTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new ReadFileTool();
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("read_file");
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.READ_ONLY);
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void readsFileSuccessfully(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");

        ObjectNode input = mapper.createObjectNode().put("path", file.toString());
        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().asText()).isEqualTo("Hello World");
    }

    @Test
    void failsForMissingFile(@TempDir Path tempDir) {
        ObjectNode input = mapper.createObjectNode().put("path", "nonexistent.txt");
        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
    }
}
