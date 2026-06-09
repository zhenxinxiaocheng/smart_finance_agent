import { bg, C, footer, glass, text, title } from "./common.mjs";

export async function slide05(presentation, ctx) {
  const slide = presentation.slides.add();
  bg(slide, ctx);
  title(slide, ctx, "现有基础与预期交付");

  glass(slide, ctx, 92, 154, 500, 310, C.cyan, C.panel);
  text(slide, ctx, { x: 126, y: 188, width: 410, height: 32, text: "已有系统能力", fontSize: 23, bold: true });
  text(slide, ctx, {
    x: 126,
    y: 252,
    width: 410,
    height: 150,
    text: "交易记录与分类管理\n预算管理与风险预警\n收支统计图表\nRAG 知识增强\n显式 ReAct 财务 Agent",
    fontSize: 18,
    color: "#D8EAF0",
  });

  glass(slide, ctx, 688, 154, 500, 310, C.orange, C.panel);
  text(slide, ctx, { x: 722, y: 188, width: 410, height: 32, text: "本阶段新增交付", fontSize: 23, bold: true });
  text(slide, ctx, {
    x: 722,
    y: 252,
    width: 410,
    height: 150,
    text: "账单图像数据集\n迁移学习分类模型\nOCR 结构化导入\n端到端系统演示\n实验结果与期末论文",
    fontSize: 18,
    color: "#D8EAF0",
  });

  ctx.addShape(slide, { x: 126, y: 548, width: 1028, height: 54, fill: "#0F2437", line: ctx.line("#31536B", 1) });
  text(slide, ctx, {
    x: 156,
    y: 563,
    width: 968,
    height: 24,
    text: "第1周 数据采集与标注  →  第2周 模型训练与对比  →  第3周 系统集成与测试  →  期末 论文与答辩",
    fontSize: 16,
    bold: true,
    color: "#D8F4F6",
    align: "center",
  });
  footer(slide, ctx, 5);
  return slide;
}
