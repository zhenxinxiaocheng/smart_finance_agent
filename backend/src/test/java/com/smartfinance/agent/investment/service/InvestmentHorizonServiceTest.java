package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonSettingRequest;
import com.smartfinance.agent.investment.entity.InvestmentHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentHorizonSetting;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonProfileMapper;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentHorizonServiceTest {

    private InvestmentHorizonProfileMapper profileMapper;
    private InvestmentHorizonSettingMapper settingMapper;
    private InvestmentHorizonServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(InvestmentHorizonProfileMapper.class);
        settingMapper = mock(InvestmentHorizonSettingMapper.class);
        InvestmentHorizonProperties properties = mock(InvestmentHorizonProperties.class);
        when(properties.toTemplateProfile()).thenReturn(new com.smartfinance.agent.investment.domain.ResolvedHorizonProfile(
                "template:horizon-template-v1", "horizon-template-v1", List.of(
                new HorizonSetting("SHORT", "短期", 10, 5, 20, true, "TEMPLATE"),
                new HorizonSetting("MEDIUM", "中期", 20, 20, 120, false, "TEMPLATE"),
                new HorizonSetting("LONG", "长期", 30, 120, 500, false, "TEMPLATE")
        ), List.of()));
        when(properties.getMaxHistoryTradingDays()).thenReturn(2500);
        service = new InvestmentHorizonServiceImpl(profileMapper, settingMapper, properties);
    }

    @Test
    void assetSettingsOverlayGlobalAndTemplateByCode() {
        InvestmentHorizonProfile global = profile(21L, "USER", null, 2);
        InvestmentHorizonProfile asset = profile(22L, "ASSET", 11L, 4);
        when(profileMapper.selectOne(any())).thenReturn(global, asset);
        when(settingMapper.selectList(any())).thenReturn(
                List.of(setting(21L, "SHORT", "我的短期", 10, 9, 70, true)),
                List.of(setting(22L, "SHORT", "波段", 10, 100, 160, true),
                        setting(22L, "CUSTOM", "自定义", 40, 50, 900, false))
        );

        var result = service.resolve(7L, 11L);

        assertThat(result.analysisRanges().get("SHORT")).containsExactly(100, 160);
        assertThat(result.analysisRanges().get("MEDIUM")).containsExactly(20, 120);
        assertThat(result.analysisRanges().get("CUSTOM")).containsExactly(50, 900);
        assertThat(result.settings().stream().filter(item -> item.code().equals("SHORT")).findFirst().orElseThrow()
                .sourceScope()).isEqualTo("ASSET");
        assertThat(result.version()).isEqualTo("template:horizon-template-v1|global:2|asset:4");
    }

    @Test
    void overlappingAndUnusuallyNamedRangesAreSavedAsANewVersion() {
        HorizonProfileRequest request = new HorizonProfileRequest(List.of(
                new HorizonSettingRequest("FAST", "我的短期", 10, 10, 100, true),
                new HorizonSettingRequest("SLOW", "我的长期", 20, 50, 80, false)
        ));
        InvestmentHorizonProfile previous = profile(31L, "USER", null, 6);
        InvestmentHorizonProfile saved = profile(32L, "USER", null, 7);
        when(profileMapper.selectOne(any())).thenReturn(previous, saved);
        when(profileMapper.insert(any())).thenAnswer(invocation -> {
            InvestmentHorizonProfile value = invocation.getArgument(0);
            value.setId(32L);
            return 1;
        });
        when(settingMapper.selectList(any())).thenReturn(List.of(
                setting(32L, "FAST", "我的短期", 10, 10, 100, true),
                setting(32L, "SLOW", "我的长期", 20, 50, 80, false)
        ));

        assertThatCode(() -> service.saveGlobal(7L, request)).doesNotThrowAnyException();

        assertThat(previous.getActive()).isFalse();
        verify(profileMapper).updateById(previous);
        ArgumentCaptor<InvestmentHorizonProfile> header = ArgumentCaptor.forClass(InvestmentHorizonProfile.class);
        verify(profileMapper).insert(header.capture());
        assertThat(header.getValue().getVersion()).isEqualTo(7);
        assertThat(header.getValue().getSource()).isEqualTo("USER");
        verify(settingMapper, times(2)).insert(any());
    }

    @Test
    void duplicateCodesAreRejectedAfterNormalization() {
        HorizonProfileRequest request = new HorizonProfileRequest(List.of(
                new HorizonSettingRequest("same", "一", 10, 5, 20, true),
                new HorizonSettingRequest("SAME", "二", 20, 30, 60, false)
        ));

        assertThatThrownBy(() -> service.saveGlobal(7L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");
        verifyNoInteractions(profileMapper, settingMapper);
    }

    private static InvestmentHorizonProfile profile(Long id, String scope, Long assetId, int version) {
        InvestmentHorizonProfile value = new InvestmentHorizonProfile();
        value.setId(id);
        value.setUserId(7L);
        value.setScopeType(scope);
        value.setAssetId(assetId);
        value.setVersion(version);
        value.setTemplateVersion("horizon-template-v1");
        value.setSource(scope.equals("USER") ? "USER" : "ASSET");
        value.setActive(true);
        value.setEffectiveFrom(LocalDateTime.now());
        return value;
    }

    private static InvestmentHorizonSetting setting(Long profileId, String code, String name,
                                                    int order, int minimum, int maximum, boolean primary) {
        InvestmentHorizonSetting value = new InvestmentHorizonSetting();
        value.setProfileId(profileId);
        value.setHorizonCode(code);
        value.setDisplayName(name);
        value.setSortOrder(order);
        value.setMinHoldingDays(minimum);
        value.setMaxHoldingDays(maximum);
        value.setPrimary(primary);
        return value;
    }
}
