package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InvestmentAccountMapper extends BaseMapper<InvestmentAccount> {
    @Update("UPDATE investment_account SET updated_at = updated_at " +
            "WHERE id = #{accountId} AND account_type = 'PAPER' AND deleted = 0")
    int lockActivePaperAccount(@Param("accountId") Long accountId);
}
