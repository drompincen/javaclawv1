package io.github.drompincen.javaclawv1.runtime.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.agent.LogService;
import io.github.drompincen.javaclawv1.runtime.agent.ReminderAgentService;
import io.github.drompincen.javaclawv1.runtime.agent.approval.ApprovalService;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import io.github.drompincen.javaclawv1.runtime.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests the multi-agent orchestration loop in AgentGraphBuilder:
 * controller → specialist → checker, including JSON parsing with
 * markdown code fences, silent vs streaming LLM calls, and retry logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentGraphBuilderTest {

    @Mock private LlmService llmService;
    @Mock private ToolRegistry toolRegistry;
    @Mock private EventService eventService;
    @Mock private ApprovalService approvalService;
    @Mock private MongoCheckpointSaver checkpointSaver;
    @Mock private AgentRepository agentRepository;
    @Mock private LogService logService;
    @Mock private ReminderAgentService reminderAgentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentGraphBuilder builder;

    private AgentDocument controller;
    private AgentDocument generalist;
    private AgentDocument checker;

    @BeforeEach
    void setUp() {
        // Force test mode so approval is skipped for tools
        System.setProperty("javaclaw.llm.provider", "test");

        builder = new AgentGraphBuilder(
                llmService, toolRegistry, eventService, approvalService,
                checkpointSaver, agentRepository, logService, objectMapper,
                reminderAgentService, null);

        controller = makeAgent("controller", AgentRole.CONTROLLER, "You are the controller.");
        generalist = makeAgent("generalist", AgentRole.SPECIALIST, "You are the generalist.");
        checker = makeAgent("reviewer", AgentRole.CHECKER, "You are the reviewer.");

        // Default: LLM is available
        when(llmService.isAvailable()).thenReturn(true);

        // EventService.emit returns null by default (mock); just let it pass
        when(eventService.emit(any(), any(EventType.class), any())).thenReturn(null);
        when(eventService.emit(any(), any(EventType.class))).thenReturn(null);
    }

    // ---------------------------------------------------------------
    // 1. Full orchestration: controller delegates → specialist → checker PASS
    // ---------------------------------------------------------------

    @Test
    void runGraph_controllerDelegatesToSpecialist_checkerPasses() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        // Controller returns delegation JSON (via blockingResponse — silent call)
        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"generalist\", \"subTask\": \"respond to greeting\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"Good response\"}";
            }
            return null;
        });

        // Specialist streams its response (via streamResponse — streaming call)
        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Hello", " from", " generalist!"));

        AgentState initial = makeState("thread-1", "hello");
        AgentState result = builder.runGraph(initial);

        // Specialist's response should be in the conversation (not necessarily last —
        // checker's response comes after as an internal message)
        assertThat(allAssistantMessages(result)).anyMatch(m -> m.equals("Hello from generalist!"));

        // Verify controller used blockingResponse (silent), NOT streamResponse
        verify(llmService, atLeastOnce()).blockingResponse(any());

        // Verify specialist used streamResponse (streaming tokens to chat)
        verify(llmService, atLeastOnce()).streamResponse(any());

        // Verify AGENT_SWITCHED events were emitted
        ArgumentCaptor<EventType> typeCaptor = ArgumentCaptor.forClass(EventType.class);
        verify(eventService, atLeast(3)).emit(eq("thread-1"), typeCaptor.capture(), any());
        List<EventType> types = typeCaptor.getAllValues();
        assertThat(types).contains(EventType.AGENT_SWITCHED, EventType.AGENT_DELEGATED,
                EventType.AGENT_CHECK_PASSED);
    }

    // ---------------------------------------------------------------
    // 2. Code fences: controller wraps JSON in ```json...```
    // ---------------------------------------------------------------

    @Test
    void runGraph_controllerResponseInCodeFences_stillParsesDelegation() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                // Wrapped in markdown code fences — the bug this fix addresses
                return "```json\n{\"delegate\": \"generalist\", \"subTask\": \"test\"}\n```";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "```json\n{\"pass\": true, \"summary\": \"All good\"}\n```";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Specialist response"));

        AgentState initial = makeState("thread-2", "hello");
        AgentState result = builder.runGraph(initial);

        // If code fence stripping works, specialist should have executed
        assertThat(allAssistantMessages(result)).anyMatch(m -> m.equals("Specialist response"));

        // Verify specialist DID execute (streamResponse was called)
        verify(llmService, atLeastOnce()).streamResponse(any());

        // Verify check passed
        verify(eventService).emit(eq("thread-2"), eq(EventType.AGENT_CHECK_PASSED), any());
    }

    @Test
    void runGraph_controllerResponseInCodeFencesNoJsonTag_stillParsesDelegation() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                // Code fences without "json" tag
                return "```\n{\"delegate\": \"generalist\", \"subTask\": \"test\"}\n```";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"ok\"}";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("OK"));

        AgentState initial = makeState("thread-3", "hi");
        AgentState result = builder.runGraph(initial);

        // Specialist should have run
        verify(llmService, atLeastOnce()).streamResponse(any());
    }

    // ---------------------------------------------------------------
    // 3. Checker rejects → retry → checker passes on second attempt
    // ---------------------------------------------------------------

    @Test
    void runGraph_checkerRejectsThenPasses_retriesSpecialist() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        // Track call count for checker to fail first, pass second
        int[] checkerCallCount = {0};
        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"generalist\", \"subTask\": \"do work\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                checkerCallCount[0]++;
                if (checkerCallCount[0] == 1) {
                    return "{\"pass\": false, \"feedback\": \"Incomplete answer\", \"summary\": \"Needs work\"}";
                }
                return "{\"pass\": true, \"summary\": \"Good now\"}";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("First attempt"))
                .thenReturn(Flux.just("Second attempt"));

        AgentState initial = makeState("thread-4", "do something");
        AgentState result = builder.runGraph(initial);

        // Controller was called twice (retry loop), so blockingResponse ≥ 4 calls
        // (controller x2 + checker x2)
        verify(llmService, atLeast(4)).blockingResponse(any());

        // Specialist stream was called twice (once per retry)
        verify(llmService, times(2)).streamResponse(any());

        // Check FAILED then PASSED events
        verify(eventService).emit(eq("thread-4"), eq(EventType.AGENT_CHECK_FAILED), any());
        verify(eventService).emit(eq("thread-4"), eq(EventType.AGENT_CHECK_PASSED), any());
    }

    // ---------------------------------------------------------------
    // 4. Controller responds directly (no delegation)
    // ---------------------------------------------------------------

    @Test
    void runGraph_controllerDirectResponse_noSpecialist() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"respond\": \"I can answer that directly.\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"Direct answer ok\"}";
            }
            return null;
        });

        AgentState initial = makeState("thread-5", "what is 2+2?");
        AgentState result = builder.runGraph(initial);

        // Specialist should NOT have been called (no streamResponse)
        verify(llmService, never()).streamResponse(any());

        // AGENT_DELEGATED should NOT have been emitted
        verify(eventService, never()).emit(any(), eq(EventType.AGENT_DELEGATED), any());
    }

    // ---------------------------------------------------------------
    // 5. No agents → single agent fallback
    // ---------------------------------------------------------------

    @Test
    void runGraph_noAgents_fallsBackToSingleAgent() {
        when(agentRepository.findByEnabledTrue()).thenReturn(List.of());

        // Single-agent mode uses callLlm which calls streamResponse
        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Single agent response"));

        AgentState initial = makeState("thread-6", "hello");
        AgentState result = builder.runGraph(initial);

        assertThat(getLastAssistantMessage(result)).isEqualTo("Single agent response");

        // No multi-agent events
        verify(eventService, never()).emit(any(), eq(EventType.AGENT_DELEGATED), any());
        verify(eventService, never()).emit(any(), eq(EventType.AGENT_CHECK_PASSED), any());
    }

    // ---------------------------------------------------------------
    // 6. No controller → single agent fallback
    // ---------------------------------------------------------------

    @Test
    void runGraph_noController_fallsBackToSingleAgent() {
        // Only specialists, no controller
        when(agentRepository.findByEnabledTrue()).thenReturn(List.of(generalist));

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Fallback"));

        AgentState initial = makeState("thread-7", "hi");
        AgentState result = builder.runGraph(initial);

        assertThat(getLastAssistantMessage(result)).isEqualTo("Fallback");
    }

    // ---------------------------------------------------------------
    // 7. Silent calls: controller/checker → no MODEL_TOKEN_DELTA
    //    Streaming calls: specialist → yes MODEL_TOKEN_DELTA
    // ---------------------------------------------------------------

    @Test
    void runGraph_controllerAndCheckerDoNotEmitTokenDeltas_specialistDoes() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"generalist\", \"subTask\": \"greet\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"ok\"}";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("token1", "token2"));

        AgentState initial = makeState("thread-8", "hello");
        builder.runGraph(initial);

        // MODEL_TOKEN_DELTA should have been emitted (from specialist streaming)
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService, atLeastOnce()).emit(
                eq("thread-8"), eq(EventType.MODEL_TOKEN_DELTA), payloadCaptor.capture());

        // All token deltas should carry the specialist's agentId, NOT controller/reviewer
        for (Map<?, ?> payload : payloadCaptor.getAllValues()) {
            assertThat(payload.get("agentId")).isEqualTo("generalist");
        }
    }

    // ---------------------------------------------------------------
    // 8. LLM not available → short-circuit onboarding
    // ---------------------------------------------------------------

    @Test
    void runGraph_llmNotAvailable_returnsOnboarding() {
        when(llmService.isAvailable()).thenReturn(false);
        when(llmService.blockingResponse(any())).thenReturn("Welcome onboarding message");

        AgentState initial = makeState("thread-9", "hello");
        AgentState result = builder.runGraph(initial);

        assertThat(getLastAssistantMessage(result)).isEqualTo("Welcome onboarding message");

        // No streaming or multi-agent calls
        verify(llmService, never()).streamResponse(any());
        verify(agentRepository, never()).findByEnabledTrue();
    }

    // ---------------------------------------------------------------
    // 9. No checker configured → accepts result without validation
    // ---------------------------------------------------------------

    @Test
    void runGraph_noChecker_acceptsResultDirectly() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist)); // no checker

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"generalist\", \"subTask\": \"respond\"}";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Answer without checker"));

        AgentState initial = makeState("thread-10", "hello");
        AgentState result = builder.runGraph(initial);

        assertThat(getLastAssistantMessage(result)).isEqualTo("Answer without checker");

        // CHECK_PASSED emitted with "No checker configured" message
        verify(eventService).emit(eq("thread-10"), eq(EventType.AGENT_CHECK_PASSED),
                argThat(m -> m instanceof Map && ((Map<?,?>) m).get("summary").toString()
                        .contains("No checker configured")));
    }

    // ---------------------------------------------------------------
    // 10. Specialist with tool calls
    // ---------------------------------------------------------------

    @Test
    void runGraph_specialistEmitsToolCalls_executesAndLoops() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"generalist\", \"subTask\": \"read file\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"File read ok\"}";
            }
            return null;
        });

        // First specialist call returns a tool call, second returns final text
        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Reading file\n\n<tool_call>\n{\"name\": \"read_file\", \"args\": {\"path\": \"pom.xml\"}}\n</tool_call>"))
                .thenReturn(Flux.just("The pom.xml contains Maven config."));

        // Mock tool registry to return a dummy tool
        when(toolRegistry.get("read_file")).thenReturn(java.util.Optional.of(
                new io.github.drompincen.javaclawv1.runtime.tools.Tool() {
                    @Override public String name() { return "read_file"; }
                    @Override public String description() { return "Read a file"; }
                    @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
                    @Override public com.fasterxml.jackson.databind.JsonNode outputSchema() { return null; }
                    @Override public java.util.Set<io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile> riskProfiles() {
                        return java.util.Set.of();
                    }
                    @Override
                    public io.github.drompincen.javaclawv1.runtime.tools.ToolResult execute(
                            io.github.drompincen.javaclawv1.runtime.tools.ToolContext ctx,
                            com.fasterxml.jackson.databind.JsonNode input,
                            io.github.drompincen.javaclawv1.runtime.tools.ToolStream stream) {
                        return io.github.drompincen.javaclawv1.runtime.tools.ToolResult.success(
                                objectMapper.valueToTree("file contents here"));
                    }
                }
        ));

        AgentState initial = makeState("thread-11", "read pom.xml");
        AgentState result = builder.runGraph(initial);

        // Specialist streamed twice (tool call step + final response step)
        verify(llmService, times(2)).streamResponse(any());

        // Tool was executed
        verify(toolRegistry).get("read_file");

        // Specialist's final text response should be in the conversation
        assertThat(allAssistantMessages(result)).anyMatch(m -> m.equals("The pom.xml contains Maven config."));
    }

    // ---------------------------------------------------------------
    // 11. Code fences with extra whitespace and indentation
    // ---------------------------------------------------------------

    @Test
    void runGraph_codeFencesWithWhitespace_stillParses() {
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker));

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                // Extra spaces and newlines around fences
                return "  ```json\n  {\"delegate\": \"generalist\", \"subTask\": \"test\"}\n  ```  ";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "  ```\n{\"pass\": true, \"summary\": \"good\"}\n```";
            }
            return null;
        });

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("response"));

        AgentState initial = makeState("thread-12", "test");
        builder.runGraph(initial);

        // Specialist should have been called — delegation parsed correctly
        verify(llmService, atLeastOnce()).streamResponse(any());
        verify(eventService).emit(eq("thread-12"), eq(EventType.AGENT_CHECK_PASSED), any());
    }

    // ---------------------------------------------------------------
    // 12. Forced agent routing skips controller
    // ---------------------------------------------------------------

    @Test
    void runGraph_forcedAgentId_skipsControllerAndRoutesDirectly() {
        AgentDocument threadExtractor = makeAgent("thread-extractor", AgentRole.SPECIALIST, "You extract action items.");
        when(agentRepository.findByEnabledTrue())
                .thenReturn(List.of(controller, generalist, checker, threadExtractor));

        when(llmService.streamResponse(any()))
                .thenReturn(Flux.just("Extracted 3 items from thread."));

        AgentState initial = makeState("thread-13", "extract items from thread");
        initial.setForcedAgentId("thread-extractor");
        AgentState result = builder.runGraph(initial);

        // Specialist should have been called via streamResponse (pipeline mode)
        assertThat(allAssistantMessages(result)).anyMatch(m -> m.equals("Extracted 3 items from thread."));

        // Forced agent routing skips controller AND checker — no blockingResponse calls
        verify(llmService, never()).blockingResponse(any());

        // AGENT_DELEGATED should have been emitted (from forced routing)
        verify(eventService).emit(eq("thread-13"), eq(EventType.AGENT_DELEGATED),
                argThat(m -> m instanceof Map && "thread-extractor".equals(((Map<?,?>) m).get("targetAgentId"))));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static AgentDocument makeAgent(String id, AgentRole role, String systemPrompt) {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId(id);
        agent.setName(id);
        agent.setDescription(id + " agent");
        agent.setRole(role);
        agent.setSystemPrompt(systemPrompt);
        agent.setEnabled(true);
        return agent;
    }

    private static AgentState makeState(String threadId, String userMessage) {
        AgentState state = new AgentState();
        state.setThreadId(threadId);
        state.setMessages(new java.util.ArrayList<>(List.of(
                new java.util.HashMap<>(Map.of("role", "user", "content", userMessage))
        )));
        return state;
    }

    private static String getLastAssistantMessage(AgentState state) {
        List<Map<String, String>> msgs = state.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("assistant".equals(msgs.get(i).get("role"))) {
                return msgs.get(i).get("content");
            }
        }
        return "";
    }

    private static List<String> allAssistantMessages(AgentState state) {
        return state.getMessages().stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .map(m -> m.get("content"))
                .toList();
    }
}
