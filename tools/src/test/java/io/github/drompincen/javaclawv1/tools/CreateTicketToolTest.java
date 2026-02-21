package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateTicketToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ThingService thingService;
    @Mock private ToolStream stream;

    private CreateTicketTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateTicketTool();
        tool.setThingService(thingService);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(thingService.createThing(any(), eq(ThingCategory.TICKET), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.TICKET);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    @Test
    void toolMetadata() {
        assertThat(tool.name()).isEqualTo("create_ticket");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.AGENT_INTERNAL);
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
    void failsWithoutThingService() {
        CreateTicketTool unwired = new CreateTicketTool();
        ObjectNode input = MAPPER.createObjectNode().put("projectId", "p1").put("title", "test");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available");
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.TICKET), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("title")).isEqualTo("Fix login bug");
        assertThat(payload.get("status")).isEqualTo("TODO");
        assertThat(payload.get("priority")).isEqualTo("MEDIUM");
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.TICKET), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("priority")).isEqualTo("CRITICAL");
        assertThat(payloadCaptor.getValue().get("description")).isEqualTo("Production is down");
    }

    @Test
    void linksSourceThread() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Some task");
        input.put("sourceThreadId", "thread-abc");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.TICKET), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("linkedThreadIds")).asList().containsExactly("thread-abc");
    }

    @Test
    void dedupReturnsExistingTicket() {
        ThingDocument existing = new ThingDocument();
        existing.setId("existing-ticket-id");
        existing.setThingCategory(ThingCategory.TICKET);
        when(thingService.findByProjectCategoryAndTitleIgnoreCase("proj-1", ThingCategory.TICKET, "Fix login bug"))
                .thenReturn(Optional.of(existing));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Fix login bug");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("ticketId").asText()).isEqualTo("existing-ticket-id");
        assertThat(result.output().get("status").asText()).isEqualTo("already_exists");
        verify(thingService, never()).createThing(any(), any(), any());
    }
}
