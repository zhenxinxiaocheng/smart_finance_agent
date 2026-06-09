package com.smartfinance.agent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FallbackChatLanguageModel implements ChatLanguageModel {

    private static final String ANSWER = "AI 服务暂未配置可用的 DashScope API Key。账单导入、交易记录和统计功能仍可正常使用；如需使用智能助手，请先配置 DASHSCOPE_API_KEY。";

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        log.warn("DashScope chat model is unavailable; returning fallback response");
        return Response.from(AiMessage.from("{\"type\":\"final\",\"answer\":\"" + ANSWER + "\"}"));
    }
}
