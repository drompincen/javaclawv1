package io.github.drompincen.javaclawv1.runtime.agent.graph;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.agent.ReminderAgentService;
import io.github.drompincen.javaclawv1.runtime.agent.LogService;
import io.github.drompincen.javaclawv1.runtime.agent.approval.ApprovalService;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import io.github.drompincen.javaclawv1.runtime.agent.llm.ToolMockRegistry;
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
    private static final int MAX_PIPELINE_STEPS = 5;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_TOOL_NUDGES = 2;

    private static final String TOOL_CALL_NUDGE =
            "You described what tools you want to call but did NOT use the required XML format. "
            + "Your tool calls were NOT executed. You MUST output tool calls using <tool_call> XML blocks. "
            + "Here is the exact format:\n\n"
            + "<tool_call>\n{\"name\": \"tool_name\", \"args\": {\"key\": \"value\"}}\n</tool_call>\n\n"
            + "Output your tool calls NOW using this exact format. Do NOT describe them in prose.";

    /** Pattern to match inline tool calls: <tool_call>{"name":"...","args":{...}}</tool_call> */
    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);

    /** Fallback pattern for XML sub-element variant: <tool_call><name>x</name><args>{...}</args></tool_call> */
    private static final Pattern TOOL_CALL_XML_PATTERN =
            Pattern.compile("<tool_call>\\s*<name>\\s*(.*?)\\s*</name>\\s*<args>\\s*(\\{.*?\\})\\s*</args>\\s*</tool_call>", Pattern.DOTALL);

    /** Fallback pattern for element-name variant: <tool_call><excel>{...}</excel></tool_call> */
    private static final Pattern TOOL_CALL_ELEMENT_PATTERN =
            Pattern.compile("<tool_call>\\s*<(\\w+)>\\s*(\\{.*?\\})\\s*</\\1>\\s*</tool_call>", Pattern.DOTALL);

    /** Fallback pattern for fully-XML args: <tool_call><name>x</name><args><key>val</key>...</args></tool_call> */
    private static final Pattern TOOL_CALL_XML_ARGS_PATTERN =
            Pattern.compile("<tool_call>\\s*<name>\\s*(.*?)\\s*</name>\\s*<args>(.*?)</args>\\s*</tool_call>", Pattern.DOTALL);

    /** Pattern to extract XML child elements: <key>value</key> */
    private static final Pattern XML_ELEMENT_PATTERN =
            Pattern.compile("<(\\w+)>(.*?)</\\1>", Pattern.DOTALL);

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final EventService eventService;
    private final ApprovalService approvalService;
    private final MongoCheckpointSaver checkpointSaver;
    private final AgentRepository agentRepository;
    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final ReminderAgentService reminderAgentService;
    private final ToolMockRegistry toolMockRegistry;
    private final boolean testMode;

    public AgentGraphBuilder(LlmService llmService,
                             ToolRegistry toolRegistry,
                             EventService eventService,
                             ApprovalService approvalService,
                             MongoCheckpointSaver checkpointSaver,
                             AgentRepository agentRepository,
                             LogService logService,
                             ObjectMapper objectMapper,
                             ReminderAgentService reminderAgentService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             ToolMockRegistry toolMockRegistry) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.checkpointSaver = checkpointSaver;
        this.agentRepository = agentRepository;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.reminderAgentService = reminderAgentService;
        this.toolMockRegistry = toolMockRegistry;
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

        // Fast path: forced agent (pipeline) — skip controller/checker loop entirely
        if (state.getForcedAgentId() != null) {
            return runForcedAgent(state, agents);
        }

        // Multi-agent orchestration loop
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            String delegateAgentId;
            String directResponse = null;
            String controllerResponse = null;

            // Step 1: Controller decides routing (isolated — fork discarded)
            eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                    Map.of("toAgent", controller.getAgentId()));

            String specialistList = agents.stream()
                    .filter(a -> a.getRole() == AgentRole.SPECIALIST)
                    .map(a -> a.getAgentId() + ": " + a.getDescription())
                    .collect(Collectors.joining("\n"));

            AgentState controllerState = state.withAgent(controller.getAgentId())
                    .withMessage("system", controller.getSystemPrompt()
                            + "\n\nAvailable specialists:\n" + specialistList);
            controllerResponse = callLlmForAgentSilent(controllerState, controller);
            // controllerState is discarded — main state is unchanged

            if (controllerResponse == null || controllerResponse.isBlank()) break;

            // Parse controller decision
            delegateAgentId = parseDelegate(controllerResponse);
            directResponse = parseDirectResponse(controllerResponse);

            String specialistOutput;
            if (delegateAgentId != null) {
                String subTaskDesc = parseSubTask(controllerResponse);
                log.info("[controller] Delegating to '{}', subTask='{}'",
                        delegateAgentId, subTaskDesc);
                // Step 2: Delegate to specialist (isolated fork, merged back)
                AgentDocument specialist = agents.stream()
                        .filter(a -> a.getAgentId().equals(delegateAgentId))
                        .findFirst().orElse(null);

                if (specialist == null) {
                    eventService.emit(state.getThreadId(), EventType.ERROR,
                            Map.of("message", "Specialist not found: " + delegateAgentId));
                    break;
                }

                eventService.emit(state.getThreadId(), EventType.AGENT_DELEGATED,
                        Map.of("targetAgentId", delegateAgentId, "subTask", subTaskDesc));

                eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                        Map.of("fromAgent", controller.getAgentId(), "toAgent", specialist.getAgentId()));

                // Fork: specialist operates on isolated state with clean context
                AgentState specialistState = state.withAgent(specialist.getAgentId())
                        .withMessage("system", specialist.getSystemPrompt());

                // Inject sub-task so specialist knows what to focus on
                if (subTaskDesc != null && !"delegated task".equals(subTaskDesc)) {
                    specialistState = specialistState.withMessage("system",
                            "Your current task: " + subTaskDesc);
                }

                specialistState = runAgentSteps(specialistState, specialist, MAX_STEPS);
                specialistOutput = getLastAssistantMessage(specialistState);

                // Merge ONLY the specialist's final output into main state
                state = state.withAgent(specialist.getAgentId())
                        .withMessage("assistant", specialistOutput);

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
                // Direct response — attribute to controller
                state = state.withAgent(controller.getAgentId())
                        .withMessage("assistant", specialistOutput);
                eventService.emit(state.getThreadId(), EventType.AGENT_RESPONSE,
                        Map.of("agentId", controller.getAgentId(), "response", truncate(specialistOutput, 500)));
            }

            // Step 3: Checker validates (isolated — fork discarded)
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

            eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                    Map.of("toAgent", checker.getAgentId()));

            // Fork: checker operates on its own isolated state
            AgentState checkerState = state.withAgent(checker.getAgentId())
                    .withMessage("system", checker.getSystemPrompt())
                    .withMessage("user",
                            "Review the work completed above. The specialist's final output was:\n" + specialistOutput);
            String checkerResponse = callLlmForAgentSilent(checkerState, checker);
            // checkerState is discarded — main state is unchanged

            boolean passed = parseCheckPassed(checkerResponse);
            String summary = parseCheckSummary(checkerResponse);
            log.info("[checker] Result: pass={}, summary='{}'", passed, summary);
            if (passed) {
                eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_PASSED,
                        Map.of("agentId", checker.getAgentId(),
                                "summary", summary));
                break;
            } else {
                String feedback = parseCheckFeedback(checkerResponse);
                eventService.emit(state.getThreadId(), EventType.AGENT_CHECK_FAILED,
                        Map.of("agentId", checker.getAgentId(), "feedback", feedback,
                                "retry", retry + 1, "maxRetries", MAX_RETRIES));

                // Only retry feedback goes into main state (for next specialist iteration)
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
     * Fast path for forced-agent routing (pipeline sessions).
     * Runs the specialist once with a low step limit, no controller or checker.
     */
    private AgentState runForcedAgent(AgentState state, List<AgentDocument> agents) {
        String agentId = state.getForcedAgentId();
        log.info("[routing] Forced agent routing to '{}' (pipeline mode, max {} steps)",
                agentId, MAX_PIPELINE_STEPS);

        AgentDocument specialist = agents.stream()
                .filter(a -> a.getAgentId().equals(agentId))
                .findFirst().orElse(null);

        if (specialist == null) {
            eventService.emit(state.getThreadId(), EventType.ERROR,
                    Map.of("message", "Specialist not found: " + agentId));
            return state;
        }

        eventService.emit(state.getThreadId(), EventType.AGENT_DELEGATED,
                Map.of("targetAgentId", agentId, "subTask", "pipeline"));
        eventService.emit(state.getThreadId(), EventType.AGENT_SWITCHED,
                Map.of("toAgent", specialist.getAgentId()));

        AgentState specialistState = state.withAgent(specialist.getAgentId())
                .withMessage("system", specialist.getSystemPrompt());

        specialistState = runAgentSteps(specialistState, specialist, MAX_PIPELINE_STEPS);
        String output = getLastAssistantMessage(specialistState);

        state = state.withAgent(specialist.getAgentId())
                .withMessage("assistant", output);

        eventService.emit(state.getThreadId(), EventType.AGENT_RESPONSE,
                Map.of("agentId", specialist.getAgentId(),
                        "response", truncate(output, 500)));

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
     *   4. If response describes tools in prose (no XML tags), nudge LLM to use XML format
     */
    private AgentState runAgentSteps(AgentState state, AgentDocument agent, int maxSteps) {
        int nudgeCount = 0;
        for (int step = state.getStepNo(); step <= maxSteps; step++) {
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
                // Check if LLM described tool calls in prose instead of using XML tags
                if (nudgeCount < MAX_TOOL_NUDGES && looksLikeProseToolDescription(response, agent)) {
                    nudgeCount++;
                    log.warn("[{}] Step {} — LLM described tools in prose (nudge {}/{}), requesting XML format",
                            agent.getAgentId(), step, nudgeCount, MAX_TOOL_NUDGES);
                    state = state.withMessage("user", TOOL_CALL_NUDGE);
                    continue; // Retry — don't break
                }
                // No tool calls and not a prose description — specialist is finished
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
     * Check if the LLM's response describes tool usage in prose without actually
     * emitting <tool_call> XML blocks. This indicates the LLM understood the task
     * but used the wrong output format.
     */
    private boolean looksLikeProseToolDescription(String response, AgentDocument agent) {
        if (response == null || response.length() < 30) return false;
        String lower = response.toLowerCase();
        List<String> tools = agent.getAllowedTools();
        if (tools == null || tools.isEmpty()) return false;

        // Check if the response mentions any of the agent's tool names
        boolean mentionsTool = false;
        for (String tool : tools) {
            if ("*".equals(tool) || tool == null) continue;
            if (lower.contains(tool) || lower.contains(tool.replace("_", " "))) {
                mentionsTool = true;
                break;
            }
        }
        if (!mentionsTool) return false;

        // Check for prose indicators that suggest describing rather than calling
        return lower.contains("let me") || lower.contains("i'll ")
                || lower.contains("i will") || lower.contains("let's")
                || lower.contains("using the") || lower.contains("call the")
                || lower.contains("invoke") || lower.contains("i need to")
                || lower.contains("first, ") || lower.contains("next, ");
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
     * Parse tool calls from an LLM response. Supports three formats:
     * 1. JSON:    <tool_call>{"name":"x","args":{...}}</tool_call>
     * 2. XML:     <tool_call><name>x</name><args>{...}</args></tool_call>
     * 3. Element: <tool_call><excel>{...}</excel></tool_call>  (tool name as element)
     */
    List<ToolCallRequest> parseToolCalls(String response) {
        if (response == null) return List.of();
        List<ToolCallRequest> calls = new ArrayList<>();

        // Try primary JSON format first
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

        // If no JSON format found, try XML sub-element format
        if (calls.isEmpty()) {
            Matcher xmlMatcher = TOOL_CALL_XML_PATTERN.matcher(response);
            while (xmlMatcher.find()) {
                String name = xmlMatcher.group(1).trim();
                String argsJson = xmlMatcher.group(2).trim();
                if (!name.isBlank()) {
                    try {
                        objectMapper.readTree(argsJson); // validate JSON
                        calls.add(new ToolCallRequest(name, argsJson));
                        log.info("Parsed tool call via XML format: {} with args: {}", name, argsJson);
                    } catch (Exception e) {
                        log.warn("Failed to parse XML-format tool call args: {}", argsJson, e);
                    }
                }
            }
        }

        // If still nothing, try element-name format: <tool_call><toolname>{...}</toolname></tool_call>
        if (calls.isEmpty()) {
            Matcher elemMatcher = TOOL_CALL_ELEMENT_PATTERN.matcher(response);
            while (elemMatcher.find()) {
                String name = elemMatcher.group(1).trim();
                String argsJson = elemMatcher.group(2).trim();
                if (!name.isBlank()) {
                    try {
                        objectMapper.readTree(argsJson); // validate JSON
                        calls.add(new ToolCallRequest(name, argsJson));
                        log.info("Parsed tool call via element-name format: <{}>{}</{}>", name, argsJson, name);
                    } catch (Exception e) {
                        log.warn("Failed to parse element-name tool call args: {}", argsJson, e);
                    }
                }
            }
        }

        // If still nothing, try fully-XML args: <tool_call><name>x</name><args><key>val</key>...</args></tool_call>
        if (calls.isEmpty()) {
            Matcher xmlArgsMatcher = TOOL_CALL_XML_ARGS_PATTERN.matcher(response);
            while (xmlArgsMatcher.find()) {
                String name = xmlArgsMatcher.group(1).trim();
                String argsXml = xmlArgsMatcher.group(2).trim();
                if (!name.isBlank() && !argsXml.isBlank()) {
                    try {
                        String argsJson = xmlArgsToJson(argsXml);
                        objectMapper.readTree(argsJson); // validate
                        calls.add(new ToolCallRequest(name, argsJson));
                        log.info("Parsed tool call via XML-args format: {} with converted args", name);
                    } catch (Exception e) {
                        log.warn("Failed to convert XML args for tool {}: {}", name, e.getMessage());
                    }
                }
            }
        }

        return calls;
    }

    /**
     * Convert XML child elements to a JSON object string.
     * Handles: simple values, JSON arrays/objects embedded as text, and plain strings.
     * Example: {@code <projectId>abc</projectId><title>My Title</title><items>[1,2]</items>}
     * becomes: {@code {"projectId":"abc","title":"My Title","items":[1,2]}}
     */
    String xmlArgsToJson(String argsXml) {
        StringBuilder sb = new StringBuilder("{");
        Matcher m = XML_ELEMENT_PATTERN.matcher(argsXml);
        boolean first = true;
        while (m.find()) {
            String key = m.group(1).trim();
            String value = m.group(2).trim();
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJsonString(key)).append("\":");
            // If value looks like JSON (array or object), embed it directly
            if ((value.startsWith("[") && value.endsWith("]"))
                    || (value.startsWith("{") && value.endsWith("}"))) {
                try {
                    objectMapper.readTree(value); // validate it's real JSON
                    sb.append(value);
                } catch (Exception e) {
                    // Not valid JSON — treat as string
                    sb.append("\"").append(escapeJsonString(value)).append("\"");
                }
            } else {
                sb.append("\"").append(escapeJsonString(value)).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Strip <tool_call>...</tool_call> blocks from response, leaving only text.
     */
    String stripToolCallTags(String response) {
        if (response == null) return "";
        String stripped = TOOL_CALL_PATTERN.matcher(response).replaceAll("");
        stripped = TOOL_CALL_XML_PATTERN.matcher(stripped).replaceAll("");
        stripped = TOOL_CALL_ELEMENT_PATTERN.matcher(stripped).replaceAll("");
        stripped = TOOL_CALL_XML_ARGS_PATTERN.matcher(stripped).replaceAll("");
        return stripped.trim();
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
        log.info("[{}] Streaming LLM response", agent.getAgentId());
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

    /**
     * Blocking LLM call that does NOT emit MODEL_TOKEN_DELTA events.
     * Used for controller and checker whose responses are internal orchestration
     * and should not appear in the user's chat.
     */
    private String callLlmForAgentSilent(AgentState state, AgentDocument agent) {
        long startTime = System.currentTimeMillis();
        try {
            String result = llmService.blockingResponse(state);
            long durationMs = System.currentTimeMillis() - startTime;
            log.info("[{}] LLM response received ({}ms, {} chars)",
                    agent.getAgentId(), durationMs, result != null ? result.length() : 0);
            logService.recordLlmInteraction(state.getThreadId(), agent.getAgentId(),
                    "anthropic", null, state.getMessages().size(), 0,
                    estimateTokens(result), durationMs, true, null);
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("[{}] LLM call failed ({}ms): {}", agent.getAgentId(), durationMs, e.getMessage());
            logService.recordLlmInteraction(state.getThreadId(), agent.getAgentId(),
                    "anthropic", null, state.getMessages().size(), 0, 0,
                    durationMs, false, e.getMessage());
            eventService.emit(state.getThreadId(), EventType.ERROR,
                    Map.of("message", "LLM call failed for " + agent.getAgentId() + ": " + e.getMessage()));
            return null;
        }
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }

    public ToolResult executeTool(AgentState state, String toolName, JsonNode input) {
        // Enforce agent's allowedTools list
        String agentId = state.getCurrentAgentId();
        if (agentId != null) {
            Optional<AgentDocument> agentOpt = agentRepository.findById(agentId);
            if (agentOpt.isPresent()) {
                List<String> allowed = agentOpt.get().getAllowedTools();
                if (allowed != null && !allowed.isEmpty()
                        && !allowed.contains("*") && !allowed.contains(toolName)) {
                    log.warn("[{}] Blocked disallowed tool call: {}", agentId, toolName);
                    return ToolResult.failure("Tool '" + toolName
                            + "' is not allowed for agent '" + agentId + "'");
                }
            }
        }

        // Check tool mock registry first (V2 scenario testing)
        if (toolMockRegistry != null) {
            Optional<ToolResult> mockResult = toolMockRegistry.tryMatch(toolName, input);
            if (mockResult.isPresent()) {
                log.info("[tool] Mock result for {}", toolName);
                return mockResult.get();
            }
        }

        Optional<Tool> toolOpt = toolRegistry.get(toolName);
        if (toolOpt.isEmpty()) {
            return ToolResult.failure("Tool not found: " + toolName);
        }

        Tool tool = toolOpt.get();
        Set<ToolRiskProfile> risks = tool.riskProfiles();

        boolean isPipeline = state.getForcedAgentId() != null;
        boolean needsApproval = !testMode && !isPipeline
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

    /** Pattern to strip markdown code fences (```json ... ```) that LLMs wrap around JSON responses */
    private static final Pattern CODE_FENCE_PATTERN_JSON =
            Pattern.compile("^\\s*```(?:json)?\\s*\\n?(.*?)\\n?\\s*```\\s*$", Pattern.DOTALL);

    private String stripCodeFences(String response) {
        if (response == null) return null;
        Matcher m = CODE_FENCE_PATTERN_JSON.matcher(response.trim());
        return m.matches() ? m.group(1).trim() : response.trim();
    }

    private String parseDelegate(String response) {
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
            if (node.has("delegate")) return node.get("delegate").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private String parseDirectResponse(String response) {
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
            if (node.has("respond")) return node.get("respond").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private String parseSubTask(String response) {
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
            if (node.has("subTask")) return node.get("subTask").asText();
        } catch (Exception ignored) {}
        return "delegated task";
    }

    private boolean parseCheckPassed(String response) {
        if (response == null) return true;
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
            if (node.has("pass")) return node.get("pass").asBoolean();
        } catch (Exception ignored) {}
        return !response.toLowerCase().contains("\"pass\": false")
                && !response.toLowerCase().contains("\"pass\":false");
    }

    private String parseCheckSummary(String response) {
        if (response == null) return "";
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
            if (node.has("summary")) return node.get("summary").asText();
        } catch (Exception ignored) {}
        return truncate(response, 200);
    }

    private String parseCheckFeedback(String response) {
        if (response == null) return "no feedback provided";
        try {
            JsonNode node = objectMapper.readTree(stripCodeFences(response));
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
