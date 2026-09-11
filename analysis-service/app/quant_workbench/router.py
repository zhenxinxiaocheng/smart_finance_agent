from fastapi import APIRouter, HTTPException

from .engine import EngineError, execute, FACTOR_KEYS, runtime_info
from .parameters import parameter_catalog
from .sensitivity import CandidateError, generate_candidates

router = APIRouter(prefix="/quant/v2", tags=["quant-workbench"])


@router.get("/parameters")
def parameters() -> dict:
    return parameter_catalog()


@router.get("/runtime-info")
def runtime() -> dict:
    return runtime_info()


@router.post("/parameter-sensitivity/candidates")
def sensitivity_candidates(payload: dict) -> dict:
    try:
        return generate_candidates(payload.get("config") or {}, str(payload.get("parameterKey") or ""))
    except CandidateError as exc:
        raise HTTPException(status_code=422, detail={"code": exc.code, "message": str(exc)}) from exc


@router.get("/factors")
def factor_catalog() -> list:
    names = {"momentum": "动量", "trend": "均线趋势", "volatility": "低波动", "drawdown": "回撤", "reversal": "短期反转", "volume": "成交量变化", "liquidity": "流动性"}
    return [{"key": key, "name": names[key], "assetClasses": ["STOCK", "ETF"] if key in ("volume", "liquidity") else ["STOCK", "ETF", "FUND"]} for key in FACTOR_KEYS]


@router.post("/execute")
def execute_quant(payload: dict) -> dict:
    try:
        return execute(payload)
    except EngineError as exc:
        raise HTTPException(status_code=422, detail={"code": exc.code, "message": str(exc)}) from exc
    except (KeyError, TypeError, ValueError, OverflowError) as exc:
        raise HTTPException(status_code=422, detail={"code": "INVALID_INPUT", "message": str(exc)}) from exc
