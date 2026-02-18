package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AgentBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrapService.class);

    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;

    /** Agent descriptions used by the LLM controller to decide routing */
    public static final Map<String, String> AGENT_DESCRIPTIONS = Map.of(
            "coder", "Handles coding tasks: writing code, running/executing code via jbang or python, debugging, code review, 'run it' requests, creating tools/scripts/programs",
            "pm", "Handles project management: sprint planning, tickets, milestones, backlog, resource allocation, deadlines",
            "generalist", "Handles general questions: life advice, brainstorming, knowledge questions, writing help, anything that doesn't fit other agents",
            "reminder", "Handles reminders and scheduling: setting reminders, recurring tasks, schedule optimization"
    );

    public AgentBootstrapService(AgentRepository agentRepository, SessionRepository sessionRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
    }

    @PostConstruct
    public void bootstrap() {
        // Clean up ephemeral sessions — only threads survive restarts
        long sessionCount = sessionRepository.count();
        if (sessionCount > 0) {
            sessionRepository.deleteAll();
            log.info("Cleaned up {} ephemeral sessions on startup (threads preserved)", sessionCount);
        }

        long count = agentRepository.count();
        if (count > 0) {
            log.info("Agents already seeded ({} found), checking for missing agents", count);
            ensureMissingAgents();
            return;
        }

        log.info("Seeding default agents...");
        seedController();
        seedCoder();
        seedReviewer();
        seedPmAgent();
        seedGeneralistAgent();
        seedReminderAgent();
        seedDistillerAgent();
        log.info("Seeded 7 default agents: controller, coder, reviewer, pm, generalist, reminder, distiller");
    }

    private void ensureMissingAgents() {
        if (agentRepository.findById("pm").isEmpty()) { seedPmAgent(); log.info("Seeded missing PM agent"); }
        if (agentRepository.findById("distiller").isEmpty()) { seedDistillerAgent(); log.info("Seeded missing distiller agent"); }
        if (agentRepository.findById("generalist").isEmpty()) { seedGeneralistAgent(); log.info("Seeded missing generalist agent"); }
        if (agentRepository.findById("reminder").isEmpty()) { seedReminderAgent(); log.info("Seeded missing reminder agent"); }
        // Fix agents from older bootstraps that may lack the enabled flag
        agentRepository.findAll().forEach(agent -> {
            if (!agent.isEnabled()) {
                agent.setEnabled(true);
                agent.setUpdatedAt(Instant.now());
                agentRepository.save(agent);
                log.info("Fixed enabled flag on agent: {}", agent.getAgentId());
            }
        });
        // Update existing agents with rich prompts if they have short prompts
        updateIfNeeded("controller", CONTROLLER_PROMPT);
        updateIfNeeded("coder", CODER_PROMPT);
        updateIfNeeded("reviewer", REVIEWER_PROMPT);
    }

    private void updateIfNeeded(String agentId, String richPrompt) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            if (agent.getSystemPrompt() != null && agent.getSystemPrompt().length() < richPrompt.length()) {
                agent.setSystemPrompt(richPrompt);
                agent.setUpdatedAt(Instant.now());
                agentRepository.save(agent);
                log.info("Updated {} agent with rich system prompt", agentId);
            }
        });
    }

    // --- Rich system prompts matching javaclaw.java AGENT_SYSTEM_PROMPTS ---

    private static final String CONTROLLER_PROMPT = """
            You are a routing controller that delegates tasks to specialist agents.
            You decide which agent should handle each user request.""";

    private static final String CODER_PROMPT = """
            You are a senior software engineer with the ability to READ FILES, WRITE FILES, and EXECUTE CODE.

            ## Available Tools
            To use a tool, output a <tool_call> block with JSON specifying "name" and "args":

            <tool_call>
            {"name": "list_directory", "args": {"path": "/some/directory"}}
            </tool_call>

            <tool_call>
            {"name": "read_file", "args": {"path": "/some/file.java"}}
            </tool_call>

            ### Tool Reference:
            - **list_directory**: List files/dirs in a path. Args: {"path": "..."}
            - **read_file**: Read file contents. Args: {"path": "..."}
            - **write_file**: Write content to file. Args: {"path": "...", "content": "..."}
            - **search_files**: Search file contents. Args: {"pattern": "...", "path": "..."}
            - **shell_exec**: Run a shell command. Args: {"command": "..."}
            - **jbang_exec**: Run Java code via JBang. Args: {"code": "...", "args": [...]}
            - **python_exec**: Run Python code. Args: {"code": "..."}
            - **git_status**: Show git status. Args: {"path": "..."}
            - **git_diff**: Show git diff. Args: {"path": "..."}

            ## Workflow
            1. When the user asks about files, ALWAYS use list_directory or read_file first
            2. After receiving tool results, explain what you found
            3. Use write_file or shell_exec only when the user explicitly asks to create/modify/run something

            Paths can be Windows (C:\\Users\\...) or Unix (/home/...) — both work.
            Be concise and provide working code. Use markdown formatting.""";

    private static final String REVIEWER_PROMPT = """
            You are a quality reviewer. Review the specialist agent's response and decide:
            - PASS: The response fully addresses the user's request
            - FAIL: The response is incomplete, wrong, or misses the point
            - OPTIONS: The response was partial (e.g., listed files but didn't read them). Offer the user numbered options.
            When reviewing, consider the FULL user request, not just the literal response.
            For example, if the user asked to read files and the agent only listed a directory, that's incomplete — suggest reading the files.
            Respond with one of: PASS, FAIL, or OPTIONS followed by numbered choices.
            Use markdown formatting.""";

    private static final String PM_PROMPT = """
            You are a project manager and assistant. Your skills include:
            - Sprint planning, backlog grooming, and task estimation
            - Creating and organizing tickets and milestones
            - Resource allocation and deadline tracking
            - Roadmap planning and stakeholder communication
            - Retrospective facilitation and team workflow optimization
            You have access to the session and thread history for context.
            Be practical, clear, and focused on outcomes. Use markdown formatting.""";

    private static final String GENERALIST_PROMPT = """
            You are a helpful, knowledgeable AI assistant. Your skills include:
            - Answering general knowledge questions on any topic
            - Giving life advice, wellness tips, and personal productivity suggestions
            - Brainstorming ideas and creative problem-solving
            - Summarizing information and explaining complex topics simply
            - Helping with writing, communication, and decision-making
            You have access to the full conversation history for context.
            Be friendly, concise, and helpful. Use markdown formatting.""";

    private static final String REMINDER_PROMPT = """
            You are a scheduling and reminder assistant. Your skills include:
            - Creating reminders for tasks, events, and deadlines
            - Reading files or previous conversation context to identify things the user should be reminded about
            - Suggesting optimal times based on the user's described schedule
            - Organizing recurring reminders (daily, weekly, etc.)
            - Prioritizing tasks by urgency and importance
            You have access to the full conversation history. If files were read or listed earlier in the conversation,
            use that content to identify reminders. When you identify reminders, list each one clearly with:
            REMINDER: <what> | WHEN: <time> | RECURRING: <yes/no interval>
            Then provide a friendly summary after. Use markdown formatting.""";

    private void seedController() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("controller");
        agent.setName("Controller");
        agent.setDescription("Routing agent that analyzes tasks and delegates to specialists");
        agent.setSystemPrompt(CONTROLLER_PROMPT);
        agent.setSkills(List.of("task analysis", "delegation", "routing"));
        agent.setAllowedTools(List.of("*"));
        agent.setRole(AgentRole.CONTROLLER);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedCoder() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("coder");
        agent.setName("Coder");
        agent.setDescription(AGENT_DESCRIPTIONS.get("coder"));
        agent.setSystemPrompt(CODER_PROMPT);
        agent.setSkills(List.of("coding", "debugging", "testing", "file editing", "shell commands", "jbang", "python"));
        agent.setAllowedTools(List.of("*"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedReviewer() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("reviewer");
        agent.setName("Reviewer");
        agent.setDescription("Quality checker that validates task completion and correctness");
        agent.setSystemPrompt(REVIEWER_PROMPT);
        agent.setSkills(List.of("code review", "testing", "validation"));
        agent.setAllowedTools(List.of("read_file", "search_files", "list_directory", "shell_exec", "jbang_exec", "python_exec", "excel", "memory"));
        agent.setRole(AgentRole.CHECKER);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedPmAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("pm");
        agent.setName("PM");
        agent.setDescription(AGENT_DESCRIPTIONS.get("pm"));
        agent.setSystemPrompt(PM_PROMPT);
        agent.setSkills(List.of("project management", "sprint planning", "ticket management",
                "resource planning", "stakeholder tracking", "risk assessment"));
        agent.setAllowedTools(List.of("create_ticket", "create_idea", "memory", "excel",
                "read_file", "list_directory", "search_files"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedGeneralistAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("generalist");
        agent.setName("Generalist");
        agent.setDescription(AGENT_DESCRIPTIONS.get("generalist"));
        agent.setSystemPrompt(GENERALIST_PROMPT);
        agent.setSkills(List.of("general knowledge", "brainstorming", "writing", "advice"));
        agent.setAllowedTools(List.of("memory", "read_file"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedReminderAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("reminder");
        agent.setName("Reminder");
        agent.setDescription(AGENT_DESCRIPTIONS.get("reminder"));
        agent.setSystemPrompt(REMINDER_PROMPT);
        agent.setSkills(List.of("scheduling", "reminders", "time management"));
        agent.setAllowedTools(List.of("memory", "read_file"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedDistillerAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("distiller");
        agent.setName("Distiller");
        agent.setDescription("Distills completed sessions into persistent memories");
        agent.setSystemPrompt("""
                You are a distiller agent. Your job is to analyze completed sessions and extract \
                key knowledge, decisions, and outcomes into persistent memories. You summarize \
                conversations and store important findings for future reference.

                When you are done with your task, provide a summary of what was distilled.""");
        agent.setSkills(List.of("memory_extraction", "summarization", "knowledge_distillation"));
        agent.setAllowedTools(List.of("memory"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }
}
