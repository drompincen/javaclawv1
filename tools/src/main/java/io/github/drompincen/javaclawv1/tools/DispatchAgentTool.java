package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.IntakeSourceType;
import io.github.drompincen.javaclawv1.protocol.api.IntakeStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class DispatchAgentTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "dispatch_agent"; }

    @Override public String description() {
        return "Record a dispatch intent for an intake — which downstream agent should process the classified content. " +
               "Creates an intake record tracking the classification and dispatch target.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("agentId").put("type", "string")
                .put("description", "Target agent to dispatch to (e.g., pm, thread-agent, plan-agent)");
        props.putObject("projectId").put("type", "string");
        props.putObject("sourceType").put("type", "string")
                .put("description", "Content type: JIRA_DUMP, CONFLUENCE_EXPORT, MEETING_NOTES, etc.");
        props.putObject("summary").put("type", "string")
                .put("description", "Brief summary of the intake content");
        props.putObject("priority").put("type", "integer")
                .put("description", "Priority 1-5 (1=highest, default=3)");
        schema.putArray("required").add("agentId").add("projectId").add("sourceType");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) {
            return ToolResult.failure("ThingService not available");
        }

        String agentId = input.path("agentId").asText(null);
        String projectId = input.path("projectId").asText(null);
        String sourceTypeStr = input.path("sourceType").asText(null);

        if (agentId == null || agentId.isBlank()) return ToolResult.failure("'agentId' is required");
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (sourceTypeStr == null || sourceTypeStr.isBlank()) return ToolResult.failure("'sourceType' is required");

        IntakeSourceType sourceType;
        try {
            sourceType = IntakeSourceType.valueOf(sourceTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            sourceType = IntakeSourceType.UNKNOWN;
        }

        String summary = input.path("summary").asText("");
        int priority = input.path("priority").asInt(3);

        Map<String, Object> dispatchTarget = new LinkedHashMap<>();
        dispatchTarget.put("agentId", agentId);
        dispatchTarget.put("sessionId", "pending-" + UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("summary", summary);
        metadata.put("priority", priority);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", sourceType.name());
        payload.put("classifiedAs", sourceType.name());
        payload.put("status", IntakeStatus.DISPATCHED.name());
        payload.put("dispatchedTo", List.of(dispatchTarget));
        payload.put("extractedMetadata", metadata);

        var thing = thingService.createThing(projectId, ThingCategory.INTAKE, payload);
        stream.progress(100, "Dispatched to " + agentId + " for project " + projectId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("intakeId", thing.getId());
        result.put("dispatchedTo", agentId);
        result.put("status", "DISPATCHED");
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
