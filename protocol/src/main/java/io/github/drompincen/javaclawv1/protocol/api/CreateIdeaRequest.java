package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;

public record CreateIdeaRequest(String title, String content, List<String> tags) {}
