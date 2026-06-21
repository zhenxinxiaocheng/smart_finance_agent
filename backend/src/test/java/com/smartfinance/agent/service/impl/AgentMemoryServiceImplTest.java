package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.AgentMemoryPreferencesRequest;
import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.mapper.AgentMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryServiceImplTest {

    @Mock
    private AgentMemoryMapper agentMemoryMapper;

    private AgentMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentMemoryServiceImpl(agentMemoryMapper);
        lenient().when(agentMemoryMapper.selectActiveByKey(1L, "AGENT_PREFERENCE", "_AUTO_MEMORY_ENABLED"))
                .thenReturn(null);
    }

    @Test
    void list_shouldHideSettingsMemories() {
        AgentMemory setting = memory("AGENT_PREFERENCE", "_CUSTOM_INSTRUCTIONS", "short answers");
        AgentMemory normal = memory("RESPONSE_STYLE", "brevity", "answer briefly");
        when(agentMemoryMapper.selectByUser(1L)).thenReturn(List.of(setting, normal));

        assertThat(service.list(1L)).containsExactly(normal);
    }

    @Test
    void updatePreferences_shouldSaveEditableInstructionsAndSwitches() {
        AgentMemoryPreferencesRequest request = new AgentMemoryPreferencesRequest();
        request.setCustomInstructions("用中文回答，尽量简短");
        request.setAutoMemoryEnabled(false);
        request.setSkipToolAssistedMemory(true);

        var response = service.updatePreferences(1L, request);

        assertThat(response.getCustomInstructions()).isEqualTo("用中文回答，尽量简短");
        assertThat(response.isAutoMemoryEnabled()).isFalse();
        assertThat(response.isSkipToolAssistedMemory()).isTrue();

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(agentMemoryMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AgentMemory::getMemoryKey)
                .contains("_CUSTOM_INSTRUCTIONS", "_AUTO_MEMORY_ENABLED", "_SKIP_TOOL_ASSISTED_MEMORY");
    }

    @Test
    void updatePreferences_shouldRestoreSoftDeletedInstructions() {
        AgentMemory deleted = memory("AGENT_PREFERENCE", "_CUSTOM_INSTRUCTIONS", "old");
        deleted.setId(99L);
        deleted.setDeleted(1);
        when(agentMemoryMapper.selectByKeyIncludingDeleted(1L, "AGENT_PREFERENCE", "_CUSTOM_INSTRUCTIONS"))
                .thenReturn(deleted);

        AgentMemoryPreferencesRequest request = new AgentMemoryPreferencesRequest();
        request.setCustomInstructions("咖啡归为餐饮");

        var response = service.updatePreferences(1L, request);

        assertThat(response.getCustomInstructions()).isEqualTo("咖啡归为餐饮");
        assertThat(deleted.getDeleted()).isZero();
        assertThat(deleted.getMemoryValue()).isEqualTo("咖啡归为餐饮");
        verify(agentMemoryMapper).restoreOrUpdateById(deleted);
        verify(agentMemoryMapper, never()).updateById(deleted);
    }

    @Test
    void upsertAutoMemory_shouldCreateLowRiskMemory() {
        when(agentMemoryMapper.selectByKeyIncludingDeleted(1L, "CATEGORY_PREFERENCE", "coffee"))
                .thenReturn(null);

        boolean saved = service.upsertAutoMemory(1L, "CATEGORY_PREFERENCE", "coffee",
                "咖啡归为餐饮", 0.92, "以后咖啡算餐饮");

        assertThat(saved).isTrue();
        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(agentMemoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getMemoryType()).isEqualTo("CATEGORY_PREFERENCE");
        assertThat(captor.getValue().getMemoryKey()).isEqualTo("coffee");
        assertThat(captor.getValue().getMemoryValue()).isEqualTo("咖啡归为餐饮");
        assertThat(captor.getValue().getDisabled()).isZero();
    }

    @Test
    void upsertAutoMemory_shouldSkipWhenAutoMemoryDisabled() {
        when(agentMemoryMapper.selectActiveByKey(1L, "AGENT_PREFERENCE", "_AUTO_MEMORY_ENABLED"))
                .thenReturn(memory("AGENT_PREFERENCE", "_AUTO_MEMORY_ENABLED", "false"));

        boolean saved = service.upsertAutoMemory(1L, "CATEGORY_PREFERENCE", "coffee",
                "咖啡归为餐饮", 0.92, "以后咖啡算餐饮");

        assertThat(saved).isFalse();
        verify(agentMemoryMapper, never()).insert(any());
        verify(agentMemoryMapper, never()).updateById(any());
    }

    @Test
    void upsertAutoMemory_shouldUpdateExistingMemoryForSameKey() {
        AgentMemory existing = memory("RESPONSE_STYLE", "brevity", "回答简短");
        existing.setId(9L);
        when(agentMemoryMapper.selectByKeyIncludingDeleted(1L, "RESPONSE_STYLE", "brevity"))
                .thenReturn(existing);

        boolean saved = service.upsertAutoMemory(1L, "RESPONSE_STYLE", "brevity",
                "回答尽量简短", 0.88, "以后短一点");

        assertThat(saved).isTrue();
        assertThat(existing.getMemoryValue()).isEqualTo("回答尽量简短");
        verify(agentMemoryMapper).restoreOrUpdateById(existing);
        verify(agentMemoryMapper, never()).insert(any());
    }

    @Test
    void upsertAutoMemory_shouldRejectSensitiveOrLowConfidenceMemory() {
        assertThat(service.upsertAutoMemory(1L, "CATEGORY_PREFERENCE", "bank",
                "银行卡号是 6222020202020202", 0.95, "记住我的银行卡号")).isFalse();
        assertThat(service.upsertAutoMemory(1L, "CATEGORY_PREFERENCE", "coffee",
                "咖啡归为餐饮", 0.50, "可能咖啡算餐饮")).isFalse();
        assertThat(service.upsertAutoMemory(1L, "ASSET_AMOUNT", "asset",
                "资产 100000", 0.95, "我的资产")).isFalse();

        verify(agentMemoryMapper, never()).insert(any());
        verify(agentMemoryMapper, never()).updateById(any());
    }

    @Test
    void buildAgentContext_shouldFormatEditableInstructionsAndActiveMemoriesForPrompt() {
        AgentMemory instructions = memory("AGENT_PREFERENCE", "_CUSTOM_INSTRUCTIONS", "用中文回答，尽量简短");
        AgentMemory category = memory("CATEGORY_PREFERENCE", "coffee", "咖啡归为餐饮");
        when(agentMemoryMapper.selectActiveByKey(1L, "AGENT_PREFERENCE", "_CUSTOM_INSTRUCTIONS"))
                .thenReturn(instructions);
        when(agentMemoryMapper.selectActiveForAgent(1L, 10)).thenReturn(List.of(category));

        String context = service.buildAgentContext(1L);

        assertThat(context).contains("用户可编辑的 Agent 长期指令");
        assertThat(context).contains("用中文回答，尽量简短");
        assertThat(context).contains("自动沉淀的 Agent 长期记忆");
        assertThat(context).contains("咖啡归为餐饮");
    }

    @Test
    void reset_shouldSoftDeleteAllUserMemories() {
        service.reset(1L);

        verify(agentMemoryMapper).softDeleteByUser(1L);
    }

    private AgentMemory memory(String type, String key, String value) {
        AgentMemory memory = new AgentMemory();
        memory.setUserId(1L);
        memory.setMemoryType(type);
        memory.setMemoryKey(key);
        memory.setMemoryValue(value);
        memory.setConfidence(0.9);
        memory.setDisabled(0);
        return memory;
    }
}
