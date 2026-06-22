---
id: B-03
title: Redis — 多轮对话连续性
module: B — Checkpointer / 中间件
owner: haozhizhuang
priority: P1
feature: 特性2-2
status: designed
sut: mainplan→trip→hotel（三级链；仅 mainplan 启用 Redis Checkpointer）
stack: mainplan(ENTRY) + trip(MIDDLE) + hotel(LEAF) + Testcontainers Redis
tags: [integration, smoke]
depends_on:
  - 三个 travel fat jar 可构建并安装至本地 Maven 仓库
  - Docker 可用（Testcontainers 拉起 Redis）
  - LLM 凭据已配置（见 §6）
smoke_scope: 仅 test.env=SIT 或 test.env=UAT，且 LLM 凭据 + Docker 齐全时纳入 SmokeTestSuite
---

# B-03 — Redis Checkpointer 多轮对话连续性

> **一句话**：在 **三级 travel 链路** 上，mainplan 配置 `checkpointer=redis`（Testcontainers Redis），
> 经流式 `message/stream` 完成两轮对话（Turn1「我要出差」→ Turn2「去北京」续 `contextId`），
> 验证 Turn2 在 **Redis 持久化** 下仍理解 Turn1 上下文，终态 `COMPLETED` 且回复满足语义启发式。
>
> 本用例验证 **特性 2-2（Checkpointer Redis）**。对话步骤与 SIT 计划 **B-01 场景等价**，
> 但 **自包含执行**；栈扩展为三级链（用户确认），**不测**进程重启跨进程恢复（→ 后续扩展或 B-04 矩阵补充说明）。

---

## 1. 场景目标

验证 Redis Checkpointer 在 mainplan 上支撑多轮上下文连续：

1. Testcontainers 拉起 Redis，mainplan 通过 `--main-plan-agent.checkpointer=redis` 与 `--main-plan-agent.redis-url=...` 接入；
2. trip / hotel **保持默认 in-memory** checkpointer（不测全链 Redis）；
3. Turn1 流式发送「我要出差」，捕获 `contextId`（Turn1 终态允许 `COMPLETED` 或 `INPUT_REQUIRED`）；
4. Turn2 携带同一 `contextId` 流式发送「去北京」，终态 **必须** `COMPLETED`；
5. Turn2 回复体现对 Turn1「出差」意图的理解（语义/关键词启发式），且不像全新会话重复泛泛追问。

**本用例不覆盖**（由其他用例负责）：

