package io.github.drompincen.javaclawv1.runtime.merge;

import io.github.drompincen.javaclawv1.persistence.document.IdeaDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.IdeaRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private final IdeaRepository ideaRepository;
    private final TicketRepository ticketRepository;

    public MergeService(IdeaRepository ideaRepository,
                        TicketRepository ticketRepository) {
        this.ideaRepository = ideaRepository;
        this.ticketRepository = ticketRepository;
    }

    public TicketDocument mergeIdeas(String projectId, List<String> ideaIds) {
        List<IdeaDocument> ideas = ideaIds.stream()
                .map(id -> ideaRepository.findById(id).orElseThrow())
                .toList();

        String combinedTitle = ideas.stream()
                .map(IdeaDocument::getTitle)
                .collect(Collectors.joining(" + "));
        String combinedContent = ideas.stream()
                .map(IdeaDocument::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        TicketDocument ticket = new TicketDocument();
        ticket.setTicketId(UUID.randomUUID().toString());
        ticket.setProjectId(projectId);
        ticket.setTitle(combinedTitle);
        ticket.setDescription(combinedContent);
        ticket.setStatus(TicketDto.TicketStatus.TODO);
        ticket.setPriority(TicketDto.TicketPriority.MEDIUM);
        ticket.setCreatedAt(Instant.now());
        ticket.setUpdatedAt(Instant.now());
        ticketRepository.save(ticket);

        for (IdeaDocument idea : ideas) {
            idea.setStatus(IdeaDto.IdeaStatus.PROMOTED);
            idea.setPromotedToTicketId(ticket.getTicketId());
            idea.setUpdatedAt(Instant.now());
            ideaRepository.save(idea);
        }

        log.info("Merged {} ideas into ticket {}", ideaIds.size(), ticket.getTicketId());
        return ticket;
    }
}
