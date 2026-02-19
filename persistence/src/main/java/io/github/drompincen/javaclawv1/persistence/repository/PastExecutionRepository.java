package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.PastExecutionDocument;
import io.github.drompincen.javaclawv1.protocol.api.ResultStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PastExecutionRepository extends MongoRepository<PastExecutionDocument, String> {
    Page<PastExecutionDocument> findByAgentIdOrderByStartedAtDesc(String agentId, Pageable pageable);
    Page<PastExecutionDocument> findByProjectIdOrderByStartedAtDesc(String projectId, Pageable pageable);
    Optional<PastExecutionDocument> findByExecutionId(String executionId);
    List<PastExecutionDocument> findByScheduleIdAndStartedAtBetween(
            String scheduleId, Instant from, Instant to);
    long countByAgentIdAndResultStatus(String agentId, ResultStatus status);
    Page<PastExecutionDocument> findAllByOrderByStartedAtDesc(Pageable pageable);
}
