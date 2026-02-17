package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final MessageRepository messageRepository;
    private final EventService eventService;

    public SearchController(MessageRepository messageRepository, EventService eventService) {
        this.messageRepository = messageRepository;
        this.eventService = eventService;
    }

    /**
     * Submit search results from the human.
     * The HumanSearchTool polls for a message with id "search-resp-{requestId}".
     */
    @PostMapping("/response")
    public ResponseEntity<?> submitSearchResponse(@RequestBody Map<String, String> body) {
        String requestId = body.get("requestId");
        String sessionId = body.get("sessionId");
        String content = body.get("content");

        if (requestId == null || content == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "requestId and content are required"));
        }

        MessageDocument responseMsg = new MessageDocument();
        responseMsg.setMessageId("search-resp-" + requestId);
        responseMsg.setSessionId(sessionId);
        responseMsg.setSeq(0);
        responseMsg.setRole("search_response");
        responseMsg.setContent(content);
        responseMsg.setTimestamp(Instant.now());
        messageRepository.save(responseMsg);

        if (sessionId != null) {
            eventService.emit(sessionId, EventType.SEARCH_RESPONSE_SUBMITTED,
                    Map.of("requestId", requestId));
        }

        return ResponseEntity.ok(Map.of("submitted", true, "requestId", requestId));
    }
}
