package com.agent.agent;

import com.agent.llm.OllamaService;
import com.agent.model.AgentDefinition;
import com.agent.model.AgentType;
import com.agent.model.ChatMessage;
import com.agent.tool.ToolExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Universal agent engine driven by JSON config.
 *
 * CTO REWRITE — key changes:
 *
 * 1. LAYERED PROMPTS instead of one massive blob:
 *    Layer 0: Core identity (tiny — who you are)
 *    Layer 1: Rules (only if defined)
 *    Layer 2: Tool descriptions (only if workspace connected)
 *    Layer 3: Response format (minimal)
 *    Layer 4: Anti-hallucination guard
 *
 * 2. MINIMAL PROMPTS for local models:
 *    A 14B model performs WORSE with a 2000-token system prompt.
 *    We keep it under 800 tokens — lean and focused.
 *
 * 3. NO CHAIN-OF-THOUGHT IN SYSTEM PROMPT:
 *    CoT makes local models output "Step 1: Restate..." garbage.
 *    Instead, we rely on the ContextGatherer to pre-fetch real data,
 *    and the model just synthesizes from facts.
 *
 * 4. FORCED TOOL AWARENESS:
 *    When workspace context is injected, the model knows it has REAL DATA
 *    and doesn't need to guess. This is the biggest accuracy multiplier.
 */
@Slf4j
public class ConfigurableAgent implements Agent {

    private final OllamaService ollamaService;
    @Getter
    private final AgentDefinition definition;
    private final String coreSystemPrompt;

    public ConfigurableAgent(OllamaService ollamaService, AgentDefinition definition) {
        this.ollamaService = ollamaService;
        this.definition = definition;
        this.coreSystemPrompt = compileSystemPrompt(definition);
        log.info("Agent loaded: {} (type={}, model={}, temp={})",
                definition.getName(), definition.getType(),
                definition.getModel() != null ? definition.getModel() : "default",
                definition.getTemperature());
    }

    @Override
    public AgentType getType() {
        return definition.getType();
    }

    @Override
    public String getSystemPrompt() {
        return coreSystemPrompt;
    }

    @Override
    public OllamaService.LlmParams getParams() {
        return new OllamaService.LlmParams(
                definition.getMaxTokens(),
                definition.getTemperature(),
                definition.getTopP(),
                definition.getTopK(),
                definition.getRepeatPenalty(),
                definition.getContextLength()
        );
    }

    @Override
    public Flux<String> execute(String input, List<ChatMessage> history) {
        List<Map<String, String>> messages = buildMessages(input, history);
        return ollamaService.streamChat(messages, getParams(), definition.getModel());
    }

    @Override
    public Flux<String> execute(String input, List<ChatMessage> history, String modelOverride) {
        List<Map<String, String>> messages = buildMessages(input, history);
        // User-selected model takes priority over agent's configured model
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : definition.getModel();
        return ollamaService.streamChat(messages, getParams(), model);
    }

    /**
     * Compile a LEAN system prompt from the definition.
     * Target: under 800 tokens. Every word must earn its place.
     */
    private String compileSystemPrompt(AgentDefinition def) {
        StringBuilder prompt = new StringBuilder();

        // Layer 0: Core identity (1-2 sentences)
        prompt.append(def.getSystemPrompt()).append("\n\n");

        // Layer 1: Rules (if defined — keep them short)
        if (def.getRules() != null && !def.getRules().isEmpty()) {
            prompt.append("RULES:\n");
            for (String rule : def.getRules()) {
                prompt.append("- ").append(rule).append("\n");
            }
            prompt.append("\n");
        }

        // Layer 2: Response format (minimal — just the essential instruction)
        if (def.getOutputFormat() != null) {
            prompt.append(switch (def.getOutputFormat()) {
                case MARKDOWN -> "Format: Use Markdown with headings and code blocks.\n";
                case CODE -> "Format: Respond primarily with code. Minimal explanation.\n";
                case BULLETS -> "Format: Use bullet points.\n";
                case JSON -> "Format: Return structured JSON.\n";
                case PLAIN -> "";
            });
        }

        // Layer 3: Response depth (one line)
        if (def.getResponseLevel() != null) {
            prompt.append(switch (def.getResponseLevel()) {
                case CONCISE -> "Be concise and direct. 1-3 sentences for simple questions.\n";
                case DETAILED -> "Be thorough but not verbose. Use structure for complex topics.\n";
                case EXHAUSTIVE -> "Provide comprehensive coverage with examples and edge cases.\n";
            });
        }

        // Layer 4: Anti-hallucination guard (CRITICAL for accuracy)
        prompt.append("\nCRITICAL: Only state facts you are certain about. ");
        prompt.append("If workspace context is provided below, base your answer on that real data. ");
        prompt.append("Never make up file contents, code, or project structure.\n");

        // Layer 5: Few-shot examples (if defined — very effective for local models)
        if (def.getExamples() != null && !def.getExamples().isEmpty()) {
            prompt.append("\nEXAMPLES:\n");
            for (AgentDefinition.FewShotExample ex : def.getExamples()) {
                prompt.append("User: ").append(ex.getUserInput()).append("\n");
                prompt.append("Assistant: ").append(ex.getExpectedOutput()).append("\n\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Build the messages array for Ollama's /api/chat.
     *
     * The system prompt is assembled at call time:
     *   core prompt + tool descriptions (only if workspace connected)
     *
     * This means non-workspace queries get a SMALLER prompt = faster + more accurate.
     */
    private List<Map<String, String>> buildMessages(String input, List<ChatMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        // System prompt: core only (tool descriptions are injected by orchestrator if needed)
        messages.add(Map.of("role", "system", "content", coreSystemPrompt));

        // Conversation history (already windowed by ConversationMemory)
        if (history != null) {
            for (ChatMessage msg : history) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }

        // Current user input (may include injected workspace context from ContextGatherer)
        messages.add(Map.of("role", "user", "content", input));

        return messages;
    }
}
