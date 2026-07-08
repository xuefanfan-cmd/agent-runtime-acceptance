---
id: OJ-05
title: openjiuwen — 三轮信息收集（INPUT_REQUIRED 连续追问）
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P1
feature: agent-core-java UserInputInterruptRail + RequestUserInputTool + Checkpointer 跨 3 轮续接
status: designed
sut: agent-openjiuwen-travel-mainplan（入口）
stack: mainplan → trip → hotel（三 agent 全链，managed 模式）
tags: [integration, openjiuwen]
depends_on:
  - travel-openjiuwen 三 jar 已 install 至 ~/.m2
  - LLM 可用；mainplan→trip→hotel 链路互通
  - 参考 C-03 / StreamingTravelPlanningTest（SUT 换 openjiuwen）
---

# OJ-05 — openjiuwen 三轮信息收集（INPUT_REQUIRED）

> **一句话**：用户分 **3 轮**才说全差旅需求——mainplan 的 `UserInputInterruptRail` 应
> **连续追问两轮**（Turn1/2 终态 `INPUT_REQUIRED`），Turn3 补全后 `COMPLETED` 并给出
> 实质行程回复；三轮 **同一 contextId**，Checkpointer 累积已收集字段。
>
> **关键定位**：OJ-03 是两轮「上下文连续」；OJ-05 是 **rail 驱动的 3 轮 INPUT_REQUIRED
> 状态机**，也是第一步中 **唯一需要三 agent 全链** 的用例（Turn3 完整规划可能委派 trip）。
> 参考 C-03，SUT 改为 openjiuwen + `-Dtest.env=openjiuwen`。

---

## 1. 规约与机制

| 维度 | 依据 |
|------|------|
| INPUT_REQUIRED | `UserInputInterruptRail` + `RequestUserInputTool`；信息不全时任务停在 `INPUT_REQUIRED`（终态） |
| 连续追问 | Turn2 仍缺出发地（故意设计）⇒ 预期再次 INPUT_REQUIRED，而非 Turn2 直接 COMPLETED |
| contextId 续接 | 三轮同一 `contextId`；`InteractionFlow` 自动带入 |
| 全链 | Turn3 完整需求可能触发 `RemoteTripRail` → trip → hotel |
| 传输模式 | 第一步默认 **同步** `streaming(false)`；若 sync 路径对 INPUT_REQUIRED 观测不足，可暂用 `streaming(true)` 并在此文档记录偏差 |

---

## 2. 场景目标

| 轮次 | 输入 | 信息完整度 | 期望终态 | 期望状态序列（同步路径弱断言） |
|------|------|-----------|---------|------------------------------|
| Turn 1 | 「我要去北京出差。」 | 仅意图+目的地，缺日期/时长/出发地 | INPUT_REQUIRED | 含 INPUT_REQUIRED |
| Turn 2 | 「出差3天，下周二出发。」 | +时长+日期，**仍缺出发地** | INPUT_REQUIRED | 含 INPUT_REQUIRED |
| Turn 3 | 「从上海出发。差标：每晚不超过 800 元…偏好：国贸附近」 | 完整 | COMPLETED | 含 COMPLETED |

每轮断言：终态 artifact / status 文本 **非空**；Turn3 额外要求实质回复 `length > 8`。

**本用例不覆盖**：

- Redis checkpointer（→ OJ-06）
- 流式 SSE 事件序列精确匹配（同步路径为主；流式序列断言为扩展 OJ-05-S）
- 长期记忆（→ OJ-12）

---

## 3. 前置条件

| # | 条件 | 不满足时 |
|---|------|----------|
| 1 | 三 jar 在 ~/.m2 | 栈启动 **FAIL** |
| 2 | `application-openjiuwen.yml` 配齐 mainplan/trip/hotel 坐标与 `remote-agents-prefix` | wiring 失败 **FAIL** |
| 3 | LLM 可用 | Turn 失败 **FAIL** |
| 4 | managed 模式 leaf-first：hotel → trip → mainplan | 框架自动注入 `openjiuwen.service.a2a.remote-agents[i].url` |
| 5 | 独立 sessionId（如 `oj-05-manual-session-001`）避免与其他用例串状态 | — |

---

## 4. 场景步骤

