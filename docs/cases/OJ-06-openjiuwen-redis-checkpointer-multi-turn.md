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
> Turn2「去北京」续同一 `contextId`，验证 Turn2 **理解** Turn1 上下文，行为与 OJ-03
>（in_memory）语义一致，但 checkpointer 后端为 Redis。
>
> **关键定位**：openjiuwen 第三步 **checkpoint-redis 主测点**；传输固定 **流式**
> `message/stream`（`SutStack.streaming(true)`），对话文案与语义规则对齐
> [B-03-redis-checkpointer-multi-turn.md](./B-03-redis-checkpointer-multi-turn.md)，但 SUT 换为
> openjiuwen 制品，配置方式为 **`redis` Spring profile + `REDIS_HOST`/`REDIS_PORT`**（非
> spring-ai-ascend 的 `main-plan-agent.checkpointer` 属性键）。

---

## 1. 场景目标

验证 travel-openjiuwen 在 **三 agent 均启用 Redis checkpointer** 时，mainplan 入口多轮对话仍连续：

1. Testcontainers 拉起 Redis；三 agent 启动参数含 `--spring.profiles.active=redis`，并注入
   `REDIS_HOST` / `REDIS_PORT`（与 S-01 `application-redis.yml` 一致）；
2. Turn1、Turn2 复用同一 `contextId`；
3. Turn1 终态允许 `COMPLETED` 或 `INPUT_REQUIRED`；
4. Turn2 终态 **必须** `COMPLETED`（首选）或体现已理解上下文的实质回复；
5. Turn2 文本满足弱语义：含「北京」等正向词，不含「是否要出差」类 reset 短语。

**本用例不覆盖**：

