package com.smartfinance.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DashScopeChatLanguageModelTest {
    @Test
    void sendsThinkingSwitchAtTopLevelAndPreservesConversationAndUsage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = """
                    {"choices":[{"message":{"content":"你好","reasoning_content":"internal"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            for (boolean thinking : List.of(false, true)) {
                var model = new DashScopeChatLanguageModel("http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1",
                        "test-key", "test-model", 0.4, false, Duration.ofSeconds(2));
                dev.langchain4j.model.output.Response<AiMessage> response;
                try (var ignored = ChatModelThinkingContext.override(thinking)) {
                    response = model.generate(List.of(SystemMessage.from("中文回复"),
                            UserMessage.from("hi"), AiMessage.from("你好"), UserMessage.from("继续")));
                }
                assertNull(ChatModelThinkingContext.current());
                assertEquals(thinking, request.get().path("enable_thinking").booleanValue());
                assertFalse(request.get().path("stream").asBoolean());
                assertEquals("test-model", request.get().path("model").asText());
                assertEquals("assistant", request.get().path("messages").get(2).path("role").asText());
                assertEquals("继续", request.get().path("messages").get(3).path("content").asText());
                assertEquals("你好", response.content().text());
                assertEquals(12, response.tokenUsage().totalTokenCount());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void upstreamFailureMustNotExposeResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "private prompt and provider details".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new DashScopeChatLanguageModel("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "test-model", 0.4, false, Duration.ofSeconds(2));
            var error = assertThrows(IllegalStateException.class,
                    () -> model.generate(List.of(UserMessage.from("hi"))));
            assertEquals("Chat model HTTP status 429", error.getMessage());
            assertNull(error.getCause());
        } finally {
            server.stop(0);
        }
    }
}
