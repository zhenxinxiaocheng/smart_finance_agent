package com.smartfinance.agent.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public record ContextBundle(List<ChatMessage> messages,
                            ContextUsageSnapshot usage,
                            List<ContextBlock> selectedBlocks,
                            List<ContextBlock> compressedBlocks,
                            List<ContextBlock> droppedBlocks) {
}
