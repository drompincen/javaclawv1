package io.github.drompincen.javaclawv1.runtime.tools;

import java.nio.file.Path;
import java.util.Map;

public record ToolContext(
        String sessionId,
        Path workingDirectory,
        Map<String, String> environment
) {}
