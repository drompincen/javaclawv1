package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ReconciliationDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReconciliationRepository;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationDto;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationControllerTest {

    @Mock private ReconciliationRepository reconciliationRepository;

    private ReconciliationController controller;

    @BeforeEach
    void setUp() {
        controller = new ReconciliationController(reconciliationRepository);
    }

    private ReconciliationDocument makeReconciliation(String id, String projectId) {
        ReconciliationDocument doc = new ReconciliationDocument();
        doc.setReconciliationId(id);
        doc.setProjectId(projectId);
        doc.setSourceUploadId("upload-1");
        doc.setSourceType("JIRA_CSV");
        doc.setStatus(ReconciliationStatus.DRAFT);

        ReconciliationDocument.MappingEntry m = new ReconciliationDocument.MappingEntry();
        m.setSourceRow("row-1");
        m.setTicketId("t1");
        m.setMatchType("EXACT");
        doc.setMappings(List.of(m));

        ReconciliationDocument.ConflictEntry c = new ReconciliationDocument.ConflictEntry();
        c.setField("priority");
        c.setSourceValue("HIGH");
        c.setTicketValue("MEDIUM");
        c.setResolution("PENDING");
        doc.setConflicts(List.of(c));

        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        when(reconciliationRepository.save(any(ReconciliationDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDocument body = new ReconciliationDocument();
        body.setSourceType("JIRA_CSV");

        ResponseEntity<ReconciliationDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ReconciliationDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.reconciliationId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(ReconciliationStatus.DRAFT);
        assertThat(dto.createdAt()).isNotNull();
        verify(reconciliationRepository).save(any(ReconciliationDocument.class));
    }

    @Test
    void createPreservesExplicitStatus() {
        when(reconciliationRepository.save(any(ReconciliationDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDocument body = new ReconciliationDocument();
        body.setStatus(ReconciliationStatus.REVIEWED);

        ResponseEntity<ReconciliationDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(ReconciliationStatus.REVIEWED);
    }

    @Test
    void listByProjectReturnsAll() {
        ReconciliationDocument r1 = makeReconciliation("r1", "p1");
        ReconciliationDocument r2 = makeReconciliation("r2", "p1");
        when(reconciliationRepository.findByProjectId("p1")).thenReturn(List.of(r1, r2));

        List<ReconciliationDto> result = controller.list("p1", null);

        assertThat(result).hasSize(2);
        verify(reconciliationRepository).findByProjectId("p1");
    }

    @Test
    void listByStatusFilters() {
        ReconciliationDocument r1 = makeReconciliation("r1", "p1");
        r1.setStatus(ReconciliationStatus.APPLIED);
        when(reconciliationRepository.findByProjectIdAndStatus("p1", ReconciliationStatus.APPLIED))
                .thenReturn(List.of(r1));

        List<ReconciliationDto> result = controller.list("p1", ReconciliationStatus.APPLIED);

        assertThat(result).hasSize(1);
        verify(reconciliationRepository).findByProjectIdAndStatus("p1", ReconciliationStatus.APPLIED);
    }

    @Test
    void getReturnsReconciliationWhenFound() {
        ReconciliationDocument doc = makeReconciliation("r1", "p1");
        when(reconciliationRepository.findById("r1")).thenReturn(Optional.of(doc));

        ResponseEntity<ReconciliationDto> response = controller.get("p1", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ReconciliationDto dto = response.getBody();
        assertThat(dto.reconciliationId()).isEqualTo("r1");
        assertThat(dto.mappings()).hasSize(1);
        assertThat(dto.mappings().get(0).sourceRow()).isEqualTo("row-1");
        assertThat(dto.conflicts()).hasSize(1);
        assertThat(dto.conflicts().get(0).field()).isEqualTo("priority");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(reconciliationRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<ReconciliationDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void toDtoHandlesNullMappingsAndConflicts() {
        ReconciliationDocument doc = new ReconciliationDocument();
        doc.setReconciliationId("r1");
        doc.setProjectId("p1");
        doc.setStatus(ReconciliationStatus.DRAFT);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        // mappings and conflicts are null
        when(reconciliationRepository.findById("r1")).thenReturn(Optional.of(doc));

        ResponseEntity<ReconciliationDto> response = controller.get("p1", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().mappings()).isEmpty();
        assertThat(response.getBody().conflicts()).isEmpty();
    }

    @Test
    void updateAppliesPartialChanges() {
        ReconciliationDocument existing = makeReconciliation("r1", "p1");
        when(reconciliationRepository.findById("r1")).thenReturn(Optional.of(existing));
        when(reconciliationRepository.save(any(ReconciliationDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDocument updates = new ReconciliationDocument();
        updates.setStatus(ReconciliationStatus.APPLIED);
        updates.setSourceType("AZURE_CSV");

        ResponseEntity<ReconciliationDto> response = controller.update("p1", "r1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo(ReconciliationStatus.APPLIED);
        assertThat(response.getBody().sourceType()).isEqualTo("AZURE_CSV");
        assertThat(response.getBody().sourceUploadId()).isEqualTo("upload-1"); // unchanged
        verify(reconciliationRepository).save(existing);
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(reconciliationRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<ReconciliationDto> response = controller.update("p1", "bad", new ReconciliationDocument());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesReconciliation() {
        when(reconciliationRepository.existsById("r1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(reconciliationRepository).deleteById("r1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(reconciliationRepository.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
