package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_message WHERE user_id = #{userId} AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatMessage> selectRecentByUser(@Param("userId") Long userId,
                                         @Param("limit") int limit);

    @Select("""
            SELECT * FROM chat_message
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND deleted = 0
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectRecentByConversation(@Param("userId") Long userId,
                                                 @Param("conversationId") Long conversationId,
                                                 @Param("limit") int limit);

    @Select("""
            SELECT * FROM chat_message
            WHERE user_id = #{userId}
              AND deleted = 0
            ORDER BY CASE WHEN (trace_id = #{traceId} OR (#{traceId} IS NULL AND trace_id IS NULL)) THEN 0 ELSE 1 END, created_at DESC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectByTraceOrRecent(@Param("userId") Long userId,
                                            @Param("traceId") String traceId,
                                            @Param("limit") int limit);

    @Select("""
            SELECT * FROM chat_message
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND deleted = 0
            ORDER BY CASE WHEN (trace_id = #{traceId} OR (#{traceId} IS NULL AND trace_id IS NULL)) THEN 0 ELSE 1 END, created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatMessage> selectByTraceOrRecentAndConversation(@Param("userId") Long userId,
                                                           @Param("conversationId") Long conversationId,
                                                           @Param("traceId") String traceId,
                                                           @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM chat_message
            WHERE user_id = #{userId}
              AND deleted = 0
              AND conversation_id IS NULL
            """)
    Integer countOrphanMessages(@Param("userId") Long userId);

    @Update("""
            UPDATE chat_message
            SET conversation_id = #{conversationId}
            WHERE user_id = #{userId}
              AND deleted = 0
              AND conversation_id IS NULL
            """)
    int assignOrphanMessages(@Param("userId") Long userId,
                             @Param("conversationId") Long conversationId);

    @Update("""
            UPDATE chat_message
            SET deleted = 1
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND deleted = 0
            """)
    int softDeleteByConversation(@Param("userId") Long userId,
                                 @Param("conversationId") Long conversationId);
}
