package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;
import java.util.Map;

public record ToolPolicy(
        List<String> allowList,
        List<String> denyList,
        Map<String, ApprovalMode> approvalOverrides
) {
    public enum ApprovalMode {
        AUTO_APPROVE,
        REQUIRE_APPROVAL,
        DENY
    }

    public static ToolPolicy allowAll() {
        return new ToolPolicy(List.of("*"), List.of(), Map.of());
    }
}
