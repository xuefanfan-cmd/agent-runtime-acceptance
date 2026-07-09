---
id: OJ-08
title: openjiuwen — MCP 工具调用端到端
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P0
feature: hotel ExternalSvcAdapterRegistrar + Mock MCP（demo_echo）
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel（单 agent，managed）+ Mock MCP Server（test-support JAR）
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-02：hotel `mcp` profile + `application-mcp.yml`
  - `travel-openjiuwen-test-support-0.1.0.jar` 可构建（Mock MCP，`demo_echo` / `district_hint`）
  - LLM 可用（须能发起 tool call）
---

# OJ-08 — openjiuwen MCP 工具调用端到端

> **一句话**：仅拉起 hotel（8093，`mcp` profile），测试生命周期内先启动 Mock MCP（18080），
> 经 A2A **流式** `message/stream` 发送「请调用 demo_echo 工具，输入 text=hello」，验证终态
> `COMPLETED` 且回复含 MCP 回显 **`demo_echo:hello`**。
>
> **关键定位**：openjiuwen 第三步 **MCP 主测点**；参考 runtime `McpDemoApplication` /
> `DemoMcpToolCallEndToEndTest`，SUT 换为 travel-openjiuwen hotel + test-support Mock Server。

---

## 1. 场景目标

验证 hotel agent 经 `ExternalSvcAdapterRegistrar` 挂载的外部 MCP 工具可被 ReActAgent 调用并回显：

1. `@BeforeAll` 启动 Mock MCP（`com.openjiuwen.service.travel.testsupport.mcp.MockMcpServerExample`，端口可配置，默认 18080）；
2. hotel 以 `--spring.profiles.active=mcp` 启动，环境变量 `MCP_HOST`/`MCP_PORT` 指向 Mock Server；
3. 单轮 A2A `message/stream`（`SendStreamingMessage` + `A2aEventCollector`），用户消息明确要求调用 `demo_echo` 且 `text=hello`；
4. 终态 `COMPLETED`；artifact 文本包含 `demo_echo:hello`（Mock Server 固定回显格式）。

**本用例不覆盖**：

- `district_hint` 工具（可选扩展 OJ-08b）
- mainplan / trip 链路透传 MCP（→ 可扩展 OJ-08c 全链）
- MCP 熔断 / 超时 / Server 不可用（→ 可选扩展）
- 同步 `message/send`（第三步 OJ-06～11 不测；→ 可选扩展 OJ-08-S）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-openjiuwen-travel-hotel:0.1.0` 在 ~/.m2 | **FAIL** |
| 2 | Mock MCP 在 hotel 启动前监听 `http://127.0.0.1:<port>/mcp` | hotel 启动或 tool call **FAIL** |
| 3 | hotel `profile=mcp`；`MCP_HOST`/`MCP_PORT` 与 Mock 一致 | MCP 注册失败 → **FAIL** |
| 4 | LLM 可用且能执行 tool call | 无 tool call、终态 FAILED → **FAIL** / INCONCLUSIVE |
| 5 | 栈 `streaming(true)`，客户端 `message/stream` | — |
| 6 | `-Dtest.env=openjiuwen` | — |
| 7 | **无需** mainplan / trip / Redis / Sandbox | — |

---

## 3. 场景步骤

| # | 动作 | 协议 / 配置 | 预期 |
|---|------|------------|------|
| 1 | 启动 Mock MCP Server（子进程或 embedded HttpServer） | POST `/mcp` JSON-RPC | `tools/list` 含 `demo_echo` |
| 2 | `@BeforeAll`：`SutStack` 仅 `hotel`，`profile=mcp`，注入 `MCP_HOST`/`MCP_PORT` | managed ProcessLauncher | hotel 就绪；日志含 MCP server 注册 |
| 3 | `SendStreamingMessage`：见 testdata `inputText`，collector 收 SSE 至终态 | message/stream | 收到 ≥1 事件；终态 COMPLETED |
| 4 | 断言 OJ-08.A / B / C | — | 见 §4 |
| 5 | `@AfterAll`：关闭 Mock MCP | — | — |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-08.0 — Mock MCP 门禁

