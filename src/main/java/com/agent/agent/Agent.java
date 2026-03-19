package com.agent.agent;

import com.agent.llm.OllamaService;
import com.agent.model.AgentType;
import com.agent.model.ChatMessage;
import reactor.core.publisher.Flux;

import java.util.List;

public interface Agent {

    AgentType getType();

    String getSystemPrompt();

    OllamaService.LlmParams getParams();

    Flux<String> execute(String input, List<ChatMessage> history);

    /**
     * Execute with a user-selected model override.
     * If modelOverride is null/blank, uses the agent's configured model.
     */
    default Flux<String> execute(String input, List<ChatMessage> history, String modelOverride) {
        return execute(input, history);
    }
}
