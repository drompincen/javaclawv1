package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.runtime.agent.llm.LlmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmService llmService;

    public ConfigController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/keys")
    public ResponseEntity<Map<String, String>> setKeys(@RequestBody Map<String, String> keys) {
        if (keys.containsKey("anthropicKey") && !keys.get("anthropicKey").isBlank()) {
            System.setProperty("spring.ai.anthropic.api-key", keys.get("anthropicKey"));
        }
        if (keys.containsKey("openaiKey") && !keys.get("openaiKey").isBlank()) {
            System.setProperty("spring.ai.openai.api-key", keys.get("openaiKey"));
        }
        return ResponseEntity.ok(Map.of("status", "keys_updated"));
    }

    @GetMapping("/keys")
    public ResponseEntity<Map<String, String>> getKeys() {
        String anthropic = System.getProperty("spring.ai.anthropic.api-key", "");
        String openai = System.getProperty("spring.ai.openai.api-key", "");
        return ResponseEntity.ok(Map.of(
                "anthropicKey", mask(anthropic),
                "openaiKey", mask(openai)
        ));
    }

    @PostMapping("/font-size")
    public ResponseEntity<Map<String, Integer>> setFontSize(@RequestBody Map<String, Integer> body) {
        int size = body.getOrDefault("fontSize", 15);
        size = Math.max(10, Math.min(24, size));
        System.setProperty("javaclaw.font.size", String.valueOf(size));
        return ResponseEntity.ok(Map.of("fontSize", size));
    }

    @GetMapping("/font-size")
    public ResponseEntity<Map<String, Integer>> getFontSize() {
        int size = Integer.parseInt(System.getProperty("javaclaw.font.size", "15"));
        return ResponseEntity.ok(Map.of("fontSize", size));
    }

    @GetMapping("/provider")
    public ResponseEntity<Map<String, String>> getProvider() {
        return ResponseEntity.ok(Map.of("provider", llmService.getProviderInfo()));
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) return key == null ? "" : "***";
        return key.substring(0, 7) + "..." + key.substring(key.length() - 4);
    }
}
