package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public EventDocument emit(String sessionId, EventType type, Object payload) {
        long seq = seqCounters
                .computeIfAbsent(sessionId, k -> {
                    long last = eventRepository.findTopBySessionIdOrderBySeqDesc(sessionId)
                            .map(EventDocument::getSeq).orElse(0L);
                    return new AtomicLong(last);
                }).incrementAndGet();

        EventDocument event = new EventDocument();
        event.setEventId(UUID.randomUUID().toString());
        event.setSessionId(sessionId);
        event.setSeq(seq);
        event.setType(type);
        event.setPayload(payload);
        event.setTimestamp(Instant.now());
        return eventRepository.save(event);
    }

    public EventDocument emit(String sessionId, EventType type) {
        return emit(sessionId, type, Map.of());
    }
}
