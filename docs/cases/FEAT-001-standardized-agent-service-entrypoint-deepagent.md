---
feature_id: FEAT-001
feature_title: 标准化智能体服务入口
sut: deep-research-agent（openjiuwen 变体，SIT 上以 remote url-only 声明）
scope: 本档只覆盖 deep-research SUT 侧可外部黑盒断言的 FEAT-001 事实要求；agent-bus forwarding / gRPC / 普通-client webhook 均按特性档 §5.2 明示 OUT，不列入
status: designed
owner: TBD
tags: [integration, deepagent, feat-001]
depends_on:
  - deep-research 已启动并监听 http://7.209.189.82:18090
  - deep-research 启动时按 SIT env 就绪 (含 SANDBOX_ENABLED / redis-checkpointer / long-term-memory 等按子用例前置声明)
  - 部分子用例需算子在跑前手工重启 deep-research（与 DA-06 / DA-07 同源 bug 触发条件）
related_docs:
  - FEAT-001 特性文档（version-scope，外部契约）：`chaosxingxc-orion/spring-ai-ascend@experimental` → `version-scope/FEAT-001-standardized-agent-service-entrypoint.md`（2026-07-15 版本；已抽象化错误码值、把 gRPC / generic-client webhook / mid-state webhook / HITL webhook / agent-bus 私有入口 / outbound / 认证等明示 OUT）
  - FEAT-001 L2 设计文档（当前实现事实）：`chaosxingxc-orion/spring-ai-ascend@experimental` → `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-001-standardized-agent-service-entrypoint.md`（2026-07-09 版本；⬜ Push Notification Config CRUD JSON-RPC 分发 / runtime-to-runtime webhook 实际推送 / gRPC 均未激活；✅ SendMessage / SendStreamingMessage / GetTask / Agent Card 发现 / JSON-RPC 错误面 / SSE 已激活）
  - FEAT-001 评审与待澄清清单：[FEAT-001-standardized-agent-service-entrypoint-review.md](FEAT-001-standardized-agent-service-entrypoint-review.md)
  - 旧档：[deepagent/DA-01-agent-card-discovery.md](deepagent/DA-01-agent-card-discovery.md) ~ [DA-07-sandbox-tools.md](deepagent/DA-07-sandbox-tools.md)（增量沉淀之前 smoke，本档为 FEAT-001 覆盖全景视角，不废弃）
---

# FEAT-001 — deep-research 侧标准化 Agent 服务入口用例设计

> **一句话**：以 deep-research SUT 为对象，把 FEAT-001 §2 能力表里所有 MUST 项、§4 用户旅程和 §5.1.8 错误场景，映射为可在 SIT 侧黑盒断言的子用例；旧 DA-01~07 已覆盖的部分在本档表里显式标记，剩余部分是本档新增落点。

> **⚠️ 本文档已同步评审结论**（2026-07-09）：每条子用例带**状态**（runnable / partial / blocked / deferred）与**评审关联**列，映射到 [评审文档](FEAT-001-standardized-agent-service-entrypoint-review.md) 的 7 项待澄清项。blocked / deferred 项在特性文档未澄清前，实现阶段跳过。

**状态含义**：
- **runnable**：可直接落地实现，与评审无关
- **partial**：核心路径可测，某些断言维度受评审待澄清项限制（比如只能测负路径、只能间接观察）
- **blocked**：断言依据待评审澄清（比如无 error code 承载、无阈值定义）
- **deferred**：依赖能力在整个栈上缺失（比如 webhook receiver），落地实现等能力补齐

---

## 1. 覆盖矩阵

对应 FEAT-001 §2 能力表和 §4 场景表；每行一条子用例。

