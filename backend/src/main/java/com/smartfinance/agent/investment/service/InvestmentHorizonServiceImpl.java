package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonProfileResponse;
import com.smartfinance.agent.investment.dto.HorizonSettingResponse;
import com.smartfinance.agent.investment.entity.InvestmentHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentHorizonSetting;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonProfileMapper;
import com.smartfinance.agent.investment.mapper.InvestmentHorizonSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InvestmentHorizonServiceImpl implements InvestmentHorizonService {

    private static final String USER_SCOPE = "USER";
    private static final String ASSET_SCOPE = "ASSET";

    private final InvestmentHorizonProfileMapper profileMapper;
    private final InvestmentHorizonSettingMapper settingMapper;
    private final InvestmentHorizonProperties properties;

    public InvestmentHorizonServiceImpl(InvestmentHorizonProfileMapper profileMapper,
                                        InvestmentHorizonSettingMapper settingMapper,
                                        InvestmentHorizonProperties properties) {
        this.profileMapper = profileMapper;
        this.settingMapper = settingMapper;
        this.properties = properties;
    }

    @Override
    public ResolvedHorizonProfile resolve(Long userId, Long assetId) {
        ResolvedHorizonProfile template = properties.toTemplateProfile();
        Map<String, HorizonSetting> merged = new LinkedHashMap<>();
        template.settings().forEach(item -> merged.put(item.code(), item));

        InvestmentHorizonProfile global = findActive(userId, USER_SCOPE, null);
        if (global != null) {
            applyScope(merged, loadSettings(global.getId()), "GLOBAL");
        }
        InvestmentHorizonProfile asset = assetId == null ? null : findActive(userId, ASSET_SCOPE, assetId);
        if (asset != null) {
            applyScope(merged, loadSettings(asset.getId()), ASSET_SCOPE);
        }

        List<String> warnings = new ArrayList<>();
        int requestedHistory = merged.values().stream()
                .mapToInt(HorizonSetting::maxHoldingDays).max().orElse(0);
        if (requestedHistory > properties.getMaxHistoryTradingDays()) {
            warnings.add("最长周期需要 " + requestedHistory + " 个交易日历史，当前数据能力为 "
                    + properties.getMaxHistoryTradingDays() + " 个交易日；配置已保留，但超出部分暂不计算");
        }
        String version = "template:" + template.templateVersion()
                + "|global:" + versionOf(global)
                + "|asset:" + versionOf(asset);
        return new ResolvedHorizonProfile(
                version, template.templateVersion(), new ArrayList<>(merged.values()), warnings);
    }

    @Override
    public HorizonProfileResponse global(Long userId) {
        return response(resolve(userId, null));
    }

    @Override
    @Transactional
    public HorizonProfileResponse saveGlobal(Long userId, HorizonProfileRequest request) {
        saveScope(userId, USER_SCOPE, null, "USER", request);
        return global(userId);
    }

    @Override
    @Transactional
    public HorizonProfileResponse saveAssetOverride(Long userId, Long assetId,
                                                    HorizonProfileRequest request) {
        if (assetId == null) {
            throw new IllegalArgumentException("资产不能为空");
        }
        saveScope(userId, ASSET_SCOPE, assetId, ASSET_SCOPE, request);
        return response(resolve(userId, assetId));
    }

    @Override
    @Transactional
    public HorizonProfileResponse clearAssetOverride(Long userId, Long assetId) {
        InvestmentHorizonProfile current = findActive(userId, ASSET_SCOPE, assetId);
        if (current != null) {
            current.setActive(false);
            profileMapper.updateById(current);
        }
        return response(resolve(userId, assetId));
    }

    private void saveScope(Long userId, String scopeType, Long assetId,
                           String source, HorizonProfileRequest request) {
        List<HorizonSetting> normalized = normalize(request, scopeType.equals(ASSET_SCOPE) ? ASSET_SCOPE : "GLOBAL");
        InvestmentHorizonProfile previous = findActive(userId, scopeType, assetId);
        int nextVersion = previous == null ? 1 : previous.getVersion() + 1;
        if (previous != null) {
            previous.setActive(false);
            profileMapper.updateById(previous);
        }

        LocalDateTime now = LocalDateTime.now();
        InvestmentHorizonProfile header = new InvestmentHorizonProfile();
        header.setUserId(userId);
        header.setScopeType(scopeType);
        header.setAssetId(assetId);
        header.setVersion(nextVersion);
        header.setTemplateVersion(properties.toTemplateProfile().templateVersion());
        header.setSource(source);
        header.setActive(true);
        header.setEffectiveFrom(now);
        profileMapper.insert(header);
        if (header.getId() == null) {
            throw new IllegalStateException("周期配置版本保存失败");
        }
        for (HorizonSetting item : normalized) {
            InvestmentHorizonSetting entity = new InvestmentHorizonSetting();
            entity.setProfileId(header.getId());
            entity.setHorizonCode(item.code());
            entity.setDisplayName(item.displayName());
            entity.setSortOrder(item.sortOrder());
            entity.setMinHoldingDays(item.minHoldingDays());
            entity.setMaxHoldingDays(item.maxHoldingDays());
            entity.setPrimary(item.primary());
            settingMapper.insert(entity);
        }
    }

    private List<HorizonSetting> normalize(HorizonProfileRequest request, String sourceScope) {
        if (request == null || request.settings() == null || request.settings().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个分析周期");
        }
        List<HorizonSetting> settings = request.settings().stream()
                .map(item -> new HorizonSetting(
                        item.code(), item.displayName(), item.sortOrder(),
                        item.minHoldingDays(), item.maxHoldingDays(), item.primary(), sourceScope))
                .toList();
        return new ResolvedHorizonProfile(
                "validation", "validation", settings, List.of()).settings();
    }

    private InvestmentHorizonProfile findActive(Long userId, String scopeType, Long assetId) {
        LambdaQueryWrapper<InvestmentHorizonProfile> query =
                new LambdaQueryWrapper<InvestmentHorizonProfile>()
                        .eq(InvestmentHorizonProfile::getUserId, userId)
                        .eq(InvestmentHorizonProfile::getScopeType, scopeType)
                        .eq(InvestmentHorizonProfile::getActive, true);
        if (assetId == null) {
            query.isNull(InvestmentHorizonProfile::getAssetId);
        } else {
            query.eq(InvestmentHorizonProfile::getAssetId, assetId);
        }
        return profileMapper.selectOne(query.orderByDesc(InvestmentHorizonProfile::getVersion).last("LIMIT 1"));
    }

    private List<InvestmentHorizonSetting> loadSettings(Long profileId) {
        return settingMapper.selectList(new LambdaQueryWrapper<InvestmentHorizonSetting>()
                .eq(InvestmentHorizonSetting::getProfileId, profileId)
                .orderByAsc(InvestmentHorizonSetting::getSortOrder));
    }

    private static void applyScope(Map<String, HorizonSetting> target,
                                   List<InvestmentHorizonSetting> source,
                                   String sourceScope) {
        if (source.stream().anyMatch(item -> Boolean.TRUE.equals(item.getPrimary()))) {
            target.replaceAll((code, item) -> new HorizonSetting(
                    item.code(), item.displayName(), item.sortOrder(),
                    item.minHoldingDays(), item.maxHoldingDays(), false, item.sourceScope()));
        }
        for (InvestmentHorizonSetting item : source) {
            HorizonSetting value = new HorizonSetting(
                    item.getHorizonCode(), item.getDisplayName(), item.getSortOrder(),
                    item.getMinHoldingDays(), item.getMaxHoldingDays(),
                    Boolean.TRUE.equals(item.getPrimary()), sourceScope);
            target.put(value.code(), value);
        }
    }

    private HorizonProfileResponse response(ResolvedHorizonProfile profile) {
        boolean assetOverride = profile.settings().stream()
                .anyMatch(item -> ASSET_SCOPE.equals(item.sourceScope()));
        String sourceScope = assetOverride ? ASSET_SCOPE
                : profile.settings().stream().anyMatch(item -> "GLOBAL".equals(item.sourceScope()))
                ? "GLOBAL" : "TEMPLATE";
        List<HorizonSettingResponse> settings = profile.settings().stream()
                .map(item -> new HorizonSettingResponse(
                        item.code(), item.displayName(), item.sortOrder(),
                        item.minHoldingDays(), item.maxHoldingDays(),
                        item.primary(), item.sourceScope()))
                .toList();
        return new HorizonProfileResponse(
                profile.version(), profile.templateVersion(), sourceScope, assetOverride,
                properties.getMaxHistoryTradingDays(), settings, profile.warnings());
    }

    private static int versionOf(InvestmentHorizonProfile profile) {
        return profile == null ? 0 : profile.getVersion();
    }
}
