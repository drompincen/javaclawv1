package io.github.drompincen.javaclawv1.persistence.stream;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;

public interface EventStreamListener {
    void onEvent(EventDocument event);
    default void onError(Throwable t) {}
}
