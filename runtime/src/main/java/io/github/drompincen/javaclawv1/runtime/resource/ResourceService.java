package io.github.drompincen.javaclawv1.runtime.resource;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final ResourceAssignmentRepository assignmentRepository;

    public ResourceService(ResourceRepository resourceRepository,
                           ResourceAssignmentRepository assignmentRepository) {
        this.resourceRepository = resourceRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public ResourceDocument create(ResourceDocument resource) {
        resource.setResourceId(UUID.randomUUID().toString());
        return resourceRepository.save(resource);
    }

    public List<ResourceDocument> findAll() {
        return resourceRepository.findAll();
    }

    public Optional<ResourceDocument> findById(String resourceId) {
        return resourceRepository.findById(resourceId);
    }

    public ResourceAssignmentDocument assignToTicket(String resourceId, String ticketId, double percentAllocation) {
        ResourceAssignmentDocument assignment = new ResourceAssignmentDocument();
        assignment.setAssignmentId(UUID.randomUUID().toString());
        assignment.setResourceId(resourceId);
        assignment.setTicketId(ticketId);
        assignment.setPercentageAllocation(percentAllocation);
        return assignmentRepository.save(assignment);
    }

    public Map<String, Double> getWorkloads() {
        return assignmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        ResourceAssignmentDocument::getResourceId,
                        Collectors.summingDouble(ResourceAssignmentDocument::getPercentageAllocation)));
    }

    public List<ResourceAssignmentDocument> getAssignmentsForResource(String resourceId) {
        return assignmentRepository.findByResourceId(resourceId);
    }
}