| FEAT-001 事实要求 | 本档子用例 ID | 现状 | 状态 | 评审关联 | 备注 |
|---|---|---|---|---|---|
| Agent Card 双入口发现 | `FEAT-001.agent-card` | DA-01 部分覆盖 | runnable | — | 补两入口等价对比 |
| Agent Card 公开 base URL 解析 | `FEAT-001.agent-card-public-base-url` | 未覆盖 | partial | — | SUT 侧配置可见性影响判定 |
| Agent Card capabilities 声明真实性 | `FEAT-001.agent-card-capabilities` | DA-01 部分覆盖 | runnable | 评审 §3 交叉 | pushNotifications 声明真实性依赖 receiver 定义 |
| Agent Card skills 声明真实性 | `FEAT-001.agent-card-skills` | DA-01 部分覆盖 | runnable | — | 新增字段完整性断言 |
| `/a2a` 与 `/a2a/` 尾斜杠等价 | `FEAT-001.jsonrpc-endpoint-slash` | 未覆盖 | runnable | — | 用底层 HTTP client |
| JSON-RPC parse error | `FEAT-001.jsonrpc-parse-error` | 未覆盖 | runnable | — | 通用 `-32700` |
| JSON-RPC invalid request | `FEAT-001.jsonrpc-invalid-request` | 未覆盖 | runnable | — | 通用 `-32600` |
| JSON-RPC method-not-found | `FEAT-001.jsonrpc-method-not-found` | 未覆盖 | runnable | — | 通用 `-32601` |
| JSON-RPC error 保留 request id | `FEAT-001.jsonrpc-id-preserved` | 未覆盖 | runnable | — | 并入上面三条断言 |
| 阻塞 `SendMessage` | `FEAT-001.send-message-blocking` | DA-02 覆盖 | runnable | — | 已覆盖 |
| 流式 `SendStreamingMessage` | `FEAT-001.send-streaming-message` | DA-03 覆盖 | runnable | — | 已覆盖 |
| Stream 中途异常追一帧 error | `FEAT-001.stream-mid-error-frame` | 未覆盖 | partial | 评审 §6 | 触发条件依赖故障注入；code 断言受 §6 影响 |
| `GetTask` 快照 | `FEAT-001.get-task` | DA-04 覆盖 | runnable | — | 已覆盖 |
| `GetTask` 负路径（TaskNotFound） | `FEAT-001.get-task-not-found` | DA-04.F 覆盖 | runnable | — | 已覆盖 |
| `CancelTask` 执行中任务 → CANCELED | `FEAT-001.cancel-task-in-flight` | 未覆盖 | partial | 评审 §5 | 到达 CANCELED 时限未定，用宽松窗口 |
| `CancelTask` 已完成任务的幂等语义 | `FEAT-001.cancel-task-terminal` | 未覆盖 | blocked | 评审 §5 §6 | 期望行为 + error code 均未定 |
| `ListTasks` 分页 / 过滤 | `FEAT-001.list-tasks` | 未覆盖 | runnable | — | 新增 |
| `SubscribeToTask` SSE 断线重连 | `FEAT-001.subscribe-to-task` | 未覆盖 | runnable | — | 依赖 SDK API 存在性验证 |
| Push Notification config CRUD | `FEAT-001.push-config-crud` | 未覆盖 | runnable | — | CRUD 契约本身可测，不触发实际推送 |
| Webhook COMPLETED 文本一次性回调 | `FEAT-001.webhook-completed` | 未覆盖 | **deferred** | 评审 §3 | receiver 全栈缺失 |
| Webhook FAILED 回调 | `FEAT-001.webhook-failed` | 未覆盖 | **deferred** | 评审 §3 | 同上 |
| Webhook CANCELED 回调 | `FEAT-001.webhook-canceled` | 未覆盖 | **deferred** | 评审 §3 | 同上 |
| Webhook REJECTED 回调 | `FEAT-001.webhook-rejected` | 未覆盖 | **deferred** | 评审 §3 | 同上 |
| Webhook 大载荷 `payloadRef` | `FEAT-001.webhook-payload-ref` | 未覆盖 | **blocked** | 评审 §1 §3 | 阈值未定 + receiver 缺失 |
| Webhook notification id 幂等 | `FEAT-001.webhook-idempotent` | 未覆盖 | **blocked** | 评审 §3 §4 | SDK 无 id 字段承载 |
| Webhook 不通知中间态 | `FEAT-001.webhook-no-intermediate` | 未覆盖 | **deferred** | 评审 §3 | 需 receiver 侧观察 |
| Webhook 未受信任 target 拒绝 | `FEAT-001.webhook-untrusted-target` | 未覆盖 | partial | 评审 §2 | 只能测注册拒绝负路径 |
| Webhook 与 streaming 分离 | `FEAT-001.webhook-vs-streaming` | 未覆盖 | **deferred** | 评审 §3 | 需 receiver 侧观察 |
| `X-Tenant-Id` 头传递 | `FEAT-001.tenant-id-propagation` | 未覆盖 | partial | 评审 §7 | 缺 header 落点未定 |
| Tenant 跨租户记忆隔离 | `FEAT-001.tenant-isolation` | 未覆盖 | partial | 评审 §7 | 间接证据（DA-05/06 记忆链路衍生） |
| 空文本输入拒绝 | `FEAT-001.empty-text-input` | 未覆盖 | partial | 评审 §6 | 拒绝语义可测，具体 code 无法断言 |
| Task 生命周期状态序列 | `FEAT-001.task-lifecycle` | DA-03 部分覆盖 | runnable | — | 显式状态序列断言 |
| Failed Task 携带结构化错误 payload | `FEAT-001.task-failed-payload` | 未覆盖 | blocked | 评审 §6 | 无 code 可断言 + 触发条件依赖故障注入 |

> **待决**：input-required 子用例（`FEAT-001.input-required`）待 deep-research planner 代码检查后决定是否列入（见 §6.3）。

> **不在本档范围**（对齐 FEAT-001 §5.2）：多 Agent 路由、租户认证、gRPC、普通-client webhook 自报 URL、webhook 中间态订阅、webhook token 流、webhook HITL 继续执行、非文本输入、强制中断 LLM、outbound 远程 Agent 编排、agent-bus 私有入口、认证授权协议。

### 1.1 状态分布快照

| 状态 | 数量 | 说明 |
|---|---|---|
| runnable | 14 | 可直接落地，无评审依赖 |
| partial | 7 | 主路径可测，某维度受评审限制 |
| blocked | 5 | 断言依据待评审澄清 |
| deferred | 7 | 依赖能力缺失（webhook 家族 6 条 + no-intermediate 归属其中） |

**落地优先级**：runnable → partial → 评审澄清后 → blocked / deferred。

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置
- deep-research 运行在 `http://7.209.189.82:18090`；A2A 入口 `POST /a2a`（与 `/a2a/`）；Agent Card 入口 `GET /.well-known/agent-card.json`（与 `/.well-known/agent.json`）。
- Redis / long-term memory / sandbox 相关子用例依赖对应 env（`SANDBOX_ENABLED=true` / `SANDBOX_URL` / redis 连接 / `SPRING_PROFILES_ACTIVE=redis-checkpointer` 等），已在旧 DA-05-2 / DA-06 / DA-07 档中登记。
- Push Notification config CRUD 相关子用例假定 SUT 部署已启用 push config store（Agent Card `capabilities.pushNotifications = true`）；若未启用，则相关子用例走 INCONCLUSIVE 分支。

### 2.2 共享测试基础设施
- 客户端：`A2aServiceClient`（现有），SIT 项目 SPI；直接构造 A2A SDK 1.0.0.Final `Client`。
- 事件收集：`A2aEventCollector` + `awaitTerminalState` + `findTerminalEvent` + `collectArtifactText`。
- 文本抽取：`TaskTextExtractor.textOf(task)`。
- 断言库：AssertJ；`@Tag("integration") @Tag("deepagent") @Tag("feat-001")`；部分子用例带 `@Tag("manual")`（cancel 需要长任务模拟等）。
- **底层 HTTP client**：JSON-RPC 错误码 / 尾斜杠 / 非法 payload 等子用例需绕过 SDK 直接发 HTTP，用 `HttpClient` 或等价工具。原因见 §6.2。
- **Webhook 占位 endpoint**：见 §3.5 引言——由于 A2A 标准与 SDK 1.0.0.Final 未定义 receiver 契约、deep-research/agent-search 未实现 receiver（评审 §3），本档不引入 mock receiver 依赖；`webhook-*` 家族大部分子用例 deferred，少数可测子用例（sender 是否 POST、未受信任 target 拒绝）仅需在 SIT 侧临时挂一个占位 HTTP endpoint 观察出向请求。

