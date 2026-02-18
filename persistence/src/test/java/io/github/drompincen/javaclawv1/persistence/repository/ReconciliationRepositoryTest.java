package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.ReconciliationDocument;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private ReconciliationRepository reconciliationRepository;

    @Test
    void findByProjectId() {
        reconciliationRepository.save(createReconciliation("r1", "proj1", "u1", ReconciliationStatus.DRAFT));
        reconciliationRepository.save(createReconciliation("r2", "proj1", "u2", ReconciliationStatus.REVIEWED));
        reconciliationRepository.save(createReconciliation("r3", "proj2", "u3", ReconciliationStatus.DRAFT));

        List<ReconciliationDocument> result = reconciliationRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndStatus() {
        reconciliationRepository.save(createReconciliation("r1", "proj1", "u1", ReconciliationStatus.DRAFT));
        reconciliationRepository.save(createReconciliation("r2", "proj1", "u2", ReconciliationStatus.REVIEWED));

        List<ReconciliationDocument> result = reconciliationRepository.findByProjectIdAndStatus("proj1", ReconciliationStatus.DRAFT);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReconciliationId()).isEqualTo("r1");
    }

    @Test
    void findBySourceUploadId() {
        reconciliationRepository.save(createReconciliation("r1", "proj1", "u1", ReconciliationStatus.DRAFT));
        reconciliationRepository.save(createReconciliation("r2", "proj1", "u1", ReconciliationStatus.REVIEWED));
        reconciliationRepository.save(createReconciliation("r3", "proj1", "u2", ReconciliationStatus.DRAFT));

        List<ReconciliationDocument> result = reconciliationRepository.findBySourceUploadId("u1");
        assertThat(result).hasSize(2);
    }

    private ReconciliationDocument createReconciliation(String id, String projectId, String uploadId, ReconciliationStatus status) {
        ReconciliationDocument doc = new ReconciliationDocument();
        doc.setReconciliationId(id);
        doc.setProjectId(projectId);
        doc.setSourceUploadId(uploadId);
        doc.setSourceType("smartsheet");
        doc.setStatus(status);
        doc.setMappings(List.of());
        doc.setConflicts(List.of());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
