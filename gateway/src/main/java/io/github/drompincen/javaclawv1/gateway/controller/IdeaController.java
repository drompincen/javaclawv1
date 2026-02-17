package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.IdeaDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.IdeaRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.CreateIdeaRequest;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/ideas")
public class IdeaController {

    private final IdeaRepository ideaRepository;
    private final TicketRepository ticketRepository;

    public IdeaController(IdeaRepository ideaRepository, TicketRepository ticketRepository) {
        this.ideaRepository = ideaRepository;
        this.ticketRepository = ticketRepository;
    }

    @PostMapping
    public ResponseEntity<IdeaDto> create(@PathVariable String projectId, @RequestBody CreateIdeaRequest req) {
        IdeaDocument doc = new IdeaDocument();
        doc.setIdeaId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setTitle(req.title());
        doc.setContent(req.content());
        doc.setTags(req.tags());
        doc.setStatus(IdeaDto.IdeaStatus.NEW);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        ideaRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<IdeaDto> list(@PathVariable String projectId) {
        return ideaRepository.findByProjectId(projectId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{ideaId}")
    public ResponseEntity<IdeaDto> get(@PathVariable String projectId, @PathVariable String ideaId) {
        return ideaRepository.findById(ideaId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{ideaId}")
    public ResponseEntity<IdeaDto> update(@PathVariable String projectId, @PathVariable String ideaId,
                                          @RequestBody IdeaDocument updates) {
        return ideaRepository.findById(ideaId).map(existing -> {
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getContent() != null) existing.setContent(updates.getContent());
            if (updates.getTags() != null) existing.setTags(updates.getTags());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            existing.setUpdatedAt(Instant.now());
            ideaRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{ideaId}/promote")
    public ResponseEntity<?> promote(@PathVariable String projectId, @PathVariable String ideaId) {
        return ideaRepository.findById(ideaId).map(idea -> {
            TicketDocument ticket = new TicketDocument();
            ticket.setTicketId(UUID.randomUUID().toString());
            ticket.setProjectId(projectId);
            ticket.setTitle(idea.getTitle());
            ticket.setDescription(idea.getContent());
            ticket.setStatus(TicketDto.TicketStatus.TODO);
            ticket.setPriority(TicketDto.TicketPriority.MEDIUM);
            ticket.setCreatedAt(Instant.now());
            ticket.setUpdatedAt(Instant.now());
            ticketRepository.save(ticket);

            idea.setStatus(IdeaDto.IdeaStatus.PROMOTED);
            idea.setPromotedToTicketId(ticket.getTicketId());
            idea.setUpdatedAt(Instant.now());
            ideaRepository.save(idea);

            return ResponseEntity.ok(java.util.Map.of("ticketId", ticket.getTicketId(), "ideaId", ideaId));
        }).orElse(ResponseEntity.notFound().build());
    }

    private IdeaDto toDto(IdeaDocument doc) {
        return new IdeaDto(doc.getIdeaId(), doc.getProjectId(), doc.getTitle(), doc.getContent(),
                doc.getTags(), doc.getStatus(), doc.getPromotedToTicketId(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
