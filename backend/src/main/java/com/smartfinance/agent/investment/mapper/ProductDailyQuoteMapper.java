package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;

@Mapper
public interface ProductDailyQuoteMapper extends BaseMapper<ProductDailyQuote> {
    default boolean hasMissingFundReturns(Long productId) {
        return selectCount(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .and(query -> query.isNull(ProductDailyQuote::getTotalReturnIndex)
                        .or().le(ProductDailyQuote::getTotalReturnIndex, BigDecimal.ZERO))) > 0;
    }
}
