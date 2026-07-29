---
feature_id: FEAT-003
feature_title: 智能体任务状态缓存 — DeepAgent 视角
sut: agent-deep-research DeepAgent（`com.openjiuwen.example:agent-deep-research:0.1.0`，`redis-checkpointer` profile 激活 Redis Checkpointer + KV Todolist）
scope: DeepAgent 黑盒；跨轮 checkpointer 召回（sync/stream 双路径 × in-memory/Redis 双后端）、长期记忆召回、v2 spec 新增 KV Todolist 存储的 sessionId 分片隔离
status: designed
owner: TBD
priority: P0
tags: [integration, deepagent, feat-003]
depends_on:
  - agent-deep-research 可执行 jar 已按 application-local.yml 坐标安装到本地 Maven 仓库
  - Docker/Testcontainers 可用（Redis 变体需拉起 redis:7-alpine）
  - LLM 可用（deepseek-v4-pro 等，两轮召回主链路必需，纯 in-memory 快回归可豁免）
  - 跨 JVM 用例需要算子按脚本 kill + restart `redis-checkpointer` profile 的 jar
related_docs:
  - FEAT-003-agent-task-state-cache-reactagent.md（travel-openjiuwen 侧标准 SIT）
  - FEAT-003-agent-task-state-cache-workflow.md（expense-review / plan-agent 侧标准 SIT）
  - version-scope FEAT-003-agent-task-state-cache.md（v2 spec，§5.1 KV Todolist、§5.1.6 MUST 清单）
  - Feat-Func-003-agent-task-state-cache.md（L2 设计）
  - ISSUE_DRAFT_kv-todo-sessionid-fallback.md（DA-KV-01 red-first 依据，openjiuwen-java multi-deep-research-demo/feat-003-evidence/）
---

# FEAT-003 — 智能体任务状态缓存测试用例设计（DeepAgent）

> **一句话**：以 `agent-deep-research` DeepAgent 为对象，把 FEAT-003 中 **checkpointer 跨轮召回** 与 **v2 新增 KV Todolist 存储 sessionId 隔离** 两条与 DeepAgent 强相关的能力，映射到本仓已存在的 6 个 SIT 用例上；不覆盖 standalone/cluster 配置切换、TTL 数值、密码脱敏、SPI 合同 —— 那些能力沿用 ReactAgent 版本或 Workflow 版本，两侧无 DeepAgent 差异。

> **仓库边界**：所有新增测试代码只写入 `agent-runtime-acceptance`；`agent-deep-research` example jar 与 `agent-core-java` runtime 均为只读被测对象；不加载产品类、不反射内部 rail / checkpointer 私有状态、不为测试新增 Agent HTTP/SPI 代理端点。

> **与 ReactAgent / Workflow 版本的关系**：三档 SIT 的 SUT 与关注面互补——
> - **ReactAgent 档**：SUT = travel-openjiuwen（mainplan → trip → hotel），关注 Redis 数据面 / TTL / TaskStore + checkpointer 双角色 / standalone↔cluster 切换 / requirepass 脱敏 / 统一 Redis SPI 合同。
> - **Workflow 档**：SUT = expense-review-workflow + edpa-plan-agent + edpa-adapter，关注 A2A TaskStore 与 workflow 节点 checkpoint 在两端落盘、远端 Versatile / 本地 8 节点 DAG 两种拓扑。
> - **DeepAgent 档（本档）**：SUT = agent-deep-research，关注 DeepAgent 特有的 **planner rail + inner ReActAgent** 双层结构下 checkpointer 是否覆盖两条 A2A 路径（sync / stream）、v2 新增 **KV Todolist** 存储的 sessionId 分片是否生效（rail 装 tool 时会不会误 fallback）。DeepAgent 侧不重复配置切换/SPI/密码脱敏——那些不因 DeepAgent 而变。

## 1. 状态定义

沿用 [ReactAgent 档](FEAT-003-agent-task-state-cache-reactagent.md) 的三态并新增两态：

