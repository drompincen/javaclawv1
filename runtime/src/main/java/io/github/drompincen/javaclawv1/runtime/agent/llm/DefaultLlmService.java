package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Default LLM service that checks both Anthropic and OpenAI keys.
 * Uses whichever provider has a real (non-placeholder) key configured.
 * Anthropic is checked first, then OpenAI.
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

    public DefaultLlmService(@Autowired(required = false) AnthropicChatModel anthropicModel,
                             @Autowired(required = false) OpenAiChatModel openaiModel) {
        this.anthropicModel = anthropicModel;
        this.openaiModel = openaiModel;
    }

    private enum Provider { ANTHROPIC, OPENAI, NONE }

    private Provider resolveProvider() {
        String anthropicKey = System.getProperty("spring.ai.anthropic.api-key", "");
        if (!anthropicKey.isBlank() && !anthropicKey.startsWith("sk-ant-placeholder")) {
            return Provider.ANTHROPIC;
        }
        String openaiKey = System.getProperty("spring.ai.openai.api-key", "");
        if (!openaiKey.isBlank() && !openaiKey.startsWith("sk-placeholder")) {
            return Provider.OPENAI;
        }
        return Provider.NONE;
    }

    private ChatModel getActiveModel() {
        return switch (resolveProvider()) {
            case ANTHROPIC -> anthropicModel;
            case OPENAI -> openaiModel;
            case NONE -> null;
        };
    }

    @Override
    public Flux<String> streamResponse(AgentState state) {
        ChatModel model = getActiveModel();
        if (model == null) {
            return Flux.just(ONBOARDING_MESSAGE);
        }
        Prompt prompt = buildPrompt(state);
        Provider provider = resolveProvider();
        log.debug("Streaming response via {}", provider);

        if (provider == Provider.ANTHROPIC) {
            return anthropicModel.stream(prompt)
                    .map(response -> {
                        if (response.getResult() != null && response.getResult().getOutput() != null) {
                            String text = response.getResult().getOutput().getText();
                            return text != null ? text : "";
                        }
                        return "";
                    })
                    .filter(text -> !text.isEmpty());
        } else {
            return openaiModel.stream(prompt)
                    .map(response -> {
                        if (response.getResult() != null && response.getResult().getOutput() != null) {
                            String text = response.getResult().getOutput().getText();
                            return text != null ? text : "";
                        }
                        return "";
                    })
                    .filter(text -> !text.isEmpty());
        }
    }

    @Override
    public String blockingResponse(AgentState state) {
        ChatModel model = getActiveModel();
        if (model == null) {
            return ONBOARDING_MESSAGE;
        }
        log.debug("Blocking response via {}", resolveProvider());
        Prompt prompt = buildPrompt(state);
        var response = model.call(prompt);
        return response.getResult().getOutput().getText();
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
