package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ObjectMapper objectMapper;
    private ScenarioConfig config;
    private ScenarioConfigV2 configV2;
    private boolean v2;

    /** Per-agent response counter for pipeline mode (cycles through responses in order) */
    private final Map<String, AtomicInteger> agentResponseCounters = new ConcurrentHashMap<>();

    /** Current project ID for template resolution in pipeline scenario responses */
    private volatile String currentProjectId;

    /** Current step index for step-scoped response collection (-1 = collect from all steps) */
    private volatile int currentStepIndex = -1;

    /** Thread IDs discovered after seed steps, for {{threads[N].threadId}} template resolution */
    private volatile List<String> threadIds = new ArrayList<>();

    public ScenarioService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        String filePath = System.getProperty("javaclaw.scenario.file");
        if (filePath != null && !filePath.isBlank()) {
            loadScenario(filePath);
        }
    }

    public void loadScenario(String filePath) {
        try {
            File file = new File(filePath);
            JsonNode root = objectMapper.readTree(file);

            if (root.has("schemaVersion") && root.get("schemaVersion").asInt() == 2) {
                configV2 = objectMapper.treeToValue(root, ScenarioConfigV2.class);
                v2 = true;
                log.info("[Scenario] Loaded V2 scenario '{}' with {} steps from {}",
                        configV2.projectName(), configV2.steps().size(), filePath);
            } else {
                config = objectMapper.treeToValue(root, ScenarioConfig.class);
                v2 = false;
                log.info("[Scenario] Loaded scenario '{}' with {} steps from {}",
                        config.projectName(), config.steps().size(), filePath);
            }
        } catch (Exception e) {
            log.error("[Scenario] Failed to load scenario from {}: {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * Looks up a response for the given (userQuery, agentName) pair.
     * Works for both v1 and v2 scenarios (v2 steps reuse AgentResponse).
     * Falls back to agent-name-only cycling for pipeline sessions where
     * the userQuery is a dynamically built prompt that won't match any step.
     */
    public String getResponseForAgent(String userQuery, String agentName) {
        if (userQuery == null || agentName == null) return null;

        List<? extends Object> steps = v2 ? getV2Steps() : getSteps();
        if (steps == null || steps.isEmpty()) return null;

        // Exact match
        for (Object step : steps) {
            List<ScenarioConfig.AgentResponse> responses = getAgentResponses(step);
            if (responses == null) continue;
            if (userQuery.equals(getUserQuery(step))) {
                String response = findAgentResponse(responses, agentName);
                if (response != null) return resolveTemplates(response);
                break; // Step matched but agent exhausted — fall through to pipeline fallback
            }
        }

        // Case-insensitive fallback
        for (Object step : steps) {
            List<ScenarioConfig.AgentResponse> responses = getAgentResponses(step);
            if (responses == null) continue;
            if (userQuery.equalsIgnoreCase(getUserQuery(step))) {
                String response = findAgentResponse(responses, agentName);
                if (response != null) return resolveTemplates(response);
                break; // Step matched but agent exhausted — fall through to pipeline fallback
            }
        }

        // Pipeline fallback: no exact query match → cycle through responses by agent name
        String pipelineResponse = getNextResponseForAgent(agentName);
        if (pipelineResponse != null) {
            log.info("[Scenario] Pipeline fallback: returning response #{} for agent '{}'",
                    agentResponseCounters.get(agentName).get(), agentName);
            pipelineResponse = resolveTemplates(pipelineResponse);
        }
        return pipelineResponse;
    }

    /**
     * Get the next response for an agent, cycling through all responses across all steps.
     * Used for pipeline sessions where the dynamically built prompt won't match any step's userQuery.
     */
    public String getNextResponseForAgent(String agentName) {
        // Collect all responses for this agent across all steps
        List<String> allResponses = collectAllResponsesForAgent(agentName);
        if (allResponses.isEmpty()) return null;

        AtomicInteger counter = agentResponseCounters.computeIfAbsent(agentName, k -> new AtomicInteger(0));
        int idx = counter.getAndIncrement();
        if (idx >= allResponses.size()) return null; // exhausted all responses
        return allResponses.get(idx);
    }

    private List<String> collectAllResponsesForAgent(String agentName) {
        List<String> result = new ArrayList<>();
        List<? extends Object> steps = v2 ? getV2Steps() : getSteps();
        if (steps == null) return result;

        for (int i = 0; i < steps.size(); i++) {
            // When step-scoped, only collect from the current step
            if (currentStepIndex >= 0 && i != currentStepIndex) continue;

            Object step = steps.get(i);
            List<ScenarioConfig.AgentResponse> responses = getAgentResponses(step);
            if (responses == null) continue;
            for (ScenarioConfig.AgentResponse ar : responses) {
                if (agentName.equals(ar.agentName()) && ar.responseFallback() != null) {
                    result.add(ar.responseFallback());
                }
            }
        }
        return result;
    }

    private String getUserQuery(Object step) {
        if (step instanceof ScenarioConfig.ScenarioStep s) return s.userQuery();
        if (step instanceof ScenarioConfigV2.Step s) return s.userQuery();
        return null;
    }

    private List<ScenarioConfig.AgentResponse> getAgentResponses(Object step) {
        if (step instanceof ScenarioConfig.ScenarioStep s) return s.agentResponses();
        if (step instanceof ScenarioConfigV2.Step s) return s.agentResponses();
        return null;
    }

    private String findAgentResponse(List<ScenarioConfig.AgentResponse> responses, String agentName) {
        // Collect all responses for this agent in order
        List<String> agentResponses = new ArrayList<>();
        for (ScenarioConfig.AgentResponse ar : responses) {
            if (agentName.equals(ar.agentName()) && ar.responseFallback() != null) {
                agentResponses.add(ar.responseFallback());
            }
        }
        if (agentResponses.isEmpty()) return null;

        // Always use counter-based cycling — this ensures each response is delivered
        // exactly once, even for single-response agents. When exhausted, return null
        // so the caller can fall through to pipeline fallback or TestLLMConsumer.
        AtomicInteger counter = agentResponseCounters.computeIfAbsent(agentName, k -> new AtomicInteger(0));
        int idx = counter.getAndIncrement();
        if (idx >= agentResponses.size()) return null; // exhausted — let caller handle fallback
        return agentResponses.get(idx);
    }

    public String getProjectName() {
        if (v2) return configV2 != null ? configV2.projectName() : null;
        return config != null ? config.projectName() : null;
    }

    public List<ScenarioConfig.ScenarioStep> getSteps() {
        return config != null ? config.steps() : List.of();
    }

    public boolean isV2() {
        return v2;
    }

    public ScenarioConfigV2 getV2Config() {
        return configV2;
    }

    public List<ScenarioConfigV2.Step> getV2Steps() {
        return configV2 != null ? configV2.steps() : List.of();
    }

    public boolean isLoaded() {
        return config != null || configV2 != null;
    }

    public void reset() {
        this.config = null;
        this.configV2 = null;
        this.v2 = false;
        this.agentResponseCounters.clear();
        this.currentProjectId = null;
        this.currentStepIndex = -1;
        this.threadIds = new ArrayList<>();
    }

    /** Reset only the per-agent response counters (for pipeline retries within the same scenario). */
    public void resetCounters() {
        agentResponseCounters.clear();
    }

    /** Set the current project ID for template resolution in scenario responses. */
    public void setProjectId(String projectId) {
        this.currentProjectId = projectId;
    }

    /** Set the current step index for step-scoped response collection. */
    public void setCurrentStepIndex(int stepIndex) {
        this.currentStepIndex = stepIndex;
    }

    /** Set thread IDs for {{threads[N].threadId}} template resolution. */
    public void setThreadIds(List<String> ids) {
        this.threadIds = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
    }

    /** Replace {{projectId}} and {{threads[N].threadId}} templates in scenario responses. */
    private String resolveTemplates(String response) {
        if (response == null) return response;
        if (currentProjectId != null) {
            response = response.replace("{{projectId}}", currentProjectId);
        }
        // Resolve {{threads[N].threadId}} patterns
        if (!threadIds.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{threads\\[(\\d+)]\\.(\\w+)}}").matcher(response);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                String field = m.group(2);
                String replacement = "";
                if ("threadId".equals(field) && idx < threadIds.size()) {
                    replacement = threadIds.get(idx);
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            response = sb.toString();
        }
        return response;
    }

    // Package-private for testing
    void setConfig(ScenarioConfig config) {
        this.config = config;
        this.v2 = false;
    }

    void setConfigV2(ScenarioConfigV2 configV2) {
        this.configV2 = configV2;
        this.v2 = true;
    }
}
