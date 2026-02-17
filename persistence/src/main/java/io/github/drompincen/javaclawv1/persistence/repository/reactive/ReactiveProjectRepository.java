package io.github.drompincen.javaclawv1.persistence.repository.reactive;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface ReactiveProjectRepository extends ReactiveMongoRepository<ProjectDocument, String> {
}
