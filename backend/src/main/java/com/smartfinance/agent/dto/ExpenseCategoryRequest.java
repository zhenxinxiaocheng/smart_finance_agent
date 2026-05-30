package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExpenseCategoryRequest {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过50个字符")
    private String name;

    @Size(max = 50, message = "图标标识不能超过50个字符")
    private String icon;

    private Integer benchmarkMin;

    private Integer benchmarkMax;

    @Size(max = 100, message = "对标标签不能超过100个字符")
    private String benchmarkLabel;

    private Integer sortOrder;
}
