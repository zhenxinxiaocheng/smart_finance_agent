import { arrow, bg, C, footer, node, text, title } from "./common.mjs";

export async function slide03(presentation, ctx) {
  const slide = presentation.slides.add();
  bg(slide, ctx);
  title(slide, ctx, "技术闭环：从账单图片到 Agent 分析");
  const y = 214;
  const steps = [
    ["账单图像\n多平台截图", 74, C.panel, C.cyan],
    ["CNN 分类\n来源 / 版式", 282, C.panel2, C.orange],
    ["OCR 抽取\n金额 / 时间 / 商户", 490, C.panel2, C.violet],
    ["统一流水库\n去重 / 分类 / 归档", 698, C.panel2, C.cyan],
    ["RAG + Agent\n预算 / 预警 / 建议", 906, C.panel2, C.green],
  ];
  for (let i = 0; i < steps.length; i += 1) {
    const [label, x, fill, accent] = steps[i];
    node(slide, ctx, x, y, 168, 128, label, accent, fill);
    if (i < steps.length - 1) arrow(slide, ctx, x + 172, y + 50, 34, accent);
  }
  ctx.addShape(slide, { x: 126, y: 438, width: 1028, height: 86, fill: "#0F2437", line: ctx.line("#31536B", 1) });
  text(slide, ctx, {
    x: 162,
    y: 462,
    width: 956,
    height: 36,
    text: "图像分类是高质量数据入口；财务 Agent 是最终价值出口",
    fontSize: 25,
    bold: true,
    color: "#D8F4F6",
    align: "center",
  });
  await ctx.addLucideIcon(slide, { icon: "DatabaseZap", x: 338, y: 560, width: 40, height: 40, color: C.cyan });
  await ctx.addLucideIcon(slide, { icon: "SearchCheck", x: 618, y: 560, width: 40, height: 40, color: C.orange });
  await ctx.addLucideIcon(slide, { icon: "Bot", x: 898, y: 560, width: 40, height: 40, color: C.green });
  footer(slide, ctx, 3);
  return slide;
}
