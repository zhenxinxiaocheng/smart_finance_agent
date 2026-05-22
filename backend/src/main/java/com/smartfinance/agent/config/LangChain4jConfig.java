package com.smartfinance.agent.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.dashscope.chat-model.temperature}")
    private Double temperature;

    @Bean
    public OpenAiChatModel openAiChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}
