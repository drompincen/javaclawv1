package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @Mock private MemoryRepository memoryRepository;
    private MemoryTool tool;
    private final ToolStream noopStream = new ToolStream() {
        @Override public void stdoutDelta(String text) {}
        @Override public void stderrDelta(String text) {}
        @Override public void progress(int percent, String message) {}
        @Override public void artifactCreated(String type, String uriOrRef) {}
    };

    @BeforeEach
    void setUp() {
        tool = new MemoryTool();
        tool.setMemoryRepository(memoryRepository);
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("memory");
        assertThat(tool.riskProfiles()).contains(ToolRiskProfile.WRITE_FILES);
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void storeCreatesNewMemory() {
        when(memoryRepository.findByScopeAndKey(any(), any())).thenReturn(Optional.empty());
        when(memoryRepository.save(any(MemoryDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "store");
        input.put("scope", "GLOBAL");
        input.put("key", "test-key");
        input.put("content", "test content");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("stored").asBoolean()).isTrue();
        assertThat(result.output().get("key").asText()).isEqualTo("test-key");
        assertThat(result.output().get("updated").asBoolean()).isFalse();

        verify(memoryRepository).save(any(MemoryDocument.class));
    }

    @Test
    void storeUpdatesExistingMemory() {
        MemoryDocument existing = new MemoryDocument();
        existing.setMemoryId("mem-1");
        existing.setScope(MemoryDocument.MemoryScope.GLOBAL);
        existing.setKey("test-key");
        existing.setContent("old content");
        existing.setCreatedAt(Instant.now());

        when(memoryRepository.findByScopeAndKey(MemoryDocument.MemoryScope.GLOBAL, "test-key"))
                .thenReturn(Optional.of(existing));
        when(memoryRepository.save(any(MemoryDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "store");
        input.put("scope", "GLOBAL");
        input.put("key", "test-key");
        input.put("content", "new content");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("updated").asBoolean()).isTrue();
    }

    @Test
    void storeMissingKeyReturnsFailure() {
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "store");
        input.put("content", "some content");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("key");
    }

    @Test
    void recallByKeyReturnsMemory() {
        MemoryDocument doc = new MemoryDocument();
        doc.setMemoryId("mem-1");
        doc.setScope(MemoryDocument.MemoryScope.GLOBAL);
        doc.setKey("build-system");
        doc.setContent("Uses Maven");
        doc.setCreatedBy("agent");
        doc.setUpdatedAt(Instant.now());

        when(memoryRepository.findByScopeAndKey(MemoryDocument.MemoryScope.GLOBAL, "build-system"))
                .thenReturn(Optional.of(doc));

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "recall");
        input.put("scope", "GLOBAL");
        input.put("key", "build-system");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("count").asInt()).isEqualTo(1);
        assertThat(result.output().get("memories").get(0).get("content").asText()).isEqualTo("Uses Maven");
    }

    @Test
    void recallByQuerySearchesContent() {
        when(memoryRepository.searchContent("maven")).thenReturn(List.of());

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "recall");
        input.put("scope", "GLOBAL");
        input.put("query", "maven");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("count").asInt()).isEqualTo(0);
        verify(memoryRepository).searchContent("maven");
    }

    @Test
    void deleteRemovesMemory() {
        MemoryDocument doc = new MemoryDocument();
        doc.setMemoryId("mem-1");
        doc.setScope(MemoryDocument.MemoryScope.GLOBAL);
        doc.setKey("old-key");

        when(memoryRepository.findByScopeAndKey(MemoryDocument.MemoryScope.GLOBAL, "old-key"))
                .thenReturn(Optional.of(doc));

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "delete");
        input.put("scope", "GLOBAL");
        input.put("key", "old-key");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("deleted").asBoolean()).isTrue();
        verify(memoryRepository).deleteById("mem-1");
    }

    @Test
    void deleteNonExistentKeyReturnsFailure() {
        when(memoryRepository.findByScopeAndKey(any(), any())).thenReturn(Optional.empty());

        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "delete");
        input.put("scope", "GLOBAL");
        input.put("key", "nonexistent");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No memory found");
    }

    @Test
    void failsWithoutRepository() {
        MemoryTool noRepoTool = new MemoryTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "recall");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = noRepoTool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("repository");
    }

    @Test
    void invalidScopeReturnsFailure() {
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "recall");
        input.put("scope", "INVALID");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid scope");
    }

    @Test
    void unknownOperationReturnsFailure() {
        ObjectNode input = mapper.createObjectNode();
        input.put("operation", "bad_op");

        ToolContext ctx = new ToolContext("s1", Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, noopStream);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown operation");
    }
}
