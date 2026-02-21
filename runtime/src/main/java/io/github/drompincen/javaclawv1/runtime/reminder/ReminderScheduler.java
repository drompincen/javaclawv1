package io.github.drompincen.javaclawv1.runtime.reminder;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@EnableScheduling
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ThingService thingService;
    private final EventService eventService;

    public ReminderScheduler(ThingService thingService, EventService eventService) {
        this.thingService = thingService;
        this.eventService = eventService;
    }

    @Scheduled(fixedDelay = 60000)
    public void checkReminders() {
        List<ThingDocument> dueReminders = thingService.findDueReminders(Instant.now());

        for (ThingDocument reminder : dueReminders) {
            try {
                Map<String, Object> p = reminder.getPayload();
                String message = (String) p.get("message");
                String type = p.get("type") != null ? p.get("type").toString() : "TIME_BASED";

                eventService.emit(reminder.getProjectId(), EventType.REMINDER_TRIGGERED,
                        Map.of("reminderId", reminder.getId(),
                                "message", message != null ? message : "",
                                "type", type));

                boolean recurring = Boolean.TRUE.equals(p.get("recurring"));
                if (recurring && p.get("intervalSeconds") != null) {
                    long interval = ((Number) p.get("intervalSeconds")).longValue();
                    Instant nextTrigger = Instant.now().plusSeconds(interval);
                    thingService.mergePayload(reminder, Map.of(
                            "triggerAt", nextTrigger.toString(),
                            "triggered", false));
                } else {
                    thingService.mergePayload(reminder, Map.of("triggered", true));
                }

                log.info("Triggered reminder {} for project {}", reminder.getId(), reminder.getProjectId());
            } catch (Exception e) {
                log.error("Failed to trigger reminder {}", reminder.getId(), e);
            }
        }
    }
}
