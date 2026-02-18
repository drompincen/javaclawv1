package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentGraphBuilder;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import io.github.drompincen.javaclawv1.runtime.agent.graph.MongoCheckpointSaver;
import io.github.drompincen.javaclawv1.runtime.lock.SessionLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final SessionRepository sessionRepository;
    private final ThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final EventService eventService;
    private final SessionLockService lockService;
    private final AgentGraphBuilder graphBuilder;
    private final MongoCheckpointSaver checkpointSaver;
    private final ContextCommandService contextCommandService;
    private DistillerService distillerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-loop");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Future<?>> runningLoops = new ConcurrentHashMap<>();

    public AgentLoop(SessionRepository sessionRepository,
                     ThreadRepository threadRepository,
                     MessageRepository messageRepository,
                     EventService eventService,
                     SessionLockService lockService,
                     AgentGraphBuilder graphBuilder,
                     MongoCheckpointSaver checkpointSaver,
                     ContextCommandService contextCommandService) {
        this.sessionRepository = sessionRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.lockService = lockService;
        this.graphBuilder = graphBuilder;
        this.checkpointSaver = checkpointSaver;
        this.contextCommandService = contextCommandService;
    }

    @Autowired
    void setDistillerService(DistillerService ds) { this.distillerService = ds; }

    public void startAsync(String sessionId) {
        if (runningLoops.containsKey(sessionId)) {
            log.warn("Agent loop already running for session {}", sessionId);
            return;
        }
        Future<?> future = executor.submit(() -> run(sessionId));
        runningLoops.put(sessionId, future);
    }

    public void stop(String sessionId) {
        Future<?> future = runningLoops.remove(sessionId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void run(String sessionId) {
        Optional<String> lockOwner = lockService.tryAcquire(sessionId);
        if (lockOwner.isEmpty()) {
            log.error("Cannot acquire lock for session {}", sessionId);
            return;
        }
        String owner = lockOwner.get();

        try {
            // Dual-lookup: try sessions first, then threads
            boolean isThread = false;
            SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
            ThreadDocument thread = null;
            if (session == null) {
                thread = threadRepository.findById(sessionId).orElse(null);
                if (thread == null) {
                    throw new IllegalStateException("No session or thread found for id: " + sessionId);
                }
                isThread = true;
            }

            // Update status to RUNNING
            updateStatus(sessionId, SessionStatus.RUNNING, isThread);

            // Build initial state from messages and optional checkpoint
            AgentState state = checkpointSaver.load(sessionId).orElseGet(() -> {
                AgentState s = new AgentState();
                s.setThreadId(sessionId);
                s.setStepNo(1);
                return s;
            });

            // Load messages into state (with multimodal support)
            List<MessageDocument> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            for (MessageDocument msg : messages) {
                if (msg.getParts() != null && !msg.getParts().isEmpty()) {
                    try {
                        String partsJson = objectMapper.writeValueAsString(msg.getParts());
                        state = state.withMultimodalMessage(msg.getRole(), msg.getContent(), partsJson);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize parts for message {}, falling back to text", msg.getMessageId());
                        state = state.withMessage(msg.getRole(), msg.getContent());
                    }
                } else {
                    state = state.withMessage(msg.getRole(), msg.getContent());
                }
            }

            // Check for context commands (use project, use thread, whereami) before running graph
            String lastUserMsg = getLastUserMessage(messages);
            if (lastUserMsg != null) {
                String cmdResponse = contextCommandService.handleContextCommand(
                        lastUserMsg.trim(), sessionId, isThread);
                if (cmdResponse != null) {
                    log.info("Context command handled for session {}: {}", sessionId,
                            lastUserMsg.trim().length() > 50 ? lastUserMsg.trim().substring(0, 50) : lastUserMsg.trim());
                    persistCommandResponse(sessionId, messages.size(), cmdResponse);
                    eventService.emit(sessionId, EventType.AGENT_RESPONSE,
                            Map.of("agentId", "system", "response", cmdResponse));
                    updateStatus(sessionId, SessionStatus.COMPLETED, isThread);
                    return;
                }
            }

            // Run the graph
            AgentState finalState = graphBuilder.runGraph(state);

            // Persist new messages (assistant/tool) back to MongoDB
            persistNewMessages(sessionId, messages.size(), finalState);

            updateStatus(sessionId, SessionStatus.COMPLETED, isThread);
            // Distiller auto-trigger disabled — superseded by ExtractionService / thread-extractor agent
        } catch (Exception e) {
            log.error("Agent loop error for session {}", sessionId, e);
            boolean failThread = sessionRepository.findById(sessionId).isEmpty();
            updateStatus(sessionId, SessionStatus.FAILED, failThread);
            eventService.emit(sessionId, EventType.ERROR,
                    Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        } finally {
            lockService.release(sessionId, owner);
            runningLoops.remove(sessionId);
        }
    }

    /**
     * Persist messages generated during graph execution (assistant, tool, system)
     * back to the messages collection so they're visible via the API and UI.
     */
    private void persistNewMessages(String sessionId, int existingCount,
                                     io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState finalState) {
        List<Map<String, String>> allMsgs = finalState.getMessages();
        // Skip messages we already loaded (the first existingCount were from DB)
        long nextSeq = existingCount + 1;
        for (int i = existingCount; i < allMsgs.size(); i++) {
            Map<String, String> msg = allMsgs.get(i);
            String role = msg.getOrDefault("role", "system");
            String content = msg.getOrDefault("content", "");

            // Never persist system prompts — they are internal orchestration
            if ("system".equals(role)) continue;
            // Never persist tool results — they are shown via events
            if ("tool".equals(role)) continue;

            MessageDocument doc = new MessageDocument();
            doc.setMessageId(java.util.UUID.randomUUID().toString());
            doc.setSessionId(sessionId);
            doc.setSeq(nextSeq++);
            doc.setRole(role);
            doc.setContent(content);
            doc.setTimestamp(Instant.now());

            // Tag assistant messages with the producing agent
            if ("assistant".equals(role)) {
                doc.setAgentId(finalState.getCurrentAgentId());
            }

            messageRepository.save(doc);
        }
        log.info("Persisted {} new messages for session {}", nextSeq - existingCount - 1, sessionId);
    }

    private String getLastUserMessage(List<MessageDocument> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return null;
    }

    private void persistCommandResponse(String sessionId, int existingCount, String response) {
        MessageDocument doc = new MessageDocument();
        doc.setMessageId(java.util.UUID.randomUUID().toString());
        doc.setSessionId(sessionId);
        doc.setSeq(existingCount + 1);
        doc.setRole("assistant");
        doc.setContent(response);
        doc.setTimestamp(Instant.now());
        messageRepository.save(doc);
    }

    private void updateStatus(String sessionId, SessionStatus status, boolean isThread) {
        if (isThread) {
            threadRepository.findById(sessionId).ifPresent(t -> {
                t.setStatus(status);
                t.setUpdatedAt(Instant.now());
                threadRepository.save(t);
            });
        } else {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.setStatus(status);
                s.setUpdatedAt(Instant.now());
                sessionRepository.save(s);
            });
        }
        eventService.emit(sessionId, EventType.SESSION_STATUS_CHANGED,
                Map.of("status", status));
    }
}