### 2.3 共享命名约定
- `contextId` 用 `ctx-feat001-<slug>-<uuid8>`，避免不同子用例互相踩记忆缓存。
- Bug 标志串（与 DA-02/03/04/07 复用）：`deep_agent_task_1 already exists` / `controller task parameter error`。任一命中即 FAIL。

---

## 3. 子用例设计

> 约定：每条子用例的表头对齐 FEAT-001 事实要求；步骤用 G/W/T（Given/When/Then）；结论分 PASS/FAIL/INCONCLUSIVE。「框架落点」列指现有 Java 类或标注「待新建」。每条附**状态**行与**评审关联**行（若有）。

### 3.1 Agent Card 与发现

#### FEAT-001.agent-card — 双入口 Agent Card 发现
- **状态**：runnable
- **FEAT 依据**：§2「A2A Agent Card 发现」+ §3「`/.well-known/agent-card.json` + `/.well-known/agent.json`」。
- **G**：deep-research 已就绪。
- **W**：分别 `GET /.well-known/agent-card.json` 与 `GET /.well-known/agent.json`。
- **T**：两次响应 status=200；body JSON 反序列化为 A2A `AgentCard`；两个 card 在 name / version / preferredTransport / url / defaultInputModes / defaultOutputModes / capabilities / skills 上完全等价。
- **PASS**：完全等价。**FAIL**：任一入口 4xx/5xx / 字段漂移。**INCONCLUSIVE**：SUT 不可达。
- **框架落点**：扩展 [AgentCardDiscoveryTest.java](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java)，加对比断言（不重建）。

#### FEAT-001.agent-card-public-base-url — 相对 URL 按公开 base 解析
- **状态**：partial（依赖 SUT 侧配置可见性）
- **FEAT 依据**：§3「`/.well-known/agent-card.json`」补充说明 + §5.1.1「Agent Card 发现语义」。
- **G**：deep-research 启动带 `agent-runtime.access.a2a.public-base-url=...`（SIT 侧无法改 SUT env，故本条 assumeTrue 判定；若未配置公开 base，走原请求 base 分支）。
- **W**：`GET /.well-known/agent-card.json`；解析 card 的 `url` 与 `additionalInterfaces[*].url`。
- **T**：所有 URL 要么以 `public-base-url` 开头（配置了），要么与请求地址一致（未配置）。
- **PASS**：URL 解析规则符合两分支之一。**FAIL**：URL 是本地 hostname / 127.0.0.1 / 与请求 base 不一致且未匹配 public-base。**INCONCLUSIVE**：无法确定 SUT 侧配置。
- **框架落点**：待新建（`AgentCardPublicBaseUrlTest`）。

#### FEAT-001.agent-card-capabilities — capabilities 声明与部署一致
- **状态**：runnable（但 `pushNotifications` 声明真实性与评审 §3 交叉）
- **评审关联**：§3 —— receiver 缺口场景下，SUT 若声明 `pushNotifications=true` 但没有对端 receiver，本身就是声明真实性问题；本用例可以捕获这个不一致。
- **FEAT 依据**：§2「Agent Card capabilities」+ §5.1.1「capabilities 反映部署配置」。
- **G**：deep-research 就绪。
- **W**：`GET /.well-known/agent-card.json`；读 `capabilities`。
- **T**：`streaming=true`；`pushNotifications` 与本档 §3.5 是否可跑对齐——若声明 true 但 sender 侧无法 POST（受信目标为空），视为声明夸大能力。
- **PASS**：capabilities 与实际能力口径一致。**FAIL**：声明 pushNotifications=true 但 sender 从不 POST（夸大能力）；或声明 false 但 SUT 实际推送（能力泄漏）。
- **框架落点**：待新建（`AgentCardCapabilitiesTest`）。

#### FEAT-001.agent-card-skills — skills 声明真实性
- **状态**：runnable
- **FEAT 依据**：§2「Agent Card skills」+ §5.1.1「skills 是跨 Agent 工具发现事实入口」。
- **G**：deep-research 就绪。
- **W**：读 card `skills[]`。
- **T**：skills 非空；每个 skill 有 id / name / description / inputModes / outputModes 完整字段。
- **PASS**：skills 声明完整。**FAIL**：空 skills 但 SUT 实际有可远程调用工具；或 skills 里含幽灵 id 无法调用。
- **框架落点**：待新建（`AgentCardSkillsTest`）。

### 3.2 JSON-RPC 入口分发与错误表面

#### FEAT-001.jsonrpc-endpoint-slash — 尾斜杠等价
- **状态**：runnable
- **FEAT 依据**：§2「A2A JSON-RPC 统一入口」+ §3「`POST /a2a` 与 `POST /a2a/`」。
- **G**：deep-research 就绪；用最小合法 `SendMessage` 请求（同 DA-02 payload）。
- **W**：分别 `POST /a2a` 与 `POST /a2a/`，body 相同。
- **T**：两次响应均为合法 JSON-RPC response；不出现 404 / 301 / 308；两次响应 shape 等价。
- **PASS**：两个 URL 都走标准入口。**FAIL**：任一返 404 / 重定向 / 走了不同分发路径。
- **框架落点**：待新建（`JsonRpcEndpointSlashTest`，用底层 HTTP client 直接发）。

