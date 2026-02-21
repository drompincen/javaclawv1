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
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ChecklistRepository checklistRepository;
    private final PhaseRepository phaseRepository;
    private final MilestoneRepository milestoneRepository;
    private final DeltaPackRepository deltaPackRepository;
    private final LlmService llmService;

    public AskController(ThreadRepository threadRepository,
                         ObjectiveRepository objectiveRepository,
                         TicketRepository ticketRepository,
                         BlindspotRepository blindspotRepository,
                         ResourceRepository resourceRepository,
                         MemoryRepository memoryRepository,
                         ResourceAssignmentRepository resourceAssignmentRepository,
                         ChecklistRepository checklistRepository,
                         PhaseRepository phaseRepository,
                         MilestoneRepository milestoneRepository,
                         DeltaPackRepository deltaPackRepository,
                         LlmService llmService) {
        this.threadRepository = threadRepository;
        this.objectiveRepository = objectiveRepository;
        this.ticketRepository = ticketRepository;
        this.blindspotRepository = blindspotRepository;
        this.resourceRepository = resourceRepository;
        this.memoryRepository = memoryRepository;
        this.resourceAssignmentRepository = resourceAssignmentRepository;
        this.checklistRepository = checklistRepository;
        this.phaseRepository = phaseRepository;
        this.milestoneRepository = milestoneRepository;
        this.deltaPackRepository = deltaPackRepository;
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
                "You are a project analyst. Answer questions using ONLY the structured data below. " +
                "For ticket assignments, use ONLY the 'assignee=' field — a ticket is unassigned " +
                "if and only if assignee=UNASSIGNED. Do NOT infer assignment from description text. " +
                "Be specific — list item names and counts.");
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

        // Pre-compute assignment data for tickets and resources
        List<ResourceAssignmentDocument> assignments = resourceAssignmentRepository.findByProjectId(projectId);
        List<ResourceDocument> resources = resourceRepository.findByProjectId(projectId);
        if (resources.isEmpty()) {
            resources = resourceRepository.findAll();
        }
        Map<String, String> resourceIdToName = resources.stream()
                .collect(Collectors.toMap(ResourceDocument::getResourceId, ResourceDocument::getName, (a, b) -> a));

        // Build ticketId → assignee name from resource_assignments
        Map<String, String> ticketAssigneeFromAssignments = new HashMap<>();
        // Build resourceId → list of ticketIds from resource_assignments
        Map<String, List<String>> resourceTicketMap = new HashMap<>();
        for (ResourceAssignmentDocument a : assignments) {
            String name = resourceIdToName.getOrDefault(a.getResourceId(), a.getResourceId());
            ticketAssigneeFromAssignments.put(a.getTicketId(), name);
            resourceTicketMap.computeIfAbsent(a.getResourceId(), k -> new ArrayList<>()).add(a.getTicketId());
        }

        // Tickets — resolve assignee from 3 sources, never omit
        List<TicketDocument> tickets = ticketRepository.findByProjectId(projectId);
        Map<String, TicketDocument> ticketById = tickets.stream()
                .collect(Collectors.toMap(TicketDocument::getTicketId, t -> t, (a, b) -> a));

        List<String> assignedTicketIds = new ArrayList<>();
        List<String> unassignedTicketIds = new ArrayList<>();

        ctx.append("\n## TICKETS (").append(tickets.size()).append(")\n");
        for (TicketDocument t : tickets) {
            // Resolve assignee: owner > assignedResourceId > resource_assignments
            String assignee = null;
            if (t.getOwner() != null && !t.getOwner().isBlank()) {
                assignee = t.getOwner();
            } else if (t.getAssignedResourceId() != null && !t.getAssignedResourceId().isBlank()) {
                assignee = resourceIdToName.getOrDefault(t.getAssignedResourceId(), t.getAssignedResourceId());
            } else {
                assignee = ticketAssigneeFromAssignments.get(t.getTicketId());
            }

            if (assignee != null) {
                assignedTicketIds.add(safe(t.getTitle()));
            } else {
                unassignedTicketIds.add(safe(t.getTitle()));
            }

            ctx.append("- **").append(safe(t.getTitle())).append("**");
            if (t.getStatus() != null) ctx.append(" [").append(t.getStatus()).append("]");
            if (t.getPriority() != null) ctx.append(" priority=").append(t.getPriority());
            if (t.getStoryPoints() != null) ctx.append(" sp=").append(t.getStoryPoints());
            ctx.append(" assignee=").append(assignee != null ? assignee : "UNASSIGNED");
            if (t.getExternalRef() != null) ctx.append(" externalRef=").append(t.getExternalRef());
            ctx.append("\n");
            if (t.getLinkedThreadIds() != null && !t.getLinkedThreadIds().isEmpty()) {
                ctx.append("  linkedThreads: ").append(String.join(", ", t.getLinkedThreadIds())).append("\n");
            }
            if (t.getBlockedBy() != null && !t.getBlockedBy().isEmpty()) {
                ctx.append("  blockedBy: ").append(String.join(", ", t.getBlockedBy())).append("\n");
            }
            if (t.getObjectiveIds() != null && !t.getObjectiveIds().isEmpty()) {
                ctx.append("  objectiveIds: ").append(String.join(", ", t.getObjectiveIds())).append("\n");
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

        // Resources — enhanced with assignment info and computed capacity
        ctx.append("\n## RESOURCES (").append(resources.size()).append(")\n");
        for (ResourceDocument r : resources) {
            double effectiveHours = r.getCapacity() * r.getAvailability();
            ctx.append("- **").append(safe(r.getName())).append("**");
            if (r.getRole() != null) ctx.append(" role=").append(r.getRole());
            ctx.append(" capacity=").append(r.getCapacity());
            ctx.append(" availability=").append(r.getAvailability());
            ctx.append(" effectiveHours=").append(String.format("%.0f", effectiveHours));
            ctx.append("\n");

            // Show assigned tickets with SP
            List<String> ticketIds = resourceTicketMap.getOrDefault(r.getResourceId(), List.of());
            int allocatedSP = 0;
            if (!ticketIds.isEmpty()) {
                List<String> ticketLabels = new ArrayList<>();
                for (String tid : ticketIds) {
                    TicketDocument td = ticketById.get(tid);
                    if (td != null) {
                        String label = safe(td.getTitle());
                        if (td.getPriority() != null) label += " (" + td.getPriority() + ")";
                        if (td.getStoryPoints() != null) {
                            label += " " + td.getStoryPoints() + "SP";
                            allocatedSP += td.getStoryPoints();
                        }
                        ticketLabels.add(label);
                    } else {
                        ticketLabels.add(tid);
                    }
                }
                ctx.append("  Assigned: ").append(String.join(", ", ticketLabels));
                ctx.append(" — ").append(ticketIds.size()).append(ticketIds.size() == 1 ? " ticket" : " tickets");
                ctx.append(", allocatedSP=").append(allocatedSP);
                ctx.append("\n");
            } else {
                // Also count SP from tickets assigned via owner field
                int ownerSP = 0;
                int ownerTickets = 0;
                for (TicketDocument t : tickets) {
                    String assignee = null;
                    if (t.getOwner() != null && !t.getOwner().isBlank()) assignee = t.getOwner();
                    if (assignee != null && assignee.equalsIgnoreCase(r.getName())) {
                        ownerTickets++;
                        if (t.getStoryPoints() != null) ownerSP += t.getStoryPoints();
                    }
                }
                if (ownerTickets > 0) {
                    ctx.append("  Assigned (via owner): ").append(ownerTickets)
                            .append(ownerTickets == 1 ? " ticket" : " tickets")
                            .append(", allocatedSP=").append(ownerSP).append("\n");
                    allocatedSP = ownerSP;
                } else {
                    ctx.append("  Assigned: (none) — 0 tickets, allocatedSP=0\n");
                }
            }
        }

        // Assignment Summary
        ctx.append("\n## ASSIGNMENT SUMMARY\n");
        ctx.append("Total tickets: ").append(tickets.size()).append("\n");
        ctx.append("Assigned: ").append(assignedTicketIds.size());
        if (!assignedTicketIds.isEmpty()) {
            ctx.append(" (").append(String.join(", ", assignedTicketIds)).append(")");
        }
        ctx.append("\n");
        ctx.append("Unassigned: ").append(unassignedTicketIds.size());
        if (!unassignedTicketIds.isEmpty()) {
            ctx.append(" (").append(String.join(", ", unassignedTicketIds)).append(")");
        }
        ctx.append("\n");

        // Resource capacity summary — compute effective capacity and allocated SP per resource
        ctx.append("\n## RESOURCE CAPACITY SUMMARY\n");
        for (ResourceDocument r : resources) {
            double effectiveHours = r.getCapacity() * r.getAvailability();
            List<String> rTicketIds = resourceTicketMap.getOrDefault(r.getResourceId(), List.of());
            int allocSP = 0;
            int ticketCount = 0;
            for (String tid : rTicketIds) {
                TicketDocument td = ticketById.get(tid);
                if (td != null) {
                    ticketCount++;
                    if (td.getStoryPoints() != null) allocSP += td.getStoryPoints();
                }
            }
            // Also count tickets via owner field if no resource_assignments
            if (rTicketIds.isEmpty()) {
                for (TicketDocument t : tickets) {
                    if (t.getOwner() != null && t.getOwner().equalsIgnoreCase(r.getName())) {
                        ticketCount++;
                        if (t.getStoryPoints() != null) allocSP += t.getStoryPoints();
                    }
                }
            }
            ctx.append("- **").append(safe(r.getName())).append("**: ");
            ctx.append("effectiveHours=").append(String.format("%.0f", effectiveHours));
            ctx.append(", tickets=").append(ticketCount);
            ctx.append(", allocatedSP=").append(allocSP);
            ctx.append(", spareCapacity=").append(allocSP > 0 ? "LOW" : "HIGH");
            ctx.append("\n");
        }

        // Checklists
        List<ChecklistDocument> checklists = checklistRepository.findByProjectId(projectId);
        ctx.append("\n## CHECKLISTS (").append(checklists.size()).append(")\n");
        for (ChecklistDocument c : checklists) {
            ctx.append("- **").append(safe(c.getName())).append("**");
            if (c.getStatus() != null) ctx.append(" [").append(c.getStatus()).append("]");
            int totalItems = c.getItems() != null ? c.getItems().size() : 0;
            long checkedItems = c.getItems() != null
                    ? c.getItems().stream().filter(ChecklistDocument.ChecklistItem::isChecked).count()
                    : 0;
            ctx.append(" items=").append(totalItems).append(" checked=").append(checkedItems);
            if (c.getPhaseId() != null) ctx.append(" phaseId=").append(c.getPhaseId());
            ctx.append("\n");
        }

        // Phases
        List<PhaseDocument> phases = phaseRepository.findByProjectIdOrderBySortOrder(projectId);
        ctx.append("\n## PHASES (").append(phases.size()).append(")\n");
        for (PhaseDocument p : phases) {
            ctx.append("- **").append(safe(p.getName())).append("**");
            if (p.getStatus() != null) ctx.append(" [").append(p.getStatus()).append("]");
            ctx.append(" sortOrder=").append(p.getSortOrder());
            if (p.getStartDate() != null) ctx.append(" start=").append(p.getStartDate());
            if (p.getEndDate() != null) ctx.append(" end=").append(p.getEndDate());
            ctx.append("\n");
            if (p.getEntryCriteria() != null && !p.getEntryCriteria().isEmpty()) {
                ctx.append("  entryCriteria: ").append(p.getEntryCriteria().size()).append(" items\n");
            }
            if (p.getExitCriteria() != null && !p.getExitCriteria().isEmpty()) {
                ctx.append("  exitCriteria: ").append(p.getExitCriteria().size()).append(" items\n");
            }
        }

        // Milestones
        List<MilestoneDocument> milestones = milestoneRepository.findByProjectIdOrderByTargetDateAsc(projectId);
        ctx.append("\n## MILESTONES (").append(milestones.size()).append(")\n");
        for (MilestoneDocument m : milestones) {
            ctx.append("- **").append(safe(m.getName())).append("**");
            if (m.getStatus() != null) ctx.append(" [").append(m.getStatus()).append("]");
            if (m.getTargetDate() != null) ctx.append(" targetDate=").append(m.getTargetDate());
            if (m.getActualDate() != null) ctx.append(" actualDate=").append(m.getActualDate());
            if (m.getOwner() != null) ctx.append(" owner=").append(m.getOwner());
            if (m.getPhaseId() != null) ctx.append(" phaseId=").append(m.getPhaseId());
            ctx.append("\n");
        }

        // Delta Packs
        List<DeltaPackDocument> deltaPacks = deltaPackRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        ctx.append("\n## DELTA PACKS (").append(deltaPacks.size()).append(")\n");
        for (DeltaPackDocument d : deltaPacks) {
            ctx.append("- **").append(safe(d.getDeltaPackId())).append("**");
            if (d.getStatus() != null) ctx.append(" [").append(d.getStatus()).append("]");
            int deltaCount = d.getDeltas() != null ? d.getDeltas().size() : 0;
            ctx.append(" deltas=").append(deltaCount);
            if (d.getCreatedAt() != null) ctx.append(" createdAt=").append(d.getCreatedAt());
            ctx.append("\n");
            if (d.getSummary() != null && !d.getSummary().isEmpty()) {
                ctx.append("  summary: ").append(truncate(d.getSummary().toString(), 200)).append("\n");
            }
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
