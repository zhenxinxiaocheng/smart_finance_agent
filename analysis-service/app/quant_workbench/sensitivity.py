from __future__ import annotations

from decimal import Decimal, ROUND_FLOOR, InvalidOperation

from .parameters import CANDIDATE_RULE_VERSION, PARAMETERS, SENSITIVITY


class CandidateError(ValueError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


def _fail(code: str, message: str):
    raise CandidateError(code, message)


def _parameter(key: str):
    match = next((item for item in PARAMETERS if item[0] == key), None)
    if match is None:
        _fail("UNKNOWN_PARAMETER", f"Unknown parameter: {key}")
    return match


def _quantize_down(value: Decimal, quantum: Decimal) -> Decimal:
    return (value / quantum).to_integral_value(rounding=ROUND_FLOOR) * quantum


def generate_candidates(config: dict, parameter_key: str, constraints: dict | None = None) -> dict:
    key, default, low, high, kind, *_ = _parameter(parameter_key)
    rule = SENSITIVITY.get(key)
    strategy_type = str(config.get("strategyType") or "TREND")
    if not rule or not rule["enabled"] or strategy_type not in rule["strategyTypes"]:
        _fail("PARAMETER_NOT_APPLICABLE", f"{key} is not available for {strategy_type}")

    try:
        raw = config.get(key, default)
        if isinstance(raw, bool):
            raise InvalidOperation
        baseline = Decimal(str(raw))
    except (InvalidOperation, ValueError, TypeError):
        _fail("INVALID_BASELINE", f"{key} must be a finite number")
    if kind == "integer" and baseline.is_finite() and baseline != baseline.to_integral_value():
        _fail("INVALID_BASELINE", f"{key} must be an integer")
    lower, upper = Decimal(str(low)), Decimal(str(high))
    if key == "topN" and constraints is not None:
        count = constraints.get("effectiveAssetCount") if isinstance(constraints, dict) else None
        if isinstance(count, bool) or not isinstance(count, int) or count < 1:
            _fail("INVALID_CONSTRAINTS", "effectiveAssetCount must be a positive integer")
        upper = min(upper, Decimal(count))
    if not baseline.is_finite() or not lower <= baseline <= upper:
        _fail("INVALID_BASELINE", f"{key} is outside its supported range")

    quantum = Decimal(1) if kind == "integer" else Decimal(1).scaleb(-int(rule["precision"]))
    minimum = Decimal(str(rule["minStep"]))
    preferred = abs(baseline) * Decimal(str(rule["relativeStep"]))
    capacity = min(baseline - lower, upper - baseline) / Decimal(2)
    initial = _quantize_down(min(max(preferred, minimum), capacity), quantum)

    delta = None
    for scale in (Decimal(1), Decimal("0.75"), Decimal("0.5"), Decimal("0.25")):
        candidate = _quantize_down(initial * scale, quantum)
        if candidate < minimum:
            continue
        values = [baseline + Decimal(offset) * candidate for offset in (-2, -1, 0, 1, 2)]
        if len(set(values)) == 5 and all(lower <= value <= upper for value in values):
            delta = candidate
            break
    if delta is None:
        _fail("SYMMETRIC_RANGE_UNAVAILABLE", f"{key} has no five-point symmetric local range")

    values = [baseline + Decimal(offset) * delta for offset in (-2, -1, 0, 1, 2)]
    convert = (lambda value: int(value)) if kind == "integer" else (lambda value: float(value))
    return {
        "parameterKey": key,
        "baseline": convert(baseline),
        "delta": convert(delta),
        "values": [convert(value) for value in values],
        "ruleVersion": CANDIDATE_RULE_VERSION,
    }
