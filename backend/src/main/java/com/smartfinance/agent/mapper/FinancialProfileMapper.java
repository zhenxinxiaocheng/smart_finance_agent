package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.FinancialProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinancialProfileMapper extends BaseMapper<FinancialProfile> {

    @Select("SELECT * FROM financial_profile WHERE user_id = #{userId} LIMIT 1")
    FinancialProfile selectByUserId(Long userId);
}
