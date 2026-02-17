package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TicketRepository extends MongoRepository<TicketDocument, String> {
    List<TicketDocument> findByProjectId(String projectId);
    List<TicketDocument> findByProjectIdAndStatus(String projectId, TicketDto.TicketStatus status);
}
