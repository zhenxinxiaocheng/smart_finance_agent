package com.smartfinance.agent.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record HorizonProfileRequest(
        @NotEmpty List<@Valid HorizonSettingRequest> settings) {

    public HorizonProfileRequest {
        settings = settings == null ? null : List.copyOf(settings);
    }
}
