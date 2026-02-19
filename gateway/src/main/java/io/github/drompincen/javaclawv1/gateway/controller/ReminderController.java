package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ReminderDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReminderRepository;
import io.github.drompincen.javaclawv1.protocol.api.CreateReminderRequest;
import io.github.drompincen.javaclawv1.protocol.api.ReminderDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class ReminderController {

    private final ReminderRepository reminderRepository;

    public ReminderController(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    @PostMapping("/api/projects/{projectId}/reminders")
    public ResponseEntity<ReminderDto> create(@PathVariable String projectId,
                                               @RequestBody CreateReminderRequest req) {
        ReminderDocument doc = new ReminderDocument();
        doc.setReminderId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setMessage(req.message());
        doc.setType(req.type() != null ? req.type() : ReminderDto.ReminderType.TIME_BASED);
        doc.setTriggerAt(req.triggerAt());
        doc.setTriggered(false);
        doc.setRecurring(req.recurring());
        doc.setIntervalSeconds(req.intervalSeconds());
        reminderRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping("/api/projects/{projectId}/reminders")
    public List<ReminderDto> listByProject(@PathVariable String projectId) {
        return reminderRepository.findByProjectId(projectId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/api/reminders")
    public List<ReminderDto> list(@RequestParam(required = false) String projectId) {
        List<ReminderDocument> docs;
        if (projectId != null) {
            docs = reminderRepository.findByProjectId(projectId);
        } else {
            docs = reminderRepository.findAll();
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (reminderRepository.existsById(id)) {
            reminderRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ReminderDto toDto(ReminderDocument doc) {
        return new ReminderDto(doc.getReminderId(), doc.getProjectId(), doc.getMessage(),
                doc.getType(), doc.getTriggerAt(), doc.getCondition(), doc.isTriggered(),
                doc.isRecurring(), doc.getIntervalSeconds(), doc.getSourceThreadId());
    }
}
