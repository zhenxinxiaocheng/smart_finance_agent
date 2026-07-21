# Windows Three-Process Launchers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 更新根目录 `启动.exe` 和 `关闭.exe`，用 3000/8088/8090 启停前端、Java 后端和 Python/FastAPI 行情服务。

**Architecture:** `启动.exe` 的内嵌 PowerShell 调用当前 `start-dev.ps1` 并显式传入三个端口；`关闭.exe` 读取 `.run-logs/dev-processes.json`，递归停止 analysis、backend、frontend，端口回退覆盖 8090、8088、3000。保持现有小型 PE 结构，只替换等长 `-EncodedCommand` 载荷。

**Tech Stack:** Windows PE、PowerShell、UTF-16LE Base64、Spring Boot、Vite、FastAPI。

## Global Constraints

- 实际交付文件必须是根目录 `启动.exe` 和 `关闭.exe`。
- 后端默认端口固定为 8088，不使用 8080。
- Python/FastAPI 行情服务端口固定为 8090。
- 前端端口固定为 3000，API 代理指向 8088。

---

### Task 1: 启动器内容回归测试

**Files:**
- Create: `scripts/test-windows-launchers.ps1`
- Test: `启动.exe`
- Test: `关闭.exe`

**Interfaces:**
- Consumes: PE 中 `-EncodedCommand <Base64>` 载荷。
- Produces: 对 3000、8088、8090、analysis 启停逻辑的内容断言。

- [x] **Step 1: 写失败测试**

解码两个 EXE，断言启动器包含 `AnalysisPort=8090` 和 `start-dev.ps1`，关闭器包含 `info.analysis.pid`、`Stop-Port $AnalysisPort`，并断言两个文件均不含 `BackendPort=8080`。

- [x] **Step 2: 运行测试确认失败**

Run: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-windows-launchers.ps1`

Expected: FAIL，旧启动器缺少 Python/FastAPI 启停逻辑。

### Task 2: 替换两个 EXE 的内嵌 PowerShell

**Files:**
- Modify: `启动.exe`
- Modify: `关闭.exe`

**Interfaces:**
- Consumes: `start-dev.ps1 -BackendPort 8088 -FrontendPort 3000 -AnalysisPort 8090`。
- Produces: 三进程启动器和三进程关闭器。

- [x] **Step 1: 生成等长 UTF-16LE Base64 载荷并原位替换**

启动载荷调用根目录 `start-dev.ps1`，等待 3000 可访问后打开 `/stocks`；关闭载荷先递归停止 PID 文件中的 analysis/backend/frontend，再按 8090/8088/3000 端口兜底清理。

- [x] **Step 2: 运行内容测试确认通过**

Run: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-windows-launchers.ps1`

Expected: PASS，两个 EXE 均包含三进程逻辑且不包含后端 8080 默认值。

### Task 3: 实际启停验证

**Files:**
- Test: `启动.exe`
- Test: `关闭.exe`
- Test: `.run-logs/dev-processes.json`

**Interfaces:**
- Produces: 3000、8088、8090 三端口的启动与关闭证据。

- [x] **Step 1: 运行关闭器释放当前服务**

Run: `Start-Process -FilePath .\关闭.exe -WorkingDirectory $PWD -Wait`

Expected: 3000、8088、8090 均无监听。

- [x] **Step 2: 运行启动器**

Run: `Start-Process -FilePath .\启动.exe -WorkingDirectory $PWD -Wait`

Expected: PID 文件含 analysis/backend/frontend，3000、8088、8090 均监听。

- [x] **Step 3: 再次运行关闭器**

Run: `Start-Process -FilePath .\关闭.exe -WorkingDirectory $PWD -Wait`

Expected: 三端口均释放，PID 文件删除。
