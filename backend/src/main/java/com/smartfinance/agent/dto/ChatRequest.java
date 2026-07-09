package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatRequest {

    @NotNull(message = "conversationId不能为空")
    private Long conversationId;

    @NotBlank(message = "消息内容不能为空")
    private String message;
}
