package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface FutureExecutionRepository extends MongoRepository<FutureExecutionDocument, String> {
    List<FutureExecutionDocument> findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
            ExecStatus status, Instant now);
    List<FutureExecutionDocument> findByDateKeyAndAgentIdAndProjectId(
            String dateKey, String agentId, String projectId);
    List<FutureExecutionDocument> findByDateKeyAndExecStatusNotIn(
            String dateKey, List<ExecStatus> exclude);
    void deleteByDateKeyAndExecStatusIn(String dateKey, List<ExecStatus> statuses);
    boolean existsByIdempotencyKey(String key);
    List<FutureExecutionDocument> findByAgentIdAndExecStatus(String agentId, ExecStatus status);
    List<FutureExecutionDocument> findByLeaseUntilLessThanAndExecStatusIn(
            Instant threshold, List<ExecStatus> statuses);
    List<FutureExecutionDocument> findByScheduleIdAndExecStatusNotIn(
            String scheduleId, List<ExecStatus> exclude);
    List<FutureExecutionDocument> findByExecStatus(ExecStatus status);
    List<FutureExecutionDocument> findByAgentId(String agentId);
    List<FutureExecutionDocument> findByDateKey(String dateKey);
}
