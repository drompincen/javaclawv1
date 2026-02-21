package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveDto;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
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
@RequestMapping("/api/projects/{projectId}/objectives")
public class ObjectiveController {

    private final ThingService thingService;

    public ObjectiveController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<ObjectiveDto> create(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new LinkedHashMap<>(body);
        if (!payload.containsKey("status") || payload.get("status") == null) {
            payload.put("status", ObjectiveStatus.PROPOSED.name());
        } else {
            payload.put("status", payload.get("status").toString());
        }
        if (payload.containsKey("startDate") && payload.get("startDate") != null) {
            payload.put("startDate", payload.get("startDate").toString());
        }
        if (payload.containsKey("endDate") && payload.get("endDate") != null) {
            payload.put("endDate", payload.get("endDate").toString());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.OBJECTIVE, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<ObjectiveDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) ObjectiveStatus status,
                                    @RequestParam(required = false) String sprintName) {
        List<ThingDocument> docs;
        if (status != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.OBJECTIVE,
                    "status", status.name());
        } else if (sprintName != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.OBJECTIVE,
                    "sprintName", sprintName);
        } else {
            docs = thingService.findByProjectAndCategory(projectId, ThingCategory.OBJECTIVE);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{objectiveId}")
    public ResponseEntity<ObjectiveDto> get(@PathVariable String projectId,
                                             @PathVariable String objectiveId) {
        return thingService.findById(objectiveId, ThingCategory.OBJECTIVE)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{objectiveId}")
    public ResponseEntity<ObjectiveDto> update(@PathVariable String projectId,
                                                @PathVariable String objectiveId,
                                                @RequestBody Map<String, Object> updates) {
        return thingService.findById(objectiveId, ThingCategory.OBJECTIVE).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("sprintName") != null) merged.put("sprintName", updates.get("sprintName"));
            if (updates.get("outcome") != null) merged.put("outcome", updates.get("outcome"));
            if (updates.get("measurableSignal") != null) merged.put("measurableSignal", updates.get("measurableSignal"));
            if (updates.get("risks") != null) merged.put("risks", updates.get("risks"));
            if (updates.get("threadIds") != null) merged.put("threadIds", updates.get("threadIds"));
            if (updates.get("ticketIds") != null) merged.put("ticketIds", updates.get("ticketIds"));
            if (updates.get("coveragePercent") != null) merged.put("coveragePercent", updates.get("coveragePercent"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            if (updates.get("startDate") != null) merged.put("startDate", updates.get("startDate").toString());
            if (updates.get("endDate") != null) merged.put("endDate", updates.get("endDate").toString());
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{objectiveId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String objectiveId) {
        if (thingService.existsById(objectiveId)) {
            thingService.deleteById(objectiveId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ObjectiveDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        ObjectiveStatus status = null;
        if (p.get("status") != null) {
            try { status = ObjectiveStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        @SuppressWarnings("unchecked")
        List<String> risks = (List<String>) p.get("risks");
        @SuppressWarnings("unchecked")
        List<String> threadIds = (List<String>) p.get("threadIds");
        @SuppressWarnings("unchecked")
        List<String> ticketIds = (List<String>) p.get("ticketIds");
        Double coveragePercent = p.get("coveragePercent") != null
                ? ((Number) p.get("coveragePercent")).doubleValue() : null;
        Instant startDate = p.get("startDate") != null
                ? Instant.parse(p.get("startDate").toString()) : null;
        Instant endDate = p.get("endDate") != null
                ? Instant.parse(p.get("endDate").toString()) : null;
        return new ObjectiveDto(
                thing.getId(), thing.getProjectId(),
                (String) p.get("sprintName"), (String) p.get("outcome"),
                (String) p.get("measurableSignal"),
                risks, threadIds, ticketIds, coveragePercent, status,
                startDate, endDate,
                thing.getCreateDate(), thing.getUpdateDate()
        );
    }
}
