---
id: B-04
title: Checkpointer 配置切换（InMemory → Redis）
module: B — Checkpointer / 中间件
owner: haozhizhuang
priority: P1
feature: 特性2-4
status: designed
sut: main-plan-agent
stack: mainplan（单 agent）+ Testcontainers Redis（仅 Phase2）
tags: [integration, smoke]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - Docker 可用（Phase2 Testcontainers Redis）
  - LLM 凭据已配置（见 §6）
  - B-03 已定义之 testdata / 语义断言 helper（本用例复用，见 §7）
smoke_scope: 仅 test.env=SIT 或 test.env=UAT，且 LLM 凭据 + Docker 齐全时纳入 SmokeTestSuite
---

# B-04 — Checkpointer 配置切换（InMemory → Redis）

> **一句话**：在 **单个测试方法** 内先后启动两套 **仅 mainplan** 托管栈——Phase1 `checkpointer=in-memory`，
> Phase2 **仅改** `checkpointer=redis` 与 `redis-url`（同一 fat jar、同一对话 testdata）——
> 各跑一遍两轮流式对话；**各自独立** 满足与 B-03 相同的语义断言，验证 **特性 2-4（切换不改代码）**。
>
> **不要求** InMemory 与 Redis 两次 Turn2 文本逐字相同；**不要求** B-03 / B-01 用例必须先跑过。

---

## 1. 场景目标

验证 Checkpointer 后端可通过 **配置 alone** 在 InMemory 与 Redis 间切换，且多轮对话行为在两种模式下均可达标：

1. Phase1：mainplan 显式 `main-plan-agent.checkpointer=in-memory`，流式两轮对话（Turn1「我要出差」→ Turn2「去北京」续 `contextId`）→ 语义断言 **PASS**；
2. tearDown Phase1 栈（进程退出）；
3. Phase2：启动新 mainplan 栈，**唯一配置差** 为 `checkpointer=redis` + Testcontainers `redis-url` → 同一对话再跑一遍 → 语义断言 **PASS**；
4. 测试代码/meta 断言：两阶段除 checkpointer 相关属性外，栈构建参数一致（§4.B-04.0c）。

**本用例不覆盖**（由其他用例负责）：

- Redis 后端专项（无 InMemory 对照）→ B-03
- InMemory 专项、无切换叙事 → B-01 / B-02
- 三级链 mainplan→trip→hotel → B-03 已覆盖；B-04 刻意 **单 agent** 聚焦中间件切换
- 反向切换 Redis→InMemory、MemoryProvider 切换
- 跨进程：Phase1 写入、Phase2 新进程读 **同一** Redis 键（与 B-03 同，不在 v0.1.0）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 可在 `~/.m2` 解析 | **FAIL** |
| 2 | Docker 可用（Phase2 Redis） | **FAIL** |
| 3 | `SIT_LLM_API_KEY`（或统一 `LLM_*`）非空 | **FAIL** |
| 4 | `test.env` ∈ {SIT, UAT} 时纳入 smoke | LOCAL 可单类调试 |
| 5 | 栈 `streaming(true)`，两轮均为 `message/stream` | — |
| 6 | 复用 `b03-redis-multi-turn.json` 与 B-03 语义 helper | 见 §7 |

---

## 3. 场景步骤（单测两阶段）

| # | 阶段 | 动作 | 预期 |
|---|------|------|------|
| 0 | — | LLM + Docker 门禁 | 缺失 → **FAIL** |
| 1 | Phase1 | 起栈：`checkpointer=in-memory`，仅 mainplan | 就绪 |
| 2 | Phase1 | Turn1 流式 → Turn2 续 `contextId` | B-04.P1.* 全 PASS |
| 3 | Phase1 | `stack.close()` | 进程退出 |
| 4 | Phase2 | Testcontainers Redis 就绪；起 **新** 栈：`checkpointer=redis` + `redis-url` | 就绪 |
| 5 | Phase2 | **同一 testdata** 再跑 Turn1→Turn2 | B-04.P2.* 全 PASS |
| 6 | — | B-04.0c 配置差 meta 校验 | 仅 checkpointer/redis-url 不同 |

> Phase1 **FAIL** 则整个测试 **FAIL**，不进入 Phase2（体现 SIT「先 InMemory 跑通，再改 Redis」）。

---

## 4. 可观测子断言

