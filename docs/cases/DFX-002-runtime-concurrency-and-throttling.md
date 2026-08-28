---
scope: v0730
deployable_units: [agent-runtime-ext-java, agent-runtime-java, edp-agent-java]
sut: concurrency-agent-runtime（单服务 SUT，端口 18210；验收 example concurrency-throttling-acceptance-demo，fork zhangdengjiecai/agent-solution）
features: [DFX-002]
updated: 2026-08-27
---

# 运行时任务级并发与限流（DFX-002）—— 测试计划

## 1. 测试目标

验证 agent-runtime 的任务级并发承载控制能力：任务级并发数配置（`max-concurrent-tasks`，默认 -1）、任务级准入/限流（超限返回 HTTP 503）、额度生命周期管理（占用/释放）、活跃任务查询、任务执行隔离，以及并发场景下共享可变数据的线程安全。

验收视角覆盖验收测试 + 线程安全单元测试 + 并发压测三层：验收测试验证准入/限流/额度/查询的对外契约行为，单元测试验证并发共享可变数据的线程安全，压测验证高并发下的额度不泄漏与快照一致性。对应 example 的 F（功能）/ T（线程安全）/ P（压测）三个用例族。

## 2. 范围与非范围

范围（需求文档 §2.1 能力清单 MUST 项）：

- 任务级并发数配置：`openjiuwen.service.concurrency.max-concurrent-tasks`，缺省 -1（不限制）。
- 任务级资源管理：任务开始占用额度，进入终态（COMPLETED/FAILED/CANCELED/REJECTED）释放额度；进入 INPUT_REQUIRED（等用户输入或等远端 SubAgent 返回）释放额度，续传/结果回传重新申请额度（额度已满则拒绝）。
- 任务执行隔离：并行任务之间执行隔离，一个任务的异常/超时/资源异常不得影响其他任务；每个任务独立执行上下文，不跨任务泄露状态。
- 正常服务：并发低于上限（或配置 -1）时任务正常流转、SSE 正常推送、负载日志正常记录。
- 任务级超限拒绝：并发超过上限时返回 HTTP 503 并记录日志。
- 任务生命周期记录：收到请求时记录负载变化到日志。
- 当前活跃任务查询：`GET /v1/current_active_tasks` 返回活跃任务列表与并发负载快照。

非范围（需求文档 §2.1 OUT 项 + 设计文档 §1.1「不包含」）：

- 请求排队等待（超限直接拒绝；排队属消息队列中间件职责）。
- 基于权重的并发控制。
- 自适应限流（全局限流/熔断属网关职责）。
- 精确资源用量计量（以任务数为单位，CPU/内存归容器平台监控）。
- 请求级并发控制（Tomcat 线程池管理，非应用层参数）。
- Sub-agent 并发计数（当前 edp-agent 不使用 sub-agent，后续启用再回特性文档定义）。

结构性排除与覆盖差距（相对 LLD §7.3 错误表面 / §7.4 最小测试套件的定位）：

- LLD §7.3 中以下错误场景需 `agent-runtime-java` 生产模块具备故障注入能力（进程内抛异常、执行超时取消、`ActiveStreamRegistry` 连接中断、JVM 进程终止）方可确定性触发，本 demo SUT 结构性无法覆盖，由上游生产模块单测/集成测试承接，不在本 demo 23 条验收范围内：
  - Agent 实例创建失败（释放已占额度 + HTTP 500 + ERROR 日志）。
  - Agent 执行超时（生命周期取消 → `CANCELED`）。
  - 连接中断（`ActiveStreamRegistry` 检测取消 → Task 取消 → 额度释放）。
  - JVM 异常终止（重启后额度状态从 0 开始）。
