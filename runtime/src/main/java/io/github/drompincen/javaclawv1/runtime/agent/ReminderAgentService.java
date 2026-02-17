package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.BiFunction;

@Service
public class ReminderAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReminderAgentService.class);

    private final ReminderRepository reminderRepo;

    public ReminderAgentService(ReminderRepository reminderRepo) {
        this.reminderRepo = reminderRepo;
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
                    ReminderDocument doc = new ReminderDocument();
                    doc.setReminderId(UUID.randomUUID().toString());
                    doc.setSessionId(sessionId);

                    // Parse "REMINDER: what | WHEN: time | RECURRING: yes/no interval"
                    String[] parts = line.split("\\|");
                    doc.setMessage(parts[0].replaceAll("(?i)^\\s*REMINDER:\\s*", "").trim());
                    doc.setTriggerAt(null); // cannot parse natural-language times here
                    doc.setRecurring(false);

                    for (String part : parts) {
                        part = part.trim();
                        if (part.toUpperCase().startsWith("WHEN:")) {
                            // triggerAt stays null; we log the raw value below
                        } else if (part.toUpperCase().startsWith("RECURRING:")) {
                            String val = part.substring(10).trim().toLowerCase();
                            doc.setRecurring(val.startsWith("yes"));
                            if (val.contains("daily")) {
                                doc.setIntervalSeconds(86_400L);
                            } else if (val.contains("weekly")) {
                                doc.setIntervalSeconds(604_800L);
                            } else if (val.contains("hourly")) {
                                doc.setIntervalSeconds(3_600L);
                            }
                        }
                    }

                    if (doc.getMessage() != null && !doc.getMessage().isBlank()) {
                        reminderRepo.save(doc);
                        savedCount++;

                        String whenRaw = extractWhenPart(parts);
                        log.info("[Reminder] created id={} msg={} when={}",
                                doc.getReminderId().substring(0, 8),
                                truncate(doc.getMessage(), 50),
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
        ReminderDocument doc = new ReminderDocument();
        doc.setReminderId(UUID.randomUUID().toString());
        doc.setSessionId(sessionId);
        doc.setMessage(userMessage);
        doc.setTriggerAt(null);
        doc.setRecurring(false);
        reminderRepo.save(doc);

        log.info("[Reminder] created (mock) id={}", doc.getReminderId().substring(0, 8));

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

    /**
     * Extracts the raw WHEN: value from the parsed parts array, or "unspecified" if absent.
     */
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