> 对话级断言 **复用 B-03** 规则（§5）；此处仅列 B-04 特有与子场景前缀。  
> 完整 Turn 断言命名对照 B-03：`A/B/C/D/E/F` → Phase1 为 `P1.A`…，Phase2 为 `P2.A`…

### B-04.0 — LLM 凭据门禁

同 B-03.0。

### B-04.0b — Docker / Redis 门禁（Phase2）

- **Given**：进入 Phase2 前。
- **When**：Testcontainers Redis 启动。
- **Then**：`redisUrl` 非空；Phase2 mainplan 注入 `--main-plan-agent.redis-url=<redisUrl>`。
- **PASS** / **FAIL**：Redis 不可用 → **FAIL**（Phase2 无法代表 redis 切换）。

### B-04.0c — 「仅改配置」硬门禁（测试 meta）

- **Given**：Phase1 与 Phase2 的 `SutStack.Builder` / `AgentConfig` 快照（测试代码内结构化记录，非读 SUT 源码）。
- **When**：对比两阶段 mainplan 的 `property()` / `env()` / `port` / `profile` / artifact。
- **Then**：
  - **允许不同**：`main-plan-agent.checkpointer`（`in-memory` vs `redis`）、Phase2 独有 `main-plan-agent.redis-url`；
  - **必须相同**：fat jar 坐标、LLM 注入、`streaming(true)`、其余 property/env。
- **PASS**：仅上述差分。**FAIL**：Phase2 相对 Phase1 存在额外未声明配置差（违背「不改代码、只改 YAML/启动参数」叙事）。

### B-04.P1 — Phase1（InMemory）对话断言

| 子断言 | 等同 B-03 |
|--------|-----------|
| B-04.P1.A | B-03.A Turn1 流式可达 |
| B-04.P1.B | B-03.B Turn2 续轮 + `COMPLETED` |
| B-04.P1.C | B-03.C Turn2 文本非空 |
| B-04.P1.D | B-03.D 正向关键词 |
| B-04.P1.E | B-03.E 负向禁问句 |
| B-04.P1.F | B-03.F Turn1 终态观测（日志） |

Phase1 额外 **硬门禁**：启动参数含 `main-plan-agent.checkpointer=in-memory`（或等价默认显式化）。

### B-04.P2 — Phase2（Redis）对话断言

与 **B-04.P1** 同结构、同 testdata、同语义规则；**独立判定**，不与 P1 的 Turn2 文本做相等比较。

Phase2 额外 **硬门禁**：

- `main-plan-agent.checkpointer=redis`
- `main-plan-agent.redis-url` 指向 Phase2 Testcontainers 实例

---

## 5. 复用 B-03 的对话规则

以下 **不重复定义**，实现与评审时以 [B-03-redis-checkpointer-multi-turn.md](./B-03-redis-checkpointer-multi-turn.md) §5 为准：

- `textOf(Task)` 抽取顺序
- Turn2 必须复用 Turn1 `contextId`
- `turn2MustMatchAny` / `turn2MustNotMatchAny` 子串匹配
- Turn1 终态 ∈ {`COMPLETED`, `INPUT_REQUIRED`}；Turn2 必须 `COMPLETED`

**建议抽取共享 helper**（实现 B-03 时一并落地）：

```text
com.huawei.ascend.sit.support.checkpointer.TwoTurnDialogueRunner
  run(mainplanClient, B03ScenarioData) → TwoTurnResult
com.huawei.ascend.sit.support.checkpointer.ContextUnderstandingAssertions
  assertTurn2Semantics(text, B03ScenarioData)
```

B-04 测试类只编排 **两阶段栈 + 两次调用 runner**。

---

## 6. LLM 与 Checkpointer 配置

### 6.1 LLM

| 变量 | 必填 |
|------|------|
| `SIT_LLM_API_KEY` 或 `LLM_API_KEY` | 是 |

两阶段 **相同** 注入方式（满足 B-04.0c）。

### 6.2 Phase1 — InMemory

| 属性 | Phase1 值 |
|------|-----------|
| `main-plan-agent.checkpointer` | `in-memory` |

### 6.3 Phase2 — Redis

| 属性 | Phase2 值 |
|------|-----------|
| `main-plan-agent.checkpointer` | `redis` |
| `main-plan-agent.redis-url` | `redis://<host>:<mappedPort>`（Testcontainers） |

