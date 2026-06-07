package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("analysis_record")
public class AnalysisRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String query;
    private String plan;
    private String stepsResult;
    private String finalAnswer;
    private Integer score;
    private String feedback;
    private Integer tokensUsed;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
