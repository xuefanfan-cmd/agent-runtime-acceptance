---
id: DA-01
title: deep-research / search Agent Card 发现与字段完整性
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-1 Agent Card（deep-research + search 两 agent 的远端探测）
status: designed
sut: deep-research-agent + search-agent（均预部署在 SIT 服务器 7.209.189.82）
stack: 两 agent 均 remote 方式声明（url-only；框架不启动、不停止）
tags: [component, smoke, deepagent]
depends_on:
  - deep-research agent 已启动并监听 :18090
  - search agent 已启动并监听 :18091
  - 测试执行环境到 SIT 服务器网络可达（`-Dtest.env=SIT`）
---

# DA-01 — deep-research / search Agent Card 发现

> **一句话**：deep-research 同时暴露 `/.well-known/agent.json`、`/.well-known/agent-card.json`、
> `/a2a/.well-known/agent-card.json` 三个发现端点，三份 body 必须完全等价；SDK
> `A2A.getAgentCard(baseUrl)` 解析出的 card 关键字段须符合手工契约（name / provider /
> capabilities / skills / supportedInterfaces / preferredTransport）。search agent
> 目前仅作为 deep-research 的下游被间接触达，本档只做最小 well-formed 校验。

---

## 1. 场景目标

对两 agent 的远端探测（不发 A2A 请求，仅 read-only）：

1. **发现端点契约**：deep-research / search 各自的三个发现端点均 200 + `application/json` +
   同 agent 内三份 body 等价（`/.well-known/agent.json` 与别名 `/.well-known/agent-card.json`
   与 `/a2a/.well-known/agent-card.json`）。
2. **deep-research 字段契约**：以当前 SIT SUT 返回值为真——
   `name="DeepResearchAgent"`、`provider.organization="OpenJiuwen"`、`provider.url=""`（SUT 当前发空串）、
   `version="0.1.0"`、`capabilities.streaming=true`、`capabilities.pushNotifications=false`、
   `defaultInputModes=["text","text/plain"]`、`defaultOutputModes` 同上、`skills[0].id="deep_research"`、
   `supportedInterfaces[0].protocolBinding="JSONRPC"` + `url` 结尾 `/a2a`。
   （历史真值参考 [deepagent测试结果.txt §1](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt)——
   当时 `name="application"` / `provider.url="https://gitcode.com/openJiuwen"`，SUT 已重命名为 DeepResearchAgent
   并清空了 provider.url。）
3. **search agent 最小契约**：name / version / capabilities / supportedInterfaces 非空（无手工真值可比对）。
4. **SUT 现状快照**：deep-research 顶层 `$.url` 与 `$.preferredTransport` 按 A2A 1.0 已废弃，
   但 SUT 当前仍在发；本档以 "存在且值合法" 固化现状——将来 SUT 若移除，用例 FAIL 提示"契约变更"。

## 2. 前置条件

- deep-research 已启动，监听 `http://7.209.189.82:18090`，`/a2a` 走 JSONRPC；
- search 已启动，监听 `http://7.209.189.82:18091`；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 已声明两 agent 的 `url`（url-only 意味着 remote，
  {@code SutStack.Builder.agent(name)} 时框架不启动进程，只用来寻址）；
- 无 LLM 需求（本档只读发现端点）。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明两 remote agent；`SutStack.start()` 立即就绪（remote 无需拉起） | `SutStack` | stack 就绪 |
| 2 | deep-research `GET /.well-known/agent.json` / `/.well-known/agent-card.json` / `/a2a/.well-known/agent-card.json` | HTTP GET | 各 200 `application/json`；三份 body 等价 |
| 3 | search 同上三端点 | HTTP GET | 各 200 `application/json`；三份 body 等价 |
| 4 | `client("deep-research").getAgentCard()` | A2A SDK | 非空 AgentCard，`name`/`version` 与 raw 一致 |
| 5 | 对 deep-research card 断言 §1 契约字段 | — | 全部命中 |
| 6 | 对 search card 断言最小 well-formed | — | 全部命中 |
| 7 | 对 deep-research raw card 断言顶层 `$.url` / `$.preferredTransport` 存在且值合法 | — | 快照现状 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 HTTP + A2A SDK 观测，不读 agent 内部实现。

### DA-01.A — 发现端点可达 + 等价性
- **Given**：deep-research / search 就绪。
- **When**：对每个 agent，`GET` 三端点。
- **Then**：三份响应均 200 `application/json`，body 可解析为 JSON 对象且两两等价（`isEqualTo`）。
- **PASS**：全部满足。**FAIL**：任一端点非 200 / 非 JSON / 与另两端 body 不一致。

