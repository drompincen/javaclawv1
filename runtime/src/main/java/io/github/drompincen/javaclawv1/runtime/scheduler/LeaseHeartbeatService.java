package io.github.drompincen.javaclawv1.runtime.scheduler;

import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class LeaseHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeatService.class);
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long LEASE_DURATION_MS = 90_000;

    private final FutureExecutionRepository futureExecutionRepository;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lease-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();

    public LeaseHeartbeatService(FutureExecutionRepository futureExecutionRepository) {
        this.futureExecutionRepository = futureExecutionRepository;
    }

    public void startHeartbeat(String executionId) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                futureExecutionRepository.findById(executionId).ifPresent(exec -> {
                    exec.setLeaseUntil(Instant.now().plusMillis(LEASE_DURATION_MS));
                    exec.setLastUpdatedAt(Instant.now());
                    futureExecutionRepository.save(exec);
                });
            } catch (Exception e) {
                log.warn("Heartbeat failed for execution {}: {}", executionId, e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        activeHeartbeats.put(executionId, future);
        log.debug("Started heartbeat for execution {}", executionId);
    }

    public void stopHeartbeat(String executionId) {
        ScheduledFuture<?> future = activeHeartbeats.remove(executionId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped heartbeat for execution {}", executionId);
        }
    }

    public void shutdown() {
        activeHeartbeats.values().forEach(f -> f.cancel(false));
        activeHeartbeats.clear();
        scheduler.shutdown();
    }
}
