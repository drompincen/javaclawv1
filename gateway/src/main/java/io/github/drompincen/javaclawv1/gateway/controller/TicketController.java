package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.CreateTicketRequest;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/tickets")
public class TicketController {

    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @PostMapping
    public ResponseEntity<TicketDto> create(@PathVariable String projectId, @RequestBody CreateTicketRequest req) {
        TicketDocument doc = new TicketDocument();
        doc.setTicketId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setTitle(req.title());
        doc.setDescription(req.description());
        doc.setStatus(TicketDto.TicketStatus.TODO);
        doc.setPriority(req.priority() != null ? req.priority() : TicketDto.TicketPriority.MEDIUM);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        ticketRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<TicketDto> list(@PathVariable String projectId,
                                @RequestParam(required = false) TicketDto.TicketStatus status) {
        List<TicketDocument> docs = (status != null)
                ? ticketRepository.findByProjectIdAndStatus(projectId, status)
                : ticketRepository.findByProjectId(projectId);
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDto> get(@PathVariable String projectId, @PathVariable String ticketId) {
        return ticketRepository.findById(ticketId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{ticketId}")
    public ResponseEntity<TicketDto> update(@PathVariable String projectId, @PathVariable String ticketId,
                                            @RequestBody TicketDocument updates) {
        return ticketRepository.findById(ticketId).map(existing -> {
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getPriority() != null) existing.setPriority(updates.getPriority());
            if (updates.getAssignedResourceId() != null) existing.setAssignedResourceId(updates.getAssignedResourceId());
            if (updates.getBlockedBy() != null) existing.setBlockedBy(updates.getBlockedBy());
            existing.setUpdatedAt(Instant.now());
            ticketRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String ticketId) {
        if (ticketRepository.existsById(ticketId)) {
            ticketRepository.deleteById(ticketId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private TicketDto toDto(TicketDocument doc) {
        return new TicketDto(doc.getTicketId(), doc.getProjectId(), doc.getTitle(), doc.getDescription(),
                doc.getStatus(), doc.getPriority(), doc.getAssignedResourceId(),
                doc.getLinkedThreadIds(), doc.getBlockedBy(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