#### FEAT-001.jsonrpc-parse-error — 非法 JSON → parse error
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 + §5.1.8 承诺 "parse error 语义"（不再固定具体码值）；具体码 `-32700` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body 为 `{not-json`。
- **T**：HTTP 200；body 是 JSON-RPC error response；`error.code == -32700`（L2 §5.3 当前实现）；`id == null`。
- **PASS**：满足。**FAIL**：HTTP 4xx/5xx / body 不是标准 JSON-RPC error / code 不匹配。
- **框架落点**：待新建（`JsonRpcParseErrorTest`）。

#### FEAT-001.jsonrpc-invalid-request — shape 不符 → invalid request
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 承诺 "invalid-request 语义" + "错误 response 尽量保留原 request id"（不再固定具体码值）；具体码 `-32600` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body = `{"jsonrpc":"2.0","id":"1"}`。
- **T**：HTTP 200；error response;`error.code == -32600`（L2 §5.3 当前实现）；`id == "1"`。
- **PASS**：满足。**FAIL**：code 不匹配 / id 丢失。
- **框架落点**：待新建（`JsonRpcInvalidRequestTest`）。

#### FEAT-001.jsonrpc-method-not-found — 未知 method
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 承诺 "method-not-found 语义" + "错误 response 尽量保留原 request id"（不再固定具体码值）；具体码 `-32601` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body method 为 `NoSuchMethodEver`。
- **T**：HTTP 200；error response；`error.code == -32601`（L2 §5.3 当前实现）；`id == "7"`。
- **PASS**：满足。**FAIL**：其他 code / HTTP 5xx / id 丢失。
- **框架落点**：待新建（`JsonRpcMethodNotFoundTest`）。

#### FEAT-001.jsonrpc-id-preserved — error response 保留 request id
- **状态**：runnable（并入上面三条断言）
- **FEAT 依据**：version-scope §5.1.8「错误 response 尽量保留原 request id」；对应 L2 §5.3 表里各错误行的 id 回显要求。
- **框架落点**：断言并入 `JsonRpcInvalidRequestTest` / `JsonRpcMethodNotFoundTest`。

### 3.3 核心 A2A 方法（send / get / cancel / list / subscribe）

> **⚠️ Scope 说明**（对齐新 version-scope §2 能力表 + §3 事实要求列）：
> - **MUST 集**：`SendMessage` / `SendStreamingMessage` / `GetTask` / push config CRUD（`Create/Get/List/DeleteTaskPushNotificationConfig`，见 §3.4）。
> - **不在 MUST 集**：`CancelTask` / `ListTasks` / `SubscribeToTask`。version-scope §5.1.8 明示"method unsupported → method-not-found"，即 SUT 不实现这三个 method 返 `-32601` 是**合规**的。
> - **本档处理**：这三个子用例保留在框架落点里作为"如 SUT 实现则做实现快照"，但断言口径应加一步 assumeTrue 探针 —— 先探 method 是否可用（返 `-32601` 视为"能力未激活，本用例 INCONCLUSIVE"），可用才继续正路径断言。

#### FEAT-001.send-message-blocking — 阻塞 send
- **状态**：runnable（DA-02 已覆盖）
- **框架落点**：[SyncSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/SyncSendMessageTest.java)。

#### FEAT-001.send-streaming-message — 流式 send
- **状态**：runnable（DA-03 已覆盖）
- **框架落点**：[StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java)。

#### FEAT-001.stream-mid-error-frame — stream 中途异常追一帧 error
- **状态**：partial
- **评审关联**：§6 —— error code 断言受限，只能断言"有 error envelope"而不能断言具体 code
- **FEAT 依据**：§5.1.4。
- **G**：deep-research 就绪；能人为触发 handler mid-stream 失败。
- **W**：`SendStreamingMessage` 后收集所有 SSE frame。
- **T**：最后一帧是 JSON-RPC error envelope，而不是裸 TCP FIN。
- **PASS**：满足。**FAIL**：连接静默关闭。**INCONCLUSIVE**：无法人为触发 mid-stream 异常。
- **框架落点**：待新建（`StreamMidErrorFrameTest`）。

#### FEAT-001.get-task / get-task-not-found
- **状态**：runnable（DA-04 / DA-04.F 已覆盖）
- **框架落点**：[GetTaskTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/GetTaskTest.java)。

#### FEAT-001.cancel-task-in-flight — 取消执行中任务
- **状态**：partial（scope 降级：新 version-scope §2 未把 `CancelTask` 列入 MUST；SUT 未实现则合规，返 `-32601` 时本用例走 INCONCLUSIVE）
- **评审关联**：§5 —— cancel 到 CANCELED 时限未定，本用例用宽松窗口（30s）观察，不断言时限
- **FEAT 依据**：**不在 version-scope §2 MUST 集**；旧 FEAT §5.1.6 曾要求 cancel 语义，新档已收窄。若 SUT 实现了 `CancelTask`，则按下方期望断言；若返 method-not-found，视为"能力未激活"，本条 INCONCLUSIVE。
- **G**：deep-research 就绪；发起一个足够长的流式任务。
- **W**：拿到 `taskId` 后立即 `CancelTask(taskId)`。
- **T**：先探针一次 `CancelTask`，若返 `-32601` 则 INCONCLUSIVE；否则 CancelTask 返回的 Task 状态在宽松窗口内到达 `TASK_STATE_CANCELED`；后续 `GetTask(taskId)` 也是 CANCELED；stream 侧收到 canceled 终态事件。
- **PASS**：三处一致 CANCELED。**FAIL**：CancelTask 抛异常（非 method-not-found） / 返回 COMPLETED / 状态漂移。**INCONCLUSIVE**：method 未激活。
- **框架落点**：待新建（`CancelTaskInFlightTest`）。

#### FEAT-001.cancel-task-terminal — cancel 已完成任务的幂等语义
- **状态**：blocked（+ scope 降级：同上，不在新 version-scope MUST 集）
- **评审关联**：§5 §6 —— 期望行为未定（幂等 no-op 还是错误）+ 若错误无 code 承载
- **FEAT 依据**：**不在 version-scope §2 MUST 集**；旧 FEAT §5.1.6 边界 + §5.1.8。
- **备注**：等评审澄清"已 terminal 的 Task 再 cancel 是幂等 200 完整回终态还是返回 A2A 特定错误码"后再落地。当前只能弱断言"不 HTTP 5xx"。
- **框架落点**：待新建，与 in-flight 用例合并到 `CancelTaskTest`；本条目前只写"不 5xx"最弱断言，等评审。

