package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MessageRepository extends MongoRepository<MessageDocument, String> {
    List<MessageDocument> findBySessionIdOrderBySeqAsc(String sessionId);
    long countBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
}
