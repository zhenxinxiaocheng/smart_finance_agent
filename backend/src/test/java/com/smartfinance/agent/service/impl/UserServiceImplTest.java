package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.User;
import com.smartfinance.agent.mapper.ExpenseCategoryMapper;
import com.smartfinance.agent.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private ExpenseCategoryMapper categoryMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void register_whenUsernameExists_shouldThrow() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(IllegalArgumentException.class,
                () -> userService.register("alice", "123456", "Alice", "alice@example.com"));
    }

    @Test
    void register_shouldHashPasswordAndFallbackNickname() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        userService.register("alice", "123456", null, "alice@example.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User inserted = captor.getValue();

        assertEquals("alice", inserted.getUsername());
        assertEquals("alice", inserted.getNickname());
        assertEquals("alice@example.com", inserted.getEmail());
        assertNotEquals("123456", inserted.getPassword());
    }

    @Test
    void login_withRightPassword_shouldReturnUser() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        userService.register("alice", "123456", "Alice", "alice@example.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User stored = captor.getValue();

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stored);
        User loginUser = userService.login("alice", "123456");

        assertEquals("alice", loginUser.getUsername());
    }

    @Test
    void login_withWrongPassword_shouldThrow() {
        User stored = new User();
        stored.setUsername("alice");
        stored.setPassword("invalid-hash");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stored);

        assertThrows(IllegalArgumentException.class, () -> userService.login("alice", "wrong"));
    }

    @Test
    void getById_whenMissing_shouldThrow() {
        when(userMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> userService.getById(99L));
    }
}
