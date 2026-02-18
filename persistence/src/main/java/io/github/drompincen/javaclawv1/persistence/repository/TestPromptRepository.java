package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.TestPromptDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TestPromptRepository extends MongoRepository<TestPromptDocument, String> {

    long countBySessionId(String sessionId);

    List<TestPromptDocument> findAllByOrderByCreateTimestampAsc();
}
