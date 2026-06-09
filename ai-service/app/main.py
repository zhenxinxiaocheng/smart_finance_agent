import base64
import io
import json
import mimetypes
import os
import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, List, Optional

import requests
from fastapi import FastAPI, File, UploadFile
from pydantic import BaseModel, Field


BILL_TYPES = {"WECHAT", "ALIPAY", "BANK", "NON_BILL", "LOW_QUALITY", "UNKNOWN", "ANALYSIS_FAILED"}
CNN_CLASS_TO_BILL_TYPE = {
    "wechat": "WECHAT",
    "weixin": "WECHAT",
    "alipay": "ALIPAY",
    "bank": "BANK",
    "bankcard": "BANK",
    "non_bill": "NON_BILL",
    "non-bill": "NON_BILL",
    "low_quality": "LOW_QUALITY",
}
DEFAULT_MODEL = "qwen3.5-omni-plus-2026-03-15"
DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
DEFAULT_CNN_MODEL_PATH = "models/bill_resnet18.pt"
DEFAULT_CNN_THRESHOLD = Decimal("0.60")

app = FastAPI(title="Smart Finance CNN + Multimodal Bill AI Service")


class CandidateTransaction(BaseModel):
    amount: Decimal
    type: str = "EXPENSE"
    category: str = "其他"
    description: str = "账单识别导入"
    transactionDate: str = Field(default_factory=lambda: date.today().isoformat())
    confidence: Decimal = Decimal("0.60")


class BillAnalysisResponse(BaseModel):
    billType: str
    confidence: Decimal
    ocrText: str = ""
    candidates: List[CandidateTransaction] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)


@dataclass
class CnnClassification:
    bill_type: str
    confidence: Decimal
    ready: bool
    warning: str = ""


class BillCnnClassifier:
    def __init__(self, model_path: str):
        self.model_path = Path(model_path)
        self._loaded = False
        self._ready = False
        self._warning = ""
        self._model = None
        self._classes: list[str] = []
        self._device = None
        self._transform = None
        self._torch = None
        self._image_cls = None

    @property
    def ready(self) -> bool:
        self._ensure_loaded()
        return self._ready

    @property
    def warning(self) -> str:
        self._ensure_loaded()
        return self._warning

    def predict(self, image_bytes: bytes) -> CnnClassification:
        self._ensure_loaded()
        if not self._ready:
            return CnnClassification(
                bill_type="ANALYSIS_FAILED",
                confidence=Decimal("0.00"),
                ready=False,
                warning=self._warning or "CNN 模型未加载，请先训练或配置 BILL_CNN_MODEL_PATH",
            )

        try:
            image = self._image_cls.open(io.BytesIO(image_bytes)).convert("RGB")
            tensor = self._transform(image).unsqueeze(0).to(self._device)
            with self._torch.no_grad():
                logits = self._model(tensor)
                probs = self._torch.softmax(logits, dim=1)[0]
            confidence_float, index_tensor = self._torch.max(probs, dim=0)
            class_name = self._classes[int(index_tensor.item())]
            bill_type = normalize_cnn_class(class_name)
            confidence = decimal_between_zero_and_one(confidence_float.item(), Decimal("0.00"))
            return CnnClassification(bill_type=bill_type, confidence=confidence, ready=True)
        except Exception as exc:
            return CnnClassification(
                bill_type="ANALYSIS_FAILED",
                confidence=Decimal("0.00"),
                ready=False,
                warning=f"CNN 推理失败：{exc}",
            )

    def _ensure_loaded(self) -> None:
        if self._loaded:
            return
        self._loaded = True

        if not self.model_path.exists():
            self._warning = f"CNN 模型文件不存在：{self.model_path}"
            return

        try:
            import torch
            from PIL import Image
            from torch import nn
            from torchvision import models, transforms
        except Exception as exc:
            self._warning = f"CNN 运行依赖未安装：{exc}"
            return

        try:
            self._torch = torch
            self._image_cls = Image
            self._device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            checkpoint = torch.load(self.model_path, map_location=self._device, weights_only=True)
            self._classes = list(checkpoint.get("classes") or [])
            if not self._classes:
                self._warning = "CNN checkpoint 缺少 classes 字段"
                return

            model = models.resnet18(weights=None)
            model.fc = nn.Linear(model.fc.in_features, len(self._classes))
            model.load_state_dict(checkpoint["state_dict"])
            model.to(self._device)
            model.eval()

            self._model = model
            self._transform = transforms.Compose(
                [
                    transforms.Resize((224, 224)),
                    transforms.ToTensor(),
                    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
                ]
            )
            self._ready = True
        except Exception as exc:
            self._warning = f"CNN 模型加载失败：{exc}"


_bill_classifier: Optional[BillCnnClassifier] = None


def get_bill_classifier() -> BillCnnClassifier:
    global _bill_classifier
    configured_path = os.getenv("BILL_CNN_MODEL_PATH", DEFAULT_CNN_MODEL_PATH).strip() or DEFAULT_CNN_MODEL_PATH
    if _bill_classifier is None or str(_bill_classifier.model_path) != configured_path:
        _bill_classifier = BillCnnClassifier(configured_path)
    return _bill_classifier