- 额度泄漏安全网：LLD §7.3 标注「预留，当前版本未实现」，不设用例。
- 续传额度满拒绝（503）：已补 F-23（`ConcurrencyAcceptanceTest.f23_resumeRejectedWhenQuotaFull` + §5.1 场景行，对应需求 §6 第 4 条「续传额度已满拒绝」）。
- LLD §7.4 建议 16 个最小测试类，本 demo 仅落地 4 类（`ConcurrencyAcceptanceTest` + `ExtConcurrencyPropertiesTest` / `ExtTaskAdmissionControlTest` / `ExtTaskQuotaTrackerTest`，对应 ext 层复刻）；其余 `AgentInstanceManagerTest` / `ConcurrencyAutoConfigurationTest` / `ActiveTaskControllerTest` / `A2aJsonRpcControllerAdmissionTest` / `CustomRestA2ABridgeAdmissionTest` / `EdpAgentFactoryConcurrencyTest` 及各 `*IntegrationTest` / `*E2EIntegrationTest` / `JiuwenCoreAgentExtHandler*` 类落在 `agent-runtime-java` / `agent-runtime-ext-java` / `edp-agent-java` 生产模块，由上游模块验收承接，不属本 demo 验收范围。
- 需求↔LLD 断层（待收敛）——活跃任务查询字段 `currentInputRequiredTasks`：需求 §4.1.2/§5.1.4 要求查询接口「至少包含」任务级配置上限、`currentActiveTasks`、`currentInputRequiredTasks`（`INPUT_REQUIRED` 等待中任务数）与非终态任务列表；LLD §2.3 出参仅 `maxConcurrentTasks`/`currentActiveTasks`/`tasks`，全文未设计该字段，故 F-40 仅断言三字段。须先由需求/LLD 收敛（补 LLD 出参并实现，或将需求该字段降级为下版本）后再补断言。
- 需求↔LLD 断层（待收敛）——SubAgent 额度链路：需求 §5.1.5 + §6 第 4 条要求覆盖「主 Agent 等待远端子 Agent 返回」的额度释放→续传恢复链路；LLD §1.4 仅将「Sub-agent 并发计数」列为「暂不处理」，未明确该额度链路是否本期交付，故无对应场景。须先由需求/LLD 明确 SubAgent 额度链路是否本期交付，再决定补场景或改需求。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/DFX-002-runtime-concurrency-and-throttling.md` | 能力清单 §2.1、请求/任务/SubAgent 定义 §1、并发前提假设、场景 §4——断言契约的唯一来源。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-DFX-002-runtime-concurrency-and-throttling.md` | 特性范围 §1.1、核心设计原则 §1.2、能力对齐矩阵 §1.3、实现仓（agent-runtime-ext-java 主要 / agent-runtime-java 配合 / edp-agent-java SPI）。 |
| 验收 example `concurrency-throttling-acceptance-demo/README.md` | 拓扑 §1、确定性工具 §3、用例映射 §4、依赖版本 §6、激活方式 §7、源码签名 §8。 |
| example `acceptance-tests/`、`concurrency-agent-runtime/src/test` 源码 + `run-acceptance.sh` | fixture 类、测试类与分组（smoke/pending）、运行命令。 |

## 4. 部署拓扑

```text
acceptance test driver (JDK HttpClient + ExecutorService 并发 worker)
  -> concurrency-agent-runtime (:18210)
       DeepAgent（确定性工具集 + mock LLM）
       任务级准入控制 TaskAdmissionGate（max-concurrent-tasks）
       per-Task Agent 实例管理（AgentFactory SPI + AgentInstanceManager）
       活跃任务查询 GET /v1/current_active_tasks
```

边界要求：

- test driver 只通过 SUT 公开面（`/a2a` JSON-RPC、`/health`、`/v1/current_active_tasks`）观察系统，不读 SUT 内部状态、日志或存储。
- SUT 的 Agent 工具全部确定性（sleep / echo / 确认中断 / 抛异常），LLM 用 mock 模式（固定延迟 + 按输入 token 确定性路由到工具），隔离真实 LLM 波动，保证并发断言可重复。

## 5. 测试场景矩阵

场景矩阵锚定验收 example `concurrency-throttling-acceptance-demo` 当前已落地的 23 条测试——`ConcurrencyAcceptanceTest` 17 条 + `Ext*` 三个类 6 条，编号以测试代码 `@DisplayName` / 类头注释为准（如 `smoke-1` ↔ `ConcurrencyAcceptanceTest.smoke_echoReturnsCompleted`、`F-10` ↔ `f10_admissionRejectsOverQuota`，§5.2 单测行 Fixture 列已标注具体方法）。

