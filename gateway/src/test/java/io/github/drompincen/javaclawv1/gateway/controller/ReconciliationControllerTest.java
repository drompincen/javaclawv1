package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationDto;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
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
class ReconciliationControllerTest {

    @Mock private ThingService thingService;

    private ReconciliationController controller;

    @BeforeEach
    void setUp() {
        controller = new ReconciliationController(thingService);
        when(thingService.createThing(any(), eq(ThingCategory.RECONCILIATION), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(java.util.UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.RECONCILIATION);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    private ThingDocument makeReconciliation(String id, String projectId) {
        ThingDocument thing = new ThingDocument();
        thing.setId(id);
        thing.setProjectId(projectId);
        thing.setThingCategory(ThingCategory.RECONCILIATION);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceUploadId", "upload-1");
        payload.put("sourceType", "JIRA_CSV");
        payload.put("status", ReconciliationStatus.DRAFT.name());

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sourceRow", "row-1");
        mapping.put("ticketId", "t1");
        mapping.put("matchType", "EXACT");
        payload.put("mappings", List.of(mapping));

        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("field", "priority");
        conflict.put("sourceValue", "HIGH");
        conflict.put("ticketValue", "MEDIUM");
        conflict.put("resolution", "PENDING");
        payload.put("conflicts", List.of(conflict));

        thing.setPayload(payload);
        thing.setCreateDate(Instant.now());
        thing.setUpdateDate(Instant.now());
        return thing;
    }

    @Test
    void createSetsIdAndProjectIdAndDefaults() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceType", "JIRA_CSV");

        ResponseEntity<ReconciliationDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ReconciliationDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.reconciliationId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.status()).isEqualTo(ReconciliationStatus.DRAFT);
        assertThat(dto.createdAt()).isNotNull();
        verify(thingService).createThing(eq("p1"), eq(ThingCategory.RECONCILIATION), any());
    }

    @Test
    void createPreservesExplicitStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ReconciliationStatus.REVIEWED.name());

        ResponseEntity<ReconciliationDto> response = controller.create("p1", body);

        assertThat(response.getBody().status()).isEqualTo(ReconciliationStatus.REVIEWED);
    }

    @Test
    void listByProjectReturnsAll() {
        ThingDocument r1 = makeReconciliation("r1", "p1");
        ThingDocument r2 = makeReconciliation("r2", "p1");
        when(thingService.findByProjectAndCategory("p1", ThingCategory.RECONCILIATION))
                .thenReturn(List.of(r1, r2));

        List<ReconciliationDto> result = controller.list("p1", null);

        assertThat(result).hasSize(2);
        verify(thingService).findByProjectAndCategory("p1", ThingCategory.RECONCILIATION);
    }

    @Test
    void listByStatusFilters() {
        ThingDocument r1 = makeReconciliation("r1", "p1");
        r1.getPayload().put("status", ReconciliationStatus.APPLIED.name());
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.RECONCILIATION,
                "status", "APPLIED"))
                .thenReturn(List.of(r1));

        List<ReconciliationDto> result = controller.list("p1", ReconciliationStatus.APPLIED);

        assertThat(result).hasSize(1);
        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.RECONCILIATION, "status", "APPLIED");
    }

    @Test
    void getReturnsReconciliationWhenFound() {
        ThingDocument doc = makeReconciliation("r1", "p1");
        when(thingService.findById("r1", ThingCategory.RECONCILIATION)).thenReturn(Optional.of(doc));

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
        when(thingService.findById("bad", ThingCategory.RECONCILIATION)).thenReturn(Optional.empty());

        ResponseEntity<ReconciliationDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void toDtoHandlesNullMappingsAndConflicts() {
        ThingDocument doc = new ThingDocument();
        doc.setId("r1");
        doc.setProjectId("p1");
        doc.setThingCategory(ThingCategory.RECONCILIATION);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", ReconciliationStatus.DRAFT.name());
        doc.setPayload(payload);
        doc.setCreateDate(Instant.now());
        doc.setUpdateDate(Instant.now());
        when(thingService.findById("r1", ThingCategory.RECONCILIATION)).thenReturn(Optional.of(doc));

        ResponseEntity<ReconciliationDto> response = controller.get("p1", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().mappings()).isEmpty();
        assertThat(response.getBody().conflicts()).isEmpty();
    }

    @Test
    void updateAppliesPartialChanges() {
        ThingDocument existing = makeReconciliation("r1", "p1");
        when(thingService.findById("r1", ThingCategory.RECONCILIATION)).thenReturn(Optional.of(existing));
        when(thingService.mergePayload(any(), any())).thenAnswer(inv -> {
            ThingDocument thing = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> updates = inv.getArgument(1);
            thing.getPayload().putAll(updates);
            thing.setUpdateDate(Instant.now());
            return thing;
        });

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("status", ReconciliationStatus.APPLIED.name());
        updates.put("sourceType", "AZURE_CSV");

        ResponseEntity<ReconciliationDto> response = controller.update("p1", "r1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo(ReconciliationStatus.APPLIED);
        assertThat(response.getBody().sourceType()).isEqualTo("AZURE_CSV");
        assertThat(response.getBody().sourceUploadId()).isEqualTo("upload-1"); // unchanged
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.RECONCILIATION)).thenReturn(Optional.empty());

        ResponseEntity<ReconciliationDto> response = controller.update("p1", "bad", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesReconciliation() {
        when(thingService.existsById("r1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(thingService).deleteById("r1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(thingService.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
