package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.BillCandidateTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillCandidateTransactionMapper extends BaseMapper<BillCandidateTransaction> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM bill_candidate_transaction WHERE id = #{id} " +
            "AND user_id = #{userId} AND bill_import_id = #{billId} AND deleted = 0 FOR UPDATE")
    @org.apache.ibatis.annotations.Select(value = "SELECT * FROM bill_candidate_transaction WHERE id = #{id} " +
            "AND user_id = #{userId} AND bill_import_id = #{billId} AND deleted = 0", databaseId = "sqlite")
    BillCandidateTransaction selectOwnedForUpdate(@org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("billId") Long billId, @org.apache.ibatis.annotations.Param("id") Long id);
}
