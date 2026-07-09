package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.AgentContextSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentContextSummaryMapper extends BaseMapper<AgentContextSummary> {

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND deleted = 0 " +
            "ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND deleted = 0 " +
            "AND scope = #{scope} ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestByScope(@Param("userId") Long userId, @Param("scope") String scope);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND deleted = 0 " +
            "AND scope IN ('CONVERSATION', 'MANUAL_COMPRESS') ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestConversationSummary(@Param("userId") Long userId);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND conversation_id = #{conversationId} AND deleted = 0 " +
            "AND scope IN ('CONVERSATION', 'MANUAL_COMPRESS') ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestConversationSummaryByConversation(@Param("userId") Long userId,
                                                                      @Param("conversationId") Long conversationId);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND deleted = 0 " +
            "AND scope = #{scope} AND source_hash = #{sourceHash} ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestByScopeAndSourceHash(@Param("userId") Long userId,
                                                         @Param("scope") String scope,
                                                         @Param("sourceHash") String sourceHash);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND conversation_id = #{conversationId} AND deleted = 0 " +
            "AND scope = #{scope} AND source_hash = #{sourceHash} ORDER BY updated_at DESC, id DESC LIMIT 1")
    AgentContextSummary selectLatestByScopeAndSourceHashByConversation(@Param("userId") Long userId,
                                                                       @Param("conversationId") Long conversationId,
                                                                       @Param("scope") String scope,
                                                                       @Param("sourceHash") String sourceHash);

    @Select("SELECT * FROM agent_context_summary WHERE user_id = #{userId} AND deleted = 0 " +
            "AND scope IN ('CONVERSATION', 'MANUAL_COMPRESS', 'TASK_STATE') ORDER BY updated_at DESC, id DESC LIMIT 3")
    List<AgentContextSummary> selectRecentCompressed(@Param("userId") Long userId);
}
