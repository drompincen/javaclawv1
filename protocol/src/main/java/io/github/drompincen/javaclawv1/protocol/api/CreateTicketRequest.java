package io.github.drompincen.javaclawv1.protocol.api;

public record CreateTicketRequest(String title, String description, TicketDto.TicketPriority priority, String owner, Integer storyPoints) {}
