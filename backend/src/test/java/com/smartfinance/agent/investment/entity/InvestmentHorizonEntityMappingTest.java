package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonProfileMapper;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonSettingMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentHorizonEntityMappingTest {

    @Test
    void entitiesAndMappersMatchVersionedHorizonTables() {
        assertThat(InvestmentHorizonProfile.class.getAnnotation(TableName.class).value())
                .isEqualTo("investment_horizon_profile");
        assertThat(InvestmentHorizonSetting.class.getAnnotation(TableName.class).value())
                .isEqualTo("investment_horizon_setting");
        assertThat(BaseMapper.class).isAssignableFrom(InvestmentHorizonProfileMapper.class);
        assertThat(BaseMapper.class).isAssignableFrom(InvestmentHorizonSettingMapper.class);
        try {
            assertThat(InvestmentHorizonSetting.class.getDeclaredField("primary")
                    .getAnnotation(TableField.class).value()).isEqualTo("is_primary");
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }

        InvestmentHorizonProfile profile = new InvestmentHorizonProfile();
        profile.setScopeType("ASSET");
        profile.setVersion(4);
        profile.setActive(true);
        assertThat(profile.getVersion()).isEqualTo(4);

        InvestmentHorizonSetting setting = new InvestmentHorizonSetting();
        setting.setHorizonCode("WAVE");
        setting.setMinHoldingDays(7);
        setting.setMaxHoldingDays(45);
        assertThat(setting.getHorizonCode()).isEqualTo("WAVE");
    }

    @Test
    void analysisSnapshotStoresResolvedHorizonVersionAndPayload() {
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setHorizonProfileVersion("template:v1|global:2|asset:4");
        snapshot.setHorizonConfigJson("{\"WAVE\":[7,45]}");

        assertThat(snapshot.getHorizonProfileVersion()).contains("asset:4");
        assertThat(snapshot.getHorizonConfigJson()).contains("WAVE");
    }
}
