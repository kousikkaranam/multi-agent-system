package com.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ollama configuration.
 *
 * LangChain4j now handles the heavy HTTP lifting (streaming, retries, connection pooling).
 * This config provides:
 * 1. Properties (baseUrl, model, etc.) used by LangChain4jConfig + OllamaChatModelFactory
 * 2. Virtual thread executor for parallel I/O
 * 3. Minimal WebClient ONLY for Ollama management APIs (/api/tags, /api/ps)
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class OllamaConfig {

    private String baseUrl = "http://localhost:11434";
    private String model = "qwen2.5-coder:14b";
    private int maxTokens = 2048;
    private double temperature = 0.7;

    /**
     * Virtual thread executor — Java 21+ Project Loom.
     * Each virtual thread costs ~1KB vs ~1MB for platform threads.
     * Used for: tool execution, context gathering, async LLM calls.
     */
    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Minimal WebClient for Ollama management APIs only.
     * LangChain4j handles all chat/streaming — this is just for /api/tags and /api/ps.
     */
    @Bean
    public WebClient ollamaManagementClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
