package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ObjectMapper objectMapper;
    private ScenarioConfig config;

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

    void loadScenario(String filePath) {
        try {
            config = objectMapper.readValue(new File(filePath), ScenarioConfig.class);
            log.info("[Scenario] Loaded scenario '{}' with {} steps from {}",
                    config.projectName(), config.steps().size(), filePath);
        } catch (Exception e) {
            log.error("[Scenario] Failed to load scenario from {}: {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * Looks up a response for the given (userQuery, agentName) pair.
     * Tries exact match first, then case-insensitive.
     */
    public String getResponseForAgent(String userQuery, String agentName) {
        if (config == null || userQuery == null || agentName == null) return null;

        for (ScenarioConfig.ScenarioStep step : config.steps()) {
            if (step.agentResponses() == null) continue;

            // Exact match
            if (userQuery.equals(step.userQuery())) {
                return findAgentResponse(step.agentResponses(), agentName);
            }
        }

        // Case-insensitive fallback
        for (ScenarioConfig.ScenarioStep step : config.steps()) {
            if (step.agentResponses() == null) continue;

            if (userQuery.equalsIgnoreCase(step.userQuery())) {
                return findAgentResponse(step.agentResponses(), agentName);
            }
        }

        return null;
    }

    private String findAgentResponse(List<ScenarioConfig.AgentResponse> responses, String agentName) {
        for (ScenarioConfig.AgentResponse ar : responses) {
            if (agentName.equals(ar.agentName())) {
                return ar.responseFallback();
            }
        }
        return null;
    }

    public String getProjectName() {
        return config != null ? config.projectName() : null;
    }

    public List<ScenarioConfig.ScenarioStep> getSteps() {
        return config != null ? config.steps() : List.of();
    }

    public boolean isLoaded() {
        return config != null;
    }

    // Package-private for testing
    void setConfig(ScenarioConfig config) {
        this.config = config;
    }
}
