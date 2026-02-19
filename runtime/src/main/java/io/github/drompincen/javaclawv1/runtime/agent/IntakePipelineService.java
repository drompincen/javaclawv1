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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IntakePipelineService {

    private static final Logger log = LoggerFactory.getLogger(IntakePipelineService.class);
    private static final long TRIAGE_TIMEOUT_MS = 60_000;
    private static final long THREAD_AGENT_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 200;

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

    public IntakePipelineResponse startPipeline(String projectId, String rawContent, String sourceSessionId) {
        String pipelineId = UUID.randomUUID().toString();
        log.info("[pipeline-{}] Starting intake pipeline for project {}", pipelineId.substring(0, 8), projectId);

        eventService.emit(sourceSessionId, EventType.INTAKE_PIPELINE_STARTED,
                Map.of("pipelineId", pipelineId, "projectId", projectId));

        executor.submit(() -> runPipeline(pipelineId, projectId, rawContent, sourceSessionId));

        return new IntakePipelineResponse(pipelineId, sourceSessionId, "PIPELINE_STARTED");
    }

    private void runPipeline(String pipelineId, String projectId, String rawContent, String sourceSessionId) {
        String prefix = "[pipeline-" + pipelineId.substring(0, 8) + "]";
        try {
            // Phase 1: Triage
            log.info("{} Phase 1: Triage", prefix);
            String triageSessionId = createAgentSession(projectId, "intake-triage", pipelineId);
            seedUserMessage(triageSessionId, buildTriagePrompt(rawContent));
            agentLoop.startAsync(triageSessionId);
            waitForCompletion(triageSessionId, TRIAGE_TIMEOUT_MS);

            String triageOutput = getLastAssistantMessage(triageSessionId);
            eventService.emit(sourceSessionId, EventType.INTAKE_CLASSIFIED,
                    Map.of("pipelineId", pipelineId, "triageSessionId", triageSessionId));
            log.info("{} Phase 1 complete — triage classified", prefix);

            // Phase 2: Thread creation
            log.info("{} Phase 2: Thread creation", prefix);
            String threadSessionId = createAgentSession(projectId, "thread-agent", pipelineId);
            seedUserMessage(threadSessionId, buildThreadAgentPrompt(rawContent, triageOutput, projectId));
            agentLoop.startAsync(threadSessionId);
            waitForCompletion(threadSessionId, THREAD_AGENT_TIMEOUT_MS);

            eventService.emit(sourceSessionId, EventType.THREAD_CREATED,
                    Map.of("pipelineId", pipelineId, "threadSessionId", threadSessionId));
            log.info("{} Phase 2 complete — threads created", prefix);

            // Phase 3: Distillation
            log.info("{} Phase 3: Distillation", prefix);
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
            log.info("{} Phase 3 complete — distilled {} threads", prefix, distilled);

            // Persist summary to source session
            String summary = String.format("Pipeline complete. Triage classified content, %d thread(s) created and distilled.", distilled);
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

    private String buildTriagePrompt(String rawContent) {
        return """
                Classify and organize the following raw content. Identify distinct topics and for each topic provide:

                ### Topic: [Topic Name]
                **Type:** [architecture_decision / open_question / action_item / discussion]
                **Decisions:** [list of decisions made]
                **Open Questions:** [list of unresolved questions]
                **Action Items:** [list with assignees if known]
                **Key Content:** [organized notes for this topic]

                Use `classify_content` to determine the overall content type first.

                ---
                RAW CONTENT:
                """ + rawContent;
    }

    private String buildThreadAgentPrompt(String rawContent, String triageOutput, String projectId) {
        return """
                You are in **Intake Pipeline Mode**. The triage agent has classified the following raw content \
                into distinct topics. Your job is to create one thread per topic using `create_thread`.

                For each thread:
                1. Use a clean, descriptive title (no session IDs or placeholders)
                2. Populate the `content` field with organized markdown for the topic
                3. Populate the `decisions` array with any decisions identified
                4. Populate the `actions` array with action items (each with `text` and `assignee` if known)
                5. Set `projectId` to: """ + projectId + """


                If topics overlap significantly, merge them into a single thread.

                ---
                TRIAGE OUTPUT:
                """ + triageOutput + """

                ---
                ORIGINAL RAW CONTENT:
                """ + rawContent;
    }
}
