package com.agent.config;

import com.agent.agent.Agent;
import com.agent.agent.ConfigurableAgent;
import com.agent.llm.OllamaService;
import com.agent.model.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans the /agents folder for JSON files and creates ConfigurableAgent instances.
 * Drop a new .json file → restart → new agent is live.
 */
@Slf4j
@Configuration
public class AgentLoader {

    @Value("${agents.config-dir:agents}")
    private String agentsDir;

    @Bean
    public List<Agent> agents(OllamaService ollamaService) {
        List<Agent> agents = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        Path agentsPath = Paths.get(agentsDir);
        if (!Files.exists(agentsPath)) {
            log.warn("Agents config directory not found: {}. Creating it...", agentsPath.toAbsolutePath());
            try {
                Files.createDirectories(agentsPath);
            } catch (IOException e) {
                log.error("Failed to create agents directory: {}", e.getMessage());
            }
            return agents;
        }

        try (Stream<Path> files = Files.list(agentsPath)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            AgentDefinition def = mapper.readValue(file.toFile(), AgentDefinition.class);
                            applyDefaults(def);
                            agents.add(new ConfigurableAgent(ollamaService, def));
                            log.info("Loaded agent '{}' from {}", def.getName(), file.getFileName());
                        } catch (IOException e) {
                            log.error("Failed to load agent from {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to scan agents directory: {}", e.getMessage());
        }

        if (agents.isEmpty()) {
            log.warn("No agent configs found in {}. Create .json files to add agents.", agentsPath.toAbsolutePath());
        }

        log.info("Loaded {} agents total", agents.size());
        return agents;
    }

    /**
     * Apply sensible defaults for any fields not specified in JSON.
     */
    private void applyDefaults(AgentDefinition def) {
        if (def.getTemperature() == 0) def.setTemperature(0.7);
        if (def.getTopP() == 0) def.setTopP(0.9);
        if (def.getTopK() == 0) def.setTopK(40);
        if (def.getRepeatPenalty() == 0) def.setRepeatPenalty(1.1);
        if (def.getMaxTokens() == 0) def.setMaxTokens(4096);
        if (def.getContextLength() == 0) def.setContextLength(16384);
        if (def.getResponseLevel() == null) def.setResponseLevel(AgentDefinition.ResponseLevel.DETAILED);
        if (def.getOutputFormat() == null) def.setOutputFormat(AgentDefinition.OutputFormat.MARKDOWN);
    }
}
