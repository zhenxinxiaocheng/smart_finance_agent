package com.smartfinance.agent.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Mapper
public interface ProductDailyQuoteMapper extends BaseMapper<ProductDailyQuote> {
    @Select("""
            <script>
            SELECT id,product_id,trade_date,open_price,high_price,low_price,close_price,total_return_index,
                   previous_close,change_amount,change_percent,volume,amount,turnover_rate,volume_ratio,
                   amplitude,adjust_type,source,adapter_version,synced_at
            FROM product_daily_quote
            WHERE product_id IN
              <foreach collection="productIds" item="id" open="(" close=")" separator=",">#{id}</foreach>
              <if test="startDate != null">AND trade_date &gt;= #{startDate}</if>
              AND trade_date &lt;= #{endDate}
              AND adjust_type IN
              <foreach collection="adjustTypes" item="adjust" open="(" close=")" separator=",">#{adjust}</foreach>
            ORDER BY product_id,trade_date,adjust_type
            </script>
            """)
    List<ProductDailyQuote> selectSeries(@Param("productIds") List<Long> productIds,
                                        @Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate,
                                        @Param("adjustTypes") Set<String> adjustTypes);

    @Insert("""
            <script>
            INSERT INTO product_daily_quote
              (product_id,trade_date,open_price,high_price,low_price,close_price,total_return_index,
                previous_close,change_amount,change_percent,volume,amount,turnover_rate,adjust_type,source,adapter_version,synced_at)
            VALUES
            <foreach collection="quotes" item="q" separator=",">
              (#{q.productId},#{q.tradeDate},#{q.openPrice},#{q.highPrice},#{q.lowPrice},#{q.closePrice},
               #{q.totalReturnIndex},#{q.previousClose},#{q.changeAmount},#{q.changePercent},#{q.volume},
                #{q.amount},#{q.turnoverRate},#{q.adjustType},#{q.source},#{q.adapterVersion},#{q.syncedAt})
            </foreach>
            <choose>
              <when test="_databaseId == 'sqlite'">
                ON CONFLICT(product_id,trade_date,adjust_type) DO UPDATE SET
                  open_price=COALESCE(excluded.open_price,product_daily_quote.open_price),
                  high_price=COALESCE(excluded.high_price,product_daily_quote.high_price),
                  low_price=COALESCE(excluded.low_price,product_daily_quote.low_price),
                  close_price=COALESCE(excluded.close_price,product_daily_quote.close_price),
                  total_return_index=COALESCE(excluded.total_return_index,product_daily_quote.total_return_index),
                  previous_close=COALESCE(excluded.previous_close,product_daily_quote.previous_close),
                  change_amount=COALESCE(excluded.change_amount,product_daily_quote.change_amount),
                  change_percent=CASE WHEN COALESCE(excluded.close_price,product_daily_quote.close_price)>0
                    AND COALESCE(excluded.previous_close,product_daily_quote.previous_close)>0
                    THEN COALESCE(excluded.change_percent,product_daily_quote.change_percent) ELSE NULL END,
                  volume=COALESCE(excluded.volume,product_daily_quote.volume),
                  amount=COALESCE(excluded.amount,product_daily_quote.amount),
                  turnover_rate=COALESCE(excluded.turnover_rate,product_daily_quote.turnover_rate),
                  source=excluded.source,adapter_version=excluded.adapter_version,synced_at=excluded.synced_at
              </when>
              <otherwise>
                ON DUPLICATE KEY UPDATE
                  open_price=COALESCE(VALUES(open_price),open_price),
                  high_price=COALESCE(VALUES(high_price),high_price),
                  low_price=COALESCE(VALUES(low_price),low_price),
                  close_price=COALESCE(VALUES(close_price),close_price),
                  total_return_index=COALESCE(VALUES(total_return_index),total_return_index),
                  previous_close=COALESCE(VALUES(previous_close),previous_close),
                  change_amount=COALESCE(VALUES(change_amount),change_amount),
                  change_percent=CASE WHEN COALESCE(VALUES(close_price),close_price)>0
                    AND COALESCE(VALUES(previous_close),previous_close)>0
                    THEN COALESCE(VALUES(change_percent),change_percent) ELSE NULL END,
                  volume=COALESCE(VALUES(volume),volume),
                  amount=COALESCE(VALUES(amount),amount),
                  turnover_rate=COALESCE(VALUES(turnover_rate),turnover_rate),
                  source=VALUES(source),adapter_version=VALUES(adapter_version),synced_at=VALUES(synced_at)
              </otherwise>
            </choose>
            </script>
            """)
    int upsertHistory(@Param("quotes") List<ProductDailyQuote> quotes);

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

    default LocalDate earliestMissingFundReturnDate(Long productId) {
        ProductDailyQuote first = selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .select(ProductDailyQuote::getTradeDate)
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getAdjustType, "NONE")
                .and(nested -> nested.isNull(ProductDailyQuote::getTotalReturnIndex)
                        .or().le(ProductDailyQuote::getTotalReturnIndex, BigDecimal.ZERO))
                .orderByAsc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
        return first == null ? null : first.getTradeDate();
    }
}
