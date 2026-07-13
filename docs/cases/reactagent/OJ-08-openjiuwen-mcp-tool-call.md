---
id: OJ-08
title: openjiuwen — MCP 工具调用端到端
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P0
feature: hotel ExternalSvcAdapterRegistrar + Mock MCP（demo_echo）
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel + Mock MCP Server（均由 SutStack managed）
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-02：hotel `mcp` profile + `application-mcp.yml`
  - `travel-openjiuwen-test-support-0.1.0.jar` 可构建（Mock MCP，`demo_echo` / `district_hint`）
  - LLM 可用（须能发起 tool call）
---

# OJ-08 — openjiuwen MCP 工具调用端到端

> **一句话**：仅拉起 hotel（`mcp` profile）+ Mock MCP，经 A2A **流式** `message/stream`
> 发送「请调用 demo_echo 工具，输入 text=hello」，硬等 `COMPLETED`，回复含 **`demo_echo:hello`**。
>
> **关键定位**：openjiuwen 第三步 **MCP 主测点**；SUT 为 travel-openjiuwen hotel +
> test-support Mock Server。

---

## 1. 场景目标

1. `SutStack` 以 `java -jar --server.port=0` 启动 `mock-mcp`；
2. hotel：`streaming(true)` + `profile=mcp`，通过 downstream property 注入 Mock MCP base URL；
3. `InteractionFlow` 单轮流式；终态 **硬等** `COMPLETED`；
4. 回复含 `demo_echo:hello`。

**本用例不覆盖**：`district_hint`、全链透传 MCP、MCP 熔断、同步 send。

---

## 2. 前置条件

| # | 条件 |
|---|------|
| 1 | hotel jar + 自包含可执行 test-support jar 在 ~/.m2 |
| 2 | Mock MCP 在 hotel 启动前就绪 |
| 3 | LLM 可用且能 tool call |
| 4 | `-Dtest.env=openjiuwen`；**无需** mainplan / trip / Redis |

---

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | `SutStack.agent("mock-mcp")` | 随机端口 TCP ready |
| 2 | hotel downstream 注入 `server-path` | OJ-08.0 / OJ-08.C |
| 3 | `InteractionFlow` send → `awaitState(COMPLETED)` | OJ-08.A / B |
| 4 | `SutStack.close()` | 同时回收 hotel + Mock MCP |

---

## 4. 可观测子断言

### OJ-08.0 — Mock MCP 门禁

`tools/list` 含 `demo_echo`。

### OJ-08.A — 终态 COMPLETED

硬等；不应 `INPUT_REQUIRED`（信息已给全）。

### OJ-08.B — MCP 回显

`textOf(task)` 含 `demo_echo:hello`。

### OJ-08.C — hotel MCP profile 门禁

日志含 `profile is active: "mcp"`、`Registered external MCP server` 与 `Bound MCP server to hotel agent ability manager`。

---

## 5. 测试数据

外置参考：`testdata/component/singleagent/oj-08-mcp-demo-echo.json`  
文案写在测试常量；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../component/singleagent/OpenjiuwenMcpToolCallTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen") @Tag("nightly")` |
| 生命周期 | `@TestInstance(PER_CLASS)` + `SutStack` 统一管理两个进程 |
| 栈 | `.agent("mock-mcp").agent("hotel", downstream server-path)` |
| 驱动 | `InteractionFlow`：`awaitState(COMPLETED)` |
| 门禁 | `OpenjiuwenMcpToolCallTest` 类内私有断言 |
| 配置 | `-Dtest.env=openjiuwen` |

> **不**引入 `OpenjiuwenStackSupport` / Runner / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenMcpToolCallTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| MCP | OJ-08.0 / A / B / C | ✅ |

---

## 9. 风险与备注

- LLM 未调 `demo_echo` → OJ-08.B FAIL；prompt 已明示工具名与参数。
- Mock MCP 使用 `--server.port=0`，由 `ProcessLauncher` 按 PID 解析唯一 TCP 监听端口。
