package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;

public record CreateProjectRequest(String name, String description, List<String> tags) {}
