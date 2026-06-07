package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.BudgetAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BudgetAlertMapper extends BaseMapper<BudgetAlert> {

    @Select("SELECT * FROM budget_alert WHERE user_id = #{userId} AND is_read = 0 AND deleted = 0 ORDER BY created_at DESC")
    List<BudgetAlert> findUnreadAlerts(@Param("userId") Long userId);

    @Select("SELECT * FROM budget_alert WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<BudgetAlert> findRecentAlerts(@Param("userId") Long userId, @Param("limit") int limit);
}
