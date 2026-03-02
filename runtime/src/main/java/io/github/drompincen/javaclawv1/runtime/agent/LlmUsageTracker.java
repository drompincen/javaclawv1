package io.github.drompincen.javaclawv1.runtime.agent;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory tracker for LLM usage since server start.
 * Provides cumulative counters by agent and by source (agent vs ask-claw).
 */
@Service
public class LlmUsageTracker {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final Instant startedAt = Instant.now();

    private final ConcurrentHashMap<String, AgentUsage> perAgent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SourceUsage> bySource = new ConcurrentHashMap<>();

    public void record(String agentId, String source, int promptTokens, int completionTokens,
                       long durationMs, boolean success) {
        totalCalls.incrementAndGet();
        totalPromptTokens.addAndGet(promptTokens);
        totalCompletionTokens.addAndGet(completionTokens);
        totalDurationMs.addAndGet(durationMs);
        if (!success) {
            totalErrors.incrementAndGet();
        }

        if (agentId != null) {
            perAgent.computeIfAbsent(agentId, k -> new AgentUsage()).record(promptTokens, completionTokens);
        }

        if (source != null) {
            bySource.computeIfAbsent(source, k -> new SourceUsage()).record(promptTokens, completionTokens);
        }
    }

    public void reset() {
        totalCalls.set(0);
        totalPromptTokens.set(0);
        totalCompletionTokens.set(0);
        totalDurationMs.set(0);
        totalErrors.set(0);
        perAgent.clear();
        bySource.clear();
    }

    public Map<String, Object> getSnapshot() {
        long calls = totalCalls.get();
        long promptTok = totalPromptTokens.get();
        long completionTok = totalCompletionTokens.get();
        long durationMs = totalDurationMs.get();
        long errors = totalErrors.get();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalCalls", calls);
        snapshot.put("totalPromptTokens", promptTok);
        snapshot.put("totalCompletionTokens", completionTok);
        snapshot.put("totalTokens", promptTok + completionTok);
        snapshot.put("totalDurationMs", durationMs);
        snapshot.put("totalErrors", errors);
        snapshot.put("avgPromptTokensPerCall", calls > 0 ? promptTok / calls : 0);
        snapshot.put("avgCompletionTokensPerCall", calls > 0 ? completionTok / calls : 0);
        snapshot.put("avgDurationMsPerCall", calls > 0 ? durationMs / calls : 0);
        snapshot.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
        snapshot.put("startedAt", startedAt.toString());

        Map<String, Map<String, Long>> agentMap = new LinkedHashMap<>();
        perAgent.forEach((id, usage) -> agentMap.put(id, usage.toMap()));
        snapshot.put("perAgent", agentMap);

        Map<String, Map<String, Long>> sourceMap = new LinkedHashMap<>();
        bySource.forEach((src, usage) -> sourceMap.put(src, usage.toMap()));
        snapshot.put("bySource", sourceMap);

        return snapshot;
    }

    static class AgentUsage {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();

        void record(int prompt, int completion) {
            calls.incrementAndGet();
            promptTokens.addAndGet(prompt);
            completionTokens.addAndGet(completion);
        }

        Map<String, Long> toMap() {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("calls", calls.get());
            m.put("promptTokens", promptTokens.get());
            m.put("completionTokens", completionTokens.get());
            return m;
        }
    }

    static class SourceUsage {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();

        void record(int prompt, int completion) {
            calls.incrementAndGet();
            promptTokens.addAndGet(prompt);
            completionTokens.addAndGet(completion);
        }

        Map<String, Long> toMap() {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("calls", calls.get());
            m.put("promptTokens", promptTokens.get());
            m.put("completionTokens", completionTokens.get());
            return m;
        }
    }
}
