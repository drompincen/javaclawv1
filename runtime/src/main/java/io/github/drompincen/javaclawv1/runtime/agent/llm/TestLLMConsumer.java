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

            String response = TestResponseGenerator.generateResponse(agentId, messages);
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

}
