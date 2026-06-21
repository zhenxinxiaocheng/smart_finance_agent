package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    @Select("SELECT * FROM agent_memory WHERE user_id = #{userId} AND memory_type = #{memoryType} " +
            "AND memory_key = #{memoryKey} AND deleted = 0 LIMIT 1")
    AgentMemory selectActiveByKey(@Param("userId") Long userId,
                                  @Param("memoryType") String memoryType,
                                  @Param("memoryKey") String memoryKey);

    @Select("SELECT * FROM agent_memory WHERE user_id = #{userId} AND memory_type = #{memoryType} " +
            "AND memory_key = #{memoryKey} LIMIT 1")
    AgentMemory selectByKeyIncludingDeleted(@Param("userId") Long userId,
                                            @Param("memoryType") String memoryType,
                                            @Param("memoryKey") String memoryKey);

    @Update("UPDATE agent_memory SET memory_value = #{memory.memoryValue}, confidence = #{memory.confidence}, " +
            "source_query = #{memory.sourceQuery}, disabled = #{memory.disabled}, deleted = 0, " +
            "updated_at = CURRENT_TIMESTAMP WHERE id = #{memory.id} AND user_id = #{memory.userId}")
    int restoreOrUpdateById(@Param("memory") AgentMemory memory);

    @Select("SELECT * FROM agent_memory WHERE user_id = #{userId} AND deleted = 0 " +
            "ORDER BY disabled ASC, updated_at DESC")
    List<AgentMemory> selectByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM agent_memory WHERE user_id = #{userId} AND disabled = 0 AND deleted = 0 " +
            "AND memory_key NOT IN ('_AUTO_MEMORY_ENABLED', '_SKIP_TOOL_ASSISTED_MEMORY', '_CUSTOM_INSTRUCTIONS') " +
            "ORDER BY confidence DESC, updated_at DESC LIMIT #{limit}")
    List<AgentMemory> selectActiveForAgent(@Param("userId") Long userId,
                                           @Param("limit") int limit);

    @Update("UPDATE agent_memory SET deleted = 1 WHERE user_id = #{userId} AND deleted = 0")
    int softDeleteByUser(@Param("userId") Long userId);
}
