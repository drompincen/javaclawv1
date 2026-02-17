package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.PlanDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PlanRepository;
import io.github.drompincen.javaclawv1.protocol.api.PlanDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/plans")
public class PlanController {

    private final PlanRepository planRepository;

    public PlanController(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @PostMapping
    public ResponseEntity<PlanDto> create(@PathVariable String projectId, @RequestBody PlanDocument doc) {
        if (doc.getPlanId() == null) doc.setPlanId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        planRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<PlanDto> list(@PathVariable String projectId) {
        return planRepository.findByProjectId(projectId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<PlanDto> get(@PathVariable String projectId, @PathVariable String planId) {
        return planRepository.findById(planId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{planId}")
    public ResponseEntity<PlanDto> update(@PathVariable String projectId, @PathVariable String planId,
                                          @RequestBody PlanDocument updates) {
        return planRepository.findById(planId).map(existing -> {
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getMilestones() != null) existing.setMilestones(updates.getMilestones());
            if (updates.getTicketIds() != null) existing.setTicketIds(updates.getTicketIds());
            existing.setUpdatedAt(Instant.now());
            planRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String planId) {
        if (planRepository.existsById(planId)) {
            planRepository.deleteById(planId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private PlanDto toDto(PlanDocument doc) {
        return new PlanDto(doc.getPlanId(), doc.getProjectId(), doc.getTitle(),
                doc.getMilestones(), doc.getTicketIds(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
