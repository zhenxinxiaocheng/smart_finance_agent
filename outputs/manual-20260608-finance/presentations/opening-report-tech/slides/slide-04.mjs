import { bg, C, footer, glass, kicker, text, title } from "./common.mjs";

export async function slide04(presentation, ctx) {
  const slide = presentation.slides.add();
  bg(slide, ctx, true);
  title(slide, ctx, "算法验证：CNN 迁移学习账单分类", true);
  kicker(slide, ctx, "课程核心落点：模型对比、微调策略、可解释性分析", true);

  glass(slide, ctx, 84, 166, 334, 310, C.cyan, C.white);
  text(slide, ctx, { x: 116, y: 196, width: 270, height: 30, text: "分类任务", fontSize: 22, bold: true, color: C.ink });
  text(slide, ctx, {
    x: 116,
    y: 252,
    width: 270,
    height: 160,
    text: "微信账单\n支付宝账单\n银行流水\n信用卡账单\n无效/低质量图片",
    fontSize: 18,
    color: C.ink,
  });

  glass(slide, ctx, 474, 166, 334, 310, C.orange, C.white);
  text(slide, ctx, { x: 506, y: 196, width: 270, height: 30, text: "模型对比", fontSize: 22, bold: true, color: C.ink });
  text(slide, ctx, {
    x: 506,
    y: 252,
    width: 270,
    height: 160,
    text: "ResNet-18\nMobileNetV3\nEfficientNet-B0\n\n冻结骨干 / 部分微调 / 全量微调",
    fontSize: 18,
    color: C.ink,
  });

  glass(slide, ctx, 864, 166, 334, 310, C.violet, C.white);
  text(slide, ctx, { x: 896, y: 196, width: 270, height: 30, text: "评价指标", fontSize: 22, bold: true, color: C.ink });
  text(slide, ctx, {
    x: 896,
    y: 252,
    width: 270,
    height: 160,
    text: "Accuracy / F1\n混淆矩阵\n推理时间\n模型大小\nGrad-CAM 热力图",
    fontSize: 18,
    color: C.ink,
  });

  ctx.addShape(slide, { x: 258, y: 548, width: 764, height: 48, fill: C.bg });
  text(slide, ctx, {
    x: 290,
    y: 561,
    width: 700,
    height: 24,
    text: "目标：证明账单图像分类能稳定支撑后续 OCR 和数据导入流程",
    fontSize: 17,
    bold: true,
    color: C.white,
    align: "center",
  });
  footer(slide, ctx, 4, true);
  return slide;
}
