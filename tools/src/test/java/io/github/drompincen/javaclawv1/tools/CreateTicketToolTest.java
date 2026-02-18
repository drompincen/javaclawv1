package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
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
class CreateTicketToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private TicketRepository ticketRepository;
    @Mock private ToolStream stream;

    private CreateTicketTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateTicketTool();
        tool.setTicketRepository(ticketRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(ticketRepository.save(any(TicketDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void toolMetadata() {
        assertThat(tool.name()).isEqualTo("create_ticket");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.WRITE_FILES);
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.outputSchema()).isNotNull();
    }

    @Test
    void inputSchemaHasRequiredFields() throws Exception {
        String schema = MAPPER.writeValueAsString(tool.inputSchema());
        assertThat(schema).contains("projectId");
        assertThat(schema).contains("title");
    }

    @Test
    void failsWithoutRepository() {
        CreateTicketTool unwired = new CreateTicketTool();
        ObjectNode input = MAPPER.createObjectNode().put("projectId", "p1").put("title", "test");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("repository not available");
    }

    @Test
    void failsWithoutProjectId() {
        ObjectNode input = MAPPER.createObjectNode().put("title", "test");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("projectId");
    }

    @Test
    void failsWithoutTitle() {
        ObjectNode input = MAPPER.createObjectNode().put("projectId", "p1");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("title");
    }

    @Test
    void createsTicketWithDefaults() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Fix login bug");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("ticketId").asText()).isNotBlank();
        assertThat(result.output().get("status").asText()).isEqualTo("created");

        ArgumentCaptor<TicketDocument> captor = ArgumentCaptor.forClass(TicketDocument.class);
        verify(ticketRepository).save(captor.capture());
        TicketDocument saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getTitle()).isEqualTo("Fix login bug");
        assertThat(saved.getStatus()).isEqualTo(TicketDto.TicketStatus.TODO);
        assertThat(saved.getPriority()).isEqualTo(TicketDto.TicketPriority.MEDIUM);
    }

    @Test
    void createsTicketWithPriorityAndDescription() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Critical outage");
        input.put("description", "Production is down");
        input.put("priority", "CRITICAL");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<TicketDocument> captor = ArgumentCaptor.forClass(TicketDocument.class);
        verify(ticketRepository).save(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(TicketDto.TicketPriority.CRITICAL);
        assertThat(captor.getValue().getDescription()).isEqualTo("Production is down");
    }

    @Test
    void linksSourceThread() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Some task");
        input.put("sourceThreadId", "thread-abc");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<TicketDocument> captor = ArgumentCaptor.forClass(TicketDocument.class);
        verify(ticketRepository).save(captor.capture());
        assertThat(captor.getValue().getLinkedThreadIds()).containsExactly("thread-abc");
    }
}
