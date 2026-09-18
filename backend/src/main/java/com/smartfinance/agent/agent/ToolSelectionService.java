package com.smartfinance.agent.agent;

import org.springframework.stereotype.Service;

@Service
public class ToolSelectionService {
    private final ToolRegistry toolRegistry;
    public ToolSelectionService(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; }
    public String manifest(Long userId, String userMessage) {
        // User settings determine availability; wording must not hide capabilities.
        return toolRegistry.manifest(userId);
    }
}
