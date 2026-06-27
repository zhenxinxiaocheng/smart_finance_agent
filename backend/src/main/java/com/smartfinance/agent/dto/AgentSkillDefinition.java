package com.smartfinance.agent.dto;

import java.util.List;

public record AgentSkillDefinition(String skillKey,
                                   String name,
                                   String category,
                                   String description,
                                   String version,
                                   String author,
                                   String riskLevel,
                                   String inputSchema,
                                   String instructionText,
                                   List<String> boundTools) {
}