| # | 动作 | 协议 | 预期 |
|---|------|------|------|
| 1 | `@BeforeAll` 拉起全链：`hotel → trip → mainplan` | SutStack | 三 agent 就绪 |
| 2 | 加载 `oj-05-three-turn-collection.json` | classpath | 三轮文案就位 |
| 3 | Turn1 send | message/send（或 stream） | INPUT_REQUIRED；有效追问非空 |
| 4 | Turn2 同 contextId send | 续轮 | INPUT_REQUIRED；有效追问非空 |
| 5 | Turn3 同 contextId send | 续轮 | COMPLETED；行程文本非空且 length>8 |
| 6 | 断言 OJ-05.A / B / C / D | — | 见 §5 |

---

## 5. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-05.A — Turn1 触发 INPUT_REQUIRED

- **Then**：终态 `INPUT_REQUIRED`；status/artifact 含追问文本（非空）。
- **PASS**：满足。**FAIL**：直接 COMPLETED（信息被误判为已全）或 FAILED。
- **INCONCLUSIVE**：LLM 未触发 rail——记录实测，与开发对齐 prompt/rail 策略。

### OJ-05.B — Turn2 再次 INPUT_REQUIRED（连续追问）

- **Then**：终态 `INPUT_REQUIRED`（**不应** Turn2 直接 COMPLETED，除非 rail 策略变更）。
- **PASS**：INPUT_REQUIRED。**FAIL**：Turn2 COMPLETED 且未收集出发地。
- **INCONCLUSIVE**：Turn1 已非 INPUT_REQUIRED，前置不满足。

### OJ-05.C — Turn3 COMPLETED 且实质回复

- **Then**：`COMPLETED`；`textOf` length > 8；宜含「北京」「上海」等已收集字段之一。
- **PASS**：满足。**FAIL**：仍 INPUT_REQUIRED / FAILED / 空回复。

### OJ-05.D — contextId 三轮一致

- **Then**：三轮 `Task.contextId` 相同（InteractionFlow 自动保证；失败则 checkpointer 续接异常）。

---

## 6. 测试数据

`src/test/resources/testdata/openjiuwen/integration/oj-05-three-turn-collection.json`

```json
{
  "sessionId": "oj-05-manual-session-001",
  "turns": [
    { "text": "我要去北京出差。", "expectedTerminal": "INPUT_REQUIRED" },
    { "text": "出差3天，下周二出发。", "expectedTerminal": "INPUT_REQUIRED" },
    {
      "text": "从上海出发。差标：每晚不超过 800 元、最低 4 星。偏好：国贸附近，需要会议室。",
      "expectedTerminal": "COMPLETED",
      "minResponseLength": 8
    }
  ]
}
```

文案与 C-03 对齐，便于对照 spring-ai-ascend 行为。

---

## 7. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/integration/OpenjiuwenThreeTurnInputRequiredTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `.streaming(false)` 或 `.streaming(true)`（见 §1 传输模式说明）<br>`.agent("hotel").agent("trip", d→hotel).agent("mainplan", d→trip)` |
| 客户端 | `InteractionFlow` 三轮 `.send(...).awaitState(...)` |
| 配置 | `-Dtest.env=openjiuwen` |
| 参考 | `StreamingTravelPlanningTest.threeTurnCollectionFollowsExpectedStateSequences()` |

---

## 8. 运行方式

```bash
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenThreeTurnInputRequiredTest test
```

> 无需人工启动 8091/8092/8093；`SutStack` 按依赖顺序自动拉起并注入 remote-agents URL。

---

## 9. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| 短期记忆（3 轮累积） | OJ-05.C / D | ✅ |
| A2A 同步/流式 + INPUT_REQUIRED 状态机 | OJ-05.A / B | ✅ |

---

## 10. 风险与备注

- **rail 策略漂移**：若 Turn2 直接 COMPLETED，说明 mainplan prompt/rail 与 C-03 设计不一致——**FAIL 并提缺陷**，勿 silently 改期望。
- **全链依赖**：Turn3 若 trip/hotel 不可达，可能 FAILED 而非 COMPLETED——先确认 OJ-01 与链路 smoke。
- **P1 优先级**：可在 OJ-01～OJ-04 稳定后再实现；第一步交付物以 OJ-01～OJ-04 为主。
- **与 C-03 关系**：逻辑等价，仅 SUT 与 profile 不同；**勿**直接跑 spring-ai-ascend 的 `StreamingTravelPlanningTest` 代替本用例。
