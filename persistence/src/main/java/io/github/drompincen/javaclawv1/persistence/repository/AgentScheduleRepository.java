package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.AgentScheduleDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentScheduleRepository extends MongoRepository<AgentScheduleDocument, String> {
    List<AgentScheduleDocument> findByEnabled(boolean enabled);
    List<AgentScheduleDocument> findByAgentId(String agentId);
    Optional<AgentScheduleDocument> findByAgentIdAndProjectId(String agentId, String projectId);
    List<AgentScheduleDocument> findByUpdatedAtGreaterThan(Instant since);
}
