package io.github.drompincen.javaclawv1.runtime.reminder;

import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReminderRepository;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
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

    private final ReminderRepository reminderRepository;
    private final EventService eventService;

    public ReminderScheduler(ReminderRepository reminderRepository, EventService eventService) {
        this.reminderRepository = reminderRepository;
        this.eventService = eventService;
    }

    @Scheduled(fixedDelay = 60000)
    public void checkReminders() {
        List<ReminderDocument> dueReminders =
                reminderRepository.findByTriggeredFalseAndTriggerAtBefore(Instant.now());

        for (ReminderDocument reminder : dueReminders) {
            try {
                eventService.emit(reminder.getProjectId(), EventType.REMINDER_TRIGGERED,
                        Map.of("reminderId", reminder.getReminderId(),
                                "message", reminder.getMessage(),
                                "type", reminder.getType().name()));

                if (reminder.isRecurring() && reminder.getIntervalSeconds() != null) {
                    // Re-arm recurring reminder for next occurrence
                    Instant nextTrigger = Instant.now().plusSeconds(reminder.getIntervalSeconds());
                    reminder.setTriggerAt(nextTrigger);
                    reminder.setTriggered(false);
                } else {
                    reminder.setTriggered(true);
                }
                reminderRepository.save(reminder);

                log.info("Triggered reminder {} for project {}", reminder.getReminderId(), reminder.getProjectId());
            } catch (Exception e) {
                log.error("Failed to trigger reminder {}", reminder.getReminderId(), e);
            }
        }
    }
}
