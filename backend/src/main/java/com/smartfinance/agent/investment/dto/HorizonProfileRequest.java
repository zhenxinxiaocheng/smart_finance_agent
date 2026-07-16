package com.smartfinance.agent.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record HorizonProfileRequest(
        @NotEmpty @Size(max = 20) List<@Valid HorizonSettingRequest> settings) {

    public HorizonProfileRequest {
        settings = settings == null ? null : List.copyOf(settings);
    }
}
