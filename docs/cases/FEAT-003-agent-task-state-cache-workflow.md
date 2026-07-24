---
feature_id: FEAT-003
feature_title: 智能体任务状态缓存
sut: WorkflowAgent（本地 expense-review / 远端 Versatile 控制器）
scope: 本档覆盖 WorkflowAgent SIT 侧可外部黑盒断言的 FEAT-003 事实要求。本地 expense-review 的 standalone+认证、cluster 场景已由 workflow_call 用例覆盖；剩余状态缓存语义（TTL、重启恢复、任务隔离、Redis 故障可诊断、配置变体、自定义适配）按 `<agent><Capability>Test` 规则命名并标记待补齐。
status: designed
owner: TBD
tags: [integration, workflowagent, redis, feat-003]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等 SAA_* 环境变量）
  - 被测 jar 就绪：expense-review-workflow、edpa-plan-agent、edpa-adapter、envexplorer
  - Docker（Testcontainers Redis / grokzen/redis-cluster）
related_docs:
  - FEAT-003 特性文档（version-scope，外部契约）：`spring-ai-ascend/version-scope/FEAT-003-agent-task-state-cache.md`
  - 姊妹篇（FEAT-001 入口/框架分工边界）：`FEAT-001-standardized-agent-service-entrypoint-workflow.md`
  - 姊妹篇（FEAT-002 异构框架）：`FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md`
  - 本仓 SIT 现状总述：`../SIT.md`
  - 框架/基础设施用法：`../framework/STATUS.md`（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
---

# FEAT-003 智能体任务状态缓存 — WorkflowAgent SIT 测试设计

> **一句话**：以 WorkflowAgent 为对象，把 FEAT-003 §2/§3/§4/§5 事实要求映射为可黑盒断言的子用例，并按 story 标注实现/覆盖现状（2026-07-22 登记）：**远端 Versatile 场景**已由 `PlanAgentDirectStreamingRedisTest` 实现；**本地 expense-review 场景**已由 `ExpenseReviewRedisAcceptanceTest`（standalone + 认证 + 密码脱敏）与 `ExpenseReviewRedisClusterAcceptanceTest`（cluster 拓扑）覆盖；剩余状态缓存语义（TTL、重启恢复、任务隔离、Redis 故障可诊断、配置变体、自定义适配）按 `<agent><Capability>Test` 规则重命名后标记为 ⬜ 待新建。

> **组织原则（与 FEAT-001/FEAT-002 档一致）**：
> 1. **同类项合并到单一测试类**：同一被测面的多条事实要求合并为一个测试类的多个 `@Test` 方法，不逐条独立成类。
> 2. **非法输入/错误配置场景与 SDK 错误码验证不在 SIT 考虑**：缺 nodes、非法 type、缺 ref、非法 TTL 等配置校验，以及 SPI 方法面（文本/二进制、setnx、mget、scanIter、跨 slot）属 component 层或 contract 层关注点，归 `cases/component` 或 react_travel 的 contract 用例；SIT 不建非法输入用例，也不断言具体错误码。**SIT 的故障覆盖只采纳逻辑合理的故障模拟**——报销审批等待期间 Redis 不可达、客户封装组件命令差异/连接问题均为业务可达场景。
> 3. **业务流程优先，传输层能力不单独建用例**：SIT 核心是以业务流程串通整体逻辑。Redis key schema 非外部稳定契约，键空间断言只做"存在且与 taskId 相关"的弱形态；cluster 下禁用 `KEYS *`，只用 scan 并容忍 MOVED。

> **落地分层约定（2026-07-22 按实现现状登记）**：业务流程面（本地 expense-review / 远端 Versatile 的 Redis 业务链路）留在 `cases/integration/workflow_call`；无 workflow 业务面的 SPI 合同、配置错误诊断、连接生命周期等归 component / contract 层（本仓 react_travel 已存在 `Feat003RedisStandaloneBehaviorTest` / `Feat003RedisConfigurationAndDiagnosticsTest` / `Feat003RedisClusterAndSwitchTest` 作为同型先例证据，见 §2.3）。

**状态含义**：
- **runnable**：被测能力已实现，可直接落地实现
- **partial**：核心路径可测，某些断言维度受限
- **deferred**：依赖能力在整个栈上缺失，待补齐后回归

## 1. 文档说明

本文档承接 [`FEAT-003-agent-task-state-cache.md`](../../third_party/spring-version-scope/version-scope/FEAT-003-agent-task-state-cache.md)，针对其中涉及 **WorkflowAgent** 的范围输出 SIT 测试设计。运行时单一开关 `openjiuwen.service.middleware.checkpointer.type=redis` 同时激活 Redis Checkpointer 与 Redis A2A TaskStore（复用同一 Redis 数据源抽象）。

**两种工作流场景**：WorkflowAgent 在本仓以两种拓扑承载，本档统一纳入设计——

