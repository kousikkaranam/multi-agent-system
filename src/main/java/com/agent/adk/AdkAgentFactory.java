package com.agent.adk;

import com.agent.agent.Agent;
import com.agent.agent.ConfigurableAgent;
import com.agent.llm.OllamaChatModelFactory;
import com.agent.llm.OllamaService;
import com.agent.model.AgentType;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates ADK LlmAgents backed by LangChain4j Ollama models.
 *
 * This bridges three layers:
 *   Your ConfigurableAgent (JSON config) → ADK LlmAgent → LangChain4j OllamaModel → Ollama
 *
 * ADK agents get:
 * - Instructions from your existing agent JSON configs
 * - LLM connectivity via LangChain4j → Ollama
 * - Tool registration (for future ADK-native tool execution)
 * - Sub-agent support (for future agent-to-agent delegation)
 *
 * Your custom pipeline (IntentRouter, ContextGatherer, PromptEnhancer, ToolExecutor)
 * still runs OUTSIDE ADK — ADK handles only the LLM interaction layer.
 */
@Slf4j
@Component
public class AdkAgentFactory {

    private final OllamaChatModelFactory modelFactory;
    private final List<Agent> agents;
    private final ConcurrentHashMap<AgentType, LlmAgent> adkAgentCache = new ConcurrentHashMap<>();

    public AdkAgentFactory(OllamaChatModelFactory modelFactory, List<Agent> agents) {
        this.modelFactory = modelFactory;
        this.agents = agents;

        log.info("[ADK] Agent factory initialized with {} agents", agents.size());
    }

    /**
     * Get or create an ADK LlmAgent for the given agent type.
     * The ADK agent wraps the LangChain4j model from the existing ConfigurableAgent config.
     */
    public LlmAgent getAdkAgent(AgentType type) {
        return adkAgentCache.computeIfAbsent(type, this::createAdkAgent);
    }

    /**
     * Get or create an ADK LlmAgent with a user-selected model override.
     * Returns a fresh agent (not cached) since the model is dynamic.
     */
    public LlmAgent getAdkAgent(AgentType type, String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return getAdkAgent(type);
        }
        // Dynamic model → build a fresh ADK agent (not cached)
        return buildAdkAgent(type, modelOverride);
    }

    private LlmAgent createAdkAgent(AgentType type) {
        return buildAdkAgent(type, null);
    }

    private LlmAgent buildAdkAgent(AgentType type, String modelOverride) {
        // Find the matching ConfigurableAgent for this type
        ConfigurableAgent configAgent = agents.stream()
                .filter(a -> a.getType() == type && a instanceof ConfigurableAgent)
                .map(a -> (ConfigurableAgent) a)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No agent config for type: " + type));

        var def = configAgent.getDefinition();
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride
                : (def.getModel() != null ? def.getModel() : "qwen2.5-coder:14b");

        // Get LangChain4j streaming model from factory
        OllamaStreamingChatModel streamingModel = modelFactory.getStreamingModel(
                model, configAgent.getParams());

        // Wrap LangChain4j model in ADK's bridge
        LangChain4j adkModel = new LangChain4j(streamingModel);

        // Build ADK LlmAgent with the existing agent's configuration
        LlmAgent adkAgent = LlmAgent.builder()
                .name(def.getName())
                .model(adkModel)
                .instruction(configAgent.getSystemPrompt())
                .build();

        log.info("[ADK] Created agent: {} (type={}, model={})", def.getName(), type, model);
        return adkAgent;
    }

    /**
     * Get all available agent types.
     */
    public Map<AgentType, String> getAvailableAgents() {
        Map<AgentType, String> available = new java.util.LinkedHashMap<>();
        for (Agent agent : agents) {
            if (agent instanceof ConfigurableAgent ca) {
                available.put(agent.getType(), ca.getDefinition().getName());
            }
        }
        return available;
    }
}
