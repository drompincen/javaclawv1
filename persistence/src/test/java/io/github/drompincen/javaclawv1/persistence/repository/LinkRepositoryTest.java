package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.LinkDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private LinkRepository linkRepository;

    @Test
    void findByProjectId() {
        linkRepository.save(createLink("l1", "proj1", "architecture", false, null));
        linkRepository.save(createLink("l2", "proj1", "runbook", true, null));
        linkRepository.save(createLink("l3", "proj2", "repo", false, null));

        List<LinkDocument> result = linkRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndCategory() {
        linkRepository.save(createLink("l1", "proj1", "architecture", false, null));
        linkRepository.save(createLink("l2", "proj1", "runbook", false, null));

        List<LinkDocument> result = linkRepository.findByProjectIdAndCategory("proj1", "architecture");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLinkId()).isEqualTo("l1");
    }

    @Test
    void findByProjectIdAndPinnedTrue() {
        linkRepository.save(createLink("l1", "proj1", "architecture", true, null));
        linkRepository.save(createLink("l2", "proj1", "runbook", false, null));
        linkRepository.save(createLink("l3", "proj1", "repo", true, null));

        List<LinkDocument> result = linkRepository.findByProjectIdAndPinnedTrue("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByBundleId() {
        linkRepository.save(createLink("l1", "proj1", "architecture", false, "b1"));
        linkRepository.save(createLink("l2", "proj1", "runbook", false, "b1"));
        linkRepository.save(createLink("l3", "proj1", "repo", false, "b2"));

        List<LinkDocument> result = linkRepository.findByBundleId("b1");
        assertThat(result).hasSize(2);
    }

    private LinkDocument createLink(String id, String projectId, String category, boolean pinned, String bundleId) {
        LinkDocument doc = new LinkDocument();
        doc.setLinkId(id);
        doc.setProjectId(projectId);
        doc.setUrl("https://example.com/" + id);
        doc.setTitle("Link " + id);
        doc.setCategory(category);
        doc.setPinned(pinned);
        doc.setBundleId(bundleId);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
