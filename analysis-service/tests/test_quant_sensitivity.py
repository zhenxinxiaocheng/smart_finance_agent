from copy import deepcopy

import pytest

from app.quant_workbench.engine import EngineError, execute, runtime_info
from app.quant_workbench.parameters import parameter_catalog
from app.quant_workbench.sensitivity import CandidateError, generate_candidates
from tests.test_quant_workbench import request


def test_runtime_info_is_the_same_environment_recorded_by_execution():
    runtime = runtime_info()
    provenance = execute(request())["result"]["provenance"]

    assert runtime == {
        "engineVersion": provenance["engineVersion"],
        "codeHash": provenance["codeHash"],
        "parameterCatalogVersion": "parameters-v2",
        "candidateRuleVersion": "parameter-sensitivity-v1",
    }


def test_expected_runtime_locks_execution_without_changing_normal_requests():
    payload = request()
    normal = execute(deepcopy(payload))
    locked = deepcopy(payload)
    locked["expectedRuntime"] = runtime_info()

    assert execute(locked) == normal

    changed = deepcopy(locked)
    changed["expectedRuntime"]["codeHash"] = "old-code"
    with pytest.raises(EngineError) as error:
        execute(changed)
    assert error.value.code == "EXPERIMENT_ENVIRONMENT_CHANGED"


@pytest.mark.parametrize(
    ("parameter", "baseline", "expected"),
    [
        ("slowWindow", 60, [48, 54, 60, 66, 72]),
        ("topN", 5, [3, 4, 5, 6, 7]),
        ("maxWeight", 0.3, [0.24, 0.27, 0.3, 0.33, 0.36]),
    ],
)
def test_candidates_are_centered_symmetric_local_perturbations(parameter, baseline, expected):
    config = {"strategyType": "TREND", parameter: baseline}

    result = generate_candidates(config, parameter)

    assert result["values"] == expected
    assert result["baseline"] == baseline
    assert result["values"][0] + result["values"][4] == pytest.approx(2 * baseline)
    assert result["values"][1] + result["values"][3] == pytest.approx(2 * baseline)


def test_candidate_generation_does_not_fill_only_one_side_near_a_boundary():
    with pytest.raises(CandidateError) as error:
        generate_candidates({"strategyType": "TREND", "lookback": 2}, "lookback")

    assert error.value.code == "SYMMETRIC_RANGE_UNAVAILABLE"


def test_ml_feature_parameters_are_not_available_for_fixed_model_experiments():
    with pytest.raises(CandidateError) as error:
        generate_candidates({"strategyType": "ML_ELASTIC_NET", "lookback": 20}, "lookback")

    assert error.value.code == "PARAMETER_NOT_APPLICABLE"
    assert generate_candidates(
        {"strategyType": "ML_ELASTIC_NET", "rebalanceDays": 5}, "rebalanceDays"
    )["values"] == [3, 4, 5, 6, 7]


def test_parameter_catalog_exposes_versioned_sensitivity_rules():
    catalog = parameter_catalog()
    by_key = {item["key"]: item for item in catalog["parameters"]}

    assert catalog["version"] == "parameters-v2"
    assert catalog["candidateRuleVersion"] == "parameter-sensitivity-v1"
    assert by_key["slowWindow"]["sensitivity"]["relativeStep"] == 0.1
    assert by_key["feeRate"]["sensitivity"]["enabled"] is False
