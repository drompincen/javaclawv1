package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateThreadToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ThreadRepository threadRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ToolStream stream;

    private CreateThreadTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateThreadTool();
        tool.setThreadRepository(threadRepository);
        tool.setMessageRepository(messageRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(threadRepository.save(any(ThreadDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(threadRepository.findByTitleIgnoreCaseAndProjectIdsContaining(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void createNewThread_setsContentField() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Evidence Service Design");
        input.put("content", "## Design\n\nEvidence service API design notes.");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("seeded").asBoolean()).isTrue();

        ArgumentCaptor<ThreadDocument> captor = ArgumentCaptor.forClass(ThreadDocument.class);
        verify(threadRepository).save(captor.capture());
        ThreadDocument saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("## Design\n\nEvidence service API design notes.");
        assertThat(saved.getTitle()).isEqualTo("Evidence Service Design");
    }

    @Test
    void dedup_appendsContentToExisting() {
        ThreadDocument existing = new ThreadDocument();
        existing.setThreadId("thread-1");
        existing.setProjectIds(List.of("proj-1"));
        existing.setTitle("Evidence Service Design");
        existing.setContent("## Initial Design\n\nOriginal content.");
        existing.setDecisions(new ArrayList<>());
        existing.setActions(new ArrayList<>());

        when(threadRepository.findByTitleIgnoreCaseAndProjectIdsContaining("Evidence Service Design", "proj-1"))
                .thenReturn(List.of(existing));
        when(messageRepository.countBySessionId("thread-1")).thenReturn(1L);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Evidence Service Design");
        input.put("content", "## Updated Notes\n\nNew decisions made.");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("status").asText()).isEqualTo("updated_existing");

        ArgumentCaptor<ThreadDocument> captor = ArgumentCaptor.forClass(ThreadDocument.class);
        verify(threadRepository).save(captor.capture());
        ThreadDocument saved = captor.getValue();
        assertThat(saved.getContent()).contains("Original content.");
        assertThat(saved.getContent()).contains("---");
        assertThat(saved.getContent()).contains("New decisions made.");
    }

    @Test
    void dedup_mergesDecisionsAndActions() {
        ThreadDocument existing = new ThreadDocument();
        existing.setThreadId("thread-1");
        existing.setProjectIds(List.of("proj-1"));
        existing.setTitle("Evidence Service Design");
        existing.setContent("Initial content");

        ThreadDocument.Decision existingDecision = new ThreadDocument.Decision();
        existingDecision.setText("Use S3");
        existing.setDecisions(new ArrayList<>(List.of(existingDecision)));

        ThreadDocument.ActionItem existingAction = new ThreadDocument.ActionItem();
        existingAction.setText("Build API");
        existingAction.setAssignee("Alice");
        existing.setActions(new ArrayList<>(List.of(existingAction)));

        when(threadRepository.findByTitleIgnoreCaseAndProjectIdsContaining("Evidence Service Design", "proj-1"))
                .thenReturn(List.of(existing));
        when(messageRepository.countBySessionId("thread-1")).thenReturn(1L);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Evidence Service Design");
        input.put("content", "New content");

        ArrayNode decisions = input.putArray("decisions");
        decisions.add("7-year retention");

        ArrayNode actions = input.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("text", "Implement audit trail");
        action.put("assignee", "Charlie");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<ThreadDocument> captor = ArgumentCaptor.forClass(ThreadDocument.class);
        verify(threadRepository).save(captor.capture());
        ThreadDocument saved = captor.getValue();
        assertThat(saved.getDecisions()).hasSize(2);
        assertThat(saved.getDecisions().get(0).getText()).isEqualTo("Use S3");
        assertThat(saved.getDecisions().get(1).getText()).isEqualTo("7-year retention");
        assertThat(saved.getActions()).hasSize(2);
        assertThat(saved.getActions().get(0).getText()).isEqualTo("Build API");
        assertThat(saved.getActions().get(1).getText()).isEqualTo("Implement audit trail");
        assertThat(saved.getActions().get(1).getAssignee()).isEqualTo("Charlie");
    }
}
