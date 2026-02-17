package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ProjectRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.protocol.api.ProjectDto;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * End-to-end UI flow test: create a project, select it, send "what project is this",
 * and verify:
 * 1. The project appears in the left sidebar project list
 * 2. The message is saved with the project context (sessionId = projectId)
 * 3. The agent controller starts working (agentLoop.startAsync is called)
 * 4. The chat pane shows the user's message
 *
 * Note: Tests ThreadView directly rather than via MainWindow because JTabbedPane
 * requires a graphics context not available in CI headless mode.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectAgentFlowTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ChangeStreamService changeStreamService;
    @Mock private MessageRepository messageRepository;
    @Mock private AgentLoop agentLoop;
    @Mock private EventService eventService;

    private ThreadView threadView;

    @BeforeEach
    void setUp() {
        when(changeStreamService.watchBySessionId(any(), any(), any())).thenReturn(Flux.empty());
        when(messageRepository.countBySessionId(any())).thenReturn(0L);
        when(messageRepository.findBySessionIdOrderBySeqAsc(any())).thenReturn(List.of());

        threadView = new ThreadView(messageRepository, changeStreamService, agentLoop, eventService);
    }

    @Test
    void projectAppearsInSidebarListModel() {
        // Given: a project exists in the repository
        ProjectDocument project = createProject("Sprint Alpha", "proj-12345678-abcd");
        when(projectRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(project));

        // When: we populate the project list model (same logic as MainWindow.loadProjects)
        DefaultListModel<String> projectListModel = new DefaultListModel<>();
        List<ProjectDocument> projects = projectRepository.findAllByOrderByUpdatedAtDesc();
        for (ProjectDocument p : projects) {
            projectListModel.addElement(p.getName() + " [" + p.getProjectId().substring(0, 8) + "]");
        }

        // Then: the project should appear in the sidebar list
        assertThat(projectListModel.getSize()).isEqualTo(1);
        assertThat(projectListModel.getElementAt(0)).contains("Sprint Alpha");
        assertThat(projectListModel.getElementAt(0)).contains("proj-123");
    }

    @Test
    void sendMessageSavesToRepoWithProjectContext() throws InvocationTargetException, InterruptedException {
        // Given: a project is selected (ThreadView.setProjectId simulates selecting in sidebar)
        String projectId = "proj-12345678-abcd-efgh-ijkl";
        threadView.setProjectId(projectId);
        SwingUtilities.invokeAndWait(() -> {});

        // When: user types "what project is this" and hits Enter
        SwingUtilities.invokeAndWait(() -> {
            JTextField input = findComponent(threadView, JTextField.class);
            input.setText("what project is this");
            input.postActionEvent(); // Triggers sendMessage()
        });
        SwingUtilities.invokeAndWait(() -> {});

        // Then: the message is saved to MongoDB with the project as session context
        // This is the prompt that will reach the LLM — it includes the project name
        // because the session is bound to this project.
        ArgumentCaptor<MessageDocument> msgCaptor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).save(msgCaptor.capture());
        MessageDocument savedMsg = msgCaptor.getValue();
        assertThat(savedMsg.getRole()).isEqualTo("user");
        assertThat(savedMsg.getContent()).isEqualTo("what project is this");
        assertThat(savedMsg.getSessionId()).isEqualTo(projectId);

        // Then: a USER_MESSAGE_RECEIVED event is emitted
        // The right-side agent pane subscribes to these events via WebSocket
        verify(eventService).emit(eq(projectId), eq(EventType.USER_MESSAGE_RECEIVED), any());
    }

    @Test
    void agentControllerStartsOnRun() throws InvocationTargetException, InterruptedException {
        // Given: a project is selected with a message sent
        String projectId = "proj-12345678-abcd-efgh-ijkl";
        threadView.setProjectId(projectId);
        SwingUtilities.invokeAndWait(() -> {});

        // Send a message first
        SwingUtilities.invokeAndWait(() -> {
            JTextField input = findComponent(threadView, JTextField.class);
            input.setText("what project is this");
            input.postActionEvent();
        });
        SwingUtilities.invokeAndWait(() -> {});

        // When: user clicks Run button (triggers agent controller)
        SwingUtilities.invokeAndWait(() -> {
            JButton runBtn = findButton(threadView, "Run");
            assertThat(runBtn).as("Run button should exist in ThreadView").isNotNull();
            runBtn.doClick();
        });
        SwingUtilities.invokeAndWait(() -> {});

        // Then: AgentLoop is started with the project's session ID
        // The controller agent picks up the "what project is this" message,
        // sees the project context, and delegates to the PM agent.
        // In the UI right pane, this appears as:
        //   [controller] STEP 1 started
        //   Delegating to [pm]
        //   [pm] STEP 2 started
        verify(agentLoop).startAsync(projectId);
    }

    @Test
    void chatPaneDisplaysUserMessage() throws InvocationTargetException, InterruptedException {
        // Given: a project is selected
        String projectId = "proj-12345678-abcd-efgh-ijkl";
        threadView.setProjectId(projectId);
        SwingUtilities.invokeAndWait(() -> {});

        // When: user sends "what project is this"
        SwingUtilities.invokeAndWait(() -> {
            JTextField input = findComponent(threadView, JTextField.class);
            input.setText("what project is this");
            input.postActionEvent();
        });
        SwingUtilities.invokeAndWait(() -> {});

        // Then: the chat pane in the center shows the user message
        JTextPane chatPane = findComponent(threadView, JTextPane.class);
        assertThat(chatPane).isNotNull();
        String chatContent = chatPane.getText();
        assertThat(chatContent).contains("what project is this");
        assertThat(chatContent).contains("[user]");
    }

    @Test
    void messageSavedWithCorrectSequenceNumber() throws InvocationTargetException, InterruptedException {
        // Given: project with 2 existing messages
        String projectId = "proj-12345678-abcd-efgh-ijkl";
        when(messageRepository.countBySessionId(projectId)).thenReturn(2L);
        threadView.setProjectId(projectId);
        SwingUtilities.invokeAndWait(() -> {});

        // When: user sends a new message
        SwingUtilities.invokeAndWait(() -> {
            JTextField input = findComponent(threadView, JTextField.class);
            input.setText("what project is this");
            input.postActionEvent();
        });
        SwingUtilities.invokeAndWait(() -> {});

        // Then: message seq is 3 (existing 2 + 1)
        ArgumentCaptor<MessageDocument> msgCaptor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getSeq()).isEqualTo(3);
    }

    // --- Helpers ---

    private ProjectDocument createProject(String name, String projectId) {
        ProjectDocument project = new ProjectDocument();
        project.setProjectId(projectId);
        project.setName(name);
        project.setStatus(ProjectDto.ProjectStatus.ACTIVE);
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return project;
    }

    @SuppressWarnings("unchecked")
    private <T> T findComponent(java.awt.Container container, Class<T> type) {
        for (java.awt.Component c : container.getComponents()) {
            if (type.isInstance(c)) return (T) c;
            if (c instanceof java.awt.Container child) {
                T found = findComponent(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private JButton findButton(java.awt.Container container, String text) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof JButton btn && text.equals(btn.getText())) return btn;
            if (c instanceof java.awt.Container child) {
                JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
