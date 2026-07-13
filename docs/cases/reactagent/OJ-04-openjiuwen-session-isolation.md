---
id: OJ-04
title: openjiuwen — 短期记忆 Session 隔离（不同 contextId 不串扰）
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P1
feature: agent-core-java Checkpointer session 隔离 + A2A contextId 契约
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent，managed；简化版，串行两 session）
tags: [component, openjiuwen]
depends_on:
  - OJ-03 同类前置
  - 参考 A-11-1 隔离思路，本用例为 **串行双 session** 简化（非并发全链）
---

# OJ-04 — openjiuwen Session 隔离（不同 contextId）

> **一句话**：在同一 mainplan 上先后使用 **两个不同** `contextId` 各进行一轮对话——
> Session-A 说「去上海出差」、Session-B 说「去北京出差」——各自第二轮追问时，
> 回复应只反映本 session 的目的地，**不得**串成对方城市。
>
> **关键定位**：OJ-03 验「同 contextId 该连的连」；OJ-04 验「不同 contextId 不该串」。
> 相对 A-11-1 的简化：仅 mainplan、串行执行、不做 virtual-thread 并发全链。

---

## 1. 场景目标

1. Session-A：`contextId="oj-04-session-a"`，Turn1「我计划去上海出差 3 天」；
2. Session-B：`contextId="oj-04-session-b"`，Turn1「我计划去北京出差 3 天」；
3. 各自 Turn2（同 contextId 续轮）发送「住宿标准每晚 800 以内」；
4. Session-A 的 Turn2 回复 **不应** 以北京语境为主；Session-B **不应** 以上海语境为主；
5. 协议层：两 session 的 `task.contextId` 各自稳定且互不相等。

**本用例不覆盖**：

- 同 userId 跨 session 长期记忆（→ 第三步 OJ-12）
- 并发压测（→ 可扩展 OJ-04b 参考 A-11-1 全链并发）
- trip/hotel 全链业务 artifact（单 mainplan 即可）

---

## 2. 前置条件

| # | 条件 |
|---|------|
| 1 | `com.huawei.ascend.examples:agent-openjiuwen-travel-mainplan:0.1.0` + LLM |
| 2 | 每 session 用独立 `InteractionFlow.withContextId(...)` 固定会话键 |
| 3 | 两 session **串行**执行（先 A 两轮，再 B 两轮） |

---

## 3. 场景步骤

| # | 动作 | contextId | 输入 | 预期 |
|---|------|-----------|------|------|
| 1 | 拉起 mainplan | — | — | 就绪 |
| 2 | Flow-A Turn1 | `oj-04-session-a` | 「我计划去上海出差 3 天」 | ∈ {COMPLETED, INPUT_REQUIRED}（城市+天数常直接 COMPLETED） |
| 3 | Flow-A Turn2 | 同 | 「住宿标准每晚 800 以内」 | ∈ {COMPLETED, INPUT_REQUIRED}；弱语义隔离 |
| 4 | Flow-B Turn1 | `oj-04-session-b` | 「我计划去北京出差 3 天」 | ∈ {COMPLETED, INPUT_REQUIRED} |
| 5 | Flow-B Turn2 | 同 | 「住宿标准每晚 800 以内」 | ∈ {COMPLETED, INPUT_REQUIRED}；弱语义隔离 |
| 6 | 断言 OJ-04.A / B / C | — | — | 见 §4 |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-04.A — contextId 互异且各自稳定

- **Then**：A 路两轮 `contextId == "oj-04-session-a"`；B 路均为 `"oj-04-session-b"`；两值不等。
- **PASS**：满足。**FAIL**：漂移或相等。

### OJ-04.B — Session-A 未污染 Session-B 城市

- **When**：读 B-Turn2 文本（及 A/B 对话语料）。
- **Then**：不以「上海」为规划目的地；续轮信号或语料保留「北京」。
- **PASS / FAIL / INCONCLUSIVE**：同 OJ-03 弱语义纪律。

### OJ-04.C — Session-B 未污染 Session-A 城市

- **When**：读 A-Turn2 文本。
- **Then**：不以「北京」为规划目的地；续轮信号或语料保留「上海」。

---

## 5. 测试数据

外置参考：`src/test/resources/testdata/component/singleagent/oj-04-session-isolation.json`

超时取自 `sut.timeout.poll-seconds`。用例常量与 JSON 对齐；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../cases/component/singleagent/OpenjiuwenSessionIsolationTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `SutStack.builder(config).streaming(false).agent("mainplan")` |
| 配置 | `-Dtest.env=openjiuwen` |
| 驱动 | 两个独立 `InteractionFlow` + `withContextId`；Turn1/2 均 `mayReachState(INPUT_REQUIRED)`，允许 COMPLETED |
| 参考 | A-11-1（简化为串行 + 单 agent）；OJ-03 终态放宽 |

> **不**引入 `OpenjiuwenStackSupport` / `OpenjiuwenSessionIsolationRunner` / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSessionIsolationTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| 短期记忆（隔离） | OJ-04.A / B / C | ✅ |

---

## 9. 风险与备注

- **串行 vs 并发**：第一步用串行降低 flaky；稳定后可增 OJ-04b 并发版。
- **单 mainplan**：Turn1「城市+天数」常直接 COMPLETED（LLM 默认出发地）；Turn2 可因无 trip 停在 `INPUT_REQUIRED`——均可接受。隔离信号看固定 `contextId` + 弱语义，不依赖 Turn1 必走 rail。
- **与 OJ-03 成对**：建议同批回归。
