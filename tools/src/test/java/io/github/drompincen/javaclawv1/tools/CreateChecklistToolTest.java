package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateChecklistToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ChecklistRepository checklistRepository;
    @Mock private ToolStream stream;

    private CreateChecklistTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateChecklistTool();
        tool.setChecklistRepository(checklistRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(checklistRepository.save(any(ChecklistDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void failsWithoutRepository() {
        CreateChecklistTool unwired = new CreateChecklistTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "p1");
        input.put("name", "test");
        input.putArray("items").addObject().put("text", "item 1");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
    }

    @Test
    void failsWithoutProjectId() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", "test");
        input.putArray("items").addObject().put("text", "item");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("projectId");
    }

    @Test
    void failsWithEmptyItems() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "p1");
        input.put("name", "test");
        input.putArray("items"); // empty array
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("items");
    }

    @Test
    void createsChecklistWithItems() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("name", "Sprint Checklist");
        ArrayNode items = input.putArray("items");
        items.addObject().put("text", "Write tests").put("assignee", "Alice");
        items.addObject().put("text", "Review PR");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("itemCount").asInt()).isEqualTo(2);
        assertThat(result.output().get("checklistId").asText()).isNotBlank();

        ArgumentCaptor<ChecklistDocument> captor = ArgumentCaptor.forClass(ChecklistDocument.class);
        verify(checklistRepository).save(captor.capture());
        ChecklistDocument saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getName()).isEqualTo("Sprint Checklist");
        assertThat(saved.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getItems().get(0).getText()).isEqualTo("Write tests");
        assertThat(saved.getItems().get(0).getAssignee()).isEqualTo("Alice");
        assertThat(saved.getItems().get(0).isChecked()).isFalse();
        assertThat(saved.getItems().get(1).getAssignee()).isNull();
    }
}