### 5.1 功能验收场景（smoke 基线 + F 系列 · `ConcurrencyAcceptanceTest`）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| smoke-1 | echo 工具基础链路 | SUT 就绪（`concurrency-agent-runtime` :18210，fat jar 已 `mvn package`，`application-concurrency_local.yml` 填真实 LLM 凭据） | `SendMessage`「echo:hello-world」 | 终态 `COMPLETED`，结果含 `hello-world` | `SutStack` + `A2aServiceClient` |
| smoke-2 | slow_task 工具基础链路 | 同上 | `SendMessage`「slow:100」 | 终态 `COMPLETED` | 同上 |
| smoke-3 | confirm 中断续传链路 | 同上 | 「confirm:transfer-100」触发 `INPUT_REQUIRED`，再以 taskId 续传「确认」 | 首段 `INPUT_REQUIRED`（taskId 非空），续传后 `COMPLETED` | 同上 |
| smoke-4 | fail 失败传播链路 | 同上 | 「fail:boom」 | 终态 `FAILED` | 同上 |
| F-10 | 并发达上限后新请求被拒 | `max-concurrent-tasks=2` | `ConcurrentDriver` 并发 3 个 `slow:3000` | 2 个 HTTP 200 + 1 个 HTTP 503 | `ConcurrentDriver` + `A2aServiceClient` |
| F-11 | max-concurrent-tasks=-1 不限额 | `max-concurrent-tasks=-1` | 并发 5 个 `slow:200` | 全部 HTTP 200 | 同上 |
| F-20 | 任务完成释放额度 | `max-concurrent-tasks=1` | 先发 1 个 `slow:100` 等其 `COMPLETED`，再发 1 个 `slow:100` | 首任务 `COMPLETED`，第二个请求 200 | `A2aServiceClient` |
| F-21 | INPUT_REQUIRED 驻留不占额度 | `max-concurrent-tasks=1` | 「confirm:op」触发 `INPUT_REQUIRED` 后并发发「echo:x」 | 首任务 `INPUT_REQUIRED`，并发请求 200 | `A2aServiceClient` |
| F-22 | 任务失败释放额度 | `max-concurrent-tasks=1` | 「fail:x」触发 `FAILED` 后发「echo:x」 | 首任务 `FAILED`，随后请求 200 | `A2aServiceClient` |
| F-23 | INPUT_REQUIRED 续传时额度已满被拒 | `max-concurrent-tasks=1` | 「confirm:op」触发 `INPUT_REQUIRED`，后台发 `slow:3000` 占满唯一额度后以 taskId 续传 A | 续传请求 HTTP 503 被拒（需求 §6 第 4 条「续传额度已满拒绝」） | `A2aServiceClient` + 后台 blocker |
| F-25 | 顺序多轮额度归零无泄漏 | `max-concurrent-tasks=2` | 顺序发 10 个 `echo:round-i` | 全部 `COMPLETED`，结束时快照 `currentActiveTasks==0` 且 `tasks` 空 | `A2aServiceClient` |
| F-40 | 活跃任务快照结构完整 | SUT 就绪 | `GET /v1/current_active_tasks` 读快照 | 含 `tasks` / `currentActiveTasks` / `maxConcurrentTasks` 三字段 | `A2aServiceClient` |
| F-42 | 无活跃任务时快照为空 | SUT 空闲 | 读快照 | `currentActiveTasks==0`、`tasks` 空 | `A2aServiceClient` |
| F-50 | 不同 conversation_id 任务隔离 | `max-concurrent-tasks>=2` | 并发 2 个 `slow:200` + 2 个 `echo:isolated-i` | 全部 `COMPLETED`，各任务独立实例不串扰（per-Task） | `ConcurrentDriver` + `A2aServiceClient` |

### 5.2 线程安全与一致性场景（T/D/P）

