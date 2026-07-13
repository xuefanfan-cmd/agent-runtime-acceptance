---
id: A-01
title: Agent Card 发现
module: A — A2A 协议与通讯模型
owner: liubin
priority: P0
feature: 特性4 / 4-1 Agent Card
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，无需 trip/hotel）
tags: [component, smoke]
depends_on: [mainplan fat jar 可构建（mvn -pl :agent-travel-mainplan-a2a -am package）]
---

# A-01 — Agent Card 发现

> **一句话**：通过 A2A 标准发现端点拿到 mainplan 的 Agent Card，并验证其 `name` / `capabilities` / 接口契约
> 由**正确的配置层**决定。
>
> 本用例同时是框架"生命周期层"的**最小闭环验证**：仅拉起 mainplan、无 LLM、无链路，
> 用来打通 `SutStack → ProcessLauncher → 就绪探针 → client("mainplan").getAgentCard()` 全链路。
> 参见 [travel-sit-test-framework-design.md](../../travel-sit-test-framework-design.md)。

---

## 1. 场景目标

验证 A2A 协议的 Agent Card 发现（特性 4-1）：

- 标准发现端点 `/.well-known/agent.json` 可达且返回合法 JSON；
- Card 的 `name`、`capabilities.streaming`、`supportedInterfaces` 满足 mainplan 契约；
- Card 内容由配置层决定，并可经配置覆盖（liubin 专属维度：default / application.yaml / 自定义）。

## 2. 前置条件

- main-plan-agent 的 fat jar 已构建（`agent-travel-mainplan-a2a`）；
- 框架按 per-class 粒度拉起 mainplan（随机端口，进程拉起）；
- 测试客户端能访问 `http://localhost:<mainplanPort>`（端口由栈分配）；
- **无需** LLM、**无需** trip/hotel。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 拉起 mainplan（per-class），等待就绪 | `SutStack` + 就绪探针 | `GET /.well-known/agent.json` 返回 200 |
| 2 | `GET /.well-known/agent.json` | HTTP GET | 200；`Content-Type: application/json`；body 为 JSON 对象 |
| 3 | `GET /.well-known/agent-card.json`（别名） | HTTP GET | 200；body 与步骤 2 等价（兼容性） |
| 4 | 经 SDK `client("mainplan").getAgentCard()` 解析 | A2A SDK | 返回非空 `AgentCard` |
| 5 | 断言 `$.name` | — | `== "main-plan-agent"` |
| 6 | 断言 `$.capabilities.streaming` | — | `== true` |
| 7 | 断言 `$.capabilities.pushNotifications` | — | `== true` |
| 8 | 断言 `$.url` / `$.supportedInterfaces` / `$.preferredTransport` | — | `url` 以 `/a2a` 结尾；接口含 `JSONRPC`；`preferredTransport == JSONRPC` |
| 9 | 断言 `$.defaultInputModes` / `$.defaultOutputModes` | — | `["text"]` / `["text","artifact"]` |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 HTTP 发现端点 + A2A SDK 解析观测，不读 mainplan 内部类/配置文件。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### A-01.A — Card 可达性与媒体类型
- **Given**：mainplan 已就绪（就绪探针通过）。
- **When**：`GET /.well-known/agent.json` 与别名 `/.well-known/agent-card.json`。
- **Then**：两者均 200、`Content-Type: application/json`、body 可解析为 JSON 对象。
- **PASS**：全部满足。**FAIL**：非 200、或非 JSON、或两端不一致。**INCONCLUSIVE**：不适用（发现端点是 A2A 强制要求）。

### A-01.B — Card 标识字段（name）
- **Given**：A 返回的 Card。
- **When**：读取 `$.name`。
- **Then**：`== "main-plan-agent"`（来源见 §5）。
- **PASS**：精确匹配。**FAIL**：不符。**INCONCLUSIVE**：不适用。

### A-01.C — 能力声明（streaming / pushNotifications）
- **Given**：A 返回的 Card。
- **When**：读取 `$.capabilities`。
- **Then**：`streaming == true`（mainplan 经 `A2aAgentExecutor` 支持 SSE）；`pushNotifications == true`。
- **PASS**：均为 true。**FAIL**：任一为 false 或缺失。**INCONCLUSIVE**：SUT 既不暴露 `extendedAgentCard` 也未在基础 card 中声明 `capabilities` 时（记录为表面不足，而非通过）。

