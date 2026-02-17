package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SessionRepository extends MongoRepository<SessionDocument, String> {
    List<SessionDocument> findByStatus(SessionStatus status);
    List<SessionDocument> findAllByOrderByUpdatedAtDesc();
    List<SessionDocument> findByThreadId(String threadId);
    List<SessionDocument> findByThreadIdIsNullOrderByUpdatedAtDesc();
}
