package com.smartfinance.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentHorizonSetting;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonSettingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:horizon_setting_mapper_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentHorizonSettingMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvestmentHorizonSettingMapper settingMapper;

    @Test
    void selectList_shouldMapPrimaryFlagWithoutUsingReservedAlias() {
        jdbcTemplate.update("""
                INSERT INTO investment_horizon_profile
                    (id, user_id, scope_type, asset_id, version, template_version, source, active, effective_from)
                VALUES (1, 1, 'GLOBAL', NULL, 1, 'test-v1', 'TEST', TRUE, CURRENT_TIMESTAMP)
                """);

        InvestmentHorizonSetting setting = new InvestmentHorizonSetting();
        setting.setProfileId(1L);
        setting.setHorizonCode("WAVE");
        setting.setDisplayName("波段");
        setting.setSortOrder(1);
        setting.setMinHoldingDays(5);
        setting.setMaxHoldingDays(20);
        setting.setPrimary(true);
        settingMapper.insert(setting);

        var settings = settingMapper.selectList(new LambdaQueryWrapper<InvestmentHorizonSetting>()
                .eq(InvestmentHorizonSetting::getProfileId, 1L));

        assertThat(settings).singleElement().satisfies(saved -> {
            assertThat(saved.getHorizonCode()).isEqualTo("WAVE");
            assertThat(saved.getPrimary()).isTrue();
        });
    }
}