- **runnable**：能力已实现，本地容器 + LLM 齐备后可直接落地实现，绿即通过。
- **manual**：runnable，但依赖算子在测试中间执行 kill / restart（例如 DA-05-2 / DA-05-4）或 `@Tag("manual")` 让 CI 默认不扫。
- **env-gated**：实现明确，依赖 LLM + 本地 Redis / 本地 jar 齐备；缺任一走 `Assumptions.abort` / 跳过。
- **red-first**：用例编写时上游 bug 未修，首次运行预期红并作为独立 SIT 侧复现证据（DA-KV-01）；上游修复后自动转绿。
- **out-of-scope**：能力属于 ReactAgent 档 / Workflow 档 / component 层，DeepAgent 侧不重复覆盖。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 | DA 附加断言 |
|---|---|---|---|---|
| 同 contextId 两轮 in-memory 记忆召回（sync） | `DA-05-1` | runnable，P0 | 两轮 A2A → turn2 artifact 命中 turn1 姓名 token | Bug 标志串（`deep_agent_task_1 already exists` / `controller task parameter error`）缺席 |
| 同 contextId 两轮 in-memory 记忆召回（streaming） | `DA-05-3` | runnable，P0 | SSE 合并 artifact 命中 turn1 姓名 token | 同上；证明 SSE 路径下 checkpoint 生命周期不错位 |
| Redis checkpointer 跨 JVM 召回（sync，手工 2 步） | `DA-05-2` | manual，P0 | Step1 存 → 算子 kill+restart → Step2 召回 | 需 `-Dda052.contextId=<共享>`；缺失则 `Assumptions` 跳过 |
| Redis checkpointer 跨 JVM 召回（streaming，手工 2 步） | `DA-05-4` | manual，P0 | 同 DA-05-2 但两步均走 SSE | 需 `-Dda054.contextId=<共享>` |
| 长期记忆跨轮召回（DA-06） | `DA-06` | manual，P1 | 两轮同 contextId 流式，turn2 复述 turn1 主题 | agent 可达性 `Assumptions` 兜底；命中 `RECALL_SUBJECT_TOKEN` + 至少一话题词 |
| KV Todolist sessionId 分片隔离（v2 MUST #3） | `DA-KV-01` | red-first / manual，P0 | 两次不同 sessionId + 不同 prompt → Redis SCAN 两个 sessionId 前缀都存在，`default` 命名空间为空 | 底层 bug（`TaskPlanningRail.init` lambda 走 inputs-only 构造器，永远 fallback `"default"`）修复后自动转绿 |
| standalone/cluster 配置切换 / TTL / 密码脱敏 / SPI 合同 | — | out-of-scope | 归 [ReactAgent 档](FEAT-003-agent-task-state-cache-reactagent.md) §4-§6 | 不因 DeepAgent 而变，重复无益 |
| workflow 节点 checkpoint / A2A TaskStore 双端落盘 | — | out-of-scope | 归 [Workflow 档](FEAT-003-agent-task-state-cache-workflow.md) | expense-review / plan-agent 拓扑，DeepAgent 无 workflow 语义 |

runnable = 2；manual = 3；red-first = 1；out-of-scope = 2 类。

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 默认 `TestConfig.load()` 走 LOCAL（`application-local.yml`）；DeepAgent 档不复用 `-Dtest.env=openjiuwen`（openjiuwen profile 未声明 `deep-research` 别名）。
- 别名沿用 `application-local.yml` 中的 `deep-research`（`com.openjiuwen.example:agent-deep-research:0.1.0`）；FEAT-005 有独立 `deep-research-skillhub` 别名，本档不使用。
- Redis 变体（DA-05-2 / DA-05-4 / DA-KV-01）需要 `--spring.profiles.active=redis-checkpointer`：
  - `DA-05-2` / `DA-05-4`：算子手工启动 jar 并显式带 profile；测试类 `buildStack(...)` 只走 `.agent(DEEP_RESEARCH)`（不接管进程），依赖 `-D<test>.contextId=<共享 id>` 贯穿两步。
  - `DA-KV-01`：测试自管栈，`buildStack` 里 `.profile("redis-checkpointer")` + `.serviceBinding("redis", "REDIS_HOST", "{{host}}")` + `.serviceBinding("redis", "REDIS_PORT", "{{port}}")`，Redis 容器由 `sut.services.redis` 自动拉起。
