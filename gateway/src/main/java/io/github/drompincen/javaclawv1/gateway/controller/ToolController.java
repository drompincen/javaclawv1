package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.protocol.api.ToolDescriptor;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<ToolDescriptor> list() {
        return toolRegistry.descriptors();
    }

    @GetMapping("/{name}")
    public ResponseEntity<ToolDescriptor> describe(@PathVariable String name) {
        return toolRegistry.get(name)
                .map(t -> ResponseEntity.ok(new ToolDescriptor(
                        t.name(), t.description(), t.inputSchema(), t.outputSchema(), t.riskProfiles())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{name}/invoke")
    public ResponseEntity<?> invoke(@PathVariable String name, @RequestBody JsonNode input) {
        return toolRegistry.get(name).map(tool -> {
            ToolStream noOpStream = new ToolStream() {
                @Override public void stdoutDelta(String text) {}
                @Override public void stderrDelta(String text) {}
                @Override public void progress(int percent, String message) {}
                @Override public void artifactCreated(String type, String uriOrRef) {}
            };
            ToolContext ctx = new ToolContext("admin", Path.of("."), Map.of());
            ToolResult result = tool.execute(ctx, input, noOpStream);
            return ResponseEntity.ok(Map.of(
                    "success", result.success(),
                    "output", result.output() != null ? result.output() : "",
                    "error", result.error() != null ? result.error() : ""));
        }).orElse(ResponseEntity.notFound().build());
    }
}
