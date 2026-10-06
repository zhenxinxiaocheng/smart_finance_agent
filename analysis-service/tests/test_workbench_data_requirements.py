import pytest
from app.quant_workbench.engine import data_requirements, EngineError
from app.quant_workbench.parameters import PARAMETERS


def test_requirements_share_execution_defaults_without_observations():
    defaults = {row[0]: row[1] for row in PARAMETERS}
    result = data_requirements({"config": {}})
    assert result["datasets"] == ["PRICE"]
    assert result["warmupTradingDays"] == max(defaults["slowWindow"], defaults["lookback"] + 1)


def test_custom_warmup_and_invalid_parameters():
    assert data_requirements({"config": {"slowWindow": 120, "lookback": 150}})["warmupTradingDays"] == 151
    with pytest.raises(EngineError):
        data_requirements({"config": {"slowWindow": -1}})
