package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentSkillInstallRequest {

    @NotBlank
    private String sourceType;

    @NotBlank
    private String sourceUri;
}
