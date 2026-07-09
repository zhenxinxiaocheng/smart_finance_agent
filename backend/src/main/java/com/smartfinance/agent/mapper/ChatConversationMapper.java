package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    @Select("""
            SELECT * FROM chat_conversation
            WHERE user_id = #{userId}
              AND deleted = 0
            ORDER BY updated_at DESC, created_at DESC, id DESC
            """)
    List<ChatConversation> selectActiveByUser(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE user_id = #{userId}
              AND title = '历史对话'
              AND deleted = 0
            ORDER BY id ASC
            LIMIT 1
            """)
    ChatConversation selectHistoricalConversation(@Param("userId") Long userId);

    @Update("""
            UPDATE chat_conversation
            SET deleted = 1
            WHERE id = #{conversationId}
              AND user_id = #{userId}
              AND deleted = 0
            """)
    int softDeleteOwned(@Param("userId") Long userId,
                        @Param("conversationId") Long conversationId);
}
