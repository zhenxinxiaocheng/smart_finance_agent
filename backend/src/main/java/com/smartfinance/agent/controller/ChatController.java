package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.ChatConversationRequest;
import com.smartfinance.agent.dto.ChatRequest;
import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.service.ChatConversationService;
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
    private final ChatConversationService conversationService;

    public ChatController(ChatService chatService,
                          ChatConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    @GetMapping("/conversations")
    public Result<List<ChatConversation>> conversations(@RequestAttribute Long userId) {
        return Result.success(conversationService.list(userId));
    }

    @PostMapping("/conversations")
    public Result<ChatConversation> createConversation(@RequestAttribute Long userId,
                                                       @RequestBody(required = false) ChatConversationRequest request) {
        return Result.success(conversationService.create(userId, request == null ? null : request.getTitle()));
    }

    @PatchMapping("/conversations/{id}")
    public Result<ChatConversation> renameConversation(@RequestAttribute Long userId,
                                                       @PathVariable Long id,
                                                       @RequestBody(required = false) ChatConversationRequest request) {
        return Result.success(conversationService.rename(userId, id, request == null ? null : request.getTitle()));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@RequestAttribute Long userId,
                                           @PathVariable Long id) {
        conversationService.delete(userId, id);
        return Result.success();
    }

    @PostMapping
    public Result<Map<String, String>> chat(@RequestAttribute Long userId,
                                            @Valid @RequestBody ChatRequest request) {
        String response = chatService.chat(userId, request.getConversationId(), request.getMessage());
        return Result.success(Map.of("response", response));
    }

    @PostMapping(value = "/react/stream", produces = "text/event-stream")
    public SseEmitter reactStream(@RequestAttribute Long userId,
                                  @Valid @RequestBody ChatRequest request) {
        return chatService.streamReactChat(userId, request.getConversationId(), request.getMessage());
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(
            @RequestAttribute Long userId,
            @RequestParam Long conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(chatService.getChatHistory(userId, conversationId, limit));
    }
}
