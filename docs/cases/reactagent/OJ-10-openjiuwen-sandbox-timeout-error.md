---
id: OJ-10
title: openjiuwen — 沙箱超时 / 错误（P2，可选）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P2
feature: hotel 沙箱异常路径 — 超时或非法命令时的错误可见性
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel（单 agent，managed）+ jiuwenbox
tags: [component, openjiuwen]
depends_on:
  - 第二步 S-03 已合并
  - OJ-09 同类 jiuwenbox 前置
  - LLM 可用
---

# OJ-10 — openjiuwen 沙箱超时 / 错误（可选）

> **一句话**：hotel `sandbox` profile 下触发超时 / 非法命令，验证失败可见且进程存活。
> **不**硬等 `COMPLETED`（与 OJ-09 happy path 分工）。

---

## 1. 场景目标

| 子场景 | 输入 | 期望 |
|--------|------|------|
| OJ-10a | Python `sleep(120)` | 非 COMPLETED **或** 文本含超时/失败关键词；hotel 存活 |
| OJ-10b | `/nonexistent/command_xyz` | 文本含错误关键词；hotel 存活；可再发「你好」 |

---

## 2. 前置条件

同 OJ-09：`SutStack` 通过 inline `containerFactory` 管理 `jiuwenbox:latest`，并启动 `hotel-sandbox`。

---

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | managed jiuwenbox bootstrap/restart + `hotel-sandbox` + health | 就绪 |
| 2a | `InteractionFlow` timeoutPrompt，`mayReachState(COMPLETED)` | OJ-10.T + OJ-10.P |
| 2b | `InteractionFlow` errorPrompt | OJ-10.E + OJ-10.P + 探针 send |

---

## 4. 子断言

- **OJ-10.T**：`nonCompleted || timeoutKeywords`  
- **OJ-10.E**：`errorKeywords` 命中  
- **OJ-10.P**：`GET /.well-known/agent.json` 200  

---

## 5. 测试数据

外置参考：`testdata/component/boundary/oj-10-sandbox-error.json`  
文案写在测试常量；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../boundary/OpenjiuwenSandboxErrorTest.java` |
| 栈 | 与 OJ-09 相同：`hotel-sandbox` + YAML binding + inline jiuwenbox factory |
| 驱动 | `InteractionFlow`：`mayReachState(COMPLETED)`（等任意终态，不强制 COMPLETED） |
| 门禁 | `OpenjiuwenSandboxErrorTest` 类内健康、错误和存活私有断言 |

> **不**引入 `OpenjiuwenStackSupport` / Runner / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSandboxErrorTest test
```
