package io.github.drompincen.javaclawv1.protocol.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyTest {

    @Test
    void allowAllReturnsPermissivePolicy() {
        ToolPolicy policy = ToolPolicy.allowAll();

        assertThat(policy.allowList()).containsExactly("*");
        assertThat(policy.denyList()).isEmpty();
        assertThat(policy.approvalOverrides()).isEmpty();
    }

    @Test
    void customPolicyPreservesValues() {
        ToolPolicy policy = new ToolPolicy(
                List.of("read_file", "list_directory"),
                List.of("shell_exec"),
                Map.of("git_commit", ToolPolicy.ApprovalMode.REQUIRE_APPROVAL));

        assertThat(policy.allowList()).containsExactly("read_file", "list_directory");
        assertThat(policy.denyList()).containsExactly("shell_exec");
        assertThat(policy.approvalOverrides()).containsEntry("git_commit", ToolPolicy.ApprovalMode.REQUIRE_APPROVAL);
    }

    @Test
    void approvalModeEnumValues() {
        assertThat(ToolPolicy.ApprovalMode.values()).containsExactly(
                ToolPolicy.ApprovalMode.AUTO_APPROVE,
                ToolPolicy.ApprovalMode.REQUIRE_APPROVAL,
                ToolPolicy.ApprovalMode.DENY);
    }
}
