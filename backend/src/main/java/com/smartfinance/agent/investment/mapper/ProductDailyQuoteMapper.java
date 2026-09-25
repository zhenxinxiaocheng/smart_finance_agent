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

    default LocalDate latestTradeDate(Long productId, String adjustType) {
        ProductDailyQuote latest = selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .select(ProductDailyQuote::getTradeDate)
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getAdjustType, adjustType)
                .orderByDesc(ProductDailyQuote::getTradeDate)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getTradeDate();
    }

    default boolean hasMissingFundReturns(Long productId) {
        return hasMissingFundReturns(productId, null);
    }

    default boolean hasMissingFundReturns(Long productId, LocalDate throughDate) {
        LambdaQueryWrapper<ProductDailyQuote> query = new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getAdjustType, "NONE")
                .and(nested -> nested.isNull(ProductDailyQuote::getTotalReturnIndex)
                        .or().le(ProductDailyQuote::getTotalReturnIndex, BigDecimal.ZERO));
        if (throughDate != null) {
            query.le(ProductDailyQuote::getTradeDate, throughDate);
        }
        return selectCount(query) > 0;
    }

    default LocalDate latestCompleteFundTradeDate(Long productId) {
        ProductDailyQuote latest = selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .select(ProductDailyQuote::getTradeDate)
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getAdjustType, "NONE")
                .gt(ProductDailyQuote::getTotalReturnIndex, BigDecimal.ZERO)
                .orderByDesc(ProductDailyQuote::getTradeDate)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getTradeDate();
    }
}
