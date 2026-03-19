package com.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    @NotBlank(message = "Input cannot be empty")
    @Size(min = 1, message = "Input must be at least 3 characters")
    private String input;

    private String conversationId;

    /** Optional model override from UI. If set, overrides the agent's default model. */
    private String model;
}
