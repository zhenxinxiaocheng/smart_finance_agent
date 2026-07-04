package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_schedule")
public class AgentSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String traceId;

    private String name;

    private String description;

    private String cronExpression;

    private String timezone;

    private String taskQuery;

    private Integer enabled;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lockUntil;

    private Integer runCount;

    private Integer consecutiveFailures;

    private String lastStatus;

    private String lastAnswer;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
