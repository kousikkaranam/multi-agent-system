package com.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String token;
    private AgentType agentType;
    private boolean done;
    private String error;

    public static AgentResponse token(String token, AgentType agentType) {
        return AgentResponse.builder()
                .token(token)
                .agentType(agentType)
                .done(false)
                .build();
    }

    public static AgentResponse done(AgentType agentType) {
        return AgentResponse.builder()
                .token("")
                .agentType(agentType)
                .done(true)
                .build();
    }

    public static AgentResponse error(String error) {
        return AgentResponse.builder()
                .error(error)
                .done(true)
                .build();
    }
}
