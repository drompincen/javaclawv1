package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.LinkDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LinkRepository;
import io.github.drompincen.javaclawv1.protocol.api.LinkDto;
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
class LinkControllerTest {

    @Mock private LinkRepository linkRepository;

    private LinkController controller;

    @BeforeEach
    void setUp() {
        controller = new LinkController(linkRepository);
    }

    private LinkDocument makeLink(String id, String projectId) {
        LinkDocument doc = new LinkDocument();
        doc.setLinkId(id);
        doc.setProjectId(projectId);
        doc.setUrl("https://example.com");
        doc.setTitle("Example");
        doc.setCategory("Architecture");
        doc.setPinned(false);
        doc.setTags(List.of("docs"));
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }

    @Test
    void createSetsIdAndProjectId() {
        when(linkRepository.save(any(LinkDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        LinkDocument body = new LinkDocument();
        body.setUrl("https://wiki.example.com");
        body.setTitle("Wiki");

        ResponseEntity<LinkDto> response = controller.create("p1", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        LinkDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.linkId()).isNotNull();
        assertThat(dto.projectId()).isEqualTo("p1");
        assertThat(dto.url()).isEqualTo("https://wiki.example.com");
        assertThat(dto.createdAt()).isNotNull();
        verify(linkRepository).save(any(LinkDocument.class));
    }

    @Test
    void listByProjectReturnsAll() {
        LinkDocument l1 = makeLink("l1", "p1");
        LinkDocument l2 = makeLink("l2", "p1");
        when(linkRepository.findByProjectId("p1")).thenReturn(List.of(l1, l2));

        List<LinkDto> result = controller.list("p1", null, null);

        assertThat(result).hasSize(2);
        verify(linkRepository).findByProjectId("p1");
    }

    @Test
    void listByCategoryFilters() {
        LinkDocument l1 = makeLink("l1", "p1");
        l1.setCategory("Minutes");
        when(linkRepository.findByProjectIdAndCategory("p1", "Minutes"))
                .thenReturn(List.of(l1));

        List<LinkDto> result = controller.list("p1", "Minutes", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("Minutes");
        verify(linkRepository).findByProjectIdAndCategory("p1", "Minutes");
    }

    @Test
    void listByBundleIdFilters() {
        LinkDocument l1 = makeLink("l1", "p1");
        l1.setBundleId("b1");
        when(linkRepository.findByBundleId("b1")).thenReturn(List.of(l1));

        List<LinkDto> result = controller.list("p1", null, "b1");

        assertThat(result).hasSize(1);
        verify(linkRepository).findByBundleId("b1");
    }

    @Test
    void listCategoryTakesPrecedenceOverBundleId() {
        when(linkRepository.findByProjectIdAndCategory("p1", "Arch"))
                .thenReturn(List.of());

        controller.list("p1", "Arch", "b1");

        verify(linkRepository).findByProjectIdAndCategory("p1", "Arch");
        verify(linkRepository, never()).findByBundleId(any());
    }

    @Test
    void getReturnsLinkWhenFound() {
        LinkDocument doc = makeLink("l1", "p1");
        when(linkRepository.findById("l1")).thenReturn(Optional.of(doc));

        ResponseEntity<LinkDto> response = controller.get("p1", "l1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().linkId()).isEqualTo("l1");
    }

    @Test
    void getReturns404WhenNotFound() {
        when(linkRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<LinkDto> response = controller.get("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updateAppliesPartialChanges() {
        LinkDocument existing = makeLink("l1", "p1");
        when(linkRepository.findById("l1")).thenReturn(Optional.of(existing));
        when(linkRepository.save(any(LinkDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        LinkDocument updates = new LinkDocument();
        updates.setTitle("Updated Title");
        updates.setPinned(true);
        updates.setTags(List.of("important", "docs"));

        ResponseEntity<LinkDto> response = controller.update("p1", "l1", updates);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().title()).isEqualTo("Updated Title");
        assertThat(response.getBody().pinned()).isTrue();
        assertThat(response.getBody().tags()).containsExactly("important", "docs");
        assertThat(response.getBody().url()).isEqualTo("https://example.com"); // unchanged
        verify(linkRepository).save(existing);
    }

    @Test
    void updateReturns404WhenNotFound() {
        when(linkRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<LinkDto> response = controller.update("p1", "bad", new LinkDocument());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesLink() {
        when(linkRepository.existsById("l1")).thenReturn(true);

        ResponseEntity<Void> response = controller.delete("p1", "l1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(linkRepository).deleteById("l1");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(linkRepository.existsById("bad")).thenReturn(false);

        ResponseEntity<Void> response = controller.delete("p1", "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
