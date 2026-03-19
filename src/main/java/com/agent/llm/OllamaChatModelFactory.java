package com.agent.llm;

import com.agent.config.OllamaConfig;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached factory for LangChain4j Ollama models.
 *
 * Why a factory instead of a single bean:
 * - Per-agent model override (qwen2.5-coder:14b vs qwen2.5:3b)
 * - Per-agent context length (affects KV cache allocation in Ollama)
 * - User can select model from UI dropdown → per-request override
 *
 * Models are cached by (modelName, numCtx) so we don't recreate
 * the HTTP client on every request. Only unique configurations
 * create new instances.
 */
@Slf4j
public class OllamaChatModelFactory {

    private final OllamaConfig config;
    private final ConcurrentHashMap<String, OllamaStreamingChatModel> streamingCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OllamaChatModel> blockingCache = new ConcurrentHashMap<>();

    public OllamaChatModelFactory(OllamaConfig config) {
        this.config = config;
    }

    /**
     * Get a streaming chat model for the given configuration.
     * Cached by (modelName, numCtx) — reused across requests with same config.
     */
    public OllamaStreamingChatModel getStreamingModel(String modelName, OllamaService.LlmParams params) {
        String effectiveModel = (modelName != null && !modelName.isBlank()) ? modelName : config.getModel();
        String cacheKey = effectiveModel + ":" + params.contextLength() + ":" + params.maxTokens()
                + ":" + params.temperature();

        return streamingCache.computeIfAbsent(cacheKey, key -> {
            log.info("[Factory] Creating streaming model: {} (ctx={}, predict={})",
                    effectiveModel, params.contextLength(), params.maxTokens());
            return OllamaStreamingChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(effectiveModel)
                    .temperature(params.temperature())
                    .topK(params.topK())
                    .topP(params.topP())
                    .repeatPenalty(params.repeatPenalty())
                    .numPredict(params.maxTokens())
                    .numCtx(params.contextLength())
                    .timeout(Duration.ofSeconds(180))
                    .build();
        });
    }

    /**
     * Get a blocking (non-streaming) chat model for fast tasks like classification.
     */
    public OllamaChatModel getBlockingModel(String modelName, OllamaService.LlmParams params) {
        String effectiveModel = (modelName != null && !modelName.isBlank()) ? modelName : config.getModel();
        String cacheKey = effectiveModel + ":blocking:" + params.contextLength() + ":" + params.maxTokens();

        return blockingCache.computeIfAbsent(cacheKey, key -> {
            log.info("[Factory] Creating blocking model: {} (ctx={}, predict={})",
                    effectiveModel, params.contextLength(), params.maxTokens());
            return OllamaChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(effectiveModel)
                    .temperature(params.temperature())
                    .topK(params.topK())
                    .topP(params.topP())
                    .repeatPenalty(params.repeatPenalty())
                    .numPredict(params.maxTokens())
                    .numCtx(params.contextLength())
                    .timeout(Duration.ofSeconds(30))
                    .build();
        });
    }

    /**
     * Get the default model name from config.
     */
    public String getDefaultModel() {
        return config.getModel();
    }

    /**
     * Clear the model cache (useful after Ollama restart).
     */
    public void clearCache() {
        streamingCache.clear();
        blockingCache.clear();
        log.info("[Factory] Model cache cleared");
    }
}
