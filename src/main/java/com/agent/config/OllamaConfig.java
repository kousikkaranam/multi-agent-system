package com.agent.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Production-grade Ollama configuration.
 *
 * Key decisions:
 * - Virtual thread executor for all blocking I/O (Java 21 Project Loom)
 * - Connection pooling with keep-alive to avoid TCP handshake per request
 * - Aggressive timeouts to fail fast instead of hanging
 * - Large buffer for streaming responses
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

    // Connection pool settings
    private int maxConnections = 10;
    private int pendingAcquireMaxCount = 20;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(180);
    private Duration idleTimeout = Duration.ofSeconds(30);

    /**
     * Virtual thread executor — Java 21's killer feature.
     * Each virtual thread costs ~1KB vs ~1MB for platform threads.
     * Perfect for I/O-bound work: Ollama calls, file reads, tool execution.
     */
    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Production WebClient with:
     * - Connection pooling (reuse TCP connections to Ollama)
     * - Timeouts at every level (connect, read, write)
     * - Large buffer for streaming LLM responses
     */
    @Bean
    public WebClient ollamaWebClient() {
        // Connection pool: reuse connections instead of opening new ones per request
        ConnectionProvider pool = ConnectionProvider.builder("ollama-pool")
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(pendingAcquireMaxCount)
                .maxIdleTime(idleTimeout)
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        // HTTP client with timeouts
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeout.toSeconds(), TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)))
                .responseTimeout(readTimeout);

        // Exchange strategies: 32MB buffer for large streaming responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