#### FEAT-001.list-tasks — 任务列表查询
- **状态**：runnable（scope 降级：不在新 version-scope §2 MUST 集；未实现返 `-32601` 时 INCONCLUSIVE）
- **FEAT 依据**：**不在 version-scope §2 MUST 集**；旧 FEAT §2「任务列表」+ §3「`ListTasks`」。若 SUT 实现则做实现快照。
- **G**：deep-research 就绪；先跑 2~3 个 `SendMessage` 建 task。
- **W**：先探针 `ListTasks(pageSize=1)`，若返 `-32601` 则 INCONCLUSIVE；否则调 `ListTasks(pageSize=..., pageToken=...)`。
- **T**：返回结果集包含刚建的 taskId；分页语义；contextId / tenantId 过滤（若 SDK 支持）能正确缩小结果集。
- **PASS**：所有断言满足。**FAIL**：taskId 缺失 / 分页 / 过滤不工作。**INCONCLUSIVE**：method 未激活。
- **框架落点**：待新建（`ListTasksTest`）。

#### FEAT-001.subscribe-to-task — SSE 断线重连
- **状态**：runnable（scope 降级：不在新 version-scope §2 MUST 集；依赖 SDK API 存在性，未实现返 `-32601` 时 INCONCLUSIVE）
- **FEAT 依据**：**不在 version-scope §2 MUST 集**；旧 FEAT §2「重新订阅」+ §4「断线重连」。若 SUT 实现则做实现快照。
- **G**：deep-research 就绪；起一个长任务。
- **W**：`SendStreamingMessage` 拿到 taskId 后 close SSE；先探针 `SubscribeToTask(taskId)`，若返 `-32601` 则 INCONCLUSIVE；否则调 `SubscribeToTask(taskId)` 重连。
- **T**：重连成功；后续事件序列包含 working / artifact update / terminal；终态与"不断线"路径一致。
- **PASS**：满足。**FAIL**：重连 4xx/5xx（非 method-not-found） / 空事件流 / 终态不一致。**INCONCLUSIVE**：method 未激活。
- **框架落点**：待新建（`SubscribeToTaskTest`；先跑 SDK API smoke 探针确认 `resubscribe` 存在）。

### 3.4 Push Notification config CRUD

#### FEAT-001.push-config-crud — CRUD 全链路
- **状态**：runnable（CRUD 契约本身可测，不触发实际推送）
- **评审关联**：不阻塞，但此条通过后即证明 sender 半边就绪，为 §3.5 deferred 项的解锁前置
- **FEAT 依据**：§2「Push Notification 配置 CRUD」+ §3。
- **G**：deep-research 就绪；Agent Card `capabilities.pushNotifications == true`（否则 assumeTrue 跳过）。
- **W**：串接：
  1. `CreateTaskPushNotificationConfig(taskId, url, token)` → 得 configId
  2. `GetTaskPushNotificationConfig(taskId, configId)` → 回显同一 url / token
  3. `ListTaskPushNotificationConfigs(taskId)` → 包含 configId
  4. `DeleteTaskPushNotificationConfig(taskId, configId)`
  5. 再 `Get` → not-found 类错误
- **T**：每步返回 JSON-RPC result；字段等价；删除后再查为 not-found。
- **PASS**：五步全通。**FAIL**：任一步 5xx / 字段漂移 / delete 后仍能查到。**INCONCLUSIVE**：capabilities.pushNotifications=false 跳过。
- **框架落点**：待新建（`PushConfigCrudTest`）。

### 3.5 Runtime-to-runtime Webhook 完成回调（受评审 §3 影响，全节 deferred / blocked）

> **⚠️ 全节状态说明**：本节子用例整体 **deferred / blocked**，等待评审 §3 (receiver 契约)、§1 (payload 阈值)、§4 (notification id) 澄清并落地对应能力后再实现。
>
> **当前栈能力**：
> - A2A SDK 1.0.0.Final：sender 半边完整（`BasePushNotificationSender` / config CRUD / payload formatter），**receiver 半边一个类都没有**
> - deep-research / agent-search：都未实现 receiver endpoint
>
> **本节可实测子集（不 deferred 的部分）**：
> - `webhook-untrusted-target` **注册拒绝负路径**（不需要 receiver 收到）→ partial 状态
> - `push-config-crud`（在 §3.4）→ runnable
> - 在 SIT 挂占位 HTTP endpoint 观察 "sender 是否曾发起 POST"（能力探针，不作为 FEAT-001 断言，见 §6.2）

#### FEAT-001.webhook-completed — COMPLETED 一次性文本回调
- **状态**：deferred
- **评审关联**：§3 receiver 缺口
- **FEAT 依据**：§2「webhook 文本结果」+ §4 + §5.1.3。
- **落地阻塞条件**：整栈需先具备 receiver endpoint 契约（评审 §3 (a)/(b) 澄清后）。
- **G/W/T**（供解锁后参考）：mock receiver 已启动；SUT allowlist 已加；`SendMessage` 附 `pushNotificationConfig`；等待 receiver 收到 POST。body 含 `tenantId` / `taskId` / `status=COMPLETED` / `result` / `notificationId` / `timestamp`；文本正文能直接从 body 读。
- **框架落点**：待新建（`WebhookCompletedTest`），阻塞至 receiver 契约就绪。

#### FEAT-001.webhook-failed / webhook-canceled / webhook-rejected
- **状态**：deferred
- **评审关联**：§3
- **落地阻塞条件**：同 completed；且各终态本身的触发条件（评审 §5.1.6 关联，见 §6.4）
- **框架落点**：待新建，三条并入 `WebhookTerminalStateTest`（参数化），阻塞至 receiver 就绪。

