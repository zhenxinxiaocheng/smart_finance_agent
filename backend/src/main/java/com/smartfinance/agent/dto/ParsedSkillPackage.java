package com.smartfinance.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ParsedSkillPackage {
    private String skillKey;
    private String name;
    private String description;
    private String version = "1.0.0";
    private String author = "unknown";
    private String category = "Installed Skill";
    private String riskLevel = "READ_ONLY";
    private String triggerText = "";
    private String inputSchema = "{}";
    private String instructionText;
    private List<String> boundTools = new ArrayList<>();
}
