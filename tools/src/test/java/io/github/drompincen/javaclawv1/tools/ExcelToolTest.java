package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ExcelTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new ExcelTool();
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("excel");
        assertThat(tool.riskProfiles()).contains(ToolRiskProfile.READ_ONLY, ToolRiskProfile.WRITE_FILES);
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void writeAndReadExcel(@TempDir Path tempDir) {
        // Write
        ObjectNode writeInput = mapper.createObjectNode();
        writeInput.put("operation", "write");
        writeInput.put("file_path", "test.xlsx");
        writeInput.put("sheet_name", "Data");
        ArrayNode data = writeInput.putArray("data");
        ArrayNode header = data.addArray();
        header.add("Name");
        header.add("Age");
        ArrayNode row1 = data.addArray();
        row1.add("Alice");
        row1.add("30");
        ArrayNode row2 = data.addArray();
        row2.add("Bob");
        row2.add("25");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult writeResult = tool.execute(ctx, writeInput, noopStream);

        assertThat(writeResult.success()).isTrue();
        assertThat(writeResult.output().get("written").asBoolean()).isTrue();
        assertThat(writeResult.output().get("rowCount").asInt()).isEqualTo(3);
        assertThat(tempDir.resolve("test.xlsx").toFile().exists()).isTrue();

        // Read
        ObjectNode readInput = mapper.createObjectNode();
        readInput.put("operation", "read");
        readInput.put("file_path", "test.xlsx");
        readInput.put("sheet_name", "Data");

        ToolResult readResult = tool.execute(ctx, readInput, noopStream);

        assertThat(readResult.success()).isTrue();
        assertThat(readResult.output().get("sheet").asText()).isEqualTo("Data");
        assertThat(readResult.output().get("rowCount").asInt()).isEqualTo(3);
        JsonNode rows = readResult.output().get("data");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.get(0).get(0).asText()).isEqualTo("Name");
        assertThat(rows.get(1).get(0).asText()).isEqualTo("Alice");
    }

    @Test
    void listSheets(@TempDir Path tempDir) {
        // First write a file
        ObjectNode writeInput = mapper.createObjectNode();
        writeInput.put("operation", "write");
        writeInput.put("file_path", "multi.xlsx");
        writeInput.put("sheet_name", "Sheet1");
        ArrayNode data = writeInput.putArray("data");
        data.addArray().add("val");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        tool.execute(ctx, writeInput, noopStream);

        // List sheets
        ObjectNode listInput = mapper.createObjectNode();
        listInput.put("operation", "list_sheets");
        listInput.put("file_path", "multi.xlsx");

        ToolResult result = tool.execute(ctx, listInput, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sheets").size()).isEqualTo(1);
        assertThat(result.output().get("sheets").get(0).get("name").asText()).isEqualTo("Sheet1");
    }

    @Test
    void readNonExistentFileReturnsFailure(@TempDir Path tempDir) {
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "read");
        input.put("file_path", "nonexistent.xlsx");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to read");
    }

    @Test
    void unknownOperationReturnsFailure(@TempDir Path tempDir) {
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "invalid");
        input.put("file_path", "test.xlsx");

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown operation");
    }

    @Test
    void maxRowsLimitsOutput(@TempDir Path tempDir) {
        // Write 10 rows
        ObjectNode writeInput = mapper.createObjectNode();
        writeInput.put("operation", "write");
        writeInput.put("file_path", "big.xlsx");
        ArrayNode data = writeInput.putArray("data");
        for (int i = 0; i < 10; i++) {
            data.addArray().add("row" + i);
        }

        ToolContext ctx = new ToolContext("s1", tempDir, Map.of());
        tool.execute(ctx, writeInput, noopStream);

        // Read with max_rows=3
        ObjectNode readInput = mapper.createObjectNode();
        readInput.put("operation", "read");
        readInput.put("file_path", "big.xlsx");
        readInput.put("max_rows", 3);

        ToolResult result = tool.execute(ctx, readInput, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("rowCount").asInt()).isEqualTo(3);
    }
}
