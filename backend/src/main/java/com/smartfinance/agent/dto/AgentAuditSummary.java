package com.smartfinance.agent.dto;

import lombok.Data;

@Data
public class AgentAuditSummary {

    private int openReflections;

    private int pendingActions;

    private int failedSchedules;

    private int activeSchedules;
}
