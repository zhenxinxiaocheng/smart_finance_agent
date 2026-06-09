# Bill Image AI Service

Python-side AI entry for bill image importing. The runtime path is now:

1. A locally trained CNN transfer-learning model classifies the screenshot as WeChat, Alipay, bank statement, or non-bill.
2. A multimodal LLM reads the same screenshot and extracts visible transaction text and candidate records.
3. The service fuses both results. `billType` and top-level `confidence` always come from the CNN result.

This keeps the machine-learning course contribution explicit: the trained CNN model owns the source/quality judgement, while the multimodal model replaces traditional OCR for text extraction.

## Run API Service

```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090
```

Environment variables:

```text
DASHSCOPE_API_KEY=your_dashscope_api_key
DASHSCOPE_VL_MODEL=qwen3.5-omni-plus-2026-03-15
DASHSCOPE_VL_ENDPOINT=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
BILL_CNN_MODEL_PATH=models/bill_resnet18.pt
BILL_CNN_CONFIDENCE_THRESHOLD=0.60
```

`DASHSCOPE_API_KEY` is required for multimodal extraction. `BILL_CNN_MODEL_PATH` is required for successful bill import. If the CNN checkpoint is missing or cannot be loaded, the service returns `ANALYSIS_FAILED` and does not let the multimodal model replace CNN classification.

The Spring Boot backend calls:

```text
POST /api/ai/bill/analyze
```

Compatible response shape:

```json
{
  "billType": "WECHAT",
  "confidence": 0.91,
  "ocrText": "short visual text summary",
  "candidates": [
    {
      "amount": 35.0,
      "type": "EXPENSE",
      "category": "餐饮",
      "description": "校园食堂午餐 12:10",
      "transactionDate": "2026-06-09",
      "confidence": 0.86
    }
  ],
  "warnings": []
}
```

`ocrText` is kept for backend/frontend compatibility, but its meaning is now visual text extracted by the multimodal model.

## CNN Training Path

Dataset layout:

```text
dataset/
  train/wechat/
  train/alipay/
  train/bank/
  train/non_bill/
  val/wechat/
  val/alipay/
  val/bank/
  val/non_bill/
  test/...
```

Train the first ResNet-18 baseline:

```bash
python training/train_resnet18.py --data-dir dataset --output models/bill_resnet18.pt --epochs 10 --freeze-backbone
```

Then train a partial fine-tuning comparison without `--freeze-backbone`:

```bash
python training/train_resnet18.py --data-dir dataset --output models/bill_resnet18_finetune.pt --epochs 10
```

The script saves a checkpoint containing `state_dict` and `classes`, which is the format loaded by the API service.
