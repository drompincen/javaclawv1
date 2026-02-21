package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineResponse;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class IntakePipelineService {

    private static final Logger log = LoggerFactory.getLogger(IntakePipelineService.class);
    private static final long TRIAGE_TIMEOUT_MS = 90_000;
    private static final long GENERALIST_TIMEOUT_MS = 300_000;
    private static final long OBJECTIVE_TIMEOUT_MS = 120_000;
    private static final long RECONCILE_TIMEOUT_MS = 180_000;
    private static final long RESOURCE_TIMEOUT_MS = 180_000;
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
    private final MemoryRepository memoryRepository;
    private final EventService eventService;
    private final AgentLoop agentLoop;
    private final ContentExtractorService contentExtractor;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "intake-pipeline");
        t.setDaemon(true);
        return t;
    });

    public IntakePipelineService(SessionRepository sessionRepository,
                                 MessageRepository messageRepository,
                                 ThreadRepository threadRepository,
                                 MemoryRepository memoryRepository,
                                 EventService eventService,
                                 AgentLoop agentLoop,
                                 ContentExtractorService contentExtractor) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.threadRepository = threadRepository;
        this.memoryRepository = memoryRepository;
        this.eventService = eventService;
        this.agentLoop = agentLoop;
        this.contentExtractor = contentExtractor;
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

    private void runPipeline(String pipelineId, String projectId, String rawContentParam,
                             String sourceSessionId, List<String> filePaths) {
        String prefix = "[pipeline-" + pipelineId.substring(0, 8) + "]";
        try {
            String rawContent = rawContentParam;

            // ── Phase 0: Store raw content as project memory ──
            log.info("{} Phase 0: Storing raw content as memory", prefix);
            MemoryDocument intakeMemory = new MemoryDocument();
            intakeMemory.setMemoryId(UUID.randomUUID().toString());
            intakeMemory.setKey("intake-" + pipelineId.substring(0, 8));
            intakeMemory.setScope(MemoryDocument.MemoryScope.PROJECT);
            intakeMemory.setProjectId(projectId);
            intakeMemory.setContent(rawContent);
            intakeMemory.setCreatedBy("intake-pipeline");
            intakeMemory.setCreatedAt(Instant.now());
            intakeMemory.setUpdatedAt(Instant.now());
            intakeMemory.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
            intakeMemory.setTags(List.of("intake", "raw-content"));
            memoryRepository.save(intakeMemory);
            log.info("{} Phase 0 complete — raw content saved as memory {}", prefix, intakeMemory.getKey());

            // ── Detect pasted content format ──
            if (rawContent != null && !rawContent.isBlank()) {
                String detectedFormat = contentExtractor.detectTextFormat(rawContent);
                if (!"plain text".equals(detectedFormat)) {
                    log.info("{} Detected pasted content format: {}", prefix, detectedFormat);
                    rawContent = "[Detected format: " + detectedFormat + "]\n\n" + rawContent;
                }
            }

            // ── Auto-extract file content ──
            if (!filePaths.isEmpty()) {
                log.info("{} Extracting content from {} uploaded file(s)", prefix, filePaths.size());
                String fileContent = contentExtractor.extractContent(filePaths);
                rawContent = (rawContent != null ? rawContent + "\n\n" : "") + fileContent;
                log.info("{} File content extracted ({} chars)", prefix, fileContent.length());
            }

            // Capture as effectively-final for use in lambdas below
            final String enrichedContent = rawContent;

            // ── Phase 1: Triage ──
            log.info("{} Phase 1: Triage", prefix);
            String triageSessionId = createAgentSession(projectId, "intake-triage", pipelineId);
            seedUserMessage(triageSessionId, buildTriagePrompt(enrichedContent, projectId, filePaths));
            agentLoop.startAsync(triageSessionId);
            waitForCompletion(triageSessionId, TRIAGE_TIMEOUT_MS);

            String triageOutput = getLastAssistantMessage(triageSessionId);
            eventService.emit(sourceSessionId, EventType.INTAKE_CLASSIFIED,
                    Map.of("pipelineId", pipelineId, "triageSessionId", triageSessionId));
            log.info("{} Phase 1 complete — triage classified", prefix);

            // ── Parse triage agent's routing decisions ──
            boolean routeToThreads = parseRoute(triageOutput, "THREAD");
            boolean routeToTickets = parseRoute(triageOutput, "TICKETS");
            boolean routeToPlan = parseRoute(triageOutput, "PLAN");
            boolean routeToResources = parseRoute(triageOutput, "RESOURCES");
            // Content-based routing fallback: scan enrichedContent for signals the triage agent missed
            Map<String, Boolean> contentSignals = detectContentSignals(enrichedContent);
            if (!routeToPlan && Boolean.TRUE.equals(contentSignals.get("PLAN"))) {
                log.info("{} Content heuristic override: PLAN=true", prefix);
                routeToPlan = true;
            }
            if (!routeToResources && Boolean.TRUE.equals(contentSignals.get("RESOURCES"))) {
                log.info("{} Content heuristic override: RESOURCES=true", prefix);
                routeToResources = true;
            }
            if (!routeToTickets && Boolean.TRUE.equals(contentSignals.get("TICKETS"))) {
                log.info("{} Content heuristic override: TICKETS=true", prefix);
                routeToTickets = true;
            }

            log.info("{} Routing: THREAD={}, TICKETS={}, PLAN={}, RESOURCES={}",
                    prefix, routeToThreads, routeToTickets, routeToPlan, routeToResources);

            // ── Phase 2: Generalist hydration (single pass) ──
            log.info("{} Phase 2: Generalist hydration", prefix);
            String hydrationSessionId = createAgentSession(projectId, "generalist", pipelineId);
            seedUserMessage(hydrationSessionId, buildGeneralistHydrationPrompt(
                    enrichedContent, triageOutput, projectId, filePaths,
                    routeToThreads, routeToTickets, routeToPlan, routeToResources));
            agentLoop.startAsync(hydrationSessionId);
            waitForCompletion(hydrationSessionId, GENERALIST_TIMEOUT_MS);

            eventService.emit(sourceSessionId, EventType.THREAD_CREATED,
                    Map.of("pipelineId", pipelineId, "hydrationSessionId", hydrationSessionId));
            log.info("{} Phase 2 complete — generalist hydration done", prefix);

            // ── Phase 5 & 6: Objective + Reconcile (conditional on tickets or plan) ──
            if (routeToTickets || routeToPlan) {
                try {
                    log.info("{} Phase 5: Objective Agent", prefix);
                    String objectiveSessionId = createAgentSession(projectId, "objective-agent", pipelineId);
                    seedUserMessage(objectiveSessionId, buildObjectiveAgentPrompt(projectId));
                    agentLoop.startAsync(objectiveSessionId);
                    waitForCompletion(objectiveSessionId, OBJECTIVE_TIMEOUT_MS);
                    log.info("{} Phase 5 complete — objectives synthesized", prefix);
                } catch (Exception e) {
                    log.warn("{} Phase 5 failed (non-fatal): {}", prefix, e.getMessage());
                }

                try {
                    log.info("{} Phase 6: Reconcile Agent", prefix);
                    String reconcileSessionId = createAgentSession(projectId, "reconcile-agent", pipelineId);
                    seedUserMessage(reconcileSessionId, buildReconcileAgentPrompt(projectId));
                    agentLoop.startAsync(reconcileSessionId);
                    waitForCompletion(reconcileSessionId, RECONCILE_TIMEOUT_MS);
                    log.info("{} Phase 6 complete — reconciliation done, delta pack created", prefix);
                } catch (Exception e) {
                    log.warn("{} Phase 6 failed (non-fatal): {}", prefix, e.getMessage());
                }
            } else {
                log.info("{} Phases 5-6: Objective + Reconcile — skipped (single-source intake)", prefix);
            }

            // ── Phase 7: Resource Agent (conditional) ──
            if (routeToResources || routeToTickets) {
                try {
                    log.info("{} Phase 7: Resource Agent", prefix);
                    String resourceSessionId = createAgentSession(projectId, "resource-agent", pipelineId);
                    seedUserMessage(resourceSessionId, buildResourceAgentPrompt(projectId));
                    agentLoop.startAsync(resourceSessionId);
                    waitForCompletion(resourceSessionId, RESOURCE_TIMEOUT_MS);
                    log.info("{} Phase 7 complete — resource analysis done", prefix);
                } catch (Exception e) {
                    log.warn("{} Phase 7 failed (non-fatal): {}", prefix, e.getMessage());
                }
            } else {
                log.info("{} Phase 7: Resource Agent — skipped (no TICKETS or RESOURCES route)", prefix);
            }

            // Persist summary to source session
            int threadCount = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId).size();
            String summary = String.format(
                    "Pipeline complete. Triage classified content, generalist hydrated %d thread(s).%s%s%s",
                    threadCount,
                    routeToTickets ? " Tickets created." : "",
                    routeToPlan ? " Plan phases created." : "",
                    routeToResources ? " Resources created." : "");
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
                    Map.of("pipelineId", pipelineId, "threadCount", threadCount));
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

    /** Parse triage agent's routing block for a specific route decision.
     *  Handles markdown formatting like **PLAN:** yes, `PLAN`: yes, _PLAN_: yes */
    private boolean parseRoute(String triageOutput, String routeName) {
        Pattern p = Pattern.compile(
                "^[\\s*_`]*" + routeName + "[\\s*_`]*[:\\-=]\\s*[*_`]*(yes|true)[*_`]*",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        return p.matcher(triageOutput).find();
    }

    /**
     * Deterministic keyword scan to detect content signals the triage agent may have missed.
     * Returns a map with PLAN, RESOURCES, TICKETS keys mapped to true/false.
     */
    private Map<String, Boolean> detectContentSignals(String content) {
        if (content == null || content.isBlank()) {
            return Map.of("PLAN", false, "RESOURCES", false, "TICKETS", false);
        }
        String lower = content.toLowerCase();

        // PLAN: "objective" + ("outcome" or "committed" or "proposed"), or "sprint goal/objective"
        boolean plan = (lower.contains("objective") && (lower.contains("outcome")
                || lower.contains("committed") || lower.contains("proposed")))
                || lower.contains("measurable signal")
                || lower.contains("sprint goal") || lower.contains("sprint objective");

        // RESOURCES: ("capacity" + "availability"), or "resource allocation", or ("engineer" + "skills")
        boolean resources = (lower.contains("capacity") && lower.contains("availability"))
                || lower.contains("resource allocation")
                || (lower.contains("engineer") && lower.contains("skills"));

        // TICKETS: 3+ Jira keys, or CSV/JSON headers with key+summary+status
        boolean tickets = false;
        java.util.regex.Matcher jiraMatcher = Pattern.compile("[A-Z]{2,}-\\d+").matcher(content);
        int jiraCount = 0;
        while (jiraMatcher.find()) { jiraCount++; if (jiraCount >= 3) { tickets = true; break; } }
        if (!tickets) {
            tickets = lower.contains("key") && lower.contains("summary") && lower.contains("status");
        }

        return Map.of("PLAN", plan, "RESOURCES", resources, "TICKETS", tickets);
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

    private String buildTriagePrompt(String rawContent, String projectId, List<String> filePaths) {
        StringBuilder sb = new StringBuilder();

        // Existing project context from memories
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Existing Project Context (memories from prior intakes)\n");
            for (MemoryDocument mem : memories) {
                String snippet = truncate(mem.getContent(), 120);
                String date = mem.getCreatedAt() != null ? mem.getCreatedAt().toString().substring(0, 10) : "unknown";
                sb.append("- [").append(mem.getKey()).append("] ").append(snippet).append(" (").append(date).append(")\n");
            }
            sb.append("\nClassify the NEW RAW CONTENT below. Use the existing memories for context ")
              .append("(e.g., to recognize content that updates prior topics).\n\n");
        }

        // File path hints
        if (filePaths != null && !filePaths.isEmpty()) {
            sb.append("## Attached Files\n");
            for (String fp : filePaths) {
                sb.append("- ").append(fp).append("\n");
            }
            sb.append("\nUse `excel` tool to read these files if relevant to your classification.\n\n");
        }

        sb.append("The raw content below may be in ANY format: plain text, CSV, TSV, JSON, XML, HTML, Markdown, YAML, ")
          .append("Jira export, meeting notes, Smartsheet plan data, or a mix. ")
          .append("A [Detected format: ...] header may appear if the system identified the format. ")
          .append("Parse the content accordingly.\n\n")
          .append("Classify and organize the following raw content. Identify distinct topics and for each topic provide:\n\n")
          .append("### Topic: [Topic Name]\n")
          .append("**Type:** [architecture_decision / open_question / action_item / discussion]\n")
          .append("**Decisions:** [list of decisions made]\n")
          .append("**Open Questions:** [list of unresolved questions]\n")
          .append("**Action Items:** [list with assignees if known]\n")
          .append("**Key Content:** [organized notes for this topic]\n\n")
          .append("## Classification Output (REQUIRED)\n\n")
          .append("After organizing content into topics, you MUST output a routing block:\n\n")
          .append("### Routes\n")
          .append("THREAD: yes/no — topics, ideas, architecture discussions, meeting notes, designs\n")
          .append("TICKETS: yes/no — Jira exports, task lists, bug reports, ticket dumps, work items with keys like ABC-123\n")
          .append("PLAN: yes/no — sprint objectives, OKRs, milestone schedules, phase definitions, timelines, ")
          .append("content with 'Objective' + 'Outcome' or 'COMMITTED'/'PROPOSED' status\n")
          .append("RESOURCES: yes/no — team member info with capacity/availability/skills, resource allocation, ")
          .append("workload data, people with roles and assignments\n\n")
          .append("A single piece of content can trigger MULTIPLE routes. Set each to \"yes\" if you identified ")
          .append("that specific content type anywhere in the input.\n")
          .append("This routing block drives which downstream agents will process the content.\n")
          .append(TOOL_FORMAT)
          .append("### Tool: classify_content\n")
          .append("Call with: name=\"classify_content\", args={\"content\": \"the raw content\"}\n\n")
          .append("Call classify_content first to determine the content type, then organize into topics.\n\n")
          .append("---\nNEW RAW CONTENT:\n").append(rawContent);

        return sb.toString();
    }

    private String buildGeneralistHydrationPrompt(String rawContent, String triageOutput,
                                                    String projectId, List<String> filePaths,
                                                    boolean routeToThreads, boolean routeToTickets,
                                                    boolean routeToPlan, boolean routeToResources) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are in **Intake Hydration Mode**. The triage agent has classified the following raw content. ")
          .append("Your job is to create ALL appropriate domain objects in a SINGLE pass using tool calls.\n\n");

        // Existing threads for context
        List<ThreadDocument> existingThreads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        if (!existingThreads.isEmpty()) {
            sb.append("## Existing Threads for This Project\n");
            sb.append(buildExistingThreadContext(existingThreads));
            sb.append("\n\n");
        }

        // Project memories for context
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Project Memories (recent intakes)\n");
            sb.append(buildMemoryContext(memories));
            sb.append("\n\n");
        }

        // File path hints
        if (filePaths != null && !filePaths.isEmpty()) {
            sb.append("## Attached Files\n");
            for (String fp : filePaths) {
                sb.append("- ").append(fp).append("\n");
            }
            sb.append("\nUse `excel` tool to read these files if they contain structured data.\n\n");
        }

        // Routing summary — THREAD is always yes because generalist always creates threads
        sb.append("## Triage Routing Decisions\n");
        sb.append("- THREAD: yes (always create at least one thread per distinct topic)\n");
        sb.append("- TICKETS: ").append(routeToTickets ? "yes" : "no").append("\n");
        sb.append("- PLAN: ").append(routeToPlan ? "yes" : "no").append("\n");
        sb.append("- RESOURCES: ").append(routeToResources ? "yes" : "no").append("\n\n");

        sb.append("## Instructions\n")
          .append("Do NOT assume any specific format — the content could be CSV, JSON, plain text, meeting notes, ")
          .append("or any other format. Parse it as-is and extract structured data.\n\n")
          .append("For the identified routes, create the appropriate domain objects:\n\n");

        // Always create threads (content always produces threads)
        sb.append("### Threads (MANDATORY — create FIRST, before any other objects)\n")
          .append("You MUST call `create_thread` at least once. Group content by epic, theme, or topic.\n")
          .append("For ticket/Jira data: create one thread per epic or functional area (e.g., \"Payment Processing\", \"Webhook Integration\").\n")
          .append("For discussions: create one thread per distinct topic.\n")
          .append("  args={\"projectId\": \"").append(projectId).append("\", \"title\": \"Topic Name\", ")
          .append("\"content\": \"## Markdown summary of this topic/epic\", ")
          .append("\"decisions\": [\"decision text\"], ")
          .append("\"actions\": [{\"text\": \"action text\", \"assignee\": \"Person\"}]}\n")
          .append("If a topic matches an existing thread's title, create_thread will append to it.\n\n");

        if (routeToTickets) {
            sb.append("### Tickets\n")
              .append("Call `create_ticket` for each work item/task/bug:\n")
              .append("  args={\"projectId\": \"").append(projectId).append("\", \"title\": \"J-101: Summary\", ")
              .append("\"description\": \"Epic: X | Status: Y | Owner: Z\", \"priority\": \"HIGH\"}\n\n");
        }

        if (routeToPlan) {
            sb.append("### Objectives (IMPORTANT — create for EACH sprint objective or OKR)\n")
              .append("Call `create_objective` for EACH objective, goal, or OKR found in the content.\n")
              .append("ALWAYS create new objective records even if similar objectives already exist in the project.\n")
              .append("Each intake creates its own set of records — do NOT deduplicate.\n")
              .append("  args={\"projectId\": \"").append(projectId).append("\", ")
              .append("\"title\": \"Objective outcome description\", ")
              .append("\"description\": \"Measurable signal / success criteria\", ")
              .append("\"status\": \"COMMITTED\", ")
              .append("\"week\": \"Sprint 42\"}\n")
              .append("Status should be COMMITTED or PROPOSED based on the content.\n")
              .append("If the content has 3 objectives, you MUST make 3 create_objective calls.\n\n");

            sb.append("### Phases & Milestones\n")
              .append("Call `create_phase` for each project phase:\n")
              .append("  args={\"projectId\": \"").append(projectId).append("\", \"name\": \"Phase 1\", ")
              .append("\"sortOrder\": 1, \"description\": \"...\"}\n")
              .append("Call `create_milestone` for each milestone:\n")
              .append("  args={\"projectId\": \"").append(projectId).append("\", \"name\": \"Milestone\", ")
              .append("\"targetDate\": \"2026-03-15\", \"owner\": \"Bob\"}\n\n");
        }

        if (routeToResources) {
            sb.append("### Resources (IMPORTANT — create for EACH team member mentioned by name)\n")
              .append("Call `create_resource` for EVERY person mentioned in the content.\n")
              .append("Look in: attendees lists, resource allocation sections, action items, team rosters.\n")
              .append("Extract their full name, role, capacity, availability, and skills from context.\n")
              .append("  args={\"projectId\": \"").append(projectId).append("\", \"name\": \"Joe Martinez\", ")
              .append("\"role\": \"ENGINEER\", \"skills\": [\"Java\", \"Spring\"], ")
              .append("\"capacity\": 100, \"availability\": 0.8}\n")
              .append("Do NOT skip any named person. Do NOT invent names not in the content.\n")
              .append("If 3 people are named, you MUST make 3 create_resource calls.\n\n");
        }

        sb.append(TOOL_FORMAT)
          .append("IMPORTANT: Output ALL tool calls in a SINGLE response using <tool_call> blocks.\n")
          .append("Do NOT create objects one at a time across multiple responses.\n")
          .append("You MUST call the appropriate create_* tool for EACH item. Do NOT skip any.\n")
          .append("CRITICAL: You MUST include create_thread calls. Threads are MANDATORY. ")
          .append("If you only create tickets without threads, the pipeline will fail.\n\n")
          .append("---\nTRIAGE OUTPUT (routing decisions already extracted above — ignore any Routes block below):\n")
          .append(stripRoutingBlock(triageOutput)).append("\n\n")
          .append("---\nORIGINAL RAW CONTENT:\n").append(rawContent);

        return sb.toString();
    }

    /** @deprecated Use {@link #buildGeneralistHydrationPrompt} instead. Kept for reference during migration. */
    @Deprecated
    private String buildThreadAgentPrompt(String rawContent, String triageOutput, String projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are in **Intake Pipeline Mode**. The triage agent has classified the following raw content ")
          .append("into distinct topics. Your job is to create one thread per topic.\n\n");

        // Existing threads for context
        List<ThreadDocument> existingThreads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        if (!existingThreads.isEmpty()) {
            sb.append("## Existing Threads for This Project\n");
            sb.append(buildExistingThreadContext(existingThreads));
            sb.append("\n");
        }

        // Project memories for context
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Project Memories (recent intakes)\n");
            sb.append(buildMemoryContext(memories));
            sb.append("\n");
        }

        sb.append("For each topic, call `create_thread` with:\n")
          .append("- `projectId`: \"").append(projectId).append("\"\n")
          .append("- `title`: clean descriptive title (no session IDs)\n")
          .append("- `content`: organized markdown for the topic\n")
          .append("- `decisions`: array of decision strings\n")
          .append("- `actions`: array of objects with `text` and `assignee`\n\n")
          .append("If a topic matches an existing thread's topic, call create_thread with the SAME title. ")
          .append("The tool will automatically append content to the existing thread instead of creating a duplicate.\n")
          .append("If it is a genuinely new idea/train of thought, create a new thread.\n")
          .append("Distill the relevant content into concise key points for each thread.\n")
          .append("Include decisions, action items, and summarized content.\n")
          .append("If topics overlap significantly, merge them into a single thread.\n")
          .append(TOOL_FORMAT)
          .append("### Tool: create_thread\n")
          .append("Call ONCE per distinct topic. Args:\n")
          .append("  name=\"create_thread\"\n")
          .append("  args={\"projectId\": \"").append(projectId).append("\", \"title\": \"Topic Name\", ")
          .append("\"content\": \"## Content markdown\", ")
          .append("\"decisions\": [\"decision text\"], ")
          .append("\"actions\": [{\"text\": \"action text\", \"assignee\": \"Person\"}]}\n\n")
          .append("IMPORTANT: Create ALL threads in a SINGLE response — output one <tool_call> block per topic.\n")
          .append("Do NOT create threads one at a time across multiple responses.\n")
          .append("You MUST call create_thread for each topic. Do NOT skip any topics.\n\n")
          .append("---\nTRIAGE OUTPUT:\n").append(triageOutput).append("\n\n")
          .append("---\nORIGINAL RAW CONTENT:\n").append(rawContent);

        return sb.toString();
    }

    /** @deprecated Use {@link #buildGeneralistHydrationPrompt} instead. Kept for reference during migration. */
    @Deprecated
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

    /** @deprecated Use {@link #buildGeneralistHydrationPrompt} instead. Kept for reference during migration. */
    @Deprecated
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
        StringBuilder sb = new StringBuilder();
        sb.append("You are in **Intake Pipeline Mode — Objective Phase**. Synthesize objectives from the data ")
          .append("that PM Agent and Plan Agent have just created for project: ").append(projectId).append(".\n\n");

        // Load existing threads for context
        List<ThreadDocument> threads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        if (!threads.isEmpty()) {
            sb.append("## Existing Threads\n").append(buildExistingThreadContext(threads)).append("\n\n");
        }

        // Load project memories (prior coverage analyses, intake context)
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Project Memories\n").append(buildMemoryContext(memories)).append("\n\n");
        }

        sb.append("## Instructions\n")
          .append("1. Call `compute_coverage` to analyze all tickets and objectives for the project.\n")
          .append("2. Derive high-level objectives from the ticket and thread data.\n")
          .append("3. Map tickets to objectives and report coverage percentages.\n")
          .append("4. Identify any unmapped tickets or threads.\n")
          .append(TOOL_FORMAT)
          .append("### Tool: compute_coverage\n")
          .append("  name=\"compute_coverage\"\n")
          .append("  args={\"projectId\": \"").append(projectId).append("\"}\n\n")
          .append("Call compute_coverage first, then summarize the results.");

        return sb.toString();
    }

    // ── Context builders ──

    private String buildExistingThreadContext(List<ThreadDocument> threads) {
        return threads.stream()
                .map(t -> "- **" + t.getTitle() + "**: " + truncate(
                        t.getContent() != null ? t.getContent() : "(no content)", 100))
                .collect(Collectors.joining("\n"));
    }

    private String buildMemoryContext(List<MemoryDocument> memories) {
        return memories.stream()
                .map(m -> "- [" + m.getKey() + "] " + truncate(m.getContent(), 120))
                .collect(Collectors.joining("\n"));
    }

    /** Strip routing block (### Routes ... THREAD/TICKETS/PLAN/RESOURCES lines) from triage output
     *  to prevent conflicting routing signals in the generalist prompt. */
    private String stripRoutingBlock(String triageOutput) {
        if (triageOutput == null) return "";
        return triageOutput.replaceAll("(?im)^###\\s*Routes\\s*$", "")
                .replaceAll("(?im)^[\\s*_`]*(THREAD|TICKETS|PLAN|RESOURCES)[\\s*_`]*[:\\-=].*$", "")
                .replaceAll("(?m)^\\s*\n{2,}", "\n");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String buildResourceAgentPrompt(String projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are in **Intake Pipeline Mode — Resource Phase**. Analyze team capacity and ")
          .append("resource allocation for project: ").append(projectId).append(".\n\n");

        // Load project memories for context
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Project Memories\n").append(buildMemoryContext(memories)).append("\n\n");
        }

        // Load existing threads for context
        List<ThreadDocument> threads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        if (!threads.isEmpty()) {
            sb.append("## Existing Threads\n").append(buildExistingThreadContext(threads)).append("\n\n");
        }

        sb.append("## Instructions\n")
          .append("1. Call `read_resources` to load team members for the project.\n")
          .append("2. Call `read_tickets` to see all tickets (including newly created ones).\n")
          .append("3. Call `capacity_report` to compute load per resource.\n")
          .append("4. Flag any overloaded resources (>100% allocation).\n")
          .append("5. Call `suggest_assignments` for unassigned tickets if any exist.\n")
          .append("6. Summarize findings.\n")
          .append(TOOL_FORMAT)
          .append("### Step 1 — Load data:\n")
          .append("  read_resources: args={\"projectId\": \"").append(projectId).append("\"}\n")
          .append("  read_tickets: args={\"projectId\": \"").append(projectId).append("\"}\n\n")
          .append("### Step 2 — Compute capacity:\n")
          .append("  capacity_report: args={\"projectId\": \"").append(projectId).append("\"}\n\n")
          .append("### Step 3 — Suggest assignments (if unassigned tickets exist):\n")
          .append("  suggest_assignments: args={\"projectId\": \"").append(projectId).append("\"}\n\n")
          .append("Flag resources at >100% as OVERLOADED. Summarize total allocation per team member.");

        return sb.toString();
    }

    private String buildReconcileAgentPrompt(String projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are in **Intake Pipeline Mode — Reconcile Phase**. Cross-reference all project data ")
          .append("for project: ").append(projectId).append(" and produce a delta pack.\n\n");

        // Load project memories (prior analyses, intake context)
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        if (!memories.isEmpty()) {
            sb.append("## Project Memories (prior analyses)\n").append(buildMemoryContext(memories)).append("\n\n");
        }

        // Load existing threads for context
        List<ThreadDocument> threads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        if (!threads.isEmpty()) {
            sb.append("## Existing Threads\n").append(buildExistingThreadContext(threads)).append("\n\n");
        }

        sb.append("## Instructions\n")
          .append("1. First read ALL project data using the tools below.\n")
          .append("2. Cross-reference sources and detect:\n")
          .append("   - OWNER_MISMATCH: different owners across sources\n")
          .append("   - DATE_DRIFT: milestone dates differ from plan\n")
          .append("   - MISSING_EPIC: tickets without epic grouping\n")
          .append("   - ORPHANED_WORK: tickets not mapped to any objective\n")
          .append("   - COVERAGE_GAP: objectives with no backing tickets\n")
          .append("   - CAPACITY_OVERLOAD: team members assigned beyond 100% capacity\n")
          .append("3. Call `create_delta_pack` with ALL detected deltas.\n")
          .append("4. For CRITICAL findings, also call `create_blindspot`.\n")
          .append(TOOL_FORMAT)
          .append("### Step 1 — Read data (call all three):\n")
          .append("  read_tickets: args={\"projectId\": \"").append(projectId).append("\"}\n")
          .append("  read_objectives: args={\"projectId\": \"").append(projectId).append("\"}\n")
          .append("  read_phases: args={\"projectId\": \"").append(projectId).append("\"}\n\n")
          .append("### Step 2 — After analyzing, create delta pack:\n")
          .append("  create_delta_pack: args={\"projectId\": \"").append(projectId).append("\", \"deltas\": [")
          .append("{\"deltaType\": \"OWNER_MISMATCH\", \"severity\": \"HIGH\", \"title\": \"...\", ")
          .append("\"description\": \"...\", \"sourceA\": \"Jira\", \"sourceB\": \"Smartsheet\", ")
          .append("\"suggestedAction\": \"...\"}]}\n\n")
          .append("### Step 3 — For CRITICAL findings:\n")
          .append("  create_blindspot: args={\"projectId\": \"").append(projectId).append("\", \"title\": \"...\", ")
          .append("\"category\": \"ORPHANED_TICKET\", \"severity\": \"HIGH\", \"description\": \"...\"}\n\n")
          .append("Be thorough — compare every ticket against milestones and objectives.");

        return sb.toString();
    }
}
