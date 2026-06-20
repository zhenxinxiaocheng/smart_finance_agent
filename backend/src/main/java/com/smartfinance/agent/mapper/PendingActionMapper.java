package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.PendingAction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PendingActionMapper extends BaseMapper<PendingAction> {
}
