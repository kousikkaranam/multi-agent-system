package com.agent.enhancer;

import com.agent.model.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Zero-cost prompt enhancer — NO LLM calls.
 *
 * CTO INSIGHT:
 * The old enhancer made an LLM call to rewrite vague queries.
 * Problems:
 *   1. Added 2-5 seconds of latency
 *   2. The rewrite often CHANGED the user's intent (semantic drift)
 *   3. The rewrite was sometimes longer than the final answer
 *   4. For a 14B model, rewriting prompts is a waste of its capacity
 *
 * THE FIX: Template-based enhancement. Zero LLM calls.
 * We add structure and specificity through templates, not through another LLM pass.
 * The ContextGatherer handles the real heavy lifting (gathering actual data).
 *
 * This enhancement is additive — it never changes the user's original words,
 * only appends structure hints for the LLM to follow.
 */
@Slf4j
@Service
public class PromptEnhancer {

    private static final int VAGUE_THRESHOLD = 30; // chars — below this, always enhance

    /**
     * Enhance queries with structural hints based on agent type.
     * ZERO LLM calls. Pure string template. Sub-microsecond execution.
     *
     * Short queries (< 30 chars): always add structure hints.
     * Longer queries: add depth hints for RESEARCH to ensure detailed analysis.
     */
    public String enhance(String input, AgentType agentType) {
        String enhanced = input;

        if (input.length() < VAGUE_THRESHOLD) {
            // Short/vague query — add basic structure
            enhanced = switch (agentType) {
                case CODE -> input + "\n\nProvide working code with brief explanation. Use proper syntax.";
                case RESEARCH -> input + "\n\nGive a detailed, factual explanation. Structure with clear headings. Reference actual code and file names from the workspace context.";
                case FINANCE -> input + "\n\nProvide factual financial analysis with numbers where applicable.";
                case GENERAL -> input;
            };
        } else if (agentType == AgentType.RESEARCH) {
            // Longer RESEARCH query — add depth instructions
            enhanced = input + "\n\nIMPORTANT: Go deep. If workspace source code is provided below, " +
                    "analyze the actual code — reference real class names, method signatures, and explain " +
                    "how data flows through the system. Do NOT give a surface-level overview when you have " +
                    "real source code to analyze. Structure your response with: Architecture Overview, " +
                    "Component Breakdown (with actual code references), and Data Flow.";
        }

        if (!enhanced.equals(input)) {
            log.debug("Enhanced: '{}' → +structure hints", input.substring(0, Math.min(30, input.length())));
        }

        return enhanced;
    }
}
