package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.PhaseDto;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
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
class PhaseControllerTest {

    @Mock private ThingService thingService;

    private PhaseController controller;

    @BeforeEach
    void setUp() {
        controller = new PhaseController(thingService);
        when(thingService.createThing(any(), eq(ThingCategory.PHASE), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(java.util.UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.PHASE);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    private ThingDocument makePhase(String id, String projectId) {
        ThingDocument thing = new ThingDocument();
        thing.setId(id);
        thing.setProjectId(projectId);
        thing.setThingCategory(ThingCategory.PHASE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Design Phase");
        payload.put("description", "Initial design work");
        payload.put("status", PhaseStatus.NOT_STARTED.name());
        payload.put("sortOrder", 1);
        thing.setPayload(payload);
        thing.setCreateDate(Instant.now());
        thing.setUpdateDate(Instant.now());
        return thing;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Planning");

        ResponseEntity<PhaseDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PhaseDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.phaseId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(PhaseStatus.NOT_STARTED);
        assertThat(dto.createdAt()).isNotNull();
        verify(thingService).createThing(eq("p1"), eq(ThingCategory.PHASE), any());
    }

    @Test
    void createPreservesExplicitStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", PhaseStatus.IN_PROGRESS.name());

        ResponseEntity<PhaseDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(PhaseStatus.IN_PROGRESS);
    }

    @Test
    void listByProjectReturnsSortedByOrder() {
        ThingDocument p1 = makePhase("ph1", "p1");
        ThingDocument p2 = makePhase("ph2", "p1");
        when(thingService.findByProjectAndCategorySorted("p1", ThingCategory.PHASE, "payload.sortOrder", true))
                .thenReturn(List.of(p1, p2));

        List<PhaseDto> result = controller.list("p1", null);

        assertThat(result).hasSize(2);
        verify(thingService).findByProjectAndCategorySorted("p1", ThingCategory.PHASE, "payload.sortOrder", true);
    }

    @Test
    void listByStatusFilters() {
        ThingDocument p1 = makePhase("ph1", "p1");
        p1.getPayload().put("status", PhaseStatus.IN_PROGRESS.name());
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.PHASE,
                "status", "IN_PROGRESS"))
                .thenReturn(List.of(p1));

        List<PhaseDto> result = controller.list("p1", PhaseStatus.IN_PROGRESS);

        assertThat(result).hasSize(1);
        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.PHASE, "status", "IN_PROGRESS");
    }

    @Test
    void getReturnsPhaseWhenFound() {
        ThingDocument doc = makePhase("ph1", "p1");
        when(thingService.findById("ph1", ThingCategory.PHASE)).thenReturn(Optional.of(doc));

        ResponseEntity<PhaseDto> response = controller.get("p1", "ph1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().phaseId()).isEqualTo("ph1");
        assertThat(response.getBody().name()).isEqualTo("Design Phase");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.PHASE)).thenReturn(Optional.empty());

        ResponseEntity<PhaseDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        ThingDocument existing = makePhase("ph1", "p1");
        when(thingService.findById("ph1", ThingCategory.PHASE)).thenReturn(Optional.of(existing));
        when(thingService.mergePayload(any(), any())).thenAnswer(inv -> {
            ThingDocument thing = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> updates = inv.getArgument(1);
            thing.getPayload().putAll(updates);
            thing.setUpdateDate(Instant.now());
            return thing;
        });

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("name", "Updated Phase");
        updates.put("status", PhaseStatus.COMPLETED.name());
        updates.put("entryCriteria", List.of("PRD approved"));

        ResponseEntity<PhaseDto> response = controller.update("p1", "ph1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().name()).isEqualTo("Updated Phase");
        assertThat(response.getBody().status()).isEqualTo(PhaseStatus.COMPLETED);
        assertThat(response.getBody().entryCriteria()).containsExactly("PRD approved");
        assertThat(response.getBody().description()).isEqualTo("Initial design work"); // unchanged
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.PHASE)).thenReturn(Optional.empty());

        ResponseEntity<PhaseDto> response = controller.update("p1", "bad", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesPhase() {
        when(thingService.existsById("ph1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "ph1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(thingService).deleteById("ph1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(thingService.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
