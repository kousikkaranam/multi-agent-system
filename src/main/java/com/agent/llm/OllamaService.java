package com.agent.llm;

import com.agent.config.OllamaConfig;
import com.agent.resilience.CircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Production-grade Ollama integration.
 *
 * Architecture decisions:
 * 1. Circuit breaker prevents cascading failure when Ollama is down
 * 2. Exponential retry with jitter for transient failures
 * 3. Virtual thread scheduling for blocking operations
 * 4. Per-agent parameter tuning (temperature, tokens, context)
 * 5. Strict timeouts at every level
 *
 * The LLM is the bottleneck in this system. Every optimization here
 * directly translates to user-visible speed improvement.
 */
@Slf4j
@Service
public class OllamaService {

    private final WebClient ollamaWebClient;
    private final OllamaConfig ollamaConfig;
    private final ExecutorService virtualExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Circuit breaker: 3 failures → open for 30s
    private final CircuitBreaker circuitBreaker = new CircuitBreaker("ollama", 3, Duration.ofSeconds(30));

    // Retry: 2 retries with exponential backoff (500ms, 1s)
    private static final Retry RETRY_SPEC = Retry.backoff(2, Duration.ofMillis(500))
            .maxBackoff(Duration.ofSeconds(2))
            .filter(OllamaService::isRetryable)
            .doBeforeRetry(signal -> {
                // Using System.Logger since we can't use Slf4j in static context
                System.err.println("[OllamaService] Retry #" + (signal.totalRetries() + 1) +
                        " after: " + signal.failure().getMessage());
            });

    public OllamaService(WebClient ollamaWebClient, OllamaConfig ollamaConfig,
                          ExecutorService virtualThreadExecutor) {
        this.ollamaWebClient = ollamaWebClient;
        this.ollamaConfig = ollamaConfig;
        this.virtualExecutor = virtualThreadExecutor;
    }

    /**
     * Stream a chat response with full resilience, using a specific model.
     */
    public Flux<String> streamChat(List<Map<String, String>> messages, LlmParams params, String model) {
        return doStreamChat(messages, params, model);
    }

    /**
     * Stream a chat response with full resilience, using the default model.
     * This is the primary method — used for all agent responses.
     */
    public Flux<String> streamChat(List<Map<String, String>> messages, LlmParams params) {
        return doStreamChat(messages, params, null);
    }

    private Flux<String> doStreamChat(List<Map<String, String>> messages, LlmParams params, String modelOverride) {
        // Circuit breaker check (fail fast if Ollama is down)
        if (circuitBreaker.isOpen()) {
            return Flux.error(new CircuitBreaker.CircuitBreakerOpenException(
                    "Ollama is currently unreachable. Please try again in 30 seconds."));
        }

        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : ollamaConfig.getModel();
        Map<String, Object> body = buildRequestBody(messages, params, effectiveModel);
        final long streamStart = System.currentTimeMillis();
        final long[] firstTokenTime = {0}; // Track first token latency
        final int[] tokenCount = {0};

        // Log prompt size for context budget awareness
        int promptChars = messages.stream().mapToInt(m -> m.getOrDefault("content", "").length()).sum();
        log.info("[LLM] Starting stream: model={}, num_ctx={}, num_predict={}, prompt_chars={}",
                effectiveModel, params.contextLength(), params.maxTokens(), promptChars);

        return ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(this::extractChatToken)
                .doOnNext(token -> {
                    tokenCount[0]++;
                    if (firstTokenTime[0] == 0) {
                        firstTokenTime[0] = System.currentTimeMillis();
                        long ttft = firstTokenTime[0] - streamStart;
                        log.info("[LLM] First token in {}ms ({})", ttft,
                                ttft > 5000 ? "SLOW — possible model reload" : "OK");
                    }
                    circuitBreaker.execute(() -> true); // Record success
                })
                .doOnComplete(() -> {
                    long totalMs = System.currentTimeMillis() - streamStart;
                    long ttft = firstTokenTime[0] > 0 ? firstTokenTime[0] - streamStart : 0;
                    long generationMs = totalMs - ttft;
                    double tokPerSec = tokenCount[0] > 0 && generationMs > 0
                            ? (tokenCount[0] * 1000.0 / generationMs) : 0;
                    log.info("[LLM] Stream complete: {}ms total, {}ms TTFT, {} tokens, {} tok/s",
                            totalMs, ttft, tokenCount[0], String.format("%.1f", tokPerSec));
                })
                .retryWhen(RETRY_SPEC)
                .doOnError(e -> {
                    log.error("Ollama stream failed after retries: {}", e.getMessage());
                    try { circuitBreaker.execute(() -> { throw new RuntimeException(e); }); }
                    catch (Exception ignored) {}
                })
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(e -> {
                    if (e instanceof CircuitBreaker.CircuitBreakerOpenException) {
                        return Flux.just("[Ollama is temporarily unavailable. Please try again shortly.]");
                    }
                    return Flux.just("[Error generating response: " + e.getMessage() + "]");
                });
    }

    /**
     * Stream with default params.
     */
    public Flux<String> streamChat(List<Map<String, String>> messages) {
        return streamChat(messages, LlmParams.defaults());
    }

