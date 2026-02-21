package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveDto;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ObjectiveControllerTest {

    @Mock private ThingService thingService;

    private ObjectiveController controller;

    @BeforeEach
    void setUp() {
        controller = new ObjectiveController(thingService);
        when(thingService.createThing(any(), eq(ThingCategory.OBJECTIVE), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(java.util.UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.OBJECTIVE);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    private ThingDocument makeObjective(String id, String projectId) {
        ThingDocument thing = new ThingDocument();
        thing.setId(id);
        thing.setProjectId(projectId);
        thing.setThingCategory(ThingCategory.OBJECTIVE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sprintName", "Sprint 1");
        payload.put("outcome", "Deliver feature X");
        payload.put("status", ObjectiveStatus.PROPOSED.name());
        thing.setPayload(payload);
        thing.setCreateDate(Instant.now());
        thing.setUpdateDate(Instant.now());
        return thing;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "Ship v2");

        ResponseEntity<ObjectiveDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ObjectiveDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.objectiveId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(ObjectiveStatus.PROPOSED);
        assertThat(dto.createdAt()).isNotNull();
        verify(thingService).createThing(eq("p1"), eq(ThingCategory.OBJECTIVE), any());
    }

    @Test
    void createPreservesExplicitStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ObjectiveStatus.COMMITTED.name());

        ResponseEntity<ObjectiveDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(ObjectiveStatus.COMMITTED);
    }

    @Test
    void listByProjectReturnsAll() {
        ThingDocument o1 = makeObjective("o1", "p1");
        ThingDocument o2 = makeObjective("o2", "p1");
        when(thingService.findByProjectAndCategory("p1", ThingCategory.OBJECTIVE))
                .thenReturn(List.of(o1, o2));

        List<ObjectiveDto> result = controller.list("p1", null, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).objectiveId()).isEqualTo("o1");
    }

    @Test
    void listByStatusFilters() {
        ThingDocument o1 = makeObjective("o1", "p1");
        o1.getPayload().put("status", ObjectiveStatus.COMMITTED.name());
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.OBJECTIVE,
                "status", ObjectiveStatus.COMMITTED.name()))
                .thenReturn(List.of(o1));

        List<ObjectiveDto> result = controller.list("p1", ObjectiveStatus.COMMITTED, null);

        assertThat(result).hasSize(1);
        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.OBJECTIVE,
                "status", "COMMITTED");
    }

    @Test
    void listBySprintNameFilters() {
        ThingDocument o1 = makeObjective("o1", "p1");
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.OBJECTIVE,
                "sprintName", "Sprint 1"))
                .thenReturn(List.of(o1));

        List<ObjectiveDto> result = controller.list("p1", null, "Sprint 1");

        assertThat(result).hasSize(1);
        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.OBJECTIVE,
                "sprintName", "Sprint 1");
    }

    @Test
    void getReturnsObjectiveWhenFound() {
        ThingDocument doc = makeObjective("o1", "p1");
        when(thingService.findById("o1", ThingCategory.OBJECTIVE)).thenReturn(Optional.of(doc));

        ResponseEntity<ObjectiveDto> response = controller.get("p1", "o1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().objectiveId()).isEqualTo("o1");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.OBJECTIVE)).thenReturn(Optional.empty());

        ResponseEntity<ObjectiveDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        ThingDocument existing = makeObjective("o1", "p1");
        when(thingService.findById("o1", ThingCategory.OBJECTIVE)).thenReturn(Optional.of(existing));
        when(thingService.mergePayload(any(), any())).thenAnswer(inv -> {
            ThingDocument thing = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> updates = inv.getArgument(1);
            thing.getPayload().putAll(updates);
            thing.setUpdateDate(Instant.now());
            return thing;
        });

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("outcome", "New outcome");
        updates.put("status", ObjectiveStatus.ACHIEVED.name());

        ResponseEntity<ObjectiveDto> response = controller.update("p1", "o1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().outcome()).isEqualTo("New outcome");
        assertThat(response.getBody().status()).isEqualTo(ObjectiveStatus.ACHIEVED);
        assertThat(response.getBody().sprintName()).isEqualTo("Sprint 1"); // unchanged
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.OBJECTIVE)).thenReturn(Optional.empty());

        ResponseEntity<ObjectiveDto> response = controller.update("p1", "bad", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesObjective() {
        when(thingService.existsById("o1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "o1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(thingService).deleteById("o1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(thingService.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
