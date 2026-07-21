package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuantPaperFillMapper extends BaseMapper<QuantPaperFill> {
    @Select("""
            SELECT COUNT(DISTINCT f.fill_date)
            FROM quant_paper_fill f
            JOIN quant_paper_order o ON o.id = f.order_id
            WHERE o.strategy_version = #{strategyVersion}
            """)
    long countTradingDays(@Param("strategyVersion") String strategyVersion);
}
