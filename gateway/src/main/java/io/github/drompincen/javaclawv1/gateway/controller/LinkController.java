package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.LinkDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LinkRepository;
import io.github.drompincen.javaclawv1.protocol.api.LinkDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/links")
public class LinkController {

    private final LinkRepository linkRepository;

    public LinkController(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    @PostMapping
    public ResponseEntity<LinkDto> create(@PathVariable String projectId,
                                           @RequestBody LinkDocument body) {
        body.setLinkId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        linkRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<LinkDto> list(@PathVariable String projectId,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) String bundleId) {
        List<LinkDocument> docs;
        if (category != null) {
            docs = linkRepository.findByProjectIdAndCategory(projectId, category);
        } else if (bundleId != null) {
            docs = linkRepository.findByBundleId(bundleId);
        } else {
            docs = linkRepository.findByProjectId(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{linkId}")
    public ResponseEntity<LinkDto> get(@PathVariable String projectId,
                                        @PathVariable String linkId) {
        return linkRepository.findById(linkId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{linkId}")
    public ResponseEntity<LinkDto> update(@PathVariable String projectId,
                                           @PathVariable String linkId,
                                           @RequestBody LinkDocument updates) {
        return linkRepository.findById(linkId).map(existing -> {
            if (updates.getUrl() != null) existing.setUrl(updates.getUrl());
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
            if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
            if (updates.getBundleId() != null) existing.setBundleId(updates.getBundleId());
            if (updates.getThreadIds() != null) existing.setThreadIds(updates.getThreadIds());
            if (updates.getObjectiveIds() != null) existing.setObjectiveIds(updates.getObjectiveIds());
            if (updates.getPhaseIds() != null) existing.setPhaseIds(updates.getPhaseIds());
            if (updates.getTags() != null) existing.setTags(updates.getTags());
            existing.setPinned(updates.isPinned());
            existing.setUpdatedAt(Instant.now());
            linkRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String linkId) {
        if (linkRepository.existsById(linkId)) {
            linkRepository.deleteById(linkId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private LinkDto toDto(LinkDocument doc) {
        return new LinkDto(doc.getLinkId(), doc.getProjectId(), doc.getUrl(),
                doc.getTitle(), doc.getCategory(), doc.getDescription(),
                doc.isPinned(), doc.getBundleId(), doc.getThreadIds(),
                doc.getObjectiveIds(), doc.getPhaseIds(), doc.getTags(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
