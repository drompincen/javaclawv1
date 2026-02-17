package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRepository agentRepository;

    public AgentController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public List<AgentDocument> list() {
        return agentRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentDocument> get(@PathVariable String id) {
        return agentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AgentDocument> create(@RequestBody AgentDocument agent) {
        if (agent.getAgentId() == null) agent.setAgentId(UUID.randomUUID().toString());
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        if (agent.getRole() == null) agent.setRole(AgentRole.SPECIALIST);
        agent.setEnabled(true);
        agentRepository.save(agent);
        return ResponseEntity.ok(agent);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentDocument> update(@PathVariable String id, @RequestBody AgentDocument updates) {
        return agentRepository.findById(id).map(existing -> {
            if (updates.getName() != null) existing.setName(updates.getName());
            if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
            if (updates.getSystemPrompt() != null) existing.setSystemPrompt(updates.getSystemPrompt());
            if (updates.getSkills() != null) existing.setSkills(updates.getSkills());
            if (updates.getAllowedTools() != null) existing.setAllowedTools(updates.getAllowedTools());
            if (updates.getRole() != null) existing.setRole(updates.getRole());
            existing.setUpdatedAt(Instant.now());
            agentRepository.save(existing);
            return ResponseEntity.ok(existing);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (agentRepository.existsById(id)) {
            agentRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
