package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ResourceAssignmentRepository extends MongoRepository<ResourceAssignmentDocument, String> {
    List<ResourceAssignmentDocument> findByResourceId(String resourceId);
    List<ResourceAssignmentDocument> findByTicketId(String ticketId);
    List<ResourceAssignmentDocument> findByProjectId(String projectId);
    java.util.Optional<ResourceAssignmentDocument> findByResourceIdAndTicketId(String resourceId, String ticketId);
}
