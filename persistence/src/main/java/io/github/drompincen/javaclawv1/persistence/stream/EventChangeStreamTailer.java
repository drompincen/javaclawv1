package io.github.drompincen.javaclawv1.persistence.stream;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.FullDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class EventChangeStreamTailer {

    private static final Logger log = LoggerFactory.getLogger(EventChangeStreamTailer.class);

    private final MongoTemplate mongoTemplate;
    private final List<EventStreamListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-stream-tailer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;

    public EventChangeStreamTailer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void addListener(EventStreamListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EventStreamListener listener) {
        listeners.remove(listener);
    }

    @PostConstruct
    public void start() {
        running = true;
        executor.submit(this::tailChangeStream);
        log.info("Event change stream tailer started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
        log.info("Event change stream tailer stopped");
    }

    private void tailChangeStream() {
        while (running) {
            try {
                doTail();
            } catch (Exception e) {
                if (running) {
                    log.warn("Change stream interrupted, falling back to polling: {}", e.getMessage());
                    pollFallback();
                }
            }
        }
    }

    private void doTail() {
        var collection = mongoTemplate.getDb().getCollection("events");
        var stream = collection.watch(
                List.of(Aggregates.match(Filters.eq("operationType", "insert")))
        ).fullDocument(FullDocument.UPDATE_LOOKUP);

        try (var cursor = stream.iterator()) {
            while (running && cursor.hasNext()) {
                var change = cursor.next();
                Document fullDoc = change.getFullDocument();
                if (fullDoc != null) {
                    EventDocument event = mongoTemplate.getConverter().read(EventDocument.class, fullDoc);
                    notifyListeners(event);
                }
            }
        }
    }

    private void pollFallback() {
        log.info("Using polling fallback for event streaming");
        long lastSeq = -1;
        while (running) {
            try {
                var events = mongoTemplate.find(
                        Query.query(Criteria.where("seq").gt(lastSeq))
                                .with(Sort.by("seq")),
                        EventDocument.class);
                for (EventDocument event : events) {
                    notifyListeners(event);
                    lastSeq = event.getSeq();
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Polling fallback error", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void notifyListeners(EventDocument event) {
        for (EventStreamListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Listener error for event {}", event.getEventId(), e);
                listener.onError(e);
            }
        }
    }
}
