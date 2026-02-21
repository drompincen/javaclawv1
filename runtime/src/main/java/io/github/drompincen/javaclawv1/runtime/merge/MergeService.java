package io.github.drompincen.javaclawv1.runtime.merge;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private final ThingService thingService;

    public MergeService(ThingService thingService) {
        this.thingService = thingService;
    }

    public ThingDocument mergeIdeas(String projectId, List<String> ideaIds) {
        List<ThingDocument> ideas = ideaIds.stream()
                .map(id -> thingService.findById(id, ThingCategory.IDEA).orElseThrow())
                .toList();

        String combinedTitle = ideas.stream()
                .map(t -> t.payloadString("title"))
                .collect(Collectors.joining(" + "));
        String combinedContent = ideas.stream()
                .map(t -> t.payloadString("content"))
                .collect(Collectors.joining("\n\n---\n\n"));

        // Create ticket via ThingService
        Map<String, Object> ticketPayload = new LinkedHashMap<>();
        ticketPayload.put("title", combinedTitle);
        ticketPayload.put("description", combinedContent);
        ticketPayload.put("status", TicketDto.TicketStatus.TODO.name());
        ticketPayload.put("priority", TicketDto.TicketPriority.MEDIUM.name());
        ThingDocument ticket = thingService.createThing(projectId, ThingCategory.TICKET, ticketPayload);

        for (ThingDocument idea : ideas) {
            thingService.mergePayload(idea, Map.of(
                    "status", IdeaDto.IdeaStatus.PROMOTED.name(),
                    "promotedToTicketId", ticket.getId()
            ));
        }

        log.info("Merged {} ideas into ticket {}", ideaIds.size(), ticket.getId());
        return ticket;
    }
}
