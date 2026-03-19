package com.agent.controller;

import com.agent.agent.Agent;
import com.agent.agent.ConfigurableAgent;
import com.agent.cache.SmartCache;
import com.agent.exception.StrictModeException;
import com.agent.llm.OllamaService;
import com.agent.memory.ConversationMemory;
import com.agent.model.AgentRequest;
import com.agent.model.AgentResponse;
import com.agent.orchestrator.AgentOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final List<Agent> agents;
    private final OllamaService ollamaService;
    private final ConversationMemory conversationMemory;
    private final SmartCache cache;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentResponse> chat(@RequestBody AgentRequest request) {
        return orchestrator.handle(request);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, Object>>> getAgents() {
        List<Map<String, Object>> agentInfos = agents.stream()
                .map(agent -> {
                    if (agent instanceof ConfigurableAgent ca) {
                        var def = ca.getDefinition();
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("name", def.getName());
                        info.put("type", def.getType().name());
                        info.put("model", def.getModel() != null ? def.getModel() : "default");
                        info.put("responseLevel", def.getResponseLevel().name());
                        info.put("chainOfThought", def.isChainOfThought());
                        info.put("selfVerify", def.isSelfVerify());
                        info.put("temperature", def.getTemperature());
                        return (Map<String, Object>) info;
                    }
                    return Map.<String, Object>of(
                            "name", agent.getType().name().toLowerCase(),
                            "type", agent.getType().name()
                    );
                })
                .toList();
        return ResponseEntity.ok(agentInfos);
    }

    /**
     * Enhanced health check — exposes system status for monitoring.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "multi-agent-system");
        status.put("circuitBreaker", ollamaService.getCircuitBreakerState());
        status.put("activeConversations", conversationMemory.activeConversations());
        status.put("cacheSize", cache.size());
        status.put("agentsLoaded", agents.size());
        return ResponseEntity.ok(status);
    }

    /**
     * List available Ollama models — used by UI model selector.
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels() {
        var available = ollamaService.listModels();
        var loaded = ollamaService.getLoadedModels();
        return ResponseEntity.ok(Map.of(
                "available", available,
                "loaded", loaded
        ));
    }

    @ExceptionHandler(StrictModeException.class)
    public ResponseEntity<Map<String, String>> handleStrictMode(StrictModeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
