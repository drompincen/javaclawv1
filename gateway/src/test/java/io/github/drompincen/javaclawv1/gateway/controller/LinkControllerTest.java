package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.LinkDto;
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
class LinkControllerTest {

    @Mock private ThingService thingService;

    private LinkController controller;

    @BeforeEach
    void setUp() {
        controller = new LinkController(thingService);
        when(thingService.createThing(any(), eq(ThingCategory.LINK), any()))
                .thenAnswer(inv -> {
                    ThingDocument thing = new ThingDocument();
                    thing.setId(java.util.UUID.randomUUID().toString());
                    thing.setProjectId(inv.getArgument(0));
                    thing.setThingCategory(ThingCategory.LINK);
                    thing.setPayload(new LinkedHashMap<>(inv.getArgument(2)));
                    thing.setCreateDate(Instant.now());
                    thing.setUpdateDate(Instant.now());
                    return thing;
                });
    }

    private ThingDocument makeLink(String id, String projectId) {
        ThingDocument thing = new ThingDocument();
        thing.setId(id);
        thing.setProjectId(projectId);
        thing.setThingCategory(ThingCategory.LINK);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", "https://example.com");
        payload.put("title", "Example");
        payload.put("category", "Architecture");
        payload.put("pinned", false);
        payload.put("tags", List.of("docs"));
        thing.setPayload(payload);
        thing.setCreateDate(Instant.now());
        thing.setUpdateDate(Instant.now());
        return thing;
    }

    @Test
    void createSetsIdAndProjectId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", "https://wiki.example.com");
        body.put("title", "Wiki");

        ResponseEntity<LinkDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        LinkDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.linkId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.url()).isEqualTo("https://wiki.example.com");
        assertThat(dto.createdAt()).isNotNull();
        verify(thingService).createThing(eq("p1"), eq(ThingCategory.LINK), any());
    }

    @Test
    void listByProjectReturnsAll() {
        ThingDocument l1 = makeLink("l1", "p1");
        ThingDocument l2 = makeLink("l2", "p1");
        when(thingService.findByProjectAndCategory("p1", ThingCategory.LINK)).thenReturn(List.of(l1, l2));

        List<LinkDto> result = controller.list("p1", null, null);

        assertThat(result).hasSize(2);
        verify(thingService).findByProjectAndCategory("p1", ThingCategory.LINK);
    }

    @Test
    void listByCategoryFilters() {
        ThingDocument l1 = makeLink("l1", "p1");
        l1.getPayload().put("category", "Minutes");
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.LINK, "category", "Minutes"))
                .thenReturn(List.of(l1));

        List<LinkDto> result = controller.list("p1", "Minutes", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("Minutes");
        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.LINK, "category", "Minutes");
    }

    @Test
    void listByBundleIdFilters() {
        ThingDocument l1 = makeLink("l1", "p1");
        l1.getPayload().put("bundleId", "b1");
        when(thingService.findByPayloadField(ThingCategory.LINK, "bundleId", "b1")).thenReturn(List.of(l1));

        List<LinkDto> result = controller.list("p1", null, "b1");

        assertThat(result).hasSize(1);
        verify(thingService).findByPayloadField(ThingCategory.LINK, "bundleId", "b1");
    }

    @Test
    void listCategoryTakesPrecedenceOverBundleId() {
        when(thingService.findByProjectCategoryAndPayload("p1", ThingCategory.LINK, "category", "Arch"))
                .thenReturn(List.of());

        controller.list("p1", "Arch", "b1");

        verify(thingService).findByProjectCategoryAndPayload("p1", ThingCategory.LINK, "category", "Arch");
        verify(thingService, never()).findByPayloadField(any(), any(), any());
    }

    @Test
    void getReturnsLinkWhenFound() {
        ThingDocument doc = makeLink("l1", "p1");
        when(thingService.findById("l1", ThingCategory.LINK)).thenReturn(Optional.of(doc));

        ResponseEntity<LinkDto> response = controller.get("p1", "l1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().linkId()).isEqualTo("l1");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.LINK)).thenReturn(Optional.empty());

        ResponseEntity<LinkDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        ThingDocument existing = makeLink("l1", "p1");
        when(thingService.findById("l1", ThingCategory.LINK)).thenReturn(Optional.of(existing));
        when(thingService.mergePayload(any(), any())).thenAnswer(inv -> {
            ThingDocument thing = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> updates = inv.getArgument(1);
            thing.getPayload().putAll(updates);
            thing.setUpdateDate(Instant.now());
            return thing;
        });

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("title", "Updated Title");
        updates.put("pinned", true);
        updates.put("tags", List.of("important", "docs"));

        ResponseEntity<LinkDto> response = controller.update("p1", "l1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().title()).isEqualTo("Updated Title");
        assertThat(response.getBody().pinned()).isTrue();
        assertThat(response.getBody().tags()).containsExactly("important", "docs");
        assertThat(response.getBody().url()).isEqualTo("https://example.com"); // unchanged
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(thingService.findById("bad", ThingCategory.LINK)).thenReturn(Optional.empty());

        ResponseEntity<LinkDto> response = controller.update("p1", "bad", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesLink() {
        when(thingService.existsById("l1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "l1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(thingService).deleteById("l1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(thingService.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
