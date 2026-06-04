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

    @Select("SELECT category, COALESCE(SUM(amount), 0) as total FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "GROUP BY category ORDER BY total DESC")
    List<Map<String, Object>> sumByCategory(@Param("userId") Long userId,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM transaction WHERE user_id = #{userId} " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "ORDER BY transaction_date DESC, created_at DESC")
    List<Transaction> selectByUserAndDateRange(@Param("userId") Long userId,
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    @Select("SELECT transaction_date as date, COALESCE(SUM(amount), 0) as total FROM transaction " +
            "WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} AND deleted = 0 " +
            "GROUP BY transaction_date ORDER BY transaction_date")
    List<Map<String, Object>> sumDailyExpense(@Param("userId") Long userId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    @Select("SELECT DATE_FORMAT(transaction_date, '%Y-%m') as month, " +
            "COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END), 0) as income, " +
            "COALESCE(SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END), 0) as expense " +
            "FROM transaction WHERE user_id = #{userId} AND deleted = 0 " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} " +
            "GROUP BY DATE_FORMAT(transaction_date, '%Y-%m') ORDER BY month")
    List<Map<String, Object>> sumMonthlyIncomeExpense(@Param("userId") Long userId,
                                                       @Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM transaction WHERE user_id = #{userId} AND type = 'EXPENSE' " +
            "AND amount >= #{minAmount} AND deleted = 0 " +
            "AND transaction_date BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY amount DESC")
    List<Transaction> selectExpensesAboveAmount(@Param("userId") Long userId,
                                                 @Param("minAmount") BigDecimal minAmount,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);
}
