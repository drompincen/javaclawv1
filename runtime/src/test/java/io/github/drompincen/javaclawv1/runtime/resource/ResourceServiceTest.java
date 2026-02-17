package io.github.drompincen.javaclawv1.runtime.resource;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock
    private ResourceRepository resourceRepository;
    @Mock
    private ResourceAssignmentRepository assignmentRepository;

    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        resourceService = new ResourceService(resourceRepository, assignmentRepository);
    }

    @Test
    void getWorkloadsAggregatesCorrectly() {
        ResourceAssignmentDocument a1 = new ResourceAssignmentDocument();
        a1.setResourceId("r1");
        a1.setPercentageAllocation(50.0);

        ResourceAssignmentDocument a2 = new ResourceAssignmentDocument();
        a2.setResourceId("r1");
        a2.setPercentageAllocation(30.0);

        ResourceAssignmentDocument a3 = new ResourceAssignmentDocument();
        a3.setResourceId("r2");
        a3.setPercentageAllocation(100.0);

        when(assignmentRepository.findAll()).thenReturn(List.of(a1, a2, a3));

        Map<String, Double> workloads = resourceService.getWorkloads();

        assertThat(workloads).containsEntry("r1", 80.0);
        assertThat(workloads).containsEntry("r2", 100.0);
    }

    @Test
    void createSetsResourceId() {
        ResourceDocument resource = new ResourceDocument();
        resource.setName("Alice");
        when(resourceRepository.save(any(ResourceDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ResourceDocument saved = resourceService.create(resource);

        assertThat(saved.getResourceId()).isNotNull();
    }

    @Test
    void assignToTicketCreatesAssignment() {
        when(assignmentRepository.save(any(ResourceAssignmentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResourceAssignmentDocument assignment = resourceService.assignToTicket("r1", "t1", 50.0);

        assertThat(assignment.getAssignmentId()).isNotNull();
        assertThat(assignment.getResourceId()).isEqualTo("r1");
        assertThat(assignment.getTicketId()).isEqualTo("t1");
        assertThat(assignment.getPercentageAllocation()).isEqualTo(50.0);
    }
}
