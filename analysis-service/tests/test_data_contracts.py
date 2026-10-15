from datetime import date, datetime, timezone

import pytest


def test_history_switches_source_when_first_response_misses_required_fields(monkeypatch):
    import pandas as pd
    import app.market_catalog as catalog

    calls = []
    def source(config, module, **kwargs):
        calls.append(config["name"])
        row = dict(date="2026-01-05", open=10, high=11, low=9, close=10)
        if len(calls) > 1:
            row["volume"] = 100
        return pd.DataFrame([row])
    monkeypatch.setattr(catalog, "_source_call", source)
    records = catalog.daily_history("600000", "SSE", "STOCK", date(2026, 1, 5), date(2026, 1, 5), "NONE", object())
    assert len(calls) == 2
    assert records[0].volume is not None


def test_stock_fund_and_index_have_distinct_required_fields():
    from app.data_contracts import contract_for

    stock = contract_for("PRICE", "STOCK")
    fund = contract_for("PRICE", "MUTUAL_FUND")
    index = contract_for("PRICE", "INDEX")
    assert stock["version"] == "stock-daily-v1"
    assert set(stock["requiredFields"]) == {"data_date", "open", "high", "low", "close", "volume"}
    assert fund["version"] == "fund-daily-v1"
    assert set(fund["requiredFields"]) == {"data_date", "close"}
    assert set(index["requiredFields"]) == {"data_date", "close"}


def test_missing_required_field_is_not_an_empty_source_response():
    from app.data_contracts import validate_quotes

    with pytest.raises(ValueError, match="volume"):
        validate_quotes([{"data_date": "2026-01-05", "open": "10", "high": "11",
                          "low": "9", "close": "10"}], "STOCK")
    validate_quotes([{"data_date": "2026-01-05", "close": "1.23"}], "MUTUAL_FUND")
    validate_quotes([], "STOCK")


def test_research_contract_declares_temporal_metadata_and_unknown_dataset_is_rejected():
    from app.data_contracts import contract_for

    value = contract_for("FINANCIALS", "STOCK")
    assert value["version"] == "research-record-v1"
    assert "observedAt" in value["requiredFields"]
    assert "payload" in value["requiredFields"]
    with pytest.raises(ValueError, match="Unsupported"):
        contract_for("UNKNOWN", "STOCK")


def test_snapshot_context_preserves_legacy_identity_without_silently_upgrading():
    from app.data_quality.models import DataSnapshotContext

    values = dict(productType="STOCK", code="600000", market="SSE", frequency="DAY",
                  adjustType="NONE", provider="TEST", adapterVersion="2",
                  requestedStartDate=date(2026, 1, 5), requestedEndDate=date(2026, 1, 5),
                  fetchedAt=datetime(2026, 1, 6, tzinfo=timezone.utc))
    legacy = DataSnapshotContext.model_validate(values)
    assert legacy.data_contract_version is None
    current = DataSnapshotContext.model_validate(dict(values, dataContractVersion="stock-daily-v1"))
    assert current.data_contract_version == "stock-daily-v1"


def test_contract_identity_changes_when_required_fields_change_even_if_the_label_is_unchanged(monkeypatch):
    from app import data_contracts as module

    original = module.contract_for("PRICE", "STOCK")
    module.contract_version.cache_clear()
    before = module.contract_version("PRICE", "STOCK")
    changed = dict(original, requiredFields=original["requiredFields"] + ["amount"])
    monkeypatch.setattr(module, "contract_for", lambda *args: changed)
    module.contract_version.cache_clear()
    try:
        assert module.contract_version("PRICE", "STOCK") != before
    finally:
        module.contract_version.cache_clear()


def test_price_contract_changes_when_history_units_change(monkeypatch):
    from copy import deepcopy
    from app import data_contracts, providers

    original = providers.STRATEGY
    class ChangedStrategy:
        def value(self, key):
            value = original.value(key)
            if key == "providers.history_units":
                value = deepcopy(value)
                value["stock_zh_a_hist"]["volume"] *= 10
            return value
    data_contracts.contract_version.cache_clear()
    before = data_contracts.contract_version("PRICE", "STOCK")
    monkeypatch.setattr(providers, "STRATEGY", ChangedStrategy())
    data_contracts.contract_version.cache_clear()
    try:
        assert data_contracts.contract_version("PRICE", "STOCK") != before
    finally:
        data_contracts.contract_version.cache_clear()


def test_current_fund_quality_response_uses_the_market_storage_fields():
    from types import SimpleNamespace
    from app.data_contracts import contract_version, validate_quotes
    from app.data_quality.service import _response
    from app.data_quality.models import ProductType

    manifest = SimpleNamespace(dataset_version="snapshot", product_type=ProductType.MUTUAL_FUND,
        data_contract_version=contract_version("PRICE", "MUTUAL_FUND"), model_dump=lambda **kwargs: {})
    report = SimpleNamespace(model_dump=lambda **kwargs: {})
    result = _response(manifest, [], report,
        [{"data_date": "2026-01-05", "nav": "1.2", "adjustment_factor": "2"}], [])
    validate_quotes(result["records"], "MUTUAL_FUND")
    assert result["records"][0]["close"] == "1.2"
