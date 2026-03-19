package com.agent.config;

import com.agent.llm.OllamaChatModelFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j integration configuration.
 * Creates the model factory that provides cached Ollama chat models.
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public OllamaChatModelFactory ollamaChatModelFactory(OllamaConfig ollamaConfig) {
        return new OllamaChatModelFactory(ollamaConfig);
    }
}
