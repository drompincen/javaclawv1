package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.DeltaPackDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/delta-packs")
public class DeltaPackController {

    private final ThingService thingService;

    public DeltaPackController(ThingService thingService) {
        this.thingService = thingService;
    }

    @GetMapping
    public List<DeltaPackDto> list(@PathVariable String projectId) {
        return thingService.findByProjectAndCategorySorted(projectId, ThingCategory.DELTA_PACK,
                        "createDate", false)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{deltaPackId}")
    public ResponseEntity<DeltaPackDto> get(@PathVariable String projectId,
                                             @PathVariable String deltaPackId) {
        return thingService.findById(deltaPackId, ThingCategory.DELTA_PACK)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unchecked")
    private DeltaPackDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        List<Map<String, Object>> deltaMaps = (List<Map<String, Object>>) p.get("deltas");
        List<DeltaPackDto.DeltaEntry> deltas = deltaMaps != null
                ? deltaMaps.stream()
                    .map(d -> new DeltaPackDto.DeltaEntry(
                            (String) d.get("deltaType"), (String) d.get("severity"),
                            (String) d.get("title"), (String) d.get("description"),
                            (String) d.get("sourceA"), (String) d.get("sourceB"),
                            (String) d.get("fieldName"), (String) d.get("valueA"),
                            (String) d.get("valueB"), (String) d.get("suggestedAction"),
                            Boolean.TRUE.equals(d.get("autoResolvable"))))
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return new DeltaPackDto(
                thing.getId(), thing.getProjectId(),
                (String) p.get("projectName"), (String) p.get("reconcileSessionId"),
                (List<Map<String, Object>>) p.get("sourcesCompared"),
                deltas, (Map<String, Object>) p.get("summary"),
                (String) p.get("status"),
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
