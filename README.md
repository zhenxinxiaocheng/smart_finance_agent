# 智财 Agent

个人智能财务代理系统，当前分支为机器学习期末作业成果分支：在原有记账、统计、AI 财务助手和 RAG 知识库能力上，新增“账单截图导入”模块，并使用 CNN 迁移学习模型完成账单来源/质量判别，再由多模态大模型抽取交易内容。

## 分支说明

- 当前分支：`codex/transfer-learning-image-tech`
- 课程目标：展示 CNN 迁移学习在账单图像分类中的应用。
- 核心成果：用户上传微信、支付宝或银行卡流水截图后，系统先用本地训练的 CNN 分类器判断图片类型和置信度，再用多模态大模型抽取候选交易，最后由用户确认后写入正式交易记录。

## 主要功能

- 用户注册、登录与 JWT 鉴权。
- 收入/支出记录管理、分类筛选、统计图表和仪表盘。
- 基于 LangChain4j 与 DashScope 的 AI 财务助手。
- RAG 理财知识库问答和 Tavily 联网搜索。
- 账单截图导入：
  - CNN 分类账单来源：微信、支付宝、银行卡流水、非账单图片。
  - 多模态大模型抽取金额、类型、分类、日期、描述和置信度。
  - 用户确认候选交易后才写入正式消费记录，避免自动误入账。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2.5, MyBatis-Plus, MySQL 8, JWT |
| AI Agent | LangChain4j, DashScope, RAG, Tavily Search |
| 前端 | Vue 3, Vite, Element Plus, Pinia, Vue Router, Axios, ECharts |
| AI 服务 | Python, FastAPI, PyTorch, TorchVision, Pillow |
| 机器学习 | ResNet-18 迁移学习, CNN 图像分类, Grad-CAM 可解释性辅助 |

## 项目结构

```text
smart_finance_agent/
├── backend/                 # Spring Boot 后端、业务接口、数据库访问
├── frontend/                # Vue 3 前端页面
├── ai-service/              # Python AI 服务：CNN 分类 + 多模态抽取
│   ├── app/main.py          # 账单分析接口 /api/ai/bill/analyze
│   ├── training/            # CNN 训练、评估、Grad-CAM 脚本
│   ├── tests/               # AI 服务单元测试
│   └── README.md            # AI 服务和训练说明
├── docs/                    # 课程交付物与演示资料
└── README.md                # 当前分支总说明
```

## 本地运行

### 1. 准备环境

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Node.js 18+
- Python 3.10+

### 2. 配置数据库

```sql
CREATE DATABASE IF NOT EXISTS smart_finance
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

复制本地配置模板并填写数据库密码、JWT 密钥、DashScope Key、Tavily Key：

```powershell
copy env.example backend\src\main\resources\application-local.yml
```

### 3. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认地址：`http://localhost:8080`。

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

默认地址：`http://localhost:3000`。

### 5. 启动 AI 服务

```powershell
cd ai-service
python -m venv .venv-ai
.\.venv-ai\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8090
```

关键环境变量：

```text
DASHSCOPE_API_KEY=你的 DashScope API Key
BILL_CNN_MODEL_PATH=models/bill_resnet18.pt
BILL_CNN_CONFIDENCE_THRESHOLD=0.60
```

## CNN 训练说明

账单分类模型位于 `ai-service/training/`，数据集默认按类别拆分：

```text
dataset/
├── train/
│   ├── wechat/
│   ├── alipay/
│   ├── bank/
│   └── non_bill/
└── val/
    ├── wechat/
    ├── alipay/
    ├── bank/
    └── non_bill/
```

训练 ResNet-18 基线模型：

```powershell
cd ai-service
python training/train_resnet18.py --data-dir dataset --output models/bill_resnet18.pt --epochs 10 --freeze-backbone
```

进一步微调：

```powershell
python training/train_resnet18.py --data-dir dataset --output models/bill_resnet18_finetune.pt --epochs 10
```

训练完成后将模型路径配置为 `BILL_CNN_MODEL_PATH`，AI 服务会在账单导入时加载该模型。

## 账单导入流程

1. 前端上传账单截图到后端 `/api/bills/import`。
2. 后端保存原始图片并调用 Python AI 服务。
3. AI 服务先执行 CNN 分类，得到 `billType` 和 `confidence`。
4. 当 CNN 结果可用且置信度达标时，AI 服务调用多模态模型抽取候选交易。
5. 后端保存识别记录和候选交易。
6. 用户在前端检查并确认候选交易。
7. 后端将确认项写入正式交易表。

## 测试

后端测试：

```powershell
cd backend
mvn test
```

前端构建：

```powershell
cd frontend
npm run build
```

AI 服务测试：

```powershell
cd ai-service
pytest
```

## 说明

- `application-local.yml`、模型文件、训练数据、上传图片和本地输出产物不提交到仓库。
- 该分支保留机器学习课程相关实现；后续软件过程与项目管理课程会在新分支中移除 CNN 与 Python AI 服务，并将账单识别改为后端直接调用多模态大模型。
