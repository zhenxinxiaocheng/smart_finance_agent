package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_feature_set")
public class QuantFeatureSet {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String featureSetVersion;
    private String quantConfigVersion;
    private String productType;
    private String schemaJson;
    private String artifactUri;
    private String artifactHash;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
