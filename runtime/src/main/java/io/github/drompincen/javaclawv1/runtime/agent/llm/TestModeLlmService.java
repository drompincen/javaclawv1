package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.persistence.document.TestPromptDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TestPromptRepository;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    static final long DEFAULT_TIMEOUT_MS = 15_000; // 15 seconds

    private final TestPromptRepository testPromptRepository;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;
    private final ScenarioService scenarioService;

    @Autowired
    public TestModeLlmService(TestPromptRepository testPromptRepository,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) ScenarioService scenarioService) {
        this(testPromptRepository, objectMapper, DEFAULT_TIMEOUT_MS, scenarioService);
    }

    TestModeLlmService(TestPromptRepository testPromptRepository,
                       ObjectMapper objectMapper,
                       long timeoutMs) {
        this(testPromptRepository, objectMapper, timeoutMs, null);
    }

    TestModeLlmService(TestPromptRepository testPromptRepository,
                       ObjectMapper objectMapper,
                       long timeoutMs,
                       ScenarioService scenarioService) {
        this.testPromptRepository = testPromptRepository;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
        this.scenarioService = scenarioService;
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
        String userQuery = TestResponseGenerator.getLastUserMessage(state.getMessages());

        // Check scenario-driven response first
        boolean hasToolResults = state.getMessages().stream()
                .anyMatch(m -> "tool".equals(m.get("role")));
        if (scenarioService != null) {
            String scenarioResponse = scenarioService.getResponseForAgent(userQuery, agentId);
            // Skip scenario response if it contains <tool_call> and messages already have
            // tool results — this prevents an infinite loop where the specialist keeps
            // re-executing the same tool call from the scenario on every loop-back.
            if (scenarioResponse != null
                    && !(hasToolResults && scenarioResponse.contains("<tool_call>"))) {
                log.info("[TEST LLM] Scenario match for agent={}, userQuery='{}' — returning scenario response",
                        agentId, TestResponseGenerator.truncate(userQuery, 80));

                // Write to testPrompts for observability
                enforcePromptCap();
                TestPromptDocument doc = new TestPromptDocument();
                doc.setId(UUID.randomUUID().toString());
                doc.setAgentId(agentId);
                doc.setSessionId(sessionId);
                doc.setUserQuery(userQuery);
                doc.setResponseFallback(scenarioResponse);
                doc.setLlmResponse(scenarioResponse);
                doc.setDuration(0L);
                doc.setCreateTimestamp(Instant.now());
                doc.setResponseTimestamp(Instant.now());
                try {
                    doc.setPrompt(objectMapper.writeValueAsString(state.getMessages()));
                } catch (Exception e) {
                    doc.setPrompt("[serialization error]");
                }
                testPromptRepository.save(doc);
                return scenarioResponse;
            }
        }

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

        // Pre-compute fallback response
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
        long deadline = startMs + timeoutMs;
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
    public String getProviderInfo() {
        String anthropicKey = System.getProperty("spring.ai.anthropic.api-key", "");
        String openaiKey = System.getProperty("spring.ai.openai.api-key", "");
        boolean hasAnthropic = !anthropicKey.isBlank() && !anthropicKey.contains("placeholder");
        boolean hasOpenai = !openaiKey.isBlank() && !openaiKey.contains("placeholder");
        if (hasAnthropic) return "Claude Sonnet (Test Mode)";
        if (hasOpenai) return "GPT-4o (Test Mode)";
        return "Test Mode (No API Key)";
    }

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
