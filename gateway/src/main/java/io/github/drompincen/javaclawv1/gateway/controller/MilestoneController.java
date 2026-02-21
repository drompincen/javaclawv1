package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneDto;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/milestones")
public class MilestoneController {

    private final ThingService thingService;

    public MilestoneController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<MilestoneDto> create(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        if (body.get("status") == null) {
            body.put("status", MilestoneStatus.UPCOMING.name());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.MILESTONE, body);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<MilestoneDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) MilestoneStatus status) {
        List<ThingDocument> docs;
        if (status != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.MILESTONE,
                    "status", status.name());
        } else {
            docs = thingService.findByProjectAndCategorySorted(projectId, ThingCategory.MILESTONE,
                    "payload.targetDate", true);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{milestoneId}")
    public ResponseEntity<MilestoneDto> get(@PathVariable String projectId,
                                             @PathVariable String milestoneId) {
        return thingService.findById(milestoneId, ThingCategory.MILESTONE)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{milestoneId}")
    public ResponseEntity<MilestoneDto> update(@PathVariable String projectId,
                                                @PathVariable String milestoneId,
                                                @RequestBody Map<String, Object> updates) {
        return thingService.findById(milestoneId, ThingCategory.MILESTONE)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(existing -> {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    if (updates.get("name") != null) merged.put("name", updates.get("name"));
                    if (updates.get("description") != null) merged.put("description", updates.get("description"));
                    if (updates.get("targetDate") != null) merged.put("targetDate", updates.get("targetDate"));
                    if (updates.get("actualDate") != null) merged.put("actualDate", updates.get("actualDate"));
                    if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
                    if (updates.get("phaseId") != null) merged.put("phaseId", updates.get("phaseId"));
                    if (updates.get("objectiveIds") != null) merged.put("objectiveIds", updates.get("objectiveIds"));
                    if (updates.get("ticketIds") != null) merged.put("ticketIds", updates.get("ticketIds"));
                    if (updates.get("owner") != null) merged.put("owner", updates.get("owner"));
                    if (updates.get("dependencies") != null) merged.put("dependencies", updates.get("dependencies"));
                    ThingDocument updated = thingService.mergePayload(existing, merged);
                    return ResponseEntity.ok(toDto(updated));
                }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{milestoneId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String milestoneId) {
        return thingService.findById(milestoneId, ThingCategory.MILESTONE)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(d -> {
                    thingService.deleteById(milestoneId);
                    return ResponseEntity.noContent().<Void>build();
                }).orElse(ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unchecked")
    private MilestoneDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        MilestoneStatus status = null;
        if (p.get("status") != null) {
            try { status = MilestoneStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        Instant targetDate = p.get("targetDate") != null ? Instant.parse(p.get("targetDate").toString()) : null;
        Instant actualDate = p.get("actualDate") != null ? Instant.parse(p.get("actualDate").toString()) : null;
        return new MilestoneDto(thing.getId(), thing.getProjectId(),
                (String) p.get("name"), (String) p.get("description"),
                targetDate, actualDate, status,
                (String) p.get("phaseId"), (List<String>) p.get("objectiveIds"),
                (List<String>) p.get("ticketIds"), (String) p.get("owner"),
                (List<String>) p.get("dependencies"),
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
