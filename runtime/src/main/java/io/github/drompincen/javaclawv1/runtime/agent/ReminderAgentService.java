package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Service
public class ReminderAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReminderAgentService.class);

    private final ThingService thingService;

    public ReminderAgentService(ThingService thingService) {
        this.thingService = thingService;
    }

    /**
     * Main entry point for the reminder agent.
     *
     * @param userMessage the user's message describing the reminder
     * @param sessionId   the current session identifier
     * @param llmCaller   a function taking (agentName, userMessage) and returning
     *                    the LLM response, or {@code null} if no LLM is available
     * @return the LLM response if available, otherwise a mock fallback message
     */
    public String executeReminder(String userMessage, String sessionId,
                                  BiFunction<String, String, String> llmCaller) {

        String response = llmCaller.apply("reminder", userMessage);

        if (response != null) {
            // Parse REMINDER lines from the LLM response and save to DB
            int savedCount = 0;
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("REMINDER:") || line.contains("| WHEN:")) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("sessionId", sessionId);
                    payload.put("triggered", false);
                    payload.put("recurring", false);

                    // Parse "REMINDER: what | WHEN: time | RECURRING: yes/no interval"
                    String[] parts = line.split("\\|");
                    String message = parts[0].replaceAll("(?i)^\\s*REMINDER:\\s*", "").trim();
                    payload.put("message", message);

                    for (String part : parts) {
                        part = part.trim();
                        if (part.toUpperCase().startsWith("RECURRING:")) {
                            String val = part.substring(10).trim().toLowerCase();
                            payload.put("recurring", val.startsWith("yes"));
                            if (val.contains("daily")) {
                                payload.put("intervalSeconds", 86_400L);
                            } else if (val.contains("weekly")) {
                                payload.put("intervalSeconds", 604_800L);
                            } else if (val.contains("hourly")) {
                                payload.put("intervalSeconds", 3_600L);
                            }
                        }
                    }

                    if (message != null && !message.isBlank()) {
                        ThingDocument thing = thingService.createThing(null, ThingCategory.REMINDER, payload);
                        savedCount++;

                        String whenRaw = extractWhenPart(parts);
                        log.info("[Reminder] created id={} msg={} when={}",
                                thing.getId().substring(0, 8),
                                truncate(message, 50),
                                whenRaw);
                    }
                }
            }
            if (savedCount > 0) {
                log.info("[Reminder] saved {} reminders from LLM response", savedCount);
            }
            return response;
        }

        // Fallback: basic reminder without LLM
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("message", userMessage);
        payload.put("triggered", false);
        payload.put("recurring", false);
        ThingDocument thing = thingService.createThing(null, ThingCategory.REMINDER, payload);

        log.info("[Reminder] created (mock) id={}", thing.getId().substring(0, 8));

        return "Reminder saved: **" + truncate(userMessage, 200) + "**\n\n"
                + "I've stored this reminder but can't parse specific timing without an LLM. "
                + "Once an API key is configured, I'll be able to understand natural language scheduling."
                + "\n\n---\n*This is a **basic reminder** — no LLM API key is configured. "
                + "Press **Ctrl+K** to add your API key for smarter scheduling.*";
    }

    // ---- private helpers ----

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String extractWhenPart(String[] parts) {
        for (String part : parts) {
            part = part.trim();
            if (part.toUpperCase().startsWith("WHEN:")) {
                return part.substring(5).trim();
            }
        }
        return "unspecified";
    }
}
