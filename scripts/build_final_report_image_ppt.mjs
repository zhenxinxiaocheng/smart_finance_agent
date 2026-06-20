import fs from "node:fs/promises";
import path from "node:path";
import { Presentation, PresentationFile } from "file:///C:/Users/%E8%83%A1%E8%AF%9A/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs";

const root = process.cwd();
const assetDir = path.join(root, "outputs", "final_report_image_ppt_assets");
const outDir = path.join(root, "outputs");
const previewDir = path.join(outDir, "final_report_image_ppt_preview");
const finalPptx = path.join(outDir, "智财Agent个人智能财务代理系统-项目管理结项汇报-生命周期修正版.pptx");

const notes = [
  [
    "本页作为封面，先说明本次汇报主题是“项目管理结项汇报”，不是系统功能演示。",
    "介绍小组成员分工：胡诚负责项目经理和Agent开发，李开宇负责前端开发，张家豪负责后端开发，杨浦负责数据库开发和测试。",
    "过渡：接下来会按立项、范围、WBS、进度、质量、风险和分工来说明项目管理方案。"
  ],
  [
    "本页说明项目为什么立项。年轻用户常见问题是消费去向不清楚、预算控制弱、传统记账工具主动性不足。",
    "智财Agent项目的价值在于把财务管理场景和智能问答结合起来，但本次汇报重点不是实现效果，而是围绕这个项目怎样做管理规划。",
    "预期形成的成果主要是立项说明、项目计划、WBS、质量计划和风险登记等过程材料。"
  ],
  [
    "本页讲范围控制。我们把项目范围分为范围内和暂不纳入两部分，避免目标过大。",
    "范围内聚焦消费记录、预算设置、AI财务问答规划、统计分析和账单导入规划。",
    "暂不纳入微信支付宝自动导入、多Agent协作、原生App和本地模型部署等内容，保证项目可管理。"
  ],
  [
    "本页展示调整后的WBS任务分解。WBS不只分解管理文档，而是围绕整个软件项目生命周期展开。",
    "根节点是智财Agent个人智能财务代理系统，下面分为项目启动、需求分析、系统设计、项目开发、测试与质量保证、项目管理与交付六部分。",
    "其中项目开发进一步拆成前端开发、后端开发、Agent开发和数据库开发，这样更符合软件项目管理中的任务分解要求。"
  ],
  [
    "本页说明项目计划和里程碑。项目从4月初启动，到6月23日进行PPT结项汇报。",
    "计划按生命周期安排：先完成立项和需求范围确认，再进入系统设计和模块开发，之后进行测试、质量检查和材料汇总。",
    "关键里程碑包括立项完成、需求确认、设计完成、阶段性开发成果、质量检查完成和6月23日汇报。"
  ],
  [
    "本页重点讲进度计划。甘特图把4月初到6月23日的任务按时间展开，能看出先后依赖和并行关系。",
    "需求和设计是开发前置条件，前端、后端、Agent和数据库开发可以在设计完成后并行推进。",
    "可行性分析重点看开发、测试和PPT汇总之间是否留有缓冲，特别是6月23日汇报节点是固定的。"
  ],
  [
    "本页讲质量计划。质量计划覆盖需求、设计、开发、测试和交付材料，而不是只检查最终PPT。",
    "需求阶段看需求是否清楚、范围是否可控；设计阶段看前端、后端、数据库和Agent方案是否一致；开发阶段看模块是否能联调。",
    "测试和交付阶段则关注测试记录、质量检查、WBS、进度计划、风险登记和PPT材料是否完整。"
  ],
  [
    "本页讲风险管理。风险要结合本组项目实际，不能只写时间不足、技术困难这类空泛内容。",
    "我们关注AI回答不稳定、工具调用失败、接口联调延迟、数据库字段变更、材料汇总时间不足等具体风险。",
    "每个风险都对应可能性、影响程度、风险等级、应对措施和责任人。"
  ],
  [
    "本页说明小组分工与协作。分工按生命周期任务来展示，而不是只写成员姓名。",
    "胡诚负责项目统筹和Agent开发，李开宇负责前端开发，张家豪负责后端开发，杨浦负责数据库开发和测试。",
    "在需求、设计、开发、测试、风险和汇报阶段，各成员既有主责也有配合关系，最后通过交叉检查和材料汇总形成结项PPT。"
  ],
  [
    "最后总结本次项目管理过程的收获和改进方向。",
    "收获主要体现在立项分析、范围控制、WBS拆分、进度计划、质量检查和风险识别。",
    "改进方向包括进一步细化任务粒度、提前确认接口边界、预留进度缓冲和加强文档评审。"
  ],
];

async function readImageBlob(imagePath) {
  const bytes = await fs.readFile(imagePath);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

async function writeBlob(filePath, blob) {
  await fs.writeFile(filePath, new Uint8Array(await blob.arrayBuffer()));
}

async function main() {
  await fs.mkdir(outDir, { recursive: true });
  await fs.mkdir(previewDir, { recursive: true });

  const presentation = Presentation.create({
    slideSize: { width: 1280, height: 720 },
  });

  for (let i = 1; i <= 10; i++) {
    const slide = presentation.slides.add();
    const imagePath = path.join(assetDir, `slide-${String(i).padStart(2, "0")}.png`);
    const imageBytes = await readImageBlob(imagePath);

    slide.images.add({
      blob: imageBytes,
      contentType: "image/png",
      alt: `智财Agent项目管理结项汇报第${i}页`,
      fit: "cover",
      position: { left: 0, top: 0, width: 1280, height: 720 },
    });

    slide.speakerNotes.textFrame.setText(notes[i - 1]);
    slide.speakerNotes.setVisible(true);
  }

  for (const [index, slide] of presentation.slides.items.entries()) {
    const stem = `slide-${String(index + 1).padStart(2, "0")}`;
    const png = await presentation.export({ slide, format: "png", scale: 1 });
    await writeBlob(path.join(previewDir, `${stem}.png`), png);
    const layout = await slide.export({ format: "layout" });
    await fs.writeFile(path.join(previewDir, `${stem}.layout.json`), await layout.text());
  }

  const montage = await presentation.export({ format: "webp", montage: true, scale: 1 });
  await writeBlob(path.join(previewDir, "deck-montage.webp"), montage);

  const pptx = await PresentationFile.exportPptx(presentation);
  await pptx.save(finalPptx);
  console.log(finalPptx);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
