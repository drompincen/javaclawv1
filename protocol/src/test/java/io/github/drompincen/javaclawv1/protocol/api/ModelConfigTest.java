package io.github.drompincen.javaclawv1.protocol.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigTest {

    @Test
    void defaultsReturnsExpectedValues() {
        ModelConfig config = ModelConfig.defaults();

        assertThat(config.modelName()).isEqualTo("claude-sonnet-4-5-20250929");
        assertThat(config.temperature()).isEqualTo(0.7);
        assertThat(config.maxTokens()).isEqualTo(4096);
        assertThat(config.systemPrompt()).isNull();
    }

    @Test
    void customConfigPreservesValues() {
        ModelConfig config = new ModelConfig("gpt-4o", 0.5, 2048, "You are a helpful assistant.");

        assertThat(config.modelName()).isEqualTo("gpt-4o");
        assertThat(config.temperature()).isEqualTo(0.5);
        assertThat(config.maxTokens()).isEqualTo(2048);
        assertThat(config.systemPrompt()).isEqualTo("You are a helpful assistant.");
    }
}
