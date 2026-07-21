# 智财 Agent Product Introduction Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and privately deploy a standalone Sites product introduction website for 智财 Agent without changing the existing Vue or Spring Boot applications.

**Architecture:** Create an isolated `product-site/` Vinext/React site with one static route, one page component, one global stylesheet, copied product screenshots, and build-time tests against the generated Cloudflare Worker HTML. The site has no runtime API, database, authentication, upload, or browser-storage dependency; Sites owns the source repository, version, private deployment, and access URL.

**Tech Stack:** Node.js >= 22.13.0, React 19.2.6, Next 16.2.6 compatibility layer, Vinext 0.0.50, Vite 8.0.13, Cloudflare Workers, Node test runner, Sites private hosting.

## Global Constraints

- Work only inside `product-site/` after initialization; do not modify `frontend/`, `backend/`, launchers, or database files.
- Preserve the user's existing dirty parent worktree; `product-site/` is an independent Git repository created by the Sites initializer.
- Use warm gray-white `#F3F0E9`, deep gray-black `#292725`, secondary text `#67625B`, brand accent `#B65D3D`, border `#C9C2B7`, and soft surface `#FAF8F3`.
- Never use `#FFFFFF` or `#000000` as design tokens.
- Use the accent only for primary buttons, focus/selected states, current workflow nodes, section numbers, and key metrics.
- Chinese display headings use weight `600–620`; body and card headings stay within `400–650`.
- Body line height stays within `1.75–1.85`; desktop sections use `80–112px` vertical spacing and mobile sections use `56–72px`.
- Reuse real screenshots copied from `frontend/public/`; do not create fake application screenshots or model-authored SVG illustrations.
- GitHub is the only external destination: `https://github.com/zhenxinxiaocheng/smart_finance_agent`.
- Support keyboard focus, visible labels, meaningful image alternative text, responsive single-column mobile layout, and `prefers-reduced-motion`.
- Generate exactly one site-specific social preview image after the page copy and visual direction are stable; omit `og:image` if the image text is not usable after one retry.
- Finish with a successful build and a Sites private deployment, never a public deployment.

---

### Task 1: Initialize the isolated Sites project and implement the semantic product page

**Files:**
- Create from Sites starter: `product-site/**`
- Modify: `product-site/app/page.tsx`
- Modify: `product-site/app/layout.tsx`
- Modify: `product-site/package.json`
- Modify: `product-site/package-lock.json`
- Modify: `product-site/tests/rendered-html.test.mjs`
- Delete: `product-site/app/_sites-preview/SkeletonPreview.tsx`
- Delete: `product-site/app/_sites-preview/preview.css`
- Copy: `frontend/public/readme-chat.png` → `product-site/public/readme-chat.png`
- Copy: `frontend/public/readme-statistics.png` → `product-site/public/readme-statistics.png`
- Copy: `frontend/public/readme-profile.png` → `product-site/public/readme-profile.png`

**Interfaces:**
- Consumes: the approved design spec and current real product screenshots.
- Produces: a server-rendered `/` page with section IDs `product`, `capabilities`, `workflow`, `showcase`, `architecture`, `safety`, and `technology`; later tasks style and publish these exact IDs.

- [ ] **Step 1: Create the empty target and initialize it with the Sites starter**

Run from the repository root:

```powershell
New-Item -ItemType Directory -Path product-site
& 'C:\Program Files\Git\bin\bash.exe' 'C:/Users/胡诚/.codex/plugins/cache/openai-bundled/sites/0.1.27/scripts/init-site.sh' 'E:/桌面/smart_finance_agent/product-site'
```

Expected: `product-site/package.json`, `product-site/app/page.tsx`, and `product-site/.openai/hosting.json` exist; dependency installation exits `0`.

- [ ] **Step 2: Copy the three real product screenshots**

Run:

```powershell
Copy-Item -LiteralPath frontend/public/readme-chat.png -Destination product-site/public/readme-chat.png
Copy-Item -LiteralPath frontend/public/readme-statistics.png -Destination product-site/public/readme-statistics.png
Copy-Item -LiteralPath frontend/public/readme-profile.png -Destination product-site/public/readme-profile.png
```