@app.get("/health")
def health():
    classifier = get_bill_classifier()
    return {
        "status": "ok",
        "mode": "cnn-classifier-plus-multimodal-extractor",
        "cnnModelPath": str(classifier.model_path),
        "cnnModelReady": classifier.ready,
        "cnnWarning": classifier.warning,
        "cnnThreshold": str(cnn_confidence_threshold()),
        "provider": "dashscope-compatible",
        "multimodalModel": os.getenv("DASHSCOPE_VL_MODEL", DEFAULT_MODEL),
        "endpoint": os.getenv("DASHSCOPE_VL_ENDPOINT", DEFAULT_ENDPOINT),
        "apiKeyConfigured": bool(os.getenv("DASHSCOPE_API_KEY", "").strip()),
    }


@app.post("/api/ai/bill/analyze", response_model=BillAnalysisResponse)
async def analyze_bill(file: UploadFile = File(...)):
    image_bytes = await file.read()
    filename = file.filename or "bill-image"

    if not image_bytes:
        return BillAnalysisResponse(
            billType="LOW_QUALITY",
            confidence=Decimal("0.00"),
            warnings=["上传图片为空，无法识别。"],
        )

    cnn_result = get_bill_classifier().predict(image_bytes)
    threshold = cnn_confidence_threshold()

    if not cnn_result.ready or cnn_result.bill_type in {"NON_BILL", "LOW_QUALITY"} or cnn_result.confidence < threshold:
        return merge_cnn_and_multimodal_result(cnn_result, {}, threshold)

    try:
        extraction = call_multimodal_bill_model(filename, file.content_type, image_bytes)
    except Exception as exc:
        extraction = {
            "ocrText": "",
            "candidates": [],
            "warnings": [f"多模态文本抽取失败：{exc}"],
        }
    return merge_cnn_and_multimodal_result(cnn_result, extraction, threshold)


def call_multimodal_bill_model(filename: str, content_type: Optional[str], image_bytes: bytes) -> dict[str, Any]:
    api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("未配置 DASHSCOPE_API_KEY")

    endpoint = os.getenv("DASHSCOPE_VL_ENDPOINT", DEFAULT_ENDPOINT).strip() or DEFAULT_ENDPOINT
    model = os.getenv("DASHSCOPE_VL_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    image_url = to_data_url(filename, content_type, image_bytes)
    today = date.today().isoformat()

    payload = {
        "model": model,
        "temperature": 0.1,
        "modalities": ["text"],
        "messages": [
            {
                "role": "system",
                "content": [
                    {
                        "type": "text",
                        "text": (
                            "你是个人财务系统的账单图像文本抽取器。"
                            "你的职责只包括读取图片中的可见文本、交易行、金额、日期和类别线索。"
                            "不要判断账单来源，不要输出推理过程，不要输出 Markdown，只输出严格 JSON。"
                        ),
                    }
                ],
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": (
                            "请直接观察这张图片，抽取可导入的候选交易。"
                            "返回 JSON schema："
                            '{"ocrText":"图片中与交易有关的简短文本摘要",'
                            '"candidates":[{"amount":35.00,"type":"EXPENSE|INCOME",'
                            '"category":"餐饮/交通/购物/工资/投资理财/教育培训/其他等",'
                            '"description":"简短交易描述，若图片出现时分如 18:06 请保留在这里",'
                            '"transactionDate":"YYYY-MM-DD",'
                            '"confidence":0.0}],'
                            '"warnings":["低置信度、图片模糊、信息缺失等提示"]}'
                            "。金额只抽取确定的交易金额，不要把余额、优惠、积分当作交易金额。"
                        ),
                    },
                    {
                        "type": "text",
                        "text": (
                            f"当前日期是 {today}。图片中如果出现“今天”或“今日”，transactionDate 必须填写 {today}。"
                            "如果出现类似“今天 18:06”的时间，日期仍填写当前日期，并把 18:06 保留在 description 中。"
                            "系统当前只存交易日期，不单独存时分秒。"
                        ),
                    },
                    {"type": "image_url", "image_url": {"url": image_url}},
                ],
            },
        ],
    }

    response = requests.post(
        endpoint,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=60,
    )
    if not response.ok:
        raise RuntimeError(f"{response.status_code} {response.text[:500]}")
    data = response.json()
    content = data["choices"][0]["message"]["content"]
    return parse_json_object(content)