属性键与 SUT 一致（`MainPlanAgentConfiguration`：`main-plan-agent.checkpointer` / `main-plan-agent.redis-url`）。

---

## 7. 测试数据

**复用** B-03 文件（不新建 B-04 专用 json）：

`src/test/resources/testdata/integration/checkpointer/b03-redis-multi-turn.json`

加载：`B03ScenarioData.loadDefault()`（或等价常量路径）。

---

## 8. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/checkpointer/CheckpointerConfigSwitchTest.java` |
| 标签 | `@Tag("integration")` `@Tag("smoke")` |
| 生命周期 | **不推荐** 默认 `BaseManagedStackTest` 单栈 `@BeforeAll`；在 **单个 `@Test`** 内对 Phase1/Phase2 各 `try (SutStack stack = builder.start()) { ... }` |
| Phase1 栈 | `SutStack.builder(config).streaming(true).agent("mainplan", a -> a.property("main-plan-agent.checkpointer", "in-memory").…LLM)` |
| Phase2 栈 | 同上，仅改 `checkpointer=redis` + `redis-url=redisUrl` |
| Redis | `@Container` / `@BeforeAll` Testcontainers，Phase1 **不** 依赖 Redis |
| smoke | `@EnabledIf`：SIT/UAT + LLM + Docker |

**伪代码结构**：

```java
@Test
void inMemoryThenRedis_sameDialogue_bothPassSemantics() {
    B03ScenarioData scenario = B03ScenarioData.loadDefault();
    assertConfigDiffGate(buildPhase1Config(), buildPhase2Config(redisUrl)); // B-04.0c

    try (SutStack p1 = buildInMemoryStack().start()) {
        TwoTurnResult r1 = TwoTurnDialogueRunner.run(p1.client("mainplan"), scenario);
        ContextUnderstandingAssertions.assertAll(r1, scenario); // P1.*
    }

    try (SutStack p2 = buildRedisStack(redisUrl).start()) {
        TwoTurnResult r2 = TwoTurnDialogueRunner.run(p2.client("mainplan"), scenario);
        ContextUnderstandingAssertions.assertAll(r2, scenario); // P2.*
    }
}
```

**实现检查清单**：

- [ ] 单 `@Test` 两阶段；Phase1 FAIL 不进入 Phase2
- [ ] B-04.0c 配置差 meta 断言
- [ ] 复用 `b03-redis-multi-turn.json` + 语义 helper
- [ ] Phase1 显式 in-memory；Phase2 redis + Testcontainers url
- [ ] 两阶段 **不** 比较 Turn2 文本互等
- [ ] 仅 mainplan；`streaming(true)`

---

## 9. 运行方式

```bash
export SIT_LLM_API_KEY=<your-key>

./mvnw -Dtest.env=SIT -Dtest=CheckpointerConfigSwitchTest test

./mvnw -Dtest.env=SIT -Dtest=SmokeTestSuite test
```

> Phase1 无 Redis 依赖；无 Docker 时 Phase2 失败 → 整例 **FAIL**。

---

## 10. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 2-4 切换不改代码 | B-04.0c / P1+P2 | ✅ |
| 特性 2-1 InMemory | P1 | ✅（Phase1 主覆盖） |
| 特性 2-2 Redis | P2 | ⚠️ 作为切换后路径，专项连续性是 B-03 |
| 特性 3-2 流式 | P1.A / P2.A | ⚠️ 传输前置 |

---

## 11. 风险与备注

- **与 B-03 分工**：B-03 = 全链 + 仅 Redis + 多轮连续；B-04 = **单 agent + InMemory→Redis 切换 + 双跑独立达标**。
- **LLM 非确定性**：两阶段各自语义断言，**不要求** Turn2 文案一致；任一侧 D/E 失败即 FAIL。
- **Phase2 新 Redis 实例**：Phase2 是新栈 + 新 Redis 容器，**不** 读取 Phase1 写入的数据；本例验证的是「改配置后行为仍达标」，不是跨阶段 Redis 数据继承。
- **「不改代码」边界**：指 SUT fat jar 与业务代码不变；测试侧允许两阶段不同 `AgentConfig.property`。
- **实现顺序**：建议与 B-03 共用 `TwoTurnDialogueRunner` 时 **先实现 B-03 helper**，再写 B-04 编排层。
