package io.github.drompincen.javaclawv1.runtime.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.agent.LogService;
import io.github.drompincen.javaclawv1.runtime.agent.ReminderAgentService;
import io.github.drompincen.javaclawv1.runtime.agent.approval.ApprovalService;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import io.github.drompincen.javaclawv1.runtime.tools.Tool;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolRegistry;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Combined orchestration + real tool execution test.
 *
 * Wires the full AgentGraphBuilder loop (controller → specialist → checker)
 * with REAL tool execution: the specialist's response contains a write_file
 * tool call, the ToolRegistry resolves it to a real WriteFileTool that creates
 * a file on disk, then the specialist produces a final text answer.
 *
 * This validates the complete pipeline:
 *   1. Controller delegates (silent LLM call, code-fence stripped)
 *   2. Specialist issues <tool_call> (streaming LLM call)
 *   3. Tool executes (real file I/O)
 *   4. Specialist loops back with final text
 *   5. Checker validates (silent LLM call)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentGraphBuilderExecFlowTest {

    @Mock private LlmService llmService;
    @Mock private EventService eventService;
    @Mock private ApprovalService approvalService;
    @Mock private MongoCheckpointSaver checkpointSaver;
    @Mock private AgentRepository agentRepository;
    @Mock private LogService logService;
    @Mock private ReminderAgentService reminderAgentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Real tool registry with a real WriteFileTool */
    private ToolRegistry toolRegistry;
    private AgentGraphBuilder builder;
    private Path tempFile;

    @BeforeEach
    void setUp() {
        System.setProperty("javaclaw.llm.provider", "test");

        // Build a ToolRegistry with a real write_file tool
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.get("write_file")).thenReturn(Optional.of(realWriteFileTool()));

        builder = new AgentGraphBuilder(
                llmService, toolRegistry, eventService, approvalService,
                checkpointSaver, agentRepository, logService, objectMapper,
                reminderAgentService);

        when(llmService.isAvailable()).thenReturn(true);
        when(eventService.emit(any(), any(EventType.class), any())).thenReturn(null);
        when(eventService.emit(any(), any(EventType.class))).thenReturn(null);
    }

    @AfterEach
    void cleanup() {
        if (tempFile != null) {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------
    // Full flow: controller → specialist (write_file tool) → checker
    // ---------------------------------------------------------------

    @Test
    void fullFlow_specialistWritesFile_checkerPasses() throws Exception {
        tempFile = Files.createTempFile("jclaw_test_", ".txt");
        String tempPath = tempFile.toAbsolutePath().toString().replace("\\", "\\\\");

        AgentDocument controller = makeAgent("controller", AgentRole.CONTROLLER, "Route tasks");
        AgentDocument coder = makeAgent("coder", AgentRole.SPECIALIST, "Write code");
        AgentDocument checker = makeAgent("reviewer", AgentRole.CHECKER, "Check work");

        when(agentRepository.findByEnabledTrue()).thenReturn(List.of(controller, coder, checker));

        // Controller delegates to coder (wrapped in code fences to test stripping)
        // Specialist call #1: returns a write_file tool call
        // Specialist call #2: returns final text (after tool result)
        // Checker: passes
        int[] specialistCallCount = {0};

        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "```json\n{\"delegate\": \"coder\", \"subTask\": \"write a test file\"}\n```";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                return "{\"pass\": true, \"summary\": \"File was written successfully\"}";
            }
            return null;
        });

        when(llmService.streamResponse(any())).thenAnswer(inv -> {
            specialistCallCount[0]++;
            if (specialistCallCount[0] == 1) {
                // First call: specialist issues a write_file tool call
                String toolCall = "I'll write a file for you.\n\n<tool_call>\n"
                        + "{\"name\": \"write_file\", \"args\": {\"path\": \"" + tempPath
                        + "\", \"content\": \"Hello from JavaClaw test!\"}}\n</tool_call>";
                return Flux.just(toolCall);
            }
            // Second call: specialist returns final text after seeing tool result
            return Flux.just("Done! I wrote the file successfully. It contains the test greeting.");
        });

        AgentState initial = new AgentState();
        initial.setThreadId("exec-flow-1");
        initial.setMessages(new ArrayList<>(List.of(
                new java.util.HashMap<>(Map.of("role", "user", "content", "write a test file"))
        )));

        // --- Execute the graph ---
        AgentState result = builder.runGraph(initial);

        // --- Verify: file was ACTUALLY written to disk ---
        assertThat(tempFile).exists();
        String fileContent = Files.readString(tempFile);
        assertThat(fileContent).isEqualTo("Hello from JavaClaw test!");

        // --- Verify: specialist was called twice (tool call + final text) ---
        verify(llmService, times(2)).streamResponse(any());

        // --- Verify: write_file tool was resolved and executed ---
        verify(toolRegistry, atLeastOnce()).get("write_file");

        // --- Verify: tool result event was emitted ---
        verify(eventService, atLeastOnce()).emit(eq("exec-flow-1"), eq(EventType.TOOL_RESULT), any());

        // --- Verify: checker passed ---
        verify(eventService).emit(eq("exec-flow-1"), eq(EventType.AGENT_CHECK_PASSED), any());

        // --- Verify: specialist's final text is in the conversation ---
        List<String> assistantMsgs = result.getMessages().stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .map(m -> m.get("content"))
                .toList();
        assertThat(assistantMsgs).anyMatch(m -> m.contains("wrote the file successfully"));

        // --- Verify: controller used silent call (blockingResponse), not streaming ---
        // blockingResponse was called at least twice: controller + checker
        verify(llmService, atLeast(2)).blockingResponse(any());

        // --- Verify: MODEL_TOKEN_DELTA was emitted for specialist streaming only ---
        verify(eventService, atLeastOnce()).emit(
                eq("exec-flow-1"), eq(EventType.MODEL_TOKEN_DELTA), any());
    }

    // ---------------------------------------------------------------
    // Flow with checker rejection → retry → file rewritten → pass
    // ---------------------------------------------------------------

    @Test
    void fullFlow_checkerRejects_specialistRetries_fileRewritten() throws Exception {
        tempFile = Files.createTempFile("jclaw_retry_", ".txt");
        String tempPath = tempFile.toAbsolutePath().toString().replace("\\", "\\\\");

        AgentDocument controller = makeAgent("controller", AgentRole.CONTROLLER, "Route tasks");
        AgentDocument coder = makeAgent("coder", AgentRole.SPECIALIST, "Write code");
        AgentDocument checker = makeAgent("reviewer", AgentRole.CHECKER, "Check work");

        when(agentRepository.findByEnabledTrue()).thenReturn(List.of(controller, coder, checker));

        int[] checkerCount = {0};
        when(llmService.blockingResponse(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            if ("controller".equals(s.getCurrentAgentId())) {
                return "{\"delegate\": \"coder\", \"subTask\": \"write greeting\"}";
            }
            if ("reviewer".equals(s.getCurrentAgentId())) {
                checkerCount[0]++;
                if (checkerCount[0] == 1) {
                    return "{\"pass\": false, \"feedback\": \"File content too short\", \"summary\": \"Needs more\"}";
                }
                return "{\"pass\": true, \"summary\": \"File content is adequate now\"}";
            }
            return null;
        });

        int[] specialistCount = {0};
        when(llmService.streamResponse(any())).thenAnswer(inv -> {
            specialistCount[0]++;
            if (specialistCount[0] == 1) {
                // First attempt: write short content
                return Flux.just("<tool_call>\n{\"name\": \"write_file\", \"args\": {\"path\": \""
                        + tempPath + "\", \"content\": \"Hi\"}}\n</tool_call>");
            }
            if (specialistCount[0] == 2) {
                // First attempt: final text
                return Flux.just("Written: Hi");
            }
            if (specialistCount[0] == 3) {
                // Second attempt (after rejection): write longer content
                return Flux.just("<tool_call>\n{\"name\": \"write_file\", \"args\": {\"path\": \""
                        + tempPath + "\", \"content\": \"Hello from JavaClaw! This is a proper greeting.\"}}\n</tool_call>");
            }
            // Second attempt: final text
            return Flux.just("Written: Hello from JavaClaw! This is a proper greeting.");
        });

        AgentState initial = new AgentState();
        initial.setThreadId("exec-retry-1");
        initial.setMessages(new ArrayList<>(List.of(
                new java.util.HashMap<>(Map.of("role", "user", "content", "write a greeting"))
        )));

        AgentState result = builder.runGraph(initial);

        // File should have the second (longer) content after retry
        assertThat(tempFile).exists();
        String content = Files.readString(tempFile);
        assertThat(content).isEqualTo("Hello from JavaClaw! This is a proper greeting.");

        // Specialist was called 4 times (2 per attempt: tool call + final text)
        verify(llmService, times(4)).streamResponse(any());

        // Checker failed once, then passed
        verify(eventService).emit(eq("exec-retry-1"), eq(EventType.AGENT_CHECK_FAILED), any());
        verify(eventService).emit(eq("exec-retry-1"), eq(EventType.AGENT_CHECK_PASSED), any());
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

    /**
     * A real WriteFileTool that actually writes files to disk.
     * Risk profiles return empty set so approval is skipped in test mode.
     */
    private Tool realWriteFileTool() {
        return new Tool() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override public String name() { return "write_file"; }
            @Override public String description() { return "Write content to a file"; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public JsonNode outputSchema() { return null; }
            @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(); }

            @Override
            public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
                try {
                    String filePath = input.get("path").asText();
                    String content = input.get("content").asText();
                    Path resolved = Path.of(filePath);
                    Files.createDirectories(resolved.getParent());
                    Files.writeString(resolved, content);
                    stream.stdoutDelta("Written " + content.length() + " chars to " + filePath);
                    return ToolResult.success(mapper.valueToTree(
                            "Written " + content.length() + " chars to " + filePath));
                } catch (Exception e) {
                    return ToolResult.failure("Failed to write file: " + e.getMessage());
                }
            }
        };
    }
}