def merge_cnn_and_multimodal_result(
    cnn_result: CnnClassification,
    multimodal_result: Optional[dict[str, Any]],
    threshold: Decimal = DEFAULT_CNN_THRESHOLD,
) -> BillAnalysisResponse:
    raw = multimodal_result or {}
    bill_type = normalize_bill_type(cnn_result.bill_type)
    confidence = decimal_between_zero_and_one(cnn_result.confidence, Decimal("0.00"))
    visual_summary = str(raw.get("ocrText") or raw.get("visualText") or "")
    warnings = normalize_warnings(raw.get("warnings"))

    if cnn_result.warning:
        warnings.insert(0, cnn_result.warning)

    if not cnn_result.ready:
        return BillAnalysisResponse(
            billType="ANALYSIS_FAILED",
            confidence=Decimal("0.00"),
            ocrText=visual_summary,
            candidates=[],
            warnings=dedupe_warnings(warnings or ["CNN 模型未加载，请先训练或配置 BILL_CNN_MODEL_PATH。"]),
        )

    candidates: list[CandidateTransaction] = []
    if bill_type in {"NON_BILL", "LOW_QUALITY"}:
        warnings.append("CNN 判断该图片不是可导入账单，未生成候选交易。")
    elif confidence < threshold:
        warnings.append(f"CNN 分类置信度低于阈值 {threshold}，未生成候选交易。")
    else:
        for item in raw.get("candidates") or []:
            candidate = normalize_candidate(item, visual_summary)
            if candidate is not None:
                candidate.confidence = min(candidate.confidence, confidence)
                candidates.append(candidate)

    return BillAnalysisResponse(
        billType=bill_type,
        confidence=confidence,
        ocrText=visual_summary,
        candidates=candidates,
        warnings=dedupe_warnings(warnings),
    )


def normalize_model_result(raw: dict[str, Any]) -> BillAnalysisResponse:
    """Compatibility wrapper for older tests or scripts."""
    cnn_result = CnnClassification(
        bill_type=str(raw.get("billType") or "UNKNOWN").upper(),
        confidence=decimal_between_zero_and_one(raw.get("confidence"), Decimal("0.00")),
        ready=True,
    )
    return merge_cnn_and_multimodal_result(cnn_result, raw)


def to_data_url(filename: str, content_type: Optional[str], image_bytes: bytes) -> str:
    mime_type = content_type or mimetypes.guess_type(filename)[0] or "image/png"
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{mime_type};base64,{encoded}"


def parse_json_object(content: Any) -> dict[str, Any]:
    if isinstance(content, list):
        content = "".join(item.get("text", "") if isinstance(item, dict) else str(item) for item in content)
    if not isinstance(content, str):
        raise ValueError("模型返回内容不是文本")

    text = content.strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.S)
        if not match:
            raise ValueError("模型未返回 JSON 对象")
        return json.loads(match.group(0))


def normalize_candidate(item: Any, visual_summary: str = "") -> Optional[CandidateTransaction]:
    if not isinstance(item, dict):
        return None
    amount = positive_decimal(item.get("amount"))
    if amount is None:
        return None

    tx_type = str(item.get("type") or "EXPENSE").upper()
    if tx_type not in {"EXPENSE", "INCOME"}:
        tx_type = "EXPENSE"

    transaction_date = str(item.get("transactionDate") or date.today().isoformat())
    if not re.fullmatch(r"20\d{2}-\d{2}-\d{2}", transaction_date):
        transaction_date = date.today().isoformat()
    if mentions_today(item, visual_summary):
        transaction_date = date.today().isoformat()

    return CandidateTransaction(
        amount=amount,
        type=tx_type,
        category=str(item.get("category") or "其他")[:50],
        description=str(item.get("description") or "账单识别导入")[:500],
        transactionDate=transaction_date,
        confidence=decimal_between_zero_and_one(item.get("confidence"), Decimal("0.60")),
    )


def normalize_bill_type(value: Any) -> str:
    bill_type = str(value or "UNKNOWN").upper()
    return bill_type if bill_type in BILL_TYPES else "UNKNOWN"


def normalize_cnn_class(class_name: str) -> str:
    normalized = str(class_name or "").strip().lower()
    return CNN_CLASS_TO_BILL_TYPE.get(normalized, normalize_bill_type(normalized.upper()))


def mentions_today(item: dict[str, Any], visual_summary: str) -> bool:
    text = json.dumps(item, ensure_ascii=False) + " " + (visual_summary or "")
    return "今天" in text or "今日" in text


def positive_decimal(value: Any) -> Optional[Decimal]:
    try:
        amount = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return amount if amount > 0 else None


def decimal_between_zero_and_one(value: Any, fallback: Decimal) -> Decimal:
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return fallback
    if parsed < 0:
        return Decimal("0.00")
    if parsed > 1:
        return Decimal("1.00")
    return parsed.quantize(Decimal("0.0001"))


def cnn_confidence_threshold() -> Decimal:
    return decimal_between_zero_and_one(os.getenv("BILL_CNN_CONFIDENCE_THRESHOLD"), DEFAULT_CNN_THRESHOLD)


def normalize_warnings(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item).strip()]
    text = str(value).strip()
    return [text] if text else []


def dedupe_warnings(warnings: list[str]) -> list[str]:
    seen = set()
    result = []
    for warning in warnings:
        normalized = str(warning).strip()
        if normalized and normalized not in seen:
            seen.add(normalized)
            result.append(normalized)
    return result
