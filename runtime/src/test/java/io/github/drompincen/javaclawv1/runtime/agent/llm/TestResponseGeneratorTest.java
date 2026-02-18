package io.github.drompincen.javaclawv1.runtime.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestResponseGeneratorTest {

    @Test
    void controllerDelegatesToCoderForCodeQueries() {
        var messages = List.of(userMsg("read the pom.xml file"));
        String response = TestResponseGenerator.generateControllerResponse("read the pom.xml file", messages);
        assertThat(response).contains("\"delegate\": \"coder\"");
    }

    @Test
    void controllerDelegatesToPmForSprintQueries() {
        var messages = List.of(userMsg("show me the sprint backlog"));
        String response = TestResponseGenerator.generateControllerResponse("show me the sprint backlog", messages);
        assertThat(response).contains("\"delegate\": \"pm\"");
    }

    @Test
    void controllerDelegatesToReminderForRemindQueries() {
        var messages = List.of(userMsg("remind me to check PR tomorrow"));
        String response = TestResponseGenerator.generateControllerResponse("remind me to check PR tomorrow", messages);
        assertThat(response).contains("\"delegate\": \"reminder\"");
    }

    @Test
    void controllerDelegatesToGeneralistForGenericQueries() {
        var messages = List.of(userMsg("hello how are you"));
        String response = TestResponseGenerator.generateControllerResponse("hello how are you", messages);
        assertThat(response).contains("\"delegate\": \"generalist\"");
    }

    @Test
    void reviewerPassesWithToolResults() {
        var messages = List.of(
                userMsg("list files"),
                Map.of("role", "tool", "name", "list_dir", "content", "file1.java\nfile2.java"),
                Map.of("role", "assistant", "content", "Found 2 files")
        );
        String response = TestResponseGenerator.generateReviewerResponse(messages, true);
        assertThat(response).contains("\"pass\": true");
    }

    @Test
    void reviewerFailsOnError() {
        var messages = List.of(
                userMsg("run build"),
                Map.of("role", "assistant", "content", "[ERROR] Build failed")
        );
        String response = TestResponseGenerator.generateReviewerResponse(messages, false);
        assertThat(response).contains("\"pass\": false");
    }

    @Test
    void specialistGeneratesToolCallForListDirectory() {
        String response = TestResponseGenerator.generateSpecialistResponse("coder", "list files in /src", false);
        assertThat(response).contains("list_directory");
        assertThat(response).contains("/src");
    }

    @Test
    void specialistGeneratesToolCallForReadFile() {
        String response = TestResponseGenerator.generateSpecialistResponse("coder", "read the pom.xml file", false);
        assertThat(response).contains("read_file");
        assertThat(response).contains("pom.xml");
    }

    @Test
    void specialistGeneratesToolCallForSearch() {
        String response = TestResponseGenerator.generateSpecialistResponse("coder", "search for java files", false);
        assertThat(response).contains("search_files");
    }

    @Test
    void specialistReportsCompletionWithToolResults() {
        String response = TestResponseGenerator.generateSpecialistResponse("coder", "list files", true);
        assertThat(response).contains("[TEST]");
        assertThat(response).contains("completed");
    }

    @Test
    void reminderResponseExtractsDailyRecurrence() {
        String response = TestResponseGenerator.generateReminderResponse("remind me daily to check standup notes");
        assertThat(response).contains("RECURRING: yes daily");
    }

    @Test
    void getLastUserMessageFindsCorrectMessage() {
        var messages = List.of(
                userMsg("first message"),
                Map.of("role", "assistant", "content", "response"),
                userMsg("second message")
        );
        String last = TestResponseGenerator.getLastUserMessage(messages);
        assertThat(last).isEqualTo("second message");
    }

    @Test
    void getLastUserMessageReturnsEmptyForNoUserMessages() {
        var messages = List.of(Map.of("role", "assistant", "content", "response"));
        String last = TestResponseGenerator.getLastUserMessage(messages);
        assertThat(last).isEmpty();
    }

    @Test
    void escapeJsonHandlesSpecialCharacters() {
        assertThat(TestResponseGenerator.escapeJson("hello \"world\"")).isEqualTo("hello \\\"world\\\"");
        assertThat(TestResponseGenerator.escapeJson("line1\nline2")).isEqualTo("line1\\nline2");
        assertThat(TestResponseGenerator.escapeJson("path\\to\\file")).isEqualTo("path\\\\to\\\\file");
    }

    @Test
    void truncateRespectsMaxLength() {
        assertThat(TestResponseGenerator.truncate("short", 100)).isEqualTo("short");
        assertThat(TestResponseGenerator.truncate("a".repeat(200), 10)).hasSize(13); // 10 + "..."
        assertThat(TestResponseGenerator.truncate(null, 10)).isEmpty();
    }

    @Test
    void extractPathFindsUnixPaths() {
        assertThat(TestResponseGenerator.extractPath("read /src/main/java/App.java")).isEqualTo("/src/main/java/App.java");
    }

    @Test
    void extractPathFindsFilenames() {
        assertThat(TestResponseGenerator.extractPath("read pom.xml")).isEqualTo("pom.xml");
    }

    private static Map<String, String> userMsg(String content) {
        return Map.of("role", "user", "content", content);
    }
}
