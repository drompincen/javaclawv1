package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.*;

@Service
@ConditionalOnProperty(name = "javaclaw.llm.provider", havingValue = "openai")
public class OpenAiLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenAiChatModel chatModel;

    public OpenAiLlmService(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Flux<String> streamResponse(AgentState state) {
        Prompt prompt = buildPrompt(state);
        return chatModel.stream(prompt)
                .map(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                })
                .filter(text -> !text.isEmpty());
    }

    @Override
    public String blockingResponse(AgentState state) {
        Prompt prompt = buildPrompt(state);
        var response = chatModel.call(prompt);
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
