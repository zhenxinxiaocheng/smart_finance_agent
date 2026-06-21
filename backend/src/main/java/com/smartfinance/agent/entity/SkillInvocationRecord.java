package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_invocation_record")
public class SkillInvocationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String traceId;

    private String skillName;

    private String category;

    private String input;

    private Integer success;

    private String summary;

    private String rawResult;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