#### FEAT-001.webhook-payload-ref — 大载荷走 payloadRef
- **状态**：blocked
- **评审关联**：§1 阈值未定 + §3 receiver 缺失
- **落地阻塞条件**：需要评审 §1 定义 payload 阈值、§3 定义 receiver 契约
- **框架落点**：待新建（`WebhookPayloadRefTest`）。

#### FEAT-001.webhook-idempotent — 稳定 notification id + 幂等
- **状态**：blocked
- **评审关联**：§4 notification id 字段承载缺失 + §3 receiver 缺失
- **落地阻塞条件**：A2A SDK 当前 payload 无独立 `notificationId` 字段；需 SDK 后续版本或 runtime 侧扩展 header/body
- **框架落点**：待新建（`WebhookIdempotencyTest`）。

#### FEAT-001.webhook-no-intermediate — 中间态不推送
- **状态**：deferred
- **评审关联**：§3
- **框架落点**：待新建，可与 `WebhookCompletedTest` 合并为双段断言，阻塞至 receiver 就绪。

#### FEAT-001.webhook-untrusted-target — 未受信任 target 拒绝
- **状态**：partial（可测注册拒绝负路径，不需要 receiver 收到；正路径—— trusted target 真实推送——deferred）
- **评审关联**：§2 webhook 安全机制未明；本条只能测"注册被拒绝"这一条负路径，不能验证"trusted 判定规则本身"
- **FEAT 依据**：§2「webhook 安全边界」+ §5.1.8「webhook target untrusted」。
- **G**：显式配一个明显不受信的 URL（如 `http://evil.example`，或 `file://` scheme）。
- **W**：调 `CreateTaskPushNotificationConfig(url=<untrusted>)`。
- **T**：SUT 返回 JSON-RPC error 拒绝注册，**或**注册成功但 SIT 侧占位 endpoint 未收到任何 POST。
- **PASS**：注册被拒绝或投递被拦截。**FAIL**：SUT 向未受信 URL 发了 POST（信任边界失守）。
- **框架落点**：待新建（`WebhookUntrustedTargetTest`）。

#### FEAT-001.webhook-vs-streaming — streaming 与 webhook 分离
- **状态**：deferred
- **评审关联**：§3 receiver 缺失导致无法观察 webhook 侧收到什么
- **框架落点**：待新建（`WebhookVsStreamingTest`），阻塞至 receiver 就绪。

### 3.6 传输 / 上下文 / 输入边界

#### FEAT-001.tenant-id-propagation — X-Tenant-Id 头传递
- **状态**：partial（scope 降级 + SUT 现状）
- **评审关联**：§7 缺 tenant header 时的落点未定 —— 本用例只测"带 header 时可被观测"
- **FEAT 依据**：新 version-scope §2 能力表**未明确 `X-Tenant-Id` header 契约**（能力被抽象为"运行时上下文传递 / 多租户隔离"，未固定 header 名称）；旧 FEAT §5.1.7 曾承诺 `X-Tenant-Id` 落点，本条按旧口径断言。
- **SUT 现状快照**：deep-research 的 Agent Card `supportedInterfaces[0].tenant == null`（DA-01.C 已断言），表明 SUT 侧多租户尚未启用；带 header 也可能无观测面，本条大概率 INCONCLUSIVE。
- **G**：deep-research 就绪；能观测到 `tenantId` 落到 task metadata / trajectory / artifact 中（若 SUT 侧回显；否则本条 INCONCLUSIVE）。
- **W**：`POST /a2a` 带 `X-Tenant-Id: sit-tenant-<uuid>`；跑 `SendMessage`。
- **T**：task metadata（或 SUT 侧可读的其他 observability 面）里能读到该 tenantId。
- **PASS**：tenantId 能被回显 / 追踪。**FAIL**：header 被丢弃。**INCONCLUSIVE**：SUT 侧无 tenantId 可观测面 / 多租户未启用 → 走 §3.6.tenant-isolation 间接证据。
- **框架落点**：待新建（`TenantIdPropagationTest`）。

#### FEAT-001.tenant-isolation — 跨租户记忆隔离（间接证据）
- **状态**：partial（scope 降级 + SUT 现状）
- **评审关联**：§7 —— 特性文档要求 SIT 承认此为验收证据
- **FEAT 依据**：新 version-scope §2「多租户隔离」抽象化未固定 header 与 key 派生规则；旧 FEAT §5.1.7「租户 …… 派生默认 `agentStateKey` 与 `memoryScope`」保留作为期望参考。
- **SUT 现状快照**：同上，SUT 当前 `supportedInterfaces[0].tenant == null`，多租户未激活；本条大概率 INCONCLUSIVE。
- **G**：deep-research 就绪；redis-checkpointer 或 long-term-memory 已启用（依赖 DA-05 / DA-06 链路）。
- **W**：用 tenant=A 跑一轮存记忆 → 用 tenant=B 相同 prompt 尝试召回。
- **T**：tenant=B 无法召回 tenant=A 的记忆内容。
- **PASS**：跨租户隔离生效。**FAIL**：tenant=B 召回到 tenant=A 的内容（隔离失守）。**INCONCLUSIVE**：deep-research 记忆链路本身不 work（回退到 DA-05/DA-06 排障） / 多租户未启用。
- **框架落点**：待新建（`TenantIsolationTest`；复用 DA-05/DA-06 的 fixture）。

#### FEAT-001.empty-text-input — 空文本输入拒绝
- **状态**：partial
- **评审关联**：§6 —— 拒绝语义能测（走 JSON-RPC error / 或 Task REJECTED），但具体 error code 无法断言
- **FEAT 依据**：§5.1.7。
- **G**：deep-research 就绪。
- **W**：`SendMessage`，parts = `[new TextPart("")]`。
- **T**：runtime 应返回 JSON-RPC error **或** task 走 FAILED / REJECTED；**不应**把空输入交给 agent 猜。
- **PASS**：任一拒绝分支。**FAIL**：task COMPLETED 且 agent 生成了任何 artifact。
- **框架落点**：待新建（`EmptyTextInputTest`）。

