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
| Agent Card 双入口发现 | `FEAT-001.agent-card` | 已覆盖（[AgentCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java)） | runnable | — | 三入口等价性硬断言（agent.json / agent-card.json / /a2a/.well-known/agent-card.json 三份 body 完全等价 + 200 + application/json） |
| Agent Card 公开 base URL 解析 | `FEAT-001.agent-card-public-base-url` | 已覆盖（[AgentCardPublicBaseUrlTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardPublicBaseUrlTest.java)） | partial | — | 落"可拨性"约束，不依赖 SUT env |
| Agent Card capabilities 声明真实性 | `FEAT-001.agent-card-capabilities` | 已覆盖（[AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java)） | runnable | 评审 §3 交叉 | streaming=true 硬断言；pushNotifications 与 push-config-crud 联动 |
| Agent Card skills 声明真实性 | `FEAT-001.agent-card-skills` | 已覆盖（[AgentCardSkillsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardSkillsTest.java)） | runnable | — | id/name/description 非空 + id 唯一 + 主 skill 存在 |
| `/a2a` 与 `/a2a/` 尾斜杠等价 | `FEAT-001.jsonrpc-endpoint-slash` | 已覆盖（[JsonRpcEndpointSlashTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcEndpointSlashTest.java)） | runnable | — | 用底层 HTTP client + `GetTask` payload 避免真实 LLM |
| JSON-RPC parse error | `FEAT-001.jsonrpc-parse-error` | 已覆盖（[JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java)） | runnable | — | 硬断言 `-32700`（L2 §5.3） |
| JSON-RPC invalid request | `FEAT-001.jsonrpc-invalid-request` | 已覆盖（[JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java)） | runnable | — | 硬断言 `-32600`（L2 §5.3）+ id 回显 |
| JSON-RPC method-not-found | `FEAT-001.jsonrpc-method-not-found` | 已覆盖（[JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java)） | runnable | — | 硬断言 `-32601`（L2 §5.3）+ id 回显 |
| JSON-RPC error 保留 request id | `FEAT-001.jsonrpc-id-preserved` | 已覆盖（并入 invalid-request + method-not-found 断言） | runnable | — | invalid-request 断 id=`"1"`；method-not-found 断 id=`"7"`；parse-error 按 JSON-RPC 2.0 §5.1 断 id=null |
| 阻塞 `SendMessage` | `FEAT-001.send-message-blocking` | DA-02 覆盖 | runnable | — | 已覆盖 |
| 流式 `SendStreamingMessage` | `FEAT-001.send-streaming-message` | DA-03 覆盖 | runnable | — | 已覆盖 |
| Stream 中途下游 agent 被杀 | `FEAT-001.downstream-agent-killed-mid-stream` | 已覆盖（[DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java)，watchdog + manual） | partial | 评审 §6 | 用 SutStack.stop() 中途杀 search 触发 handler runtime exception；层 1（终态 ∈ failed/canceled/rejected）+ 层 2（结构化 payload）为硬 MUST，不受 §6 影响；jar 就绪前 @manual |
| 不存在工具的 LLM 拒答 | `FEAT-001.nonexistent-tool-refusal` | 已覆盖（[NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java)） | runnable | — | §5.1.6 正例：LLM 收到虚构工具请求应 COMPLETED 且回答包含工具名 + 「不存在/不可用」关键词；与 downstream-agent-killed 构成完整错误面覆盖 |
| `GetTask` 快照 | `FEAT-001.get-task` | DA-04 覆盖 | runnable | — | 已覆盖 |
| `GetTask` 负路径（TaskNotFound） | `FEAT-001.get-task-not-found` | DA-04.F 覆盖 | runnable | — | 已覆盖 |
| Push Notification config CRUD | `FEAT-001.push-config-crud` | 已覆盖（[PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java)） | runnable | — | capabilities.pushNotifications=false 时 assumeTrue skip |
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
| 空文本输入拒绝 | `FEAT-001.empty-text-input` | 已覆盖（[EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java)） | partial | 评审 §6 | 接受 send 异常 / FAILED / REJECTED / COMPLETED+空 artifact 四种拒绝分支 |
| Task 生命周期状态序列 | `FEAT-001.task-lifecycle` | DA-03 部分覆盖 | runnable | — | 显式状态序列断言 |
| Failed Task 携带结构化错误 payload | `FEAT-001.task-failed-payload` | 未覆盖 | blocked | 评审 §6 | 无 code 可断言 + 触发条件依赖故障注入 |

