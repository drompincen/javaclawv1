package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.DeltaPackDocument;
import io.github.drompincen.javaclawv1.persistence.repository.DeltaPackRepository;
import io.github.drompincen.javaclawv1.protocol.api.DeltaPackDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/delta-packs")
public class DeltaPackController {

    private final DeltaPackRepository deltaPackRepository;

    public DeltaPackController(DeltaPackRepository deltaPackRepository) {
        this.deltaPackRepository = deltaPackRepository;
    }

    @GetMapping
    public List<DeltaPackDto> list(@PathVariable String projectId) {
        return deltaPackRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{deltaPackId}")
    public ResponseEntity<DeltaPackDto> get(@PathVariable String projectId,
                                             @PathVariable String deltaPackId) {
        return deltaPackRepository.findById(deltaPackId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    private DeltaPackDto toDto(DeltaPackDocument doc) {
        List<DeltaPackDto.DeltaEntry> deltas = doc.getDeltas() != null
                ? doc.getDeltas().stream()
                    .map(d -> new DeltaPackDto.DeltaEntry(
                            d.getDeltaType(), d.getSeverity(), d.getTitle(), d.getDescription(),
                            d.getSourceA(), d.getSourceB(), d.getFieldName(),
                            d.getValueA(), d.getValueB(), d.getSuggestedAction(), d.isAutoResolvable()))
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return new DeltaPackDto(
                doc.getDeltaPackId(), doc.getProjectId(), doc.getProjectName(),
                doc.getReconcileSessionId(), doc.getSourcesCompared(),
                deltas, doc.getSummary(),
                doc.getStatus(), doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
