package io.github.drompincen.javaclawv1.persistence.repository.reactive;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface ReactiveTicketRepository extends ReactiveMongoRepository<TicketDocument, String> {
}
