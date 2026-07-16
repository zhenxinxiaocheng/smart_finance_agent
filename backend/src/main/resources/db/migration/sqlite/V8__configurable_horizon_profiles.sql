CREATE TABLE investment_horizon_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    scope_type TEXT NOT NULL,
    asset_id INTEGER,
    version INTEGER NOT NULL,
    template_version TEXT NOT NULL,
    source TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    effective_from TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_horizon_profile_resolution
    ON investment_horizon_profile(user_id, scope_type, asset_id, active, version);

CREATE TABLE investment_horizon_setting (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id INTEGER NOT NULL,
    horizon_code TEXT NOT NULL,
    display_name TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    min_holding_days INTEGER NOT NULL,
    max_holding_days INTEGER NOT NULL,
    is_primary INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(profile_id, horizon_code),
    FOREIGN KEY(profile_id) REFERENCES investment_horizon_profile(id)
);

CREATE INDEX idx_horizon_setting_profile
    ON investment_horizon_setting(profile_id, sort_order);

ALTER TABLE investment_analysis_snapshot
    ADD COLUMN horizon_profile_version TEXT;

ALTER TABLE investment_analysis_snapshot
    ADD COLUMN horizon_config_json TEXT;

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
