package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.LogDocument;
import io.github.drompincen.javaclawv1.persistence.document.LlmInteractionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LogRepository;
import io.github.drompincen.javaclawv1.persistence.repository.LlmInteractionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogRepository logRepository;
    private final LlmInteractionRepository llmInteractionRepository;
    private final MessageRepository messageRepository;

    public LogController(LogRepository logRepository, LlmInteractionRepository llmInteractionRepository,
                         MessageRepository messageRepository) {
        this.logRepository = logRepository;
        this.llmInteractionRepository = llmInteractionRepository;
        this.messageRepository = messageRepository;
    }

    @GetMapping
    public List<LogDocument> listLogs(@RequestParam(required = false) String level,
                                       @RequestParam(required = false) String sessionId,
                                       @RequestParam(required = false, defaultValue = "100") int limit) {
        if (sessionId != null) return logRepository.findBySessionId(sessionId);
        if (level != null) {
            LogDocument.LogLevel l = LogDocument.LogLevel.valueOf(level.toUpperCase());
            return logRepository.findByLevel(l);
        }
        return logRepository.findTop100ByOrderByTimestampDesc();
    }

    @GetMapping("/errors")
    public List<LogDocument> listErrors() {
        return logRepository.findByLevel(LogDocument.LogLevel.ERROR);
    }

    @GetMapping("/llm-interactions")
    public List<LlmInteractionDocument> listLlmInteractions(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String agentId) {
        if (sessionId != null) return llmInteractionRepository.findBySessionId(sessionId);
        if (agentId != null) return llmInteractionRepository.findByAgentId(agentId);
        return llmInteractionRepository.findTop100ByOrderByTimestampDesc();
    }

    @GetMapping("/llm-interactions/metrics")
    public ResponseEntity<Map<String, Object>> llmMetrics() {
        long totalInteractions = llmInteractionRepository.count();
        List<LlmInteractionDocument> recent = llmInteractionRepository.findTop100ByOrderByTimestampDesc();

        long totalTokens = recent.stream().mapToLong(d -> d.getPromptTokens() + d.getCompletionTokens()).sum();
        long totalDuration = recent.stream().mapToLong(LlmInteractionDocument::getDurationMs).sum();
        long errors = recent.stream().filter(d -> !d.isSuccess()).count();

        long totalMessages = messageRepository.count();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalInteractions", totalInteractions);
        metrics.put("recentTokens", totalTokens);
        metrics.put("recentDurationMs", totalDuration);
        metrics.put("recentErrors", errors);
        metrics.put("avgDurationMs", recent.isEmpty() ? 0 : totalDuration / recent.size());
        metrics.put("totalMessages", totalMessages);
        return ResponseEntity.ok(metrics);
    }
}
