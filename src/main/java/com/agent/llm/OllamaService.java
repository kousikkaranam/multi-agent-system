package com.agent.llm;

import com.agent.config.OllamaConfig;
import com.agent.resilience.CircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Ollama integration powered by LangChain4j.
 *
 * LangChain4j handles: HTTP client, streaming, JSON parsing, message formatting.
 * We handle: circuit breaker, timing logs (TTFT, tok/s), per-request model override.
 *
 * A minimal WebClient is kept ONLY for Ollama management APIs (/api/tags, /api/ps)
 * that LangChain4j does not abstract.
 */
@Slf4j
@Service
public class OllamaService {

    private final OllamaChatModelFactory modelFactory;
    private final OllamaConfig ollamaConfig;
    private final ExecutorService virtualExecutor;
    private final WebClient managementClient; // Only for /api/tags, /api/ps
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CircuitBreaker circuitBreaker = new CircuitBreaker("ollama", 3, Duration.ofSeconds(30));

    public OllamaService(OllamaChatModelFactory modelFactory, OllamaConfig ollamaConfig,
                          ExecutorService virtualThreadExecutor, WebClient ollamaManagementClient) {
        this.modelFactory = modelFactory;
        this.ollamaConfig = ollamaConfig;
        this.virtualExecutor = virtualThreadExecutor;
        this.managementClient = ollamaManagementClient;
    }

    // ─── Streaming Chat (LangChain4j) ───

    public Flux<String> streamChat(List<Map<String, String>> messages, LlmParams params, String model) {
        if (circuitBreaker.isOpen()) {
            return Flux.error(new CircuitBreaker.CircuitBreakerOpenException(
                    "Ollama is currently unreachable. Please try again in 30 seconds."));
        }

        String effectiveModel = (model != null && !model.isBlank()) ? model : ollamaConfig.getModel();

        // Get cached LangChain4j streaming model
        OllamaStreamingChatModel streamingModel = modelFactory.getStreamingModel(effectiveModel, params);

        // Convert our message format to LangChain4j format
        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = toLangChain4jMessages(messages);

        int promptChars = messages.stream().mapToInt(m -> m.getOrDefault("content", "").length()).sum();
        log.info("[LLM] Starting stream: model={}, num_ctx={}, num_predict={}, prompt_chars={}",
                effectiveModel, params.contextLength(), params.maxTokens(), promptChars);

        final long streamStart = System.currentTimeMillis();
        final long[] firstTokenTime = {0};
        final int[] tokenCount = {0};

        // Bridge LangChain4j's callback-based streaming to Reactor Flux
        return Flux.<String>create(sink -> {
            streamingModel.chat(lc4jMessages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    tokenCount[0]++;
                    if (firstTokenTime[0] == 0) {
                        firstTokenTime[0] = System.currentTimeMillis();
                        long ttft = firstTokenTime[0] - streamStart;
                        log.info("[LLM] First token in {}ms ({})", ttft,
                                ttft > 5000 ? "SLOW — possible model reload" : "OK");
                    }
                    sink.next(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    long totalMs = System.currentTimeMillis() - streamStart;
                    long ttft = firstTokenTime[0] > 0 ? firstTokenTime[0] - streamStart : 0;
                    long generationMs = totalMs - ttft;
                    double tokPerSec = tokenCount[0] > 0 && generationMs > 0
                            ? (tokenCount[0] * 1000.0 / generationMs) : 0;
                    log.info("[LLM] Stream complete: {}ms total, {}ms TTFT, {} tokens, {} tok/s",
                            totalMs, ttft, tokenCount[0], String.format("%.1f", tokPerSec));
                    try { circuitBreaker.execute(() -> true); } catch (Exception ignored) {}
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    log.error("[LLM] Stream error: {}", error.getMessage());
                    try { circuitBreaker.execute(() -> { throw new RuntimeException(error); }); }
                    catch (Exception ignored) {}
                    sink.error(error);
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER)
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(e -> {
                    if (e instanceof CircuitBreaker.CircuitBreakerOpenException) {
                        return Flux.just("[Ollama is temporarily unavailable. Please try again shortly.]");
                    }
                    return Flux.just("[Error generating response: " + e.getMessage() + "]");
                });
    }

    public Flux<String> streamChat(List<Map<String, String>> messages, LlmParams params) {
        return streamChat(messages, params, null);
    }

    public Flux<String> streamChat(List<Map<String, String>> messages) {
        return streamChat(messages, LlmParams.defaults());
    }

    // ─── Blocking Generate (LangChain4j) ───

    public String generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, null);
    }

    public String generate(String systemPrompt, String userPrompt, String model) {
        LlmParams fastParams = new LlmParams(64, 0.05, 0.5, 10, 1.0, 2048);
        String effectiveModel = (model != null && !model.isBlank()) ? model : ollamaConfig.getModel();

        OllamaChatModel blockingModel = modelFactory.getBlockingModel(effectiveModel, fastParams);

        return circuitBreaker.execute(() -> {
            var response = blockingModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            );
            return response.aiMessage().text();
        });
    }

