package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.LlmInteractionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface LlmInteractionRepository extends MongoRepository<LlmInteractionDocument, String> {
    List<LlmInteractionDocument> findBySessionId(String sessionId);
    List<LlmInteractionDocument> findByAgentId(String agentId);
    List<LlmInteractionDocument> findByTimestampAfterOrderByTimestampDesc(Instant after);
    List<LlmInteractionDocument> findTop100ByOrderByTimestampDesc();
    long countBySessionId(String sessionId);
}