- `DA-06` / `DA-KV-01` 需要真 search-agent 作下游：`SEARCH_AGENT_URL` env 注入；DA-06 依赖 SUT 已有默认 search 配置，DA-KV-01 走双 stack（先起 `search` 再起 `deep-research`）。

### 3.2 main 能力复用

- jar/进程/端口/日志：`ProcessLauncher`、`SutStack`、`ManagedSutInstance`。
- 服务：`BackingServices`、`TestContainerFactory`（Redis 只用 `redis:7-alpine`，不 override）。
- 配置：`AgentBuilder.property/env/profile/serviceBinding`。
- A2A：`A2aServiceClient`、`A2aEventCollector`、`TaskTextExtractor`。
- Redis 数据面：`RedisProbe`（`dbsize()` / `keys(glob)` / `keysAny(...)`；SCAN-only 只读，禁 `KEYS *`）。
- 轮询：`Awaitility` 或 `A2aEventCollector.awaitTerminalState(ms)`。

禁止测试自行实现 ProcessBuilder、端口探测、jar 路径解析和进程销毁；禁止在 DeepAgent 档新写 TestSupport 类。

### 3.3 数据、日志与召回断言约定

- 每方法生成唯一 `contextId`（形如 `ctx-da05-1-inmem-<8-char>`）避免 SUT 记忆缓存与前次跑串扰；DA-KV-01 用同样风格的 `sessionA` / `sessionB`。
- 召回断言依赖 **turn2 用户输入不含 turn1 独有 token** 的干净信号：
  - DA-05-1 / DA-05-3：turn1 存 "张三"，turn2 只问 "我叫什么名字?"（不含 "张三"）→ 命中 = 通过 checkpointer 拿到 turn1 记忆，非机械回显。
  - DA-05-2 / DA-05-4：turn1 存 "薛凡凡"（跨 JVM 变体使用不同 token 与 sync 变体区分），同样断言 turn2 artifact 命中。
  - DA-06：turn1 问 DeepSeek 定价，turn2 只问 "上次问了什么"（不含 "DeepSeek"）→ 命中 `RECALL_SUBJECT_TOKEN` = "DeepSeek" + 至少一个话题词（定价 / token / 价格）。
- 所有召回用例统一守 bug 标志串 `deep_agent_task_1 already exists` / `controller task parameter error`（历史遗留任务并发/task-id 冲突信号），命中即 FAIL。
- KV Todolist 用例（DA-KV-01）走 Redis SCAN 断言：
  - 层 3 前置：`DBSIZE > 0` 且 `keys("*todo*")` 非空（KV backend 已激活）。
  - 层 2 bug 指纹：`keys("*todo*")` 中不应有 key 含 `default` token（未修复时该断言红 → smoking gun）。
  - 层 1 spec 真相：两个 sessionId 的 key 都能在 Redis 中被 SCAN 到（sessionId 维度分片生效）。
- Redis 键空间不硬编码具体 schema；只做 glob（`*todo*` / `*agent_state_blobs` 等）+ 存在性 / 缺席性判断。cluster 场景不适用（本档纯 standalone）。

### 3.4 LLM 与外部依赖

