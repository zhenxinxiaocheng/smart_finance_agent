package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.dto.AgentMemoryPreferencesRequest;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.entity.AgentMemory;

import java.util.List;

public interface AgentMemoryService {

    List<AgentMemory> list(Long userId);

    AgentMemory createManual(Long userId, AgentMemoryRequest request);

    AgentMemoryPreferencesResponse getPreferences(Long userId);

    AgentMemoryPreferencesResponse updatePreferences(Long userId, AgentMemoryPreferencesRequest request);

    boolean upsertAutoMemory(Long userId,
                             String memoryType,
                             String memoryKey,
                             String memoryValue,
                             double confidence,
                             String sourceQuery);

    AgentMemory setDisabled(Long userId, Long memoryId, boolean disabled);

    void delete(Long userId, Long memoryId);

    void reset(Long userId);

    boolean isAutoMemoryEnabled(Long userId);

    boolean shouldSkipToolAssistedMemory(Long userId);

    String buildAgentContext(Long userId);
}
