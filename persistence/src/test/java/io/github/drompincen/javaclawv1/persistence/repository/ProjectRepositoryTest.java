package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.protocol.api.ProjectDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void saveAndFindById() {
        ProjectDocument doc = createProject("p1", "Test Project", ProjectDto.ProjectStatus.ACTIVE);
        projectRepository.save(doc);

        Optional<ProjectDocument> found = projectRepository.findById("p1");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test Project");
    }

    @Test
    void findByStatus() {
        projectRepository.save(createProject("p1", "Active", ProjectDto.ProjectStatus.ACTIVE));
        projectRepository.save(createProject("p2", "Archived", ProjectDto.ProjectStatus.ARCHIVED));
        projectRepository.save(createProject("p3", "Also Active", ProjectDto.ProjectStatus.ACTIVE));

        List<ProjectDocument> active = projectRepository.findByStatus(ProjectDto.ProjectStatus.ACTIVE);
        assertThat(active).hasSize(2);
    }

    @Test
    void findAllByOrderByUpdatedAtDesc() {
        Instant now = Instant.now();
        ProjectDocument older = createProject("p1", "Older", ProjectDto.ProjectStatus.ACTIVE);
        older.setUpdatedAt(now.minusSeconds(60));
        ProjectDocument newer = createProject("p2", "Newer", ProjectDto.ProjectStatus.ACTIVE);
        newer.setUpdatedAt(now);

        projectRepository.saveAll(List.of(older, newer));

        List<ProjectDocument> sorted = projectRepository.findAllByOrderByUpdatedAtDesc();
        assertThat(sorted).hasSize(2);
        assertThat(sorted.get(0).getProjectId()).isEqualTo("p2");
    }

    @Test
    void findByNameIgnoreCase() {
        projectRepository.save(createProject("p1", "My Project", ProjectDto.ProjectStatus.ACTIVE));

        Optional<ProjectDocument> found = projectRepository.findByNameIgnoreCase("my project");
        assertThat(found).isPresent();
        assertThat(found.get().getProjectId()).isEqualTo("p1");
    }

    private ProjectDocument createProject(String id, String name, ProjectDto.ProjectStatus status) {
        ProjectDocument doc = new ProjectDocument();
        doc.setProjectId(id);
        doc.setName(name);
        doc.setStatus(status);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
