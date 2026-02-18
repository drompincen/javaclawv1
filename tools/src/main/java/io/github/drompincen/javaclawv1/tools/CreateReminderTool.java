package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReminderRepository;
import io.github.drompincen.javaclawv1.protocol.api.ReminderDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class CreateReminderTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ReminderRepository reminderRepository;

    @Override public String name() { return "create_reminder"; }

    @Override public String description() {
        return "Create a new reminder for a project. Reminders can be one-shot or recurring, " +
               "time-based or condition-based. Link back to source threads for traceability.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("message").put("type", "string")
                .put("description", "The reminder message text");
        props.putObject("type").put("type", "string")
                .put("description", "TIME_BASED or CONDITION_BASED (default TIME_BASED)");
        props.putObject("triggerAt").put("type", "string")
                .put("description", "ISO-8601 timestamp for when the reminder should trigger");
        props.putObject("recurring").put("type", "boolean")
                .put("description", "Whether this reminder recurs (default false)");
        props.putObject("intervalSeconds").put("type", "integer")
                .put("description", "Recurrence interval in seconds (only if recurring=true)");
        props.putObject("condition").put("type", "string")
                .put("description", "Condition expression for CONDITION_BASED reminders");
        props.putObject("sourceThreadId").put("type", "string")
                .put("description", "Thread ID where this reminder was identified");
        schema.putArray("required").add("projectId").add("message");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }

    public void setReminderRepository(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (reminderRepository == null) {
            return ToolResult.failure("Reminder repository not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String message = input.path("message").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (message == null || message.isBlank()) return ToolResult.failure("'message' is required");

        String typeStr = input.path("type").asText("TIME_BASED");
        ReminderDto.ReminderType type;
        try {
            type = ReminderDto.ReminderType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = ReminderDto.ReminderType.TIME_BASED;
        }

        ReminderDocument doc = new ReminderDocument();
        doc.setReminderId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setMessage(message);
        doc.setType(type);
        doc.setTriggered(false);
        doc.setRecurring(input.path("recurring").asBoolean(false));
        doc.setSessionId(ctx.sessionId());

        String triggerAtStr = input.path("triggerAt").asText(null);
        if (triggerAtStr != null && !triggerAtStr.isBlank()) {
            try {
                doc.setTriggerAt(Instant.parse(triggerAtStr));
            } catch (Exception e) {
                return ToolResult.failure("Invalid triggerAt format. Use ISO-8601 (e.g., 2026-03-01T10:00:00Z)");
            }
        }

        if (doc.isRecurring() && input.has("intervalSeconds")) {
            doc.setIntervalSeconds(input.path("intervalSeconds").asLong());
        }

        String condition = input.path("condition").asText(null);
        if (condition != null && !condition.isBlank()) {
            doc.setCondition(condition);
        }

        reminderRepository.save(doc);
        stream.progress(100, "Reminder created: " + message);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("reminderId", doc.getReminderId());
        result.put("status", "created");
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
