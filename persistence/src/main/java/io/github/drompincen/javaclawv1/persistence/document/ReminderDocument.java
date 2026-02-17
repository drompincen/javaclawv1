package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ReminderDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "reminders")
@CompoundIndex(name = "project_trigger", def = "{'projectId': 1, 'triggerAt': 1}")
public class ReminderDocument {

    @Id
    private String reminderId;
    private String projectId;
    private String message;
    private ReminderDto.ReminderType type;
    private Instant triggerAt;
    private String condition;
    private boolean triggered;
    private boolean recurring;
    private Long intervalSeconds;
    private String sessionId;

    public ReminderDocument() {}

    public String getReminderId() { return reminderId; }
    public void setReminderId(String reminderId) { this.reminderId = reminderId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public ReminderDto.ReminderType getType() { return type; }
    public void setType(ReminderDto.ReminderType type) { this.type = type; }

    public Instant getTriggerAt() { return triggerAt; }
    public void setTriggerAt(Instant triggerAt) { this.triggerAt = triggerAt; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public boolean isTriggered() { return triggered; }
    public void setTriggered(boolean triggered) { this.triggered = triggered; }

    public boolean isRecurring() { return recurring; }
    public void setRecurring(boolean recurring) { this.recurring = recurring; }

    public Long getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(Long intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
