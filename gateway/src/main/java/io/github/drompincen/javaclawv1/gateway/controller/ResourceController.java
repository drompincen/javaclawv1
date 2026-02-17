package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.protocol.api.ResourceDto;
import io.github.drompincen.javaclawv1.protocol.api.ResourceAssignmentDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceRepository resourceRepository;
    private final ResourceAssignmentRepository assignmentRepository;

    public ResourceController(ResourceRepository resourceRepository,
                              ResourceAssignmentRepository assignmentRepository) {
        this.resourceRepository = resourceRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @PostMapping
    public ResponseEntity<ResourceDto> create(@RequestBody ResourceDocument doc) {
        if (doc.getResourceId() == null) doc.setResourceId(UUID.randomUUID().toString());
        resourceRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<ResourceDto> list() {
        return resourceRepository.findAll().stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceDto> get(@PathVariable String id) {
        return resourceRepository.findById(id)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceDto> update(@PathVariable String id, @RequestBody ResourceDocument updates) {
        return resourceRepository.findById(id).map(existing -> {
            if (updates.getName() != null) existing.setName(updates.getName());
            if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
            if (updates.getRole() != null) existing.setRole(updates.getRole());
            if (updates.getSkills() != null) existing.setSkills(updates.getSkills());
            if (updates.getAvailability() > 0) existing.setAvailability(updates.getAvailability());
            resourceRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (resourceRepository.existsById(id)) {
            resourceRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/assignments")
    public List<ResourceAssignmentDto> assignments(@PathVariable String id) {
        return assignmentRepository.findByResourceId(id).stream()
                .map(this::toAssignmentDto).collect(Collectors.toList());
    }

    private ResourceDto toDto(ResourceDocument doc) {
        return new ResourceDto(doc.getResourceId(), doc.getName(), doc.getEmail(),
                doc.getRole(), doc.getSkills(), doc.getAvailability());
    }

    private ResourceAssignmentDto toAssignmentDto(ResourceAssignmentDocument doc) {
        return new ResourceAssignmentDto(doc.getAssignmentId(), doc.getResourceId(),
                doc.getTicketId(), doc.getPercentageAllocation());
    }
}