> **待决**：input-required 子用例（`FEAT-001.input-required`）待 deep-research planner 代码检查后决定是否列入（见 §6.3）。

> **不在本档范围**（对齐 FEAT-001 §5.2 + version-scope §2 MUST 集）：`CancelTask` / `ListTasks` / `SubscribeToTask`（不在 version-scope §2 MUST 集，method-not-found 返 `-32601` 合规）、多 Agent 路由、租户认证、gRPC、普通-client webhook 自报 URL、webhook 中间态订阅、webhook token 流、webhook HITL 继续执行、非文本输入、强制中断 LLM、outbound 远程 Agent 编排、agent-bus 私有入口、认证授权协议。

### 1.1 状态分布快照

| 状态 | 数量 | 说明 |
|---|---|---|
| runnable | 13 | 可直接落地，无评审依赖 |
| partial | 6 | 主路径可测，某维度受评审限制 |
| blocked | 4 | 断言依据待评审澄清 |
| deferred | 7 | 依赖能力缺失（webhook 家族 6 条 + no-intermediate 归属其中） |

**落地优先级**：runnable → partial → 评审澄清后 → blocked / deferred。

### 1.2 覆盖进度看板

> **用法**：随开发推进直接改 ✅ / ⬜ 状态位；子用例语义已在 §3 展开，此表只做单页进度对照。
> **图例**：✅ 已落地并 PASS；🟡 已落地但 partial（受评审 / SUT 限制）；⬜ 待落地；🚫 阻塞（评审 / 能力）；⏸ deferred（能力缺失）

| 类别 | ID | 子用例 | 状态 | 落点 |
|---|---|---|---|---|
| **A. Agent Card 发现（4）** | A1 | agent-card 双入口 | ✅ | [AgentCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java)（三入口等价性硬断言） |
| | A2 | agent-card-public-base-url | 🟡 | [AgentCardPublicBaseUrlTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardPublicBaseUrlTest.java) |
| | A3 | agent-card-capabilities | ✅ | [AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) |
| | A4 | agent-card-skills | ✅ | [AgentCardSkillsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardSkillsTest.java) |
| **B. JSON-RPC 错误面（5）** | B1 | jsonrpc-endpoint-slash | ✅ | [JsonRpcEndpointSlashTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcEndpointSlashTest.java) |
| | B2 | jsonrpc-parse-error | ✅ | [JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java) |
| | B3 | jsonrpc-invalid-request | ✅ | [JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java) |
| | B4 | jsonrpc-method-not-found | ✅ | [JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java) |
| | B5 | jsonrpc-id-preserved | ✅ | 并入 B2 / B3 / B4 |
| **C. 核心 A2A 方法（5）** | C1 | send-message-blocking | ✅ | SyncSendMessageTest（DA-02） |
| | C2 | send-streaming-message | ✅ | StreamingSendMessageTest（DA-03） |
| | C3 | downstream-agent-killed-mid-stream | 🟡 | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java)（watchdog + @manual；本地拉两 jar，用 SutStack.stop() 中途杀 search） |
| | C4 | get-task / not-found | ✅ | GetTaskTest（DA-04 + F） |
| | C5 | nonexistent-tool-refusal | ✅ | [NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java) |
| **D. Push Config CRUD（1）** | D1 | push-config-crud | ✅ | [PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java)（capabilities=false 时 assumeTrue skip） |
| **E. Webhook 家族（7）** | E1 | webhook-completed | ⏸ | WebhookCompletedTest |
| | E2 | webhook-failed/canceled/rejected | ⏸ | WebhookTerminalStateTest |
| | E3 | webhook-payload-ref | 🚫 | WebhookPayloadRefTest |
| | E4 | webhook-idempotent | 🚫 | WebhookIdempotencyTest |
| | E5 | webhook-no-intermediate | ⏸ | 并入 E1 |
| | E6 | webhook-untrusted-target | ⬜ | WebhookUntrustedTargetTest |
| | E7 | webhook-vs-streaming | ⏸ | WebhookVsStreamingTest |
| **F. Tenant / 输入 / 生命周期（5）** | F1 | tenant-id-propagation | ⬜ | TenantIdPropagationTest |
| | F2 | tenant-isolation | ⬜ | TenantIsolationTest |
| | F3 | empty-text-input | 🟡 | [EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java) |
| | F4 | task-lifecycle | ⬜ | 扩展 StreamingSendMessageTest |
| | F5 | task-failed-payload | 🚫 | TaskFailedPayloadTest |

