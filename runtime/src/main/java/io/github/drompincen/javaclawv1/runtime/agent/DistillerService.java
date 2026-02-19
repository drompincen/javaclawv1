package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.*;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DistillerService {
    private final SessionRepository sessionRepository;
    private final ThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final MemoryRepository memoryRepository;
    private final EventService eventService;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "distiller"); t.setDaemon(true); return t;
    });

    public DistillerService(SessionRepository sessionRepository, ThreadRepository threadRepository,
                            MessageRepository messageRepository, MemoryRepository memoryRepository,
                            EventService eventService) {
        this.sessionRepository = sessionRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.eventService = eventService;
    }

    public void distillAsync(String sessionId) {
        exec.submit(() -> {
            try { distill(sessionId); }
            catch (Exception e) { System.err.println("[distiller] Error distilling " + sessionId + ": " + e.getMessage()); }
        });
    }

    private void distill(String sessionId) {
        SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
        boolean isThread = (session == null);
        String threadId = null;

        if (session != null) {
            threadId = session.getThreadId();
        } else {
            ThreadDocument thread = threadRepository.findById(sessionId).orElse(null);
            if (thread == null) return;
            threadId = sessionId;
        }

        var messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        if (messages.size() < 2) return;

        String firstUserMsg = messages.stream()
                .filter(m -> "user".equals(m.getRole())).map(MessageDocument::getContent).findFirst().orElse("");
        String lastAssistantMsg = messages.stream()
                .filter(m -> "assistant".equals(m.getRole())).reduce((a, b) -> b).map(MessageDocument::getContent).orElse("");

        String summary = "**Topic:** " + truncate(firstUserMsg, 200) + "\n\n" +
                "**Outcome:** " + truncate(lastAssistantMsg, 300) + "\n\n" +
                "**Messages:** " + messages.size();

        MemoryDocument mem = new MemoryDocument();
        mem.setMemoryId(UUID.randomUUID().toString());
        mem.setKey("session-summary-" + sessionId.substring(0, Math.min(8, sessionId.length())));
        mem.setContent(summary);
        mem.setTags(List.of("auto-distilled", "session-summary"));
        mem.setCreatedBy("distiller");
        mem.setCreatedAt(Instant.now());
        mem.setUpdatedAt(Instant.now());

        if (threadId != null) {
            mem.setScope(MemoryDocument.MemoryScope.THREAD);
            mem.setThreadId(threadId);
            ThreadDocument thread = threadRepository.findById(threadId).orElse(null);
            if (thread != null) {
                var pids = thread.getEffectiveProjectIds();
                if (!pids.isEmpty()) mem.setProjectId(pids.get(0));
            }
        } else {
            mem.setScope(MemoryDocument.MemoryScope.SESSION);
            mem.setSessionId(sessionId);
        }

        memoryRepository.save(mem);
        System.out.println("[distiller] Saved memory " + mem.getKey() + " (scope=" + mem.getScope() + ")");
        eventService.emit(sessionId, EventType.MEMORY_DISTILLED,
                java.util.Map.of("memoryId", mem.getMemoryId(), "key", mem.getKey(), "scope", mem.getScope().name()));
    }

    public void distillThread(String threadId) {
        exec.submit(() -> {
            try { doDistillThread(threadId); }
            catch (Exception e) { System.err.println("[distiller] Error distilling thread " + threadId + ": " + e.getMessage()); }
        });
    }

    private void doDistillThread(String threadId) {
        ThreadDocument thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null) return;

        StringBuilder content = new StringBuilder();
        content.append("# ").append(thread.getTitle() != null ? thread.getTitle() : "Untitled Thread").append("\n\n");

        if (thread.getSummary() != null && !thread.getSummary().isBlank()) {
            content.append("**Summary:** ").append(thread.getSummary()).append("\n\n");
        }

        if (thread.getDecisions() != null && !thread.getDecisions().isEmpty()) {
            content.append("## Decisions\n");
            for (ThreadDocument.Decision d : thread.getDecisions()) {
                content.append("- ").append(d.getText());
                if (d.getDecidedBy() != null) content.append(" (decided by ").append(d.getDecidedBy()).append(")");
                content.append("\n");
            }
            content.append("\n");
        }

        if (thread.getActions() != null && !thread.getActions().isEmpty()) {
            content.append("## Action Items\n");
            for (ThreadDocument.ActionItem a : thread.getActions()) {
                content.append("- ").append(a.getText());
                if (a.getAssignee() != null) content.append(" → ").append(a.getAssignee());
                content.append("\n");
            }
            content.append("\n");
        }

        // Include message content if available
        var messages = messageRepository.findBySessionIdOrderBySeqAsc(threadId);
        if (!messages.isEmpty()) {
            content.append("## Thread Content\n");
            for (MessageDocument msg : messages) {
                if (msg.getContent() != null && !msg.getContent().isBlank()) {
                    content.append(truncate(msg.getContent(), 500)).append("\n\n");
                }
            }
        }

        List<String> pids = thread.getEffectiveProjectIds();

        MemoryDocument mem = new MemoryDocument();
        mem.setMemoryId(UUID.randomUUID().toString());
        mem.setKey("thread-distill-" + threadId.substring(0, Math.min(8, threadId.length())));
        mem.setContent(content.toString());
        mem.setScope(MemoryDocument.MemoryScope.THREAD);
        mem.setThreadId(threadId);
        if (!pids.isEmpty()) mem.setProjectId(pids.get(0));
        mem.setTags(List.of("auto-distilled", "thread-decisions", "intake-pipeline"));
        mem.setCreatedBy("distiller");
        mem.setCreatedAt(Instant.now());
        mem.setUpdatedAt(Instant.now());

        memoryRepository.save(mem);
        System.out.println("[distiller] Saved thread memory " + mem.getKey() + " for thread " + threadId);
        eventService.emit(threadId, EventType.MEMORY_DISTILLED,
                java.util.Map.of("memoryId", mem.getMemoryId(), "key", mem.getKey(), "scope", mem.getScope().name()));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
