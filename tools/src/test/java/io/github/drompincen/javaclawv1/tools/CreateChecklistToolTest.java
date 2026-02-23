package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateChecklistToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ThingService thingService;
    @Mock private ToolStream stream;

    private CreateChecklistTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new CreateChecklistTool();
        tool.setThingService(thingService);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(thingService.createThing(any(), eq(ThingCategory.CHECKLIST), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(java.util.UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.CHECKLIST);
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
    void failsWithoutThingService() {
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

    @SuppressWarnings("unchecked")
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

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.CHECKLIST), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("name")).isEqualTo("Sprint Checklist");
        assertThat(payload.get("status")).isEqualTo("IN_PROGRESS");
        List<Map<String, Object>> savedItems = (List<Map<String, Object>>) payload.get("items");
        assertThat(savedItems).hasSize(2);
        assertThat(savedItems.get(0).get("text")).isEqualTo("Write tests");
        assertThat(savedItems.get(0).get("assignee")).isEqualTo("Alice");
        assertThat(savedItems.get(0).get("checked")).isEqualTo(false);
        assertThat(savedItems.get(1).get("assignee")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void linksSourceThread() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("name", "Extracted Tasks");
        input.putArray("items").addObject().put("text", "Review PR");
        input.put("sourceThreadId", "thread-xyz");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(thingService).createThing(eq("proj-1"), eq(ThingCategory.CHECKLIST), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("sourceThreadId")).isEqualTo("thread-xyz");
    }

    @Test
    void dedupReturnsExistingChecklist() {
        ThingDocument existing = new ThingDocument();
        existing.setId("existing-checklist-id");
        existing.setThingCategory(ThingCategory.CHECKLIST);
        when(thingService.findByProjectCategoryAndNameIgnoreCase("proj-1", ThingCategory.CHECKLIST, "Sprint Checklist"))
                .thenReturn(Optional.of(existing));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("projectId", "proj-1");
        input.put("name", "Sprint Checklist");
        input.putArray("items").addObject().put("text", "item 1");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("checklistId").asText()).isEqualTo("existing-checklist-id");
        assertThat(result.output().get("status").asText()).isEqualTo("already_exists");
        verify(thingService, never()).createThing(any(), any(), any());
    }
}
