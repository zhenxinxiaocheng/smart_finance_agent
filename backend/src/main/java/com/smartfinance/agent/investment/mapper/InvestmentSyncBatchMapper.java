package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.InvestmentSyncBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InvestmentSyncBatchMapper extends BaseMapper<InvestmentSyncBatch> {
}
