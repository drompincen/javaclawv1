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

class ListDirectoryToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ListDirectoryTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new ListDirectoryTool();
    }

    @Test
    void nameAndRiskProfile() {
        assertThat(tool.name()).isEqualTo("list_directory");
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.READ_ONLY);
    }

    @Test
    void listsFilesAndDirectories(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "content");
        Files.createDirectory(tempDir.resolve("subdir"));

        ObjectNode input = mapper.createObjectNode().put("path", ".");
        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        String output = result.output().toString();
        assertThat(output).contains("file.txt");
        assertThat(output).contains("subdir/");
    }
}
