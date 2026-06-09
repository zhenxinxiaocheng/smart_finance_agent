from decimal import Decimal
import sys
import types


try:
    import fastapi  # noqa: F401
except ModuleNotFoundError:
    class DummyFastAPI:
        def __init__(self, *args, **kwargs):
            pass

        def get(self, *args, **kwargs):
            return lambda func: func

        def post(self, *args, **kwargs):
            return lambda func: func

    sys.modules["fastapi"] = types.SimpleNamespace(
        FastAPI=DummyFastAPI,
        File=lambda *args, **kwargs: None,
        UploadFile=object,
    )

try:
    import pydantic  # noqa: F401
except ModuleNotFoundError:
    class DummyFieldInfo:
        def __init__(self, default=None, default_factory=None):
            self.default = default
            self.default_factory = default_factory

        def resolve(self):
            if self.default_factory:
                return self.default_factory()
            return self.default

    class DummyBaseModel:
        def __init__(self, **data):
            annotations = {}
            for cls in reversed(self.__class__.mro()):
                annotations.update(getattr(cls, "__annotations__", {}))
            for name in annotations:
                if name in data:
                    value = data[name]
                else:
                    value = getattr(self.__class__, name, None)
                    if isinstance(value, DummyFieldInfo):
                        value = value.resolve()
                setattr(self, name, value)

    sys.modules["pydantic"] = types.SimpleNamespace(
        BaseModel=DummyBaseModel,
        Field=lambda default=None, default_factory=None, **kwargs: DummyFieldInfo(default, default_factory),
    )

from app.main import (
    BillAnalysisResponse,
    CnnClassification,
    merge_cnn_and_multimodal_result,
)


def test_cnn_classification_is_authoritative_for_bill_type_and_confidence():
    cnn = CnnClassification(bill_type="WECHAT", confidence=Decimal("0.92"), ready=True)
    multimodal = {
        "billType": "ALIPAY",
        "confidence": 0.99,
        "ocrText": "微信账单 午餐 35 元 今天 12:10",
        "candidates": [
            {
                "amount": 35,
                "type": "EXPENSE",
                "category": "餐饮",
                "description": "午餐 今天 12:10",
                "transactionDate": "2026-06-01",
                "confidence": 0.80,
            }
        ],
        "warnings": [],
    }

    result = merge_cnn_and_multimodal_result(cnn, multimodal)

    assert result.billType == "WECHAT"
    assert result.confidence == Decimal("0.92")
    assert len(result.candidates) == 1
    assert result.candidates[0].confidence == Decimal("0.80")


def test_low_confidence_cnn_result_blocks_candidate_generation():
    cnn = CnnClassification(bill_type="ALIPAY", confidence=Decimal("0.42"), ready=True)
    multimodal = {
        "ocrText": "支付宝账单 午餐 35 元",
        "candidates": [{"amount": 35, "type": "EXPENSE", "category": "餐饮", "confidence": 0.95}],
    }

    result = merge_cnn_and_multimodal_result(cnn, multimodal, threshold=Decimal("0.60"))

    assert result.billType == "ALIPAY"
    assert result.candidates == []
    assert any("CNN" in warning for warning in result.warnings)


def test_missing_cnn_model_returns_analysis_failed_without_candidates():
    cnn = CnnClassification(
        bill_type="ANALYSIS_FAILED",
        confidence=Decimal("0.00"),
        ready=False,
        warning="CNN 模型未加载，请先训练或配置 BILL_CNN_MODEL_PATH",
    )
    multimodal = {
        "ocrText": "微信账单 午餐 35 元",
        "candidates": [{"amount": 35, "type": "EXPENSE", "category": "餐饮", "confidence": 0.95}],
    }

    result = merge_cnn_and_multimodal_result(cnn, multimodal)

    assert isinstance(result, BillAnalysisResponse)
    assert result.billType == "ANALYSIS_FAILED"
    assert result.confidence == Decimal("0.00")
    assert result.candidates == []
    assert "CNN 模型未加载" in " ".join(result.warnings)