- InMemory checkpointer（→ B-01 / B-02）
- 仅改 YAML 切换 in-memory↔redis、不改代码（→ B-04）
- **跨进程**：Turn1 后重启 mainplan 再 Turn2（用户确认不测；与特性 2-2 矩阵字面「跨进程」有偏差，见 §11）
- trip/hotel 的 Redis checkpointer
- MemoryProvider 会话记忆隔离（→ B-05 / B-06）
- 同步 `message/send` 续轮（→ A-08 视角；本用例固定流式）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` / `agent-trip-a2a` / `agent-hotel-a2a` fat jar 可在 `~/.m2` 解析 | 栈启动失败 → **FAIL** |
| 2 | **Docker** 可用，Testcontainers 可启动 Redis 容器 | **FAIL**（不 skip） |
| 3 | 环境变量 `SIT_LLM_API_KEY`（或栈统一的 `LLM_*`，见 §6）已设置且非空 | **FAIL** |
| 4 | `test.env` 为 `SIT` 或 `UAT` 时纳入 smoke | `LOCAL` 可单类调试 |
| 5 | 栈 `streaming(true)`，客户端走 `message/stream` | 误配 `false` → 偏离本用例 |
| 6 | 同一 JVM 内完成 Turn1→Turn2（**不**重启 mainplan） | — |

---

## 3. 场景步骤

| # | 动作 | 协议 / 配置 | 预期 |
|---|------|------------|------|
| 0 | 检查 LLM 凭据 + Docker | 测试侧 | 缺失 → **FAIL** |
| 1 | `@BeforeAll` 启动 Testcontainers Redis，解析 `redisUrl` | Testcontainers | 容器 RUNNING |
| 2 | 拉起三级栈；**仅 mainplan** 注入 `checkpointer=redis` + `redis-url` | `SutStack` leaf-first | 三 agent 就绪 |
| 3 | 从 testdata 读 Turn1 文案（「我要出差」） | — | 非空 |
| 4 | Turn1：`SendStreamingMessage`，collector 收事件至 Turn1 终态 | `message/stream` | 终态 ∈ {`COMPLETED`, `INPUT_REQUIRED`}；记下 `contextId` |
| 5 | Turn2：同 `contextId` 发送「去北京」 | `message/stream` 续轮 | 终态 `COMPLETED` |
| 6 | 对 Turn2 终态文本执行 §5 语义断言 | — | 满足 B-03.D / E |
| 7 | （可选观测）Turn1 终态写入日志 | — | 不影响主 verdict |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A 流式事件与 Task 快照观测；不读 Redis 键空间、不读 SUT 进程内类。

### B-03.0 — LLM 凭据门禁

- **Given**：准备执行 B-03。
- **When**：读取 `SIT_LLM_API_KEY`（或 `LLM_API_KEY`，与栈注入一致）。
- **Then**：值非空。
- **PASS** / **FAIL**：缺失或空白 → **FAIL**。

### B-03.0b — Redis / Docker 门禁

- **Given**：准备执行 B-03。
- **When**：启动 Testcontainers Redis。
- **Then**：容器在 `redisStartupMs` 内就绪，`redisUrl` 非空。
- **PASS**：就绪。**FAIL**：Docker 不可用或 Redis 启动失败。

### B-03.0c — Checkpointer 配置门禁

- **Given**：mainplan 启动参数。
- **When**：检查 `--main-plan-agent.checkpointer=redis` 与 `--main-plan-agent.redis-url=<Testcontainers>`。
- **Then**：两项均已注入；trip/hotel **未**改 checkpointer。
- **PASS**：满足。**FAIL**：mainplan 未配 redis 或 url 错误。

### B-03.A — Turn1 流式可达

- **Given**：B-03.0～0c 通过。
- **When**：流式发送 Turn1 文案，等待 `turn1TimeoutMs`。
- **Then**：收到 ≥1 个流式事件；`contextId` 非空；Turn1 终态 ∈ {`COMPLETED`, `INPUT_REQUIRED`}。
- **PASS**：满足。**FAIL**：超时无事件、无 `contextId`、终态为 `FAILED`/`CANCELED` 等。

### B-03.B — Turn2 续轮协议

- **Given**：B-03.A 已获得 `contextId`。
- **When**：Turn2 `Message` 携带 **同一** `contextId`（`Message.builder(...).contextId(contextId)`），流式发送。
- **Then**：在 `turn2TimeoutMs` 内收到事件；终态 **必须** `TASK_STATE_COMPLETED`。
- **PASS**：`COMPLETED`。**FAIL**：终态非 `COMPLETED`、或未带 `contextId` 导致明显新会话行为（Turn2 语义断言同时失败时可佐证）。

### B-03.C — Turn2 文本非空

- **Given**：B-03.B 终态 `COMPLETED`。
- **When**：按 §5.1 从 Turn2 终态 Task 抽取文本。
- **Then**：trim 后非空。
- **PASS**：非空。**FAIL**：空或不可解析。

### B-03.D — 上下文理解（正向关键词）

- **Given**：B-03.C 非空文本。
- **When**：与 testdata 中 `turn2MustMatchAny` 列表做子串匹配（忽略大小写可选）。
- **Then**：至少命中 **一项**（默认含「北京」「出差」相关表述）。
- **PASS**：命中 ≥1。**FAIL**：均未命中（像未理解 Turn1 意图）。

### B-03.E — 非重复追问（负向短语）

- **Given**：B-03.C 非空文本。
- **When**：与 testdata 中 `turn2MustNotMatchAny` 禁止短语列表匹配。
- **Then**：**不得**命中「全新会话式」泛泛追问（如「您要去哪里出差」「请告诉我目的地」等，见 testdata）。
- **PASS**：无禁止短语。**FAIL**：命中任一禁止短语（表明像未记住 Turn1）。

> **说明**：D/E 为 LLM 措辞下的 **启发式**，非逐字固定答案；失败时保留 Turn1/Turn2 全文日志便于人工复核。

### B-03.F — Turn1 终态观测（不改变主 verdict）

- **Given**：B-03.A 已 PASS。
- **When**：记录 Turn1 终态。
- **Then**：日志 `turn1_terminal_state=COMPLETED|INPUT_REQUIRED`。
- **PASS**（主判据）：B-03.B～E 满足。

---

## 5. 文本抽取与语义规则

### 5.1 终态文本抽取（`textOf`）

与 A-04 / A-05 相同优先级：

1. `Task.artifacts()` 内 `TextPart` 拼接；
2. 回退 `status().message().parts()`；
3. 回退 `history()` 末条 message；
4. `trim()`。

Turn1 若终态为 `INPUT_REQUIRED`，追问文本通常在 `status().message()`，Turn2 仍须 `COMPLETED` 且满足 D/E。

### 5.2 续轮 `contextId`

- Turn1 流式事件中从首个含 Task 的事件取 `Task.contextId()`；
- Turn2 构造：`Message.builder(A2A.toUserMessage(turn2Text)).contextId(turn1ContextId).build()`；
- **禁止** Turn2 使用新 `contextId`。

### 5.3 testdata 语义表

| 字段 | 默认意图 |
|------|----------|
| `turn2MustMatchAny` | 正向：至少出现「北京」或「出差」等 |
| `turn2MustNotMatchAny` | 负向：不得像首轮一样重新收集意图的泛问句 |

匹配规则：简单 **子串包含**（实现阶段可用 `String.contains`，中文大小写不敏感可选）。

---

## 6. LLM 与 Redis 配置

### 6.1 LLM

与 `SyncTravelPlanningTest` 对齐：三 agent bundled yaml 使用 `${LLM_*}` 时，经 `ProcessLauncher` 传递测试 JVM 环境；或 mainplan 专用 `SIT_LLM_API_KEY` → `main-plan-agent.api-key` property（与 A-04 一致，实现时二选一并在测试类固定一种）。

| 变量 | 必填 | 说明 |
|------|------|------|
| `SIT_LLM_API_KEY` 或 `LLM_API_KEY` | 是 | 驱动 mainplan ReAct |

### 6.2 Redis（Testcontainers）

```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);
// redisUrl = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379)
```

mainplan 启动参数（`AgentConfig.property`）：

| 属性 | 值 |
|------|-----|
| `main-plan-agent.checkpointer` | `redis` |
| `main-plan-agent.redis-url` | Testcontainers 动态 URL |

**实现依赖**：在 `pom.xml` 增加 `org.testcontainers:redis`（或沿用 `GenericContainer` + `redis:7-alpine` 镜像，与现有 `testcontainers` + `junit-jupiter` 一致）。

---

## 7. 测试数据

文件：`src/test/resources/testdata/integration/checkpointer/b03-redis-multi-turn.json`

```json
{
  "_doc": "B-03 Redis checkpointer — two-turn dialogue (same as SIT B-01 wording)",
  "turn1Text": "我要出差",
  "turn2Text": "去北京",
  "turn1TimeoutMs": 90000,
  "turn2TimeoutMs": 120000,
  "turn1AllowedTerminalStates": ["TASK_STATE_COMPLETED", "TASK_STATE_INPUT_REQUIRED"],
  "turn2ExpectedTerminalState": "TASK_STATE_COMPLETED",
  "turn2MustMatchAny": ["北京", "出差"],
  "turn2MustNotMatchAny": [
    "您要去哪里出差",
    "请问您要去哪里",
    "请告诉我您的目的地",
    "您打算去哪里"
  ],
  "redisStartupMs": 30000
}
```

读取方式：`B03ScenarioData` + `TestDataLoader`；禁止在测试方法硬编码 Turn 文案。

---

## 8. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/checkpointer/RedisCheckpointerMultiTurnTest.java` |
| 标签 | `@Tag("integration")` `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest`（或 `@Testcontainers` + 自管 `SutStack` 生命周期；Redis 容器需 `@BeforeAll` 先于栈启动） |
| 栈 | `.streaming(true)`；leaf-first：`hotel` → `trip(MIDDLE→hotel)` → `mainplan(ENTRY→trip, redis props)` |
| 客户端 | `client("mainplan")`；Turn1/Turn2 均 `sendMessage` + `A2aEventCollector`（SSE） |
| 断言 | AssertJ + §5 语义 helper（`assertContextUnderstanding(text, testdata)`） |
| smoke | `@EnabledIf`：`TestEnvironment` ∈ {SIT,UAT} 且 LLM + Docker 可用 |