- LLM 参数仅由 shell 环境注入（`LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER` / `LLM_SSL_VERIFY`），不写入源码 / YAML / 测试报告。
- 新版 `agent-deep-research` jar（2026-07-29 起）在 Spring bean 创建阶段 fail-fast 强校验 `deep-research.llm.api-key`；即便某用例逻辑不需要 LLM，也必须先 `source ~/.llmrc` 才能起进程。
- 本地跑必须先 `unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy`，否则内网 LLM 会被 HIS 代理拦 504（stdout.log 出现 "HIS Proxy Notification" HTML 即中招）。

## 4. Checkpointer 跨轮召回子用例

框架落点：`InMemoryCheckpointerRecallTest.java` / `StreamingInMemoryCheckpointerRecallTest.java` / `RedisCheckpointerRecallTest.java` / `StreamingRedisCheckpointerRecallTest.java` / `LongTermMemoryRecallTest.java`（每类单方法，一 test = 一断言链）。

### DA-05-1 — In-memory checkpointer 两轮同 contextId 记忆召回（sync）

- **状态**：runnable，P0。
- **追溯**：FEAT-003 §2 checkpointer 默认 in-memory 语义；deep-research 场景 5.1 手工脚本。
- **G**：SUT 走默认 in-memory 后端（不带 profile），`streaming(false)` 走同步 A2A。
- **W**：turn1 发 "我叫张三,请记住"、turn2 发 "我叫什么名字?"，共用 `contextId`；每轮 send 独立 `A2aEventCollector.awaitTerminalState`。
- **T**：两轮均 `TASK_STATE_COMPLETED`；bug 标志串缺席；turn2 artifact 包含 `"张三"`（关键断言：非机械回显）。
- **方法**：`inMemoryCheckpointerRecallsTurn1IdentityIntoTurn2()`。
- **Story / DisplayName**：`da.checkpointer-inmemory-recall` / `DA-05-1: 同 contextId 两轮 — turn2 应从 in-memory checkpoint 召回 '张三'`。

### DA-05-3 — In-memory checkpointer 跨轮召回（streaming）

- **状态**：runnable，P0。
- **追溯**：FEAT-003 §2 checkpointer 双 A2A 路径覆盖需求；deep-research 场景 5.3。
- **G**：同 DA-05-1，但 `SutStack` 默认 `streaming(true)`，两轮均走 `SendStreamingMessage`。
- **W/T**：turn2 合并 artifact（`A2aEventCollector.collectArtifactText()`）应命中 `"张三"`；断言链与 DA-05-1 相同；防止 SSE 路径下 checkpoint 生命周期与 A2A 事件时序不同步导致的单侧回归。
- **方法**：`streamingInMemoryCheckpointerRecallsTurn1IdentityIntoTurn2()`。
- **Story**：`da.checkpointer-inmemory-recall-streaming`。

### DA-05-2 — Redis checkpointer 跨 JVM 召回（sync，手工 2 步）

- **状态**：manual（`@Tag("manual")`，CI 默认不扫），P0。
- **追溯**：FEAT-003 §3 Redis 后端跨 JVM 持久化；deep-research 场景 5.2。
- **G**：算子准备 Redis + 用 `--spring.profiles.active=redis-checkpointer` 启动 deep-research。测试类不接管 jar 进程，`buildStack` 只走 `.agent(DEEP_RESEARCH)` + `.streaming(false)`。
- **W**：两步命令共享 `-Dda052.contextId=<id>`；
  - Step1：`redisStep1Store` 发 "我叫薛凡凡，请记住" → 断言 turn1 COMPLETED + bug 标志缺席。
  - 算子 `kill $(cat deep-research.pid)` → 同 profile 重启 jar。
  - Step2：`redisStep2Recall` 用同 `contextId` 发 "我叫什么名字?" → 断言 artifact 命中 `"薛凡凡"`。
- **T**：Step2 artifact 包含 `RECALL_TOKEN`；缺失 `-D` 或 agent 不可达时 `Assumptions` 跳过（不 FAIL）。
- **方法**：`redisStep1Store()` / `redisStep2Recall()`，`@Order` 显式。
- **Story**：`da.checkpointer-redis-recall`。

