import { bg, C, chip, footer, text } from "./common.mjs";

export async function slide01(presentation, ctx) {
  const slide = presentation.slides.add();
  bg(slide, ctx);
  ctx.addShape(slide, { x: 86, y: 116, width: 8, height: 330, fill: C.cyan });
  ctx.addShape(slide, { x: 102, y: 116, width: 3, height: 330, fill: C.violet });
  text(slide, ctx, {
    x: 132,
    y: 108,
    width: 790,
    height: 150,
    text: "融合迁移学习账单图像分类与 RAG 的\n智能财务 Agent 系统",
    fontSize: 43,
    bold: true,
  });
  text(slide, ctx, {
    x: 136,
    y: 286,
    width: 760,
    height: 34,
    text: "多源消费流水聚合 · 账单图像识别 · 数据驱动财务分析",
    fontSize: 19,
    color: C.muted,
  });
  chip(slide, ctx, 136, 372, 150, "CNN 迁移学习", C.cyan);
  chip(slide, ctx, 306, 372, 88, "OCR", C.violet);
  chip(slide, ctx, 414, 372, 86, "RAG", C.orange);
  chip(slide, ctx, 520, 372, 132, "ReAct Agent", C.green);

  ctx.addShape(slide, { geometry: "ellipse", x: 930, y: 122, width: 190, height: 190, fill: "#0F2437", line: ctx.line(C.cyan, 2) });
  await ctx.addLucideIcon(slide, { icon: "BrainCircuit", x: 978, y: 168, width: 94, height: 94, color: C.cyan, strokeWidth: 1.5 });
  ctx.addShape(slide, { geometry: "ellipse", x: 1018, y: 336, width: 106, height: 106, fill: "#18223A", line: ctx.line(C.violet, 2) });
  await ctx.addLucideIcon(slide, { icon: "ReceiptText", x: 1046, y: 363, width: 52, height: 52, color: C.orange, strokeWidth: 1.7 });
  ctx.addShape(slide, { x: 890, y: 470, width: 270, height: 2, fill: "#31536B" });
  text(slide, ctx, {
    x: 136,
    y: 520,
    width: 620,
    height: 28,
    text: "期末项目前期开题 · 2 分钟汇报",
    fontSize: 17,
    color: "#7EA4B4",
  });
  footer(slide, ctx, 1);
  return slide;
}