**进度**：已落地 15 / 27（其中 ✅ 硬 PASS 12、🟡 partial 3）；⬜ 待落地 4；🚫 blocked 3；⏸ deferred 5。

**下一步优先级**：
1. **P0** ⬜ F4 task-lifecycle（StreamingSendMessageTest 扩状态序列断言）
2. **P1** ⬜ E6 webhook-untrusted-target（只测注册拒绝负路径，不依赖 receiver）
3. **P1** ⬜ F1/F2 tenant 双条（依赖 §7 澄清 X-Tenant-Id 落点）
4. **P2** 🟡 C3 downstream-agent-killed-mid-stream（本地 jar 就绪 + 验证 SEARCH_AGENT_URL env 生效后移除 @manual → 升为常态 PASS）
5. **Blocked** 🚫 F5 task-failed-payload / E3 payload-ref / E4 idempotent 等评审澄清
6. **Deferred** ⏸ webhook 家族其余 5 条等 receiver 契约就绪

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
- **框架落点**：[AgentCardPublicBaseUrlTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardPublicBaseUrlTest.java)（新增）。SIT 无法读 SUT env，本用例落"可拨性"最弱约束：URL 是绝对 URL + scheme ∈ {http,https} + host 不是 loopback + supportedInterfaces host 一致。SIT 已 PASS。

#### FEAT-001.agent-card-capabilities — capabilities 声明与部署一致
- **状态**：runnable（但 `pushNotifications` 声明真实性与评审 §3 交叉）
- **评审关联**：§3 —— receiver 缺口场景下，SUT 若声明 `pushNotifications=true` 但没有对端 receiver，本身就是声明真实性问题；本用例可以捕获这个不一致。
- **FEAT 依据**：§2「Agent Card capabilities」+ §5.1.1「capabilities 反映部署配置」。
- **G**：deep-research 就绪。
- **W**：`GET /.well-known/agent-card.json`；读 `capabilities`。
- **T**：`streaming=true`；`pushNotifications` 与本档 §3.5 是否可跑对齐——若声明 true 但 sender 侧无法 POST（受信目标为空），视为声明夸大能力。
- **PASS**：capabilities 与实际能力口径一致。**FAIL**：声明 pushNotifications=true 但 sender 从不 POST（夸大能力）；或声明 false 但 SUT 实际推送（能力泄漏）。
- **框架落点**：[AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java)（新增）。capabilities.streaming=true 硬断言；pushNotifications 字段只做可读断言（具体值交给 [PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java) 通过 CRUD 探针间接验证）。SIT 已 PASS。

#### FEAT-001.agent-card-skills — skills 声明真实性
- **状态**：runnable
- **FEAT 依据**：§2「Agent Card skills」+ §5.1.1「skills 是跨 Agent 工具发现事实入口」。
- **G**：deep-research 就绪。
- **W**：读 card `skills[]`。
- **T**：skills 非空；每个 skill 有 id / name / description / inputModes / outputModes 完整字段。
- **PASS**：skills 声明完整。**FAIL**：空 skills 但 SUT 实际有可远程调用工具；或 skills 里含幽灵 id 无法调用。
- **框架落点**：[AgentCardSkillsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardSkillsTest.java)（新增）。skills[] 非空 + 每个 skill 的 id/name/description 非空 + id 唯一 + 存在 deep_research 主 skill 带 tags。SIT 已 PASS。

