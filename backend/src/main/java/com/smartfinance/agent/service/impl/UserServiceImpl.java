package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.common.DefaultCategories;
import com.smartfinance.agent.entity.ExpenseCategory;
import com.smartfinance.agent.entity.User;
import com.smartfinance.agent.mapper.ExpenseCategoryMapper;
import com.smartfinance.agent.mapper.UserMapper;
import com.smartfinance.agent.service.UserService;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final ExpenseCategoryMapper categoryMapper;

    public UserServiceImpl(UserMapper userMapper, ExpenseCategoryMapper categoryMapper) {
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public User register(String username, String password, String nickname, String email) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setEmail(email);
        userMapper.insert(user);

        // 为新用户初始化默认消费分类
        List<ExpenseCategory> defaults = DefaultCategories.getDefaults();
        for (ExpenseCategory cat : defaults) {
            cat.setUserId(user.getId());
            categoryMapper.insert(cat);
        }

        return user;
    }

    @Override
    public User login(String username, String password) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!verifyPassword(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return user;
    }

    @Override
    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    private String hashPassword(String password) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hashedPassword = md.digest(password.getBytes());

            byte[] saltAndHash = new byte[salt.length + hashedPassword.length];
            System.arraycopy(salt, 0, saltAndHash, 0, salt.length);
            System.arraycopy(hashedPassword, 0, saltAndHash, salt.length, hashedPassword.length);

            return Base64.getEncoder().encodeToString(saltAndHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("加密算法不可用", e);
        }
    }

    private boolean verifyPassword(String inputPassword, String storedHash) {
        try {
            byte[] saltAndHash = Base64.getDecoder().decode(storedHash);
            byte[] salt = new byte[16];
            byte[] storedHashed = new byte[saltAndHash.length - 16];
            System.arraycopy(saltAndHash, 0, salt, 0, salt.length);
            System.arraycopy(saltAndHash, salt.length, storedHashed, 0, storedHashed.length);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] inputHashed = md.digest(inputPassword.getBytes());

            return MessageDigest.isEqual(storedHashed, inputHashed);
        } catch (Exception e) {
            return false;
        }
    }
}
