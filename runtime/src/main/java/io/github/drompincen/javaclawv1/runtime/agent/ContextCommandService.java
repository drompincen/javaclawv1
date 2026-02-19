package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ProjectRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles context commands such as "whereami", "use project X", and "use thread Y".
 * Extracted from the monolith AgentLoop to provide a clean, testable service for
 * managing session/thread/project context navigation.
 */
@Service
public class ContextCommandService {

    private final SessionRepository sessionRepository;
    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final MessageRepository messageRepository;

    public ContextCommandService(SessionRepository sessionRepository,
                                 ThreadRepository threadRepository,
                                 ProjectRepository projectRepository,
                                 MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * Handle context commands: use project, use thread, whereami.
     * Returns a response string if a command was handled, or null if not a command.
     *
     * @param msg       the user message (trimmed)
     * @param sessionId the current session or thread ID
     * @param isThread  true if sessionId refers to a thread, false for a standalone session
     * @return response string if a command was recognized, or null
     */
    public String handleContextCommand(String msg, String sessionId, boolean isThread) {
        String lower = msg.toLowerCase();

        // --- whereami ---
        if (lower.equals("whereami") || lower.equals("where am i") || lower.equals("where am i?")) {
            return buildWhereAmI(sessionId, isThread);
        }

        // --- use project [name] ---
        if (lower.startsWith("use project ")) {
            String projectName = msg.substring("use project ".length()).trim();
            return handleUseProject(projectName, sessionId, isThread);
        }

        // --- use thread [name] ---
        if (lower.startsWith("use thread ")) {
            String threadName = msg.substring("use thread ".length()).trim();
            return handleUseThread(threadName, sessionId, isThread);
        }

        return null; // Not a command
    }

    /**
     * Resolve the project ID from a session/thread context.
     * Checks thread first (via getEffectiveProjectIds), then follows the session's threadId.
     *
     * @param sessionId the session or thread ID to resolve from
     * @return the first project ID found, or null if none
     */
    public String resolveProjectId(String sessionId) {
        // Check if it's a thread with project IDs
        ThreadDocument thread = threadRepository.findById(sessionId).orElse(null);
        if (thread != null) {
            List<String> pids = thread.getEffectiveProjectIds();
            return pids.isEmpty() ? null : pids.get(0);
        }
        // Check if it's a session linked to a thread
        SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getThreadId() != null) {
            ThreadDocument t = threadRepository.findById(session.getThreadId()).orElse(null);
            if (t != null) {
                List<String> pids = t.getEffectiveProjectIds();
                return pids.isEmpty() ? null : pids.get(0);
            }
        }
        return null;
    }

    /**
     * Build a markdown response showing current context (thread, project, message count).
     */
    private String buildWhereAmI(String sessionId, boolean isThread) {
        StringBuilder sb = new StringBuilder("**Current Context**\n\n");

        if (isThread) {
            ThreadDocument thread = threadRepository.findById(sessionId).orElse(null);
            if (thread != null) {
                sb.append("- **Thread:** ").append(thread.getTitle())
                  .append(" (`").append(thread.getThreadId(), 0, 8).append("...`)\n");
                List<String> pids = thread.getEffectiveProjectIds();
                if (!pids.isEmpty()) {
                    for (String pid : pids) {
                        ProjectDocument proj = projectRepository.findById(pid).orElse(null);
                        sb.append("- **Project:** ")
                          .append(proj != null ? proj.getName() : "unknown")
                          .append(" (`").append(pid, 0, Math.min(8, pid.length())).append("...`)\n");
                    }
                } else {
                    sb.append("- **Project:** none attached\n");
                }
            } else {
                sb.append("- **Thread:** not found\n");
            }
        } else {
            SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null) {
                sb.append("- **Session:** `").append(sessionId, 0, 8).append("...`\n");
                if (session.getThreadId() != null) {
                    ThreadDocument thread = threadRepository.findById(session.getThreadId()).orElse(null);
                    sb.append("- **Thread:** ")
                      .append(thread != null ? thread.getTitle() : session.getThreadId()).append("\n");
                    if (thread != null) {
                        List<String> pids = thread.getEffectiveProjectIds();
                        for (String pid : pids) {
                            ProjectDocument proj = projectRepository.findById(pid).orElse(null);
                            sb.append("- **Project:** ")
                              .append(proj != null ? proj.getName() : pid).append("\n");
                        }
                    }
                } else {
                    sb.append("- **Thread:** none (standalone session)\n");
                    sb.append("- **Project:** none\n");
                    sb.append("\nUse `use project <name>` to attach this session to a project.");
                }
            }
        }