### 3.2 JSON-RPC 入口分发与错误表面

#### FEAT-001.jsonrpc-endpoint-slash — 尾斜杠等价
- **状态**：runnable
- **FEAT 依据**：§2「A2A JSON-RPC 统一入口」+ §3「`POST /a2a` 与 `POST /a2a/`」。
- **G**：deep-research 就绪；用最小合法 `SendMessage` 请求（同 DA-02 payload）。
- **W**：分别 `POST /a2a` 与 `POST /a2a/`，body 相同。
- **T**：两次响应均为合法 JSON-RPC response；不出现 404 / 301 / 308；两次响应 shape 等价。
- **PASS**：两个 URL 都走标准入口。**FAIL**：任一返 404 / 重定向 / 走了不同分发路径。
- **框架落点**：[JsonRpcEndpointSlashTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcEndpointSlashTest.java)（已落）。用底层 `HttpClient` 直接发 `GetTask` payload + 随机 UUID taskId —— 避免真实 LLM 调用，两个 URL 都返 `-32001 TaskNotFound`，尾斜杠等价性不受影响。SIT 已 PASS。

#### FEAT-001.jsonrpc-parse-error — 非法 JSON → parse error
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 + §5.1.8 承诺 "parse error 语义"（不再固定具体码值）；具体码 `-32700` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body 为 `{not-json`。
- **T**：HTTP 200；body 是 JSON-RPC error response；`error.code == -32700`（L2 §5.3 当前实现）；`id == null`。
- **PASS**：满足。**FAIL**：HTTP 4xx/5xx / body 不是标准 JSON-RPC error / code 不匹配。
- **框架落点**：[JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java)（已落）。用底层 `HttpClient` 直发非法 JSON body；硬断言 HTTP 200 + `error.code=-32700` + `id=null`。SIT 已 PASS。

#### FEAT-001.jsonrpc-invalid-request — shape 不符 → invalid request
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 承诺 "invalid-request 语义" + "错误 response 尽量保留原 request id"（不再固定具体码值）；具体码 `-32600` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body = `{"jsonrpc":"2.0","id":"1"}`。
- **T**：HTTP 200；error response;`error.code == -32600`（L2 §5.3 当前实现）；`id == "1"`。
- **PASS**：满足。**FAIL**：code 不匹配 / id 丢失。
- **框架落点**：[JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java)（已落）。用底层 `HttpClient` 发 `{"jsonrpc":"2.0","id":"1"}`（缺 method）；硬断言 HTTP 200 + `error.code=-32600` + `id="1"` 回显（并覆盖 `jsonrpc-id-preserved`）。SIT 已 PASS。

#### FEAT-001.jsonrpc-method-not-found — 未知 method
- **状态**：runnable
- **FEAT 依据**：version-scope §5.1.2 承诺 "method-not-found 语义" + "错误 response 尽量保留原 request id"（不再固定具体码值）；具体码 `-32601` 按 L2 §5.3 当前实现事实钉。
- **G**：deep-research 就绪。
- **W**：`POST /a2a` body method 为 `NoSuchMethodEver`。
- **T**：HTTP 200；error response；`error.code == -32601`（L2 §5.3 当前实现）；`id == "7"`。
- **PASS**：满足。**FAIL**：其他 code / HTTP 5xx / id 丢失。
- **框架落点**：[JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java)（已落）。用底层 `HttpClient` 发 `method="NoSuchMethodEver"`；硬断言 HTTP 200 + `error.code=-32601` + `id="7"` 回显（并覆盖 `jsonrpc-id-preserved`）。SIT 已 PASS。

