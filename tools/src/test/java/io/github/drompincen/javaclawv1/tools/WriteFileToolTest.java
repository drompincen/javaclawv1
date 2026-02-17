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

class WriteFileToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WriteFileTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new WriteFileTool();
    }

    @Test
    void nameAndRiskProfile() {
        assertThat(tool.name()).isEqualTo("write_file");
        assertThat(tool.riskProfiles()).contains(ToolRiskProfile.WRITE_FILES);
    }

    @Test
    void writesFileSuccessfully(@TempDir Path tempDir) throws IOException {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "output.txt");
        input.put("content", "File content here");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(tempDir.resolve("output.txt"))).isEqualTo("File content here");
    }

    @Test
    void createsParentDirectories(@TempDir Path tempDir) throws IOException {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "sub/dir/file.txt");
        input.put("content", "nested");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(tempDir.resolve("sub/dir/file.txt"))).isEqualTo("nested");
    }
}
