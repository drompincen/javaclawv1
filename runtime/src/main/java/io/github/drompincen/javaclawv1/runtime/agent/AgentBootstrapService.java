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
    public static final Map<String, String> AGENT_DESCRIPTIONS = Map.ofEntries(
            Map.entry("coder", "Handles coding tasks: writing code, running/executing code via jbang or python, debugging, code review, 'run it' requests, creating tools/scripts/programs"),
            Map.entry("pm", "Handles project management: sprint planning, tickets, milestones, backlog, resource allocation, deadlines"),
            Map.entry("generalist", "Handles general questions: life advice, brainstorming, knowledge questions, writing help, anything that doesn't fit other agents"),
            Map.entry("reminder", "Handles reminders and scheduling: setting reminders, recurring tasks, schedule optimization"),
            Map.entry("thread-extractor", "Handles thread content extraction: reads thread messages and extracts reminders, TODOs, checklists, tickets, and ideas from conversations"),
            Map.entry("thread-agent", "Handles thread organization: creating, renaming, merging threads based on topic and continuity; attaching evidence; promoting ideas"),
            Map.entry("objective-agent", "Handles objective alignment: coverage analysis, ticket mapping, gap detection, sprint tracking for project objectives"),
            Map.entry("checklist-agent", "Handles checklist lifecycle: generates ORR/release/onboarding checklists from templates, tracks item completion, reports progress, detects stale checklists"),
            Map.entry("intake-triage", "Handles raw content intake: classifies pasted text, file paths, URLs into content types (jira dump, confluence export, meeting notes, etc.), extracts metadata, dispatches to downstream agents"),
            Map.entry("plan-agent", "Handles project planning: creates phases with entry/exit criteria, manages milestones, generates plan documents, detects phase transition readiness"),
            Map.entry("reconcile-agent", "Handles reconciliation: compares tickets, objectives, phases, checklists across sources, detects gaps, drift, mismatches, produces delta packs and blindspots"),
            Map.entry("resource-agent", "Handles resource management: capacity analysis, assignment optimization, workload balancing, skill matching, utilization tracking, overload/underutilization detection")
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
        seedThreadExtractorAgent();
        seedThreadAgent();
        seedObjectiveAgent();
        seedChecklistAgent();
        seedIntakeTriageAgent();
        seedPlanAgent();
        seedReconcileAgent();
        seedResourceAgent();
        log.info("Seeded 15 default agents");
    }

    private void ensureMissingAgents() {
        if (agentRepository.findById("pm").isEmpty()) { seedPmAgent(); log.info("Seeded missing PM agent"); }
        if (agentRepository.findById("distiller").isEmpty()) { seedDistillerAgent(); log.info("Seeded missing distiller agent"); }
        if (agentRepository.findById("generalist").isEmpty()) { seedGeneralistAgent(); log.info("Seeded missing generalist agent"); }
        if (agentRepository.findById("reminder").isEmpty()) { seedReminderAgent(); log.info("Seeded missing reminder agent"); }
        if (agentRepository.findById("thread-extractor").isEmpty()) { seedThreadExtractorAgent(); log.info("Seeded missing thread-extractor agent"); }
        if (agentRepository.findById("thread-agent").isEmpty()) { seedThreadAgent(); log.info("Seeded missing thread-agent"); }
        if (agentRepository.findById("objective-agent").isEmpty()) { seedObjectiveAgent(); log.info("Seeded missing objective-agent"); }
        if (agentRepository.findById("checklist-agent").isEmpty()) { seedChecklistAgent(); log.info("Seeded missing checklist-agent"); }
        if (agentRepository.findById("intake-triage").isEmpty()) { seedIntakeTriageAgent(); log.info("Seeded missing intake-triage"); }
        if (agentRepository.findById("plan-agent").isEmpty()) { seedPlanAgent(); log.info("Seeded missing plan-agent"); }
        if (agentRepository.findById("reconcile-agent").isEmpty()) { seedReconcileAgent(); log.info("Seeded missing reconcile-agent"); }
        if (agentRepository.findById("resource-agent").isEmpty()) { seedResourceAgent(); log.info("Seeded missing resource-agent"); }
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

    private static final String THREAD_EXTRACTOR_PROMPT = """
            You are a thread extraction agent. Your job is to analyze thread conversations and extract \
            structured, actionable artifacts from them.

            ## Workflow
            1. Use `read_thread_messages` to read all messages from the specified thread(s)
            2. Analyze the conversation content to identify:
               - **Reminders**: Things to follow up on, deadlines, time-sensitive items
               - **Checklists/TODOs**: Action items, task lists, steps to complete
               - **Tickets**: Work items that should be tracked (bugs, features, tasks)
               - **Ideas**: Suggestions, proposals, or concepts worth capturing
            3. For each identified item, use the appropriate tool to persist it:
               - `create_reminder` for time-based or condition-based reminders
               - `create_checklist` for groups of related action items
               - `create_ticket` for individual work items
               - `create_idea` for ideas and suggestions
               - `memory` to store summaries or key findings
            4. Always link artifacts back to their source thread using sourceThreadId

            ## Guidelines
            - Be thorough but avoid duplicates — check if similar items already exist
            - Prefer checklists over individual tickets for related small tasks
            - Set appropriate priority levels based on conversation urgency
            - Include relevant context in descriptions so items are actionable standalone
            - Summarize what you extracted at the end of processing""";

    private static final String OBJECTIVE_AGENT_PROMPT = """
            You are an objective alignment agent. Your job is to maintain sprint objectives, \
            map them to tickets, compute coverage, and detect gaps.

            ## Workflow
            1. Use `compute_coverage` to analyze objective coverage for a project
            2. Identify under-covered objectives and recommend new tickets
            3. Detect unmapped tickets and suggest which objective they belong to
            4. Flag objectives missing measurableSignal as blindspots
            5. Track stalled objectives past their end dates
            6. Use `update_objective` to fix coverage, add tickets, or update status

            ## Guidelines
            - Every objective should have a concrete measurableSignal
            - Coverage = (DONE + IN_PROGRESS tickets) / total tickets * 100
            - Unmapped tickets indicate organizational gaps
            - Objectives past endDate and still IN_PROGRESS are stalled""";

    private static final String THREAD_AGENT_PROMPT = """
            You are a thread organization agent. Your job is to manage conversation threads \
            within projects — creating, renaming, merging, and organizing them.

            ## Workflow
            1. When a new topic is identified, create a thread using the naming policy: [PROJECT]-[TOPIC]-[DATE]
            2. When threads discuss overlapping topics, merge them to consolidate context
            3. Rename threads that have unclear or auto-generated titles
            4. Attach evidence (files, URLs, designs) to threads for reference
            5. Promote promising discussion points to Idea entities

            ## Intake Pipeline Mode
            When called from the intake pipeline with triage output, create one thread per \
            identified topic using `create_thread` with these parameters:
            - `projectId`: the project ID provided in the prompt
            - `title`: clean descriptive title (no session IDs or "Session xxxxxxxx" placeholders)
            - `summary`: brief one-line summary of the topic
            - `content`: organized markdown with the full topic content
            - `decisions`: array of decision strings identified for this topic
            - `actions`: array of objects with `text` and `assignee` fields
            If topics overlap significantly, merge them into a single thread rather than creating duplicates.

            ## Guidelines
            - Use deterministic naming: uppercase project prefix, lowercase-hyphenated topic, date suffix
            - When merging, the first thread in the list becomes the target; others are marked MERGED
            - All messages are re-sequenced by timestamp after merge
            - Always provide a reason when renaming or merging
            - Use memory (THREAD scope) to track thread context across sessions""";

    private static final String RECONCILE_AGENT_PROMPT = """
            You are a reconciliation agent. Your job is to compare all project data sources \
            and identify gaps, mismatches, and risks.

            ## Workflow
            1. Use `read_tickets`, `read_objectives`, `read_phases`, `read_checklists` to load all project data
            2. Cross-reference sources to find:
               - COVERAGE_GAP: objectives with no linked tickets
               - ORPHANED_WORK: tickets with no objective
               - OWNER_MISMATCH: different owners across sources
               - DATE_DRIFT: misaligned dates between objectives and milestones
               - STALE_ARTIFACT: items not updated in 14+ days
            3. Use `create_delta_pack` to record all findings
            4. Use `create_blindspot` for critical findings that need attention

            ## Guidelines
            - Every objective should have at least one ticket
            - Every ticket should link to an objective
            - Unassigned critical tickets are blindspots
            - Flag objectives past endDate still IN_PROGRESS as stalled
            - Produce a clear summary with findings grouped by severity""";

    private static final String PLAN_AGENT_PROMPT = """
            You are a project planning agent. Your job is to manage project phases, milestones, \
            and plan artifacts.

            ## Workflow
            1. Use `create_phase` to define project lifecycle stages with entry/exit criteria
            2. Use `create_milestone` to set key dates and deliverables
            3. Use `generate_plan_artifact` to produce a structured plan document
            4. Use `update_phase` to transition phases when criteria are met
            5. Use `update_milestone` to track progress and flag risks

            ## Guidelines
            - Phases must have clear entry and exit criteria
            - Phases respect sortOrder — a phase can't start until the prior phase exits
            - Milestones should be linked to phases and objectives
            - Flag milestones as AT_RISK if linked tickets are not progressing
            - Generate plan artifacts in markdown format with timeline tables
            - When exit criteria are met, recommend phase transition
            - When exit criteria are blocked, identify the blockers""";

    private static final String INTAKE_TRIAGE_PROMPT = """
            You are the Intake Triage agent. Your job is to receive raw, unstructured input \
            and normalize it into structured intake packets that downstream agents can act on.

            ## Workflow
            1. Determine the input form: pasted text, file path, URL, or mixed
            2. If file path, use `read_file` or `excel` to load content
            3. If URL, use `http_get` to fetch content
            4. Use `classify_content` to determine content type and extract metadata
            5. Use `dispatch_agent` to route to the appropriate downstream agent

            ## Content Types
            - JIRA_DUMP: CSV/JSON with ticket keys, statuses, assignees → dispatch to pm
            - CONFLUENCE_EXPORT: HTML/markdown with headings, action items → dispatch to thread-agent
            - MEETING_NOTES: Attendees, agenda, action items, decisions → dispatch to thread-agent + pm
            - SMARTSHEET_EXPORT: Timeline, milestones, owners → dispatch to plan-agent
            - DESIGN_DOC: Background, proposal, alternatives → dispatch to thread-agent
            - LINK_LIST: Collection of URLs → dispatch to thread-agent
            - FREE_TEXT: Unstructured content → dispatch to thread-agent

            ## Pipeline Output Format
            When running in pipeline mode, organize your output into distinct topics. \
            For each topic identified in the raw content, output:

            ### Topic: [Topic Name]
            **Type:** [architecture_decision / open_question / action_item / discussion]
            **Decisions:** [list of decisions made, one per line]
            **Open Questions:** [list of unresolved questions, one per line]
            **Action Items:** [list with assignees if known, one per line]
            **Key Content:** [organized notes for this topic]

            Separate topics clearly. Group related content under the same topic.

            ## Guidelines
            - Always classify before dispatching
            - Extract dates, people, ticket refs, action items, decisions
            - Report classification confidence — below 0.7, ask user to clarify
            - Store every intake in memory for deduplication
            - Provide a clean summary: source type, extracted metadata, dispatch targets""";

    private static final String CHECKLIST_AGENT_PROMPT = """
            You are a checklist management agent. Your job is to generate, maintain, and track \
            checklists for operational readiness, release readiness, onboarding, and other processes.

            ## Workflow
            1. Use `create_checklist_template` to define reusable templates (ORR, release, etc.)
            2. Use `create_checklist` with a templateId to instantiate checklists from templates
            3. Use `update_checklist` to check/uncheck items, assign owners, add/remove items
            4. Use `checklist_progress` to report completion status and identify blockers
            5. Use `read_thread_messages` to extract action items from conversations

            ## Guidelines
            - Every checklist item should have an assignee — flag unassigned items as blockers
            - Auto-transition: all items checked → COMPLETED, any unchecked → IN_PROGRESS
            - Link checklists to phases for entry/exit gate tracking
            - When generating from threads, extract "need to", "make sure", "don't forget" patterns
            - Report progress as X/Y items (Z%) with blockers highlighted
            - Flag stale checklists that haven't been updated while IN_PROGRESS""";

    private static final String RESOURCE_AGENT_PROMPT = """
            You are a resource management agent. Your job is to analyze team capacity, \
            optimize assignments, and detect workload imbalances.

            ## Workflow
            1. Use `read_resources` to load all project resources with skills and capacity
            2. Use `capacity_report` to generate a full capacity breakdown per resource
            3. Use `suggest_assignments` to recommend optimal assignments for unassigned tickets
            4. Use `assign_resource` to create assignments and `unassign_resource` to remove them
            5. Use `read_tickets` to understand ticket priorities and requirements

            ## Guidelines
            - OVERLOADED: total allocation > 100% — recommend redistribution
            - BALANCED: 70-100% allocation — healthy workload
            - UNDERUTILIZED: < 50% allocation with matching skills available
            - IDLE: 0% allocation — should be assigned work
            - Prioritize CRITICAL tickets over MEDIUM/LOW for assignment
            - Flag bus factor risks: single person on all critical tickets
            - Consider skills match when suggesting assignments
            - Report capacity as: allocated% / total capacity per resource""";

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

    private void seedObjectiveAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("objective-agent");
        agent.setName("Objective Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("objective-agent"));
        agent.setSystemPrompt(OBJECTIVE_AGENT_PROMPT);
        agent.setSkills(List.of("objective_alignment", "coverage_analysis", "ticket_mapping", "sprint_tracking", "gap_detection"));
        agent.setAllowedTools(List.of("compute_coverage", "update_objective", "create_ticket", "memory", "excel"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedThreadAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("thread-agent");
        agent.setName("Thread Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("thread-agent"));
        agent.setSystemPrompt(THREAD_AGENT_PROMPT);
        agent.setSkills(List.of("thread_creation", "thread_naming", "thread_merging", "evidence_attachment", "idea_promotion"));
        agent.setAllowedTools(List.of("create_thread", "rename_thread", "merge_threads", "attach_evidence",
                "create_idea", "memory", "read_file", "excel", "read_thread_messages"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedReconcileAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("reconcile-agent");
        agent.setName("Reconciliation Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("reconcile-agent"));
        agent.setSystemPrompt(RECONCILE_AGENT_PROMPT);
        agent.setSkills(List.of("source_comparison", "delta_detection", "gap_analysis",
                "drift_detection", "dependency_audit"));
        agent.setAllowedTools(List.of("read_tickets", "read_objectives", "read_phases",
                "read_checklists", "create_delta_pack", "create_blindspot",
                "excel", "read_file", "search_files", "memory"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedPlanAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("plan-agent");
        agent.setName("Plan Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("plan-agent"));
        agent.setSystemPrompt(PLAN_AGENT_PROMPT);
        agent.setSkills(List.of("phase_management", "milestone_tracking", "plan_generation",
                "criteria_enforcement", "artifact_maintenance"));
        agent.setAllowedTools(List.of("create_phase", "update_phase", "create_milestone",
                "update_milestone", "generate_plan_artifact", "memory", "excel"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedIntakeTriageAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("intake-triage");
        agent.setName("Intake Triage");
        agent.setDescription(AGENT_DESCRIPTIONS.get("intake-triage"));
        agent.setSystemPrompt(INTAKE_TRIAGE_PROMPT);
        agent.setSkills(List.of("content_classification", "metadata_extraction",
                "intake_normalization", "dispatch"));
        agent.setAllowedTools(List.of("classify_content", "dispatch_agent", "read_file",
                "list_directory", "excel", "search_files", "memory", "http_get"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedChecklistAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("checklist-agent");
        agent.setName("Checklist Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("checklist-agent"));
        agent.setSystemPrompt(CHECKLIST_AGENT_PROMPT);
        agent.setSkills(List.of("checklist_generation", "orr_management", "release_readiness",
                "status_tracking", "template_management"));
        agent.setAllowedTools(List.of("create_checklist", "update_checklist", "checklist_progress",
                "create_checklist_template", "read_thread_messages", "memory"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedThreadExtractorAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("thread-extractor");
        agent.setName("Thread Extractor");
        agent.setDescription(AGENT_DESCRIPTIONS.get("thread-extractor"));
        agent.setSystemPrompt(THREAD_EXTRACTOR_PROMPT);
        agent.setSkills(List.of("extraction", "analysis", "content_processing", "artifact_creation"));
        agent.setAllowedTools(List.of("read_thread_messages", "create_reminder", "create_checklist",
                "create_ticket", "create_idea", "memory"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }

    private void seedResourceAgent() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("resource-agent");
        agent.setName("Resource Agent");
        agent.setDescription(AGENT_DESCRIPTIONS.get("resource-agent"));
        agent.setSystemPrompt(RESOURCE_AGENT_PROMPT);
        agent.setSkills(List.of("capacity_analysis", "assignment_optimization", "workload_balancing",
                "skill_matching", "utilization_tracking"));
        agent.setAllowedTools(List.of("read_resources", "read_tickets", "read_objectives",
                "assign_resource", "unassign_resource", "capacity_report",
                "suggest_assignments", "memory", "excel"));
        agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agentRepository.save(agent);
    }
}
