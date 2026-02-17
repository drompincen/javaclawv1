package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface ReminderRepository extends MongoRepository<ReminderDocument, String> {
    List<ReminderDocument> findByProjectId(String projectId);
    List<ReminderDocument> findByTriggeredFalseAndTriggerAtBefore(Instant now);
}