    public CompletableFuture<String> generateAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(
                () -> generate(systemPrompt, userPrompt),
                virtualExecutor
        );
    }

    // ─── Message Conversion ───

    private List<dev.langchain4j.data.message.ChatMessage> toLangChain4jMessages(List<Map<String, String>> messages) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            switch (role) {
                case "system" -> result.add(SystemMessage.from(content));
                case "assistant" -> result.add(AiMessage.from(content));
                default -> result.add(UserMessage.from(content));
            }
        }
        return result;
    }

    // ─── Circuit Breaker Status ───

    public String getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    // ─── Ollama Management APIs (kept as WebClient — LangChain4j doesn't cover these) ───

    public List<Map<String, Object>> listModels() {
        try {
            String response = managementClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                var root = objectMapper.readTree(response);
                var models = root.get("models");
                List<Map<String, Object>> result = new ArrayList<>();
                if (models != null && models.isArray()) {
                    for (var m : models) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", m.get("name").asText());
                        info.put("size", m.has("size") ? m.get("size").asLong() : 0);
                        info.put("sizeHuman", formatSize(m.has("size") ? m.get("size").asLong() : 0));
                        info.put("family", m.has("details") && m.get("details").has("family")
                                ? m.get("details").get("family").asText() : "unknown");
                        info.put("parameterSize", m.has("details") && m.get("details").has("parameter_size")
                                ? m.get("details").get("parameter_size").asText() : "");
                        result.add(info);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to list models: {}", e.getMessage());
        }
        return List.of();
    }

    public List<Map<String, Object>> getLoadedModels() {
        try {
            String response = managementClient.get()
                    .uri("/api/ps")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                var root = objectMapper.readTree(response);
                var models = root.get("models");
                List<Map<String, Object>> result = new ArrayList<>();
                if (models != null && models.isArray()) {
                    for (var m : models) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", m.get("name").asText());
                        info.put("size", m.has("size") ? m.get("size").asLong() : 0);
                        info.put("sizeHuman", formatSize(m.has("size") ? m.get("size").asLong() : 0));
                        info.put("expiresAt", m.has("expires_at") ? m.get("expires_at").asText() : "");
                        result.add(info);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to get loaded models: {}", e.getMessage());
        }
        return List.of();
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "unknown";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format("%.1f GB", gb);
    }

    // ─── LLM Parameter Presets ───

    public record LlmParams(
            int maxTokens,
            double temperature,
            double topP,
            int topK,
            double repeatPenalty,
            int contextLength
    ) {
        public static LlmParams defaults() {
            return new LlmParams(2048, 0.7, 0.9, 40, 1.1, 8192);
        }

        public static LlmParams precise() {
            return new LlmParams(4096, 0.15, 0.85, 30, 1.15, 16384);
        }

        public static LlmParams classification() {
            return new LlmParams(32, 0.05, 0.5, 10, 1.0, 2048);
        }
    }
}
