package com.smartfinance.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.entity.BillImportRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillImportRecordMapper extends BaseMapper<BillImportRecord> {
    @org.apache.ibatis.annotations.Update("UPDATE bill_import_record SET status = status WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int acquireWriteLock(@org.apache.ibatis.annotations.Param("userId") Long userId,
                         @org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT * FROM bill_import_record WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 FOR UPDATE")
    @org.apache.ibatis.annotations.Select(value = "SELECT * FROM bill_import_record WHERE id = #{id} AND user_id = #{userId} AND deleted = 0", databaseId = "sqlite")
    BillImportRecord selectOwnedForUpdate(@org.apache.ibatis.annotations.Param("userId") Long userId,
                                        @org.apache.ibatis.annotations.Param("id") Long id);
}
