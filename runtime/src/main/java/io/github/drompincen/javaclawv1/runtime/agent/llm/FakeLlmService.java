package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fake LLM service for testing without an API key.
 * Simulates the multi-agent controller→specialist→checker flow
 * with realistic responses based on the current agent and user messages.
 *
 * Activate with: JAVACLAW_LLM_PROVIDER=fake
 */
@Service
@ConditionalOnProperty(name = "javaclaw.llm.provider", havingValue = "fake")
public class FakeLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(FakeLlmService.class);

    @Override
    public Flux<String> streamResponse(AgentState state) {
        String response = generateResponse(state);
        log.debug("[FAKE LLM] agent={}, response length={}", state.getCurrentAgentId(), response.length());
        // Stream token-by-token with slight delay to simulate real LLM
        String[] words = response.split("(?<=\\s)");
        return Flux.fromArray(words)
                .delayElements(Duration.ofMillis(30));
    }

    @Override
    public String blockingResponse(AgentState state) {
        return generateResponse(state);
    }

    private String generateResponse(AgentState state) {
        String agentId = state.getCurrentAgentId();
        String lastUserMessage = getLastUserMessage(state);
        String threadId = state.getThreadId();

        if (agentId == null) agentId = "unknown";

        return switch (agentId) {
            case "controller" -> generateControllerResponse(lastUserMessage);
            case "pm" -> generatePmResponse(lastUserMessage, threadId);
            case "coder" -> generateCoderResponse(lastUserMessage);
            case "reviewer" -> generateReviewerResponse(lastUserMessage);
            default -> generateDefaultResponse(agentId, lastUserMessage);
        };
    }

    private String generateControllerResponse(String userMessage) {
        if (userMessage == null) userMessage = "";
        String lower = userMessage.toLowerCase();

        // Route to appropriate specialist
        if (lower.contains("project") || lower.contains("sprint") || lower.contains("plan")
                || lower.contains("ticket") || lower.contains("milestone") || lower.contains("team")
                || lower.contains("resource") || lower.contains("stakeholder")) {
            return """
                    {"delegate": "pm", "subTask": "Answer the user's project management question: %s"}"""
                    .formatted(truncate(userMessage, 200));
        }

        if (lower.contains("code") || lower.contains("bug") || lower.contains("fix")
                || lower.contains("implement") || lower.contains("write") || lower.contains("debug")
                || lower.contains("function") || lower.contains("class") || lower.contains("test")) {
            return """
                    {"delegate": "coder", "subTask": "Handle the coding request: %s"}"""
                    .formatted(truncate(userMessage, 200));
        }

        // Default: answer directly for simple questions
        return """
                {"delegate": "pm", "subTask": "Help the user with: %s"}"""
                .formatted(truncate(userMessage, 200));
    }

    private String generatePmResponse(String userMessage, String threadId) {
        if (userMessage == null) userMessage = "";
        String lower = userMessage.toLowerCase();

        if (lower.contains("what project")) {
            return """
                    This is the project associated with thread %s.

                    **Project Overview:**
                    - **Status:** ACTIVE
                    - **Current Sprint:** Sprint 1 (Week 1 of 2)
                    - **Team Size:** Not yet configured

                    **Recommended Next Steps:**
                    1. Define project goals and success criteria
                    2. Create initial backlog with user stories
                    3. Set up sprint cadence (I recommend 2-week sprints)
                    4. Identify team members and their capacity
                    5. Schedule a kickoff meeting

                    I can help with any of these — just ask!"""
                    .formatted(threadId != null ? threadId.substring(0, Math.min(8, threadId.length())) : "unknown");
        }

        if (lower.contains("sprint") || lower.contains("plan")) {
            return """
                    **Sprint Planning Summary:**

                    Based on the current project state, here's my recommendation:

                    **Sprint Goal:** Establish project foundations and first deliverable
                    **Duration:** 2 weeks
                    **Capacity:** TBD (please add team members)

                    **Proposed Tickets:**
                    1. [HIGH] Set up project repository and CI/CD pipeline
                    2. [HIGH] Define core data model and API contracts
                    3. [MEDIUM] Implement authentication and authorization
                    4. [MEDIUM] Create initial UI wireframes
                    5. [LOW] Write project README and developer onboarding guide

                    Would you like me to create these as tickets?""";
        }

        return """
                I've analyzed your request: "%s"

                **Findings:**
                - The project is currently in ACTIVE status
                - No blocking issues identified
                - Team capacity and timeline need to be established

                **Recommendations:**
                1. Clarify the project scope and deliverables
                2. Set up tracking milestones
                3. Assign ownership for key workstreams

                Let me know how you'd like to proceed!"""
                .formatted(truncate(userMessage, 100));
    }

    private String generateCoderResponse(String userMessage) {
        return """
                I've analyzed the coding request: "%s"

                **Approach:**
                1. Reviewed existing codebase structure
                2. Identified the relevant files and dependencies
                3. Implemented the requested changes

                **Changes Made:**
                - Modified the target files according to requirements
                - Added appropriate error handling
                - Ensured backward compatibility

                **Testing:**
                - All existing tests continue to pass
                - Added unit tests for new functionality

                The implementation is complete. Let me know if you need any adjustments."""
                .formatted(truncate(userMessage, 100));
    }

    private String generateReviewerResponse(String lastMessage) {
        return """
                {"pass": true, "summary": "The specialist's response is comprehensive, well-structured, and directly addresses the user's request. The recommendations are actionable and appropriate for the project context."}""";
    }

    private String generateDefaultResponse(String agentId, String userMessage) {
        return """
                [Agent: %s] I've processed your request: "%s"

                The task has been completed successfully. Let me know if you need anything else."""
                .formatted(agentId, truncate(userMessage, 100));
    }

    private String getLastUserMessage(AgentState state) {
        List<Map<String, String>> messages = state.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, String> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                return msg.getOrDefault("content", "");
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
