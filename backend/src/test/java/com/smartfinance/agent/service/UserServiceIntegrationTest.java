package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.User;
import com.smartfinance.agent.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:user_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Test
    void register_shouldPersistUserWithHashedPasswordAndDefaultNickname() {
        User user = userService.register("alice", "123456", null, "alice@example.com");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getNickname()).isEqualTo("alice");
        assertThat(user.getPassword()).isNotEqualTo("123456");

        User saved = userMapper.selectById(user.getId());
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getDeleted()).isZero();
    }

    @Test
    void register_whenUsernameExists_shouldThrow() {
        userService.register("alice", "123456", "Alice", null);

        assertThatThrownBy(() -> userService.register("alice", "abcdef", "Alice Two", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void login_shouldReturnUserWhenPasswordMatches() {
        userService.register("alice", "123456", "Alice", null);

        User loggedIn = userService.login("alice", "123456");

        assertThat(loggedIn.getUsername()).isEqualTo("alice");
        assertThat(loggedIn.getNickname()).isEqualTo("Alice");
    }

    @Test
    void login_whenPasswordDoesNotMatch_shouldThrow() {
        userService.register("alice", "123456", "Alice", null);

        assertThatThrownBy(() -> userService.login("alice", "wrong-password"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