### A-01.D — 接口契约（supportedInterfaces / transport / modes）
- **Given**：A 返回的 Card。
- **When**：读取 `$.supportedInterfaces` / `$.preferredTransport` / `$.defaultInputModes` / `$.defaultOutputModes` / `$.url`。
- **Then**：`supportedInterfaces` 含 `{JSONRPC, /a2a}`；`preferredTransport == JSONRPC`；`url` 以 `/a2a` 结尾；输入/输出模式如步骤 9。
- **PASS**：全部满足。**FAIL**：缺失 JSONRPC 接口或 transport 不符。**INCONCLUSIVE**：SUT 未声明输出模式字段时（影响 artifact 断言，记录为表面不足）。

## 5. 配置来源维度（liubin：default / application.yaml / 自定义）

> 被测事实（代码）：mainplan 的 card 由 `@Bean mainPlanAgentCard()`（`AgentCards.create(AGENT_ID="main-plan-agent", …)`）产出；
> yaml 中 `agent-runtime.access.a2a.default-agent-id` 实际被运行时**忽略**；
> 仅当显式设置 `agent-runtime.access.a2a.agent-card.name`（`AgentCardProperties.hasExplicitName()`）时才覆盖 `name`。
> 该维度把"配置层决定 card"显性化，并回归"default-agent-id 被忽略"这一已知语义。

| 子用例 | 配置来源 | 关键覆盖 | 预期 `$.name` | 预期 streaming | 备注 |
|--------|---------|---------|--------------|----------------|------|
| **A-01.1（主，先做）** | default | 无 `agent-card.name` | `"main-plan-agent"`（来自 `@Bean`/AGENT_ID） | true | 打通生命周期层最小闭环 |
| A-01.2 | application.yaml | `default-agent-id=main-plan-agent`（被忽略项） | `"main-plan-agent"`（仍来自 `@Bean`） | true | 回归"忽略"语义不被悄悄改为生效 |
| A-01.3 | 自定义 | `agent-runtime.access.a2a.agent-card.name=<自定义>` | `<自定义>` | true | 验证显式覆盖生效 |

> 首轮只跑 **A-01.1**；A-01.2 / A-01.3 作为扩展子用例，按需在 per-class 栈中切换 profile / 覆盖参数。
> 子用例间的 name 差异通过 `AgentConfig.property(...)` 覆盖实现，**不改 agent 代码**。

## 6. 测试数据

- 断言字段表（字段 → 期望值）外置：`src/test/resources/testdata/component/protocol/a01-agent-card-assertions.json`，
  形如 `{"name":"main-plan-agent","capabilities.streaming":true,"capabilities.pushNotifications":true,"preferredTransport":"JSONRPC",...}`；
  由 `JsonUtils`/`TestDataLoader` 读入驱动参数化断言，便于 A-02（字段全集）复用扩展。
- 无请求体数据（GET 发现端点）。

## 7. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentCardDiscoveryTest.java` |
| 标签 | `@Tag("component") @Tag("smoke")`（见设计文档 §10.5.1） |
| 基类 | `BaseManagedStackTest`（新，per-class 管理栈），栈描述 = 仅 mainplan |
| 客户端调用 | `stack.client("mainplan").getAgentCard()`；并用原始 HTTP GET 校验状态/`Content-Type`（别名端点） |
| 断言 | AssertJ 对 `AgentCard.name()/capabilities()/supportedInterfaces()/preferredTransport()/defaultOutputModes()`；自定义字段走 `JsonUtils` |
| 配置来源扩展 | 经 `AgentConfig.profile(...)` / `AgentConfig.property("agent-runtime.access.a2a.agent-card.name", ...)` 参数化 |
| 数据 | `testdata/component/protocol/a01-agent-card-assertions.json` |

## 8. 运行方式

```bash
# 仅本类（component 层，surefire 默认含 component）
./mvnw -Dtest=AgentCardDiscoveryTest test

# 或经冒烟套件
./mvnw -Dtest=SmokeTestSuite test        # @IncludeTags("smoke")
```

> A-01 无 LLM、无远程依赖，**应纳入默认 `mvn test`**（不被 `assumeTrue` 跳过），作为框架可用性的看门狗。

## 9. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 4-1 Agent Card | A-01.A/B/C/D | ✅ |

## 10. 风险与备注

- **端口来源**：mainplan 默认端口是 runtime 自带的 8080（自身 yaml 未设 `server.port`）；本用例**不依赖**固定端口，
  由 `SutStack` 随机分配并注入 `client("mainplan")`。
- **别名端点**：`agent.json` 与 `agent-card.json` 都要校验（A2A 兼容性，两者同源）。
- **`default-agent-id` 忽略语义**：A-01.2 用于回归该"已知行为"——若某天运行时将其改为生效，A-01.2 的预期需同步修订（这正是用例的预警价值）。
- **INCONCLUSIVE 纪律**：card 中 SUT 可选未声明的字段（如输出模式）记为 INCONCLUSIVE，不得当作 PASS。
