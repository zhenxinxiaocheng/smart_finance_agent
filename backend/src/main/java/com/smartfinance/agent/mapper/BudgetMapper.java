package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.Budget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {

    @Select("SELECT COALESCE(SUM(budget_amount), 0) FROM budget " +
            "WHERE user_id = #{userId} AND month = #{month} AND category = 'ALL' AND deleted = 0")
    BigDecimal getTotalBudget(@Param("userId") Long userId, @Param("month") String month);

    @Select("SELECT COALESCE(SUM(budget_amount), 0) FROM budget " +
            "WHERE user_id = #{userId} AND month = #{month} AND category != 'ALL' AND deleted = 0")
    BigDecimal getCategoryBudgetsSum(@Param("userId") Long userId, @Param("month") String month);
}
