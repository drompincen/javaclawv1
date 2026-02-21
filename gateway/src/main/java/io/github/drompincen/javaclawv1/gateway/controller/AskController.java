package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.*;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
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
    private final MemoryRepository memoryRepository;
    private final ThingService thingService;
    private final LlmService llmService;

    public AskController(ThreadRepository threadRepository,
                         MemoryRepository memoryRepository,
                         ThingService thingService,
                         LlmService llmService) {
        this.threadRepository = threadRepository;
        this.memoryRepository = memoryRepository;
        this.thingService = thingService;
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

    @SuppressWarnings("unchecked")
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

        // Load all things for the project in one call
        Map<ThingCategory, List<ThingDocument>> thingsByCategory = thingService.findByProjectGrouped(projectId);

        // Objectives
        List<ThingDocument> objectives = thingsByCategory.getOrDefault(ThingCategory.OBJECTIVE, List.of());
        ctx.append("\n## OBJECTIVES (").append(objectives.size()).append(")\n");
        for (ThingDocument o : objectives) {
            Map<String, Object> op = o.getPayload();
            ctx.append("- **").append(safe(op.get("sprintName"))).append("**: ").append(safe(op.get("outcome")));
            if (op.get("status") != null) ctx.append(" [").append(op.get("status")).append("]");
            if (op.get("coveragePercent") != null) ctx.append(" coverage=").append(op.get("coveragePercent")).append("%");
            ctx.append("\n");
            List<String> risks = (List<String>) op.get("risks");
            if (risks != null && !risks.isEmpty()) {
                ctx.append("  Risks: ").append(String.join(", ", risks)).append("\n");
            }
            if (op.get("startDate") != null) ctx.append("  Start: ").append(op.get("startDate")).append("\n");
            if (op.get("endDate") != null) ctx.append("  End: ").append(op.get("endDate")).append("\n");
        }

        // Pre-compute assignment data for tickets and resources
        List<ThingDocument> assignmentThings = thingsByCategory.getOrDefault(ThingCategory.RESOURCE_ASSIGNMENT, List.of());
        List<ThingDocument> resourceThings = thingsByCategory.getOrDefault(ThingCategory.RESOURCE, List.of());
        if (resourceThings.isEmpty()) {
            resourceThings = thingService.findByCategory(ThingCategory.RESOURCE);
        }
        Map<String, String> resourceIdToName = resourceThings.stream()
                .collect(Collectors.toMap(ThingDocument::getId,
                        r -> safe(r.getPayload().get("name")), (a, b) -> a));

        Map<String, String> ticketAssigneeFromAssignments = new HashMap<>();
        Map<String, List<String>> resourceTicketMap = new HashMap<>();
        for (ThingDocument a : assignmentThings) {
            Map<String, Object> ap = a.getPayload();
            String resId = (String) ap.get("resourceId");
            String ticketId = (String) ap.get("ticketId");
            if (resId != null && ticketId != null) {
                String name = resourceIdToName.getOrDefault(resId, resId);
                ticketAssigneeFromAssignments.put(ticketId, name);
                resourceTicketMap.computeIfAbsent(resId, k -> new ArrayList<>()).add(ticketId);
            }
        }

        // Tickets
        List<ThingDocument> tickets = thingsByCategory.getOrDefault(ThingCategory.TICKET, List.of());
        Map<String, ThingDocument> ticketById = tickets.stream()
                .collect(Collectors.toMap(ThingDocument::getId, t -> t, (a, b) -> a));

        List<String> assignedTicketIds = new ArrayList<>();
        List<String> unassignedTicketIds = new ArrayList<>();

        ctx.append("\n## TICKETS (").append(tickets.size()).append(")\n");
        for (ThingDocument t : tickets) {
            Map<String, Object> tp = t.getPayload();
            String assignee = null;
            String owner = (String) tp.get("owner");
            if (owner != null && !owner.isBlank()) {
                assignee = owner;
            } else {
                String assignedResId = (String) tp.get("assignedResourceId");
                if (assignedResId != null && !assignedResId.isBlank()) {
                    assignee = resourceIdToName.getOrDefault(assignedResId, assignedResId);
                } else {
                    assignee = ticketAssigneeFromAssignments.get(t.getId());
                }
            }

            if (assignee != null) {
                assignedTicketIds.add(safe(tp.get("title")));
            } else {
                unassignedTicketIds.add(safe(tp.get("title")));
            }

            ctx.append("- **").append(safe(tp.get("title"))).append("**");
            if (tp.get("status") != null) ctx.append(" [").append(tp.get("status")).append("]");
            if (tp.get("priority") != null) ctx.append(" priority=").append(tp.get("priority"));
            if (tp.get("storyPoints") != null) ctx.append(" sp=").append(tp.get("storyPoints"));
            ctx.append(" assignee=").append(assignee != null ? assignee : "UNASSIGNED");
            if (tp.get("externalRef") != null) ctx.append(" externalRef=").append(tp.get("externalRef"));
            ctx.append("\n");
            List<String> linkedThreadIds = (List<String>) tp.get("linkedThreadIds");
            if (linkedThreadIds != null && !linkedThreadIds.isEmpty()) {
                ctx.append("  linkedThreads: ").append(String.join(", ", linkedThreadIds)).append("\n");
            }
            List<String> blockedBy = (List<String>) tp.get("blockedBy");
            if (blockedBy != null && !blockedBy.isEmpty()) {
                ctx.append("  blockedBy: ").append(String.join(", ", blockedBy)).append("\n");
            }
            List<String> objectiveIds = (List<String>) tp.get("objectiveIds");
            if (objectiveIds != null && !objectiveIds.isEmpty()) {
                ctx.append("  objectiveIds: ").append(String.join(", ", objectiveIds)).append("\n");
            }
        }

        // Blindspots
        List<ThingDocument> blindspots = thingsByCategory.getOrDefault(ThingCategory.BLINDSPOT, List.of());
        ctx.append("\n## BLINDSPOTS (").append(blindspots.size()).append(")\n");
        for (ThingDocument b : blindspots) {
            Map<String, Object> bp = b.getPayload();
            ctx.append("- **").append(safe(bp.get("title"))).append("**");
            if (bp.get("severity") != null) ctx.append(" [").append(bp.get("severity")).append("]");
            if (bp.get("category") != null) ctx.append(" ").append(bp.get("category"));
            ctx.append("\n");
            if (bp.get("description") != null) {
                ctx.append("  ").append(truncate(bp.get("description").toString(), 200)).append("\n");
            }
        }

        // Resources
        ctx.append("\n## RESOURCES (").append(resourceThings.size()).append(")\n");
        for (ThingDocument r : resourceThings) {
            Map<String, Object> rp = r.getPayload();
            int capacity = rp.get("capacity") != null ? ((Number) rp.get("capacity")).intValue() : 0;
            double availability = rp.get("availability") != null ? ((Number) rp.get("availability")).doubleValue() : 1.0;
            double effectiveHours = capacity * availability;
            ctx.append("- **").append(safe(rp.get("name"))).append("**");
            if (rp.get("role") != null) ctx.append(" role=").append(rp.get("role"));
            ctx.append(" capacity=").append(capacity);
            ctx.append(" availability=").append(availability);
            ctx.append(" effectiveHours=").append(String.format("%.0f", effectiveHours));
            ctx.append("\n");

            List<String> ticketIdsForResource = resourceTicketMap.getOrDefault(r.getId(), List.of());
            int allocatedSP = 0;
            if (!ticketIdsForResource.isEmpty()) {
                List<String> ticketLabels = new ArrayList<>();
                for (String tid : ticketIdsForResource) {
                    ThingDocument td = ticketById.get(tid);
                    if (td != null) {
                        Map<String, Object> tdp = td.getPayload();
                        String label = safe(tdp.get("title"));
                        if (tdp.get("priority") != null) label += " (" + tdp.get("priority") + ")";
                        if (tdp.get("storyPoints") != null) {
                            int sp = ((Number) tdp.get("storyPoints")).intValue();
                            label += " " + sp + "SP";
                            allocatedSP += sp;
                        }
                        ticketLabels.add(label);
                    } else {
                        ticketLabels.add(tid);
                    }
                }
                ctx.append("  Assigned: ").append(String.join(", ", ticketLabels));
                ctx.append(" — ").append(ticketIdsForResource.size()).append(ticketIdsForResource.size() == 1 ? " ticket" : " tickets");
                ctx.append(", allocatedSP=").append(allocatedSP);
                ctx.append("\n");
            } else {
                int ownerSP = 0;
                int ownerTickets = 0;
                String resName = safe(rp.get("name"));
                for (ThingDocument t : tickets) {
                    Map<String, Object> tp = t.getPayload();
                    String ticketOwner = (String) tp.get("owner");
                    if (ticketOwner != null && ticketOwner.equalsIgnoreCase(resName)) {
                        ownerTickets++;
                        if (tp.get("storyPoints") != null) ownerSP += ((Number) tp.get("storyPoints")).intValue();
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

        // Resource capacity summary
        ctx.append("\n## RESOURCE CAPACITY SUMMARY\n");
        for (ThingDocument r : resourceThings) {
            Map<String, Object> rp = r.getPayload();
            int capacity = rp.get("capacity") != null ? ((Number) rp.get("capacity")).intValue() : 0;
            double availability = rp.get("availability") != null ? ((Number) rp.get("availability")).doubleValue() : 1.0;
            double effectiveHours = capacity * availability;
            List<String> rTicketIds = resourceTicketMap.getOrDefault(r.getId(), List.of());
            int allocSP = 0;
            int ticketCount = 0;
            for (String tid : rTicketIds) {
                ThingDocument td = ticketById.get(tid);
                if (td != null) {
                    ticketCount++;
                    if (td.getPayload().get("storyPoints") != null) allocSP += ((Number) td.getPayload().get("storyPoints")).intValue();
                }
            }
            if (rTicketIds.isEmpty()) {
                String resName = safe(rp.get("name"));
                for (ThingDocument t : tickets) {
                    Map<String, Object> tp = t.getPayload();
                    if (tp.get("owner") != null && tp.get("owner").toString().equalsIgnoreCase(resName)) {
                        ticketCount++;
                        if (tp.get("storyPoints") != null) allocSP += ((Number) tp.get("storyPoints")).intValue();
                    }
                }
            }
            ctx.append("- **").append(safe(rp.get("name"))).append("**: ");
            ctx.append("effectiveHours=").append(String.format("%.0f", effectiveHours));
            ctx.append(", tickets=").append(ticketCount);
            ctx.append(", allocatedSP=").append(allocSP);
            ctx.append(", spareCapacity=").append(allocSP > 0 ? "LOW" : "HIGH");
            ctx.append("\n");
        }

        // Checklists
        List<ThingDocument> checklists = thingsByCategory.getOrDefault(ThingCategory.CHECKLIST, List.of());
        ctx.append("\n## CHECKLISTS (").append(checklists.size()).append(")\n");
        for (ThingDocument c : checklists) {
            Map<String, Object> cp = c.getPayload();
            ctx.append("- **").append(safe(cp.get("name"))).append("**");
            if (cp.get("status") != null) ctx.append(" [").append(cp.get("status")).append("]");
            List<Map<String, Object>> items = (List<Map<String, Object>>) cp.get("items");
            int totalItems = items != null ? items.size() : 0;
            long checkedItems = items != null
                    ? items.stream().filter(i -> Boolean.TRUE.equals(i.get("checked"))).count()
                    : 0;
            ctx.append(" items=").append(totalItems).append(" checked=").append(checkedItems);
            if (cp.get("phaseId") != null) ctx.append(" phaseId=").append(cp.get("phaseId"));
            ctx.append("\n");
        }

        // Phases
        List<ThingDocument> phases = thingsByCategory.getOrDefault(ThingCategory.PHASE, List.of());
        ctx.append("\n## PHASES (").append(phases.size()).append(")\n");
        for (ThingDocument p : phases) {
            Map<String, Object> pp = p.getPayload();
            ctx.append("- **").append(safe(pp.get("name"))).append("**");
            if (pp.get("status") != null) ctx.append(" [").append(pp.get("status")).append("]");
            if (pp.get("sortOrder") != null) ctx.append(" sortOrder=").append(pp.get("sortOrder"));
            if (pp.get("startDate") != null) ctx.append(" start=").append(pp.get("startDate"));
            if (pp.get("endDate") != null) ctx.append(" end=").append(pp.get("endDate"));
            ctx.append("\n");
            List<String> entryCriteria = (List<String>) pp.get("entryCriteria");
            if (entryCriteria != null && !entryCriteria.isEmpty()) {
                ctx.append("  entryCriteria: ").append(entryCriteria.size()).append(" items\n");
            }
            List<String> exitCriteria = (List<String>) pp.get("exitCriteria");
            if (exitCriteria != null && !exitCriteria.isEmpty()) {
                ctx.append("  exitCriteria: ").append(exitCriteria.size()).append(" items\n");
            }
        }

        // Milestones
        List<ThingDocument> milestones = thingsByCategory.getOrDefault(ThingCategory.MILESTONE, List.of());
        ctx.append("\n## MILESTONES (").append(milestones.size()).append(")\n");
        for (ThingDocument m : milestones) {
            Map<String, Object> mp = m.getPayload();
            ctx.append("- **").append(safe(mp.get("name"))).append("**");
            if (mp.get("status") != null) ctx.append(" [").append(mp.get("status")).append("]");
            if (mp.get("targetDate") != null) ctx.append(" targetDate=").append(mp.get("targetDate"));
            if (mp.get("actualDate") != null) ctx.append(" actualDate=").append(mp.get("actualDate"));
            if (mp.get("owner") != null) ctx.append(" owner=").append(mp.get("owner"));
            if (mp.get("phaseId") != null) ctx.append(" phaseId=").append(mp.get("phaseId"));
            ctx.append("\n");
        }

        // Delta Packs
        List<ThingDocument> deltaPacks = thingsByCategory.getOrDefault(ThingCategory.DELTA_PACK, List.of());
        ctx.append("\n## DELTA PACKS (").append(deltaPacks.size()).append(")\n");
        for (ThingDocument d : deltaPacks) {
            Map<String, Object> dp = d.getPayload();
            ctx.append("- **").append(safe(d.getId())).append("**");
            if (dp.get("status") != null) ctx.append(" [").append(dp.get("status")).append("]");
            List<Map<String, Object>> deltas = (List<Map<String, Object>>) dp.get("deltas");
            int deltaCount = deltas != null ? deltas.size() : 0;
            ctx.append(" deltas=").append(deltaCount);
            if (d.getCreateDate() != null) ctx.append(" createdAt=").append(d.getCreateDate());
            ctx.append("\n");
            if (dp.get("summary") != null) {
                ctx.append("  summary: ").append(truncate(dp.get("summary").toString(), 200)).append("\n");
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
        thingService.findByProjectAndCategory(projectId, ThingCategory.OBJECTIVE).forEach(o ->
                sources.add(Map.of("type", "objective", "id", safe(o.getId()), "title", safe(o.getPayload().get("outcome")))));
        thingService.findByProjectAndCategory(projectId, ThingCategory.BLINDSPOT).forEach(b ->
                sources.add(Map.of("type", "blindspot", "id", safe(b.getId()), "title", safe(b.getPayload().get("title")))));

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