    /**
     * Blocking call for fast, short tasks (intent classification).
     * Runs on virtual thread to avoid blocking the reactor event loop.
     *
     * Uses aggressive params: low temperature, short output, small context.
     * Optionally uses a fast/small model for classification.
     */
    public String generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, null);
    }

    public String generate(String systemPrompt, String userPrompt, String model) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        // Fast params: minimal tokens, low temp, small context
        LlmParams fastParams = new LlmParams(64, 0.05, 0.5, 10, 1.0, 2048);

        return circuitBreaker.execute(() ->
                streamChat(messages, fastParams, model)
                        .collectList()
                        .map(tokens -> String.join("", tokens))
                        .block(Duration.ofSeconds(15)) // Hard timeout for classification
        );
    }

    /**
     * Async generate on virtual thread — for parallel operations.
     * Returns a CompletableFuture so callers can compose with other async work.
     */
    public CompletableFuture<String> generateAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(
                () -> generate(systemPrompt, userPrompt),
                virtualExecutor
        );
    }

    private Map<String, Object> buildRequestBody(List<Map<String, String>> messages, LlmParams params,
                                                     String model) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);

        // Keep model loaded in memory — avoids 10-30s reload penalty between requests.
        // "10m" keeps model hot for 10 minutes after last request.
        body.put("keep_alive", "10m");

        // Per-agent LLM tuning — this is where accuracy comes from
        Map<String, Object> options = new HashMap<>();
        options.put("num_predict", params.maxTokens());
        options.put("temperature", params.temperature());
        options.put("top_p", params.topP());
        options.put("top_k", params.topK());
        options.put("repeat_penalty", params.repeatPenalty());
        options.put("num_ctx", params.contextLength());

        // Offload as many layers as possible to GPU for speed
        // -1 = auto (Ollama will use all available VRAM)
        options.put("num_gpu", -1);

        body.put("options", options);

        return body;
    }

    private String extractChatToken(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            JsonNode message = node.get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        } catch (Exception e) {
            // Silently skip malformed lines — Ollama sometimes sends partial JSON
        }
        return null;
    }

    /**
     * Determine if an error is worth retrying.
     * Retry: connection errors, 5xx, timeouts
     * Don't retry: 4xx (our fault), circuit breaker open
     */
    private static boolean isRetryable(Throwable t) {
        if (t instanceof CircuitBreaker.CircuitBreakerOpenException) return false;
        if (t instanceof WebClientResponseException wce) {
            return wce.getStatusCode().is5xxServerError();
        }
        // Connection refused, timeout, etc — always retry
        return true;
    }

    // ─── Circuit breaker status (exposed for health check) ───

    public String getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * List available models from Ollama.
     * Returns model names, sizes, and which one is currently loaded.
     */
    public List<Map<String, Object>> listModels() {
        try {
            String response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                var root = objectMapper.readTree(response);
                var models = root.get("models");
                List<Map<String, Object>> result = new ArrayList<>();
                if (models != null && models.isArray()) {
                    for (var model : models) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", model.get("name").asText());
                        info.put("size", model.has("size") ? model.get("size").asLong() : 0);
                        info.put("sizeHuman", formatSize(model.has("size") ? model.get("size").asLong() : 0));
                        info.put("family", model.has("details") && model.get("details").has("family")
                                ? model.get("details").get("family").asText() : "unknown");
                        info.put("parameterSize", model.has("details") && model.get("details").has("parameter_size")
                                ? model.get("details").get("parameter_size").asText() : "");
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

    /**
     * Get currently loaded model(s) from Ollama.
     */
    public List<Map<String, Object>> getLoadedModels() {
        try {
            String response = ollamaWebClient.get()
                    .uri("/api/ps")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            if (response != null) {
                var root = objectMapper.readTree(response);
                var models = root.get("models");
                List<Map<String, Object>> result = new ArrayList<>();
                if (models != null && models.isArray()) {
                    for (var model : models) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", model.get("name").asText());
                        info.put("size", model.has("size") ? model.get("size").asLong() : 0);
                        info.put("sizeHuman", formatSize(model.has("size") ? model.get("size").asLong() : 0));
                        info.put("expiresAt", model.has("expires_at") ? model.get("expires_at").asText() : "");
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

    // ─── LLM Parameter presets ───

    /**
     * Per-agent tunable LLM parameters.
     * These are the knobs that control accuracy vs speed vs creativity.
     */
    public record LlmParams(
            int maxTokens,
            double temperature,
            double topP,
            int topK,
            double repeatPenalty,
            int contextLength
    ) {
        /** Balanced defaults */
        public static LlmParams defaults() {
            return new LlmParams(2048, 0.7, 0.9, 40, 1.1, 8192);
        }

        /** Precise — for code generation. Low temp = deterministic output */
        public static LlmParams precise() {
            return new LlmParams(4096, 0.15, 0.85, 30, 1.15, 16384);
        }

        /** Analytical — for finance, data-driven */
        public static LlmParams analytical() {
            return new LlmParams(2048, 0.2, 0.85, 35, 1.1, 8192);
        }

        /** Exploratory — for research, explanations */
        public static LlmParams exploratory() {
            return new LlmParams(3072, 0.35, 0.9, 40, 1.1, 12288);
        }

        /** Balanced — for general queries */
        public static LlmParams balanced() {
            return new LlmParams(2048, 0.5, 0.9, 40, 1.1, 8192);
        }

        /** Ultra-fast — for classification and routing only */
        public static LlmParams classification() {
            return new LlmParams(32, 0.05, 0.5, 10, 1.0, 2048);
        }
    }
}
