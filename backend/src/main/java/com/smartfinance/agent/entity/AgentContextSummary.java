package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_context_summary")
public class AgentContextSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long conversationId;

    private String traceId;

    private String scope;

    private String summary;

    private String sourceRefs;

    private String sourceHash;

    private Long coveredFromMessageId;

    private Long coveredUntilMessageId;

    private Integer originalTokens;

    private Integer compressedTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
