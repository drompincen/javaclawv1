package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.*;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ask")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final ThreadRepository threadRepository;
    private final ObjectiveRepository objectiveRepository;
    private final TicketRepository ticketRepository;
    private final BlindspotRepository blindspotRepository;
    private final ResourceRepository resourceRepository;
    private final MemoryRepository memoryRepository;
    private final LlmService llmService;

    public AskController(ThreadRepository threadRepository,
                         ObjectiveRepository objectiveRepository,
                         TicketRepository ticketRepository,
                         BlindspotRepository blindspotRepository,
                         ResourceRepository resourceRepository,
                         MemoryRepository memoryRepository,
                         LlmService llmService) {
        this.threadRepository = threadRepository;
        this.objectiveRepository = objectiveRepository;
        this.ticketRepository = ticketRepository;
        this.blindspotRepository = blindspotRepository;
        this.resourceRepository = resourceRepository;
        this.memoryRepository = memoryRepository;
        this.llmService = llmService;
    }

    @PostMapping
    public ResponseEntity<?> ask(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        String question = body.get("question");

        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectId is required"));
        }
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        // Gather project context from all collections
        String context = buildProjectContext(projectId);

        // Build the enriched prompt
        String enrichedPrompt = """
                You are answering questions about a project. Use ONLY the project data below to answer.
                Be specific, cite data points, and mention risks or issues you see.

                """ + context + """

                QUESTION: """ + question;

        // Build AgentState for the generalist
        AgentState state = new AgentState();
        state.setCurrentAgentId("generalist");
        state.setProjectId(projectId);
        state.setThreadId("ask-" + UUID.randomUUID());
        state = state.withMessage("system",
                "You are a project analyst. Answer questions using the provided project data. " +
                "Be concise and data-driven. Reference specific items by name.");
        state = state.withMessage("user", enrichedPrompt);

        // Call LLM
        String answer;
        try {
            answer = llmService.blockingResponse(state);
        } catch (Exception e) {
            log.error("[AskClaw] LLM call failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get answer: " + e.getMessage()));
        }

        // Build sources list from the context data
        List<Map<String, String>> sources = buildSources(projectId);

        return ResponseEntity.ok(Map.of(
                "answer", answer != null ? answer : "",
                "sources", sources
        ));
    }

    private String buildProjectContext(String projectId) {
        StringBuilder ctx = new StringBuilder();

        // Threads
        List<ThreadDocument> threads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        ctx.append("## THREADS (").append(threads.size()).append(")\n");
        for (ThreadDocument t : threads) {
            ctx.append("- **").append(safe(t.getTitle())).append("**");
            if (t.getStatus() != null) ctx.append(" [").append(t.getStatus()).append("]");
            ctx.append("\n");
            if (t.getContent() != null) {
                ctx.append("  ").append(truncate(t.getContent(), 300)).append("\n");
            }
            if (t.getDecisions() != null && !t.getDecisions().isEmpty()) {
                ctx.append("  Decisions: ");
                ctx.append(t.getDecisions().stream()
                        .map(d -> safe(d.getText()))
                        .collect(Collectors.joining(", ")));
                ctx.append("\n");
            }
            if (t.getActions() != null && !t.getActions().isEmpty()) {
                ctx.append("  Actions: ");
                ctx.append(t.getActions().stream()
                        .map(a -> safe(a.getText()) + (a.getAssignee() != null ? " → " + a.getAssignee() : ""))
                        .collect(Collectors.joining(", ")));
                ctx.append("\n");
            }
        }

        // Objectives
        List<ObjectiveDocument> objectives = objectiveRepository.findByProjectId(projectId);
        ctx.append("\n## OBJECTIVES (").append(objectives.size()).append(")\n");
        for (ObjectiveDocument o : objectives) {
            ctx.append("- **").append(safe(o.getSprintName())).append("**: ").append(safe(o.getOutcome()));
            if (o.getStatus() != null) ctx.append(" [").append(o.getStatus()).append("]");
            if (o.getCoveragePercent() != null) ctx.append(" coverage=").append(o.getCoveragePercent()).append("%");
            ctx.append("\n");
            if (o.getRisks() != null && !o.getRisks().isEmpty()) {
                ctx.append("  Risks: ").append(String.join(", ", o.getRisks())).append("\n");
            }
            if (o.getStartDate() != null) ctx.append("  Start: ").append(o.getStartDate()).append("\n");
            if (o.getEndDate() != null) ctx.append("  End: ").append(o.getEndDate()).append("\n");
        }

        // Tickets
        List<TicketDocument> tickets = ticketRepository.findByProjectId(projectId);
        ctx.append("\n## TICKETS (").append(tickets.size()).append(")\n");
        for (TicketDocument t : tickets) {
            ctx.append("- **").append(safe(t.getTitle())).append("**");
            if (t.getStatus() != null) ctx.append(" [").append(t.getStatus()).append("]");
            if (t.getPriority() != null) ctx.append(" priority=").append(t.getPriority());
            if (t.getOwner() != null) ctx.append(" assignee=").append(t.getOwner());
            ctx.append("\n");
            if (t.getDescription() != null) {
                ctx.append("  ").append(truncate(t.getDescription(), 200)).append("\n");
            }
        }

        // Blindspots
        List<BlindspotDocument> blindspots = blindspotRepository.findByProjectId(projectId);
        ctx.append("\n## BLINDSPOTS (").append(blindspots.size()).append(")\n");
        for (BlindspotDocument b : blindspots) {
            ctx.append("- **").append(safe(b.getTitle())).append("**");
            if (b.getSeverity() != null) ctx.append(" [").append(b.getSeverity()).append("]");
            if (b.getCategory() != null) ctx.append(" ").append(b.getCategory());
            ctx.append("\n");
            if (b.getDescription() != null) {
                ctx.append("  ").append(truncate(b.getDescription(), 200)).append("\n");
            }
        }

        // Resources
        List<ResourceDocument> resources = resourceRepository.findByProjectId(projectId);
        if (resources.isEmpty()) {
            // Fallback: include all resources (resources may not be project-scoped)
            resources = resourceRepository.findAll();
        }
        ctx.append("\n## RESOURCES (").append(resources.size()).append(")\n");
        for (ResourceDocument r : resources) {
            ctx.append("- **").append(safe(r.getName())).append("**");
            if (r.getRole() != null) ctx.append(" role=").append(r.getRole());
            ctx.append(" capacity=").append(r.getCapacity());
            ctx.append(" availability=").append(r.getAvailability());
            ctx.append("\n");
        }

        // Memories
        List<MemoryDocument> memories = memoryRepository.findRelevantMemories(projectId);
        ctx.append("\n## MEMORIES (").append(memories.size()).append(")\n");
        for (MemoryDocument m : memories) {
            ctx.append("- **").append(safe(m.getKey())).append("**: ").append(truncate(safe(m.getContent()), 200));
            ctx.append("\n");
        }

        return ctx.toString();
    }

    private List<Map<String, String>> buildSources(String projectId) {
        List<Map<String, String>> sources = new ArrayList<>();

        threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId).forEach(t ->
                sources.add(Map.of("type", "thread", "id", safe(t.getThreadId()), "title", safe(t.getTitle()))));
        objectiveRepository.findByProjectId(projectId).forEach(o ->
                sources.add(Map.of("type", "objective", "id", safe(o.getObjectiveId()), "title", safe(o.getOutcome()))));
        blindspotRepository.findByProjectId(projectId).forEach(b ->
                sources.add(Map.of("type", "blindspot", "id", safe(b.getBlindspotId()), "title", safe(b.getTitle()))));

        return sources;
    }

    private static String safe(Object val) {
        return val != null ? val.toString() : "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
