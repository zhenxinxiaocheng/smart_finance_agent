package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
