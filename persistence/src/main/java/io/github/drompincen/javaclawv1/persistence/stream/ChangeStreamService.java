package io.github.drompincen.javaclawv1.persistence.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ChangeStreamOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import org.springframework.data.mongodb.core.aggregation.Aggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class ChangeStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChangeStreamService.class);

    private final ReactiveMongoTemplate reactiveMongoTemplate;

    public ChangeStreamService(ReactiveMongoTemplate reactiveMongoTemplate) {
        this.reactiveMongoTemplate = reactiveMongoTemplate;
    }

    public <T> Flux<T> watchCollection(String collection, Class<T> type) {
        return reactiveMongoTemplate.changeStream(collection,
                        ChangeStreamOptions.builder()
                                .filter(Aggregation.newAggregation(match(where("operationType").is("insert"))))
                                .build(),
                        type)
                .map(event -> event.getBody())
                .filter(body -> body != null)
                .doOnError(e -> log.warn("Change stream error on {}: {}", collection, e.getMessage()));
    }

    public <T> Flux<T> watchInserts(String collection, Class<T> type) {
        return watchCollection(collection, type);
    }

    public <T> Flux<T> watchByField(String collection, Class<T> type, String field, Object value) {
        return reactiveMongoTemplate.changeStream(collection,
                        ChangeStreamOptions.builder()
                                .filter(Aggregation.newAggregation(match(where("operationType").is("insert")
                                        .and("fullDocument." + field).is(value))))
                                .build(),
                        type)
                .map(event -> event.getBody())
                .filter(body -> body != null)
                .doOnError(e -> log.warn("Change stream error on {}: {}", collection, e.getMessage()));
    }

    public <T> Flux<T> watchBySessionId(String collection, Class<T> type, String sessionId) {
        return watchByField(collection, type, "sessionId", sessionId);
    }
}
