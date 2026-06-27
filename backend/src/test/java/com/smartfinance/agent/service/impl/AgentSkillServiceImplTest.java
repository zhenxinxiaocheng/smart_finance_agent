package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.SkillPackageParser;
import com.smartfinance.agent.dto.AgentSkillDefinition;
import com.smartfinance.agent.dto.AgentSkillInstallRequest;
import com.smartfinance.agent.dto.ParsedSkillPackage;
import com.smartfinance.agent.entity.AgentSkill;
import com.smartfinance.agent.mapper.AgentSkillMapper;
import com.smartfinance.agent.service.SkillPackageSourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSkillServiceImplTest {

    @Mock
    private AgentSkillMapper mapper;
    @Mock
    private SkillPackageParser parser;
    @Mock
    private SkillPackageSourceProvider githubProvider;

    private AgentSkillServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentSkillServiceImpl(mapper, parser, List.of(githubProvider));
    }

    @Test
    void syncBuiltInSkills_shouldCreateMissingSkillsAndNotDuplicateExistingOnes() {
        AgentSkillDefinition definition = new AgentSkillDefinition(
                "get_total_expense",
                "Total Expense",
                "Finance Query",
                "Read total expense",
                "1.0.0",
                "system",
                "READ_ONLY",
                "{}",
                "Use for total expense queries",
                List.of("get_total_expense"));
        AgentSkill existing = new AgentSkill();
        existing.setSkillKey("get_total_income");
        when(mapper.selectByUserAndSource(1L, "BUILT_IN", "builtin", "get_total_expense")).thenReturn(null);

        service.syncBuiltInSkills(1L, List.of(definition));

        ArgumentCaptor<AgentSkill> captor = ArgumentCaptor.forClass(AgentSkill.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getSkillKey()).isEqualTo("get_total_expense");
        assertThat(captor.getValue().getEnabled()).isEqualTo(1);
        assertThat(captor.getValue().getBuiltIn()).isEqualTo(1);
        verify(mapper, never()).updateById(existing);
    }

    @Test
    void buildEnabledSkillManifest_shouldHideDisabledSkills() {
        AgentSkill enabled = skill("get_total_expense", "Total Expense", 1);
        AgentSkill disabled = skill("search_web", "Web Search", 0);
        when(mapper.selectByUser(1L)).thenReturn(List.of(enabled, disabled));

        String manifest = service.buildEnabledSkillManifest(1L);

        assertThat(manifest).contains("Total Expense");
        assertThat(manifest).doesNotContain("Web Search");
    }

    @Test
    void install_shouldUseSourceProviderAndUpsertBySourceIdentity() {
        AgentSkillInstallRequest request = new AgentSkillInstallRequest();
        request.setSourceType("GITHUB");
        request.setSourceUri("https://github.com/example/finance-skill");

        ParsedSkillPackage parsed = new ParsedSkillPackage();
        parsed.setSkillKey("finance-review");
        parsed.setName("Finance Review");
        parsed.setDescription("Review finances");
        parsed.setVersion("0.1.0");
        parsed.setAuthor("example");
        parsed.setCategory("finance");
        parsed.setRiskLevel("READ_ONLY");
        parsed.setInstructionText("Use when reviewing finances");
        parsed.setBoundTools(List.of("get_monthly_summary"));

        when(githubProvider.supports("GITHUB")).thenReturn(true);
        when(githubProvider.fetch(request.getSourceUri())).thenReturn(Path.of("downloaded"));
        when(githubProvider.resolveVersion(request.getSourceUri())).thenReturn("main");
        when(parser.parse(Path.of("downloaded"))).thenReturn(parsed);
        when(mapper.selectByUserAndSource(1L, "GITHUB", request.getSourceUri(), "finance-review")).thenReturn(null);

        AgentSkill installed = service.install(1L, request);

        assertThat(installed.getSkillKey()).isEqualTo("finance-review");
        assertThat(installed.getSourceType()).isEqualTo("GITHUB");
        assertThat(installed.getSourceUri()).isEqualTo(request.getSourceUri());
        assertThat(installed.getBuiltIn()).isZero();
        verify(mapper).insert(any(AgentSkill.class));
    }

    @Test
    void resolveInvocationSkill_shouldAllowRequestedSkillBoundToTool() {
        AgentSkill skill = skill("monthly-review", "Monthly Review", 1);
        skill.setSourceType("GITHUB");
        skill.setBoundTools("get_monthly_summary,get_expense_by_category");
        when(mapper.selectByUserAndKey(1L, "monthly-review")).thenReturn(skill);

        AgentSkill resolved = service.resolveInvocationSkill(1L, "get_monthly_summary", "monthly-review");

        assertThat(resolved.getSkillKey()).isEqualTo("monthly-review");
    }

    @Test
    void resolveInvocationSkill_shouldRejectRequestedSkillNotBoundToTool() {
        AgentSkill skill = skill("pdf-helper", "PDF Helper", 1);
        skill.setBoundTools("get_recent_transactions");
        when(mapper.selectByUserAndKey(1L, "pdf-helper")).thenReturn(skill);

        assertThatThrownBy(() -> service.resolveInvocationSkill(1L, "search_web", "pdf-helper"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not bound");
    }

    private AgentSkill skill(String key, String name, int enabled) {
        AgentSkill skill = new AgentSkill();
        skill.setUserId(1L);
        skill.setSkillKey(key);
        skill.setName(name);
        skill.setCategory("Finance Query");
        skill.setDescription(name + " description");
        skill.setRiskLevel("READ_ONLY");
        skill.setInputSchema("{}");
        skill.setInstructionText("Use " + name);
        skill.setBoundTools(key);
        skill.setEnabled(enabled);
        skill.setDeleted(0);
        return skill;
    }
}
