---
id: OJ-07
title: openjiuwen — checkpoint-redis 配置切换（B-04 语义，P1）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P1
feature: openjiuwen Checkpointer 通过 Spring profile 切换 in_memory ↔ redis
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent）+ Testcontainers Redis（仅 Phase2）
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-01 已合并
  - OJ-06 弱语义可复用；Turn2 文案保持短句（单 mainplan），状态放宽对齐 OJ-03
  - Docker 可用（Phase2）
  - LLM 可用
---

# OJ-07 — openjiuwen Checkpointer 配置切换（InMemory → Redis）

> **一句话**：在 **单个测试方法** 内先后启动两套 **仅 mainplan** 托管栈——Phase1 默认
> in_memory（无 redis profile）→ Phase2 **仅改** `spring.profiles.active=redis` + Redis
> 地址 / middleware 属性——各跑一遍与 OJ-06 同款两轮对话，**各自独立** 满足语义断言，
> 验证 **切换不改代码**（B-04 语义）。
>
> **关键定位**：OJ-06 验 Redis 全链多轮连续；OJ-07 验 **profile alone 可切换**
> checkpointer 后端且行为仍达标。相对 B-04：SUT 为 openjiuwen，切换方式为 **`redis`
> profile + middleware**，而非 `main-plan-agent.checkpointer` 属性。

---

## 1. 场景目标

1. Phase1：mainplan **无** redis profile（默认 in_memory），两轮对话 → 语义 PASS；
2. tearDown Phase1 栈；
3. Phase2：新 mainplan 栈，`profile=redis` + Testcontainers Redis env/middleware → 同一对话再跑 → 语义 PASS；
4. Meta 断言：两阶段除 profile / redisBacked 外 streaming + agent 一致（OJ-07.0c）。

**本用例不覆盖**：

- 三 agent 全链 Redis（→ OJ-06）
- Redis → in_memory 反向切换
- Phase 间 Redis 数据继承（Phase2 为新容器 + 新进程，不读 Phase1 键）

---

## 2. 前置条件

| # | 条件 |
|---|------|
| 1 | mainplan jar 在 ~/.m2 |
| 2 | Phase2 需 Docker + Testcontainers Redis |
| 3 | LLM 可用 |
| 4 | 流式 `streaming(true)`，两轮均为 `message/stream`（与 OJ-06 / B-04 对齐） |

---

## 3. 场景步骤（单测两阶段）

| # | 阶段 | 动作 | 预期 |
|---|------|------|------|
| 1 | Phase1 | 起栈：仅 mainplan，`streaming(true)`，**无** redis profile | OJ-07.0 in_memory 门禁 |
| 2 | Phase1 | `InteractionFlow` Turn1「我要出差」→ Turn2「去北京」 | Turn2 ∈ {COMPLETED, INPUT_REQUIRED}；弱语义 |
| 3 | Phase1 | `stack.close()` | 进程退出 |
| 4 | Phase2 | Testcontainers Redis；起新栈：`profile=redis` + Redis wiring | OJ-07.0 redis 门禁 |
| 5 | Phase2 | **同一文案** 再跑 Turn1→Turn2 | 同上；Redis DBSIZE > 0 |
| 6 | — | OJ-07.0c 配置差 meta 校验 | 仅 profile + redisBacked 不同 |

> Phase1 **FAIL** 则不进入 Phase2。单 mainplan **不**拉 trip；Turn2 允许 `INPUT_REQUIRED`（对齐 OJ-03）。

---

## 4. 可观测子断言

### OJ-07.0 — Checkpointer 门禁

- Phase1：启动日志 `checkpointer with type: in_memory`（或等价）。
- Phase2：`checkpointer with type: redis`；对话后 Redis DBSIZE > 0。

### OJ-07.0c — 「仅改配置」硬门禁

- **允许不同**：Phase2 `profile=redis` + redisBacked；Phase1 无。
- **必须相同**：`streaming(true)`、agent=`mainplan`。

### OJ-07.P1 / P2 — 对话断言

- Turn1 / Turn2 终态 ∈ `{COMPLETED, INPUT_REQUIRED}`（单 mainplan 常继续追问日期）；
- Turn2 弱语义含「北京」/「出差」，不含 reset 短语；
- 两轮 `contextId` 一致。

---

## 5. 测试数据

外置参考（弱语义列表）：`testdata/integration/react_travel/oj-06-redis-multi-turn.json`  
Turn2 文案仍用「去北京」（单 mainplan 不追求 COMPLETED）；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../component/singleagent/OpenjiuwenCheckpointerConfigSwitchTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen") @Tag("nightly")` |
| 生命周期 | 单 `@Test` 内 Phase1/Phase2 各 `try (SutStack …)` |
| Phase1 | `SutStack.builder(config).streaming(true).agent("mainplan")` |
| Phase2 | 同上 + `BackingServices(redis)` + redis profile/env/middleware 内联 |
| 驱动 | `InteractionFlow`：Turn1/Turn2 均 `mayReachState(INPUT_REQUIRED)`，允许 COMPLETED |
| 门禁 | `OpenjiuwenCheckpointerConfigSwitchTest` 类内日志 / DBSIZE 私有断言 |
| 配置 | `-Dtest.env=openjiuwen` |

> **不**引入 `OpenjiuwenStackSupport` / Runner / `model.openjiuwen.*`；**不**拉 trip。  
> Turn2 状态放宽对齐 OJ-03；硬 `COMPLETED` 留给 OJ-06 全链。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenCheckpointerConfigSwitchTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| checkpoint-redis（切换叙事） | OJ-07.0c / P2 | ✅ |
| 短期记忆 in_memory | P1 | 🟡 对照 |
| A2A 流式 | P1 / P2 | ⚠️ 传输前置 |
| checkpoint-redis 专项连续 | — | ❌（OJ-06 负责） |

---

## 9. 风险与备注

- **P1 加分项**：0.2.0 最低验收标准为 OJ-06 PASS；OJ-07 为配置切换加分。
- **与 B-04 分工**：B-04 单 agent + spring-ai-ascend 属性键；OJ-07 单 agent + openjiuwen profile。
- **standalone URL**：YAML `mainplan` 已带占位 trip URL，避免无下游时 discovery NPE。
