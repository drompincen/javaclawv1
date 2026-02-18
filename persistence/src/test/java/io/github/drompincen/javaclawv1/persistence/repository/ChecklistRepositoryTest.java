package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChecklistRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private ChecklistRepository checklistRepository;

    @Test
    void findByProjectId() {
        checklistRepository.save(createChecklist("c1", "proj1", "ORR", "ph1", ChecklistStatus.IN_PROGRESS));
        checklistRepository.save(createChecklist("c2", "proj1", "Env Readiness", "ph2", ChecklistStatus.TEMPLATE));
        checklistRepository.save(createChecklist("c3", "proj2", "ORR", "ph3", ChecklistStatus.IN_PROGRESS));

        List<ChecklistDocument> result = checklistRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndStatus() {
        checklistRepository.save(createChecklist("c1", "proj1", "ORR", "ph1", ChecklistStatus.IN_PROGRESS));
        checklistRepository.save(createChecklist("c2", "proj1", "Env", "ph1", ChecklistStatus.COMPLETED));

        List<ChecklistDocument> result = checklistRepository.findByProjectIdAndStatus("proj1", ChecklistStatus.IN_PROGRESS);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("ORR");
    }

    @Test
    void findByPhaseId() {
        checklistRepository.save(createChecklist("c1", "proj1", "ORR", "ph1", ChecklistStatus.IN_PROGRESS));
        checklistRepository.save(createChecklist("c2", "proj1", "Env", "ph1", ChecklistStatus.TEMPLATE));
        checklistRepository.save(createChecklist("c3", "proj1", "UAT", "ph2", ChecklistStatus.IN_PROGRESS));

        List<ChecklistDocument> result = checklistRepository.findByPhaseId("ph1");
        assertThat(result).hasSize(2);
    }

    private ChecklistDocument createChecklist(String id, String projectId, String name, String phaseId, ChecklistStatus status) {
        ChecklistDocument doc = new ChecklistDocument();
        doc.setChecklistId(id);
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setPhaseId(phaseId);
        doc.setStatus(status);
        doc.setItems(List.of());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
