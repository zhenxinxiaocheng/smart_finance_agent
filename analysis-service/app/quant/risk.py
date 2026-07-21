from __future__ import annotations

from .config import QuantConfig


def size_target_weight(*, product_type: str, action: str, confidence: str,
                       market_regime: str, annualized_volatility: float,
                       current_weight: float, config: QuantConfig) -> float:
    """Translate a validated signal into a long-only portfolio target."""
    normalized_type = product_type.strip().upper()
    normalized_action = action.strip().upper()
    current = max(0.0, float(current_weight))
    maximum = config.number(f"risk.maximumAssetWeight.{normalized_type}")
    if normalized_action in {"NO_TRADE", "HOLD"}:
        return round(min(current, maximum), 8)
    if normalized_action == "EXIT":
        return 0.0
    if normalized_action == "REDUCE":
        multiplier = config.number("risk.actionMultiplier.REDUCE")
        return round(min(current * multiplier, maximum), 8)

    volatility_floor = config.number("risk.minimumAnnualizedVolatility")
    usable_volatility = max(float(annualized_volatility), volatility_floor)
    volatility_weight = config.number("risk.targetAnnualizedVolatility") / usable_volatility
    confidence_multiplier = config.number(
        f"risk.confidenceMultiplier.{confidence.strip().upper()}"
    )
    regime_multiplier = config.number(
        f"risk.regimeMultiplier.{market_regime.strip().upper()}"
    )
    action_multiplier = config.number(f"risk.actionMultiplier.{normalized_action}")
    target = min(maximum, volatility_weight * confidence_multiplier * regime_multiplier * action_multiplier)
    minimum = config.number("risk.minimumTradeWeight")
    return round(0.0 if target < minimum else target, 8)
