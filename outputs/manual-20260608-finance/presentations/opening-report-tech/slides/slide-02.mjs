import { bg, C, footer, kicker, metric, text, title } from "./common.mjs";

export async function slide02(presentation, ctx) {
  const slide = presentation.slides.add();
  bg(slide, ctx);
  title(slide, ctx, "问题定义：消费数据分布在多个孤岛");
  kicker(slide, ctx, "不是数据不真实，而是统一采集和长期维护不够便捷");

  ctx.addShape(slide, { geometry: "ellipse", x: 512, y: 224, width: 240, height: 240, fill: C.cyan2, line: ctx.line(C.cyan, 2) });
  text(slide, ctx, {
    x: 554,
    y: 292,
    width: 156,
    height: 78,
    text: "统一\n交易流水库",
    fontSize: 28,
    bold: true,
    align: "center",
  });
  const items = [
    ["微信支付", "MessageCircle", 166, 190, C.cyan],
    ["支付宝", "WalletCards", 880, 190, C.violet],
    ["银行卡 A/B", "CreditCard", 168, 438, C.orange],
    ["信用卡", "BadgeDollarSign", 890, 438, C.green],
  ];
  for (const [label, icon, x, y, color] of items) {
    ctx.addShape(slide, { x, y, width: 210, height: 78, fill: C.panel, line: ctx.line("#31536B", 1) });
    await ctx.addLucideIcon(slide, { icon, x: x + 22, y: y + 20, width: 36, height: 36, color, strokeWidth: 1.8 });
    text(slide, ctx, { x: x + 70, y: y + 26, width: 118, height: 28, text: label, fontSize: 17, bold: true });
    const start = x < 512 ? x + 210 : 752;
    const end = x < 512 ? 512 : x;
    ctx.addShape(slide, { x: Math.min(start, end), y: y + 38, width: Math.abs(end - start), height: 3, fill: "#31536B" });
  }
  metric(slide, ctx, 512, 546, "1 个", "统一数据视角", C.cyan);
  text(slide, ctx, {
    x: 724,
    y: 552,
    width: 390,
    height: 46,
    text: "将分散账单沉淀为可分析的个人财务数据资产",
    fontSize: 18,
    bold: true,
    color: "#D8F4F6",
  });
  footer(slide, ctx, 2);
  return slide;
}
