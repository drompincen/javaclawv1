package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.LockDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface LockRepository extends MongoRepository<LockDocument, String> {
    Optional<LockDocument> findBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
}
