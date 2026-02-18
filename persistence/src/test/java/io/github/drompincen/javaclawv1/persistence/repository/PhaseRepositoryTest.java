package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private PhaseRepository phaseRepository;

    @Test
    void findByProjectId() {
        phaseRepository.save(createPhase("ph1", "proj1", "Design", 1, PhaseStatus.COMPLETED));
        phaseRepository.save(createPhase("ph2", "proj1", "Build", 2, PhaseStatus.IN_PROGRESS));
        phaseRepository.save(createPhase("ph3", "proj2", "Design", 1, PhaseStatus.NOT_STARTED));

        List<PhaseDocument> result = phaseRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdOrderBySortOrder() {
        phaseRepository.save(createPhase("ph2", "proj1", "Build", 2, PhaseStatus.NOT_STARTED));
        phaseRepository.save(createPhase("ph1", "proj1", "Design", 1, PhaseStatus.COMPLETED));
        phaseRepository.save(createPhase("ph3", "proj1", "Test", 3, PhaseStatus.NOT_STARTED));

        List<PhaseDocument> result = phaseRepository.findByProjectIdOrderBySortOrder("proj1");
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getName()).isEqualTo("Design");
        assertThat(result.get(1).getName()).isEqualTo("Build");
        assertThat(result.get(2).getName()).isEqualTo("Test");
    }

    @Test
    void findByProjectIdAndStatus() {
        phaseRepository.save(createPhase("ph1", "proj1", "Design", 1, PhaseStatus.COMPLETED));
        phaseRepository.save(createPhase("ph2", "proj1", "Build", 2, PhaseStatus.IN_PROGRESS));

        List<PhaseDocument> result = phaseRepository.findByProjectIdAndStatus("proj1", PhaseStatus.IN_PROGRESS);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Build");
    }

    private PhaseDocument createPhase(String id, String projectId, String name, int order, PhaseStatus status) {
        PhaseDocument doc = new PhaseDocument();
        doc.setPhaseId(id);
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setDescription(name + " phase");
        doc.setSortOrder(order);
        doc.setStatus(status);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
