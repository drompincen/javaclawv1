package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import io.github.drompincen.javaclawv1.protocol.api.*;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final ProjectRepository projectRepository;
    private final ThreadRepository threadRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final EventService eventService;
    private final AgentLoop agentLoop;

    public ExtractionService(ProjectRepository projectRepository,
                             ThreadRepository threadRepository,
                             SessionRepository sessionRepository,
                             MessageRepository messageRepository,
                             EventService eventService,
                             AgentLoop agentLoop) {
        this.projectRepository = projectRepository;
        this.threadRepository = threadRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.agentLoop = agentLoop;
    }

    public ExtractionResponse startExtraction(ExtractionRequest request) {
        String projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }

        // Validate project exists
        projectRepository.findById(projectId).orElseThrow(() ->
                new IllegalArgumentException("Project not found: " + projectId));

        // Resolve thread IDs
        List<String> threadIds = resolveThreadIds(projectId, request.threadIds());
        if (threadIds.isEmpty()) {
            return new ExtractionResponse(
                    UUID.randomUUID().toString(), null, "COMPLETED",
                    new ExtractionSummary(0, 0, 0, 0));
        }

        // Determine extraction types
        Set<ExtractionType> types = request.types();
        if (types == null || types.isEmpty() || types.contains(ExtractionType.ALL)) {
            types = EnumSet.of(ExtractionType.REMINDERS, ExtractionType.CHECKLISTS, ExtractionType.TICKETS);
        }

        // Create extraction session
        String extractionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        SessionDocument session = new SessionDocument();
        session.setSessionId(sessionId);
        session.setProjectId(projectId);
        session.setStatus(SessionStatus.IDLE);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setMetadata(Map.of(
                "extractionId", extractionId,
                "type", "extraction",
                "agentId", "thread-extractor"
        ));
        sessionRepository.save(session);

        // Compose the extraction instruction message
        String instruction = buildExtractionInstruction(projectId, threadIds, types, request.dryRun());

        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setSeq(1);
        msg.setRole("user");
        msg.setContent(instruction);
        msg.setTimestamp(Instant.now());
        messageRepository.save(msg);

        // Emit extraction started event
        eventService.emit(sessionId, EventType.EXTRACTION_STARTED,
                Map.of("extractionId", extractionId, "projectId", projectId,
                        "threadCount", threadIds.size()));

        // Update thread extraction tracking
        Instant now = Instant.now();
        for (String threadId : threadIds) {
            threadRepository.findById(threadId).ifPresent(thread -> {
                thread.setLastExtractedAt(now);
                thread.setExtractionCount(thread.getExtractionCount() + 1);
                thread.setUpdatedAt(now);
                threadRepository.save(thread);
            });
        }

        // Kick off the agent loop
        if (!request.dryRun()) {
            agentLoop.startAsync(sessionId);
        }

        String status = request.dryRun() ? "DRY_RUN" : "QUEUED";
        return new ExtractionResponse(extractionId, sessionId, status, null);
    }

    public Optional<ExtractionResponse> getExtraction(String extractionId) {
        // Find session by extraction metadata
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(s -> s.getMetadata() != null && extractionId.equals(s.getMetadata().get("extractionId")))
                .findFirst()
                .map(s -> {
                    String status = switch (s.getStatus()) {
                        case IDLE -> "QUEUED";
                        case RUNNING -> "RUNNING";
                        case COMPLETED -> "COMPLETED";
                        case FAILED -> "FAILED";
                        default -> s.getStatus().name();
                    };
                    return new ExtractionResponse(extractionId, s.getSessionId(), status, null);
                });
    }

    public List<ExtractionResponse> listExtractions(String projectId) {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(s -> projectId.equals(s.getProjectId())
                        && s.getMetadata() != null
                        && "extraction".equals(s.getMetadata().get("type")))
                .map(s -> {
                    String extractionId = s.getMetadata().get("extractionId");
                    String status = switch (s.getStatus()) {
                        case IDLE -> "QUEUED";
                        case RUNNING -> "RUNNING";
                        case COMPLETED -> "COMPLETED";
                        case FAILED -> "FAILED";
                        default -> s.getStatus().name();
                    };
                    return new ExtractionResponse(extractionId, s.getSessionId(), status, null);
                })
                .collect(Collectors.toList());
    }

    private List<String> resolveThreadIds(String projectId, List<String> requestedIds) {
        if (requestedIds != null && !requestedIds.isEmpty()) {
            return requestedIds;
        }
        // Find all threads for the project
        return threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId).stream()
                .map(ThreadDocument::getThreadId)
                .collect(Collectors.toList());
    }

    private String buildExtractionInstruction(String projectId, List<String> threadIds,
                                               Set<ExtractionType> types, boolean dryRun) {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract actionable artifacts from the following project threads.\n\n");
        sb.append("**Project ID:** ").append(projectId).append("\n");
        sb.append("**Threads to process:** ").append(String.join(", ", threadIds)).append("\n");
        sb.append("**Extract types:** ").append(types.stream().map(Enum::name).collect(Collectors.joining(", "))).append("\n");

        if (dryRun) {
            sb.append("\n**DRY RUN:** Do NOT create any artifacts. Just analyze and report what you would extract.\n");
        }

        sb.append("\n## Instructions\n");
        sb.append("For each thread:\n");
        sb.append("1. Use `read_thread_messages` to read the conversation\n");
        sb.append("2. Identify reminders, TODOs/checklists, tickets, and ideas\n");
        sb.append("3. Use the appropriate tool to create each artifact, linking back to the source thread\n");
        sb.append("4. Provide a summary of what was extracted\n");

        return sb.toString();
    }
}
