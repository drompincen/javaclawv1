package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.BlindspotDocument;
import io.github.drompincen.javaclawv1.persistence.repository.BlindspotRepository;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotCategory;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotDto;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/blindspots")
public class BlindspotController {

    private final BlindspotRepository blindspotRepository;

    public BlindspotController(BlindspotRepository blindspotRepository) {
        this.blindspotRepository = blindspotRepository;
    }

    @PostMapping
    public ResponseEntity<BlindspotDto> create(@PathVariable String projectId,
                                                @RequestBody BlindspotDocument body) {
        body.setBlindspotId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) body.setStatus(BlindspotStatus.OPEN);
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        blindspotRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<BlindspotDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) BlindspotStatus status,
                                    @RequestParam(required = false) BlindspotCategory category) {
        List<BlindspotDocument> docs;
        if (status != null) {
            docs = blindspotRepository.findByProjectIdAndStatus(projectId, status);
        } else if (category != null) {
            docs = blindspotRepository.findByProjectIdAndCategory(projectId, category);
        } else {
            docs = blindspotRepository.findByProjectId(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{blindspotId}")
    public ResponseEntity<BlindspotDto> get(@PathVariable String projectId,
                                             @PathVariable String blindspotId) {
        return blindspotRepository.findById(blindspotId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{blindspotId}")
    public ResponseEntity<BlindspotDto> update(@PathVariable String projectId,
                                                @PathVariable String blindspotId,
                                                @RequestBody BlindspotDocument updates) {
        return blindspotRepository.findById(blindspotId).map(existing -> {
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getOwner() != null) existing.setOwner(updates.getOwner());
            if (updates.getStatus() == BlindspotStatus.RESOLVED && existing.getResolvedAt() == null) {
                existing.setResolvedAt(Instant.now());
            }
            existing.setUpdatedAt(Instant.now());
            blindspotRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    private BlindspotDto toDto(BlindspotDocument doc) {
        return new BlindspotDto(
                doc.getBlindspotId(), doc.getProjectId(), doc.getProjectName(),
                doc.getTitle(), doc.getDescription(),
                doc.getCategory(), doc.getSeverity(), doc.getStatus(),
                doc.getOwner(), doc.getSourceRefs(), doc.getDeltaPackId(),
                doc.getReconcileRunId(),
                doc.getCreatedAt(), doc.getUpdatedAt(), doc.getResolvedAt());
    }
}
