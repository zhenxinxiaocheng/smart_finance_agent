CREATE TABLE IF NOT EXISTS investment_horizon_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    asset_id BIGINT,
    version INT NOT NULL,
    template_version VARCHAR(64) NOT NULL,
    source VARCHAR(24) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    effective_from DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_horizon_profile_resolution (user_id, scope_type, asset_id, active, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_horizon_setting (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_id BIGINT NOT NULL,
    horizon_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    sort_order INT NOT NULL,
    min_holding_days INT NOT NULL,
    max_holding_days INT NOT NULL,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_horizon_setting_profile_code (profile_id, horizon_code),
    KEY idx_horizon_setting_profile (profile_id, sort_order),
    CONSTRAINT fk_horizon_setting_profile FOREIGN KEY (profile_id)
        REFERENCES investment_horizon_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE investment_analysis_snapshot
    ADD COLUMN horizon_profile_version VARCHAR(255),
    ADD COLUMN horizon_config_json LONGTEXT;

INSERT INTO investment_horizon_profile (
    user_id, scope_type, asset_id, version, template_version,
    source, active, effective_from, created_at, updated_at
)
SELECT user_id, 'ASSET', asset_id, 1, 'horizon-template-v1',
       'LEGACY', 1, updated_at, created_at, updated_at
FROM investment_analysis_preference;

INSERT INTO investment_horizon_setting (
    profile_id, horizon_code, display_name, sort_order,
    min_holding_days, max_holding_days, is_primary
)
SELECT profile.id, 'SHORT', '短期', 10,
       preference.short_min_days, preference.short_max_days, 1
FROM investment_analysis_preference preference
JOIN investment_horizon_profile profile
  ON profile.user_id = preference.user_id
 AND profile.asset_id = preference.asset_id
 AND profile.scope_type = 'ASSET'
 AND profile.source = 'LEGACY'
UNION ALL
SELECT profile.id, 'MEDIUM', '中期', 20,
       preference.medium_min_days, preference.medium_max_days, 0
FROM investment_analysis_preference preference
JOIN investment_horizon_profile profile
  ON profile.user_id = preference.user_id
 AND profile.asset_id = preference.asset_id
 AND profile.scope_type = 'ASSET'
 AND profile.source = 'LEGACY'
UNION ALL
SELECT profile.id, 'LONG', '长期', 30,
       preference.long_min_days, preference.long_max_days, 0
FROM investment_analysis_preference preference
JOIN investment_horizon_profile profile
  ON profile.user_id = preference.user_id
 AND profile.asset_id = preference.asset_id
 AND profile.scope_type = 'ASSET'
 AND profile.source = 'LEGACY';
