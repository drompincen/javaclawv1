package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

class SearchFilesToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SearchFilesTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new SearchFilesTool();
    }

    @Test
    void findsMatchingFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("readme.md"), "# Hello");
        Files.writeString(tempDir.resolve("app.java"), "class App {}");
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}");

        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "**/*.java");
        input.put("path", ".");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        String output = result.output().toString();
        assertThat(output).contains("Main.java");
        assertThat(output).doesNotContain("readme.md");
    }
}
