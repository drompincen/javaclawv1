package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveDto;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
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
class ObjectiveControllerTest {

    @Mock private ObjectiveRepository objectiveRepository;

    private ObjectiveController controller;

    @BeforeEach
    void setUp() {
        controller = new ObjectiveController(objectiveRepository);
    }

    private ObjectiveDocument makeObjective(String id, String projectId) {
        ObjectiveDocument doc = new ObjectiveDocument();
        doc.setObjectiveId(id);
        doc.setProjectId(projectId);
        doc.setSprintName("Sprint 1");
        doc.setOutcome("Deliver feature X");
        doc.setStatus(ObjectiveStatus.PROPOSED);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        when(objectiveRepository.save(any(ObjectiveDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectiveDocument body = new ObjectiveDocument();
        body.setOutcome("Ship v2");

        ResponseEntity<ObjectiveDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ObjectiveDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.objectiveId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(ObjectiveStatus.PROPOSED);
        assertThat(dto.createdAt()).isNotNull();
        verify(objectiveRepository).save(any(ObjectiveDocument.class));
    }

    @Test
    void createPreservesExplicitStatus() {
        when(objectiveRepository.save(any(ObjectiveDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectiveDocument body = new ObjectiveDocument();
        body.setStatus(ObjectiveStatus.COMMITTED);

        ResponseEntity<ObjectiveDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(ObjectiveStatus.COMMITTED);
    }

    @Test
    void listByProjectReturnsAll() {
        ObjectiveDocument o1 = makeObjective("o1", "p1");
        ObjectiveDocument o2 = makeObjective("o2", "p1");
        when(objectiveRepository.findByProjectId("p1")).thenReturn(List.of(o1, o2));

        List<ObjectiveDto> result = controller.list("p1", null, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).objectiveId()).isEqualTo("o1");
    }

    @Test
    void listByStatusFilters() {
        ObjectiveDocument o1 = makeObjective("o1", "p1");
        o1.setStatus(ObjectiveStatus.COMMITTED);
        when(objectiveRepository.findByProjectIdAndStatus("p1", ObjectiveStatus.COMMITTED))
                .thenReturn(List.of(o1));

        List<ObjectiveDto> result = controller.list("p1", ObjectiveStatus.COMMITTED, null);

        assertThat(result).hasSize(1);
        verify(objectiveRepository).findByProjectIdAndStatus("p1", ObjectiveStatus.COMMITTED);
    }

    @Test
    void listBySprintNameFilters() {
        ObjectiveDocument o1 = makeObjective("o1", "p1");
        when(objectiveRepository.findByProjectIdAndSprintName("p1", "Sprint 1"))
                .thenReturn(List.of(o1));

        List<ObjectiveDto> result = controller.list("p1", null, "Sprint 1");

        assertThat(result).hasSize(1);
        verify(objectiveRepository).findByProjectIdAndSprintName("p1", "Sprint 1");
    }

    @Test
    void getReturnsObjectiveWhenFound() {
        ObjectiveDocument doc = makeObjective("o1", "p1");
        when(objectiveRepository.findById("o1")).thenReturn(Optional.of(doc));

        ResponseEntity<ObjectiveDto> response = controller.get("p1", "o1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().objectiveId()).isEqualTo("o1");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(objectiveRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<ObjectiveDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        ObjectiveDocument existing = makeObjective("o1", "p1");
        when(objectiveRepository.findById("o1")).thenReturn(Optional.of(existing));
        when(objectiveRepository.save(any(ObjectiveDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectiveDocument updates = new ObjectiveDocument();
        updates.setOutcome("New outcome");
        updates.setStatus(ObjectiveStatus.ACHIEVED);

        ResponseEntity<ObjectiveDto> response = controller.update("p1", "o1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().outcome()).isEqualTo("New outcome");
        assertThat(response.getBody().status()).isEqualTo(ObjectiveStatus.ACHIEVED);
        assertThat(response.getBody().sprintName()).isEqualTo("Sprint 1"); // unchanged
        verify(objectiveRepository).save(existing);
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(objectiveRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<ObjectiveDto> response = controller.update("p1", "bad", new ObjectiveDocument());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesObjective() {
        when(objectiveRepository.existsById("o1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "o1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(objectiveRepository).deleteById("o1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(objectiveRepository.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