### 3.7 Task 生命周期

#### FEAT-001.task-lifecycle — 状态序列 submitted → working → terminal
- **状态**：runnable
- **FEAT 依据**：§5.1.6。
- **G**：deep-research 就绪。
- **W**：`SendStreamingMessage` 并按序记录每个 SSE 事件的 task.status.state。
- **T**：序列至少包含 `SUBMITTED` → `WORKING` → `COMPLETED`（或其他 terminal）；状态严格单调。
- **PASS**：序列合法。**FAIL**：跳过 WORKING 直到 COMPLETED / 状态回退。
- **框架落点**：扩展 [StreamingSendMessageTest.java](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java)。

#### FEAT-001.task-failed-payload — Failed Task 携带结构化错误
- **状态**：blocked
- **评审关联**：§6 —— 无 error code 承载；触发条件亦依赖故障注入
- **FEAT 依据**：§5.1.6 + §5.1.8。
- **框架落点**：待新建（`TaskFailedPayloadTest`；触发条件与 §3.3 stream-mid-error-frame 重叠，可复用 fixture），阻塞至评审 §6 定 code。

---

## 4. 框架落点汇总

| 子用例 ID | 落点 Java 类 | 状态 | 类状态 |
|---|---|---|---|
| agent-card | `AgentCardDiscoveryTest` | runnable | 扩展 |
| agent-card-public-base-url | `AgentCardPublicBaseUrlTest` | partial | 待新建 |
| agent-card-capabilities | `AgentCardCapabilitiesTest` | runnable | 待新建 |
| agent-card-skills | `AgentCardSkillsTest` | runnable | 待新建 |
| jsonrpc-endpoint-slash | `JsonRpcEndpointSlashTest` | runnable | 待新建 |
| jsonrpc-parse-error | `JsonRpcParseErrorTest` | runnable | 待新建 |
| jsonrpc-invalid-request | `JsonRpcInvalidRequestTest` | runnable | 待新建 |
| jsonrpc-method-not-found | `JsonRpcMethodNotFoundTest` | runnable | 待新建 |
| send-message-blocking | `SyncSendMessageTest` | runnable | 已有（DA-02） |
| send-streaming-message | `StreamingSendMessageTest` | runnable | 已有（DA-03） |
| stream-mid-error-frame | `StreamMidErrorFrameTest` | partial | 待新建 |
| get-task / get-task-not-found | `GetTaskTest` | runnable | 已有（DA-04 + F） |
| cancel-task-in-flight | `CancelTaskTest` | partial | 待新建 |
| cancel-task-terminal | 同上（并入） | blocked | 待新建 |
| list-tasks | `ListTasksTest` | runnable | 待新建 |
| subscribe-to-task | `SubscribeToTaskTest` | runnable | 待新建 |
| push-config-crud | `PushConfigCrudTest` | runnable | 待新建 |
| webhook-completed | `WebhookCompletedTest` | **deferred** | 阻塞（评审 §3） |
| webhook-{failed,canceled,rejected} | `WebhookTerminalStateTest` | **deferred** | 阻塞（评审 §3） |
| webhook-payload-ref | `WebhookPayloadRefTest` | **blocked** | 阻塞（评审 §1 §3） |
| webhook-idempotent | `WebhookIdempotencyTest` | **blocked** | 阻塞（评审 §3 §4） |
| webhook-no-intermediate | 并入 `WebhookCompletedTest` | **deferred** | 阻塞（评审 §3） |
| webhook-untrusted-target | `WebhookUntrustedTargetTest` | partial | 待新建 |
| webhook-vs-streaming | `WebhookVsStreamingTest` | **deferred** | 阻塞（评审 §3） |
| tenant-id-propagation | `TenantIdPropagationTest` | partial | 待新建 |
| tenant-isolation | `TenantIsolationTest` | partial | 待新建 |
| empty-text-input | `EmptyTextInputTest` | partial | 待新建 |
| task-lifecycle | 扩展 `StreamingSendMessageTest` | runnable | 扩展 |
| task-failed-payload | `TaskFailedPayloadTest` | blocked | 阻塞（评审 §6） |

所有新建类落到 `src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/`，包 `com.huawei.ascend.sit.cases.integration.deepagent_deepresearch`。

### 4.1 落地优先级建议

**P0-A · 扩展现有 test（改口径 / 加断言）**
- `AgentCardDiscoveryTest`（+ 双入口对比）
- `StreamingSendMessageTest`（+ 状态序列断言 → task-lifecycle）

**P0-B · JSON-RPC 错误面（新类，纯正/负路径，用底层 HTTP client）**
- `JsonRpcEndpointSlashTest`
- `JsonRpcParseErrorTest`
- `JsonRpcInvalidRequestTest`
- `JsonRpcMethodNotFoundTest`

**P0-C · SDK 方法扩展（新类，正路径）**
- `CancelTaskTest`（in-flight 部分，terminal 部分先写 stub）
- `ListTasksTest`
- `SubscribeToTaskTest`（先跑 SDK API smoke）
- `PushConfigCrudTest`

**P1 · Agent Card 完整性 + 场景化**
- `AgentCardCapabilitiesTest`
- `AgentCardSkillsTest`
- `AgentCardPublicBaseUrlTest`（partial）
- `TenantIdPropagationTest`（partial）
- `TenantIsolationTest`（partial，复用 DA-05/06 fixture）
- `EmptyTextInputTest`（partial）
- `WebhookUntrustedTargetTest`（partial，只测注册拒绝）

**P2 · 依赖故障注入**
- `StreamMidErrorFrameTest`
- `TaskFailedPayloadTest`

**Deferred · 阻塞至评审澄清 / 能力就绪**
- webhook 家族其余 6 条：`WebhookCompletedTest` / `WebhookTerminalStateTest` / `WebhookPayloadRefTest` / `WebhookIdempotencyTest` / `WebhookVsStreamingTest`
- `cancel-task-terminal` 完整断言

