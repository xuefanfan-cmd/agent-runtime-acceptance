---
id: OJ-03
title: openjiuwen — 短期记忆（两轮同 contextId 上下文连续）
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P0
feature: agent-core-java in_memory Checkpointer + mainplan 多轮对话语义
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent，managed；可选扩展为全链）
tags: [component, openjiuwen, smoke]
depends_on:
  - OJ-01 / OJ-02 同类前置
  - mainplan 默认 checkpointer=in_memory（travel-openjiuwen 现状，无需 Redis）
---

# OJ-03 — openjiuwen 短期记忆：两轮上下文连续

> **一句话**：同一会话下 Turn1 说「我要出差」、Turn2 说「去北京，明天出发，3天」，
> 验证 Turn2 **理解** Turn1 的出差意图，不当作全新会话重复泛泛追问「是否要出差」。
>
> **关键定位**：验证 **短期记忆 / Session 内上下文**（由 in_memory Checkpointer 承载）。
> 对话语义参考 B-01 / B-03，但 **不测 Redis**（Redis 留第三步 OJ-06）。默认 **仅 mainplan**，
> 不依赖 trip/hotel 远程调用。

---

## 1. 场景目标

验证 openjiuwen mainplan 在默认 in_memory checkpointer 下的多轮连续性：

1. Turn1 信息不全 → `INPUT_REQUIRED`；Turn2 由 `InteractionFlow` 续同一 `taskId` + `contextId`；
2. Turn2 终态允许 `COMPLETED` **或** `INPUT_REQUIRED`（单 mainplan 无 trip 时，信息充分后常因
   下游不可达停在追问/中断态——仍可凭文本验短期记忆）；
3. Turn2 文本 **不应** 仅重复 Turn1 级泛泛追问（启发式：不含「是否要出差」等 reset 短语，且应提及「北京」或「出差」）。

**本用例不覆盖**：

- Redis 持久化（→ 第三步 OJ-06）
- 不同 contextId 隔离（→ OJ-04）
- 三轮 INPUT_REQUIRED rail（→ OJ-05）
- 跨 session 长期记忆（→ 第三步 OJ-12）

---

## 2. 前置条件

| # | 条件 | 说明 |
|---|------|------|
| 1 | `com.huawei.ascend.examples:agent-openjiuwen-travel-mainplan:0.1.0` 在 ~/.m2 | managed 自动拉起 |
| 2 | LLM 可用 | 两轮均需模型 |
| 3 | checkpointer 默认 in_memory | 不注入 redis profile |
| 4 | 同步路径 | `streaming(false)` |

---

## 3. 场景步骤

| # | 动作 | 协议 | 预期 |
|---|------|------|------|
| 1 | 拉起 mainplan（managed，`streaming(false)`） | `SutStack` | 就绪 |
| 2 | `InteractionFlow.send("我要出差").awaitState(INPUT_REQUIRED)` | message/send | 追问非空；记录 contextId |
| 3 | `.send(...).mayReachState(INPUT_REQUIRED)` + 状态 ∈ {COMPLETED, INPUT_REQUIRED} | 续轮 | 实质回复（弱语义） |
| 4 | 弱语义断言 + 两轮 contextId 一致 | AssertJ / `FlowResult` | OJ-03.A / B / C |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-03.A — Turn2 到达可判定态且文本非空

- **When**：Turn2 send 完成。
- **Then**：状态 ∈ {`TASK_STATE_COMPLETED`, `TASK_STATE_INPUT_REQUIRED`}，artifact 非空。
- **PASS**：满足。**FAIL**：FAILED / 超时 / 空回复。
- **INCONCLUSIVE**：Turn1 未到 INPUT_REQUIRED（偶发直接 COMPLETED）——记录日志，不当作 PASS。
- **说明**：单 mainplan 栈下 Turn2 常因 `trip` 不可达停在 `INPUT_REQUIRED`；全链场景才稳定 `COMPLETED`。

### OJ-03.B — Turn2 承接 Turn1 意图（弱语义）

- **When**：抽取 Turn2 文本（`TaskTextExtractor.textOf`）。
- **Then**：
  - 包含「北京」或「出差」之一；**且**
  - 不包含 reset 短语表中的任一子串（如「是否要出差」「你想出差吗」「请说明是否要出差」）。
- **PASS**：满足。**FAIL**：像全新会话重新问意图。
- **INCONCLUSIVE**：LLM 偶发无关回复——记录日志，Assumptions 可跳过并标 INCONCLUSIVE（不当作 PASS）。

### OJ-03.C — contextId 两轮一致

- **Then**：`FlowResult.round(1).contextId()` == `round(0).contextId()`。

---

## 5. 测试数据

外置参考：`src/test/resources/testdata/component/singleagent/oj-03-two-turn-dialogue.json`

```json
{
  "turn1Text": "我要出差",
  "turn2Text": "去北京，明天出发，3天",
  "turn2MustMatchAny": ["北京", "出差"],
  "turn2MustNotMatchAny": ["是否要出差", "你想出差吗", "请说明是否要出差"]
}
```

超时取自 `sut.timeout.poll-seconds`。用例常量与上述 JSON 对齐；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/singleagent/OpenjiuwenShortTermMemoryTwoTurnTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen")`；可选 `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `SutStack.builder(config).streaming(false).agent("mainplan")` |
| 配置 | `-Dtest.env=openjiuwen` |
| 驱动 | `InteractionFlow`（Turn1 `INPUT_REQUIRED` → Turn2 `mayReachState(INPUT_REQUIRED)`，允许 COMPLETED） |
| 文本抽取 | `TaskTextExtractor.textOf` |
| 参考 | A-08 / `StreamingTravelPlanningTest` 多轮形态 |

> **不**引入 `OpenjiuwenStackSupport` / `OpenjiuwenSyncTwoTurnRunner` / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenShortTermMemoryTwoTurnTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| 短期记忆 | OJ-03.A / B / C | ✅ |
| A2A 同步（续轮） | OJ-03.C | 🟡 间接 |

---

## 9. 风险与备注

- **LLM 非确定性**：语义断言 intentionally 弱；FAIL 时抓 `target/sit-logs/` 下 mainplan 日志。
- **Turn1 必须 INPUT_REQUIRED**：`InteractionFlow` 仅在非终态时续同一 task；若 Turn1 偶发 COMPLETED，本用例 FAIL/INCONCLUSIVE，勿为此再写专用 RoundAwait。
- **单 mainplan vs 全链**：本用例 **不要求** trip；Turn2 因下游不可达停在 `INPUT_REQUIRED` 为预期可接受态，靠弱语义验记忆。
- **与 B-03 区别**：B-03 测 Redis；OJ-03 测 in_memory 短期记忆语义。
