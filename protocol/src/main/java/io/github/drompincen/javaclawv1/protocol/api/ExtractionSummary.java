package io.github.drompincen.javaclawv1.protocol.api;

public record ExtractionSummary(
        int remindersFound,
        int checklistsFound,
        int ticketsFound,
        int threadsProcessed
) {}
