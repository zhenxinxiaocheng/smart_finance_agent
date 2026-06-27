package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.AgentSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentSkillMapper extends BaseMapper<AgentSkill> {

    @Select("SELECT * FROM agent_skill WHERE user_id = #{userId} AND deleted = 0 ORDER BY built_in DESC, category ASC, name ASC")
    List<AgentSkill> selectByUser(Long userId);

    @Select("SELECT * FROM agent_skill WHERE user_id = #{userId} AND source_type = #{sourceType} AND source_uri = #{sourceUri} AND skill_key = #{skillKey} LIMIT 1")
    AgentSkill selectByUserAndSource(Long userId, String sourceType, String sourceUri, String skillKey);

    @Select("SELECT * FROM agent_skill WHERE user_id = #{userId} AND skill_key = #{skillKey} AND deleted = 0 LIMIT 1")
    AgentSkill selectByUserAndKey(Long userId, String skillKey);
}
