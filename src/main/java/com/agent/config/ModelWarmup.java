package com.agent.config;

import com.agent.agent.Agent;
import com.agent.agent.ConfigurableAgent;
import com.agent.llm.OllamaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Pre-warms Ollama models on application startup.
 *
 * IMPORTANT: Warmup runs SEQUENTIALLY, not in parallel.
 * Only one model can occupy VRAM at a time (12GB limit).
 * Parallel warmup causes thrashing: load A → evict A → load B → evict B → reload A.
 *
 * Strategy:
 * - Warm the PRIMARY model last (so it stays loaded for the first real request)
 * - Use the SAME context length the agent will use (avoids KV cache realloc reload)
 * - Small models (3B) warm first, then the big model stays resident
 */
@Slf4j
@Component
public class ModelWarmup {

    private final OllamaService ollamaService;
    private final OllamaConfig ollamaConfig;
    private final List<Agent> agents;
    private final ExecutorService virtualExecutor;

    public ModelWarmup(OllamaService ollamaService, OllamaConfig ollamaConfig,
                       List<Agent> agents, ExecutorService virtualThreadExecutor) {
        this.ollamaService = ollamaService;
        this.ollamaConfig = ollamaConfig;
        this.agents = agents;
        this.virtualExecutor = virtualThreadExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupModels() {
        // Collect unique models with their context lengths (largest ctx wins per model)
        Map<String, Integer> modelCtx = new LinkedHashMap<>();

        for (Agent agent : agents) {
            if (agent instanceof ConfigurableAgent ca) {
                String model = ca.getDefinition().getModel();
                int ctx = ca.getDefinition().getContextLength();
                if (model != null && !model.isBlank()) {
                    modelCtx.merge(model, ctx, Math::max);
                }
            }
        }

        if (modelCtx.isEmpty()) {
            modelCtx.put(ollamaConfig.getModel(), 8192);
        }

        log.info("[Warmup] Pre-warming {} models sequentially: {}", modelCtx.size(), modelCtx);

        // Run on a single virtual thread — sequential warmup, non-blocking to main thread
        virtualExecutor.submit(() -> {
            // Warm the PRIMARY (largest) model LAST so it stays resident in VRAM
            var entries = modelCtx.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue()) // smallest ctx first
                    .toList();

            for (var entry : entries) {
                warmupSingleModel(entry.getKey(), entry.getValue());
            }

            log.info("[Warmup] All models warmed. Last loaded stays in VRAM.");
        });
    }

    private void warmupSingleModel(String model, int contextLength) {
        long start = System.currentTimeMillis();
        try {
            // Use a LlmParams that matches the real agent's context length.
            // This ensures Ollama allocates the right KV cache size upfront,
            // so the first real request doesn't trigger a reload.
            var params = new OllamaService.LlmParams(
                    16,         // minimal output
                    0.05,
                    0.5,
                    10,
                    1.0,
                    contextLength  // match the agent's real context length
            );

            var messages = List.of(
                    Map.of("role", "system", "content", "Reply OK."),
                    Map.of("role", "user", "content", "ping")
            );

            ollamaService.streamChat(messages, params, model)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(60));

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Warmup] Model '{}' (ctx={}) ready in {}ms ({})",
                    model, contextLength, elapsed,
                    elapsed > 5000 ? "cold load" : "already warm");
        } catch (Exception e) {
            log.warn("[Warmup] Failed to warm '{}': {} — will load on first request",
                    model, e.getMessage());
        }
    }
}
