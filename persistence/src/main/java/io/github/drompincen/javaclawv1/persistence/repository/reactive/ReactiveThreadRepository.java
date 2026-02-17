package io.github.drompincen.javaclawv1.persistence.repository.reactive;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface ReactiveThreadRepository extends ReactiveMongoRepository<ThreadDocument, String> {
}
