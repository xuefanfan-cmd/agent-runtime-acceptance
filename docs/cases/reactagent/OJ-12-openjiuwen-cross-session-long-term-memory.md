---
id: OJ-12
title: openjiuwen — 长期记忆跨 session 召回（B-06 语义）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P1
feature: mainplan TravelUserMemoryRail + OpenJiuwenMemoryProvider（按 userId 跨 contextId）
status: blocked
sut: agent-openjiuwen-travel-mainplan（入口，三 agent 全链）
stack: mainplan → trip → hotel + LLM
tags: [integration, openjiuwen]
depends_on:
  - 第二步 S-05：mainplan `TravelUserMemoryRail` + `ReActAgentConfig.configureMemScope`
  - 框架支持 ReActAgent 长期记忆（当前 **未就绪**：ExternalMemoryRail 仅 DeepAgent）
  - B-06 语义与 testdata 结构
blocked_reason: S-05 未实现；ReActAgent 暂无法接入 Memory Rail，v0.2.0 阶段跳过执行
---

# OJ-12 — openjiuwen 长期记忆跨 session 召回（暂缓）

> **一句话**：同一 `userId`、**不同** `contextId` 完成 Plan-A（南京）与 Plan-B（杭州）后，
> 在 Recall-NJ / Recall-HZ 新 session 询问「上次到 XX 推荐的酒店」，应召回 **对应** plan 的
> 酒店品牌且 **两路不交叉污染**。
>
> **当前状态**：**blocked** — 第二步 S-05（`TravelUserMemoryRail`）因 ReActAgent 不支持
> 长期记忆尚未完成；**v0.2.0 正式测试不包含本用例执行**，本文档仅作设计占位，待 S-05
> 落地后启用。

---

## 1. 场景目标（设计态）

验证 mainplan 用户级长期记忆（参考 [B-06-cross-session-user-memory.md](./B-06-cross-session-user-memory.md)）：

| 阶段 | contextId | userId | 输入要点 | 期望 |
|------|-----------|--------|---------|------|
| Plan-A | `oj-12-plan-A` | `oj-12-user-001` | 成都→南京 4 天出差规划 | COMPLETED；抽出 `brand-A` |
| Plan-B | `oj-12-plan-B` | **同** userId | 广州→杭州 6 天 | COMPLETED；抽出 `brand-B` |
| Recall-NJ | `oj-12-recall-NJ` | **同** userId | 「上次到南京出差推荐的是哪家酒店？」 | 文本含 `brand-A` |
| Recall-HZ | `oj-12-recall-HZ` | **同** userId | 「上次到杭州出差推荐的是哪家酒店？」 | 文本含 `brand-B` |

**隔离要求**：Recall-NJ **不得** 以杭州品牌为主；Recall-HZ **不得** 以南京品牌为主。

**userId 传递**（二选一，实现 S-05 时择定并在 testdata 固定）：

- HTTP header `x-user-id`；或
- A2A `metadata.userId`。

---

## 2. 前置条件（启用后）

| # | 条件 |
|---|------|
| 1 | S-05 已合并：mainplan 注册 `TravelUserMemoryRail` |
| 2 | 三 agent 全链 + LLM |
| 3 | 客户端四路 **严格串行**（plan-A → plan-B → recall-NJ → recall-HZ） |
| 4 | 测试框架支持向 A2A 请求注入 userId |

---

## 3. 可观测子断言（设计摘要）

| ID | 说明 |
|----|------|
| OJ-12.A | plan 阶段可观测 `brand-A` / `brand-B`（正则 `([一-龥]{2,5})酒店` 等，同 B-06） |
| OJ-12.B | Recall-NJ 含 `brand-A` |
| OJ-12.C | Recall-HZ 含 `brand-B` |
| OJ-12.D | 交叉污染负向：Recall-NJ 不含 `brand-B` 为主导；Recall-HZ 不含 `brand-A` 为主导 |

完整步骤、testdata、框架落点见 B-06；实现时将 SUT 换为 openjiuwen + `-Dtest.env=openjiuwen`，
测试类建议命名为 `OpenjiuwenCrossSessionMemoryTest.java`。

---

## 4. 测试数据（占位）

`src/test/resources/testdata/openjiuwen/integration/oj-12-cross-session-memory.json`

结构对齐 B-06 `b06-memory-cases.json`（NJ / HZ 两对 plan+recall）。

---

## 5. 框架落点（占位）

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/integration/OpenjiuwenCrossSessionMemoryTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")` |
| 栈 | `SutStack.builder(config).agent("hotel").agent("trip", a -> a.downstream("hotel")).agent("mainplan", a -> a.downstream("trip")).start()`（或流式，与 B-06 对齐） |
| 状态 | **暂不实现**；`@Disabled("blocked: S-05 TravelUserMemoryRail")` |

---

## 6. 0.2.0 验收矩阵中的位置

| 能力 | 最低标准 | v0.2.0 状态 |
|------|---------|-------------|
| 长期记忆 | OJ-12 PASS | **阻塞 / 跳过** |

待 S-05 与框架 ReActAgent Memory Rail 就绪后，将 `status` 改为 `designed` 并实现测试类。

---

## 7. 风险与备注

- **与 OJ-04 边界**：OJ-04 验不同 contextId **短期** checkpointer 隔离；OJ-12 验同 userId **跨 session 长期** 召回。
- **全链依赖**：plan 阶段需 trip/hotel 产出可观测酒店品牌；LLM + 远程委派 flaky 时 brand 抽取可能 INCONCLUSIVE。
- **实施计划第三步**：文档已预留；当前正式测试范围 **OJ-06～OJ-11**。
