package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.DailyExchangeRate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DailyExchangeRateMapper extends BaseMapper<DailyExchangeRate> {
}
