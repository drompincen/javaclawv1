package io.github.drompincen.javaclawv1.runtime.agent.graph;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.agent.ReminderAgentService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AgentGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphBuilder.class);
    private static final int MAX_STEPS = 50;
    private static final int MAX_RETRIES = 3;

    /** Pattern to match inline tool calls: <tool_call>{"name":"...","args":{...}}</tool_call> */
    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final EventService eventService;
    private final ApprovalService approvalService;
    private final MongoCheckpointSaver checkpointSaver;
    private final AgentRepository agentRepository;
    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final ReminderAgentService reminderAgentService;
    private final boolean testMode;

    public AgentGraphBuilder(LlmService llmService,
                             ToolRegistry toolRegistry,
                             EventService eventService,
                             ApprovalService approvalService,
                             MongoCheckpointSaver checkpointSaver,
                             AgentRepository agentRepository,
                             LogService logService,
                             ObjectMapper objectMapper,
                             ReminderAgentService reminderAgentService) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.checkpointSaver = checkpointSaver;
        this.agentRepository = agentRepository;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.reminderAgentService = reminderAgentService;
        this.testMode = "test".equals(System.getProperty("javaclaw.llm.provider"));
    }

    public AgentState runGraph(AgentState initialState) {
        // Short-circuit: if LLM is not available, return onboarding once (no multi-agent loop)
        if (!llmService.isAvailable()) {
            String onboarding = llmService.blockingResponse(initialState);
            return initialState.withMessage("assistant", onboarding != null ? onboarding : "");
        }

        AgentState state = initialState;
        List<AgentDocument> agents = agentRepository.findByEnabledTrue();

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

                // Run specialist with tool execution loop
                state = state.withMessage("system", specialist.getSystemPrompt());
                state = runAgentSteps(state, specialist);

                specialistOutput = getLastAssistantMessage(state);

                // Post-process: if reminder agent, extract and save reminders
                if ("reminder".equals(delegateAgentId) && specialistOutput != null) {
                    String userMsg = getLastUserMessage(state);
                    reminderAgentService.executeReminder(userMsg, state.getThreadId(),
                            (agentName, msg) -> specialistOutput);
                }
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
     * Fallback single-agent mode
     */
    private AgentState runSingleAgent(AgentState state) {
        for (int step = state.getStepNo(); step <= MAX_STEPS; step++) {
            state = state.withStep(step);
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_STARTED, Map.of("step", step));

            String response = callLlm(state);
            if (response == null || response.isBlank()) {
                eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", step, "done", true));
                break;
            }

            // Parse and execute any tool calls in the response
            List<ToolCallRequest> toolCalls = parseToolCalls(response);
            String textPart = stripToolCallTags(response);

            state = state.withMessage("assistant", textPart);
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                    Map.of("step", step, "done", toolCalls.isEmpty()));

            if (toolCalls.isEmpty()) {
                break;
            }

            state = executeToolCalls(state, toolCalls);
            checkpointSaver.save(state.getThreadId(), step, state);
        }
        return state;
    }

    /**
     * Run specialist agent steps with tool execution loop.
     * This mirrors the Python agent pattern:
     *   1. Call LLM
     *   2. If response contains <tool_call> blocks → execute tools, feed results back, loop
     *   3. If response is pure text → done
     */
    private AgentState runAgentSteps(AgentState state, AgentDocument agent) {
        for (int step = state.getStepNo(); step <= MAX_STEPS; step++) {
            state = state.withStep(step);
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_STARTED,
                    Map.of("step", step, "agentId", agent.getAgentId()));

            String response = callLlmForAgent(state, agent);
            if (response == null || response.isBlank()) {
                eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", step, "agentId", agent.getAgentId(), "done", true));
                break;
            }

            // Parse tool calls from the response
            List<ToolCallRequest> toolCalls = parseToolCalls(response);
            String textPart = stripToolCallTags(response);

            // Add the text portion as assistant message
            if (!textPart.isBlank()) {
                state = state.withMessage("assistant", textPart);
            }

            boolean done = toolCalls.isEmpty();
            eventService.emit(state.getThreadId(), EventType.AGENT_STEP_COMPLETED,
                    Map.of("step", step, "agentId", agent.getAgentId(), "done", done));

            if (done) {
                // No tool calls — specialist is finished
                if (textPart.isBlank()) {
                    state = state.withMessage("assistant", response);
                }
                break;
            }

            // Execute each tool call and add results to conversation
            log.info("[{}] Step {} — executing {} tool call(s)", agent.getAgentId(), step, toolCalls.size());
            state = executeToolCalls(state, toolCalls);
            checkpointSaver.save(state.getThreadId(), step, state);
        }
        return state;
    }

    /**
     * Execute a list of tool calls and add results to state.
     */
    private AgentState executeToolCalls(AgentState state, List<ToolCallRequest> toolCalls) {
        for (ToolCallRequest tc : toolCalls) {
            log.info("[tool] Executing: {} with args: {}", tc.name(), tc.argsJson());
            eventService.emit(state.getThreadId(), EventType.TOOL_CALL_STARTED,
                    Map.of("tool", tc.name()));

            try {
                JsonNode argsNode = objectMapper.readTree(tc.argsJson());
                ToolResult result = executeTool(state, tc.name(), argsNode);

                String resultStr;
                if (result.success()) {
                    resultStr = result.output() != null ? result.output().toString() : "OK";
                } else {
                    resultStr = "Error: " + result.error();
                }

                // Add tool result to conversation history so LLM sees it on next call
                state = state.withToolResult(tc.name(), resultStr);

                eventService.emit(state.getThreadId(), EventType.TOOL_RESULT,
                        Map.of("tool", tc.name(), "success", result.success(),
                                "result", truncate(resultStr, 500)));
            } catch (Exception e) {
                log.error("Failed to execute tool {}: {}", tc.name(), e.getMessage(), e);
                state = state.withToolResult(tc.name(), "Error: " + e.getMessage());
                eventService.emit(state.getThreadId(), EventType.TOOL_RESULT,
                        Map.of("tool", tc.name(), "success", false,
                                "result", "Error: " + e.getMessage()));
            }
        }
        return state;
    }

    // --- Tool call parsing ---

    record ToolCallRequest(String name, String argsJson) {}

    /**
     * Parse <tool_call>{"name":"...","args":{...}}</tool_call> blocks from an LLM response.
     */
    List<ToolCallRequest> parseToolCalls(String response) {
        if (response == null) return List.of();
        List<ToolCallRequest> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALL_PATTERN.matcher(response);
        while (matcher.find()) {
            String json = matcher.group(1);
            try {
                JsonNode node = objectMapper.readTree(json);
                String name = node.path("name").asText(null);
                JsonNode argsNode = node.path("args");
                if (name != null && !name.isBlank()) {
                    calls.add(new ToolCallRequest(name, argsNode.toString()));
                }
            } catch (Exception e) {
                log.warn("Failed to parse tool call JSON: {}", json, e);
            }
        }
        return calls;
    }

    /**
     * Strip <tool_call>...</tool_call> blocks from response, leaving only text.
     */
    String stripToolCallTags(String response) {
        if (response == null) return "";
        return TOOL_CALL_PATTERN.matcher(response).replaceAll("").trim();
    }

    // --- LLM call methods ---

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

        boolean needsApproval = !testMode
                && (risks.contains(ToolRiskProfile.WRITE_FILES)
                    || risks.contains(ToolRiskProfile.EXEC_SHELL));

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

    private String getLastUserMessage(AgentState state) {
        List<Map<String, String>> msgs = state.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role"))) {
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