#### FEAT-001.jsonrpc-id-preserved — error response 保留 request id
- **状态**：runnable（并入上面三条断言）
- **FEAT 依据**：version-scope §5.1.8「错误 response 尽量保留原 request id」；对应 L2 §5.3 表里各错误行的 id 回显要求。
- **框架落点**：断言并入 [JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java)（id=`"1"`）+ [JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java)（id=`"7"`）；parse-error 场景按 JSON-RPC 2.0 §5.1 断 `id=null`（[JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java)）。

### 3.3 核心 A2A 方法（send / get）

> **⚠️ Scope 说明**（对齐 version-scope §2 能力表 + §3 事实要求列）：
> - **MUST 集**：`SendMessage` / `SendStreamingMessage` / `GetTask` / push config CRUD（`Create/Get/List/DeleteTaskPushNotificationConfig`，见 §3.4）。
> - `CancelTask` / `ListTasks` / `SubscribeToTask` 已从 version-scope §2 MUST 集中移除，见 §1「不在本档范围」；本档不再列子用例。

#### FEAT-001.send-message-blocking — 阻塞 send
- **状态**：runnable（DA-02 已覆盖）
- **框架落点**：[SyncSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/SyncSendMessageTest.java)。

#### FEAT-001.send-streaming-message — 流式 send
- **状态**：runnable（DA-03 已覆盖）
- **框架落点**：[StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java)。

#### FEAT-001.downstream-agent-killed-mid-stream — 下游 A2A agent 中途被杀
- **状态**：partial（watchdog 已落；本地拉起两 jar，用 `SutStack.stop()` 中途杀 search 触发；jar 就绪前 @manual）
- **评审关联**：§6 —— 具体 `error.code` 值断不了；但**层 1 / 层 2 是 spec 明文 MUST**，不受 §6 影响
- **FEAT 依据**：§5.1.4「stream 必须关闭 + 以 failed 收束」+ §5.1.6「COMPLETED 语义:任务已完成」+ §5.1.8「handler runtime exception → failed Task + 结构化错误 payload」。
- **G**：deep-research + search 两 jar 本地就绪（`~/.m2/repository/com/openjiuwen/example/`）；框架拉起 search 后再拉起 deep-research 并把 search baseUrl 通过 `SEARCH_AGENT_URL` 环境变量注入。
- **W**：`SendStreamingMessage` 发一个明确需要 search 的 prompt；等待 deep-research 进入 WORKING 状态 + 一小段 grace period（让 tool call 真正打给 search）后调 `SutStack.stop("search")`；收集所有 SSE frame + terminal Task。
- **T**：
  - **层 1**（§5.1.4 + §5.1.6 + §5.1.8）：stream 终态 ∈ {FAILED, CANCELED, REJECTED} —— **COMPLETED 视为 FAIL**（agent 无法完整回答用户却包装成成功，违反 spec）
  - **层 2**（§5.1.8）：终态 Task 携带结构化错误 payload（`status.message.parts` 非空）
  - **不断言**：具体 `error.code` / 错误消息措辞
- **PASS**：层 1 + 层 2 都满足。**FAIL**：终态是 COMPLETED（下游挂了却走成功收束——即 SUT 违反 §5.1.6） / 无结构化 payload / 静默 FIN。
- **框架落点**：[DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java)。标 `@Tag("manual")`：需要本地 deep-research + search 两 jar，CI 环境默认不具备；jar 就绪且 SEARCH_AGENT_URL env 注入验证生效后可移除 manual tag。

#### FEAT-001.nonexistent-tool-refusal — 不存在工具的 LLM 拒答
- **状态**：runnable（COMPLETED 正例，与 downstream-agent-killed 构成完整错误面覆盖）
- **FEAT 依据**：§5.1.6「COMPLETED 语义:任务已完成、无进一步动作」—— LLM 层的拒答仍属正常业务结论，应走 COMPLETED 路径，不应包装成 FAILED。
- **G**：deep-research 就绪。
- **W**：`SendStreamingMessage` 用一个"请调用 `__sit_fault_probe_nonexistent_tool__` 并读取结果"prompt。
- **T**：
  - **层 1**：终态 == COMPLETED（业务层拒答不走 failed 家族）
  - **层 2**：artifact 文本包含目标工具名 `__sit_fault_probe_nonexistent_tool__`（证明 LLM 认知到具体请求）
  - **层 3**：artifact 文本至少命中一个「工具不存在/不可用」关键词（`不存在` / `not exist` / `unavailable` / ...）—— 证明 LLM 给出了正确业务结论
