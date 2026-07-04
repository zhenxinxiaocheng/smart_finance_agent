package com.smartfinance.agent.dto;

import lombok.Data;

@Data
public class AgentReflectionAcceptRequest {

    private String name;

    private String description;

    private String cronExpression;

    private String taskQuery;

    private String timezone;
}
