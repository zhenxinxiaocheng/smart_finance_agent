package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestmentIndexCreateRequest(@NotBlank String indexCode) {
}