- **本地工作流场景**：`expense-review-workflow` 单 jar 内嵌 8 节点 DAG，由 `expense-review-main` 作为远程 A2A 工具调用。Task 快照与 workflow 节点状态在两端落盘。**已有入口串通用例（`ExpenseReviewAcceptanceTest`，Allure 注册在 FEAT-004）与 Redis 变体（`ExpenseReviewRedisAcceptanceTest`、`ExpenseReviewRedisClusterAcceptanceTest`）覆盖。**
- **远端工作流场景**：工作流执行在远端 Versatile 控制器，`edpa-adapter` 作无状态桥（A2A TaskStore 在 adapter 侧）、`edpa-plan-agent` 承载智能体执行（checkpointer 在 plan-agent 侧）。**已由 `PlanAgentDirectStreamingRedisTest` 实现并纳入本档。**

**SIT 设计原则**（与 FEAT-001/FEAT-002 档一致）：业务流程串通为核心；同类关注点合并到单一测试类多 `@Test` 方法；非法输入/错误配置/SPI 错误码不进 SIT；故障注入只采纳业务中**逻辑合理、可达**的点。客户封装 Redis 组件按 **SIT 必覆盖场景**处理：以模拟客户适配组件接入 agent 进程，覆盖正常承接/命令差异/装配连接问题（§2.1 待补齐项）。真实客户 JAR 现场联调属 customer-site（feature §2 明确其不可外传），内部由模拟组件覆盖。

**关联文档**：

