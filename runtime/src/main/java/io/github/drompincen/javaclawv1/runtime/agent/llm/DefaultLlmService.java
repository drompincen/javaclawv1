package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Supplier;

/**
 * Default LLM service that checks both Anthropic and OpenAI keys.
 * Uses whichever provider has a real (non-placeholder) key configured.
 * Anthropic is checked first, then OpenAI.
 * If a key is set at runtime (--api-key or Ctrl+K) but the Spring AI
 * autoconfiguration didn't create the model bean, models are created lazily.
 */
@Service
@ConditionalOnProperty(name = "javaclaw.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class DefaultLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String ONBOARDING_MESSAGE = """
            **Welcome to JavaClaw!** No API key is configured yet.

            **Quick start:**
            - Press **Ctrl+K** to set your API key (Anthropic or OpenAI)
            - Type `use project <name>` to select a project
            - Type `whereami` to see your current context

            **Supported providers:**
            - **Anthropic** (Claude): https://console.anthropic.com/settings/keys
            - **OpenAI** (GPT-4o): https://platform.openai.com/api-keys

            Set either key and JavaClaw will use it automatically.""";

    private final AnthropicChatModel anthropicModel;
    private final OpenAiChatModel openaiModel;
    private final Environment environment;

    // Lazily-created models for keys set at runtime (--api-key, Ctrl+K)
    private volatile ChatModel lazyOpenAiModel;
    private volatile ChatModel lazyAnthropicModel;

    public DefaultLlmService(@Autowired(required = false) AnthropicChatModel anthropicModel,
                             @Autowired(required = false) OpenAiChatModel openaiModel,
                             Environment environment) {
        this.anthropicModel = anthropicModel;
        this.openaiModel = openaiModel;
        this.environment = environment;
        log.info("DefaultLlmService initialized — anthropic={}, openai={}",
                anthropicModel != null ? "available" : "missing",
                openaiModel != null ? "available" : "missing");
    }

    private enum Provider { ANTHROPIC, OPENAI, NONE }

    private boolean hasRealKey(String key, String placeholderPrefix) {
        return key != null && !key.isBlank() && !key.startsWith(placeholderPrefix);
    }

    /**
     * Resolve key from multiple sources: System.setProperty (Ctrl+K runtime),
     * then Spring Environment (env vars, YAML defaults, JVM -D args).
     */
    private String resolveKey(String propertyName) {
        String key = System.getProperty(propertyName);
        if (key != null && !key.isBlank()) return key;
        return environment.getProperty(propertyName, "");
    }

    private Provider resolveProvider() {
        String anthropicKey = resolveKey("spring.ai.anthropic.api-key");
        if (hasRealKey(anthropicKey, "sk-ant-placeholder")) {
            return Provider.ANTHROPIC;
        }
        String openaiKey = resolveKey("spring.ai.openai.api-key");
        if (hasRealKey(openaiKey, "sk-placeholder")) {
            return Provider.OPENAI;
        }
        return Provider.NONE;
    }

    @Override
    public boolean isAvailable() {
        return resolveProvider() != Provider.NONE;
    }

    private ChatModel getActiveModel() {
        Provider p = resolveProvider();
        ChatModel model = switch (p) {
            case ANTHROPIC -> getOrCreateAnthropicModel();
            case OPENAI -> getOrCreateOpenAiModel();
            case NONE -> null;
        };
        if (model == null && p != Provider.NONE) {
            log.warn("Provider {} selected but could not create model", p);
        }
        return model;
    }

    private ChatModel getOrCreateOpenAiModel() {
        // If key was set at runtime, prefer lazy model over Spring bean (may have placeholder key)
        String runtimeKey = System.getProperty("spring.ai.openai.api-key");
        if (hasRealKey(runtimeKey, "sk-placeholder")) {
            if (lazyOpenAiModel != null) return lazyOpenAiModel;
            try {
                OpenAiApi api = new OpenAiApi(runtimeKey);
                OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-4o").build();
                lazyOpenAiModel = new OpenAiChatModel(api, options);
                log.info("Created OpenAI model (lazy) — key set via --api-key or Ctrl+K");
                return lazyOpenAiModel;
            } catch (Exception e) {
                log.error("Failed to create OpenAI model: {}", e.getMessage());
            }
        }
        if (openaiModel != null) return openaiModel;
        return null;
    }

    private ChatModel getOrCreateAnthropicModel() {
        // If key was set at runtime (--api-key, Ctrl+K, env bridge), prefer lazy model
        // because the Spring-injected bean may have been created with a placeholder key
        String runtimeKey = System.getProperty("spring.ai.anthropic.api-key");
        if (hasRealKey(runtimeKey, "sk-ant-placeholder")) {
            if (lazyAnthropicModel != null) return lazyAnthropicModel;
            try {
                AnthropicApi api = new AnthropicApi(runtimeKey);
                AnthropicChatOptions options = AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5-20250929")
                        .maxTokens(8192).build();
                lazyAnthropicModel = new AnthropicChatModel(api, options);
                log.info("Created Anthropic model (lazy) — key set via --api-key or Ctrl+K");
                return lazyAnthropicModel;
            } catch (Exception e) {
                log.error("Failed to create Anthropic model: {}", e.getMessage());
            }
        }
        if (anthropicModel != null) return anthropicModel;
        return null;
    }

    @Override
    public String getProviderInfo() {
        Provider p = resolveProvider();
        return switch (p) {
            case ANTHROPIC -> "Claude Sonnet";
            case OPENAI -> "GPT-4o";
            case NONE -> "No API Key";
        };
    }

    @Override
    public Flux<String> streamResponse(AgentState state) {
        ChatModel model = getActiveModel();
        if (model == null) {
            return Flux.just(ONBOARDING_MESSAGE);
        }
        Prompt prompt = buildPrompt(state);
        log.debug("Streaming response via {}", resolveProvider());

        return model.stream(prompt)
                .map(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                })
                .filter(text -> !text.isEmpty())
                .onErrorResume(e -> {
                    if (isRetryableError(e)) {
                        log.warn("Stream hit retryable error, retrying via collectStream: {}", e.getMessage());
                        try {
                            String text = collectStream(model, prompt);
                            return Flux.just(text != null ? text : "");
                        } catch (Exception ex) {
                            return Flux.error(ex);
                        }
                    }
                    return Flux.error(e);
                });
    }

    @Override
    public String blockingResponse(AgentState state) {
        ChatModel model = getActiveModel();
        if (model == null) {
            return ONBOARDING_MESSAGE;
        }
        log.debug("Blocking response via {}", resolveProvider());
        Prompt prompt = buildPrompt(state);
        // Use streaming and collect — model.call() fails with Spring AI 1.0.0-M6
        // due to deserialization mismatch with current Anthropic API response format
        return collectStream(model, prompt);
    }

    /** Stream the response and collect all chunks into a single string, with retry. */
    private String collectStream(ChatModel model, Prompt prompt) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                StringBuilder sb = new StringBuilder();
                model.stream(prompt)
                        .map(response -> {
                            if (response.getResult() != null && response.getResult().getOutput() != null) {
                                String text = response.getResult().getOutput().getText();
                                return text != null ? text : "";
                            }
                            return "";
                        })
                        .filter(text -> !text.isEmpty())
                        .doOnNext(sb::append)
                        .blockLast();
                return sb.toString();
            } catch (Exception e) {
                if (isRetryableError(e) && attempt < MAX_RETRIES - 1) {
                    long delay = BACKOFF_MS[attempt];
                    log.warn("Retryable error (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Exhausted retries");
    }

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {2_000, 4_000, 8_000};

    /** Call supplier with exponential backoff on retryable errors (429, 503, 529). */
    private <T> T callWithRetry(Supplier<T> supplier) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return supplier.get();
            } catch (Exception e) {
                if (isRetryableError(e) && attempt < MAX_RETRIES - 1) {
                    long delay = BACKOFF_MS[attempt];
                    log.warn("Retryable error (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Exhausted retries"); // unreachable
    }

    /** Check if error is retryable by walking cause chain for 429/503/529/rate-limit signals. */
    private boolean isRetryableError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("429") || lower.contains("500") || lower.contains("503") || lower.contains("529")
                        || lower.contains("rate limit") || lower.contains("rate_limit")
                        || lower.contains("overloaded") || lower.contains("too many requests")
                        || lower.contains("internal server error")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private Prompt buildPrompt(AgentState state) {
        List<Message> messages = new ArrayList<>();
        for (Map<String, String> msg : state.getMessages()) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            String partsJson = msg.get("parts");

            switch (role) {
                case "system" -> messages.add(new SystemMessage(content));
                case "assistant" -> messages.add(new AssistantMessage(content));
                default -> {
                    if (partsJson != null && !partsJson.isBlank()) {
                        messages.add(buildMultimodalUserMessage(content, partsJson));
                    } else {
                        messages.add(new UserMessage(content));
                    }
                }
            }
        }
        return new Prompt(messages);
    }

    private UserMessage buildMultimodalUserMessage(String fallbackContent, String partsJson) {
        try {
            List<Map<String, String>> parts = OBJECT_MAPPER.readValue(partsJson,
                    new TypeReference<List<Map<String, String>>>() {});

            StringBuilder textBuilder = new StringBuilder();
            List<Media> mediaList = new ArrayList<>();

            for (Map<String, String> part : parts) {
                String type = part.getOrDefault("type", "text");
                if ("image".equals(type)) {
                    String mediaType = part.getOrDefault("mediaType", "image/png");
                    String data = part.get("data");
                    if (data != null) {
                        byte[] imageBytes = Base64.getDecoder().decode(data);
                        Media media = Media.builder()
                                .mimeType(MimeType.valueOf(mediaType))
                                .data(imageBytes)
                                .build();
                        mediaList.add(media);
                    }
                } else {
                    String text = part.getOrDefault("text", "");
                    if (!text.isEmpty()) {
                        if (!textBuilder.isEmpty()) textBuilder.append("\n");
                        textBuilder.append(text);
                    }
                }
            }

            String text = textBuilder.isEmpty() ? (fallbackContent != null ? fallbackContent : "") : textBuilder.toString();

            if (!mediaList.isEmpty()) {
                return new UserMessage(text, mediaList);
            } else {
                return new UserMessage(text);
            }
        } catch (Exception e) {
            log.warn("Failed to parse multimodal parts, falling back to text content", e);
            return new UserMessage(fallbackContent != null ? fallbackContent : "");
        }
    }
}
