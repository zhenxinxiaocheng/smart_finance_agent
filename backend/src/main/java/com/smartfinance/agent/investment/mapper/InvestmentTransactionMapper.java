package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.InvestmentTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InvestmentTransactionMapper extends BaseMapper<InvestmentTransaction> {
}
