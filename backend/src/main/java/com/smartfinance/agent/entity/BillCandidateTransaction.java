package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bill_candidate_transaction")
public class BillCandidateTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billImportId;

    private Long userId;

    private BigDecimal amount;

    private String type;

    private String category;

    private String description;

    private LocalDate transactionDate;

    private BigDecimal confidence;

    private String status;

    private Long transactionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