        // Show message count
        long msgCount = messageRepository.countBySessionId(sessionId);
        sb.append("- **Messages:** ").append(msgCount).append("\n");
        return sb.toString();
    }

    /**
     * Handle "use project [name]" command.
     * Finds project by name (case-insensitive), attaches session/thread to it.
     * If thread, adds to projectIds list. If session without threadId, creates a new thread.
     * Lists available projects if not found.
     */
    private String handleUseProject(String projectName, String sessionId, boolean isThread) {
        // Find project by name (case-insensitive)
        ProjectDocument project = projectRepository.findByNameIgnoreCase(projectName).orElse(null);
        if (project == null) {
            // List available projects
            List<ProjectDocument> all = projectRepository.findAllByOrderByUpdatedAtDesc();
            StringBuilder sb = new StringBuilder("**Project not found:** `" + projectName + "`\n\n");
            if (all.isEmpty()) {
                sb.append("No projects exist yet. Create one first.");
            } else {
                sb.append("**Available projects:**\n");
                for (ProjectDocument p : all) {
                    sb.append("- ").append(p.getName()).append("\n");
                }
            }
            return sb.toString();
        }

        if (isThread) {
            // Thread: add project to projectIds
            ThreadDocument thread = threadRepository.findById(sessionId).orElse(null);
            if (thread != null) {
                List<String> pids = thread.getProjectIds() != null
                        ? new ArrayList<>(thread.getProjectIds())
                        : new ArrayList<>();
                if (!pids.contains(project.getProjectId())) {
                    pids.add(project.getProjectId());
                    thread.setProjectIds(pids);
                    thread.setUpdatedAt(Instant.now());
                    threadRepository.save(thread);
                }
                return "**Attached to project:** " + project.getName() + "\n\n"
                        + "Thread `" + thread.getTitle() + "` is now linked to project **"
                        + project.getName() + "**.\n"
                        + "You can now import tickets, manage resources, and work within this project context.";
            }
        } else {
            // Standalone session: create or find a thread in this project and link
            SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null) {
                if (session.getThreadId() == null) {
                    // Create a new thread in the project for this session
                    ThreadDocument newThread = new ThreadDocument();
                    newThread.setThreadId(UUID.randomUUID().toString());
                    newThread.setProjectIds(List.of(project.getProjectId()));
                    newThread.setTitle(project.getName() + " — " +
                            java.time.LocalDate.now().toString());
                    newThread.setStatus(SessionStatus.IDLE);
                    newThread.setCreatedAt(Instant.now());
                    newThread.setUpdatedAt(Instant.now());
                    threadRepository.save(newThread);
                    session.setThreadId(newThread.getThreadId());
                } else {
                    // Update existing thread
                    ThreadDocument thread = threadRepository.findById(session.getThreadId()).orElse(null);
                    if (thread != null) {
                        List<String> pids = thread.getProjectIds() != null
                                ? new ArrayList<>(thread.getProjectIds())
                                : new ArrayList<>();
                        if (!pids.contains(project.getProjectId())) {
                            pids.add(project.getProjectId());
                            thread.setProjectIds(pids);
                            thread.setUpdatedAt(Instant.now());
                            threadRepository.save(thread);
                        }
                    }
                }
                session.setUpdatedAt(Instant.now());
                sessionRepository.save(session);
                return "**Attached to project:** " + project.getName() + "\n\n"
                        + "Session is now linked to project **" + project.getName() + "**.\n"
                        + "You can now use project features like tickets, resources, and imports.";
            }
        }
        return "**Error:** Could not attach to project.";
    }

    /**
     * Handle "use thread [name]" command.
     * Requires project context first. Finds thread by title within project.
     * Creates a new thread if not found.
     */
    private String handleUseThread(String threadName, String sessionId, boolean isThread) {
        // First resolve the project context
        String projectId = resolveProjectId(sessionId);

        if (projectId == null) {
            return "**No project selected.** Use `use project <name>` first, then `use thread <name>`.";
        }

        // Find thread by title within the project
        ThreadDocument thread = threadRepository
                .findByTitleIgnoreCaseAndProjectIdsContaining(threadName, projectId).orElse(null);

        if (thread == null) {
            // Create a new thread
            ThreadDocument newThread = new ThreadDocument();
            newThread.setThreadId(UUID.randomUUID().toString());
            newThread.setProjectIds(List.of(projectId));
            newThread.setTitle(threadName);
            newThread.setStatus(SessionStatus.IDLE);
            newThread.setCreatedAt(Instant.now());
            newThread.setUpdatedAt(Instant.now());
            threadRepository.save(newThread);

            ProjectDocument project = projectRepository.findById(projectId).orElse(null);
            String projectName = project != null ? project.getName() : projectId;

            // If this is a session, link it to the new thread
            if (!isThread) {
                SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
                if (session != null) {
                    session.setThreadId(newThread.getThreadId());
                    session.setUpdatedAt(Instant.now());
                    sessionRepository.save(session);
                }
            }

            return "**Created and switched to thread:** " + threadName + "\n\n"
                    + "New thread `" + threadName + "` created in project **" + projectName + "**.";
        }

        // Thread exists -- link session to it
        if (!isThread) {
            SessionDocument session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null) {
                session.setThreadId(thread.getThreadId());
                session.setUpdatedAt(Instant.now());
                sessionRepository.save(session);
            }
        }

        ProjectDocument project = projectRepository.findById(projectId).orElse(null);
        String projectName = project != null ? project.getName() : projectId;
        long threadMsgCount = messageRepository.countBySessionId(thread.getThreadId());

        return "**Switched to thread:** " + thread.getTitle() + "\n\n"
                + "- **Project:** " + projectName + "\n"
                + "- **Thread:** " + thread.getTitle() + " (`" + thread.getThreadId().substring(0, 8) + "...`)\n"
                + "- **Messages in thread:** " + threadMsgCount + "\n";
    }
}
