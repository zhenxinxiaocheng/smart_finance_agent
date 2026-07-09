package com.smartfinance.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageMapperTest {

    @Test
    void selectRecentByUser_shouldIgnoreDeletedMessages() throws Exception {
        Method method = ChatMessageMapper.class.getMethod("selectRecentByUser", Long.class, int.class);
        Select select = method.getAnnotation(Select.class);

        assertThat(String.join(" ", select.value()).toLowerCase())
                .contains("deleted = 0");
    }
}
