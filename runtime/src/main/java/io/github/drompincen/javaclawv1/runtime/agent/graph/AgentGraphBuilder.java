package io.github.drompincen.javaclawv1.runtime.agent.graph;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.agent.LogService;
import io.github.drompincen.javaclawv1.runtime.agent.approval.ApprovalService;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import io.github.drompincen.javaclawv1.runtime.tools.Tool;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolRegistry;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import io.github.drompincen.javaclawv1.protocol.api.ApprovalRequestDto;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphBuilder.class);
    private static final int MAX_STEPS = 50;
    private static final int MAX_RETRIES = 3;

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final EventService eventService;
    private final ApprovalService approvalService;
    private final MongoCheckpointSaver checkpointSaver;
    private final AgentRepository agentRepository;
    private final LogService logService;
    private final ObjectMapper objectMapper;

    public AgentGraphBuilder(LlmService llmService,
                             ToolRegistry toolRegistry,
                             EventService eventService,
                             ApprovalService approvalService,
                             MongoCheckpointSaver checkpointSaver,
                             AgentRepository agentRepository,
                             LogService logService,
                             ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.checkpointSaver = checkpointSaver;
        this.agentRepository = agentRepository;
        this.logService = logService;
        this.objectMapper = objectMapper;
    }

    public AgentState runGraph(AgentState initialState) {
        AgentState state = initialState;
        List<AgentDocument> agents = agentRepository.findByEnabledTrue();

        // If no agents defined, fall back to single-agent mode
        if (agents.isEmpty()) {
            return runSingleAgent(state);
        }

        AgentDocument controller = agents.stream()
                .filter(a -> a.getRole() == AgentRole.CONTROLLER)
                .findFirst().orElse(null);

        if (controller == null) {
            return runSingleAgent(state);
        }

        // Multi-agent orchestration loop
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            // Step 1: Controller decides routing
            state = state.withAgent(controller.getAgentId());
            eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                    Map.of("toAgent", controller.getAgentId()));

            String specialistList = agents.stream()
                    .filter(a -> a.getRole() == AgentRole.SPECIALIST)
                    .map(a -> a.getAgentId() + ": " + a.getDescription())
                    .collect(Collectors.joining("\n"));

            // Inject agent list context
            state = state.withMessage("system", controller.getSystemPrompt()
                    + "\n\nAvailable specialists:\n" + specialistList);

            String controllerResponse = callLlmForAgent(state, controller);
            state = state.withMessage("assistant", controllerResponse != null ? controllerResponse : "");

            if (controllerResponse == null || controllerResponse.isBlank()) break;

            // Parse controller decision
            String delegateAgentId = parseDelegate(controllerResponse);
            String directResponse = parseDirectResponse(controllerResponse);

            String specialistOutput;
            if (delegateAgentId != null) {
                // Step 2: Delegate to specialist
                AgentDocument specialist = agents.stream()
                        .filter(a -> a.getAgentId().equals(delegateAgentId))
                        .findFirst().orElse(null);

                if (specialist == null) {
                    eventService.emit(state.getThreadId(), EventType.ERROR,
                            Map.of("message", "Specialist not found: " + delegateAgentId));
                    break;
                }

                eventService.emit(state.getThreadId(), EventType.AGENT_DELEGATED,
                        Map.of("targetAgentId", delegateAgentId, "subTask", parseSubTask(controllerResponse)));

                state = state.withAgent(specialist.getAgentId());
                eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                        Map.of("fromAgent", controller.getAgentId(), "toAgent", specialist.getAgentId()));

                // Run specialist agent steps
                state = state.withMessage("system", specialist.getSystemPrompt());
                state = runAgentSteps(state, specialist);

                specialistOutput = getLastAssistantMessage(state);
                eventService.emit(state.getThreadId(), EventType.AGENT_RESPONSE,
                        Map.of("agentId", specialist.getAgentId(),
                                "response", truncate(specialistOutput, 500)));
            } else {
                specialistOutput = directResponse != null ? directResponse : controllerResponse;
                eventService.emit(state.getThreadId(), EventType.AGENT_RESPONSE,
                        Map.of("agentId", controller.getAgentId(), "response", truncate(specialistOutput, 500)));
            }

            // Step 3: Checker validates
            AgentDocument checker = agents.stream()
                    .filter(a -> a.getRole() == AgentRole.CHECKER)
                    .findFirst().orElse(null);

            if (checker == null) {
                // No checker → accept as-is
                eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_PASSED,
                        Map.of("summary", "No checker configured, accepting result"));
                break;
            }

            eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_REQUESTED,
                    Map.of("agentId", checker.getAgentId()));

            state = state.withAgent(checker.getAgentId());
            eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                    Map.of("toAgent", checker.getAgentId()));

            state = state.withMessage("system", checker.getSystemPrompt());
            state = state.withMessage("user",
                    "Review the work completed above. The specialist's final output was:\n" + specialistOutput);

            String checkerResponse = callLlmForAgent(state, checker);
            state = state.withMessage("assistant", checkerResponse != null ? checkerResponse : "");

            boolean passed = parseCheckPassed(checkerResponse);
            if (passed) {
                eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_PASSED,
                        Map.of("agentId", checker.getAgentId(),
                                "summary", parseCheckSummary(checkerResponse)));
                break;
            } else {
                String feedback = parseCheckFeedback(checkerResponse);
                eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_FAILED,
                        Map.of("agentId", checker.getAgentId(), "feedback", feedback,
                                "retry", retry + 1, "maxRetries", MAX_RETRIES));

                if (retry < MAX_RETRIES - 1) {
                    state = state.withMessage("user",
                            "The reviewer rejected the work with feedback: " + feedback
                                    + "\nPlease address this feedback and try again.");
                }
            }
        }

        return state;
    }

    /**
     * Fallback single-agent mode (original behavior)
     */
    private AgentState runSingleAgent(AgentState state) {
        for (int step = state.getStepNo(); step <= MAX_STEPS; step++) {
            state = state.withStep(step);
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_STARTED, Map.of("step", step));

            String response = callLlm(state);
            state = state.withMessage("assistant", response != null ? response : "");

            if (response == null || response.isBlank()) {
                eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", step, "done", true));
                break;
            }

            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                    Map.of("step", step, "done", false, "response", response));

            checkpointSaver.save(state.getThreadId(), step, state);

            if (state.getPendingToolCalls().isEmpty()) {
                eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", step, "done", true));
                break;
            }
        }
        return state;
    }

    /**
     * Run multiple agent steps for a specialist agent
     */
    private AgentState runAgentSteps(AgentState state, AgentDocument agent) {
        for (int step = state.getStepNo(); step <= MAX_STEPS; step++) {
            state = state.withStep(step);
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_STARTED,
                    Map.of("step", step, "agentId", agent.getAgentId()));

            String response = callLlmForAgent(state, agent);
            state = state.withMessage("assistant", response != null ? response : "");

            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                    Map.of("step", step, "agentId", agent.getAgentId(),
                            "done", response == null || response.isBlank() || state.getPendingToolCalls().isEmpty()));

            checkpointSaver.save(state.getThreadId(), step, state);

            if (response == null || response.isBlank() || state.getPendingToolCalls().isEmpty()) {
                break;
            }
        }
        return state;
    }

    private String callLlm(AgentState state) {
        long startTime = System.currentTimeMillis();
        try {
            StringBuilder sb = new StringBuilder();
            llmService.streamResponse(state)
                    .doOnNext(token -> eventService.emit(state.getThreadId(),
                            EventType.MODEL_TOKEN_DELTA, Map.of("token", token)))
                    .doOnNext(sb::append)
                    .blockLast();
            String result = sb.toString();
            long durationMs = System.currentTimeMillis() - startTime;
            logService.recordLlmInteraction(state.getThreadId(), state.getCurrentAgentId(),
                    "anthropic", null, state.getMessages().size(), 0,
                    estimateTokens(result), durationMs, true, null);
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("LLM call failed for thread {}", state.getThreadId(), e);
            logService.recordLlmInteraction(state.getThreadId(), state.getCurrentAgentId(),
                    "anthropic", null, state.getMessages().size(), 0, 0,
                    durationMs, false, e.getMessage());
            logService.logError("AgentGraphBuilder", state.getThreadId(),
                    "LLM call failed: " + e.getMessage(), e, Map.of());
            eventService.emit(state.getThreadId(), EventType.ERROR,
                    Map.of("message", "LLM call failed: " + e.getMessage()));
            return null;
        }
    }

    private String callLlmForAgent(AgentState state, AgentDocument agent) {
        long startTime = System.currentTimeMillis();
        try {
            StringBuilder sb = new StringBuilder();
            llmService.streamResponse(state)
                    .doOnNext(token -> eventService.emit(state.getThreadId(),
                            EventType.MODEL_TOKEN_DELTA,
                            Map.of("token", token, "agentId", agent.getAgentId())))
                    .doOnNext(sb::append)
                    .blockLast();
            String result = sb.toString();
            long durationMs = System.currentTimeMillis() - startTime;
            logService.recordLlmInteraction(state.getThreadId(), agent.getAgentId(),
                    "anthropic", null, state.getMessages().size(), 0,
                    estimateTokens(result), durationMs, true, null);
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("LLM call failed for agent {} in thread {}",
                    agent.getAgentId(), state.getThreadId(), e);
            logService.recordLlmInteraction(state.getThreadId(), agent.getAgentId(),
                    "anthropic", null, state.getMessages().size(), 0, 0,
                    durationMs, false, e.getMessage());
            logService.logError("AgentGraphBuilder", state.getThreadId(),
                    "LLM call failed for " + agent.getAgentId() + ": " + e.getMessage(), e, Map.of());
            eventService.emit(state.getThreadId(), EventType.ERROR,
                    Map.of("message", "LLM call failed for " + agent.getAgentId() + ": " + e.getMessage()));
            return null;
        }
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }

    public ToolResult executeTool(AgentState state, String toolName, JsonNode input) {
        Optional<Tool> toolOpt = toolRegistry.get(toolName);
        if (toolOpt.isEmpty()) {
            return ToolResult.failure("Tool not found: " + toolName);
        }

        Tool tool = toolOpt.get();
        Set<ToolRiskProfile> risks = tool.riskProfiles();

        boolean needsApproval = risks.contains(ToolRiskProfile.WRITE_FILES)
                || risks.contains(ToolRiskProfile.EXEC_SHELL);

        if (needsApproval) {
            String approvalId = approvalService.createRequest(state.getThreadId(), toolName, input);
            eventService.emit(state.getThreadId(), EventType.APPROVAL_REQUESTED,
                    Map.of("approvalId", approvalId, "tool", toolName));

            Optional<ApprovalRequestDto.ApprovalStatus> response =
                    approvalService.waitForResponse(approvalId, Duration.ofMinutes(5));

            if (response.isEmpty() || response.get() == ApprovalRequestDto.ApprovalStatus.DENIED) {
                eventService.emit(state.getThreadId(), EventType.TOOL_CALL_DENIED,
                        Map.of("tool", toolName, "reason", "denied_or_timeout"));
                return ToolResult.failure("Tool call denied or timed out");
            }
            eventService.emit(state.getThreadId(), EventType.APPROVAL_RESPONDED,
                    Map.of("approvalId", approvalId, "status", "APPROVED"));
        }

        eventService.emit(state.getThreadId(), EventType.TOOL_CALL_STARTED, Map.of("tool", toolName));

        ToolStream stream = new ToolStream() {
            @Override public void stdoutDelta(String text) {
                eventService.emit(state.getThreadId(), EventType.TOOL_STDOUT_DELTA,
                        Map.of("tool", toolName, "text", text));
            }
            @Override public void stderrDelta(String text) {
                eventService.emit(state.getThreadId(), EventType.TOOL_STDERR_DELTA,
                        Map.of("tool", toolName, "text", text));
            }
            @Override public void progress(int percent, String message) {
                eventService.emit(state.getThreadId(), EventType.TOOL_PROGRESS,
                        Map.of("tool", toolName, "percent", percent, "message", message));
            }
            @Override public void artifactCreated(String type, String uriOrRef) {}
        };

        ToolContext ctx = new ToolContext(state.getThreadId(), Path.of("."), Map.of());
        ToolResult result = tool.execute(ctx, input, stream);

        eventService.emit(state.getThreadId(), EventType.TOOL_RESULT,
                Map.of("tool", toolName, "success", result.success()));

        return result;
    }

    // --- JSON response parsing helpers ---

    private String parseDelegate(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("delegate")) return node.get("delegate").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private String parseDirectResponse(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("respond")) return node.get("respond").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private String parseSubTask(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("subTask")) return node.get("subTask").asText();
        } catch (Exception ignored) {}
        return "delegated task";
    }

    private boolean parseCheckPassed(String response) {
        if (response == null) return true;
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("pass")) return node.get("pass").asBoolean();
        } catch (Exception ignored) {}
        return !response.toLowerCase().contains("\"pass\": false")
                && !response.toLowerCase().contains("\"pass\":false");
    }

    private String parseCheckSummary(String response) {
        if (response == null) return "";
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("summary")) return node.get("summary").asText();
        } catch (Exception ignored) {}
        return truncate(response, 200);
    }

    private String parseCheckFeedback(String response) {
        if (response == null) return "no feedback provided";
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.has("feedback")) return node.get("feedback").asText();
        } catch (Exception ignored) {}
        return truncate(response, 500);
    }

    private String getLastAssistantMessage(AgentState state) {
        List<Map<String, String>> msgs = state.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("assistant".equals(msgs.get(i).get("role"))) {
                return msgs.get(i).get("content");
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
