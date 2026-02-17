package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.LogDocument;
import io.github.drompincen.javaclawv1.persistence.document.LlmInteractionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LogRepository;
import io.github.drompincen.javaclawv1.persistence.repository.LlmInteractionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persists logs and LLM interactions to MongoDB for metrics and debugging.
 */
@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private final LogRepository logRepository;
    private final LlmInteractionRepository llmInteractionRepository;

    public LogService(LogRepository logRepository, LlmInteractionRepository llmInteractionRepository) {
        this.logRepository = logRepository;
        this.llmInteractionRepository = llmInteractionRepository;
    }

    public void logInfo(String source, String sessionId, String message, Map<String, Object> metadata) {
        persist(LogDocument.LogLevel.INFO, source, sessionId, message, null, metadata);
    }

    public void logWarn(String source, String sessionId, String message, Map<String, Object> metadata) {
        persist(LogDocument.LogLevel.WARN, source, sessionId, message, null, metadata);
    }

    public void logError(String source, String sessionId, String message, Throwable error, Map<String, Object> metadata) {
        String stackTrace = null;
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            stackTrace = sw.toString();
        }
        persist(LogDocument.LogLevel.ERROR, source, sessionId, message, stackTrace, metadata);
    }

    private void persist(LogDocument.LogLevel level, String source, String sessionId,
                         String message, String stackTrace, Map<String, Object> metadata) {
        try {
            LogDocument doc = new LogDocument();
            doc.setLogId(UUID.randomUUID().toString());
            doc.setLevel(level);
            doc.setSource(source);
            doc.setSessionId(sessionId);
            doc.setMessage(message);
            doc.setStackTrace(stackTrace);
            doc.setMetadata(metadata);
            doc.setTimestamp(Instant.now());
            logRepository.save(doc);
        } catch (Exception e) {
            log.error("Failed to persist log entry: {}", message, e);
        }
    }

    /**
     * Record an LLM interaction for metrics tracking.
     */
    public void recordLlmInteraction(String sessionId, String agentId, String provider, String model,
                                      int messageCount, int promptTokens, int completionTokens,
                                      long durationMs, boolean success, String errorMessage) {
        try {
            LlmInteractionDocument doc = new LlmInteractionDocument();
            doc.setInteractionId(UUID.randomUUID().toString());
            doc.setSessionId(sessionId);
            doc.setAgentId(agentId);
            doc.setProvider(provider);
            doc.setModel(model);
            doc.setMessageCount(messageCount);
            doc.setPromptTokens(promptTokens);
            doc.setCompletionTokens(completionTokens);
            doc.setDurationMs(durationMs);
            doc.setSuccess(success);
            doc.setErrorMessage(errorMessage);
            doc.setTimestamp(Instant.now());
            llmInteractionRepository.save(doc);
        } catch (Exception e) {
            log.error("Failed to persist LLM interaction: {}", e.getMessage(), e);
        }
    }
}