- **When**：hotel 启动前 `curl tools/list` 或测试内探针。
- **Then**：响应 tools 列表含 `demo_echo`。
- **PASS** / **FAIL**：Mock 未就绪。

### OJ-08.A — 终态 COMPLETED

- **When**：单轮流式 send 完成（collector 终态或 `getTask` 快照）。
- **Then**：`TaskState == COMPLETED`。
- **PASS** / **FAIL**：FAILED / 超时 / INPUT_REQUIRED（本用例信息已给全，不应 INPUT_REQUIRED）。
- **INCONCLUSIVE**：LLM 未调用工具但给出「无法调用」类泛泛回复——记录全文，默认 **FAIL**（MCP 链路未验证）。

### OJ-08.B — MCP 回显内容

- **Given**：OJ-08.A 为 COMPLETED。
- **When**：`textOf(task)`。
- **Then**：包含子串 `demo_echo:hello`（Mock Server 固定格式：`demo_echo:` + 参数 text）。
- **PASS**：包含。**FAIL**：不含（tool 未调用或参数错误）。

### OJ-08.C — hotel MCP profile 门禁（meta）

- **Then**：栈 meta 含 `spring.profiles.active=mcp`；`MCP_PORT` 与 Mock 一致。

---

## 5. 测试数据

`src/test/resources/testdata/component/singleagent/oj-08-mcp-demo-echo.json`

```json
{
  "inputText": "请调用 demo_echo 工具，输入 text=hello",
  "expectedTerminalState": "COMPLETED",
  "expectedResponseSubstrings": ["demo_echo:hello"],
  "mcpPort": 18080,
  "timeoutMs": 120000
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/singleagent/OpenjiuwenMcpToolCallTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；建议 `@Tag("nightly")` |
| 基类 | `BaseManagedStackTest` 或自管 Mock MCP + 单 agent 栈 |
| 栈 | `SutStack.builder(config).streaming(true).agent("hotel", a -> a.profile("mcp").env("MCP_PORT", port))` |
| 客户端 | `stack.client("hotel")` + `A2aEventCollector`；参考 A-04 `AgentStreamMessageTest` |
| Mock MCP | 方案 A：`ProcessLauncher` 拉起 `travel-openjiuwen-test-support-0.1.0.jar --port=...`；方案 B：复制 `MockMcpServerExample` 到 acceptance test-support 内嵌启动 |
| 参考 | `agent-runtime-java/.../DemoMcpToolCallEndToEndTest.java`；`travel-openjiuwen/test-support/.../MockMcpServerExample.java`；A-04 `AgentStreamMessageTest` |

**Mock MCP 启动命令（概念）**：

```bash
java -jar ~/.m2/repository/.../travel-openjiuwen-test-support-0.1.0.jar --port=18080
# 或 mvn 构建产物：spring-ai-ascend/examples/travel-openjiuwen/test-support/target/...
```

---

## 7. 运行方式

```bash
# 前置：install travel-openjiuwen（含 test-support）
cd agent-runtime-acceptance
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenMcpToolCallTest test
```

> 测试类应 **自动** 拉起 Mock MCP；无需人工预启动（与 README 手工 smoke 区分）。

---

## 8. 覆盖特性追溯

| 能力（0.2.0 七项） | 子断言 | 覆盖 |
|-------------------|--------|------|
| MCP | OJ-08.0 / A / B / C | ✅ |

---

## 9. 风险与备注

- **LLM tool-call 非确定性**：若模型未调用 `demo_echo`，OJ-08.B FAIL；可加强 prompt（testdata 已明示工具名与参数）或 retry 一次并记录。
- **端口冲突**：18080 被占用时 testdata / 动态端口 + `MCP_PORT` env 注入。
- **与 OJ-01～05 隔离**：默认 profile 不启 MCP；本用例显式 `mcp` profile，不影响第一步回归。
- **传输模式**：第三步 OJ-06～11 统一流式；同步 MCP 路径不在本阶段范围。
