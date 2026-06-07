package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transaction " +
            "WHERE user_id = #{userId} AND type = #{type} " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0")
    BigDecimal sumByUserAndTypeAndDateRange(@Param("userId") Long userId,
                                            @Param("type") String type,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' AND category = #{category} " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0")
    BigDecimal sumByUserAndCategoryAndDateRange(@Param("userId") Long userId,
                                                @Param("category") String category,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transaction " +
            "WHERE user_id = #{userId} AND type = #{type} AND deleted = 0")
    BigDecimal sumByUserAndType(@Param("userId") Long userId, @Param("type") String type);

    @Select("SELECT category AS name, COALESCE(SUM(amount), 0) AS total FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "GROUP BY category ORDER BY total DESC")
    List<Map<String, Object>> sumExpenseGroupByCategory(@Param("userId") Long userId,
                                                         @Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate);

    @Select("SELECT category, COALESCE(SUM(amount), 0) AS total FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "GROUP BY category ORDER BY total DESC")
    List<Map<String, Object>> sumByCategory(@Param("userId") Long userId,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    @Select("SELECT transaction_date AS date, COALESCE(SUM(amount), 0) AS total FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "GROUP BY transaction_date ORDER BY transaction_date ASC")
    List<Map<String, Object>> sumDailyExpense(@Param("userId") Long userId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM transaction WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND amount >= #{amount} AND transaction_date BETWEEN #{startDate} AND #{endDate} " +
            "AND deleted = 0 ORDER BY amount DESC, transaction_date DESC")
    List<Transaction> selectExpensesAboveAmount(@Param("userId") Long userId,
                                                @Param("amount") BigDecimal amount,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM transaction WHERE user_id = #{userId} AND deleted = 0 " +
            "ORDER BY transaction_date DESC, created_at DESC LIMIT #{limit}")
    List<Transaction> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM transaction WHERE user_id = #{userId} AND deleted = 0 " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY transaction_date DESC, created_at DESC")
    List<Transaction> findByUserAndDateRange(@Param("userId") Long userId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    default List<Transaction> selectByUserAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return findByUserAndDateRange(userId, startDate, endDate);
    }
}
