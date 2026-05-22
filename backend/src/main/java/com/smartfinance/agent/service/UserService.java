package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.User;

public interface UserService {

    User register(String username, String password, String nickname, String email);

    User login(String username, String password);

    User getById(Long id);
}
