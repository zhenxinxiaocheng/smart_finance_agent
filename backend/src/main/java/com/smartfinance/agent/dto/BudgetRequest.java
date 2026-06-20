package com.smartfinance.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BudgetRequest {

    @NotBlank(message = "预算分类不能为空")
    private String category;

    @NotBlank(message = "预算月份不能为空")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "预算月份格式应为yyyy-MM")
    private String month;

    @NotNull(message = "预算金额不能为空")
    @Positive(message = "预算金额必须大于0")
    private BigDecimal amount;

    @Min(value = 1, message = "预警阈值不能小于1")
    @Max(value = 100, message = "预警阈值不能大于100")
    private Integer alertThreshold;
}
