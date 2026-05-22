package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_message WHERE user_id = #{userId} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<ChatMessage> selectRecentByUser(@Param("userId") Long userId,
                                         @Param("limit") int limit);
}
