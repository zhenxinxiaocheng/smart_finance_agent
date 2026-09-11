"""Versioned parameter defaults shared by execution and the editor API."""
from copy import deepcopy

PARAMETERS = [
    ("lookback", 20, 2, 500, "integer", "研究起点", "约一个交易月，用于动量和波动估计。动量方法有实证研究；20日不是该资产池已验证的最优窗口。", "momentum"),
    ("slowWindow", 60, 3, 1000, "integer", "研究起点", "约一个交易季度，用于价格相对均线的趋势过滤；60日均线是工程起点，不能视为论文验证的参数。", "momentum"),
    ("topN", 5, 1, 100, "integer", "组合约束", "限制组合规模；5个标的不代表充分分散，股票、基金的相关性和集中度需另行评估。", None),
    ("rebalanceDays", 5, 1, 252, "integer", "研究起点", "每5个引擎交易会话检查调仓；是响应速度与成本的折中假设，需要扣费后的样本外比较。", None),
    ("predictionHorizon", 5, 1, 60, "integer", "研究起点", "从可执行入场日起预测后续5个观测期收益；需要按预测任务验证。", None),
    ("seed", 42, 0, 2147483647, "integer", "复现设置", "固定随机过程便于复现，42没有收益优势。", None),
    ("publicationLagDays", 1, 0, 30, "integer", "成交假设", "缺少实际公布时点时使用的日历日延迟，需以基金实际数据为准。", None),
    ("settlementDays", 2, 0, 30, "integer", "成交假设", "模拟赎回款日历日到账延迟，不代表所有基金的实际到账期限。", None),
    ("fundShareDecimals", 4, 2, 6, "integer", "成交假设", "份额精度需与产品规则核对。", None),
    ("targetVol", .15, .001, 2, "number", "风险预算", "仅趋势策略使用：单标的历史年化波动超过15%时按比例降仓，不加杠杆；并非组合波动目标。研究支持波动管理方法，未证明15%最优。", "volatility"),
    ("maxWeight", .3, .001, 1, "number", "组合约束", "单标的目标权重上限30%，未分配资金留现金；持仓价格变化后可能超过该比例。30%是预算选择。", None),
    ("initialCash", 100000, 1, 1e12, "number", "模拟设置", "模拟本金10万元；应按计划资金调整，整数手和费用会影响结果。", None),
    ("feeRate", .001, 0, .1, "number", "成交假设", "买入按成交金额0.1%计费；需换成实际费率。", None),
    ("sellFeeRate", .001, 0, .1, "number", "成交假设", "卖出按成交金额0.1%计费；不代表基金按持有期分档的真实费率。", None),
    ("slippageBps", 5, 0, 1000, "number", "成交假设", "成交价格偏移5基点，需用真实成交数据校准。", None),
    ("maxDrawdown", .25, .001, 1, "number", "风险预算", "权益较历史峰值回撤25%触发停止买入和清仓订单；延迟成交可能导致最终损失超过25%，不是保本承诺。", None),
]

SOURCES = {
    "momentum": {"title": "Moskowitz、Ooi、Pedersen（2012），Time Series Momentum", "url": "https://research.cbs.dk/en/publications/time-series-momentum/"},
    "volatility": {"title": "Moreira、Muir（2017），Volatility-Managed Portfolios", "url": "https://www.nber.org/papers/w22208"},
}


def parameter_catalog():
    return deepcopy({"version": "parameters-v1", "validationStatus": "UNVALIDATED",
        "notice": "方法有研究支持，具体默认数值尚未完成当前资产池的样本外验证。风险预算由资金承受能力决定，费用和到账期限需核对实际条款。",
        "parameters": [{"key": k, "default": d, "min": lo, "max": hi, "type": t,
                        "category": category, "basis": basis, "source": SOURCES.get(source)}
                       for k, d, lo, hi, t, category, basis, source in PARAMETERS]})
