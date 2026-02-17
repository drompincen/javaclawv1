package io.github.drompincen.javaclawv1.protocol.api;

public record CreateThreadRequest(String title, ModelConfig modelConfig, ToolPolicy toolPolicy) {}
