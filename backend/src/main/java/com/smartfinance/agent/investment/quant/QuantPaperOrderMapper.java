package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuantPaperOrderMapper extends BaseMapper<QuantPaperOrder> {
    @Update("UPDATE quant_paper_order SET status = 'PROCESSING' " +
            "WHERE id = #{id} AND status = 'SUBMITTED'")
    int claimSubmitted(@Param("id") Long id);
}