### DA-05-4 — Redis checkpointer 跨 JVM 召回（streaming，手工 2 步）

- **状态**：manual，P0。
- **追溯**：sync 与 stream 两条 A2A 路径均须能跨 JVM 恢复；deep-research 场景 5.4。
- **G/W/T**：与 DA-05-2 同题，两步均走 SSE；共享 `-Dda054.contextId=<id>`；`buildStack` 走默认 `streaming(true)`。
- **方法**：`redisStreamingStep1Store()` / `redisStreamingStep2Recall()`。
- **Story**：`da.checkpointer-redis-recall-streaming`。

### DA-06 — 长期记忆跨轮召回（streaming）

- **状态**：manual（存在已知 bug，需算子先手工重启 deep-research；`@Tag("manual")`），P1。
- **追溯**：FEAT-003 §2 长期记忆语义；deep-research 场景 6。
- **G**：SUT 默认 `streaming(true)`；`Assumptions.assumeTrue(a2a.getAgentCard() != null)` 探活兜底。
- **W**：turn1 问 "DeepSeek 官方 API 的输入 token 定价目前是多少？"（触发搜索 + agent search 下游）；turn2 用同 contextId 问 "我上次问了你什么问题？你上次给我的答案的要点是什么？请直接复述，不要再搜索。"
- **T**：turn2 artifact 同时命中：
  1. 专有名 `"DeepSeek"`（`RECALL_SUBJECT_TOKEN`）；
  2. 至少一个话题词（`"定价"` / `"token"` / `"价格"`）；
  代表 agent 通过长期记忆复述了 turn1 主题（不是任何机械回显——turn2 问句里没有 `"DeepSeek"`）。bug 标志串缺席。
- **方法**：`longTermMemoryRecallsPreviousTurnTopic()`。
- **Story**：`da.long-term-memory-recall`。

## 5. KV Todolist 存储子用例（v2 spec 新增）

框架落点：`KvTodoSessionIdIsolationTest.java`。

### DA-KV-01 — KV Todolist sessionId 分片隔离

- **状态**：red-first / manual，P0；上游 bug 未修前预期红。
- **追溯**：
  - version-scope FEAT-003 §5.1 KV Todolist SPI；§5.1.2 隔离维度；§5.1.6 MUST #3（task-level 隔离前置：session 维度）。
  - 上游依据：ISSUE_DRAFT `multi-deep-research-demo/feat-003-evidence/ISSUE_DRAFT_kv-todo-sessionid-fallback.md` 场景 B（file 后端）/ 场景 C（Redis 后端）—— rail `TaskPlanningRail.init()` 用只接收 `inputs` 的 `LocalFunction` 构造器装 4 个 todo_* tool，`sessionId(inputs)` 从 LLM tool-call args 里取 `session_id`，LLM 从不填 → 永远 fallback 常量 `"default"`；所有 todo 撞进 `default:todo` 单一命名空间。
- **G**：`SutStack` 自管栈——search stack 先起（真 search-agent jar，KV Todolist 与 checkpointer 都不激活），deep-research stack 后起，`.profile("redis-checkpointer")` + Redis service binding + `SEARCH_AGENT_URL` env 注入 search 的 baseUrl。Redis 容器由 `sut.services.redis` 自动拉起。
- **W**：两轮 A2A 同步 send，`contextId` 使用两个完全不同的 sessionId：
  - 轮 1：`sessionA = kv-todo-iso-A-<uuid8>`，prompt `"对比 DeepSeek R1 和 qwen-max 定价"`；
  - 轮 2：`sessionB = kv-todo-iso-B-<uuid8>`，prompt `"对比 GLM 4.5 和 Kimi 定价"`。
  两轮完毕后用 `RedisProbe.keys("*todo*")` SCAN Redis。
