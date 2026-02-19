package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineResponse;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IntakePipelineService {

    private static final Logger log = LoggerFactory.getLogger(IntakePipelineService.class);
    private static final long TRIAGE_TIMEOUT_MS = 60_000;
    private static final long THREAD_AGENT_TIMEOUT_MS = 120_000;
    private static final long PM_TIMEOUT_MS = 120_000;
    private static final long PLAN_TIMEOUT_MS = 120_000;
    private static final long OBJECTIVE_TIMEOUT_MS = 60_000;
    private static final long RECONCILE_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 200;

    private static final String TOOL_FORMAT =
            "\n\n## Tool Calling — CRITICAL\n"
            + "You MUST call tools using the <tool_call> XML format from your system instructions.\n"
            + "Do NOT describe tool calls in prose — prose descriptions do NOTHING.\n"
            + "Output <tool_call> XML blocks IMMEDIATELY. Do not explain first.\n"
            + "If you fail to use the XML format, your work will NOT be saved.\n"
            + "IMPORTANT: Output ALL your tool calls in ONE response. After receiving tool results, "
            + "write a brief summary and STOP. Do NOT repeat tool calls you already made.\n";

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ThreadRepository threadRepository;
    private final EventService eventService;
    private final AgentLoop agentLoop;
    private final DistillerService distillerService;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "intake-pipeline");
        t.setDaemon(true);
        return t;
    });

    public IntakePipelineService(SessionRepository sessionRepository,
                                 MessageRepository messageRepository,
                                 ThreadRepository threadRepository,
                                 EventService eventService,
                                 AgentLoop agentLoop,
                                 DistillerService distillerService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.threadRepository = threadRepository;
        this.eventService = eventService;
        this.agentLoop = agentLoop;
        this.distillerService = distillerService;
    }

    public IntakePipelineResponse startPipeline(String projectId, String rawContent,
                                                 String sourceSessionId, List<String> filePaths) {
        String pipelineId = UUID.randomUUID().toString();
        log.info("[pipeline-{}] Starting intake pipeline for project {}", pipelineId.substring(0, 8), projectId);

        eventService.emit(sourceSessionId, EventType.INTAKE_PIPELINE_STARTED,
                Map.of("pipelineId", pipelineId, "projectId", projectId));

        List<String> safeFilePaths = filePaths != null ? filePaths : List.of();
        executor.submit(() -> runPipeline(pipelineId, projectId, rawContent, sourceSessionId, safeFilePaths));

        return new IntakePipelineResponse(pipelineId, sourceSessionId, "PIPELINE_STARTED");
    }

    /** Backward-compatible overload for callers that don't pass filePaths. */
    public IntakePipelineResponse startPipeline(String projectId, String rawContent, String sourceSessionId) {
        return startPipeline(projectId, rawContent, sourceSessionId, List.of());
    }

    private void runPipeline(String pipelineId, String projectId, String rawContent,
                             String sourceSessionId, List<String> filePaths) {
        String prefix = "[pipeline-" + pipelineId.substring(0, 8) + "]";
        try {
            // ── Phase 1: Triage ──
            log.info("{} Phase 1: Triage", prefix);
            String triageSessionId = createAgentSession(projectId, "intake-triage", pipelineId);
            seedUserMessage(triageSessionId, buildTriagePrompt(rawContent));
            agentLoop.startAsync(triageSessionId);
            waitForCompletion(triageSessionId, TRIAGE_TIMEOUT_MS);

            String triageOutput = getLastAssistantMessage(triageSessionId);
            eventService.emit(sourceSessionId, EventType.INTAKE_CLASSIFIED,
                    Map.of("pipelineId", pipelineId, "triageSessionId", triageSessionId));
            log.info("{} Phase 1 complete — triage classified", prefix);

            // ── Phase 2: Thread creation ──
            log.info("{} Phase 2: Thread creation", prefix);
            String threadSessionId = createAgentSession(projectId, "thread-agent", pipelineId);
            seedUserMessage(threadSessionId, buildThreadAgentPrompt(rawContent, triageOutput, projectId));
            agentLoop.startAsync(threadSessionId);
            waitForCompletion(threadSessionId, THREAD_AGENT_TIMEOUT_MS);

            eventService.emit(sourceSessionId, EventType.THREAD_CREATED,
                    Map.of("pipelineId", pipelineId, "threadSessionId", threadSessionId));
            log.info("{} Phase 2 complete — threads created", prefix);

            // ── Phase 3 & 4: PM Agent + Plan Agent (parallel, conditional) ──
            String triageUpper = triageOutput.toUpperCase();
            boolean hasJiraData = triageUpper.contains("JIRA") || triageUpper.contains("JIRA_DUMP")
                    || triageUpper.contains("TICKET") || hasSheetNamed(filePaths, "Jira");
            boolean hasSmartsheetData = triageUpper.contains("SMARTSHEET") || triageUpper.contains("SMARTSHEET_EXPORT")
                    || triageUpper.contains("MILESTONE") || hasSheetNamed(filePaths, "Smartsheet");

            CompletableFuture<Void> pmFuture = CompletableFuture.completedFuture(null);
            CompletableFuture<Void> planFuture = CompletableFuture.completedFuture(null);

            if (hasJiraData) {
                log.info("{} Phase 3: PM Agent (Jira data detected)", prefix);
                pmFuture = CompletableFuture.runAsync(() -> {
                    try {
                        String pmSessionId = createAgentSession(projectId, "pm", pipelineId);
                        seedUserMessage(pmSessionId, buildPMAgentPrompt(rawContent, triageOutput, filePaths, projectId));
                        agentLoop.startAsync(pmSessionId);
                        waitForCompletion(pmSessionId, PM_TIMEOUT_MS);
                        log.info("{} Phase 3 complete — PM agent finished", prefix);
                    } catch (Exception e) {
                        throw new RuntimeException("PM Agent failed: " + e.getMessage(), e);
                    }
                }, executor);
            } else {
                log.info("{} Phase 3: PM Agent — skipped (no Jira data detected)", prefix);
            }

            if (hasSmartsheetData) {
                log.info("{} Phase 4: Plan Agent (Smartsheet data detected)", prefix);
                planFuture = CompletableFuture.runAsync(() -> {
                    try {
                        String planSessionId = createAgentSession(projectId, "plan-agent", pipelineId);
                        seedUserMessage(planSessionId, buildPlanAgentPrompt(rawContent, triageOutput, filePaths, projectId));
                        agentLoop.startAsync(planSessionId);
                        waitForCompletion(planSessionId, PLAN_TIMEOUT_MS);
                        log.info("{} Phase 4 complete — Plan agent finished", prefix);
                    } catch (Exception e) {
                        throw new RuntimeException("Plan Agent failed: " + e.getMessage(), e);
                    }
                }, executor);
            } else {
                log.info("{} Phase 4: Plan Agent — skipped (no Smartsheet data detected)", prefix);
            }

            // Wait for both PM and Plan to finish before proceeding
            CompletableFuture.allOf(pmFuture, planFuture).join();

            // ── Phase 5: Objective Agent ──
            if (hasJiraData || hasSmartsheetData) {
                log.info("{} Phase 5: Objective Agent", prefix);
                String objectiveSessionId = createAgentSession(projectId, "objective-agent", pipelineId);
                seedUserMessage(objectiveSessionId, buildObjectiveAgentPrompt(projectId));
                agentLoop.startAsync(objectiveSessionId);
                waitForCompletion(objectiveSessionId, OBJECTIVE_TIMEOUT_MS);
                log.info("{} Phase 5 complete — objectives synthesized", prefix);

                // ── Phase 6: Reconcile Agent ──
                log.info("{} Phase 6: Reconcile Agent", prefix);
                String reconcileSessionId = createAgentSession(projectId, "reconcile-agent", pipelineId);
                seedUserMessage(reconcileSessionId, buildReconcileAgentPrompt(projectId));
                agentLoop.startAsync(reconcileSessionId);
                waitForCompletion(reconcileSessionId, RECONCILE_TIMEOUT_MS);
                log.info("{} Phase 6 complete — reconciliation done, delta pack created", prefix);
            } else {
                log.info("{} Phases 5-6: Objective + Reconcile — skipped (single-source intake)", prefix);
            }

            // ── Phase 7: Distillation ──
            log.info("{} Phase 7: Distillation", prefix);
            List<ThreadDocument> recentThreads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
            Instant pipelineStart = sessionRepository.findById(sourceSessionId)
                    .map(SessionDocument::getCreatedAt).orElse(Instant.now().minusSeconds(300));
            int distilled = 0;
            for (ThreadDocument thread : recentThreads) {
                if (thread.getCreatedAt() != null && thread.getCreatedAt().isAfter(pipelineStart)) {
                    distillerService.distillThread(thread.getThreadId());
                    distilled++;
                }
            }
            log.info("{} Phase 7 complete — distilled {} threads", prefix, distilled);

            // Persist summary to source session
            String summary = String.format(
                    "Pipeline complete. Triage classified content, %d thread(s) created and distilled.%s%s",
                    distilled,
                    hasJiraData ? " PM agent processed Jira data." : "",
                    hasSmartsheetData ? " Plan agent processed Smartsheet data." : "");
            MessageDocument summaryMsg = new MessageDocument();
            summaryMsg.setMessageId(UUID.randomUUID().toString());
            summaryMsg.setSessionId(sourceSessionId);
            summaryMsg.setSeq(messageRepository.countBySessionId(sourceSessionId) + 1);
            summaryMsg.setRole("assistant");
            summaryMsg.setAgentId("intake-pipeline");
            summaryMsg.setContent(summary);
            summaryMsg.setTimestamp(Instant.now());
            messageRepository.save(summaryMsg);

            // Mark source session as completed
            sessionRepository.findById(sourceSessionId).ifPresent(s -> {
                s.setStatus(SessionStatus.COMPLETED);
                s.setUpdatedAt(Instant.now());
                sessionRepository.save(s);
            });

            eventService.emit(sourceSessionId, EventType.INTAKE_PIPELINE_COMPLETED,
                    Map.of("pipelineId", pipelineId, "threadsDistilled", distilled));
            log.info("{} Pipeline completed successfully", prefix);

        } catch (Exception e) {
            log.error("{} Pipeline failed: {}", prefix, e.getMessage(), e);
            sessionRepository.findById(sourceSessionId).ifPresent(s -> {
                s.setStatus(SessionStatus.FAILED);
                s.setUpdatedAt(Instant.now());
                sessionRepository.save(s);
            });
            eventService.emit(sourceSessionId, EventType.ERROR,
                    Map.of("pipelineId", pipelineId, "error", e.getMessage()));
        }
    }

    /** Check if any file path likely contains a sheet with the given name prefix. */
    private boolean hasSheetNamed(List<String> filePaths, String namePrefix) {
        if (filePaths == null || filePaths.isEmpty()) return false;
        String lower = namePrefix.toLowerCase();
        return filePaths.stream().anyMatch(p -> p.toLowerCase().contains(lower)
                || p.toLowerCase().endsWith(".xlsx") || p.toLowerCase().endsWith(".xls"));
    }

    // ── Session / message helpers ──

    private String createAgentSession(String projectId, String agentId, String pipelineId) {
        String sessionId = UUID.randomUUID().toString();
        SessionDocument session = new SessionDocument();
        session.setSessionId(sessionId);
        session.setProjectId(projectId);
        session.setStatus(SessionStatus.IDLE);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setMetadata(Map.of(
                "agentId", agentId,
                "type", "pipeline",
                "pipelineId", pipelineId
        ));
        sessionRepository.save(session);
        return sessionId;
    }

    private void seedUserMessage(String sessionId, String content) {
        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setSeq(1);
        msg.setRole("user");
        msg.setContent(content);
        msg.setTimestamp(Instant.now());
        messageRepository.save(msg);
    }

    private void waitForCompletion(String sessionId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            SessionStatus status = sessionRepository.findById(sessionId)
                    .map(SessionDocument::getStatus).orElse(SessionStatus.IDLE);
            if (status == SessionStatus.COMPLETED || status == SessionStatus.FAILED) {
                if (status == SessionStatus.FAILED) {
                    throw new RuntimeException("Agent session " + sessionId + " failed");
                }
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Agent session " + sessionId + " timed out after " + timeoutMs + "ms");
    }

    private String getLastAssistantMessage(String sessionId) {
        List<MessageDocument> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        return messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(MessageDocument::getContent)
                .orElse("");
    }

    // ── Prompt builders ──

    private String buildTriagePrompt(String rawContent) {
        return "Classify and organize the following raw content. Identify distinct topics and for each topic provide:\n\n"
                + "### Topic: [Topic Name]\n"
                + "**Type:** [architecture_decision / open_question / action_item / discussion]\n"
                + "**Decisions:** [list of decisions made]\n"
                + "**Open Questions:** [list of unresolved questions]\n"
                + "**Action Items:** [list with assignees if known]\n"
                + "**Key Content:** [organized notes for this topic]\n\n"
                + "Also identify content source types present in the input. Tag each with one or more of:\n"
                + "JIRA_DUMP, CONFLUENCE_EXPORT, SMARTSHEET_EXPORT, MEETING_NOTES, DESIGN_DOC, FREE_TEXT\n"
                + TOOL_FORMAT
                + "### Tool: classify_content\n"
                + "Call with: name=\"classify_content\", args={\"content\": \"the raw content\"}\n\n"
                + "Call classify_content first to determine the content type, then organize into topics.\n\n"
                + "---\nRAW CONTENT:\n" + rawContent;
    }

    private String buildThreadAgentPrompt(String rawContent, String triageOutput, String projectId) {
        return "You are in **Intake Pipeline Mode**. The triage agent has classified the following raw content "
                + "into distinct topics. Your job is to create one thread per topic.\n\n"
                + "For each topic, call `create_thread` with:\n"
                + "- `projectId`: \"" + projectId + "\"\n"
                + "- `title`: clean descriptive title (no session IDs)\n"
                + "- `content`: organized markdown for the topic\n"
                + "- `decisions`: array of decision strings\n"
                + "- `actions`: array of objects with `text` and `assignee`\n\n"
                + "If a topic matches an existing thread title, call create_thread with the SAME title. "
                + "The tool will automatically append content to the existing thread instead of creating a duplicate.\n"
                + "If topics overlap significantly, merge them into a single thread.\n"
                + TOOL_FORMAT
                + "### Tool: create_thread\n"
                + "Call ONCE per distinct topic. Args:\n"
                + "  name=\"create_thread\"\n"
                + "  args={\"projectId\": \"" + projectId + "\", \"title\": \"Topic Name\", "
                + "\"content\": \"## Content markdown\", "
                + "\"decisions\": [\"decision text\"], "
                + "\"actions\": [{\"text\": \"action text\", \"assignee\": \"Person\"}]}\n\n"
                + "IMPORTANT: Create ALL threads in a SINGLE response — output one <tool_call> block per topic.\n"
                + "Do NOT create threads one at a time across multiple responses.\n"
                + "You MUST call create_thread for each topic. Do NOT skip any topics.\n\n"
                + "---\nTRIAGE OUTPUT:\n" + triageOutput + "\n\n"
                + "---\nORIGINAL RAW CONTENT:\n" + rawContent;
    }

    private String buildPMAgentPrompt(String rawContent, String triageOutput,
                                       List<String> filePaths, String projectId) {
        String fileSection = filePaths.isEmpty() ? ""
                : "\n\nFILE PATHS (use `excel` tool to read):\n" + String.join("\n", filePaths);

        return "You are in **Intake Pipeline Mode — PM Phase**. Process Jira ticket data from the intake.\n\n"
                + "## Instructions\n"
                + "1. If file paths are provided, use the `excel` tool to read the \"Jira_Export\" sheet.\n"
                + "2. Otherwise, extract Jira ticket data from the triage output and raw content below.\n"
                + "3. For each ticket found, call `create_ticket` with:\n"
                + "   - `projectId`: \"" + projectId + "\"\n"
                + "   - `title`: include original key (e.g. J-101) + summary\n"
                + "   - `description`: status, owner, epic info\n"
                + "   - `priority`: HIGH, MEDIUM, or LOW\n"
                + "4. Flag tickets with empty/missing Epic as orphaned work in the description.\n"
                + TOOL_FORMAT
                + "### Tool: create_ticket\n"
                + "Call ONCE per ticket. Args:\n"
                + "  name=\"create_ticket\"\n"
                + "  args={\"projectId\": \"" + projectId + "\", \"title\": \"J-101: Build Evidence API\", "
                + "\"description\": \"Epic: Evidence Service | Status: In Progress | Owner: Alice\", \"priority\": \"HIGH\"}\n\n"
                + "### Tool: excel (if file paths provided)\n"
                + "  name=\"excel\"\n"
                + "  args={\"operation\": \"read\", \"file_path\": \"path/to/file.xlsx\", \"sheet_name\": \"Jira_Export\"}\n\n"
                + "IMPORTANT: Output ALL create_ticket calls in a SINGLE response using <tool_call> blocks.\n"
                + "You MUST call create_ticket for EACH ticket. Do NOT skip any.\n"
                + fileSection + "\n\n"
                + "---\nTRIAGE OUTPUT:\n" + triageOutput + "\n\n"
                + "---\nORIGINAL RAW CONTENT:\n" + rawContent;
    }

    private String buildPlanAgentPrompt(String rawContent, String triageOutput,
                                         List<String> filePaths, String projectId) {
        String fileSection = filePaths.isEmpty() ? ""
                : "\n\nFILE PATHS (use `excel` tool to read):\n" + String.join("\n", filePaths);

        return "You are in **Intake Pipeline Mode — Plan Phase**. Process Smartsheet / project plan data.\n\n"
                + "## Instructions\n"
                + "1. If file paths are provided, use the `excel` tool to read the \"Smartsheet_Plan\" sheet.\n"
                + "2. Otherwise, extract plan data from the triage output and raw content below.\n"
                + "3. For each Phase, call `create_phase`.\n"
                + "4. For each Milestone, call `create_milestone`.\n"
                + TOOL_FORMAT
                + "### Tool: create_phase\n"
                + "  name=\"create_phase\"\n"
                + "  args={\"projectId\": \"" + projectId + "\", \"name\": \"Phase 1: Evidence Service\", \"sortOrder\": 1, \"description\": \"...\"}\n\n"
                + "### Tool: create_milestone\n"
                + "  name=\"create_milestone\"\n"
                + "  args={\"projectId\": \"" + projectId + "\", \"name\": \"Evidence Service Ready\", \"targetDate\": \"2026-03-15\", \"owner\": \"Bob\"}\n\n"
                + "### Tool: excel (if file paths provided)\n"
                + "  name=\"excel\"\n"
                + "  args={\"operation\": \"read\", \"file_path\": \"path/to/file.xlsx\", \"sheet_name\": \"Smartsheet_Plan\"}\n\n"
                + "IMPORTANT: Output ALL create_phase and create_milestone calls in a SINGLE response.\n"
                + "You MUST call create_phase and create_milestone for ALL items. Preserve original dates.\n"
                + fileSection + "\n\n"
                + "---\nTRIAGE OUTPUT:\n" + triageOutput + "\n\n"
                + "---\nORIGINAL RAW CONTENT:\n" + rawContent;
    }

    private String buildObjectiveAgentPrompt(String projectId) {
        return "You are in **Intake Pipeline Mode — Objective Phase**. Synthesize objectives from the data "
                + "that PM Agent and Plan Agent have just created for project: " + projectId + ".\n\n"
                + "## Instructions\n"
                + "1. Call `compute_coverage` to analyze all tickets and objectives for the project.\n"
                + "2. Derive high-level objectives from the ticket and thread data.\n"
                + "3. Map tickets to objectives and report coverage percentages.\n"
                + "4. Identify any unmapped tickets or threads.\n"
                + TOOL_FORMAT
                + "### Tool: compute_coverage\n"
                + "  name=\"compute_coverage\"\n"
                + "  args={\"projectId\": \"" + projectId + "\"}\n\n"
                + "Call compute_coverage first, then summarize the results.";
    }

    private String buildReconcileAgentPrompt(String projectId) {
        return "You are in **Intake Pipeline Mode — Reconcile Phase**. Cross-reference all project data "
                + "for project: " + projectId + " and produce a delta pack.\n\n"
                + "## Instructions\n"
                + "1. First read ALL project data using the tools below.\n"
                + "2. Cross-reference sources and detect:\n"
                + "   - OWNER_MISMATCH: different owners across sources\n"
                + "   - DATE_DRIFT: milestone dates differ from plan\n"
                + "   - MISSING_EPIC: tickets without epic grouping\n"
                + "   - ORPHANED_WORK: tickets not mapped to any objective\n"
                + "   - COVERAGE_GAP: objectives with no backing tickets\n"
                + "3. Call `create_delta_pack` with ALL detected deltas.\n"
                + "4. For CRITICAL findings, also call `create_blindspot`.\n"
                + TOOL_FORMAT
                + "### Step 1 — Read data (call all three):\n"
                + "  read_tickets: args={\"projectId\": \"" + projectId + "\"}\n"
                + "  read_objectives: args={\"projectId\": \"" + projectId + "\"}\n"
                + "  read_phases: args={\"projectId\": \"" + projectId + "\"}\n\n"
                + "### Step 2 — After analyzing, create delta pack:\n"
                + "  create_delta_pack: args={\"projectId\": \"" + projectId + "\", \"deltas\": ["
                + "{\"deltaType\": \"OWNER_MISMATCH\", \"severity\": \"HIGH\", \"title\": \"...\", "
                + "\"description\": \"...\", \"sourceA\": \"Jira\", \"sourceB\": \"Smartsheet\", "
                + "\"suggestedAction\": \"...\"}]}\n\n"
                + "### Step 3 — For CRITICAL findings:\n"
                + "  create_blindspot: args={\"projectId\": \"" + projectId + "\", \"title\": \"...\", "
                + "\"category\": \"ORPHANED_TICKET\", \"severity\": \"HIGH\", \"description\": \"...\"}\n\n"
                + "Be thorough — compare every ticket against milestones and objectives.";
    }
}