**`buildStack` 示例（概念）**：

```java
return SutStack.builder(config)
        .streaming(true)
        .agent("hotel")
        .agent("trip", a -> a.role(SutAgent.Role.MIDDLE).downstream("hotel"))
        .agent("mainplan", a -> a.role(SutAgent.Role.ENTRY).downstream("trip")
                .property("main-plan-agent.checkpointer", "redis")
                .property("main-plan-agent.redis-url", redisUrl));
```

**实现检查清单**：

- [ ] Testcontainers Redis `@BeforeAll` 启动，失败即 FAIL
- [ ] 仅 mainplan 注入 redis checkpointer
- [ ] Turn2 必须复用 Turn1 `contextId`
- [ ] Turn1 不断言固定终态；Turn2 必须 `COMPLETED`
- [ ] B-03.D / E 语义断言 + 失败日志保留两轮全文
- [ ] 三 fat jar + LLM 门禁

---

## 9. 运行方式

```bash
# 前置：Docker 运行中 + 构建三 agent jar + LLM
export SIT_LLM_API_KEY=<your-key>

./mvnw -Dtest.env=SIT -Dtest=RedisCheckpointerMultiTurnTest test

./mvnw -Dtest.env=SIT -Dtest=SmokeTestSuite test
```

> 无 Docker 时本用例 **FAIL**，不用 `Assumptions` 跳过（smoke 门禁要求明确红/绿）。

