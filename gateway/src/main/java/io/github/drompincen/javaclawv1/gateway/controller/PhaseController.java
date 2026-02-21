package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.PhaseDto;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
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
@RequestMapping("/api/projects/{projectId}/phases")
public class PhaseController {

    private final ThingService thingService;

    public PhaseController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<PhaseDto> create(@PathVariable String projectId,
                                            @RequestBody Map<String, Object> body) {
        if (body.get("status") == null) {
            body.put("status", PhaseStatus.NOT_STARTED.name());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.PHASE, body);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<PhaseDto> list(@PathVariable String projectId,
                                @RequestParam(required = false) PhaseStatus status) {
        List<ThingDocument> docs;
        if (status != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.PHASE,
                    "status", status.name());
        } else {
            docs = thingService.findByProjectAndCategorySorted(projectId, ThingCategory.PHASE,
                    "payload.sortOrder", true);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{phaseId}")
    public ResponseEntity<PhaseDto> get(@PathVariable String projectId,
                                         @PathVariable String phaseId) {
        return thingService.findById(phaseId, ThingCategory.PHASE)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{phaseId}")
    public ResponseEntity<PhaseDto> update(@PathVariable String projectId,
                                            @PathVariable String phaseId,
                                            @RequestBody Map<String, Object> updates) {
        return thingService.findById(phaseId, ThingCategory.PHASE).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("name") != null) merged.put("name", updates.get("name"));
            if (updates.get("description") != null) merged.put("description", updates.get("description"));
            if (updates.get("entryCriteria") != null) merged.put("entryCriteria", updates.get("entryCriteria"));
            if (updates.get("exitCriteria") != null) merged.put("exitCriteria", updates.get("exitCriteria"));
            if (updates.get("checklistIds") != null) merged.put("checklistIds", updates.get("checklistIds"));
            if (updates.get("objectiveIds") != null) merged.put("objectiveIds", updates.get("objectiveIds"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            if (updates.get("startDate") != null) merged.put("startDate", updates.get("startDate"));
            if (updates.get("endDate") != null) merged.put("endDate", updates.get("endDate"));
            if (updates.get("sortOrder") != null) merged.put("sortOrder", updates.get("sortOrder"));
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{phaseId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String phaseId) {
        if (thingService.existsById(phaseId)) {
            thingService.deleteById(phaseId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @SuppressWarnings("unchecked")
    private PhaseDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        PhaseStatus status = null;
        if (p.get("status") != null) {
            try { status = PhaseStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        int sortOrder = p.get("sortOrder") != null ? ((Number) p.get("sortOrder")).intValue() : 0;
        Instant startDate = p.get("startDate") != null ? Instant.parse(p.get("startDate").toString()) : null;
        Instant endDate = p.get("endDate") != null ? Instant.parse(p.get("endDate").toString()) : null;
        return new PhaseDto(thing.getId(), thing.getProjectId(),
                (String) p.get("name"), (String) p.get("description"),
                (List<String>) p.get("entryCriteria"), (List<String>) p.get("exitCriteria"),
                (List<String>) p.get("checklistIds"), (List<String>) p.get("objectiveIds"),
                status, sortOrder, startDate, endDate,
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
