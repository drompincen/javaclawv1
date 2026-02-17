package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends MongoRepository<EventDocument, String> {
    List<EventDocument> findBySessionIdOrderBySeqAsc(String sessionId);
    List<EventDocument> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId, long seq);
    Optional<EventDocument> findTopBySessionIdOrderBySeqDesc(String sessionId);
    List<EventDocument> findBySessionIdAndType(String sessionId, EventType type);
}
