package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.CheckpointDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CheckpointRepository extends MongoRepository<CheckpointDocument, String> {
    Optional<CheckpointDocument> findTopBySessionIdOrderByStepNoDesc(String sessionId);
}
