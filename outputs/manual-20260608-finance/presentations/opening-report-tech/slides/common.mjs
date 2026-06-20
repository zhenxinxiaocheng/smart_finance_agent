export const C = {
  bg: "#07111C",
  bg2: "#0B1B2B",
  panel: "#10243A",
  panel2: "#132B45",
  cyan: "#00E5FF",
  cyan2: "#00A7B8",
  violet: "#7C5CFF",
  magenta: "#D946EF",
  orange: "#FF9B54",
  green: "#54D48A",
  white: "#F8FBFF",
  muted: "#9FB8C6",
  line: "#24445C",
  ink: "#122033",
  soft: "#F4FAFC",
};

export const FONT = "Microsoft YaHei";

export function bg(slide, ctx, light = false) {
  ctx.addShape(slide, { x: 0, y: 0, width: ctx.W, height: ctx.H, fill: light ? C.soft : C.bg });
  if (!light) {
    for (let x = 80; x < ctx.W; x += 120) {
      ctx.addShape(slide, { x, y: 0, width: 1, height: ctx.H, fill: "#0F2234" });
    }
    for (let y = 80; y < ctx.H; y += 90) {
      ctx.addShape(slide, { x: 0, y, width: ctx.W, height: 1, fill: "#0F2234" });
    }
    ctx.addShape(slide, { x: 0, y: 0, width: ctx.W, height: 8, fill: C.cyan });
    ctx.addShape(slide, { x: 0, y: 8, width: ctx.W * 0.42, height: 4, fill: C.violet });
  }
}

export function text(slide, ctx, opts) {
  return ctx.addText(slide, {
    typeface: FONT,
    color: C.white,
    ...opts,
  });
}

export function title(slide, ctx, value, light = false) {
  text(slide, ctx, {
    x: 62,
    y: 42,
    width: 960,
    height: 58,
    text: value,
    fontSize: 34,
    bold: true,
    color: light ? C.ink : C.white,
  });
}

export function kicker(slide, ctx, value, light = false) {
  text(slide, ctx, {
    x: 64,
    y: 104,
    width: 760,
    height: 28,
    text: value,
    fontSize: 14,
    bold: true,
    color: light ? C.cyan2 : C.cyan,
  });
}

export function footer(slide, ctx, page, light = false) {
  text(slide, ctx, {
    x: 64,
    y: 674,
    width: 640,
    height: 24,
    text: "《人工智能与机器学习》期末项目前期开题",
    fontSize: 11,
    color: light ? "#7390A0" : "#6F91A4",
  });
  text(slide, ctx, {
    x: 1164,
    y: 674,
    width: 52,
    height: 24,
    text: String(page).padStart(2, "0"),
    fontSize: 11,
    color: light ? "#7390A0" : "#6F91A4",
    align: "right",
  });
}

export function glass(slide, ctx, x, y, w, h, accent = C.cyan, fill = C.panel) {
  ctx.addShape(slide, { x, y, width: w, height: h, fill, line: ctx.line("#31536B", 1) });
  ctx.addShape(slide, { x, y, width: 6, height: h, fill: accent });
}

export function chip(slide, ctx, x, y, w, label, accent = C.cyan) {
  ctx.addShape(slide, { x, y, width: w, height: 34, fill: "#0F2437", line: ctx.line(accent, 1) });
  text(slide, ctx, {
    x: x + 14,
    y: y + 8,
    width: w - 28,
    height: 18,
    text: label,
    fontSize: 12,
    bold: true,
    color: accent,
    align: "center",
  });
}

export function node(slide, ctx, x, y, w, h, label, accent = C.cyan, fill = C.panel) {
  glass(slide, ctx, x, y, w, h, accent, fill);
  text(slide, ctx, {
    x: x + 18,
    y: y + 20,
    width: w - 36,
    height: h - 40,
    text: label,
    fontSize: 15,
    bold: true,
    color: C.white,
    align: "center",
    valign: "mid",
  });
}

export function arrow(slide, ctx, x, y, w = 54, color = C.cyan) {
  ctx.addShape(slide, { x, y: y + 13, width: w - 16, height: 4, fill: color });
  ctx.addShape(slide, { geometry: "triangle", x: x + w - 18, y, width: 25, height: 30, fill: color });
}

export function metric(slide, ctx, x, y, num, label, color = C.cyan) {
  text(slide, ctx, {
    x,
    y,
    width: 170,
    height: 44,
    text: num,
    fontSize: 30,
    bold: true,
    color,
    align: "center",
  });
  text(slide, ctx, {
    x,
    y: y + 44,
    width: 170,
    height: 26,
    text: label,
    fontSize: 12,
    color: C.muted,
    align: "center",
  });
}
