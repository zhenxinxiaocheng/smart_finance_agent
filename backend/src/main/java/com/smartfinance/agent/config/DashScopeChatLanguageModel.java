package com.smartfinance.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Text chat adapter for DashScope parameters unavailable in LangChain4j 0.35. */
@Slf4j
public class DashScopeChatLanguageModel implements ChatLanguageModel {
    private final RestClient client;
    private final String modelName;
    private final double temperature;
    private final boolean enableThinking;

    public DashScopeChatLanguageModel(String baseUrl, String apiKey, String modelName,
                                     double temperature, boolean enableThinking, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.modelName = modelName;
        this.temperature = temperature;
        this.enableThinking = enableThinking;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        long started = System.nanoTime();
        boolean success = false;
        Boolean requestedMode = ChatModelThinkingContext.current();
        boolean thinking = requestedMode == null ? enableThinking : requestedMode;
        try {
            JsonNode body = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", modelName, "temperature", temperature,
                            "enable_thinking", thinking, "stream", false,
                            "messages", messages.stream().map(this::toMessage).toList()))
                    .retrieve().onStatus(status -> status.isError(), (request, response) -> {
                        // Do not propagate upstream bodies, which may echo private inputs.
                        throw new IllegalStateException("Chat model HTTP status " + response.getStatusCode().value());
                    }).body(JsonNode.class);
            JsonNode choice = body == null ? null : body.path("choices").path(0);
            JsonNode content = choice == null ? null : choice.path("message").path("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("Chat model returned no answer");
            }
            FinishReason reason = switch (choice.path("finish_reason").asText()) {
                case "stop" -> FinishReason.STOP;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                default -> null;
            };
            JsonNode usage = body.path("usage");
            TokenUsage tokens = usage.isObject() ? new TokenUsage(tokenCount(usage, "prompt_tokens"),
                    tokenCount(usage, "completion_tokens"), tokenCount(usage, "total_tokens")) : null;
            success = true;
            return Response.from(AiMessage.from(content.asText()), tokens, reason);
        } finally {
            log.info("Chat model call: model={}, thinking={}, elapsedMs={}, success={}",
                    modelName, thinking, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), success);
        }
    }

    private Integer tokenCount(JsonNode usage, String field) {
        return usage.path(field).isIntegralNumber() ? usage.path(field).intValue() : null;
    }

    private Map<String, String> toMessage(ChatMessage message) {
        if (message instanceof SystemMessage system) {
            return Map.of("role", "system", "content", system.text());
        }
        if (message instanceof UserMessage user && user.hasSingleText()) {
            return user.name() == null
                    ? Map.of("role", "user", "content", user.singleText())
                    : Map.of("role", "user", "content", user.singleText(), "name", user.name());
        }
        if (message instanceof AiMessage ai && !ai.hasToolExecutionRequests() && ai.text() != null) {
            return Map.of("role", "assistant", "content", ai.text());
        }
        throw new IllegalArgumentException("Chat adapter requires text messages");
    }
}
