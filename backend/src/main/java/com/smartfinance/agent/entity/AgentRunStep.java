package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run_step")
public class AgentRunStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String traceId;

    private Integer stepNumber;

    private String summary;

    private String toolName;

    private String input;

    private Integer success;

    private String observationSummary;

    private String errorMessage;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
