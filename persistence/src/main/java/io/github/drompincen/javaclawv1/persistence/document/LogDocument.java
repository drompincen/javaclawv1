package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Stores system logs, errors, and issues in MongoDB for visibility and debugging.
 */
@Document(collection = "logs")
@CompoundIndex(name = "level_time", def = "{'level': 1, 'timestamp': -1}")
public class LogDocument {

    public enum LogLevel { DEBUG, INFO, WARN, ERROR }

    @Id
    private String logId;
    private LogLevel level;
    private String source;           // e.g., "AgentLoop", "ToolRegistry", "LlmService"
    private String sessionId;        // optional: associated session
    private String message;
    private String stackTrace;       // optional: for errors
    private Map<String, Object> metadata;  // additional context
    @Indexed(direction = org.springframework.data.mongodb.core.index.IndexDirection.DESCENDING)
    private Instant timestamp;

    public LogDocument() {}

    public String getLogId() { return logId; }
    public void setLogId(String logId) { this.logId = logId; }

    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
