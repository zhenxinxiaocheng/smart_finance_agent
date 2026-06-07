package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.ChatRequest;
import com.smartfinance.agent.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public Result<Map<String, String>> chat(@RequestAttribute Long userId,
                                            @Valid @RequestBody ChatRequest request) {
        String response = chatService.chat(userId, request.getMessage());
        return Result.success(Map.of("response", response));
    }

    @PostMapping(value = "/react/stream", produces = "text/event-stream")
    public SseEmitter reactStream(@RequestAttribute Long userId,
                                  @Valid @RequestBody ChatRequest request) {
        return chatService.streamReactChat(userId, request.getMessage());
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(
            @RequestAttribute Long userId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(chatService.getChatHistory(userId, limit));
    }
}
