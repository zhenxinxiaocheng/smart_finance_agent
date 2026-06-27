package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.AgentSkillDefinition;
import com.smartfinance.agent.dto.AgentSkillInstallRequest;
import com.smartfinance.agent.entity.AgentSkill;

import java.util.Collection;
import java.util.List;

public interface AgentSkillService {

    void syncBuiltInSkills(Long userId, Collection<AgentSkillDefinition> definitions);

    String buildEnabledSkillManifest(Long userId);

    String buildEnabledSkillManifest(Long userId, Collection<AgentSkillDefinition> definitions);

    boolean isEnabled(Long userId, String skillKey);

    AgentSkill resolveInvocationSkill(Long userId, String toolName, String requestedSkillKey);

    List<AgentSkill> list(Long userId);

    AgentSkill detail(Long userId, Long id);

    AgentSkill install(Long userId, AgentSkillInstallRequest request);

    AgentSkill setEnabled(Long userId, Long id, boolean enabled);

    void delete(Long userId, Long id);
}
