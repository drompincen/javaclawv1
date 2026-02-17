package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AgentRepository extends MongoRepository<AgentDocument, String> {

    List<AgentDocument> findByRole(AgentRole role);

    List<AgentDocument> findByEnabledTrue();
}
