package io.github.drompincen.javaclawv1.protocol.api;

public record ModelConfig(
        String modelName,
        double temperature,
        int maxTokens,
        String systemPrompt
) {
    public static ModelConfig defaults() {
        return new ModelConfig("claude-sonnet-4-5-20250929", 0.7, 4096, null);
    }
}
