package com.smartfinance.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface FinancialAiService {

    String chat(@MemoryId Long memoryId, @UserMessage String message);
}
