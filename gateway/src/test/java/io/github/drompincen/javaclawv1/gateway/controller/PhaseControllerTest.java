package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.PhaseDto;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
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
class PhaseControllerTest {

    @Mock private PhaseRepository phaseRepository;

    private PhaseController controller;

    @BeforeEach
    void setUp() {
        controller = new PhaseController(phaseRepository);
    }

    private PhaseDocument makePhase(String id, String projectId) {
        PhaseDocument doc = new PhaseDocument();
        doc.setPhaseId(id);
        doc.setProjectId(projectId);
        doc.setName("Design Phase");
        doc.setDescription("Initial design work");
        doc.setStatus(PhaseStatus.NOT_STARTED);
        doc.setSortOrder(1);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        when(phaseRepository.save(any(PhaseDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        PhaseDocument body = new PhaseDocument();
        body.setName("Planning");

        ResponseEntity<PhaseDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PhaseDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.phaseId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(PhaseStatus.NOT_STARTED);
        assertThat(dto.createdAt()).isNotNull();
        verify(phaseRepository).save(any(PhaseDocument.class));
    }

    @Test
    void createPreservesExplicitStatus() {
        when(phaseRepository.save(any(PhaseDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        PhaseDocument body = new PhaseDocument();
        body.setStatus(PhaseStatus.IN_PROGRESS);

        ResponseEntity<PhaseDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(PhaseStatus.IN_PROGRESS);
    }

    @Test
    void listByProjectReturnsSortedByOrder() {
        PhaseDocument p1 = makePhase("ph1", "p1");
        PhaseDocument p2 = makePhase("ph2", "p1");
        when(phaseRepository.findByProjectIdOrderBySortOrder("p1")).thenReturn(List.of(p1, p2));

        List<PhaseDto> result = controller.list("p1", null);

        assertThat(result).hasSize(2);
        verify(phaseRepository).findByProjectIdOrderBySortOrder("p1");
    }

    @Test
    void listByStatusFilters() {
        PhaseDocument p1 = makePhase("ph1", "p1");
        p1.setStatus(PhaseStatus.IN_PROGRESS);
        when(phaseRepository.findByProjectIdAndStatus("p1", PhaseStatus.IN_PROGRESS))
                .thenReturn(List.of(p1));

        List<PhaseDto> result = controller.list("p1", PhaseStatus.IN_PROGRESS);

        assertThat(result).hasSize(1);
        verify(phaseRepository).findByProjectIdAndStatus("p1", PhaseStatus.IN_PROGRESS);
    }

    @Test
    void getReturnsPhaseWhenFound() {
        PhaseDocument doc = makePhase("ph1", "p1");
        when(phaseRepository.findById("ph1")).thenReturn(Optional.of(doc));

        ResponseEntity<PhaseDto> response = controller.get("p1", "ph1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().phaseId()).isEqualTo("ph1");
        assertThat(response.getBody().name()).isEqualTo("Design Phase");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(phaseRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<PhaseDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        PhaseDocument existing = makePhase("ph1", "p1");
        when(phaseRepository.findById("ph1")).thenReturn(Optional.of(existing));
        when(phaseRepository.save(any(PhaseDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        PhaseDocument updates = new PhaseDocument();
        updates.setName("Updated Phase");
        updates.setStatus(PhaseStatus.COMPLETED);
        updates.setEntryCriteria(List.of("PRD approved"));

        ResponseEntity<PhaseDto> response = controller.update("p1", "ph1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().name()).isEqualTo("Updated Phase");
        assertThat(response.getBody().status()).isEqualTo(PhaseStatus.COMPLETED);
        assertThat(response.getBody().entryCriteria()).containsExactly("PRD approved");
        assertThat(response.getBody().description()).isEqualTo("Initial design work"); // unchanged
        verify(phaseRepository).save(existing);
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(phaseRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<PhaseDto> response = controller.update("p1", "bad", new PhaseDocument());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesPhase() {
        when(phaseRepository.existsById("ph1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "ph1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(phaseRepository).deleteById("ph1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(phaseRepository.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