`ConcurrencyAcceptanceTest` 三条与 `Ext*` 三个类六条合并如下；后六条编号沿用迁自上游 `agent-service-adapters-agentcore-ext` 的类头注释（`F-03` / `F-41` / `F-42` / `P-04`）。

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| T-01 | 并发 Agent 创建线程安全 | per-Task 模式（`AgentFactory` 已实现） | 并发 8 个 `echo:agent-i` | 全部 `COMPLETED`，无异常、无内部 HashMap 损坏 | `ConcurrentDriver` + `A2aServiceClient` |
| D-2 | 快照一致性 | `max-concurrent-tasks=2` | 并发 3 个 `slow:3000` 期间读快照 | 快照 `currentActiveTasks == tasks.size()` | `ConcurrentDriver` + `A2aServiceClient` |
| P-01 | 固定并发限流压测 | `max-concurrent-tasks=2` | 并发 10 个 `slow:3000` | 503 数 == 提交数 - max == 8 | `ConcurrentDriver` + `A2aServiceClient` |
| F-03a | `TaskAdmissionControl` max=0 拒绝一切 | 无（纯单测构造） | `new TaskAdmissionControl(0).tryAcquire()` | 恒 `false`，`currentCount()==0` | `ExtTaskAdmissionControlTest.tryAcquire_alwaysFalse_whenZero` |
| F-03b | `TaskAdmissionControl` 负数非 -1 视为不限 | 无 | `new TaskAdmissionControl(-2)` 连续 `tryAcquire()` 100 次 | 恒 `true`，无 503 漏判 | `ExtTaskAdmissionControlTest.tryAcquire_alwaysTrue_whenNegativeOtherThanMinusOne` |
| F-03c | `ConcurrencyProperties` 绑定无校验 | 无 | `setMaxConcurrentTasks(0)` / `setMaxConcurrentTasks(-2)` | getter 原样返回 0 / -2，不抛异常（POJO） | `ExtConcurrencyPropertiesTest.bindsZeroAndNegativeValuesWithoutValidation` |
| F-41 | 释放任务不出现在活跃列表 | 无 | `onAdmitted` 两个（working/released）后 `onReleased` released | 快照仅含 working，`currentActiveTasks==1` | `ExtTaskQuotaTrackerTest.snapshot_excludesReleasedTasks` |
| F-42 | max=-1 仍跟踪活跃任务 | 无 | `max=-1` 下 `onAdmitted` 3 个 | 快照 `max==-1`、`currentActiveTasks==0`（unlimited 不占额度）、`tasks.size()==3`（任务元数据仍追踪） | `ExtTaskQuotaTrackerTest.snapshot_unlimitedGate_stillTracksActiveTasks` |
| P-04 | 并发 admit/release 快照一致性 | 无 | 100 任务并发 admit+release，8 读线程各 2000 次快照 | 过程中 `currentActiveTasks` 恒落在 [0, max]，最终 `currentCount==0` 且任务表收敛为空（#155/#158 后不再断言 `currentActiveTasks == tasks.size()`） | `ExtTaskQuotaTrackerTest.snapshot_concurrentAdmitRelease_isInternallyConsistent` |

## 6. Test Agent 与 Fixture

本 demo 不使用真实 LLM：SUT 的 DeepAgent 装配 4 个确定性工具，每个工具对应一个明确触发 token，mock LLM 按输入 token 确定性选择工具（`temperature=0`）。

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `echo` | 确定性工具 | 触发 token `echo:`，立即返回原文；验证正常任务与额度释放（F-20/F-25）。 |
| `slow_task` | 确定性工具 | 触发 token `slow:`，sleep 可配置时长（`demo.throttle.slow-ms`，默认 3000）后返回；验证占用额度、超限 503（F-10/F-11）与并发限流（P 系列）。 |
| `confirm_action` | 确定性工具 | 触发 token `confirm:`，触发确认型中断等待用户输入；验证 INPUT_REQUIRED 中断续传（smoke-3）+ 驻留释放额度（F-21）。 |
| `fail_task` | 确定性工具 | 触发 token `fail:`，抛 RuntimeException；验证任务失败后额度释放（F-22）与失败传播。 |
| `ConcurrencyDemoAgentFactory`（`AgentFactory` SPI） | Agent 工厂 | per-Task 创建 DeepAgent（`create()` 用 ReentrantLock 串行化 + `ensureInitialized`）。 |
| `SutStack` | Fixture | 本地拉起/停止 SUT jar，提供受控故障注入。 |
| `A2aServiceClient` | Fixture | A2A 驱动 blocking/streaming/查询。 |
| `ConcurrentDriver` | Fixture | 并发 worker，驱动多路并发请求。 |
| `DriveResult` | Fixture | 单路并发请求的结果收集。 |
| `ExtConcurrencyPropertiesTest` / `ExtTaskAdmissionControlTest` / `ExtTaskQuotaTrackerTest` | 单元测试 | `concurrency-agent-runtime/src/test` 下三个单元测试类（配置 / 准入计数 / 额度跟踪），承载 §5.2 的断言（F-03 / F-41 / F-42 / P-04，编号沿用上游类头注释）。 |

## 7. 关键链路断言

