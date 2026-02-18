package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.FullDocument;
import io.github.drompincen.javaclawv1.persistence.document.TestPromptDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TestPromptRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background consumer that watches testPrompts collection via change stream,
 * reads the prompt context, generates a plausible response, and writes it back.
 *
 * No real LLM API calls — generates smart guessed responses based on:
 * - The agent role (controller → delegation JSON, reviewer → pass/fail JSON)
 * - The user's actual message content (for context-aware responses)
 * - Whether tool results are already in the conversation
 *
 * Lifecycle:
 * - On startup: clears testPrompts collection, starts daemon thread
 * - Daemon thread: watches for inserts, processes each prompt
 * - On shutdown: stops cleanly
 *
 * Activated only in test mode (javaclaw.llm.provider=test).
 */
@Component
@ConditionalOnProperty(name = "javaclaw.llm.provider", havingValue = "test")
public class TestLLMConsumer {

    private static final Logger log = LoggerFactory.getLogger(TestLLMConsumer.class);

    private final MongoTemplate mongoTemplate;
    private final TestPromptRepository testPromptRepository;
    private final ObjectMapper objectMapper;

    private volatile boolean running = true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TestLLMConsumer");
        t.setDaemon(true);
        return t;
    });

    public TestLLMConsumer(MongoTemplate mongoTemplate,
                           TestPromptRepository testPromptRepository,
                           ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.testPromptRepository = testPromptRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        testPromptRepository.deleteAll();
        log.info("[TestLLMConsumer] Cleared testPrompts collection on startup");

        executor.submit(this::consumeLoop);
        log.info("[TestLLMConsumer] Started — watching testPrompts for new prompts");
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[TestLLMConsumer] Stopped");
    }

    private void consumeLoop() {
        while (running) {
            try {
                watchChangeStream();
            } catch (Exception e) {
                if (!running) break;
                log.warn("[TestLLMConsumer] Change stream error, falling back to polling: {}", e.getMessage());
                pollForPrompts();
            }
        }
    }

    private void watchChangeStream() {
        var collection = mongoTemplate.getDb().getCollection("testPrompts");
        try (var cursor = collection.watch(
                List.of(Aggregates.match(Filters.eq("operationType", "insert")))
        ).fullDocument(FullDocument.UPDATE_LOOKUP).iterator()) {

            log.debug("[TestLLMConsumer] Change stream opened on testPrompts");
            while (running && cursor.hasNext()) {
                var change = cursor.next();
                Document fullDoc = change.getFullDocument();
                if (fullDoc == null) continue;

                String promptId = fullDoc.getString("_id");
                if (promptId == null) continue;
                if (fullDoc.getString("llmResponse") != null) continue;

                processPrompt(promptId);
            }
        }
    }

    private void pollForPrompts() {
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            try {
                var unprocessed = testPromptRepository.findAllByOrderByCreateTimestampAsc()
                        .stream()
                        .filter(doc -> doc.getLlmResponse() == null)
                        .toList();

                for (var doc : unprocessed) {
                    if (!running) break;
                    processPrompt(doc.getId());
                }
            } catch (Exception e) {
                if (!running) break;
                log.warn("[TestLLMConsumer] Polling error: {}", e.getMessage());
            }
        }
    }

    private void processPrompt(String promptId) {
        var docOpt = testPromptRepository.findById(promptId);
        if (docOpt.isEmpty()) return;

        TestPromptDocument doc = docOpt.get();
        if (doc.getLlmResponse() != null) return;

        String agentId = doc.getAgentId();
        log.info("[TestLLMConsumer] Processing prompt for agent={}, id={}", agentId, promptId);

        long startMs = System.currentTimeMillis();
        try {
            List<Map<String, String>> messages = objectMapper.readValue(
                    doc.getPrompt(), new TypeReference<>() {});

            String response = generateResponse(agentId, messages);
            long durationMs = System.currentTimeMillis() - startMs;

            doc.setLlmResponse(response);
            doc.setDuration(durationMs);
            doc.setResponseTimestamp(Instant.now());
            testPromptRepository.save(doc);

            log.info("[TestLLMConsumer] Response for agent={}, id={}, duration={}ms, length={}",
                    agentId, promptId, durationMs, response.length());

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            String errorMsg = "[ERROR] Response generation failed: " + e.getMessage();
            log.error("[TestLLMConsumer] Failed for agent={}, id={}: {}", agentId, promptId, e.getMessage(), e);

            doc.setLlmResponse(errorMsg);
            doc.setDuration(durationMs);
            doc.setResponseTimestamp(Instant.now());
            testPromptRepository.save(doc);
        }
    }

    /**
     * Generate a plausible response based on agent role and message context.
     * No real LLM — just smart guessing from the prompt structure.
     */
    private String generateResponse(String agentId, List<Map<String, String>> messages) {
        String userMsg = getLastUserMessage(messages);
        boolean hasToolResults = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));

        return switch (agentId) {
            case "controller" -> generateControllerResponse(userMsg, messages);
            case "reviewer" -> generateReviewerResponse(messages, hasToolResults);
            default -> generateSpecialistResponse(agentId, userMsg, hasToolResults);
        };
    }

    private String generateControllerResponse(String userMsg, List<Map<String, String>> messages) {
        // Check available specialists from the system message
        String systemMsg = messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce("", (a, b) -> a + "\n" + b);

        // Route based on keywords in the user message
        String lower = userMsg.toLowerCase();
        String delegate;
        if (lower.contains("code") || lower.contains("file") || lower.contains("read")
                || lower.contains("list") || lower.contains("write") || lower.contains("run")
                || lower.contains("execute") || lower.contains("debug") || lower.contains("explain")) {
            delegate = "coder";
        } else if (lower.contains("sprint") || lower.contains("ticket") || lower.contains("plan")
                || lower.contains("milestone") || lower.contains("backlog")) {
            delegate = "pm";
        } else if (lower.contains("remind") || lower.contains("schedule") || lower.contains("alarm")) {
            delegate = "reminder";
        } else {
            delegate = "generalist";
        }

        return """
                {"delegate": "%s", "subTask": "%s"}"""
                .formatted(delegate, escapeJson(truncate(userMsg, 150)));
    }

    private String generateReviewerResponse(List<Map<String, String>> messages, boolean hasToolResults) {
        // Check what the specialist produced
        String lastAssistant = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce("", (a, b) -> b); // last one

        if (hasToolResults && !lastAssistant.contains("[ERROR]")) {
            return """
                    {"pass": true, "summary": "Specialist completed task with tool results"}""";
        } else if (lastAssistant.contains("[ERROR]")) {
            return """
                    {"pass": false, "feedback": "The specialist encountered an error. Please retry."}""";
        } else {
            return """
                    {"pass": true, "summary": "Test mode — accepted specialist output"}""";
        }
    }

    private String generateSpecialistResponse(String agentId, String userMsg, boolean hasToolResults) {
        // Reminder agent — generate parseable REMINDER lines
        if ("reminder".equals(agentId)) {
            return generateReminderResponse(userMsg);
        }

        if (hasToolResults) {
            // Tool results are in — summarize
            return "[TEST] Agent %s completed the task. Tool results were processed successfully for: %s"
                    .formatted(agentId, truncate(userMsg, 200));
        }

        // First step — emit tool calls based on what the user asked
        String lower = userMsg.toLowerCase();

        // List directory
        if (lower.contains("list") && (lower.contains("file") || lower.contains("dir") || lower.contains("folder"))) {
            String path = extractPath(userMsg);
            return """
                    I'll list the directory contents for you.

                    <tool_call>
                    {"name": "list_directory", "args": {"path": "%s"}}
                    </tool_call>""".formatted(escapeJson(path));
        }

        // Read file — match "read file", "read the pom.xml", "explain the pom.xml", etc.
        if ((lower.contains("read") || lower.contains("explain") || lower.contains("show") || lower.contains("open"))
                && hasFileReference(lower)) {
            String path = extractPath(userMsg);
            return """
                    I'll read that file for you.

                    <tool_call>
                    {"name": "read_file", "args": {"path": "%s"}}
                    </tool_call>""".formatted(escapeJson(path));
        }

        // Search
        if (lower.contains("search") || lower.contains("find") || lower.contains("grep")) {
            String searchPattern = extractSearchPattern(userMsg);
            return """
                    I'll search for that.

                    <tool_call>
                    {"name": "search_files", "args": {"pattern": "%s", "path": "."}}
                    </tool_call>""".formatted(escapeJson(searchPattern));
        }

        // Execute / run
        if (lower.contains("run") || lower.contains("execute") || lower.contains("compile") || lower.contains("build")) {
            return """
                    I'll run that command for you.

                    <tool_call>
                    {"name": "shell_exec", "args": {"command": "echo 'Build completed successfully'"}}
                    </tool_call>""";
        }

        // Default — just acknowledge
        return "[TEST] Agent %s processed request: %s".formatted(agentId, truncate(userMsg, 200));
    }

    /**
     * Generate a response with parseable REMINDER lines for ReminderAgentService.
     * Format: REMINDER: what | WHEN: time | RECURRING: yes/no interval
     */
    private String generateReminderResponse(String userMsg) {
        String lower = userMsg.toLowerCase();
        String when = "tomorrow";
        String recurring = "no";

        if (lower.contains("daily") || lower.contains("every day")) {
            recurring = "yes daily";
            when = "daily";
        } else if (lower.contains("weekly") || lower.contains("every week")) {
            recurring = "yes weekly";
            when = "weekly";
        } else if (lower.contains("hourly") || lower.contains("every hour")) {
            recurring = "yes hourly";
            when = "every hour";
        } else if (lower.contains("morning")) {
            when = "tomorrow morning";
        } else if (lower.contains("evening") || lower.contains("night")) {
            when = "this evening";
        }

        String reminderMsg = truncate(userMsg, 150);
        return "I've set up a reminder for you.\n\n"
                + "REMINDER: " + reminderMsg + " | WHEN: " + when + " | RECURRING: " + recurring + "\n\n"
                + "You'll be notified at the scheduled time.";
    }

    private boolean hasFileReference(String lower) {
        return lower.contains("file") || lower.contains(".xml") || lower.contains(".java")
                || lower.contains(".json") || lower.contains(".md") || lower.contains(".yml")
                || lower.contains(".yaml") || lower.contains(".properties") || lower.contains(".txt")
                || lower.contains("pom") || lower.contains("readme") || lower.contains("config");
    }

    private String extractSearchPattern(String msg) {
        // Try to extract a meaningful search pattern from the message
        String lower = msg.toLowerCase();
        if (lower.contains("java")) return "**/*.java";
        if (lower.contains("test")) return "**/*Test*.java";
        if (lower.contains("xml")) return "**/*.xml";
        if (lower.contains("config")) return "**/application*.yml";
        return "**/*";
    }

    /**
     * Try to extract a file/directory path from the user message.
     */
    private String extractPath(String msg) {
        // 1. Full Windows paths: C:\Users\...
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("([A-Z]:\\\\[\\w\\\\.-]+)")
                .matcher(msg);
        if (m1.find()) return m1.group(1);

        // 2. Full Unix paths: /home/user/...
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(/[\\w/.-]+)")
                .matcher(msg);
        if (m2.find()) return m2.group(1);

        // 3. Relative paths or filenames: src/main/..., pom.xml, README.md
        java.util.regex.Matcher m3 = java.util.regex.Pattern
                .compile("\\b([\\w.-]+(?:/[\\w.-]+)*\\.\\w{1,10})\\b")
                .matcher(msg);
        if (m3.find()) return m3.group(1);

        return ".";
    }

    private String getLastUserMessage(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return messages.get(i).getOrDefault("content", "");
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
