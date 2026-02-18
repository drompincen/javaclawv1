package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectiveRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private ObjectiveRepository objectiveRepository;

    @Test
    void findByProjectId() {
        objectiveRepository.save(createObjective("o1", "proj1", "Sprint 1", ObjectiveStatus.PROPOSED));
        objectiveRepository.save(createObjective("o2", "proj1", "Sprint 2", ObjectiveStatus.COMMITTED));
        objectiveRepository.save(createObjective("o3", "proj2", "Sprint 1", ObjectiveStatus.PROPOSED));

        List<ObjectiveDocument> result = objectiveRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndStatus() {
        objectiveRepository.save(createObjective("o1", "proj1", "Sprint 1", ObjectiveStatus.PROPOSED));
        objectiveRepository.save(createObjective("o2", "proj1", "Sprint 1", ObjectiveStatus.COMMITTED));

        List<ObjectiveDocument> result = objectiveRepository.findByProjectIdAndStatus("proj1", ObjectiveStatus.COMMITTED);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getObjectiveId()).isEqualTo("o2");
    }

    @Test
    void findByProjectIdAndSprintName() {
        objectiveRepository.save(createObjective("o1", "proj1", "Sprint 1", ObjectiveStatus.PROPOSED));
        objectiveRepository.save(createObjective("o2", "proj1", "Sprint 2", ObjectiveStatus.PROPOSED));

        List<ObjectiveDocument> result = objectiveRepository.findByProjectIdAndSprintName("proj1", "Sprint 1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getObjectiveId()).isEqualTo("o1");
    }

    private ObjectiveDocument createObjective(String id, String projectId, String sprint, ObjectiveStatus status) {
        ObjectiveDocument doc = new ObjectiveDocument();
        doc.setObjectiveId(id);
        doc.setProjectId(projectId);
        doc.setSprintName(sprint);
        doc.setOutcome("Deliver feature " + id);
        doc.setMeasurableSignal("All tests pass");
        doc.setStatus(status);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
