# Analysis Service

本机 Python sidecar，负责市场数据 Provider、标准化、数据质量与组合指标。业务数据库只由 Spring Boot 写入。

```powershell
python -m pip install -r requirements.txt
$env:ANALYSIS_INTERNAL_TOKEN='dev-analysis-token'
python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

可选配置 `TUSHARE_TOKEN`；未配置时按 AKShare、BaoStock 顺序降级。