- **T**：三层断言：
  - **层 3（前置健康度）**：`dbsize() > 0` 且 `keys("*todo*")` 非空 —— KV backend 已激活，触发前提成立；若为 0 说明 planner 根本没走 todo_* 路径，层 1/2 判读无效。
  - **层 2（bug 指纹，未修复时命中 → 红）**：`keys("*todo*")` 中不应存在 key 含 `default` token；命中 → sessionId fallback 到常量的 smoking gun。
  - **层 1（spec 真相，修复后应绿）**：`sessionA` 与 `sessionB` 的 key 都能被 SCAN 到（存储层按 sessionId 分片）；只见其一 → scenario B 覆盖，只见 `default` → scenario C 覆盖。
- **方法**：`twoSessionsShouldNotCollideInDefaultNamespace()`。
- **Story**：`da.kv-todo-sessionid-isolation` / `da.kv-todo-two-sessions-no-collide`。
- **修复方向（给上游）**：Option A（rail 装 tool 改用带 kwargs 的 `LocalFunction` 构造器，从 `kwargs.get("session")` 拿真 sessionId）/ Option B（从 `SessionContextHolder` ThreadLocal 取）。详见 ISSUE_DRAFT §修复方案。

## 6. 框架落点汇总

| Java 类 | 覆盖子用例 | 私有 fixture / 需要 profile |
|---|---|---|
| `InMemoryCheckpointerRecallTest.java` | DA-05-1 | 默认 in-memory，无 profile；`streaming(false)` |
| `StreamingInMemoryCheckpointerRecallTest.java` | DA-05-3 | 默认 in-memory，无 profile；`streaming(true)` |
| `RedisCheckpointerRecallTest.java` | DA-05-2 Step1 + Step2 | 算子外部启动 `redis-checkpointer` profile jar，测试类不接管；`@TestMethodOrder(OrderAnnotation)` |
| `StreamingRedisCheckpointerRecallTest.java` | DA-05-4 Step1 + Step2 | 同上，两步均 SSE |
| `LongTermMemoryRecallTest.java` | DA-06 | 需 search 下游 + LLM；`Assumptions` 探活兜底 |
| `KvTodoSessionIdIsolationTest.java` | DA-KV-01 | 双 stack（search + deep-research）；`redis-checkpointer` profile；Redis 容器；`RedisProbe` |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/
```

不新增 TestSupport 文件（沿用 `A2aEventCollector` / `TaskTextExtractor` / `RedisProbe` / `BaseManagedStackTest` 等 main 能力）。

## 7. 标签、Story 与报告

统一标签集：

```java
@Tag("integration")
@Tag("deepagent")
@Tag("feat-003")
@Tag("manual")        // DA-05-2 / DA-05-4 / DA-06 / DA-KV-01 —— CI 默认不扫
@Feature("FEAT-003: 智能体任务状态缓存")
```

每个用例带 `@Story("<domain>.<case-id>: <short>")`，DisplayName 以 `DA-05-N` / `DA-06` / `FEAT-003.kv-todo-sessionid-isolation` 开头，与 Story 一一对应。

## 8. 运行方式

```bash
# 前置：清代理 + source LLM 变量
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
source ~/.llmrc

# —— 快回归：in-memory 两条（不需要 Redis / 不需要 kill-restart）——
./mvnw test -o \
  -Dgroups='integration & deepagent & feat-003' \
  -Dtest='InMemoryCheckpointerRecallTest,StreamingInMemoryCheckpointerRecallTest'

# —— DA-05-2 手工两步（Redis checkpointer 跨 JVM，sync）——
# 步骤 1：算子先用 redis-checkpointer profile 启动 jar，再执行：
CTX=ctx-da052-$(date +%s)
./mvnw test -o \
  -Dgroups='integration & deepagent & feat-003 & manual' \
  -Dtest='RedisCheckpointerRecallTest#redisStep1Store' \
  -Dda052.contextId=$CTX
# 步骤 2：算子 kill deep-research → 同 profile 重启 → 再执行：
./mvnw test -o \
  -Dgroups='integration & deepagent & feat-003 & manual' \
  -Dtest='RedisCheckpointerRecallTest#redisStep2Recall' \
  -Dda052.contextId=$CTX

