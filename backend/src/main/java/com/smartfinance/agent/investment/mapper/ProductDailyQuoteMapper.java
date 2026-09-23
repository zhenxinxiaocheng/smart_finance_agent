package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface ProductDailyQuoteMapper extends BaseMapper<ProductDailyQuote> {
    default LocalDate latestTradeDate(Long productId) {
        ProductDailyQuote latest = selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .select(ProductDailyQuote::getTradeDate)
                .eq(ProductDailyQuote::getProductId, productId)
                .orderByDesc(ProductDailyQuote::getTradeDate)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getTradeDate();
    }

    default boolean hasMissingFundReturns(Long productId) {
        return selectCount(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .and(query -> query.isNull(ProductDailyQuote::getTotalReturnIndex)
                        .or().le(ProductDailyQuote::getTotalReturnIndex, BigDecimal.ZERO))) > 0;
    }
}