- FEAT-003 特性文档（version-scope，外部契约）：[`spring-ai-ascend/version-scope/FEAT-003-agent-task-state-cache.md`](../../third_party/spring-version-scope/version-scope/FEAT-003-agent-task-state-cache.md)
- 姊妹篇（FEAT-001 入口/框架分工边界）：[`FEAT-001-standardized-agent-service-entrypoint-workflow.md`](FEAT-001-standardized-agent-service-entrypoint-workflow.md)
- 姊妹篇（FEAT-002 异构框架）：[`FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md`](FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md)
- 本仓 SIT 现状总述：[`../SIT.md`](../../SIT.md)
- 框架/基础设施用法：[`../framework/STATUS.md`](../framework/STATUS.md)（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
- component/contract 层同型先例：[`src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat003RedisStandaloneBehaviorTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat003RedisStandaloneBehaviorTest.java) / [`Feat003RedisConfigurationAndDiagnosticsTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat003RedisConfigurationAndDiagnosticsTest.java) / [`Feat003RedisClusterAndSwitchTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat003RedisClusterAndSwitchTest.java)

## 2. 规格点覆盖矩阵（workflow 视角）

### 2.1 本地工作流场景（expense-review，已纳入 2 类；待补齐 3 类）

| 规格点（feature 出处） | workflow 侧验证设计 | 用例 | 状态 |
|---|---|---|---|
| 原生单机接入 + 启动策略日志（§2 MUST、§3：endpoint type / ref / client / TTL 摘要） | workflow agent + main 以 redis 配置启动，断言诊断日志、profile 激活行与 Redis 可达（hermetic 门禁，无需 LLM） | `workflow_call/ExpenseReviewRedisAcceptanceTest::redisMiddlewareActivatesOnBoot` | ✅ 已实现（workflow_call） |
| A2A Task 与 agent 状态复用同一 Redis 数据源（§2 SHOULD） | 超标/合规报销全流程：Redis 出现 Task 快照键与 workflow 状态键（同库双角色） | `workflow_call/ExpenseReviewRedisAcceptanceTest` 继承 `AbstractExpenseReviewAcceptanceTest` 的 8 参数化场景 | ✅ 已实现（workflow_call，业务链路隐含） |
| 挂起/恢复边界状态缓存（§2、§4.1：Task 级恢复上下文边界在 workflow 的特有形态） | 场景 1 超标报销 → INPUT_REQUIRED → 续 approved → COMPLETED，状态经 Redis 持久化 | `workflow_call/ExpenseReviewRedisAcceptanceTest` 场景 1 参数化 | ✅ 已实现（workflow_call，业务链路隐含） |
| 状态缓存 TTL（§2 SHOULD、§5.1.3） | 短 TTL 配置下完成任务，轮询至快照过期：getTask 由可读变为 not-found 语义 | ⬜ `ExpenseReviewRedisStateCacheTest::taskSnapshotExpiresWithConfiguredTtl` | ⬜ 待新建 |
| Redis 故障不自动降级（§5.2 不承诺项的反向验收） | INPUT_REQUIRED 后使 Redis 不可达，续接须明确失败/可诊断，不得假 COMPLETED | ⬜ `ExpenseReviewRedisStateCacheTest::redisUnavailableAtResumeFailsDiagnosably` | ⬜ 待新建 |
| 任务级隔离（§4.1 共用 Redis 的等价单 agent 语义） | 并行两笔报销（合规 + 超标），各自终态正确、状态键按 taskId 隔离、恢复不串线 | ⬜ `ExpenseReviewRedisStateCacheTest::concurrentExpenseTasksStateIsolated` | ⬜ 待新建 |
| 缺省配置兼容：不配 type 按单机（§5.1.1） | 只注入 host/port（不配 type/ref）启动，诊断显示 standalone/default | ⬜ `ExpenseReviewRedisConfigTest::legacyConfigWithoutTypeDefaultsToStandalone` | ⬜ 待新建 |
| 命名连接引用、多使用方复用（§3：redis 连接引用按名引用） | default→DB0、secondary→DB2、`checkpointer.redis-ref=secondary`；跑业务后状态只落 DB2 | ⬜ `ExpenseReviewRedisConfigTest::namedReferenceRoutesWorkflowStateToConfiguredDatabase` | ⬜ 待新建 |
| 密码日志脱敏（§2 MUST、§5.1.5） | Testcontainers requirepass Redis + `REDIS_PASSWORD` env：业务跑通，全日志无密码 canary，含 `passwordConfigured=true` | `workflow_call/ExpenseReviewRedisAcceptanceTest::redisPasswordDoesNotLeakInLogs`（story `wf.password-desensitize`） | ✅ 已实现（workflow_call） |
| 原生集群接入、同一接口（§2 MUST、§4.1） | grokzen/redis-cluster 6 节点 cluster 跑 expense-review 8 场景，语义与单机等价；hermetic 门禁断言 cluster 诊断与 bridge-IP 可达 | `workflow_call/ExpenseReviewRedisClusterAcceptanceTest::redisClusterMiddlewareActivatesOnBoot` + 继承 8 参数化场景 | ✅ 已实现（workflow_call） |
| 单机↔集群配置切换只改配置（§2 MUST、§4.1） | 同一 agent 先单机跑一笔，改 endpoint type+nodes 重启切 cluster 再跑一笔；两阶段业务均成功 | ⬜ `ExpenseReviewRedisClusterStateCacheTest::standaloneToClusterSwitchWithoutBusinessChange` | ⬜ 待新建 |
| 集群忽略 database + 非敏感 ignored 提示（§3、§5.1.1） | cluster + database=3 启动，业务可用且日志含 `databaseIgnored=3` | ⬜ `ExpenseReviewRedisClusterStateCacheTest::clusterIgnoresStandaloneDatabaseWithDiagnostic` | ⬜ 待新建 |
| 客户封装 Redis 适配扩展点（§2 MUST）——**模拟客户侧问题** | 测试侧构建模拟客户适配组件（实现统一 Redis SPI、包装真实客户端、带故障开关）加入 agent 进程类路径：业务链路经客户组件承接，诊断显示适配实现标识 | ⬜ `ExpenseReviewRedisCustomAdapterTest::customAdapterServesWorkflowStateCache` | ⬜ 待新建 |
| 客户组件与原生命令差异（§5.1.2：语义对齐或显式报错） | 模拟组件拒答指定命令 → 适配层显式报错、错误语义可诊断 | ⬜ `ExpenseReviewRedisCustomAdapterTest::customAdapterCommandMismatchFailsExplicitly` | ⬜ 待新建 |
| 客户组件装配/连接问题（§4.1 接入客户组件场景的故障面） | 模拟组件指向不可达后端/装配失败 → 启动或首用明确失败，不静默回退默认原生实现 | ⬜ `ExpenseReviewRedisCustomAdapterTest::customAdapterFailureFailsWithoutSilentFallback` | ⬜ 待新建 |

**已实现类的 Allure 注册**：

- `ExpenseReviewRedisAcceptanceTest`：`@Feature("FEAT-003: 智能体任务状态缓存")`，stories `wf.password-desensitize`、`wf.redis-standalone-activate`、`wf.task-state-reuse`、`wf.input-required`。
- `ExpenseReviewRedisClusterAcceptanceTest`：`@Feature("FEAT-003: 智能体任务状态缓存")`，stories `wf.cluster-access`、`wf.config-switch`、`wf.task-state-reuse`。

### 2.2 远端工作流场景（Versatile，✅ 已实现纳入）

Versatile 控制器远端承载工作流执行：客户端经 A2A/REST 触达 `edpa-plan-agent`，由 `edpa-adapter` 桥接远端 versatile 服务（envexplorer）完成多轮模型/工具交互。Redis 状态缓存分布在两端——adapter 侧为 A2A TaskStore（无 Runner/checkpointer，属设计上的无状态桥），plan-agent 侧为 checkpointer——复用同一 Redis 数据源。

| 规格点（feature 出处） | 验证设计 | 用例 | 状态 |
|---|---|---|---|
| 原生单机接入 + 启动策略日志（§2 MUST、§3） | 两 agent 均加载 `redis` profile 启动：plan-agent 断言 checkpointer 行（`Begin to initializing checkpointer with type: redis`），adapter 断言 Redis 数据源诊断行（`Runtime Redis datasource selected:` + `RuntimeRedisClient=`），外加 Spring profile 激活行与 Redis 可达探测 | `workflow_call/PlanAgentDirectStreamingRedisTest::redisMiddlewareActivatesOnBoot` | ✅ 已实现（hermetic） |
| A2A Task 与 agent 状态复用同一 Redis 数据源（§2 SHOULD）+ 远端业务链路串通 | 直连 plan-agent 跑「查余额 + 给李四/王五各转 50」stepUi 自推进（A2A_STREAM/REST_QUERY 参数化）：业务语义断言 + 键空间门禁（`*agent_state_blobs`/`*workflow_state_blobs` 与 `a2a:task:*` 同库出现）+ 转账完成态硬断言 | `workflow_call/PlanAgentDirectStreamingRedisTest::balanceThenTransfersDirectRedis` | ✅ 已实现（已实跑通过） |

**远端 vs 本地的覆盖互补**：远端场景验证了"两端激活 + 业务链路双角色落盘"；本地场景（§2.1）在此基础上补齐挂起/恢复跨 JVM、TTL、任务级隔离、集群配置切换与模拟客户组件等语义——这些语义由同一 runtime 承接，远端场景不重复建设；若后续 Versatile 侧需要挂起/恢复边界的 Redis 语义验证，归入 FEAT-002 档的 Versatile 设计统一考虑。

### 2.3 无 workflow 业务面的规格点 — 处置结论

| 规格点（feature 出处） | 处置 |
|---|---|
| 统一 Redis 操作接口最小命令面（§2、§3 SPI、§5.1.2） | 归 **contract/component 层**：SPI 方法面（文本/二进制、TTL、setnx、mget、scanIter、跨 slot）无业务触发路径，acceptance 内以 test-scope runtime artifact 验证，不经 workflow 业务流。本仓 react_travel 已存在 `Feat003RedisStandaloneBehaviorTest` / `Feat003RedisClusterAndSwitchTest` 作为同型先例 |
| 配置错误可诊断（§2 SHOULD、§3：缺 nodes / 非法 type / 缺 ref / 非法 TTL 等） | 归 **component 层**（既定原则：非法输入/错误配置不进 SIT）。本仓 react_travel 已存在 `Feat003RedisConfigurationAndDiagnosticsTest` 作为同型先例 |
| 连接资源生命周期（§2 SHOULD：初始化/释放不泄漏） | runtime 级语义，无 workflow 业务差异；由既有 runtime 级用例复验，本档不重复 |
| reset conversation 清理状态 | ReAct 会话能力，workflow agent 无此业务面，不适用 |
| DeepAgent Todolist 快照 / 自治持久化 / 任务级隔离 key / 文件存储排除（§2 新增 4 条 MUST、§4.2） | DeepAgent 专属能力，归 deepagent 侧设计，不在本档 |

### 2.4 规格不承诺项 — 验收豁免（feature §5.2）

| 不承诺项 | 豁免说明 |
|---|---|
| 真实客户 JAR 现场联调 / 客户监控平台 / 私有安全协议 | 🏷 customer-site：真实 JAR 因合规不可外传；内部以**模拟客户适配组件**覆盖适配能力与客户侧常见问题（§2.1），最终现场联调在客户环境完成 |
| Redis 部署/容灾/备份、全命令暴露、业务数据迁移、key schema 稳定、零重启切换、多租户 namespace、跨 runtime 自动 keyspace 隔离、多数据源路由 | 属部署与系统工程责任，不建 SIT 用例；键空间断言遵守"key schema 非稳定契约"弱形态约定 |
| 运行时故障自动降级（fail-open / fail-close / 内存降级） | 不验证降级本身；以"明确失败/可诊断"反向验收（§2.1 故障用例） |

## 3. 状态分布

| 状态 | 数量 | 内容 |
|---|---|---|
| ✅ 已实现（本档纳入） | 3 类 / 4 显式方法 + 24 参数化场景 | `PlanAgentDirectStreamingRedisTest`（远端工作流：激活门禁 + 业务链路键空间）、`ExpenseReviewRedisAcceptanceTest`（本地 standalone+认证+脱敏：2 显式方法 + 8 协议参数化场景）、`ExpenseReviewRedisClusterAcceptanceTest`（本地 cluster：1 显式方法 + 8 协议参数化场景） |
| ⬜ 待新建 | 3 类 / 9 方法 | `ExpenseReviewRedisStateCacheTest`（TTL、Redis 故障可诊断、任务隔离，3）、`ExpenseReviewRedisConfigTest`（legacy 缺省兼容、命名引用路由，2）、`ExpenseReviewRedisClusterStateCacheTest`（单机↔集群切换、cluster 忽略 database，2）、`ExpenseReviewRedisCustomAdapterTest`（模拟客户适配：正常承接/命令差异/装配故障，3） |
| — 处置项 | 5 | SPI 合同 / 错误配置 → contract·component（react_travel 已有先例）；生命周期、reset → 不适用或既有复验；Todolist → deepagent 侧 |
| 🏷 customer-site | 1 | 真实客户 JAR 现场联调（内部已由模拟客户组件覆盖适配能力与常见问题） |

说明：workflow_call 现有用例已覆盖本地/远端 WorkflowAgent 的 Redis 单机接入、认证脱敏、cluster 接入与业务链路串通。本档待补齐项共 3 类 9 方法，均可在现有叶子类基础上扩展或新建。

## 4. 进度看板

| 分组 | 用例 | 状态 | 备注 |
|---|---|---|---|
| workflow_call 已实现 / 远端工作流 | `PlanAgentDirectStreamingRedisTest`（2 方法） | ✅ 已实现 | stories `wf.redis-two-role` / `wf.redis-standalone-activate` / `wf.task-state-reuse`；A2A_STREAM/REST_QUERY |
| workflow_call 已实现 / 本地 standalone+认证 | `ExpenseReviewRedisAcceptanceTest`（2 显式方法 + 8 参数化场景） | ✅ 已实现 | stories `wf.password-desensitize` / `wf.redis-standalone-activate` / `wf.task-state-reuse` / `wf.input-required`；四协议 |
| workflow_call 已实现 / 本地 cluster | `ExpenseReviewRedisClusterAcceptanceTest`（1 显式方法 + 8 参数化场景） | ✅ 已实现 | stories `wf.cluster-access` / `wf.config-switch` / `wf.task-state-reuse`；四协议；grokzen/redis-cluster:6.2.14 |
| P1 新建 | `ExpenseReviewRedisStateCacheTest`（3 方法） | ⬜ 待新建 | TTL / Redis 故障可诊断 / 任务隔离 |
| P1 新建 | `ExpenseReviewRedisConfigTest`（2 方法） | ⬜ 待新建 | legacy 缺省兼容 / 命名引用路由 |
| P1 新建 | `ExpenseReviewRedisClusterStateCacheTest`（2 方法） | ⬜ 待新建 | 单机↔集群切换 / cluster 忽略 database |
| P1 新建 | `ExpenseReviewRedisCustomAdapterTest`（3 方法） | ⬜ 待新建 | 模拟客户适配组件 |
| — 归 component/contract | react_travel `Feat003RedisStandaloneBehaviorTest` / `Feat003RedisConfigurationAndDiagnosticsTest` / `Feat003RedisClusterAndSwitchTest` | ✅ 已有先例 | SPI 合同、配置错误诊断、cluster 切换合同 |
| 🏷 customer-site | 真实客户 JAR 现场联调 | — | 内部由模拟客户组件覆盖 |

## 5. 子用例设计（G/W/T）

### 5.1 已实现：ExpenseReviewRedisAcceptanceTest（本地工作流 standalone + 认证 + 密码脱敏）

**FSD 需求条目**：story 1（Redis 缓存任务状态 + 复用连接池）；§2 单机接入 MUST、策略日志 MUST、密码脱敏 MUST；§2 SHOULD Task/状态复用；§4.1 恢复边界。

**覆盖业务链路**：客户端 →（四协议参数化）→ `expense-review-main` → `expense-review-workflow`（8 节点 DAG）→ INPUT_REQUIRED 挂起（Task 快照 + workflow 状态脱水）→ approved 恢复 → COMPLETED。

**拓扑与注入**：`expense-review` jar 内置 `application-redis.yml`（与 edpa overlay 同型），以 `profile("redis")` + `serviceBinding("redis", "REDIS_HOST", "{{host}}")` / `serviceBinding("redis", "REDIS_PORT", "{{port}}")` + `env("REDIS_PASSWORD", password)` 注入。Redis 容器由自定义 `AuthenticatedRedisFactory`（实现 `ContainerFactory`）以 `--requirepass <canary>` 拉起；框架通过 `.containerFactory(...)` 替换默认 factory。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `redisMiddlewareActivatesOnBoot`（hermetic） | Testcontainers 认证 Redis 就绪；两 agent 以 redis profile + `REDIS_PASSWORD` 启动 | 就绪后读启动日志并探测 Redis | 两 agent 均含 `The following 1 profile is active: "redis"`；`expense-review-main` 含 `Begin to initializing checkpointer with type: redis`；`expense-review-workflow` 含 `Runtime Redis datasource selected:` + `RuntimeRedisClient=`；`RedisProbe`（带 AUTH）DBSIZE 可达 |
| `redisPasswordDoesNotLeakInLogs`（hermetic） | 同上；canary 为本次运行唯一 UUID | 启动并读两 agent 全量日志 | 日志含 `passwordConfigured=true`；canary **不**出现在任何 agent 日志 |
| 继承 `overLimitExpenseRequiresApprovalThenCompletesOnApprove(protocol)`（8 协议参数化） | 同上 Redis 栈 | 超标报销 → INPUT_REQUIRED → 续 approved → COMPLETED | 终态与流式轨迹正确；结果非空；状态经 Redis 持久化 |
| 继承 `compliantExpenseAutoApprovesAndCompletes(protocol)`（8 协议参数化） | 同上 Redis 栈 | 合规报销 → 自动通过 COMPLETED | 终态与结果正确；状态经 Redis 持久化 |

**依赖的外部组件 / fixture**：openJiuwen profile；Docker；`AuthenticatedRedisFactory`（内嵌 `GenericContainer` + `--requirepass`）。

**对应实现位置**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisAcceptanceTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack.Builder.containerFactory(...)` / `.profile(...)` / `.serviceBinding(...)` / `.env(...)`；`InteractionFlow`（续轮携 taskId+contextId）；`RedisProbe`（带 AUTH 的 3 参构造）；`ManagedSutInstance.logFile()`。

**执行命令**：

```bash
mvn test -Dtest=ExpenseReviewRedisAcceptanceTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

**状态**：✅ 已实现（编译绿，启动门禁无需 LLM，业务场景需 LLM）。

### 5.2 已实现：ExpenseReviewRedisClusterAcceptanceTest（本地工作流 cluster 拓扑）

**FSD 需求条目**：story 1；§2 集群接入 MUST；§4.1 同一接口；§2 SHOULD 状态复用。

**覆盖业务链路**：同 §5.1，但栈整体切到 **redis cluster 拓扑**。

**拓扑与注入**：自管 `grokzen/redis-cluster:6.2.14`（单容器 6 节点 3 主 3 从，端口 7000–7005），取容器 bridge IP 作为 seed/announce 地址；经 `SutStack.AgentBuilder.property("openjiuwen.service.middleware.redis.default.type", "cluster")` + `redis.default.nodes[i]=<bridgeIp>:<port>` 注入两 agent。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `redisClusterMiddlewareActivatesOnBoot`（hermetic） | grokzen cluster 就绪；两 agent 以 redis profile + cluster nodes 启动 | 读启动日志 + 对 bridge IP:7000 发 DBSIZE 探针 | 两 agent 均含 profile 激活行、`endpoint-type=cluster`、`JedisClusterRuntimeRedisClient`；bridge-IP DBSIZE 可达 |
| 继承 expense-review 8 参数化场景 | 同上 cluster 栈 | 场景 1 / 场景 2 各四协议 | 业务语义与单机等价；状态落盘 |

**关键实现细节**：
- cluster 可达性 = 容器 bridge IP 直连（Linux 宿主对 docker 桥接子网可路由）；grokzen 无 `cluster-announce-ip`，节点公告 bridge IP，JedisCluster seed bootstrap 后跟随公告地址重定向。
- 不设密码：密码脱敏已由 standalone 变体覆盖；cluster 变体聚焦拓扑。

**依赖的外部组件 / fixture**：openJiuwen profile；Docker；`grokzen/redis-cluster:6.2.14`。

**对应实现位置**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisClusterAcceptanceTest.java`

**框架落点**：`BaseManagedStackTest`；方法级自管 `GenericContainer`；`SutStack.AgentBuilder.property(...)`；`RedisProbe`；`ManagedSutInstance.logFile()`。

**执行命令**：

```bash
mvn test -Dtest=ExpenseReviewRedisClusterAcceptanceTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

**状态**：✅ 已实现（cluster 叶子当前未 `@Disabled`，默认 `mvn test` 会因 `TestEnvironment` 回退到 OPENJIUWEN 而真跑；首跑前建议确认环境门控）。

### 5.3 已实现：PlanAgentDirectStreamingRedisTest（远端工作流场景）

**FSD 需求条目**：§2 单机接入 MUST、策略日志 MUST、Task/状态复用 SHOULD。

**拓扑**：`edpa-adapter` + `edpa-plan-agent`（envexplorer 由 adapter 的 service-bindings 自动拉起）；两 agent 加载 `redis` profile（edpa jar 自带 `application-redis.yml`，经 `serviceBinding("redis", ...)` 把动态 Testcontainers Redis 的 `{{host}}/{{port}}` 注入为 `--REDIS_HOST/--REDIS_PORT`）。Redis 状态分布两端：adapter 侧 A2A TaskStore（无状态桥，无 checkpointer）、plan-agent 侧 checkpointer。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `redisMiddlewareActivatesOnBoot`（hermetic） | 两 agent 均在 redis profile 下启动 | 读启动日志 + Redis 探测 | plan-agent 含 `Begin to initializing checkpointer with type: redis`；adapter 含 `Runtime Redis datasource selected:` + `RuntimeRedisClient=`；两 agent 均含 profile 激活行；`DBSIZE` 可达 |
| `balanceThenTransfersDirectRedis(protocol)`（A2A_STREAM / REST_QUERY，已实跑通过） | 同上栈，直连 plan-agent | 「先查下余额，再给李四和王五各转50元」stepUi 自推进（5 个 manual select） | 语义/不泄露断言（含 8200、李四、王五）；键空间门禁 `DBSIZE>0`、`*agent_state_blobs`/`*workflow_state_blobs` 非空、`a2a:task:*` 非空；转账完成态标记命中其一 |

**对应实现位置（已存在）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack.Builder.serviceBinding(...)` / `.profile(...)`；`ConversationInteractionAdapter`；`InteractionFlow`。

### 5.4 待新建：ExpenseReviewRedisStateCacheTest（本地工作流 standalone 状态语义，P1）

**FSD 需求条目**：story 1；§2 SHOULD TTL、§4.1 恢复边界、§5.2 不降级不承诺。

**覆盖业务链路**：客户端 →（A2A_STREAM 为主，可扩展四协议）→ `expense-review-main` → `expense-review-workflow`（8 节点 DAG）。

**用例设计思路**：在 `ExpenseReviewRedisAcceptanceTest` 已覆盖的"认证+业务串通"基础上，补充独立的**状态缓存语义**方法：TTL 过期、Redis 不可达时的可诊断失败、并发任务隔离。建议继承 `AbstractExpenseReviewAcceptanceTest` 以复用业务模板，方法级通过 override `buildStack` 或额外配置实现特殊注入。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `taskSnapshotExpiresWithConfiguredTtl`（⬜ 待新建） | 同上栈，追加注入 `openjiuwen.service.middleware.checkpointer.ttl-seconds=<短值>` | 完成一笔合规报销 → 立即 getTask 可读 → 轮询超过 TTL | 立即读取内容/状态一致；过期后 getTask 为 not-found/空语义；TTL 在配置窗口内 |
| `redisUnavailableAtResumeFailsDiagnosably`（⬜ 待新建） | 同上栈；轮 1 已到 INPUT_REQUIRED | 停掉栈内 Testcontainers redis 容器 → 续 approved | 任务收敛到 FAILED/可诊断错误（有界，不无限挂起）；不得静默 COMPLETED；错误日志可定位 Redis 连接问题且脱敏 |
| `concurrentExpenseTasksStateIsolated`（⬜ 待新建） | 同上栈 | 并行提交 A 合规、B 超标（B 后续接 approved） | 各自终态与结果语义正确；Redis 键按各自 taskId 区分，互不覆盖；B 恢复不串入 A 内容 |

**实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisStateCacheTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack`（serviceBinding 注入、stop/start）；`InteractionFlow`（续轮携 taskId+contextId）；`RedisProbe`（弱形态键空间断言）；`ManagedSutInstance.logFile()`。

**状态**：⬜ 待新建。

### 5.5 待新建：ExpenseReviewRedisConfigTest（本地工作流配置变体，P1）

**FSD 需求条目**：§5.1.1 缺省单机兼容；§3 命名连接引用。

**用例设计思路**：配置变体需要方法级不同栈配置，建议**不**继承 `BaseManagedStackTest`（固定类级栈），采用方法级自管 `SutStack`（try-with-resources，同 react_travel Feat003 类风格）。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `legacyConfigWithoutTypeDefaultsToStandalone`（⬜ 待新建，hermetic） | 全新 Redis；只注入 host/port（不配 type/ref） | 启动 agent 并就绪 | 启动成功；诊断行显示 `endpoint-type=standalone`、`redis-ref=default`；Redis 读写可达 |
| `namedReferenceRoutesWorkflowStateToConfiguredDatabase`（⬜ 待新建） | 同一 Redis：`redis.default.*`→DB0、`redis.secondary.*`→DB2；`checkpointer.redis-ref=secondary` | 跑一笔超标报销至 INPUT_REQUIRED | DB2 出现该 taskId 相关状态键，DB0 无新增；诊断行 `redis-ref=secondary` |

**实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisConfigTest.java`

**框架落点**：方法级 `SutStack` + `BackingServices`/`TestContainerFactory`；`RedisProbe` 分库探测。

**状态**：⬜ 待新建。

### 5.6 待新建：ExpenseReviewRedisClusterStateCacheTest（本地工作流 cluster 配置语义，P1）

**FSD 需求条目**：§2 集群接入 MUST、配置切换 MUST；§3/§5.1.1 database ignored。

**用例设计思路**：在 `ExpenseReviewRedisClusterAcceptanceTest` 已覆盖的"cluster 拓扑业务串通"基础上，补充配置切换与诊断语义。复用 `grokzen/redis-cluster` fixture 与 bridge-IP 注入模式。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `standaloneToClusterSwitchWithoutBusinessChange`（⬜ 待新建） | 同一 agent jar；阶段一 Testcontainers 单机，阶段二仅改 endpoint type+nodes 指向 Testcontainers cluster | 阶段一跑一笔业务 → 重启切 cluster → 阶段二再跑一笔 | 两阶段业务均成功；策略日志随配置切换（standalone→cluster）；测试/业务代码不变；不要求迁移旧数据 |
| `clusterIgnoresStandaloneDatabaseWithDiagnostic`（⬜ 待新建，hermetic） | Testcontainers cluster + `database=3` | 启动 agent 并就绪 | 不因 database 启动失败；增量日志精确含 `databaseIgnored=3`；Redis 读写可达 |

**实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisClusterStateCacheTest.java`

**框架落点**：方法级 `SutStack` + 类内 `grokzen/redis-cluster` fixture；cluster-aware `RedisProbe`。

**状态**：⬜ 待新建。

### 5.7 待新建：ExpenseReviewRedisCustomAdapterTest（模拟客户封装 Redis 组件，P1）

**FSD 需求条目**：§2"客户封装 Redis 适配扩展点"MUST；§4.1"接入客户封装 Redis 组件"场景；§5.1.2"客户组件与原生命令差异由适配实现语义对齐或显式报错"；§5.1.1"配置指定的适配实现无法装配时明确失败，不静默使用与配置不一致的数据源"。

**用例设计思路**：客户自封装组件不可外传，但 feature 要求产品保证 SPI 扩展点具备接入能力——SIT 以**模拟客户组件**覆盖：测试侧实现一个 `RuntimeRedisClient` SPI 适配（包装真实 Jedis 客户端、自带诊断标识与可配置故障开关），构建为独立 jar 并经类路径追加注入 agent 进程，模拟客户交付形态。故障开关由模拟组件的系统属性控制，属业务合理可达的注入点。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `customAdapterServesWorkflowStateCache`（⬜ 待新建） | 模拟客户适配 jar 已上 agent 类路径；Testcontainers Redis 就绪 | 启动 agent（诊断应选中模拟组件）→ 跑超标报销至 INPUT_REQUIRED 后续 approved 至 COMPLETED | 业务全流程成功；诊断日志显示模拟客户实现标识（默认原生实现 back-off）；模拟组件调用计数证明 Redis 操作经其承接；状态键正常落盘 |
| `customAdapterCommandMismatchFailsExplicitly`（⬜ 待新建） | 同上；模拟组件开启"拒答指定命令"开关 | 触发涉及该命令的业务操作 | 适配层显式报错，错误语义可诊断；不静默返回错误结果；日志脱敏 |
| `customAdapterFailureFailsWithoutSilentFallback`（⬜ 待新建） | 模拟组件指向不可达后端（或装配为必失败实现） | 启动 agent 并发起真实业务操作 | 启动或首次真实操作明确失败；**不**静默回退默认原生实现；日志不含敏感值 |

**实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisCustomAdapterTest.java`（模拟适配组件源码置于 `src/test/java` 侧或独立 test-support 模块，打包后由栈注入类路径）

**框架落点**：方法级自管 `SutStack`；agent 进程类路径追加机制（`loader.path` 或 `-cp`，落地时确认 `SutStack`/`ProcessLauncher` 支持，不支持则以 test-support 模块预打包 agent 变体兜底）；模拟组件内置调用计数与故障开关。

**状态**：⬜ 待新建。

## 6. 备注与风险

### 6.1 与其他档的追溯（仅标注，不作验收依据）

| 关注点 | 归属 | 说明 |
|---|---|---|
| 四协议入口串通 / AgentCard / getTask 一致性 / 尾斜杠 | FEAT-001 档 | 入口层语义，与存储介质无关；本档 redis 链路复用既有入口 |
| Versatile 控制器故障语义 / 协作式取消 | FEAT-002 档 | 控制器生命周期语义；若后续需 Versatile 侧挂起/恢复边界的 Redis 语义，并入该档统一设计 |
| runtime 级 Redis SPI 合同、配置错误诊断、cluster 切换合同 | contract / component 层（react_travel 先例） | 无 workflow 业务面，见 §2.3 处置；react_travel `Feat003RedisStandaloneBehaviorTest` / `Feat003RedisConfigurationAndDiagnosticsTest` / `Feat003RedisClusterAndSwitchTest` 已提供同型实现参考 |
| DeepAgent Todolist | deepagent 侧设计 | 与 WorkflowAgent 无关 |

### 6.2 风险

1. **激活证据的 agent 差异**：`Runtime Redis datasource selected:` 诊断行是稳定证据；`Begin to initializing checkpointer with type: redis` 是否打印取决于 agent 是否装配 Runner（远端 plan-agent 有、adapter 无；本地 `expense-review-main` 有、`expense-review-workflow` 可能无）。实现门禁时应以诊断行为主，checkpointer 行仅作可选增强。
2. **键空间断言稳定性**：沿用弱形态（存在性 + taskId 特征）；实现若调整 key 命名，放宽为"存在与该 taskId 相关的新增键"，不硬编码前缀。
3. **模拟客户组件的注入机制**：依赖 agent 进程类路径可追加（`loader.path`/`-cp`）；落地时先验证 `SutStack`/`ProcessLauncher` 支持，不支持则以 test-support 模块预打包"agent + 模拟适配"变体兜底。模拟组件本身不改变被测 runtime 代码，仅作为 SPI 装配候选。
4. **Testcontainers cluster 的 announce/MOVED**：`ExpenseReviewRedisClusterAcceptanceTest` 已验证 grokzen bridge-IP 模式在 Linux 可行；新环境首次落地先做 fixture smoke，再跑业务用例。
5. **TTL 用例的等待尺度**：短 TTL（如 12s）+ 轮询，不等待默认 7 天；过期判定用 getTask not-found 语义而非固定 key 消失。
6. **"不重复执行前置节点"断言尺度**：以"重启后快照可读 + 续轮 COMPLETED + 结果正确"为硬断言，节点不重复执行作软观察记录，避免脆弱断言。
7. **默认 `mvn test` 会真跑 FEAT-003 用例**：`TestEnvironment.current()` 回退到 OPENJIUWEN，`@Tag("integration")` 不在 surefire 排除组内；`ExpenseReviewRedisAcceptanceTest` / `ExpenseReviewRedisClusterAcceptanceTest` 当前无 `@Disabled`，首跑前须确认环境门控或按需补 `@Disabled`。
8. **文档中旧框架名残留风险**：本档 §5 已统一使用 `SutStack` / `InteractionFlow` / `RedisProbe` / `ConversationInteractionAdapter` 等当前框架 API；后续维护若复制粘贴旧 FEAT 文档，需避免 `A2aClientCalls` / `RestClientCalls` / `SitClientRecorder` 等已废弃或不存在名称回流。