# —— DA-05-4 手工两步（streaming）——
# 与 DA-05-2 相同结构，用 -Dda054.contextId 与 StreamingRedisCheckpointerRecallTest。

# —— DA-KV-01（KV Todo sessionId 分片隔离，red-first）——
./mvnw test -o \
  -Dgroups='integration & deepagent & feat-003 & manual' \
  -Dtest='KvTodoSessionIdIsolationTest'
# 首次运行预期红（层 2 或层 1 命中 bug 指纹）→ 上游修复后自动转绿

# —— 全 FEAT-003 deepagent（含 manual）——
./mvnw test -o -Dgroups='integration & deepagent & feat-003'
```

## 9. 风险与代码生成约束

1. **两轮召回断言依赖 turn2 问句不含 turn1 独有 token**：改问句时须重新审视 recall token 是否仍 "干净"（不出现在问句本身）；否则命中 = 机械回显，断言失效。
2. **手工 2 步 kill-restart 场景**：算子操作错序（未按同一 profile 重启、未复用 pid 文件）会让 DA-05-2 / DA-05-4 假绿或假红。测试内 `Assumptions` 只做 agent 可达性兜底，不校验 profile；由算子文档承担。
3. **DA-06 存在已知 bug**（算子重启前 turn1 会命中 `deep_agent_task_1 already exists`），当前用 `@Tag("manual")` + Assumptions 兜底；bug 修复后应去 `manual` 归入 CI。
4. **DA-KV-01 层 3 前置弱**：若 planner LLM 决策不走 todo_* 路径（例如提示词变化、模型行为漂移），`keys("*todo*")` 为空 → 层 1/2 判读无效。已在断言消息中提示排查方向；提示词若被产品侧改动应更新用例主题。
5. **Redis 容器和端口**：DA-KV-01 通过 `serviceBinding` 让框架自管拉起；本地必须能拉 `redis:7-alpine`（离网机记得配 `~/.testcontainers.properties` 镜像加速）。
6. **LLM fail-fast**：新 jar 强校验 `deep-research.llm.api-key`，纯 JSON validation 类用例也必须 `source ~/.llmrc`，否则 Spring bean 创建阶段直接抛异常。
7. **不重复 ReactAgent / Workflow 覆盖**：配置切换 / TTL / 密码脱敏 / 统一 Redis SPI 合同不在本档；若 DeepAgent 侧发现相关缺陷，评估是否为 DeepAgent-specific 后再决定加档 or 归位到 ReactAgent 档。

## 10. 退出标准

- 六个 Java 类均可由 `deepagent & feat-003` 标签过滤到；`@Feature("FEAT-003: 智能体任务状态缓存")` / `@Story("...")` / DisplayName 三件套齐全。
- **runnable 部分（DA-05-1 / DA-05-3）** 在本地 LLM 就绪、代理清空后应稳定绿；作为 CI 层可接管的最小回归。
- **manual 部分（DA-05-2 / DA-05-4 / DA-06）** 有明确的算子操作脚本 + `Assumptions` 跳过兜底，缺条件时不 FAIL。
- **red-first 部分（DA-KV-01）** 首次执行应红并附完整 bug 指纹（Redis 层观察值 + sessionId 对照）；上游修复后自动转绿即闭环。
- 所有新增代码只在 `agent-runtime-acceptance`；`agent-deep-research` / `agent-core-java` 工作树无修改。

## 11. 版本变更

- **2026-07-29**：首版落地。承接 6 个既有 Java 用例（DA-05-1 / DA-05-3 / DA-05-2 / DA-05-4 / DA-06）+ 1 个 red-first 新增（DA-KV-01）。DA-KV-01 依据 ISSUE_DRAFT `kv-todo-sessionid-fallback.md` 场景 B/C 独立 SIT 侧复现。