Expected: all three destination files exist and have non-zero length.

- [ ] **Step 3: Replace the starter test with failing product requirements**

Replace `product-site/tests/rendered-html.test.mjs` with:

```js
import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("renders the approved product narrative and section structure", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>智财 Agent \| 个人智能财务代理<\/title>/i);
  assert.match(html, /个人财务工作流的 Agent 化实践/);
  assert.match(html, /不是单纯的聊天机器人/);

  for (const id of [
    "product",
    "capabilities",
    "workflow",
    "showcase",
    "architecture",
    "safety",
    "technology",
  ]) {
    assert.match(html, new RegExp(`id=["']${id}["']`));
  }
});

test("renders safe GitHub actions and valid internal navigation", async () => {
  const html = await (await render()).text();
  assert.match(
    html,
    /href=["']https:\/\/github\.com\/zhenxinxiaocheng\/smart_finance_agent["']/,
  );
  assert.match(html, /target=["']_blank["']/);
  assert.match(html, /rel=["']noopener noreferrer["']/);

  for (const target of ["capabilities", "workflow", "showcase", "architecture"]) {
    assert.match(html, new RegExp(`href=["']#${target}["']`));
  }
});

test("ships real product screenshots with meaningful alternative text", async () => {
  await Promise.all([
    access(new URL("../public/readme-chat.png", import.meta.url)),
    access(new URL("../public/readme-statistics.png", import.meta.url)),
    access(new URL("../public/readme-profile.png", import.meta.url)),
  ]);

  const html = await (await render()).text();
  assert.match(html, /src=["'][^"']*readme-chat\.png/);
  assert.match(html, /alt=["']智财 Agent 新对话与 ReAct 运行界面["']/);
  assert.match(html, /alt=["']智财 Agent 收支统计分析界面["']/);
  assert.match(html, /alt=["']智财 Agent 财务画像配置界面["']/);
});

test("removes the disposable Sites starter preview", async () => {
  const [page, layout, packageJson] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
  ]);

  assert.doesNotMatch(page, /SkeletonPreview|codex-preview|_sites-preview/);
  assert.doesNotMatch(layout, /Starter Project|Your site is taking shape/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
});
```

- [ ] **Step 4: Run the test and verify RED**

Run:

```powershell
npm test
```

Working directory: `product-site/`.

Expected: FAIL because the starter still renders “Your site is taking shape”, lacks the required section IDs, and still depends on `react-loading-skeleton`.

- [ ] **Step 5: Implement the semantic page**

Replace `product-site/app/page.tsx` with:

```tsx
import Image from "next/image";

const githubUrl =
  "https://github.com/zhenxinxiaocheng/smart_finance_agent";

const capabilities = [
  ["财务管理", "记账、分类、预算、统计与趋势分析形成统一财务视图。"],
  ["ReAct 与 RAG", "结合知识检索完成可追踪的推理、观察与工具调用。"],
  ["长期记忆", "沉淀低风险偏好、财务目标与稳定的回答约束。"],
  ["Skills 与周期任务", "把稳定流程包装为能力，并按计划主动执行。"],
  ["账单导入", "识别截图中的候选交易，人工确认后才正式入账。"],
  ["安全审计", "关键写操作先审后执，运行步骤和 traceId 随时回看。"],
] as const;

const workflow = [
  ["01", "用户目标", "用自然语言描述财务问题或任务。"],
  ["02", "ReAct 决策", "Agent 结合上下文、记忆和 RAG 选择下一步。"],
  ["03", "工具调用", "读取统计、预算、账单或其他安全工具。"],
  ["04", "人工确认", "涉及写入的数据变更必须先由用户确认。"],
  ["05", "结果与审计", "返回结果并记录运行步骤、状态和 traceId。"],
] as const;

const layers = [
  ["交互层", "Vue 3 · Vite · Pinia · Vue Router · ECharts"],
  ["业务与 Agent 层", "Spring Boot · ReAct · Skills · Memory · Audit"],
  ["数据与模型层", "MySQL · DashScope · RAG · Tavily Search"],
] as const;

function GithubLink({ className = "button primary" }: { className?: string }) {
  return (
    <a
      className={className}
      href={githubUrl}
      target="_blank"
      rel="noopener noreferrer"
      aria-label="在新标签页查看智财 Agent GitHub 源码"
    >
      查看项目源码 <span aria-hidden="true">↗</span>
    </a>
  );
}

export default function Home() {
  return (
    <main>
      <header className="site-header">
        <a className="brand" href="#product" aria-label="返回智财 Agent 介绍页顶部">
          <span className="brand-mark" aria-hidden="true">Z</span>
          <span>ZHICAI AGENT</span>
        </a>
        <nav aria-label="产品介绍导航">
          <a href="#capabilities">能力</a>
          <a href="#workflow">流程</a>
          <a href="#showcase">界面</a>
          <a href="#architecture">架构</a>
        </nav>
      </header>

      <section className="hero section-grid" id="product" aria-labelledby="hero-title">
        <span className="section-number">00 / PRODUCT</span>
        <div className="hero-content">
          <p className="eyebrow">AGENTIC FINANCE SYSTEM · 2026</p>
          <h1 id="hero-title">个人财务工作流的 Agent 化实践</h1>
          <p className="hero-copy">
            智财 Agent 不是单纯的聊天机器人，而是能记账、分析、记忆，
            并在确认后执行任务的个人财务代理。
          </p>
          <div className="hero-actions">
            <GithubLink />
            <a className="button secondary" href="#capabilities">了解核心能力</a>
          </div>
        </div>
      </section>

      <section className="section section-grid" aria-labelledby="value-title">
        <span className="section-number">01 / VALUE</span>
        <div>
          <h2 id="value-title">从财务工具，到可信任的智能代理</h2>
          <p className="section-lead">
            它理解目标、调用工具、请求确认并记录过程，让每一次财务操作都有清晰边界。
          </p>
        </div>
      </section>

      <section className="section section-grid" id="capabilities" aria-labelledby="capabilities-title">
        <span className="section-number">02 / CAPABILITIES</span>
        <div>
          <h2 id="capabilities-title">覆盖真实个人财务工作的核心能力</h2>
          <div className="capability-grid">
            {capabilities.map(([title, text]) => (
              <article className="capability-card" key={title}>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="section section-grid" id="workflow" aria-labelledby="workflow-title">
        <span className="section-number">03 / WORKFLOW</span>
        <div>
          <h2 id="workflow-title">一次任务如何运行</h2>
          <ol className="workflow-list">
            {workflow.map(([number, title, text], index) => (
              <li className={index === 3 ? "workflow-item current" : "workflow-item"} key={number}>
                <span className="workflow-number">{number}</span>
                <div><h3>{title}</h3><p>{text}</p></div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="section section-grid" id="showcase" aria-labelledby="showcase-title">
        <span className="section-number">04 / EXPERIENCE</span>
        <div>
          <h2 id="showcase-title">真实产品界面</h2>
          <p className="section-lead">
            聊天、账单和数据页面使用更柔和的表面、圆角和留白，让复杂财务信息更容易理解。
          </p>
          <div className="showcase-grid">
            <figure className="showcase-card featured">
              <Image src="/readme-chat.png" alt="智财 Agent 新对话与 ReAct 运行界面" width={1600} height={900} />
              <figcaption><strong>Agent 对话</strong><span>查看工具调用与运行状态</span></figcaption>
            </figure>
            <figure className="showcase-card">
              <Image src="/readme-statistics.png" alt="智财 Agent 收支统计分析界面" width={1600} height={900} />
              <figcaption><strong>统计分析</strong><span>理解收支趋势与分类结构</span></figcaption>
            </figure>
            <figure className="showcase-card">
              <Image src="/readme-profile.png" alt="智财 Agent 财务画像配置界面" width={1600} height={900} />
              <figcaption><strong>财务画像</strong><span>维护目标、预算与风险偏好</span></figcaption>
            </figure>
          </div>
        </div>
      </section>

      <section className="section section-grid" id="architecture" aria-labelledby="architecture-title">
        <span className="section-number">05 / ARCHITECTURE</span>
        <div>
          <h2 id="architecture-title">清晰分层，便于扩展与讲解</h2>
          <div className="architecture-grid">
            {layers.map(([title, text]) => (
              <article className="architecture-layer" key={title}>
                <h3>{title}</h3><p>{text}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="section section-grid" id="safety" aria-labelledby="safety-title">
        <span className="section-number">06 / SAFETY</span>
        <div>
          <h2 id="safety-title">关键操作可确认，Agent 行为可追踪</h2>
          <ul className="safety-list">
            <li>涉及数据写入的重要动作必须先由用户确认。</li>
            <li>账单识别只生成候选交易，确认后才写入正式记录。</li>
            <li>运行步骤、状态与 traceId 可在审计页面回看。</li>
            <li>敏感财务信息不会自动沉淀到长期记忆。</li>
          </ul>
        </div>
      </section>

      <section className="section section-grid" id="technology" aria-labelledby="technology-title">
        <span className="section-number">07 / TECHNOLOGY</span>
        <div className="closing">
          <div><h2 id="technology-title">用真实工程能力，支撑可信财务体验</h2><p>Vue 3 · Spring Boot · LangChain4j · MySQL · DashScope · RAG</p></div>
          <GithubLink />
        </div>
      </section>

      <footer><span>ZHICAI AGENT · 2026</span><span>PERSONAL FINANCE, AGENTICALLY.</span></footer>
    </main>
  );
}
```

- [ ] **Step 6: Replace starter metadata without social-image metadata yet**

Replace `product-site/app/layout.tsx` with:

```tsx
import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "智财 Agent | 个人智能财务代理",
  description:
    "能记账、分析、记忆，并在确认后执行任务的个人智能财务代理。",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
```

- [ ] **Step 7: Remove the disposable starter preview and dependency**

Run in `product-site/`:

```powershell
npm uninstall react-loading-skeleton
Remove-Item -LiteralPath app/_sites-preview/SkeletonPreview.tsx
Remove-Item -LiteralPath app/_sites-preview/preview.css
Remove-Item -LiteralPath app/_sites-preview
```

Expected: `react-loading-skeleton` is absent from `package.json` and `package-lock.json`; `app/_sites-preview` no longer exists.

- [ ] **Step 8: Run the product-page tests and verify GREEN**

Run in `product-site/`:

```powershell
npm test
```

Expected: all four tests pass after a successful build.

- [ ] **Step 9: Commit the semantic page**

Run in `product-site/`:

```powershell
git add -A
git commit -m "feat: add Zhichai product introduction content"
```

Expected: one commit containing only the product site scaffold customization, tests, and copied screenshots.

---

### Task 2: Implement the approved warm editorial visual system and accessibility rules

**Files:**
- Modify: `product-site/tests/rendered-html.test.mjs`
- Modify: `product-site/app/globals.css`

**Interfaces:**
- Consumes: exact class names and section IDs produced by Task 1.
- Produces: responsive, warm-editorial styling with the approved tokens and reduced-motion behavior.

- [ ] **Step 1: Add failing visual-system tests**

Append to `product-site/tests/rendered-html.test.mjs`:

```js
test("uses the approved warm editorial design tokens", async () => {
  const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.match(css, /--paper:\s*#F3F0E9/i);
  assert.match(css, /--ink:\s*#292725/i);
  assert.match(css, /--accent:\s*#B65D3D/i);
  assert.match(css, /--surface:\s*#FAF8F3/i);
  assert.doesNotMatch(css, /#(?:FFFFFF|FFF)(?:\b|;)/i);
  assert.doesNotMatch(css, /#(?:000000|000)(?:\b|;)/i);
});

test("defines responsive and reduced-motion safeguards", async () => {
  const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.match(css, /@media\s*\(max-width:\s*760px\)/i);
  assert.match(css, /overflow-x:\s*clip/i);
  assert.match(css, /@media\s*\(prefers-reduced-motion:\s*reduce\)/i);
  assert.match(css, /scroll-behavior:\s*auto/i);
});
```

- [ ] **Step 2: Run tests and verify RED**

Run in `product-site/`:

```powershell
npm test
```

Expected: FAIL because the starter stylesheet still contains pure white/black tokens and lacks the approved token names and responsive safeguards.

- [ ] **Step 3: Replace the starter stylesheet with the approved system**

Replace `product-site/app/globals.css` with:

```css
@import "tailwindcss";

:root {
  --paper: #F3F0E9;
  --ink: #292725;
  --muted: #67625B;
  --accent: #B65D3D;
  --line: #C9C2B7;
  --surface: #FAF8F3;
  --soft: #E3DED4;
}

* { box-sizing: border-box; }
html { scroll-behavior: smooth; background: var(--paper); }
body {
  margin: 0;
  overflow-x: clip;
  color: var(--ink);
  background: var(--paper);
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont,
    "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
  line-height: 1.8;
}
a { color: inherit; text-decoration: none; }
img { display: block; max-width: 100%; height: auto; }
button, a { -webkit-tap-highlight-color: transparent; }
a:focus-visible { outline: 3px solid var(--accent); outline-offset: 4px; }

.site-header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 72px;
  padding: 0 clamp(24px, 5vw, 72px);
  border-bottom: 1px solid color-mix(in srgb, var(--line) 80%, transparent);
  background: color-mix(in srgb, var(--paper) 92%, transparent);
  backdrop-filter: blur(14px);
}
.brand { display: inline-flex; align-items: center; gap: 10px; font-size: 12px; font-weight: 650; letter-spacing: .08em; }
.brand-mark { display: grid; width: 30px; height: 30px; place-items: center; color: var(--paper); background: var(--ink); font-weight: 650; }
.site-header nav { display: flex; gap: clamp(16px, 2.5vw, 34px); font-size: 13px; }
.site-header nav a { color: var(--muted); }
.site-header nav a:hover { color: var(--accent); }

.section-grid {
  display: grid;
  grid-template-columns: minmax(110px, 160px) minmax(0, 1fr);
  gap: clamp(28px, 5vw, 72px);
}
.section-number { padding-top: 9px; color: var(--accent); font-size: 11px; font-weight: 650; letter-spacing: .12em; }
.hero {
  min-height: calc(100vh - 72px);
  align-items: center;
  padding: 96px clamp(24px, 7vw, 112px);
  background: repeating-linear-gradient(90deg, transparent, transparent calc(25% - 1px), color-mix(in srgb, var(--ink) 7%, transparent) 25%);
}
.hero-content { max-width: 920px; }
.eyebrow { margin: 0 0 22px; color: var(--accent); font-size: 11px; font-weight: 650; letter-spacing: .14em; }
h1, h2, h3 { margin-top: 0; color: var(--ink); }
h1 { max-width: 860px; margin-bottom: 28px; font-size: clamp(48px, 7vw, 92px); font-weight: 610; line-height: 1.12; letter-spacing: -.055em; }
h2 { max-width: 760px; margin-bottom: 20px; font-size: clamp(32px, 4vw, 56px); font-weight: 610; line-height: 1.3; letter-spacing: -.035em; }
h3 { margin-bottom: 10px; font-size: 18px; font-weight: 620; line-height: 1.45; }
p { margin-top: 0; color: var(--muted); }
.hero-copy { max-width: 680px; margin-bottom: 0; font-size: clamp(17px, 2vw, 22px); line-height: 1.82; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 38px; }
.button { display: inline-flex; min-height: 48px; align-items: center; justify-content: center; gap: 8px; padding: 0 20px; border: 1px solid var(--ink); font-size: 13px; font-weight: 650; transition: transform 160ms ease, background-color 160ms ease, color 160ms ease; }
.button:hover { transform: translateY(-2px); }
.button.primary { color: var(--paper); background: var(--accent); border-color: var(--accent); }
.button.secondary { background: transparent; }

.section { padding: clamp(80px, 9vw, 112px) clamp(24px, 7vw, 112px); border-top: 1px solid var(--line); }
.section-lead { max-width: 720px; margin-bottom: 36px; font-size: 17px; line-height: 1.82; }
.capability-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin-top: 42px; }
.capability-card { min-height: 190px; padding: 26px 24px; border-top: 2px solid var(--ink); background: var(--surface); }
.capability-card p { margin-bottom: 0; font-size: 14px; }

.workflow-list { display: grid; margin: 42px 0 0; padding: 0; list-style: none; }
.workflow-item { display: grid; grid-template-columns: 72px 1fr; gap: 24px; padding: 25px 22px; border-top: 1px solid var(--line); }
.workflow-item:last-child { border-bottom: 1px solid var(--line); }
.workflow-item.current { color: var(--paper); background: var(--accent); }
.workflow-number { color: var(--accent); font-size: 12px; font-weight: 650; }
.workflow-item.current .workflow-number, .workflow-item.current h3, .workflow-item.current p { color: var(--paper); }
.workflow-item p { margin-bottom: 0; }

.showcase-grid { display: grid; grid-template-columns: 1.35fr 1fr; gap: 20px; margin-top: 44px; }
.showcase-card { margin: 0; overflow: hidden; border-radius: 22px; background: var(--surface); box-shadow: 0 18px 60px color-mix(in srgb, var(--ink) 10%, transparent); }
.showcase-card.featured { grid-row: span 2; }
.showcase-card img { width: 100%; border-bottom: 1px solid var(--line); }
.showcase-card figcaption { display: flex; justify-content: space-between; gap: 20px; padding: 20px 22px; }
.showcase-card figcaption strong { font-size: 14px; font-weight: 620; }
.showcase-card figcaption span { color: var(--muted); font-size: 12px; }

.architecture-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin-top: 42px; }
.architecture-layer { padding: 28px 24px; border: 1px solid var(--line); background: color-mix(in srgb, var(--soft) 70%, transparent); }
.architecture-layer p { margin-bottom: 0; }
.safety-list { display: grid; gap: 0; margin: 38px 0 0; padding: 0; list-style: none; }
.safety-list li { padding: 20px 0 20px 30px; border-top: 1px solid var(--line); color: var(--muted); position: relative; }
.safety-list li::before { content: ""; position: absolute; top: 31px; left: 4px; width: 8px; height: 8px; background: var(--accent); }
.closing { display: flex; align-items: end; justify-content: space-between; gap: 40px; }
.closing p { margin-bottom: 0; }
footer { display: flex; justify-content: space-between; gap: 24px; padding: 28px clamp(24px, 7vw, 112px); color: var(--paper); background: var(--ink); font-size: 11px; letter-spacing: .08em; }

@media (max-width: 980px) {
  .capability-grid, .architecture-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .showcase-grid { grid-template-columns: 1fr; }
  .showcase-card.featured { grid-row: auto; }
}

@media (max-width: 760px) {
  .site-header { min-height: 64px; padding-inline: 20px; }
  .site-header nav { gap: 14px; }
  .site-header nav a:nth-child(2), .site-header nav a:nth-child(3) { display: none; }
  .section-grid { grid-template-columns: 1fr; gap: 20px; }
  .hero { min-height: calc(100vh - 64px); padding: 64px 22px; }
  h1 { font-size: clamp(42px, 14vw, 64px); }
  .section { padding: 64px 22px; }
  .section-number { padding-top: 0; }
  .capability-grid, .architecture-grid { grid-template-columns: 1fr; }
  .workflow-item { grid-template-columns: 48px 1fr; gap: 14px; padding-inline: 14px; }
  .showcase-card figcaption, .closing, footer { align-items: flex-start; flex-direction: column; }
}

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  *, *::before, *::after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
}
```

- [ ] **Step 4: Run visual-system tests and verify GREEN**

Run in `product-site/`:

```powershell
npm test
npm run lint
```

Expected: all six tests pass; lint exits `0`.

- [ ] **Step 5: Commit the visual system**

Run in `product-site/`:

```powershell
git add app/globals.css tests/rendered-html.test.mjs
git commit -m "style: apply warm editorial product design"
```

Expected: one commit containing only the approved visual system and its tests.

---

### Task 3: Generate and validate the social preview, then wire absolute metadata

**Files:**
- Create if image validation passes: `product-site/public/og.png`
- Modify: `product-site/app/layout.tsx`
- Modify: `product-site/tests/rendered-html.test.mjs`

**Interfaces:**
- Consumes: stable page headline, palette, typography, and editorial grid from Tasks 1–2.
- Produces: `generateMetadata()` that derives an absolute origin from request headers and references `/og.png` only after the asset passes visual inspection.

- [ ] **Step 1: Add failing Open Graph tests**

Append to `product-site/tests/rendered-html.test.mjs`:

```js
test("publishes site-specific absolute social metadata", async () => {
  await access(new URL("../public/og.png", import.meta.url));
  const html = await (await render()).text();
  assert.match(html, /<meta(?=[^>]*property=["']og:title["'])(?=[^>]*content=["']智财 Agent \| 个人智能财务代理["'])[^>]*>/i);
  assert.match(html, /<meta(?=[^>]*property=["']og:image["'])(?=[^>]*content=["']http:\/\/localhost\/og\.png["'])[^>]*>/i);
  assert.match(html, /<meta(?=[^>]*name=["']twitter:card["'])(?=[^>]*content=["']summary_large_image["'])[^>]*>/i);
});
```

- [ ] **Step 2: Run tests and verify RED**

Run in `product-site/`:

```powershell
npm test
```

Expected: FAIL because `public/og.png` and the Open Graph/Twitter metadata do not exist.

- [ ] **Step 3: Generate exactly one social card with the image generation skill**

Use this exact prompt:

```text
Create a complete 1200x630 landscape social preview card for “智财 Agent”. Use a warm gray-white #F3F0E9 background, deep gray-black #292725 typography, and a single low-saturation terracotta accent #B65D3D. Follow a restrained editorial engineering style with fine grid lines, generous whitespace, and medium-weight Chinese typography. Include only these exact texts: “智财 Agent” and “个人财务工作流的 Agent 化实践”. Add a small English label “AGENTIC FINANCE SYSTEM · 2026”. No browser frame, no device frame, no unrelated logo, no watermark, no fake dashboard screenshot, and no additional text.
```

Inspect the returned image at original detail. Accept only if all Chinese and English text is exact and legible. If unusable, retry once with the same palette and layout while explicitly correcting the observed text error. Save the accepted image as `product-site/public/og.png`; if both attempts fail, remove the Open Graph test from Step 1, omit the image metadata below, and document the omission in the commit message.

- [ ] **Step 4: Replace layout metadata with request-host-derived absolute metadata**

Replace `product-site/app/layout.tsx` with:

```tsx
import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";

const title = "智财 Agent | 个人智能财务代理";
const description =
  "能记账、分析、记忆，并在确认后执行任务的个人智能财务代理。";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host =
    requestHeaders.get("x-forwarded-host") ??
    requestHeaders.get("host") ??
    "localhost";
  const protocol =
    requestHeaders.get("x-forwarded-proto") ??
    (host.startsWith("localhost") ? "http" : "https");
  const origin = `${protocol}://${host}`;
  const image = new URL("/og.png", origin).toString();

  return {
    metadataBase: new URL(origin),
    title,
    description,
    openGraph: {
      title,
      description,
      type: "website",
      locale: "zh_CN",
      images: [{ url: image, width: 1200, height: 630, alt: "智财 Agent 产品介绍" }],
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [image],
    },
  };
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
```

- [ ] **Step 5: Run social metadata tests and verify GREEN**

Run in `product-site/`:

```powershell
npm test
npm run lint
```

Expected: all seven tests pass and lint exits `0`; if the social image was rejected after two attempts, all remaining six tests pass and the page contains no `og:image`.

- [ ] **Step 6: Commit the accepted social metadata state**

Run in `product-site/`:

```powershell
git add app/layout.tsx tests/rendered-html.test.mjs public/og.png
git commit -m "feat: add site-specific social preview"
```

Expected: one commit containing the validated social image, metadata, and test. If the image was omitted, stage only `app/layout.tsx` and the test file with commit message `chore: omit unusable social preview image`.

---

### Task 4: Validate, publish privately with Sites, and hand off the URL

**Files:**
- Modify: `product-site/.openai/hosting.json`
- Create outside source for packaging: `product-site-deploy.tar.gz`

**Interfaces:**
- Consumes: tested site source and the Sites connector responses.
- Produces: a saved Sites version and successful private deployment URL.

- [ ] **Step 1: Run fresh full verification**

Run in `product-site/`:

```powershell
npm test
npm run lint
npm run build
git status --short
```

Expected: tests report `0` failures, lint exits `0`, build exits `0`, `dist/server/index.js` exists, and only expected hosting metadata may remain uncommitted.

- [ ] **Step 2: Preview once in Codex without additional browser QA**

Start the existing development command in a retained background session:

```powershell
npm run dev
```

Use the exact Local URL printed by the healthy server and call Sites `open_in_codex` once. Do not take screenshots, click, resize, or inspect the DOM unless the user explicitly asks for browser testing.

- [ ] **Step 3: Create the Sites project once and persist its exact ID**

Read `product-site/.openai/hosting.json`. Because it initially contains no `project_id`, call Sites `create_site` exactly once with:

```json
{
  "title": "智财 Agent 产品介绍",
  "description": "个人财务工作流的 Agent 化实践",
  "slug": "zhichai-agent-showcase"
}
```

Copy the returned `id` unchanged into `product-site/.openai/hosting.json` as `project_id`, keeping `d1` and `r2` as `null`. Reuse the returned source repository credential until it expires; never print or persist its token.

- [ ] **Step 4: Commit and push the exact validated source state**

Run in `product-site/`:

```powershell
git add .openai/hosting.json
git commit -m "chore: configure Sites hosting"
npm test
npm run lint
npm run build
git rev-parse HEAD
```

Push the current branch to the exact `remote_url` and `branch` returned by `create_site`, passing the returned token only as a per-command HTTP authorization header. Use the pushed `git rev-parse HEAD` output unchanged as `commit_sha`. Do not write the token into `.git/config`, a remote URL, a file, or a user-facing message.

- [ ] **Step 5: Package and save one version**

Run from the repository root after the source has not changed since Step 1:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' 'C:/Users/胡诚/.codex/plugins/cache/openai-bundled/sites/0.1.27/scripts/package-site.sh' 'E:/桌面/smart_finance_agent/product-site' 'E:/桌面/smart_finance_agent/product-site-deploy.tar.gz'
```

Expected: the archive contains `dist/server/index.js`, static assets, and `dist/.openai/hosting.json`.

Call Sites `save_site_version` once using the exact `project_id`, pushed `commit_sha`, and archive from this step. Copy the returned version identifier unchanged.

- [ ] **Step 6: Deploy the saved version privately and poll to completion**

Call Sites `deploy_private_site_version` using the exact `project_id` and saved `version_id`. Copy the returned deployment `id` unchanged as `deployment_id`. Poll `get_deployment_status` with that exact `project_id`, `version_id`, and `deployment_id` until `status` is `succeeded` or `failed`; do not switch to public deployment.

Expected: `status: "succeeded"` and a deployed private URL.

- [ ] **Step 7: Open and hand off the deployed site**

After success, call Sites `open_in_codex` without a thread ID using the exact deployed URL when that connector capability is exposed. If it is not exposed, keep the deployment unchanged and return the verified URL directly. Stop the local development server, then return the private Sites URL as the primary deliverable with one sentence describing the site.
