package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_skill")
public class AgentSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String skillKey;

    private String name;

    private String description;

    private String version;

    private String author;

    private String category;

    private String riskLevel;

    private String inputSchema;

    private String triggerText;

    private String instructionText;

    private String boundTools;

    private String sourceType;

    private String sourceUri;

    private String sourceVersion;

    private Integer enabled;

    private Integer builtIn;

    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
