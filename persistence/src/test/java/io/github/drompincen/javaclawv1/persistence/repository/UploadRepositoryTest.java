package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.UploadDocument;
import io.github.drompincen.javaclawv1.protocol.api.UploadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UploadRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private UploadRepository uploadRepository;

    @Test
    void findByProjectId() {
        uploadRepository.save(createUpload("u1", "proj1", UploadStatus.INBOX, null));
        uploadRepository.save(createUpload("u2", "proj1", UploadStatus.THREADED, "t1"));
        uploadRepository.save(createUpload("u3", "proj2", UploadStatus.INBOX, null));

        List<UploadDocument> result = uploadRepository.findByProjectId("proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByProjectIdAndStatus() {
        uploadRepository.save(createUpload("u1", "proj1", UploadStatus.INBOX, null));
        uploadRepository.save(createUpload("u2", "proj1", UploadStatus.THREADED, "t1"));

        List<UploadDocument> result = uploadRepository.findByProjectIdAndStatus("proj1", UploadStatus.INBOX);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUploadId()).isEqualTo("u1");
    }

    @Test
    void findByThreadId() {
        uploadRepository.save(createUpload("u1", "proj1", UploadStatus.THREADED, "t1"));
        uploadRepository.save(createUpload("u2", "proj1", UploadStatus.THREADED, "t1"));
        uploadRepository.save(createUpload("u3", "proj1", UploadStatus.THREADED, "t2"));

        List<UploadDocument> result = uploadRepository.findByThreadId("t1");
        assertThat(result).hasSize(2);
    }

    private UploadDocument createUpload(String id, String projectId, UploadStatus status, String threadId) {
        UploadDocument doc = new UploadDocument();
        doc.setUploadId(id);
        doc.setProjectId(projectId);
        doc.setSource("paste");
        doc.setTitle("Upload " + id);
        doc.setContent("Content for " + id);
        doc.setContentType("text/plain");
        doc.setStatus(status);
        doc.setThreadId(threadId);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
