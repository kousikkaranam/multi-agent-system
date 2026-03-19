package com.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Full agent configuration — loaded from JSON files in /agents folder.
 * You control everything: prompts, params, rules, behavior, output format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    // ── Identity ──
    private String name;                    // e.g. "code-wizard"
    private AgentType type;                 // CODE, FINANCE, RESEARCH, GENERAL

    // ── Model Selection ──
    private String model;                   // Ollama model override (e.g. "qwen2.5-coder:14b")
                                            // If null/empty, uses the default from application.yml

    // ── Core Prompt ──
    private String systemPrompt;            // Your custom system prompt
    private List<String> rules;             // Custom rules appended to prompt
    private List<FewShotExample> examples;  // Few-shot examples for better output

    // ── Response Control ──
    private ResponseLevel responseLevel;    // CONCISE, DETAILED, EXHAUSTIVE
    private OutputFormat outputFormat;       // MARKDOWN, CODE, BULLETS, JSON, PLAIN

    // ── LLM Parameters ──
    private double temperature;             // 0.0 (precise) to 1.0 (creative)
    private double topP;                    // nucleus sampling (0.0 to 1.0)
    private int topK;                       // top-k sampling
    private double repeatPenalty;           // penalize repetition (1.0 = none, 1.5 = strong)
    private int maxTokens;                  // max response length
    private int contextLength;              // context window size (up to 32768 for qwen2.5-coder)

    // ── Behavior Toggles ──
    private boolean chainOfThought;         // force step-by-step reasoning
    private boolean selfVerify;             // agent checks its own output before returning
    private boolean enhancePrompt;          // rewrite vague queries before processing

    // ── Routing Keywords ──
    private List<String> keywords;          // keywords that trigger this agent in routing

    /**
     * Few-shot example: teach the model by showing input→output pairs.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FewShotExample {
        private String userInput;
        private String expectedOutput;
    }

    /**
     * How detailed the response should be.
     */
    public enum ResponseLevel {
        CONCISE,     // 1-3 sentences, direct answer
        DETAILED,    // structured with explanations
        EXHAUSTIVE   // deep dive, covers all angles
    }

    /**
     * Output formatting preference.
     */
    public enum OutputFormat {
        MARKDOWN,    // headings, bold, code blocks
        CODE,        // primarily code with minimal explanation
        BULLETS,     // bullet point lists
        JSON,        // structured JSON output
        PLAIN        // plain text, no formatting
    }
}
