package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ScorecardDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ScorecardRepository;
import io.github.drompincen.javaclawv1.protocol.api.ScorecardDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/scorecard")
public class ScorecardController {

    private final ScorecardRepository scorecardRepository;

    public ScorecardController(ScorecardRepository scorecardRepository) {
        this.scorecardRepository = scorecardRepository;
    }

    @GetMapping
    public ResponseEntity<ScorecardDto> get(@PathVariable String projectId) {
        return scorecardRepository.findByProjectId(projectId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping
    public ResponseEntity<ScorecardDto> upsert(@PathVariable String projectId, @RequestBody ScorecardDocument updates) {
        ScorecardDocument doc = scorecardRepository.findByProjectId(projectId).orElseGet(() -> {
            ScorecardDocument d = new ScorecardDocument();
            d.setScorecardId(UUID.randomUUID().toString());
            d.setProjectId(projectId);
            return d;
        });
        if (updates.getMetrics() != null) doc.setMetrics(updates.getMetrics());
        if (updates.getHealth() != null) doc.setHealth(updates.getHealth());
        doc.setUpdatedAt(Instant.now());
        scorecardRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    private ScorecardDto toDto(ScorecardDocument doc) {
        return new ScorecardDto(doc.getScorecardId(), doc.getProjectId(),
                doc.getMetrics(), doc.getHealth(), doc.getUpdatedAt());
    }
}
