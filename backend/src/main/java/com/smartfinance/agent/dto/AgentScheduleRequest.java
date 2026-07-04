package com.smartfinance.agent.dto;

import lombok.Data;

@Data
public class AgentScheduleRequest {

    private String name;

    private String description;

    private String cronExpression;

    private String timezone;

    private String taskQuery;
}