- InMemory 基线（→ OJ-03）
- 同步 `message/send`（第三步不测；OJ-03 已覆盖 in_memory 同步）
- in_memory ↔ redis **配置切换**（→ OJ-07）
- 进程重启后跨进程恢复 Redis 键（v0.2.0 不测）
- 长期记忆（→ OJ-12，当前阻塞）
- hotel 单测 MCP / Sandbox / Skill（→ OJ-08～OJ-11）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-openjiuwen-travel-{mainplan,trip,hotel}:0.1.0` 在 ~/.m2 | 栈启动失败 → **FAIL** |
| 2 | **Docker** 可用，Testcontainers 可启动 `redis:7-alpine` | Redis 启动失败 → **FAIL** |
| 3 | 三 agent **均** `profile=redis`（避免 mainplan Redis、trip/hotel in_memory 不一致） | 行为不可预期 → **FAIL** |
| 4 | `-Dtest.env=openjiuwen` | 坐标错误 → **FAIL** |
| 5 | LLM 可用（`LLM_*` env） | 对话超时 / FAILED → **FAIL** |
| 6 | 栈 `streaming(true)`，客户端走 `message/stream`（SSE） | 误配 `streaming(false)` → 偏离本用例 |
| 7 | `application-openjiuwen.yml` 扩展 `sut.services.redis` + agent `service-bindings` 或等价 env 注入（见 §6） | 实现阶段补齐 |

> LLM 为运行前置，测试类内不做 `@EnabledIf` 跳过。

---

## 3. 场景步骤

| # | 动作 | 协议 / 配置 | 预期 |
|---|------|------------|------|
| 1 | `@BeforeAll`：Testcontainers Redis 就绪，解析 `host` + `mappedPort` | BackingServices | Redis RUNNING |
| 2 | leaf-first 拉起 hotel / trip / mainplan，**三 agent** `profile=redis`，env 注入 `REDIS_HOST`/`REDIS_PORT` | SutStack | 三 agent 就绪 |
| 3 | Turn1：`SendStreamingMessage` 发送「我要出差」，`A2aEventCollector` 收事件至 Turn1 终态 | message/stream | 终态 ∈ {COMPLETED, INPUT_REQUIRED}；记录 contextId |
| 4 | Turn2：同 contextId 流式续轮发送「去北京」 | message/stream | 终态 COMPLETED（首选） |
| 5 | 对 Turn2 文本执行 §4 语义断言 | — | OJ-06.A / B / C |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-06.0 — Redis / 三 agent profile 门禁

- **Given**：准备执行 OJ-06。
- **When**：检查栈构建 meta：hotel / trip / mainplan 均含 `spring.profiles.active=redis`；
  `REDIS_HOST`/`REDIS_PORT` 指向 Testcontainers 实例。
- **Then**：三项均满足。
- **PASS** / **FAIL**：任一 agent 未启用 redis profile 或未注入 Redis 地址。

### OJ-06.A — Turn2 到达可判定终态

- **When**：Turn2 send 完成。
- **Then**：终态为 `COMPLETED`，或 `INPUT_REQUIRED` 但 artifact 非空。
- **PASS** / **FAIL** / **INCONCLUSIVE**：同 OJ-03.A。

### OJ-06.B — Turn2 承接 Turn1 意图（弱语义）

- **When**：抽取 Turn2 文本。
- **Then**：
  - 包含「北京」或 testdata `turn2MustMatchAny` 中任一项；**且**
  - 不包含 `turn2MustNotMatchAny` 中 reset 短语。
- **PASS** / **FAIL** / **INCONCLUSIVE**：同 OJ-03.B；LLM flaky 时记录日志，Assumptions 可标 INCONCLUSIVE。

### OJ-06.C — contextId 两轮一致

- **Then**：Turn2 contextId == Turn1 contextId。

---

## 5. 测试数据

`src/test/resources/testdata/integration/react_travel/oj-06-redis-multi-turn.json`

```json
{
  "_doc": "OJ-06 — Redis checkpointer two-turn (openjiuwen); wording aligned with OJ-03 / B-03",
  "turn1": "我要出差",
  "turn2": "去北京",
  "turn2MustMatchAny": ["北京", "出差"],
  "turn2MustNotMatchAny": [
    "是否要出差",
    "你想出差吗",
    "您要去哪里出差",
    "请告诉我您的目的地"
  ],
  "turn1TimeoutMs": 90000,
  "turn2TimeoutMs": 120000
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/OpenjiuwenRedisCheckpointerMultiTurnTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；建议 `@Tag("nightly")`（依赖 Docker + LLM + 全链） |
| 基类 | `BaseManagedStackTest` |
| 栈 | `SutStack.builder(config).streaming(true)` + 三 agent `.profile("redis")` + Redis env（leaf-first 全链） |
| 客户端 | `stack.client("mainplan")`（SDK `streaming=true`）；复用 B-03 `TwoTurnDialogueRunner` 或新增 `OpenjiuwenStreamingTwoTurnRunner` |
| 语义 helper | 复用 `OpenjiuwenTextAssertions`（OJ-03 已有） |
| 配置扩展 | `application-openjiuwen.yml` 增加 `sut.services.redis`；各 agent `service-bindings` 映射 `REDIS_HOST`/`REDIS_PORT`，或 `AgentConfig.env("REDIS_HOST", host)` |

**栈构建示例（概念）**：

```java
return SutStack.builder(config)
        .streaming(true)
        .agent(HOTEL, a -> a.profile("redis").env("REDIS_HOST", redisHost).env("REDIS_PORT", redisPort))
        .agent(TRIP, a -> a.downstream(HOTEL).profile("redis").env("REDIS_HOST", redisHost).env("REDIS_PORT", redisPort))
        .agent(MAINPLAN, a -> a.downstream(TRIP).profile("redis").env("REDIS_HOST", redisHost).env("REDIS_PORT", redisPort));
```

---

## 7. 运行方式

```bash
# WSL / Linux；Docker 运行中
cd agent-runtime-acceptance
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenRedisCheckpointerMultiTurnTest test
```

---

## 8. 覆盖特性追溯

| 能力（0.2.0 七项） | 子断言 | 覆盖 |
|-------------------|--------|------|
| checkpoint-redis | OJ-06.0 / A / B / C | ✅ |
| 短期记忆（Redis 后端） | OJ-06.B | ✅ |
| A2A 流式（message/stream 续轮） | OJ-06.C | 🟡 间接 |

---

## 9. 风险与备注

- **与 B-03 差异**：B-03 为 spring-ai-ascend + 仅 mainplan Redis；OJ-06 为 openjiuwen **三 agent 全链 Redis**，传输同为 **流式**。语义规则可复用，profile 机制不同。
- **与 OJ-03 差异**：OJ-03 为 in_memory + **同步** `message/send`；OJ-06 不测同步路径。
- **三 agent 一致性**：S-01 要求三 agent 同 Redis；本用例 **硬门禁** OJ-06.0。
- **不测跨进程恢复**：Turn1 后重启 mainplan 再 Turn2 不在 v0.2.0 范围。
- **LLM 非确定性**：失败时保留 `target/sit-logs/` 下三 agent 日志。
