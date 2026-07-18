package com.smartfinance.agent.investment.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedHorizonProfileTest {

    @Test
    void arbitraryLabelsAndDaysDriveRangesWithoutMarketDefinitions() {
        ResolvedHorizonProfile profile = new ResolvedHorizonProfile(
                "template:v1|global:4|asset:2",
                "v1",
                List.of(
                        new HorizonSetting("WAVE", "波段", 10, 7, 45, 21, true, "ASSET"),
                        new HorizonSetting("POSITION", "配置", 20, 80, 260, 160, false, "GLOBAL")
                ),
                List.of()
        );

        assertThat(profile.analysisRanges())
                .containsEntry("WAVE", List.of(7, 45))
                .containsEntry("POSITION", List.of(80, 260));
        assertThat(profile.primaryCode()).isEqualTo("WAVE");
        assertThat(profile.requiredHistoryDays()).isEqualTo(260);
        assertThat(profile.settings().get(0).targetHoldingDays()).isEqualTo(21);
    }

    @Test
    void sortOrderOnlyControlsPresentation() {
        ResolvedHorizonProfile profile = new ResolvedHorizonProfile(
                "template:v1", "v1", List.of(
                new HorizonSetting("LATER", "后显示", 20, 3, 10, false, "TEMPLATE"),
                new HorizonSetting("FIRST", "先显示", 10, 200, 400, true, "TEMPLATE")
        ), List.of());

        assertThat(profile.settings()).extracting(HorizonSetting::code)
                .containsExactly("FIRST", "LATER");
        assertThat(profile.primaryCode()).isEqualTo("FIRST");
        assertThat(profile.analysisRanges().get("LATER")).containsExactly(3, 10);
    }

    @Test
    void duplicateCodesAndInvalidDaysAreRejected() {
        assertThatThrownBy(() -> new HorizonSetting("X", "反向", 10, 20, 10, true, "USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最小天数");

        assertThatThrownBy(() -> new ResolvedHorizonProfile(
                "template:v1", "v1", List.of(
                new HorizonSetting("same", "一", 10, 5, 10, true, "USER"),
                new HorizonSetting("SAME", "二", 20, 6, 12, false, "USER")
        ), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");
    }
}