---

## 5. 运行方式

```bash
# 全部 FEAT-001 相关用例（跳过 @Tag("manual")）
./mvnw -Dtest.env=SIT -Dgroups=feat-001 test

# 指定单条子用例
./mvnw -Dtest.env=SIT -Dtest=CancelTaskTest test

# 强跑 manual 分支（含长任务 cancel 等）
./mvnw -Dtest.env=SIT -Dgroups='feat-001 & manual' test
```

---

## 6. 风险与备注

### 6.1 特性文档待澄清项 → 全部见评审文档

本档所有 blocked / partial / deferred 状态源于评审文档 [FEAT-001-standardized-agent-service-entrypoint-review.md](FEAT-001-standardized-agent-service-entrypoint-review.md) 的 §1~§7。摘要对照：

| 评审项 | 本档受影响子用例 |
|---|---|
| §1 webhook 承载阈值未定 | `webhook-payload-ref` |
| §2 webhook 安全机制被延后 | `webhook-untrusted-target`（partial：只测负路径） |
| §3 webhook receiver 契约在 SDK/应用/文档三层缺失 | `webhook-{completed,failed,canceled,rejected,no-intermediate,vs-streaming,payload-ref,idempotent}` |
| §4 notification id 无字段承载 | `webhook-idempotent` |
| §5 CancelTask 时限/期间行为未定 | `cancel-task-in-flight`（partial，宽松窗口）+ `cancel-task-terminal`（blocked） |
| §6 错误码未列 | `stream-mid-error-frame` / `empty-text-input` / `task-failed-payload` / `cancel-task-terminal` |
| §7 缺 X-Tenant-Id 落点 | `tenant-id-propagation`（partial）+ `tenant-isolation`（partial） |

### 6.2 实现层风险（非评审风险）

**SDK 版本能力天花板**
- A2A SDK `1.0.0.Final` 的 `JSONRPCTransport.unmarshalResponse` 不按 JSON-RPC error code 分流具体子类——见 [DA-04 §9 备注](deepagent/DA-04-get-task.md)。协议错误 code 断言子用例（`jsonrpc-*` / `webhook-untrusted-target` 若拒绝走 error 分支）需要绕过 SDK，用底层 HTTP client 直接发。
- SDK 是否暴露 `SubscribeToTask` / `ListTasks` / push config CRUD 的 Java API 需要在动工前先反编译 SDK 或跑 smoke 探针；若 SDK 未包裹，用 `HttpClient` 直发 JSON-RPC payload。

**Webhook 占位 endpoint 而非 mock receiver**
- 由于评审 §3 结论"整栈无 receiver"，本档**不引入 WireMock / MockWebServer** 依赖，避免引入"以 SIT 侧 mock 定义 receiver 契约"的隐性假设。
- `webhook-untrusted-target` 可用 `com.sun.net.httpserver.HttpServer`（JDK 自带）临时挂一个占位 endpoint，只用于"观察 SUT 是否曾 POST"（负路径断言用）。
- 完整 webhook 家族用例的实现，**等待评审 §3 落地 receiver 契约后**才决定用什么依赖。

### 6.3 待决：input-required 子用例

FEAT-001 §5.1.6 要求 handler 输出需要用户输入的中断时 Task 进入 input-required 类语义。deep-research 是否有 planner 澄清追问路径（触发该状态的业务代码）尚未确认。

- **待做**：扫描 deep-research 源码，确认 planner / clarification 路径是否产出 `TaskState.INPUT_REQUIRED`。项目组已确认 deep-research 源在 `D:\openjiuwen-java\...`，待用户提供具体路径后核实。
- **决策分支**：
  - 若有 → 新增 `FEAT-001.input-required` 子用例（runnable / partial），找到能可靠触发澄清的 prompt
  - 若无 → 本档记录"deep-research SUT 上该状态不可达"，交由其他有 HITL 能力的 SUT 覆盖

### 6.4 Cancel / Failed / Rejected 触发条件

- Cancel 需要制造够长任务。deep-research 沙箱工具场景（DA-07）prompt 长度足够；备选：sandbox 里跑 `time.sleep(60)`。
- Failed 依赖 handler 层的可控故障；如无法注入，只能利用已知 bug 状态——但那是 variant 1 bug 会污染 artifact 而非规范 FAILED，属"假的 failed"。此项若无法制造真的 failed，报告标 INCONCLUSIVE。
- Rejected 通常来自协议层拒绝（如空文本输入、no-handler）。`empty-text-input` 天然触发 rejected 路径。

### 6.5 Tenant ID 可观测性

- SIT 侧能否读到 tenantId 依赖 SUT 是否在 task metadata / trajectory / MDC 里回显。若 SUT 无 observability 面暴露，直接观测的 `tenant-id-propagation` 走 INCONCLUSIVE，退回 `tenant-isolation` 间接证据。
- `tenant-isolation` 需要 deep-research 记忆链路本身可用（DA-05 / DA-06 前置）；如 DA-05/06 本身处于已知 bug 状态，本条也回退 INCONCLUSIVE。

### 6.6 与旧 DA-*.md 的关系

- 旧档不删除；本档在覆盖矩阵和「框架落点」列里显式引用旧档路径。
- 未来若旧档某条断言与 FEAT 事实要求出现冲突，以本档为准，旧档同步修订或标 `status: legacy`。

### 6.7 与 FEAT-005（outbound Agent 编排）关系

- 本档只覆盖 inbound。deep-research 若自己作为 client 主动调其他 agent（outbound），由 FEAT-005 承接，不列入。
- 通用 runtime-to-runtime 场景（deep-research → agent-search 常规 A2A 调用）：从 **agent-search 服务端视角**观察它作为 A2A server 的入口面，本质上属于"agent-search 作为独立 SUT 的 FEAT-001 用例"——如需覆盖，另开一份 `FEAT-001-standardized-agent-service-entrypoint-agentsearch.md`，不并入本档。