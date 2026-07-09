package com.smartfinance.agent.agent;

import com.smartfinance.agent.dto.AgentSkillDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolSelectionServiceTest {

    @Test
    void manifest_shouldExposeOnlyRelevantBuiltInToolsForCurrentQuery() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.builtInSkillDefinitions()).thenReturn(List.of(
                definition("get_total_expense", "财务查询"),
                definition("search_web", "联网搜索"),
                definition("record_transaction", "记账辅助"),
                definition("create_agent_schedule", "Agent 自动化")
        ));
        when(registry.manifest(eq(1L), org.mockito.ArgumentMatchers.anyCollection())).thenReturn("selected manifest");

        ToolSelectionService service = new ToolSelectionService(registry);

        String manifest = service.manifest(1L, "我这个月消费情况如何？");

        assertThat(manifest).isEqualTo("selected manifest");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AgentSkillDefinition>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(registry).manifest(eq(1L), captor.capture());
        assertThat(captor.getValue()).extracting(AgentSkillDefinition::skillKey)
                .contains("get_total_expense")
                .doesNotContain("search_web", "record_transaction", "create_agent_schedule");
    }

    @Test
    void manifest_shouldExposeSearchToolForRealtimeMarketQueries() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.builtInSkillDefinitions()).thenReturn(List.of(
                definition("get_total_expense", "财务查询"),
                definition("search_web", "联网搜索")
        ));
        when(registry.manifest(eq(1L), org.mockito.ArgumentMatchers.anyCollection())).thenReturn("search manifest");

        ToolSelectionService service = new ToolSelectionService(registry);

        service.manifest(1L, "今天纳指100有什么新闻？");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AgentSkillDefinition>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(registry).manifest(eq(1L), captor.capture());
        assertThat(captor.getValue()).extracting(AgentSkillDefinition::skillKey).contains("search_web");
    }

    private AgentSkillDefinition definition(String key, String category) {
        return new AgentSkillDefinition(key, key, category, key + " description",
                "1.0.0", "system", "READ_ONLY", "{}", key + " instruction", List.of(key));
    }
}
