package io.github.drompincen.javaclawv1.persistence.repository.reactive;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface ReactiveEventRepository extends ReactiveMongoRepository<EventDocument, String> {
}
