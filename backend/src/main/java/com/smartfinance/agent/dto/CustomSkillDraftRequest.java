package com.smartfinance.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class CustomSkillDraftRequest {

    private String skillKey;

    private String name;

    private String description;

    private String triggerText;

    private String instructionText;

    private List<String> boundTools;

    private String category;

    private String riskLevel;
}
