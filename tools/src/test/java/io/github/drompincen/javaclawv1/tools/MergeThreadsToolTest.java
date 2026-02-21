package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.ThreadLifecycle;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MergeThreadsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private ThreadRepository threadRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ToolStream stream;

    private MergeThreadsTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new MergeThreadsTool();
        tool.setThreadRepository(threadRepository);
        tool.setMessageRepository(messageRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
        when(threadRepository.save(any(ThreadDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(MessageDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void toolMetadata() {
        assertThat(tool.name()).isEqualTo("merge_threads");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.AGENT_INTERNAL);
        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.outputSchema()).isNotNull();
    }

    @Test
    void failsWithoutRepositories() {
        MergeThreadsTool unwired = new MergeThreadsTool();
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available");
    }

    @Test
    void failsWithLessThanTwoIds() {
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("at least 2");
    }

    @Test
    void failsWithMissingIds() {
        ObjectNode input = MAPPER.createObjectNode();
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("at least 2");
    }

    @Test
    void failsWhenThreadNotFound() {
        when(threadRepository.findById("t1")).thenReturn(Optional.of(makeThread("t1", "Thread 1")));
        when(threadRepository.findById("t2")).thenReturn(Optional.empty());

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void mergesTwoThreads() {
        ThreadDocument t1 = makeThread("t1", "Thread 1");
        ThreadDocument t2 = makeThread("t2", "Thread 2");
        when(threadRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(threadRepository.findById("t2")).thenReturn(Optional.of(t2));

        MessageDocument m1 = makeMessage("m1", "t1", 1, Instant.parse("2026-01-01T10:00:00Z"));
        MessageDocument m2 = makeMessage("m2", "t2", 1, Instant.parse("2026-01-01T09:00:00Z"));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t1")).thenReturn(new ArrayList<>(List.of(m1)));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t2")).thenReturn(new ArrayList<>(List.of(m2)));

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("mergedThreadId").asText()).isEqualTo("t1");
        assertThat(result.output().get("sourceCount").asInt()).isEqualTo(2);

        // Verify source thread marked as MERGED
        assertThat(t2.getLifecycle()).isEqualTo(ThreadLifecycle.MERGED);
        assertThat(t2.getMergedIntoThreadId()).isEqualTo("t1");

        // Verify target has merge metadata
        assertThat(t1.getMergedFromThreadIds()).containsExactly("t2");

        // Verify messages re-parented
        assertThat(m2.getSessionId()).isEqualTo("t1");

        // Verify deleteBySessionId was called for both source and target
        verify(messageRepository).deleteBySessionId("t2");
        verify(messageRepository).deleteBySessionId("t1");
    }

    @Test
    void mergeWithConflictingSeqNumbers() {
        // Both threads have messages at seq=1 — the old code would cause DuplicateKeyException
        ThreadDocument t1 = makeThread("t1", "Thread 1");
        t1.setContent("Content from thread 1");
        ThreadDocument t2 = makeThread("t2", "Thread 2");
        t2.setContent("Content from thread 2");
        when(threadRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(threadRepository.findById("t2")).thenReturn(Optional.of(t2));

        MessageDocument m1a = makeMessage("m1a", "t1", 1, Instant.parse("2026-01-01T10:00:00Z"));
        MessageDocument m1b = makeMessage("m1b", "t1", 2, Instant.parse("2026-01-01T11:00:00Z"));
        MessageDocument m2a = makeMessage("m2a", "t2", 1, Instant.parse("2026-01-01T09:30:00Z"));
        MessageDocument m2b = makeMessage("m2b", "t2", 2, Instant.parse("2026-01-01T10:30:00Z"));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t1")).thenReturn(new ArrayList<>(List.of(m1a, m1b)));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t2")).thenReturn(new ArrayList<>(List.of(m2a, m2b)));

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("totalMessages").asInt()).isEqualTo(4);

        // Verify delete-then-resave: both sessions' messages were deleted
        verify(messageRepository).deleteBySessionId("t2");
        verify(messageRepository).deleteBySessionId("t1");

        // Verify all 4 messages were saved with sequential seq numbers and target sessionId
        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository, atLeast(4)).save(captor.capture());
        List<MessageDocument> saved = captor.getAllValues();
        // Last 4 saves are the re-sequenced messages (ordered by timestamp)
        List<MessageDocument> resequenced = saved.subList(saved.size() - 4, saved.size());
        for (MessageDocument msg : resequenced) {
            assertThat(msg.getSessionId()).isEqualTo("t1");
        }
        // Check seq numbers are 1,2,3,4
        assertThat(resequenced.stream().map(MessageDocument::getSeq).toList())
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void mergeContentCombined() {
        ThreadDocument t1 = makeThread("t1", "Thread 1");
        t1.setContent("Architecture decisions for payments");
        ThreadDocument t2 = makeThread("t2", "Thread 2");
        t2.setContent("Implementation notes for payments");
        when(threadRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(threadRepository.findById("t2")).thenReturn(Optional.of(t2));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t1")).thenReturn(new ArrayList<>());
        when(messageRepository.findBySessionIdOrderBySeqAsc("t2")).thenReturn(new ArrayList<>());

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        // Verify merged content contains text from both sources
        assertThat(t1.getContent()).contains("Architecture decisions for payments");
        assertThat(t1.getContent()).contains("Implementation notes for payments");
        assertThat(t1.getContent()).contains("---");
    }

    @Test
    void mergeWithCustomTitle() {
        ThreadDocument t1 = makeThread("t1", "Thread 1");
        ThreadDocument t2 = makeThread("t2", "Thread 2");
        when(threadRepository.findById("t1")).thenReturn(Optional.of(t1));
        when(threadRepository.findById("t2")).thenReturn(Optional.of(t2));
        when(messageRepository.findBySessionIdOrderBySeqAsc("t2")).thenReturn(new ArrayList<>());
        when(messageRepository.findBySessionIdOrderBySeqAsc("t1")).thenReturn(new ArrayList<>());

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode ids = input.putArray("sourceThreadIds");
        ids.add("t1");
        ids.add("t2");
        input.put("targetTitle", "Combined Thread");

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        assertThat(t1.getTitle()).isEqualTo("Combined Thread");
    }

    private ThreadDocument makeThread(String id, String title) {
        ThreadDocument doc = new ThreadDocument();
        doc.setThreadId(id);
        doc.setTitle(title);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }

    private MessageDocument makeMessage(String id, String sessionId, long seq, Instant timestamp) {
        MessageDocument msg = new MessageDocument();
        msg.setMessageId(id);
        msg.setSessionId(sessionId);
        msg.setSeq(seq);
        msg.setRole("user");
        msg.setContent("test message " + id);
        msg.setTimestamp(timestamp);
        return msg;
    }
}