- 任务级并发达到 `max-concurrent-tasks` 后，超限请求必须返回 HTTP 503 + error code -32603（需求 §2.1 MUST「任务级超限拒绝」；README §7；F-10）。
- `max-concurrent-tasks=-1` 表示不限制，超额并发必须全部通过（需求 §3.1；F-11）。
- 任务进入终态或 INPUT_REQUIRED 时必须释放额度，续传重新申请额度（需求 §2.1 MUST「任务级资源管理」；F-20/F-21/F-22/F-25）。
- 并行任务必须执行隔离：一个任务的异常/超时/资源异常不得影响其他并行任务，每个任务独立执行上下文（需求 §2.1 MUST「任务执行隔离」；F-50）。
- 活跃任务查询必须返回 `{maxConcurrentTasks, currentActiveTasks, tasks:[{taskId,conversationId,status,startedAt}]}`（需求 §2.1 MUST「活跃任务查询」；README §8 签名 8；F-40/F-42，快照边界由 F-41/F-42/P-04 覆盖）。
- Agent 层并发前提：Handler 必须可重入，任务间不得共享可变状态，不得用 static/全局可变变量在任务间传递数据（需求 §1「并发前提假设（Agent 层）」；T-01）。
- 准入拒绝必须体现在 HTTP 层而非静默吞掉（需求 §2.1「任务级超限拒绝」+「任务生命周期记录」；由 T/D/F 用例看守）。

## 8. 执行策略

分组以测试代码实际 `@Tag` 为准：`smoke`（确定性工具基础链路，4 条）+ `pending`（准入/限流 F/T/D/P 系列，DFX-002 合入后启用）。`run-acceptance.sh` 头注释的「feat-dfx002」为计划分组名，代码中尚未落地，实际 tag 为 `pending`。

- Smoke：smoke-1、smoke-2、smoke-3、smoke-4（echo / slow / confirm 续传 / fail 传播）。
- Full suite：smoke-1~smoke-4、F-10、F-11、F-20、F-21、F-22、F-23、F-25、F-40、F-42、F-50、T-01、D-2、P-01（`ConcurrencyAcceptanceTest` 17 条）+ F-03a~F-03c、F-41、F-42、P-04（`Ext*` 6 条）。
- P0 必须全绿：smoke-1~smoke-4、F-10、F-20、F-21、F-22、F-23、F-25、F-40、F-50、D-2、T-01。

```bash
# 用法：bash run-acceptance.sh [groups] [excludedGroups]
#   groups 默认 smoke（基础链路），excludedGroups 默认 pending
bash run-acceptance.sh smoke pending
```

脚本内部执行：先 `mvn -pl concurrency-agent-runtime -am package -DskipTests` 打包，再 `mvn -pl acceptance-tests -am test -Dgroups=... -DexcludedGroups=...` 跑验收。

DFX-002 并发控制实现的发布坐标沿用旧版本号（agent-core-java 0.1.14.post1 / agent-service-app 0.1.1.post1 / agent-service-adapters-agentcore-ext 0.1.0），同坐标下 Maven 不会自动拾取新实现，运行验收前需源码 install 一次落地新 jar（README §6）：

```bash
# 1) base（agent-service-spec 的 concurrency 包 + A2aJsonRpcController 准入 + ActiveTaskController）
cd agent-runtime-java && mvn install -DskipTests
# 2) ext（TaskAdmissionControl / AgentInstanceManager / ConcurrencyAutoConfiguration）
cd agent-solution/common/agent-runtime-ext-java \
  && mvn install -pl agent-service-adapters/agent-service-adapters-agentcore-ext -am -DskipTests
```

## 附录 A. 相对设计基线的差异

| 变化 | 对用例的影响 |
|---|---|
| 并发控制实现已落地于 agent-runtime-ext-java（`TaskAdmissionControl` / `AgentInstanceManager` / `ConcurrencyAutoConfiguration`）与 agent-runtime-java（`A2aJsonRpcController` 准入、`ActiveTaskController`） | 用例从设计态（设计 §1.3 能力对齐矩阵标注「未实现」）转为按落地实现签名设计；F/T/P 用例断言以 README §8 源码签名为依据。 |
| 并发创建 Agent 的线程安全策略：V1 用 `EdpAgentFactory` ReentrantLock 串行化创建，不改 agent-core-java；V2 拆分全局注册与 per-agent 初始化并行创建 | T-01 断言以 V1 串行化创建为基线，`create()` 串行 + `ensureInitialized`；GlobalResourceMgr 的 HashMap 写路径并发安全未单列用例（由 T-01 并发创建观察无损坏间接覆盖）。 |