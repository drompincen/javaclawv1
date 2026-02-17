package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ProjectRepository;
import io.github.drompincen.javaclawv1.protocol.api.CreateProjectRequest;
import io.github.drompincen.javaclawv1.protocol.api.ProjectDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @PostMapping
    public ResponseEntity<ProjectDto> create(@RequestBody CreateProjectRequest req) {
        ProjectDocument doc = new ProjectDocument();
        doc.setProjectId(UUID.randomUUID().toString());
        doc.setName(req.name());
        doc.setDescription(req.description());
        doc.setTags(req.tags());
        doc.setStatus(ProjectDto.ProjectStatus.ACTIVE);
        doc.setMetadata(Map.of());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        projectRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<ProjectDto> list() {
        return projectRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDto> get(@PathVariable String id) {
        return projectRepository.findById(id)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectDto> update(@PathVariable String id, @RequestBody ProjectDocument updates) {
        return projectRepository.findById(id).map(existing -> {
            if (updates.getName() != null) existing.setName(updates.getName());
            if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
            if (updates.getTags() != null) existing.setTags(updates.getTags());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getMetadata() != null) existing.setMetadata(updates.getMetadata());
            existing.setUpdatedAt(Instant.now());
            projectRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (projectRepository.existsById(id)) {
            projectRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ProjectDto toDto(ProjectDocument doc) {
        return new ProjectDto(doc.getProjectId(), doc.getName(), doc.getDescription(),
                doc.getStatus(), doc.getTags(), doc.getMetadata(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
