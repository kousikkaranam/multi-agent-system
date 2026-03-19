package com.agent.validator;

import com.agent.exception.StrictModeException;
import com.agent.model.AgentRequest;
import org.springframework.stereotype.Service;

@Service
public class StrictModeValidator {

    public void validate(AgentRequest request) {
        if (request.getInput() == null || request.getInput().isBlank()) {
            throw new StrictModeException("Input cannot be empty");
        }

        String trimmed = request.getInput().trim();

        if (trimmed.length() < 3) {
            throw new StrictModeException("Input too vague — provide at least 3 characters");
        }

        if (trimmed.length() > 10000) {
            throw new StrictModeException("Input too long — max 10,000 characters");
        }
    }
}
