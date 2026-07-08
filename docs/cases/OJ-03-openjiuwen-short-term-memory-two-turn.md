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
tags: [integration, openjiuwen, smoke]
depends_on:
  - OJ-01 / OJ-02 同类前置
  - mainplan 默认 checkpointer=in_memory（travel-openjiuwen 现状，无需 Redis）
---

# OJ-03 — openjiuwen 短期记忆：两轮上下文连续

> **一句话**：同一 `contextId` 下 Turn1 说「我要出差」、Turn2 说「去北京，明天出发，3天」，
> 验证 Turn2 **理解** Turn1 的出差意图，不当作全新会话重复泛泛追问「是否要出差」。
>
> **关键定位**：验证 **短期记忆 / Session 内上下文**（由 in_memory Checkpointer 承载）。
> 对话语义参考 B-01 / B-03，但 **不测 Redis**（Redis 留第三步 OJ-06）。默认 **仅 mainplan**，
> 不依赖 trip/hotel 远程调用。

---

## 1. 场景目标

验证 openjiuwen mainplan 在默认 in_memory checkpointer 下的多轮连续性：

1. Turn1、Turn2 复用同一 `contextId`（由 `InteractionFlow` 或手动续接）；
2. Turn1 终态允许 `COMPLETED` 或 `INPUT_REQUIRED`（信息不全时追问属正常）；
3. Turn2 终态 **必须** `COMPLETED` 或体现已理解上下文的实质回复（非空、非「请重新描述需求」类重置话术）；
4. Turn2 文本 **不应** 仅重复 Turn1 级泛泛追问（启发式：不含「是否要出差」「你想出差吗」等 reset 短语，且应提及「北京」或承接出差语境）。

**本用例不覆盖**：

- Redis 持久化（→ 第三步 OJ-06）
- 不同 contextId 隔离（→ OJ-04）
- 三轮 INPUT_REQUIRED rail（→ OJ-05）
- 跨 session 长期记忆（→ 第三步 OJ-12）

---

## 2. 前置条件

| # | 条件 | 说明 |
|---|------|------|
| 1 | mainplan jar 在 ~/.m2 | managed 自动拉起 |
| 2 | LLM 可用 | 两轮均需模型 |
| 3 | checkpointer 默认 in_memory | 不注入 redis profile |
| 4 | 同步或流式 | 第一步默认 **同步** `streaming(false)`；若 sync 续轮语义异常可暂改 `streaming(true)` 并记录 |

---

## 3. 场景步骤

| # | 动作 | 协议 | 预期 |
|---|------|------|------|
| 1 | 拉起 mainplan（managed，`streaming(false)`） | SutStack | 就绪 |
| 2 | 加载 testdata：`oj-03-two-turn-dialogue.json` | classpath | turn1/turn2 文案就位 |
| 3 | Turn1：发送「我要出差」 | message/send | 终态 ∈ {COMPLETED, INPUT_REQUIRED}；记录 contextId |
| 4 | Turn2：同 contextId 发送「去北京，明天出发，3天」 | message/send 续轮 | 终态 COMPLETED（首选）或实质回复 |
| 5 | 对 Turn2 文本执行 §4 语义断言 | — | OJ-03.A / B / C |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-03.A — Turn2 到达可判定终态

- **When**：Turn2 send 完成。
- **Then**：终态为 `COMPLETED`，或 `INPUT_REQUIRED` 但 artifact 非空（可解析追问内容）。
- **PASS**：满足。**FAIL**：FAILED / 超时 / 空回复。
- **INCONCLUSIVE**：Turn1 已 FAILED，无法续轮。

### OJ-03.B — Turn2 承接 Turn1 意图（弱语义）

- **When**：抽取 Turn2 文本（`textOf`）。
- **Then**：
  - 包含「北京」或等价目的地表述；**且**
  - 不包含 reset 短语表中的任一子串（如「是否要出差」「请说明是否要出差」）。
- **PASS**：满足。**FAIL**：像全新会话重新问意图。
- **INCONCLUSIVE**：LLM 偶发无关回复——记录日志，Assumptions 可跳过并标 INCONCLUSIVE（不当作 PASS）。

### OJ-03.C — contextId 两轮一致

- **Then**：Turn2 使用的 contextId == Turn1 返回的 contextId（协议层与 OJ-02 对齐）。

---

## 5. 测试数据

`src/test/resources/testdata/openjiuwen/integration/oj-03-two-turn-dialogue.json`

```json
{
  "turn1": "我要出差",
  "turn2": "去北京，明天出发，3天",
  "turn2MustMention": ["北京"],
  "turn2MustNotContain": ["是否要出差", "你想出差吗", "请说明是否要出差"]
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/integration/OpenjiuwenShortTermMemoryTwoTurnTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；可选 `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `.streaming(false).agent("mainplan")` |
| 客户端 | `InteractionFlow` 两轮 `.send(turn1).awaitTerminal(...).send(turn2)...`（自动续 contextId） |
| 参考 | B-03 对话文案 + `RedisCheckpointerMultiTurnTest` / `TwoTurnDialogueRunner` 结构 |

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
- **单 mainplan vs 全链**：本用例 **不要求** 调用 trip；若 Turn2 触发远程委派仍可通过，但不作为 PASS 条件。
- **与 B-03 区别**：B-03 测 Redis；OJ-03 测 in_memory 短期记忆语义。
