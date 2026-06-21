package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentMemoryRequest {

    @NotBlank(message = "记忆类型不能为空")
    private String memoryType;

    @NotBlank(message = "记忆键不能为空")
    private String memoryKey;

    @NotBlank(message = "记忆内容不能为空")
    private String memoryValue;
}