- **PASS**：层 1 + 层 2 + 层 3 都满足。**FAIL**：终态非 COMPLETED（handler 把用户级问询误判为异常，违反 §5.1.6） / artifact 无工具名 / 无拒答关键词（可能是幻觉调用成功）。
- **框架落点**：[NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java)。

#### FEAT-001.get-task / get-task-not-found
- **状态**：runnable（DA-04 / DA-04.F 已覆盖）
- **框架落点**：[GetTaskTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/GetTaskTest.java)。

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
- **框架落点**：[PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java)（新增）。走底层 HTTP + JSON-RPC 串接 Set/Get/List/Delete → 再 Get 应 not-found；前置探针 capabilities.pushNotifications=false 时 assumeTrue 跳过。SIT 当前 skip（capabilities.pushNotifications=false，与 DA-01.C 一致）。

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
- **框架落点**：[EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java)（新增）。sync ack + REST 轮询两阶段；接受四种拒绝分支：send 阶段异常 / errorHandler 收到 Throwable / task 终态 FAILED 或 REJECTED / COMPLETED 但 artifact 空。SIT 已 PASS（拒绝分支命中）。

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
- **框架落点**：待新建（`TaskFailedPayloadTest`；触发条件与 §3.3 downstream-agent-killed-mid-stream 重叠，可复用 fixture），阻塞至评审 §6 定 code。

---

## 4. 框架落点汇总

| 子用例 ID | 落点 Java 类 | 状态 | 类状态 |
|---|---|---|---|
| agent-card | [AgentCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java) | runnable | 已落 |
| agent-card-public-base-url | [AgentCardPublicBaseUrlTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardPublicBaseUrlTest.java) | partial | 已落 |
| agent-card-capabilities | [AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) | runnable | 已落 |
| agent-card-skills | [AgentCardSkillsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardSkillsTest.java) | runnable | 已落 |
| jsonrpc-endpoint-slash | [JsonRpcEndpointSlashTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcEndpointSlashTest.java) | runnable | 已落 |
| jsonrpc-parse-error | [JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java) | runnable | 已落 |
| jsonrpc-invalid-request | [JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java) | runnable | 已落 |
| jsonrpc-method-not-found | [JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java) | runnable | 已落 |
| send-message-blocking | `SyncSendMessageTest` | runnable | 已有（DA-02） |
| send-streaming-message | `StreamingSendMessageTest` | runnable | 已有（DA-03） |
| downstream-agent-killed-mid-stream | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) | partial | 已落（watchdog + @manual） |
| nonexistent-tool-refusal | [NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java) | runnable | 已落 |
| get-task / get-task-not-found | `GetTaskTest` | runnable | 已有（DA-04 + F） |
| push-config-crud | [PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java) | runnable | 已落（capabilities false 时 assumeTrue skip） |
| webhook-completed | `WebhookCompletedTest` | **deferred** | 阻塞（评审 §3） |
| webhook-{failed,canceled,rejected} | `WebhookTerminalStateTest` | **deferred** | 阻塞（评审 §3） |
| webhook-payload-ref | `WebhookPayloadRefTest` | **blocked** | 阻塞（评审 §1 §3） |
| webhook-idempotent | `WebhookIdempotencyTest` | **blocked** | 阻塞（评审 §3 §4） |
| webhook-no-intermediate | 并入 `WebhookCompletedTest` | **deferred** | 阻塞（评审 §3） |
| webhook-untrusted-target | `WebhookUntrustedTargetTest` | partial | 待新建 |
| webhook-vs-streaming | `WebhookVsStreamingTest` | **deferred** | 阻塞（评审 §3） |
| tenant-id-propagation | `TenantIdPropagationTest` | partial | 待新建 |
| tenant-isolation | `TenantIsolationTest` | partial | 待新建 |
| empty-text-input | [EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java) | partial | 已落 |
| task-lifecycle | 扩展 `StreamingSendMessageTest` | runnable | 扩展 |
| task-failed-payload | `TaskFailedPayloadTest` | blocked | 阻塞（评审 §6） |

