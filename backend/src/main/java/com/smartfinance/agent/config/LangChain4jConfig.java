package com.smartfinance.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class LangChain4jConfig {

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.dashscope.chat-model.temperature}")
    private Double temperature;

    @Value("${langchain4j.dashscope.chat-model.enable-thinking:false}")
    private boolean enableThinking;

    @Value("${langchain4j.dashscope.chat-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${langchain4j.dashscope.chat-model.timeout:120s}")
    private Duration timeout;

    @Bean
    public ChatLanguageModel openAiChatModel() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY is empty; chat model will use local fallback");
            return new FallbackChatLanguageModel();
        }
        return new DashScopeChatLanguageModel(baseUrl, apiKey, modelName, temperature, enableThinking, timeout);
    }
}
