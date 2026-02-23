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
class CreateObjectiveToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ThingService thingService;
    @Mock private ToolStream stream;

    private CreateObjectiveTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateObjectiveTool();
        tool.setThingService(thingService);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(thingService.createThing(any(), eq(ThingCategory.OBJECTIVE), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.OBJECTIVE);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    @Test
    void riskProfileIsAgentInternal() {
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.AGENT_INTERNAL);
    }

    @Test
    void failsWithoutProjectId() {
        ObjectNode input = MAPPER.createObjectNode().put("title", "Ship v2");
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

    @SuppressWarnings("unchecked")
    @Test
    void createsObjectiveWithDefaults() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Ship v2 by end of sprint");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("objectiveId").asText()).isNotBlank();
        assertThat(result.output().get("outcome").asText()).isEqualTo("Ship v2 by end of sprint");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.OBJECTIVE), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("outcome")).isEqualTo("Ship v2 by end of sprint");
        assertThat(payload.get("status")).isEqualTo("PROPOSED");
    }

    @Test
    void dedupReturnsExistingObjective() {
        ThingDocument existing = new ThingDocument();
        existing.setId("existing-objective-id");
        existing.setThingCategory(ThingCategory.OBJECTIVE);
        when(thingService.findByProjectCategoryAndPayloadFieldIgnoreCase(
                "proj-1", ThingCategory.OBJECTIVE, "outcome", "Ship v2 by end of sprint"))
                .thenReturn(Optional.of(existing));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("title", "Ship v2 by end of sprint");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("objectiveId").asText()).isEqualTo("existing-objective-id");
        assertThat(result.output().get("status").asText()).isEqualTo("already_exists");
        verify(thingService, never()).createThing(any(), any(), any());
    }
}
