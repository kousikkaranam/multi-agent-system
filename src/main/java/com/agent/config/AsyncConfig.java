package com.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executors;

/**
 * Configures async handling for Spring MVC with virtual threads.
 * Fixes the "SimpleAsyncTaskExecutor is not suitable for production" warning.
 *
 * Uses Java 21 virtual threads — lightweight, perfect for streaming LLM responses.
 */
@Configuration
public class AsyncConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Use virtual threads for async request handling (streaming responses)
        AsyncTaskExecutor executor = new TaskExecutorAdapter(
                Executors.newVirtualThreadPerTaskExecutor());
        configurer.setTaskExecutor(executor);
        // 3 minutes timeout for LLM streaming responses
        configurer.setDefaultTimeout(180_000);
    }
}