所有新建类落到 `src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/`，包 `com.huawei.ascend.sit.cases.integration.deepagent_deepresearch`。

### 4.1 落地优先级建议

> ✅ = 已落地；⬜ = 待落地。

**P0-A · 扩展现有 test（改口径 / 加断言）**
- ✅ `AgentCardDiscoveryTest`（DA-01；三入口等价性已硬断言）
- ✅ `StreamingSendMessageTest`（DA-03；+ 状态序列断言 → task-lifecycle）

**P0-B · JSON-RPC 错误面（新类，纯正/负路径，用底层 HTTP client）**
- ✅ `JsonRpcEndpointSlashTest`
- ✅ `JsonRpcParseErrorTest`
- ✅ `JsonRpcInvalidRequestTest`
- ✅ `JsonRpcMethodNotFoundTest`

**P0-C · SDK 方法扩展（新类，正路径）**
- ✅ `PushConfigCrudTest`（capabilities.pushNotifications=false 时 assumeTrue skip）

**P1 · Agent Card 完整性 + 场景化**
- ✅ `AgentCardCapabilitiesTest`
- ✅ `AgentCardSkillsTest`
- ✅ `AgentCardPublicBaseUrlTest`（partial）
- ⬜ `TenantIdPropagationTest`（partial）
- ⬜ `TenantIsolationTest`（partial，复用 DA-05/06 fixture）
- ✅ `EmptyTextInputTest`（partial）
- ⬜ `WebhookUntrustedTargetTest`（partial，只测注册拒绝）

**P2 · 依赖故障注入**
- 🟡 `DownstreamAgentKilledMidStreamTest`（watchdog + @manual；本地拉两 jar，用 SutStack.stop() 中途杀 search）
- ✅ `NonexistentToolRefusalTest`（§5.1.6 正例；LLM 拒答不存在工具走 COMPLETED）
- ⬜ `TaskFailedPayloadTest`

**Deferred · 阻塞至评审澄清 / 能力就绪**
- webhook 家族其余 6 条：`WebhookCompletedTest` / `WebhookTerminalStateTest` / `WebhookPayloadRefTest` / `WebhookIdempotencyTest` / `WebhookVsStreamingTest`

---

## 5. 运行方式

```bash
# 全部 FEAT-001 相关用例（跳过 @Tag("manual")）
./mvnw -Dtest.env=SIT -Dgroups=feat-001 test

# 指定单条子用例
./mvnw -Dtest.env=SIT -Dtest=PushConfigCrudTest test

# 强跑 manual 分支（含长任务等）
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
| §6 错误码未列 | `downstream-agent-killed-mid-stream` / `empty-text-input` / `task-failed-payload` |
| §7 缺 X-Tenant-Id 落点 | `tenant-id-propagation`（partial）+ `tenant-isolation`（partial） |

### 6.2 实现层风险（非评审风险）

**SDK 版本能力天花板**
- A2A SDK `1.0.0.Final` 的 `JSONRPCTransport.unmarshalResponse` 不按 JSON-RPC error code 分流具体子类——见 [DA-04 §9 备注](deepagent/DA-04-get-task.md)。协议错误 code 断言子用例（`jsonrpc-*` / `webhook-untrusted-target` 若拒绝走 error 分支）需要绕过 SDK，用底层 HTTP client 直接发。
- SDK 是否暴露 push config CRUD 的 Java API 需要在动工前先跑 smoke 探针；若 SDK 未包裹，用 `HttpClient` 直发 JSON-RPC payload（`PushConfigCrudTest` 走此路径）。

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

### 6.4 Failed / Rejected 触发条件

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