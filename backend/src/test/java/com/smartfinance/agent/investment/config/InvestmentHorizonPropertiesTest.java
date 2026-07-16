package com.smartfinance.agent.investment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentHorizonPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(InvestmentHorizonProperties.class);

    @Test
    void versionedClasspathTemplateIsTheRuntimeDefaultSource() {
        contextRunner.run(context -> {
            InvestmentHorizonProperties properties = context.getBean(InvestmentHorizonProperties.class);

            assertThat(properties.getTemplateVersion()).isEqualTo("horizon-template-v1");
            assertThat(properties.getDefaults()).extracting(InvestmentHorizonProperties.TemplateHorizon::getCode)
                    .containsExactly("SHORT", "MEDIUM", "LONG");
            assertThat(properties.toTemplateProfile().analysisRanges().get("SHORT"))
                    .containsExactly(5, 20);
        });
    }

    @Test
    void templateProfileUsesConfiguredValuesWithoutJavaIntervalDefaults() {
        InvestmentHorizonProperties properties = new InvestmentHorizonProperties();
        properties.setTemplateVersion("custom-v9");
        properties.setMaxHistoryTradingDays(3000);
        properties.setDefaults(List.of(
                configured("WAVE", "我的波段", 20, 9, 77, false),
                configured("CORE", "核心配置", 10, 120, 880, true)
        ));

        var profile = properties.toTemplateProfile();

        assertThat(profile.version()).isEqualTo("template:custom-v9");
        assertThat(profile.primaryCode()).isEqualTo("CORE");
        assertThat(profile.analysisRanges()).containsEntry("WAVE", List.of(9, 77));
        assertThat(properties.getMaxHistoryTradingDays()).isEqualTo(3000);
    }

    private static InvestmentHorizonProperties.TemplateHorizon configured(
            String code, String displayName, int sortOrder,
            int minimum, int maximum, boolean primary) {
        InvestmentHorizonProperties.TemplateHorizon value =
                new InvestmentHorizonProperties.TemplateHorizon();
        value.setCode(code);
        value.setDisplayName(displayName);
        value.setSortOrder(sortOrder);
        value.setMinHoldingDays(minimum);
        value.setMaxHoldingDays(maximum);
        value.setPrimary(primary);
        return value;
    }
}
