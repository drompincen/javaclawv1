package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.LogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface LogRepository extends MongoRepository<LogDocument, String> {
    List<LogDocument> findBySessionId(String sessionId);
    List<LogDocument> findByLevel(LogDocument.LogLevel level);
    List<LogDocument> findByTimestampAfterOrderByTimestampDesc(Instant after);
    List<LogDocument> findByLevelAndTimestampAfterOrderByTimestampDesc(LogDocument.LogLevel level, Instant after);
    List<LogDocument> findTop100ByOrderByTimestampDesc();
}