### DA-01.B — SDK 与 raw HTTP 视图对齐（deep-research）
- **Given**：deep-research 就绪。
- **When**：`client("deep-research").getAgentCard()` vs `GET /.well-known/agent-card.json`。
- **Then**：SDK 解析出的 `name` / `version` 与 raw JSON 一致。
- **PASS**：一致。**FAIL**：SDK 解析出错或字段不符（说明 SDK 与端点契约漂移）。

### DA-01.C — deep-research 字段契约
- **Given**：deep-research card。
- **When**：读取 name / provider / version / capabilities / defaultInputModes /
  defaultOutputModes / skills[0] / supportedInterfaces[0]。
- **Then**：与 §1 手工真值一一对齐。
- **PASS**：全部命中。**FAIL**：任一字段偏离手工契约。**INCONCLUSIVE**：deep-research 未部署时（改由 §2 前置条件把关）。

### DA-01.D — search 最小 well-formed
- **Given**：search card。
- **When**：读取 name / version / capabilities / supportedInterfaces。
- **Then**：均非空（`isNotBlank` / `isNotNull` / `isNotEmpty`）。
- **PASS**：命中。**FAIL**：任一字段缺失（说明 search agent card 不符 A2A 1.0 基本要求）。

### DA-01.E — 顶层 `$.url` / `$.preferredTransport` 现状快照（deep-research）
- **Given**：deep-research card（raw JSON）。
- **When**：读取 `$.url` / `$.preferredTransport`。
- **Then**：两字段均存在且值合法（`$.url` 结尾 `/a2a`；`$.preferredTransport == "JSONRPC"`）。
- **PASS**：满足现状。**FAIL**：SUT 已按 A2A 1.0 移除这两个字段——此时 §5 需与开发对齐一次决策。

## 5. 测试数据

- 无外置数据文件；contract 断言直接写在 [AgentCardDiscoveryTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java) 里。
- 真值来源：[deepagent测试结果.txt §1](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt) 中三次 curl 输出（三份 body 完全一致，说明 SUT 按 A2A 1.0 三端点等价）。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java) |
| 标签 | `@Tag("component") @Tag("smoke") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest`（per-class 栈；两 agent 均 remote 声明） |
| 客户端 | `stack.client("deep-research").getAgentCard()` + `java.net.http.HttpClient` 手工 GET 三端点 |
| 断言 | AssertJ 对 `AgentCard.*()` 与 Jackson `JsonNode`；`isEqualTo` 校验 body 等价 |
| YAML | [application-sit.yml](../../../src/test/resources/application-sit.yml) 追加 `sut.agents.deep-research.url` / `sut.agents.search.url` |

## 7. 运行方式

```bash
# 仅本类（component 层默认含 deepagent）
./mvnw -Dtest.env=SIT -Dtest=AgentCardDiscoveryTest test

# 或跑整个 deepagent 目录
./mvnw -Dtest.env=SIT -Dtest='com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.*' test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 特性 4-1 Agent Card 端点契约（含 alias 与 /a2a/.well-known 变体） | DA-01.A | ✅ |
| A2A 特性 4-1 SDK 与 raw 视图对齐 | DA-01.B | ✅ |
| deep-research 字段契约（§1 手工真值） | DA-01.C | ✅ |
| search 最小 well-formed | DA-01.D | 🟡 弱契约 |
| A2A 1.0 顶层 `$.url` / `$.preferredTransport` 现状 | DA-01.E | ✅ 快照 |

## 9. 风险与备注

- **DA-01.D 弱契约**：search agent 目前无手工真值参考，只做 name/version 非空。若后续从 SUT 侧拿到
  完整 card 契约，可扩展为像 deep-research 那样字段级断言。
- **DA-01.E 会随 SUT 升级 FAIL**：A2A 1.0 已废弃顶层 `$.url` / `$.preferredTransport`；SUT 目前仍在发。
  一旦 SUT 修复移除，本档需与开发对齐是"跟随规范去掉这两字段" or "作为兼容项保留"。
- **网络依赖**：本档必须在 SIT 环境（能访问 7.209.189.82）下跑；LOCAL 环境无对应远端 agent，用例会因
  连接失败 FAIL。
- **不测 A2A 消息**：本档只做 read-only 探测，不发 send/get。send/get 契约由 DA-02 / DA-03 / DA-04 承担。