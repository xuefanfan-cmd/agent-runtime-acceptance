---
id: OJ-06
title: openjiuwen — checkpoint-redis 多轮连续（B-03 语义）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P0
feature: openjiuwen Checkpointer Redis（application-redis.yml）+ 三 agent 全链多轮
status: designed
sut: agent-openjiuwen-travel-mainplan（入口）
stack: hotel → trip → mainplan（三 agent 全链）+ Testcontainers Redis
tags: [integration, openjiuwen, nightly]
depends_on:
  - 第二步 S-01：`application-redis.yml` 已合并至三 agent jar（`--spring.profiles.active=redis`）
  - travel-openjiuwen 三 jar 已 install 至 ~/.m2
  - Docker 可用（Testcontainers 拉起 Redis）
  - LLM 可用；mainplan→trip→hotel 链路互通
  - OJ-03 已 PASS（in_memory 多轮语义基线）
---

# OJ-06 — openjiuwen checkpoint-redis 多轮连续

> **一句话**：三 agent 全链启用 `redis` profile（Testcontainers Redis），Turn1「我要出差」→
> Turn2「去北京，明天出发，3天」续同一 `contextId`，Turn2 **硬等 COMPLETED**，弱语义验证 Turn2 理解 Turn1。
>
> **关键定位**：openjiuwen 第三步 **checkpoint-redis 主测点**；传输固定 **流式**
> `message/stream`（`SutStack.streaming(true)`）。对齐 B-03 语义，SUT 为 openjiuwen，
> 配置为 **`redis` Spring profile + REDIS_HOST/PORT + middleware 属性**。

---

## 1. 场景目标

1. Testcontainers Redis；三 agent `.profile("redis")` + Redis 接线；
2. Turn1 / Turn2 同一 `contextId`；
3. Turn1 终态允许 `{COMPLETED, INPUT_REQUIRED}`；
4. Turn2 终态 **必须** `COMPLETED`（硬等，不放宽）；
5. Turn2 弱语义：含「北京」/「出差」，不含 reset 短语。

**本用例不覆盖**：InMemory（→ OJ-03）、同步 send（第三步不测）、配置切换（→ OJ-07）。

---

## 2. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | Testcontainers Redis + 全链 `streaming(true)` + redis profile | OJ-06.0 三 agent checkpointer=redis |
| 2 | `InteractionFlow` Turn1「我要出差」 | ∈ {COMPLETED, INPUT_REQUIRED} |
| 3 | Turn2「去北京，明天出发，3天」`awaitState(COMPLETED)` | COMPLETED；弱语义；contextId 一致 |
| 4 | Redis DBSIZE > 0 | checkpoint 有数据 |

---

## 3. 可观测子断言

### OJ-06.0 — Redis checkpointer 门禁

三 agent 启动日志最新一行 `Begin to initializing checkpointer with type: redis`。

### OJ-06.A — Turn2 COMPLETED，文本非空

- **FAIL**：Turn2 非 COMPLETED（含停在 INPUT_REQUIRED）或空回复。

### OJ-06.B — Turn2 弱语义承接 Turn1

含 `turn2MustMatchAny` 任一项；不含 `turn2MustNotMatchAny`。

### OJ-06.C — contextId 两轮一致

---

## 4. 测试数据

外置参考：`testdata/integration/react_travel/oj-06-redis-multi-turn.json`  
文案写在测试常量；**不**使用 `main` ScenarioData。Turn2 **仅** `COMPLETED`。

---

## 5. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../integration/react_travel/OpenjiuwenRedisCheckpointerMultiTurnTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen") @Tag("nightly")` |
| 生命周期 | `@TestInstance(PER_CLASS)` + `BackingServices(redis)` + `SutStack`（非 OJ 专用 Base） |
| 栈 | `streaming(true)` + hotel→trip→mainplan，各 agent redis profile/env/middleware 属性内联 |
| 驱动 | `InteractionFlow`：Turn1 `mayReachState(INPUT_REQUIRED)`；Turn2 **`awaitState(COMPLETED)`** |
| 门禁 | `OpenjiuwenRedisCheckpointerMultiTurnTest` 类内日志 / DBSIZE 私有断言 |
| 配置 | `-Dtest.env=openjiuwen` |

> **不**引入 `OpenjiuwenStackSupport` / `OpenjiuwenStreamingTwoTurnRunner` / `model.openjiuwen.*`。  
> Turn2 **勿**套用 OJ-03 的 `{COMPLETED, INPUT_REQUIRED}` 放宽。

---

## 6. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenRedisCheckpointerMultiTurnTest test
```

---

## 7. 风险与备注

- **与 OJ-03**：OJ-03 同步 + in_memory + Turn2 可 INPUT_REQUIRED；OJ-06 流式 + Redis 全链 + Turn2 硬 COMPLETED。
- **与 B-03**：B-03 为 spring-ai-ascend 属性键；OJ-06 为 openjiuwen profile/middleware。
- **全链 + Redis**：超时 floor 120s；依赖 Docker + LLM。
