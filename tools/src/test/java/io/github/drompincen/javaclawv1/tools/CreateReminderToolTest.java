package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReminderRepository;
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

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateReminderToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ReminderRepository reminderRepository;
    @Mock private ToolStream stream;

    private CreateReminderTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateReminderTool();
        tool.setReminderRepository(reminderRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(reminderRepository.save(any(ReminderDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void riskProfileIsAgentInternal() {
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.AGENT_INTERNAL);
    }

    @Test
    void failsWithoutRepository() {
        CreateReminderTool unwired = new CreateReminderTool();
        ObjectNode input = MAPPER.createObjectNode().put("projectId", "p1").put("message", "test");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("repository not available");
    }

    @Test
    void failsWithoutProjectId() {
        ObjectNode input = MAPPER.createObjectNode().put("message", "test");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("projectId");
    }

    @Test
    void failsWithoutMessage() {
        ObjectNode input = MAPPER.createObjectNode().put("projectId", "p1");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("message");
    }

    @Test
    void createsTimedReminder() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("message", "Review sprint goals");
        input.put("triggerAt", "2026-03-01T10:00:00Z");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("status").asText()).isEqualTo("created");
        assertThat(result.output().get("reminderId").asText()).isNotBlank();

        ArgumentCaptor<ReminderDocument> captor = ArgumentCaptor.forClass(ReminderDocument.class);
        verify(reminderRepository).save(captor.capture());
        ReminderDocument saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getMessage()).isEqualTo("Review sprint goals");
        assertThat(saved.getTriggerAt()).isNotNull();
        assertThat(saved.isTriggered()).isFalse();
    }

    @Test
    void createsRecurringReminder() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("message", "Daily standup");
        input.put("recurring", true);
        input.put("intervalSeconds", 86400);

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<ReminderDocument> captor = ArgumentCaptor.forClass(ReminderDocument.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().isRecurring()).isTrue();
        assertThat(captor.getValue().getIntervalSeconds()).isEqualTo(86400L);
    }

    @Test
    void linksSourceThread() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("message", "Standup reminder");
        input.put("sourceThreadId", "thread-xyz");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<ReminderDocument> captor = ArgumentCaptor.forClass(ReminderDocument.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceThreadId()).isEqualTo("thread-xyz");
    }

    @Test
    void rejectsInvalidTriggerAtFormat() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "p1");
        input.put("message", "test");
        input.put("triggerAt", "not-a-date");

        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid triggerAt format");
    }
}
