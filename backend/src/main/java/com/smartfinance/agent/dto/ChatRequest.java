package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChatRequest {

    @NotNull(message = "conversationId不能为空")
    private Long conversationId;

    @NotBlank(message = "消息内容不能为空")
    private String message;

    @Pattern(regexp = "fast|deep", message = "思考模式无效")
    private String thinkingMode;

    public Boolean thinkingOverride() {
        return thinkingMode == null ? null : "deep".equals(thinkingMode);
    }
}
