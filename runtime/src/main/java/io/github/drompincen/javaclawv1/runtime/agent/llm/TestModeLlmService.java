package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.persistence.document.TestPromptDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TestPromptRepository;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-mode LLM service that routes prompts through MongoDB.
 *
 * Flow:
 * 1. Serialize agent state messages to JSON
 * 2. Write a TestPromptDocument to the testPrompts collection
 * 3. Poll until TestLLMConsumer fills in the llmResponse field
 * 4. Return the response
 *
 * This allows real Claude Sonnet responses while making every
 * prompt/response pair observable in MongoDB.
 *
 * Activate with: jbang javaclaw.java --testmode
 */
@Service
@ConditionalOnProperty(name = "javaclaw.llm.provider", havingValue = "test")
public class TestModeLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(TestModeLlmService.class);
    private static final int MAX_PROMPTS = 20;
    private static final long POLL_INTERVAL_MS = 500;
    private static final long TIMEOUT_MS = 15_000; // 15 seconds

    private final TestPromptRepository testPromptRepository;
    private final ObjectMapper objectMapper;

    public TestModeLlmService(TestPromptRepository testPromptRepository,
                              ObjectMapper objectMapper) {
        this.testPromptRepository = testPromptRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<String> streamResponse(AgentState state) {
        String response = blockingResponse(state);
        return Flux.just(response);
    }

    @Override
    public String blockingResponse(AgentState state) {
        String agentId = state.getCurrentAgentId() != null ? state.getCurrentAgentId() : "unknown";
        String sessionId = state.getThreadId();

        // Enforce cap — delete oldest if at limit
        enforcePromptCap();

        // Serialize messages to JSON
        String promptJson;
        try {
            promptJson = objectMapper.writeValueAsString(state.getMessages());
        } catch (Exception e) {
            log.error("Failed to serialize prompt for agent {}", agentId, e);
            return "[ERROR] Failed to serialize prompt: " + e.getMessage();
        }

        // Pre-compute fallback response and extract user query
        String userQuery = TestResponseGenerator.getLastUserMessage(state.getMessages());
        String fallback = TestResponseGenerator.generateResponse(agentId, state.getMessages());

        // Write prompt document
        TestPromptDocument doc = new TestPromptDocument();
        doc.setId(UUID.randomUUID().toString());
        doc.setPrompt(promptJson);
        doc.setAgentId(agentId);
        doc.setSessionId(sessionId);
        doc.setUserQuery(userQuery);
        doc.setResponseFallback(fallback);
        doc.setCreateTimestamp(Instant.now());
        testPromptRepository.save(doc);

        log.info("[TEST LLM] Wrote prompt for agent={}, sessionId={}, promptId={}",
                agentId, sessionId, doc.getId());

        // Poll for response
        long startMs = System.currentTimeMillis();
        long deadline = startMs + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "[ERROR] Interrupted while waiting for LLM response";
            }

            var updated = testPromptRepository.findById(doc.getId());
            if (updated.isPresent() && updated.get().getLlmResponse() != null) {
                String response = updated.get().getLlmResponse();
                Long duration = updated.get().getDuration();
                log.info("[TEST LLM] Got response for agent={}, promptId={}, duration={}ms, length={}",
                        agentId, doc.getId(), duration, response.length());
                return response;
            }
        }

        // Timeout — use pre-computed fallback instead of error
        log.info("[TEST LLM] Using fallback for agent={}, promptId={}", agentId, doc.getId());
        doc.setLlmResponse(fallback);
        doc.setDuration(System.currentTimeMillis() - startMs);
        doc.setResponseTimestamp(Instant.now());
        testPromptRepository.save(doc);
        return fallback;
    }

    @Override
    public String getProviderInfo() { return "Test Mode"; }

    private void enforcePromptCap() {
        long count = testPromptRepository.count();
        if (count >= MAX_PROMPTS) {
            List<TestPromptDocument> oldest = testPromptRepository.findAllByOrderByCreateTimestampAsc();
            int toDelete = (int) (count - MAX_PROMPTS + 1);
            for (int i = 0; i < toDelete && i < oldest.size(); i++) {
                testPromptRepository.deleteById(oldest.get(i).getId());
            }
            log.debug("[TEST LLM] Deleted {} oldest prompts to stay under cap of {}", toDelete, MAX_PROMPTS);
        }
    }
}
