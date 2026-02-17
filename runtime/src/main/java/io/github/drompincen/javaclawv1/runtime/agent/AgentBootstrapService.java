package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AgentBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrapService.class);

    private final AgentRepository agentRepository;

    public AgentBootstrapService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @PostConstruct
    public void bootstrap() {
        long count = agentRepository.count();
        if (count > 0) {
            log.info("Agents already seeded ({} found), checking for missing agents", count);
            ensurePmAgent();
            return;
        }

        log.info("Seeding default agents...");

        AgentDocument controller = new AgentDocument();
        controller.setAgentId("controller");
        controller.setName("Controller");
        controller.setDescription("Routing agent that analyzes tasks and delegates to specialists");
        controller.setSystemPrompt("""
                You are a controller agent. Your job is to analyze the user's task and decide \
                which specialist agent should handle it.

                Available specialist agents will be listed in the conversation. Respond with JSON:
                {"delegate": "agentId", "subTask": "description of what the specialist should do"}

                If the task is simple enough that you can answer directly, respond with:
                {"respond": "your direct answer here"}

                Always pick the most appropriate specialist for the task.""");
        controller.setSkills(List.of("task analysis", "delegation", "routing"));
        controller.setAllowedTools(List.of("*"));
        controller.setRole(AgentRole.CONTROLLER);
        controller.setEnabled(true);
        controller.setCreatedAt(Instant.now());
        controller.setUpdatedAt(Instant.now());
        agentRepository.save(controller);

        AgentDocument coder = new AgentDocument();
        coder.setAgentId("coder");
        coder.setName("Coder");
        coder.setDescription("Code specialist that writes, edits, and executes code");
        coder.setSystemPrompt("""
                You are a coding specialist agent. You write, edit, debug, and execute code. \
                You have access to file tools, shell execution, git operations, and JBang for \
                running Java code. Be thorough and write clean, working code.

                When you are done with your task, provide a summary of what you did.""");
        coder.setSkills(List.of("coding", "debugging", "testing", "file editing", "shell commands"));
        coder.setAllowedTools(List.of("*"));
        coder.setRole(AgentRole.SPECIALIST);
        coder.setEnabled(true);
        coder.setCreatedAt(Instant.now());
        coder.setUpdatedAt(Instant.now());
        agentRepository.save(coder);

        AgentDocument reviewer = new AgentDocument();
        reviewer.setAgentId("reviewer");
        reviewer.setName("Reviewer");
        reviewer.setDescription("Quality checker that validates task completion and correctness");
        reviewer.setSystemPrompt("""
                You are a reviewer/checker agent. Your job is to verify that a task was completed \
                correctly and satisfactorily. Review the work done by the specialist agent.

                You can read files, search code, list directories, and run shell commands to verify.

                Respond with JSON:
                {"pass": true, "summary": "brief summary of what was checked and why it passes"}
                or
                {"pass": false, "feedback": "what needs to be fixed and why"}""");
        reviewer.setSkills(List.of("code review", "testing", "validation"));
        reviewer.setAllowedTools(List.of("read_file", "search_files", "list_directory", "shell_exec", "jbang_exec", "python_exec", "excel", "memory"));
        reviewer.setRole(AgentRole.CHECKER);
        reviewer.setEnabled(true);
        reviewer.setCreatedAt(Instant.now());
        reviewer.setUpdatedAt(Instant.now());
        agentRepository.save(reviewer);

        seedPmAgent();
        seedDistillerAgent();

        log.info("Seeded 5 default agents: controller, coder, reviewer, pm, distiller");
    }

    private void ensurePmAgent() {
        if (agentRepository.findById("pm").isEmpty()) {
            seedPmAgent();
            log.info("Seeded missing PM agent");
        }
        if (agentRepository.findById("distiller").isEmpty()) {
            seedDistillerAgent();
            log.info("Seeded missing distiller agent");
        }
    }

    private void seedPmAgent() {
        AgentDocument pm = new AgentDocument();
        pm.setAgentId("pm");
        pm.setName("PM");
        pm.setDescription("Project management specialist for planning, tracking, and stakeholder coordination");
        pm.setSystemPrompt("""
                You are a project management specialist agent. You help engineering managers with:

                - Understanding project purpose, goals, and scope
                - Sprint planning and milestone tracking
                - Ticket creation and prioritization
                - Resource planning and allocation
                - Stakeholder identification and communication
                - Risk assessment and mitigation
                - Rendering help and services to the team

                You can create tickets, ideas, and read project files to understand context. \
                When asked about project status, check existing tickets, plans, and milestones. \
                Always provide actionable recommendations with clear next steps.

                When you are done with your task, provide a structured summary of findings \
                and recommended actions.""");
        pm.setSkills(List.of("project management", "sprint planning", "ticket management",
                "resource planning", "stakeholder tracking", "risk assessment"));
        pm.setAllowedTools(List.of("create_ticket", "create_idea", "memory", "excel",
                "read_file", "list_directory", "search_files"));
        pm.setRole(AgentRole.SPECIALIST);
        pm.setEnabled(true);
        pm.setCreatedAt(Instant.now());
        pm.setUpdatedAt(Instant.now());
        agentRepository.save(pm);
    }

    private void seedDistillerAgent() {
        AgentDocument distiller = new AgentDocument();
        distiller.setAgentId("distiller");
        distiller.setName("Distiller");
        distiller.setDescription("Distills completed sessions into persistent memories");
        distiller.setSystemPrompt("""
                You are a distiller agent. Your job is to analyze completed sessions and extract \
                key knowledge, decisions, and outcomes into persistent memories. You summarize \
                conversations and store important findings for future reference.

                When you are done with your task, provide a summary of what was distilled.""");
        distiller.setSkills(List.of("memory_extraction", "summarization", "knowledge_distillation"));
        distiller.setAllowedTools(List.of("memory"));
        distiller.setRole(AgentRole.SPECIALIST);
        distiller.setEnabled(true);
        distiller.setCreatedAt(Instant.now());
        distiller.setUpdatedAt(Instant.now());
        agentRepository.save(distiller);
    }
}