---

## 10. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 2-2 Checkpointer Redis | B-03.0b / 0c / B～E | ✅ |
| 特性 2-1 InMemory（对比基线） | — | ❌（B-01 负责） |
| 特性 1-1 OpenJiuwenAdapter | Turn1/Turn2 ReAct | ⚠️ 链路前置 |
| 特性 1-2 远程 Agent | 三级栈在线 | ⚠️ 环境前置；Turn2 可能触发远程派发，非本用例主断言 |
| 特性 3-2 流式 | B-03.A / B | ⚠️ 传输前置 |

---

## 11. 风险与备注

- **与 SIT B-01 差异**：B-01 为单 mainplan + 计划未写三级链；本实现按用户确认使用 **全链栈**，对话文案仍与 B-01 一致，主断言仍为 **Checkpointer 多轮连续**。
- **与特性 2-2 字面「跨进程」**：矩阵描述跨进程共享；本用例 **不测** 重启恢复。若矩阵需严格对齐，可在 B-03 通过后另开 B-03b 或修订矩阵说明「同进程 Redis 后端」。
- **LLM flaky**：D/E 启发式可能偶发 FAIL；保留 event 流与两轮文本，必要时放宽 `turn2MustNotMatchAny` 仅作 warning（默认仍 **FAIL** 以保持门禁严格）。
- **Turn2 触发远程 trip**：全链环境下 Turn2 信息较完整时可能 dispatch；只要 B-03.B～E 满足即 PASS，不断言是否调用远程工具。
- **与 B-04 边界**：B-04 专测 in-memory→redis **切换**；B-03 专测 redis 模式下多轮行为。
