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
  - FEAT-001 特性文档（version-scope，外部契约）：`chaosxingxc-orion/spring-ai-ascend@experimental` → `version-scope/FEAT-001-standardized-agent-service-entrypoint.md`（**2026-07-24 v2 版本**；Push Notification Config CRUD 4 method 已从 §2 MUST 集下线,由 SendMessage 内联 pushNotificationConfig 承担;runtime-to-runtime callback receiver 端点新增为 MUST;callback 家族的范围/大载荷/幂等/安全边界/streaming 分离全部转 MUST）
  - FEAT-001 L2 设计文档（当前实现事实）：`chaosxingxc-orion/spring-ai-ascend@experimental` → `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-001-standardized-agent-service-entrypoint.md`（**2026-07-25 L2 版本**；§1.3 明确 params 缺项/类型错当前映射到 `-32603` internal error,预期 `-32602` invalid params —— 实现缺口;§2.1 endpoint 列表含 `POST /a2a/push-notifications/callback` receiver;§2.7 定义 receiver 契约含 `X-A2A-Notification-Id` 幂等 header;§6.4 composite capability check）
  - FEAT-001 评审与待澄清清单：[FEAT-001-standardized-agent-service-entrypoint-review.md](FEAT-001-standardized-agent-service-entrypoint-review.md)
  - 旧档：[deepagent/DA-01-agent-card-discovery.md](deepagent/DA-01-agent-card-discovery.md) ~ [DA-07-sandbox-tools.md](deepagent/DA-07-sandbox-tools.md)（增量沉淀之前 smoke，本档为 FEAT-001 覆盖全景视角，不废弃）
---

# FEAT-001 — deep-research 侧标准化 Agent 服务入口用例设计

> **一句话**：以 deep-research SUT 为对象，把 FEAT-001 §2 能力表里所有 MUST 项、§4 用户旅程和 §5.1.8 错误场景，映射为可在 SIT 侧黑盒断言的子用例；旧 DA-01~07 已覆盖的部分在本档表里显式标记，剩余部分是本档新增落点。

> **⚠️ 本文档已同步评审结论**（2026-07-09）：每条子用例带**状态**（runnable / partial / blocked / deferred）与**评审关联**列，映射到 [评审文档](FEAT-001-standardized-agent-service-entrypoint-review.md) 的 7 项待澄清项。blocked / deferred 项在特性文档未澄清前，实现阶段跳过。

> **⚠️ 2026-08-04 spec 变更同步**：本档跟进 version-scope FEAT-001 (2026-07-24 v2) + L2 (2026-07-25) 变更 —— 主要影响面:
> - **Push Notification Config CRUD 5 method(Set/Get/List/Update/Delete)已显式下线**为 `-32601 method-not-found`,`push-config-crud` 子用例已<b>方向反转</b>为断言拒绝(不再走 CRUD 全链路)。
> - **Callback receiver 端点新增为 MUST**(§2.1 `POST /a2a/push-notifications/callback` + §2.7 契约);原 webhook 家族 deferred 项转 runnable(需 SUT 部署 receiver 激活)。
> - **SendMessage 内联 pushNotificationConfig 承担异步接受**,新增 `inline-push-config-async-accept` + `inline-push-config-untrusted-host` 子用例。
> - **JSON-RPC `-32602 invalid-params`** 由 L2 §1.3 明示为实现缺口(当前落 `-32603`),新增 `jsonrpc-invalid-params` 子用例(red-first)。
> - **§5.1.7 状态语义忠实性反向断言**(信息齐全时不得误伪装 INPUT_REQUIRED)由新增 `input-required-fake-completed` 覆盖。
>
> **⚠️ 2026-08-09 双向 wire 抓包决定性证据**:CascadeCallbackRealSearchAgentHappyPathTest 用透明代理夹在 dr↔search-agent 之间(searchProxy)+ 让 DEEP_RESEARCH_PUBLIC_URL 指向反向代理(callbackProxy) 后,首次完整抓获 cascade push 双向 HTTP 载荷。结论:
> - **outbound OK**: dr 发出 SendMessage 携带完整 `taskPushNotificationConfig{url,id,token}`,`url = DEEP_RESEARCH_PUBLIC_URL/a2a/push-notifications/callback`,response 200。
> - **反向 callback OK**: sub-agent 完成任务后反向 POST `/callback`,body 含正确 taskId + COMPLETED state + artifacts;Authorization Bearer token echo 回来一致;dr receiver 返 200 `{"status":"accepted",...}`。
> - **真 gap 在 auto-resume**: 即使前 4 步全通,caller parent task 仍永不 resume 到 COMPLETED(90s 超时)。dr 收下 callback 却未把 sub-agent 结果 wire 回 ReAct 循环 emit terminal state。该 gap 属 FEAT-004 中断-续接语义域,不属 FEAT-001 入口面,本档新增 `cascade-callback-real-search-happy-path` 子用例作端到端 smoke 兜底(assertion 4 常红)。
> - **BUG-009 outbound gap 结论翻案**:早期基于 log-grep 推断"outbound 未 wire"错误,应关闭。方法学教训:SUT log 不齐全时,优先抓 HTTP wire (`TransparentA2AProxy` 是通用工具)。

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
| JSON-RPC invalid params(-32602) | `FEAT-001.jsonrpc-invalid-params` | 已覆盖（[JsonRpcInvalidParamsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidParamsTest.java)） | partial | — | params=[] 场景 runnable；结构合法但字段缺项/类型错场景 red-first（L2 §1.3 实现缺口:当前落 -32603,预期 -32602） |
| 阻塞 `SendMessage` | `FEAT-001.send-message-blocking` | DA-02 覆盖 | runnable | — | 已覆盖 |
| 流式 `SendStreamingMessage` | `FEAT-001.send-streaming-message` | DA-03 覆盖 | runnable | — | 已覆盖 |
| Stream 中途下游 agent 被杀 | `FEAT-001.downstream-agent-killed-mid-stream` | 已覆盖（[DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java)，watchdog + manual） | partial | 评审 §6 | 用 SutStack.stop() 中途杀 search 触发 handler runtime exception；层 1（终态 ∈ failed/canceled/rejected）+ 层 2（结构化 payload）为硬 MUST，不受 §6 影响；jar 就绪前 @manual |
| 不存在工具的 LLM 拒答 | `FEAT-001.nonexistent-tool-refusal` | 已覆盖（[NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java)） | runnable | — | §5.1.6 正例：LLM 收到虚构工具请求应 COMPLETED 且回答包含工具名 + 「不存在/不可用」关键词；与 downstream-agent-killed 构成完整错误面覆盖 |
| `GetTask` 快照 | `FEAT-001.get-task` | DA-04 覆盖 | runnable | — | 已覆盖 |
| `GetTask` 负路径（TaskNotFound） | `FEAT-001.get-task-not-found` | DA-04.F 覆盖 | runnable | — | 已覆盖 |
| Push Notification config CRUD 应显式排除 | `FEAT-001.push-config-crud` | 已覆盖（[PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java)） | runnable | — | **2026-08-04 spec 反转**:5 method 都应返 -32601(v2 §2 显式排除 + L2 §2.3.1) |
| SendMessage 内联 pushNotificationConfig 异步接受 | `FEAT-001.inline-push-config-async-accept` | 已覆盖（[InlinePushConfigAsyncAcceptTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigAsyncAcceptTest.java)） | runnable | — | v2 §2 新增 MUST;非阻塞 15s 内回 Task 骨架(非 COMPLETED);`@manual`(需 LLM) |
| SendMessage 内联 config 未受信 host 拒绝 | `FEAT-001.inline-push-config-untrusted-host` | 已覆盖（[InlinePushConfigUntrustedHostTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigUntrustedHostTest.java)） | runnable | 评审 §2 | v2 §2 「callback 安全边界」MUST;`.example` TLD 保证未信任 |
| Callback receiver 端点契约 | `FEAT-001.push-notification-callback-receiver` | 已覆盖（[PushNotificationCallbackReceiverTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationCallbackReceiverTest.java)） | runnable | 评审 §3(已澄清) | L2 §2.1/§2.7 定义 endpoint + 契约;正例/malformed/unauthorized/capability-gate 四场景 |
| Callback 幂等(notificationId) | `FEAT-001.push-notification-idempotency` | 已覆盖（[PushNotificationIdempotencyTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationIdempotencyTest.java)） | runnable | 评审 §4(已澄清) | X-A2A-Notification-Id header;同 id 同 payload → 200/202/409;同 id 不同 payload → 409 |
| capabilities ⇔ callback endpoint composite check | `FEAT-001.agent-card-callback-composite` | 已覆盖（[AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) 扩展） | runnable | — | L2 §6.4:pushNotifications=true 时 callback endpoint 必须存在(非 404/501) |
| Cascade push 端到端 real-search happy-path | `FEAT-001.cascade-callback-real-search-happy-path` | 已覆盖（[CascadeCallbackRealSearchAgentHappyPathTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/CascadeCallbackRealSearchAgentHappyPathTest.java)，双向 wire 抓包 + `@manual`） | partial | 2026-08-09 wire 证据 | Assertion 1-3 断言 outbound pushConfig + 反向 callback + 200 accepted 全通(green);Assertion 4 断言 caller task COMPLETED —— **FEAT-004 auto-resume gap 常红** |
| `X-Tenant-Id` 头传递 | `FEAT-001.tenant-id-propagation` | 未覆盖 | partial | 评审 §7 | 缺 header 落点未定 |
| Tenant 跨租户记忆隔离 | `FEAT-001.tenant-isolation` | 未覆盖 | partial | 评审 §7 | 间接证据（DA-05/06 记忆链路衍生） |
| 空文本输入拒绝 | `FEAT-001.empty-text-input` | 已覆盖（[EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java)，本地 2026-07-20 PASS） | partial | — | §5.1.6 反推：断"不得伪装 completed"下限；A/B/C/D 任一拒绝分支合规，唯一 FAIL 分支是 D-COMPLETED+artifact 非空 |
| §5.1.7 状态语义忠实性反向断言 | `FEAT-001.input-required-fake-completed` | 已覆盖（[InputRequiredFakeCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InputRequiredFakeCompletedTest.java)） | runnable | — | v2 §5.1.7 反向:信息齐全 prompt 不应首轮 INPUT_REQUIRED;dual-stack + `@manual`(需 LLM) |
| Task 生命周期状态序列 | `FEAT-001.task-lifecycle` | 已覆盖（[StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java) 已扩显式状态序列 + 严格顺序 + 无回退断言） | runnable | — | 严格顺序 + 无回退硬断言 |
| Failed Task 携带结构化错误 payload | `FEAT-001.task-failed-payload` | 已覆盖（[TaskFailedPayloadTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/TaskFailedPayloadTest.java)，watchdog + manual；当前 SUT 阶段预期红） | partial | 评审 §6 | §5.1.6「**可供客户端程序化判断**」+ §5.1.8「结构化错误 payload」；本用例层 3 断"DataPart / JSON TextPart / metadata 约定 key 三选一"作为程序化判断信号；开发组尚未落实结构化 shape 前预期红 —— 红即证明 SUT 与 spec 存在 gap，评审 §6 定字段后 SUT 补齐即绿 |

> **待决**：input-required 子用例（`FEAT-001.input-required`）待 deep-research planner 代码检查后决定是否列入（见 §6.3）。

> **不在本档范围**（对齐 FEAT-001 §5.2 + version-scope §2 MUST 集）：`CancelTask` / `ListTasks` / `SubscribeToTask`（不在 version-scope §2 MUST 集，method-not-found 返 `-32601` 合规）、多 Agent 路由、租户认证、gRPC、普通-client webhook 自报 URL、webhook 中间态订阅、webhook token 流、webhook HITL 继续执行、非文本输入、强制中断 LLM、outbound 远程 Agent 编排、agent-bus 私有入口、认证授权协议。

### 1.1 状态分布快照

> **2026-08-04 spec sync 后**:v2 §2 把 callback receiver 从 deferred/blocked 类别 (评审 §3/§4) 转 MUST,原 webhook 家族 6 条 deferred/blocked → 4 条 runnable + 2 条落入新 callback 家族;新增 5 条(inline config × 2 + callback × 2 + input-required-fake-completed + jsonrpc-invalid-params + composite check),减少 1 条(push-config-crud 反转但仍占 1 位)。
> **2026-08-09 wire evidence 补充**:新增 D7 `cascade-callback-real-search-happy-path` 端到端 smoke(partial · 双向 wire 抓包 + FEAT-004 gap 兜底)。

| 状态 | 数量 | 说明 |
|---|---|---|
| runnable | 20 | 可直接落地(含 callback receiver / 内联 config / composite check 等新 spec 项) |
| partial | 10 | 主路径可测,某维度受评审限制(含 jsonrpc-invalid-params 的 L2 §1.3 red-first 场景 + F5 层 3 预期红 + D7 assertion 4 FEAT-004 auto-resume gap 常红) |
| blocked | 0 | 原 blocked 项(payload-ref / idempotent)因 v2 落地 receiver 契约后转 runnable / 直接由新用例承担 |
| deferred | 2 | 剩余 webhook 家族 mid-state / token-stream 明示 OUT,不再列入本档 |

**落地优先级**：runnable → partial → 评审澄清后 → blocked / deferred。

### 1.2 覆盖进度看板

> 最新真机进展与缺陷对时见 §7（滚动记录）。

> **用法**：随开发推进直接改 ✅ / ⬜ 状态位；子用例语义已在 §3 展开，此表只做单页进度对照。
> **图例**：✅ 已落地并 PASS；🟡 已落地但 partial（受评审 / SUT 限制）；⬜ 待落地；🚫 阻塞（评审 / 能力）；⏸ deferred（能力缺失）

| 类别 | ID | 子用例 | 状态 | 落点 |
|---|---|---|---|---|
| **A. Agent Card 发现（4）** | A1 | agent-card 双入口 | ✅ | [AgentCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardDiscoveryTest.java)（三入口等价性硬断言） |
| | A2 | agent-card-public-base-url | 🟡 | [AgentCardPublicBaseUrlTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardPublicBaseUrlTest.java) |
| | A3 | agent-card-capabilities | ✅ | [AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) |
| | A4 | agent-card-skills | ✅ | [AgentCardSkillsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardSkillsTest.java) |
| **B. JSON-RPC 错误面（6）** | B1 | jsonrpc-endpoint-slash | ✅ | [JsonRpcEndpointSlashTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcEndpointSlashTest.java) |
| | B2 | jsonrpc-parse-error | ✅ | [JsonRpcParseErrorTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcParseErrorTest.java) |
| | B3 | jsonrpc-invalid-request | ✅ | [JsonRpcInvalidRequestTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidRequestTest.java) |
| | B4 | jsonrpc-method-not-found | ✅ | [JsonRpcMethodNotFoundTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcMethodNotFoundTest.java) |
| | B5 | jsonrpc-id-preserved | ✅ | 并入 B2 / B3 / B4 |
| | B6 | jsonrpc-invalid-params | 🟡 | [JsonRpcInvalidParamsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidParamsTest.java)(params=[] runnable;结构合法但字段错 red-first L2 §1.3) |
| **C. 核心 A2A 方法（5）** | C1 | send-message-blocking | ✅ | SyncSendMessageTest（DA-02） |
| | C2 | send-streaming-message | ✅ | StreamingSendMessageTest（DA-03） |
| | C3 | downstream-agent-killed-mid-stream | 🟡 | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java)(watchdog + @manual;层 1 绿,层 2 expected-red · [BUG-005](../bugs/BUG-005-remote-agent-failure-not-propagated-to-task-status-message.md)) |
| | C4 | get-task / not-found | ✅ | GetTaskTest（DA-04 + F） |
| | C5 | nonexistent-tool-refusal | ✅ | [NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java) |
| **D. Push Config / Callback 家族(6, 2026-08-04 spec 反转 + 新增)** | D1 | push-config-crud(反转:5 method 应返 -32601) | ✅ | [PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java) |
| | D2 | inline-push-config-async-accept | ✅ | [InlinePushConfigAsyncAcceptTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigAsyncAcceptTest.java)(`@manual`) |
| | D3 | inline-push-config-untrusted-host | ✅ | [InlinePushConfigUntrustedHostTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigUntrustedHostTest.java) |
| | D4 | push-notification-callback-receiver | ✅ | [PushNotificationCallbackReceiverTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationCallbackReceiverTest.java) |
| | D5 | push-notification-idempotency | ✅ | [PushNotificationIdempotencyTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationIdempotencyTest.java) |
| | D6 | agent-card-callback-composite | ✅ | [AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) 扩展 |
| | D7 | cascade-callback-real-search-happy-path (端到端 smoke + FEAT-004 gap 兜底) | 🟡 | [CascadeCallbackRealSearchAgentHappyPathTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/CascadeCallbackRealSearchAgentHappyPathTest.java)(双向透明代理 wire 抓包;assertion 1-3 绿 · assertion 4 [FEAT-004](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md) auto-resume gap 常红 · `@manual`) |
| **E. Webhook 家族剩余项(2 项 deferred / OUT)** | E1 | webhook-vs-streaming | ⏸ | 剩余 mid-state / streaming 分离细节等 SUT 侧联测形态明确后再列 |
| | E2 | webhook-payload-ref | ⏸ | v2 §2 承接为 MUST 但 SUT 侧阈值/落地形态未确认,等联测 |
| **F. Tenant / 输入 / 生命周期(6)** | F1 | tenant-id-propagation | ⬜ | TenantIdPropagationTest |
| | F2 | tenant-isolation | ⬜ | TenantIsolationTest |
| | F3 | empty-text-input | 🟡 | [EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java)(§5.1.6 反推:任一拒绝分支合规,仅 FAIL 于 COMPLETED+artifact 非空) |
| | F4 | task-lifecycle | ✅ | [StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java#L117-L133)(DA-03 扩展:严格顺序 + 无回退硬断言,已 PASS) |
| | F5 | task-failed-payload | 🟡 | [TaskFailedPayloadTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/TaskFailedPayloadTest.java)(watchdog + @manual;层 1/2 硬 MUST,层 3「程序化判断」当前 SUT 阶段**预期红**) |
| | F6 | input-required-fake-completed | ✅ | [InputRequiredFakeCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InputRequiredFakeCompletedTest.java)(v2 §5.1.7 反向,dual-stack + @manual) |

**进度**(2026-08-09 wire evidence 后):已落地 25 / 32(其中 ✅ 硬 PASS 20、🟡 partial 5);⬜ 待落地 2(tenant 双条);⏸ deferred 2(mid-state / payload-ref 联测形态待定)。

**下一步优先级**:
1. **P0** 等 jar 到位后跑通新增 8 条测试(P0×3 / P1×3 / P2×2),验证 SUT 侧 v2 spec 实现程度 —— 预期 jsonrpc-invalid-params 结构场景 + push-callback-receiver auth 场景 red-first
2. **P1** ⬜ F1/F2 tenant 双条(依赖 §7 澄清 X-Tenant-Id 落点)
3. **P2** 🟡 C3 downstream-agent-killed-mid-stream / F5 task-failed-payload(本地 jar 就绪 + 验证 SEARCH_AGENT_URL env 生效后移除 @manual)—— F5 层 3 预期红,等 SUT 补齐结构化 payload
4. **Deferred** ⏸ webhook mid-state / payload-ref 剩余细节等 SUT 联测形态明确

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

#### FEAT-001.jsonrpc-invalid-params — params 缺项/类型错 → -32602(2026-08-04 新增)
- **状态**:partial(params=[] runnable;结构合法但字段错为 L2 §1.3 明示实现缺口,red-first)
- **FEAT 依据**:version-scope §5.1.2 承诺 shape-level 错误统一走 JSON-RPC error code(不吞、不 500);L2 §1.3 明确「参数缺项/类型不匹配当前会映射到 `-32603` internal error,预期为 `-32602 invalid params`(待补)」;L2 §2.3.1 错误码表归位。
- **G**:deep-research 就绪。
- **W**(4 payload):
  1. `params=[]`(空数组,SendMessage 无法解码 → -32602);
  2. `params={}`(缺 message);
  3. `params.message.parts` 非数组(应为数组给字符串);
  4. `params.message.role` 非法枚举("OWNER")。
- **T**:4 次响应都:
  1. HTTP 200 + body 含 error;
  2. `error.code == -32602`(严格断言);
  3. 若返 `-32603`,输出 stderr 诊断行标注为 L2 §1.3 实现缺口 red-first(不 relax 断言,补齐后自然通过)。
- **PASS**:4 payload 都 -32602。**FAIL**(spec-gap 类):任一返 -32603(现状预期);**FAIL**(SUT-崩溃类):HTTP 5xx / 无 error / -32603 之外的错值。
- **框架落点**:[JsonRpcInvalidParamsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidParamsTest.java)。

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
- **状态**:partial · **层 1 绿 · 层 2 expected-red · [BUG-005](../bugs/BUG-005-remote-agent-failure-not-propagated-to-task-status-message.md)**(watchdog 已落;本地拉起两 jar,用 `SutStack.stop()` 中途杀 search 触发;jar 就绪前 @manual)
- **评审关联**:§6 —— 具体 `error.code` 值断不了;但**层 1 / 层 2 是 spec 明文 MUST**,不受 §6 影响
- **FEAT 依据**:§5.1.4「stream 必须关闭 + 以 failed 收束」+ §5.1.6「COMPLETED 语义:任务已完成」+ §5.1.8「handler runtime exception → failed Task + 结构化错误 payload」。
- **G**:deep-research + search 两 jar 本地就绪(`~/.m2/repository/com/openjiuwen/example/`);框架拉起 search 后再拉起 deep-research 并把 search baseUrl 通过 `SEARCH_AGENT_URL` 环境变量注入。
- **W**:`SendStreamingMessage` 发一个明确需要 search 的 prompt;等待 deep-research 进入 WORKING 状态 + 一小段 grace period(让 tool call 真正打给 search)后调 `SutStack.stop("search")`;收集所有 SSE frame + terminal Task。
- **T**:
  - **层 1**(§5.1.4 + §5.1.6 + §5.1.8):stream 终态 ∈ {FAILED, CANCELED, REJECTED} —— **COMPLETED 视为 FAIL**(agent 无法完整回答用户却包装成成功,违反 spec)—— ✅ 2026-07-23 issue-42 修复后当前绿
  - **层 2**(§5.1.8):终态 Task 携带结构化错误 payload(`status.message.parts` 非空)—— ❌ 2026-07-23 观测红:`task.status.message == null`;根因是 REMOTE_ERROR 稳定码未分派 + payload 未落 wire,归为 [BUG-005](../bugs/BUG-005-remote-agent-failure-not-propagated-to-task-status-message.md)
  - **不断言**:具体 `error.code` / 错误消息措辞
- **PASS**:层 1 + 层 2 都满足。**当前 FAIL(BUG-005)**:层 2 红 —— SUT 侧 REMOTE_ERROR 路径既没分派稳定码也没落 payload。修复方向见 [BUG-005 §6](../bugs/BUG-005-remote-agent-failure-not-propagated-to-task-status-message.md)。
- **框架落点**:[DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java);姐妹用例 [RemoteSseAbortFalseCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteSseAbortFalseCompletedTest.java) 承载 issue-42 层 1 watchdog。标 `@Tag("manual")`:需要本地 deep-research + search 两 jar,CI 环境默认不具备;jar 就绪且 SEARCH_AGENT_URL env 注入验证生效后可移除 manual tag。

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

### 3.4 Push Notification config CRUD 应显式排除（2026-08-04 spec 反转）

#### FEAT-001.push-config-crud — 5 method 都应返 -32601
- **状态**:runnable
- **FEAT 依据**:v2 §2 明确 4 个 config CRUD method(Set/Get/List/Update/Delete TaskPushNotificationConfig)+ 老规范里的 `Delete` 共 5 method<b>从 §2 MUST 集下线</b> —— 现规范只保留 SendMessage 内联 pushNotificationConfig 承担 sender 侧意向声明,不再暴露独立 config CRUD method。
- **L2 归位**:L2 §2.3.1 错误码表明确"显式排除的 method 应返 `-32601 method-not-found`,不允许静默 200、也不允许返 `-32603` internal error"。
- **G**:deep-research 就绪(无论 `capabilities.pushNotifications` 值为何)。
- **W**:对 5 个 method 各发一个 params 结构基本合规的 dummy request(让 dispatcher 过 params-shape 层再判 method)。
- **T**:5 次响应都:
  1. HTTP 200(JSON-RPC error 在 body 里);
  2. body 含 error;
  3. `error.code == -32601`(非 `-32603`,后者说明 SUT 走了 internal-error 兜底而非显式 method 排除)。
- **PASS**:5 method 全部 -32601。**FAIL**:任一 method 返 result / -32603 / 5xx / 或返 result 说明 method 未下线,违反 v2 §2。
- **框架落点**:[PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java)(2026-08-04 <b>方向反转</b>,原「5 步 CRUD 全链路成功」断言删除,现「5 method 拒绝」断言)。

### 3.5 Runtime-to-runtime Callback 家族（2026-08-04 v2 spec 落地）

> **⚠️ 2026-08-04 变更**:v2 §2 把 callback receiver 从 deferred/blocked 转 MUST —— 增加 `POST /a2a/push-notifications/callback` 端点 + `X-A2A-Notification-Id` 幂等 header + capability composite check。原 webhook-* 家族的 6 条 deferred/blocked 项:
> - `webhook-untrusted-target` → **升级为 `inline-push-config-untrusted-host`**(§3.5.b);
> - `webhook-idempotent` → **升级为 `push-notification-idempotency`**(§3.5.d),不再阻塞;
> - `webhook-completed / failed / canceled / rejected` → 合并入 `push-notification-callback-receiver`(§3.5.c) 正例场景;
> - `webhook-payload-ref` → v2 §2 承接但 SUT 侧阈值/落地形态未确认,继续 deferred 至联测;
> - `webhook-vs-streaming / no-intermediate` → 已明示为 OUT(v2 §5.2);
> - 新增:`inline-push-config-async-accept`(§3.5.a) + `agent-card-callback-composite`(§3.5.e)。

#### 3.5.a FEAT-001.inline-push-config-async-accept — SendMessage 内联 config 非阻塞返回
- **状态**:runnable(`@manual`,需 LLM)
- **FEAT 依据**:v2 §2「SendMessage 支持内联 pushNotificationConfig 携带,SUT 应立即返回 Task 骨架(非阻塞语义),后续状态迁移由 callback 交付」;L2 §2.7 依赖 SUT 侧不再"卡住 sendMessage" 等 Task 完成。
- **G**:deep-research 就绪。
- **W**:`POST /a2a` 发 `SendMessage`,params 内含 `pushNotificationConfig={url, token}`(SIT placeholder URL,SUT 不应尝试连接)。
- **T**:
  1. HTTP 200 + 无 error;
  2. response 返回耗时 ≤ 15s(远小于 LLM handler 完整执行时长);
  3. result 是 Task 骨架且 `status.state` ∈ {SUBMITTED, WORKING, INPUT_REQUIRED},**非 COMPLETED**。
- **PASS**:三条全满足。**FAIL**:超时(阻塞等 handler) / COMPLETED(阻塞等完) / error(SUT 未接受内联 config)。
- **框架落点**:[InlinePushConfigAsyncAcceptTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigAsyncAcceptTest.java)。

#### 3.5.b FEAT-001.inline-push-config-untrusted-host — 未受信任 host 入口拒绝
- **状态**:runnable
- **FEAT 依据**:v2 §2「callback 安全边界」MUST + L2 §2.3.1 归位 `-32602 invalid-params` 或实现层 trust-policy 专属错误码。
- **G**:deep-research 就绪。
- **W**:`SendMessage` params 内含 `pushNotificationConfig.url = http://sit-untrusted.example/webhook`(`.example` TLD 由 RFC 2606 保留,不可能出现在任何真实 trusted hosts 白名单)。
- **T**:
  1. HTTP 200 + body 含 error(不静默接受);
  2. `error.code` ∈ {-32602, -32001~-32099}(invalid-params 首选,实现层 trust-policy 专属码次选);
  3. `error.message` / `error.data` 含 trust-policy 语义关键词(trust / host / whitelist / callback / policy / 信任 / 白名单 / 受信 / 回调)。
- **PASS**:三条全满足。**FAIL**:静默接受(违约) / 5xx / 无 trust-policy 语义(拒绝原因不可诊断)。
- **框架落点**:[InlinePushConfigUntrustedHostTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigUntrustedHostTest.java)。

#### 3.5.c FEAT-001.push-notification-callback-receiver — Receiver 端点契约
- **状态**:runnable(capability off 时正例分支 assumeTrue skip)
- **FEAT 依据**:v2 §2 「runtime 必须暴露 `POST /a2a/push-notifications/callback` 接收上游 runtime 的 task 状态回调」;L2 §2.1 endpoint 表 + §2.7 契约。
- **G**:deep-research 就绪;读 `capabilities.pushNotifications` 作为 gate 参考。
- **W**:直接 POST 到 `/a2a/push-notifications/callback`,分 4 场景:
  1. 正例:合法 body + `X-A2A-Notification-Id` header;
  2. malformed body:非法 JSON;
  3. unauthorized:显式错误 `Authorization: Bearer sit-invalid-token-*`;
  4. capability gate:capabilities=false 时应 404/501,capabilities=true 时不应 404/501。
- **T**:
  - 正例 → 200 或 202;
  - malformed → 400/422;
  - unauthorized → 401/403(spec §2.7;若 200/202 说明 SUT 未启用 callback auth 校验,spec-gap red-first);
  - capability gate:capabilities=true 时非 404/501 / capabilities=false 时非 200/202(§6.4 composite check)。
- **PASS**:四条全满足。**FAIL**:capabilities=true 却 404/501(声明夸大能力,§6.4 违约) / capabilities=false 却 200/202(能力泄漏)。
- **框架落点**:[PushNotificationCallbackReceiverTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationCallbackReceiverTest.java)。

#### 3.5.d FEAT-001.push-notification-idempotency — Callback 幂等
- **状态**:runnable(capability off 时 assumeTrue skip)
- **FEAT 依据**:L2 §2.7「`X-A2A-Notification-Id` header 与 body notificationId 一致,作幂等键;同 id + 同 payload → 200/202/409;同 id + 不同 payload → 409 conflict,禁止 silent overwrite」。
- **G**:deep-research 就绪 + `capabilities.pushNotifications=true`(否则 assumeTrue skip)。
- **W**:
  1. `POST /a2a/push-notifications/callback` 首投(200/202);
  2. 相同 id + 相同 payload 重投;
  3. 相同 id + 不同 `status.state` 重投。
- **T**:
  1. 首投 200/202;
  2. 相同 payload 重投 ∈ {200, 202, 409}(等价幂等,SUT 二选一);
  3. 不同 payload 重投必须 409(禁止 silent overwrite)。
- **PASS**:三条全满足。**FAIL**:任一 5xx / 不同 payload 却 200/202(overwrite 违约)。
- **框架落点**:[PushNotificationIdempotencyTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationIdempotencyTest.java)。

#### 3.5.e FEAT-001.agent-card-callback-composite — capabilities ⇔ callback endpoint composite check
- **状态**:runnable
- **FEAT 依据**:L2 §6.4「composite capability check」—— `capabilities.pushNotifications=true` 是复合声明,意味着 SUT 同时具备 callback receiver 端点可达 + delivery/store handler 已注入。
- **G**:deep-research 就绪。
- **W**:读 Agent Card 得 `capabilities.pushNotifications`;直接 POST 到 `/a2a/push-notifications/callback` 探端点。
- **T**:
  - 声明 true → endpoint 不允许 404/501(§6.4 违约);
  - 声明 false → endpoint 不应 200/202(能力泄漏)。
- **PASS**:两分支之一满足。**FAIL**:声明 true 却 404/501(夸大)/ 声明 false 却 200/202(泄漏)。
- **框架落点**:[AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) 扩展的 `capabilityImpliesCallbackReachability` 方法。

#### 3.5.f FEAT-001.cascade-callback-real-search-happy-path — Cascade push 端到端 wire 抓包(2026-08-09 新增)
- **状态**:partial(assertion 1-3 绿 · assertion 4 [FEAT-004](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md) auto-resume gap 常红 · `@manual`,需 LLM + 两 jar)
- **FEAT 依据**:v2 §2 「SendMessage 内联 pushNotificationConfig 的 sender 侧承担」+「callback receiver MUST」双端;本用例是<b>端到端 smoke</b>,把 3.5.a(inline config accept)+ 3.5.c(receiver 契约)+ 3.5.e(composite check)串成一条链路,并同时暴露 [FEAT-004](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md) auto-resume gap。
- **为何在 FEAT-001 而非纯 FEAT-004 承接**:
  - 前 3 条 assertion(outbound pushConfig 完整 + 反向 callback fire + receiver 200 accepted)属 FEAT-001 服务入口面的<b>wire 契约</b>,不是 continuation 语义;
  - 第 4 条 assertion(caller task COMPLETED)则跨界到 FEAT-004(中断-续接语义域);
  - 本档承接 assertion 1-3,并把 assertion 4 作为<b>红色兜底</b>标记 FEAT-004 gap 存在 —— 修复责任在 FEAT-004 承接,不在本档扩断言范围。
- **G**:
  - deep-research + search 两 jar 就绪;
  - `SEARCH_AGENT_USE_STUB=true`(绕过 Tavily 依赖);
  - `SEARCH_AGENT_PUSH_NOTIFICATIONS=true` + `DEEP_RESEARCH_PUSH_NOTIFICATIONS=true`;
  - `DEEP_RESEARCH_CALLBACK_TOKEN` 已注入(shared secret,验签依据);
  - **双向 [TransparentA2AProxy](../../src/test/java/com/huawei/ascend/sit/mock/TransparentA2AProxy.java)**:
    - `searchProxy` 夹在 dr → search 出站方向(dr 通过 proxy 打 search 的 agent-card / SendMessage);
    - `callbackProxy` 夹在 search → dr 反向方向(通过让 `DEEP_RESEARCH_PUBLIC_URL` 指向 callbackProxy,让 search 的反向 callback 也过一遍代理)。
- **W**:发一个明确需要 search 的 prompt(如 Python 官网 URL 查询) + `pushNotificationConfig` 指向 dr 自身 receiver;等 90s 轮询 caller task 状态。
- **T**(4 assertion):
  - **Assertion 1(outbound wire)**:`searchProxy.exchanges()` 应至少捕获 1 次 dr → search 的 `SendMessage`,body 内含<b>完整</b> `taskPushNotificationConfig{url, id, token}`,其中 `url == DEEP_RESEARCH_PUBLIC_URL + /a2a/push-notifications/callback`,response 200。
  - **Assertion 2(reverse callback fire)**:`callbackProxy.exchanges()` 应至少捕获 1 次 search → dr 的 `POST /a2a/push-notifications/callback`,body `result.task.id == <subTaskId>`(与 assertion 1 response 里的 taskId 一致),`status.state == TASK_STATE_COMPLETED`,`Authorization: Bearer <same token>` header 存在,`X-A2A-Notification-Id` header 存在。
  - **Assertion 3(receiver accept)**:上一步 callback POST 的 response status 应为 200 且 body 含 `{"status":"accepted", "notificationId":...}`(dr receiver 校验通过 + 落库)。
  - **Assertion 4(caller task terminal)**:轮询 dr 侧 `GetTask(callerTaskId)` 90s,finalState 应 == `TASK_STATE_COMPLETED`。**该 assertion 常红(FEAT-004 auto-resume gap)** —— dr 收下 callback 且 200 accepted,但未把 sub-agent 结果 wire 回 caller ReAct 循环,caller task 永不 resume。
- **PASS**:4 条 assertion 都绿(需要 FEAT-004 修复才可能)。**Expected FAIL**:assertion 1-3 绿 + assertion 4 90s 超时 —— 意味着 wire 契约完全通,gap 只在 auto-resume。**FAIL(SUT 退化)**:assertion 1-3 任一红 —— 之前 wire 证据已通,若回退表示 SUT 侧 outbound pushConfig 落 / 反向 callback fire / receiver auth 存在退化。
- **不断言**:artifact 内容真伪(用 stub search)/ 精确耗时。
- **框架落点**:[CascadeCallbackRealSearchAgentHappyPathTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/CascadeCallbackRealSearchAgentHappyPathTest.java) + [TransparentA2AProxy](../../src/test/java/com/huawei/ascend/sit/mock/TransparentA2AProxy.java);`@Tag("manual")`(需两 jar + LLM key)。
- **方法学教训**:SUT log 不齐全时,优先抓 HTTP wire(TransparentA2AProxy 是通用工具),不要用 log-grep 推断 gap 位置 —— 早期 BUG-009 outbound 结论就是 log-grep 误判,双向 wire 抓包后翻案。

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

#### FEAT-001.empty-text-input — 空文本输入拒绝（§5.1.6 反推）
- **状态**：partial（spec 只承诺"不得伪装 completed"下限，任何拒绝分支合规）
- **评审关联**：— （§5.1.7 不管空输入，§5.1.8 表无 empty-input 条目；本条不受 §6 影响）
- **FEAT 依据**：§5.1.6「handler 输出需要用户输入的中断时，Task 必须进入 input-required 类语义，而不是伪装成 completed」—— 空 TextPart 属"没有用户输入实质"边界情况，从该精神反推：空输入不得被处理成 COMPLETED+agent 猜答案。§5.1.7 只谈 metadata，未提空输入；§5.1.8 表无 empty-input / no-content 条目；§5.1.2 只覆盖 A2A wire shape 校验（空 TextPart 是合法 shape，不落 invalid-request）。
- **G**：deep-research 就绪。
- **W**：`SendMessage`，parts = `[new TextPart("")]`。
- **T**：runtime 落进任一拒绝分支（A/B/C/D）：A—SDK 客户端 shape 校验同步抛异常；B—服务端 JSON-RPC 拒绝走异步 errorHandler；C—无 sync ack / 无 taskId；D—task 终态 FAILED/REJECTED/CANCELED **或** COMPLETED+空 artifact。
- **PASS**：任一拒绝分支。**FAIL**：D-COMPLETED 且 artifact 非空（§5.1.6 明文禁止的"伪装 completed"）。**INCONCLUSIVE**：SUT 不可达。
- **不断言**：具体 HTTP status（400 vs 200+error body）/ 具体 error code / 具体异常类的全限定名 / 具体分支值 —— 这些都是 spec 未承诺项，钉具体分支等价于把 SUT 现状当契约。
- **本地事实备忘**（不作为断言，仅供 SUT 侧调查）：本机 2026-07-20 观察 SUT 走分支 A（`A2AClientException: HTTP 400`）；spec 允许，但 SUT 内部若把校验从 send 挪到 handler 层也合规，SIT 不应因此漂移。
- **框架落点**：[EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java)。本地 2026-07-20 PASS。

### 3.7 Task 生命周期

#### FEAT-001.task-lifecycle — 状态序列 submitted → working → terminal
- **状态**：runnable（已落地并 PASS）
- **FEAT 依据**：§5.1.6。
- **G**：deep-research 就绪。
- **W**：`SendStreamingMessage` 并按序记录每个 SSE 事件的 task.status.state。
- **T**：序列至少包含 `SUBMITTED` → `WORKING` → `COMPLETED`（或其他 terminal）；状态严格单调。
- **PASS**：序列合法。**FAIL**：跳过 WORKING 直到 COMPLETED / 状态回退。
- **框架落点**：[StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java#L117-L133) 已扩展：在 DA-03 原有 `contains(SUBMITTED/WORKING/COMPLETED)` 三条断言之上，新增 (a) 严格顺序断言 —— SUBMITTED 首现 index < WORKING 首现 index < COMPLETED 首现 index；(b) 无回退断言 —— COMPLETED 首现之后的子序列不再出现 SUBMITTED / WORKING。SIT 已 PASS。

#### FEAT-001.task-failed-payload — Failed Task 携带可程序化判断的结构化错误
- **状态**：partial（已落地 [TaskFailedPayloadTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/TaskFailedPayloadTest.java)，watchdog + `@manual`；**当前 SUT 阶段层 3 预期红**）
- **评审关联**：§6 —— 具体 error code / 字段命名（`error.code` vs `type` 等）由评审 §6 定；本用例故意不硬钉字段名
- **FEAT 依据**：§5.1.6「handler 输出 `FAILED` 或执行异常时必须形成 failed Task 表面，并携带**可供客户端程序化判断**的错误信息」+ §5.1.8 表「handler/runtime exception → failed Task + **结构化错误 payload**」。
- **与 C3 的判定差异**：C3 层 2 只断 `status.message.parts` **非空**；F5 层 3 断 payload 至少有一种「程序化判断」信号 —— `DataPart` / TextPart 内是 JSON 对象 / `status.message.metadata` 里有 `error/errorCode/code/type/reason` 等约定 key。三者皆无 → 客户端只能靠自然语言启发式解析 → 违反 §5.1.6。
- **触发机制**：与 C3 downstream-agent-killed 复用 —— deep-research + search 双 agent，WORKING 后 `SutStack.stop(SEARCH)` 触发下游 A2A connection refused → handler runtime exception → failed 家族终态。
- **断言层次**：
  - **层 1**（§5.1.4 + §5.1.6 + §5.1.8）：终态 ∈ {FAILED, CANCELED, REJECTED}
  - **层 2**（§5.1.8）：`status.message.parts` 非空
  - **层 3**（§5.1.6「程序化判断」）：至少满足 DataPart / JSON-shape TextPart / metadata 约定 key 之一
- **当下预期**：**层 3 大概率红** —— 开发组尚未落实结构化 shape，failed Task 当前多以自然语言 TextPart 承载。**这是 spec-first 写法的价值**：SUT 违约就红、SUT 补齐就绿；断言不 relax 到 SUT 现状。评审 §6 落地后可把层 3 收紧为具体字段名。

#### FEAT-001.input-required-fake-completed — §5.1.7 状态语义忠实性反向断言(2026-08-04 新增)
- **状态**:runnable(dual-stack + `@manual`,需 LLM)
- **FEAT 依据**:v2 §5.1.7「handler 输出需要用户输入的中断时,Task 必须进入 input-required 类语义,而不是伪装成 completed」—— 正向已由 [MultiTurnSearchFollowupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java) 覆盖(缺项 prompt → 期望 INPUT_REQUIRED);本用例覆盖<b>反向</b>:信息齐全 prompt 不得<b>误伪装 INPUT_REQUIRED</b>。
- **配对逻辑**:INPUT_REQUIRED 是昂贵路径(客户端挂机等续答、消费方误分配用户会话槽位);agent 因保守/歧义处理不当滥用 INPUT_REQUIRED,与「handler 意图忠实反映」精神对立。正反向配对形成完整状态语义 gate。
- **G**:deep-research + search dual-stack(deep-research 通过 `SEARCH_AGENT_URL` env 寻址 search)。
- **W**:发送信息齐全 prompt「请查询 DeepSeek-R1 模型的官方定价,请给出官方定价页面链接。」—— 显式提供了模型(DeepSeek-R1)+ 具体问题(官方定价)+ 期望格式(链接),search-agent LLM 无判追问的条件。
- **T**:
  - **A**:首轮终态 != INPUT_REQUIRED(信息齐全却假装追问 = §5.1.7 反向违约);
  - **B**:若 COMPLETED,artifact 不应是"假 COMPLETED 装追问文本"(§5.1.7 正向违约:handler 意图应忠实反映到 Task 状态)。
- **PASS**:A+B 都满足。**FAIL**:首轮 INPUT_REQUIRED(A 违约) / COMPLETED 但 artifact 全是追问文本无 DeepSeek 引用(B 违约)。
- **框架落点**:[InputRequiredFakeCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InputRequiredFakeCompletedTest.java)。

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
| jsonrpc-invalid-params | [JsonRpcInvalidParamsTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/JsonRpcInvalidParamsTest.java) | partial | 已落(params=[] runnable;结构合法但字段错 red-first) |
| push-config-crud(反转) | [PushConfigCrudTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushConfigCrudTest.java) | runnable | 已落(2026-08-04 反转:5 method 应返 -32601) |
| inline-push-config-async-accept | [InlinePushConfigAsyncAcceptTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigAsyncAcceptTest.java) | runnable | 已落(`@manual`) |
| inline-push-config-untrusted-host | [InlinePushConfigUntrustedHostTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InlinePushConfigUntrustedHostTest.java) | runnable | 已落 |
| push-notification-callback-receiver | [PushNotificationCallbackReceiverTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationCallbackReceiverTest.java) | runnable | 已落(capability off 时正例分支 assumeTrue skip) |
| push-notification-idempotency | [PushNotificationIdempotencyTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/PushNotificationIdempotencyTest.java) | runnable | 已落(capability off 时 assumeTrue skip) |
| agent-card-callback-composite | [AgentCardCapabilitiesTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/AgentCardCapabilitiesTest.java) 扩展 | runnable | 已落 |
| cascade-callback-real-search-happy-path | [CascadeCallbackRealSearchAgentHappyPathTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/CascadeCallbackRealSearchAgentHappyPathTest.java) + [TransparentA2AProxy](../../src/test/java/com/huawei/ascend/sit/mock/TransparentA2AProxy.java) | partial | 已落(assertion 1-3 绿 · assertion 4 FEAT-004 auto-resume gap 常红 · `@manual`) |
| webhook-vs-streaming / no-intermediate | — | OUT | v2 §5.2 明示 OUT,不再列 |
| webhook-payload-ref | 待新建 | **deferred** | v2 §2 承接为 MUST 但 SUT 侧阈值/落地形态未确认,等联测 |
| tenant-id-propagation | `TenantIdPropagationTest` | partial | 待新建 |
| tenant-isolation | `TenantIsolationTest` | partial | 待新建 |
| empty-text-input | [EmptyTextInputTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/EmptyTextInputTest.java) | partial | 已落（§5.1.6 反推） |
| input-required-fake-completed | [InputRequiredFakeCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InputRequiredFakeCompletedTest.java) | runnable | 已落(dual-stack + `@manual`,v2 §5.1.7 反向) |
| task-lifecycle | [StreamingSendMessageTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java#L117-L133)（DA-03 已扩展） | runnable | 已扩展落地 |
| task-failed-payload | [TaskFailedPayloadTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/TaskFailedPayloadTest.java) | partial | 已落（watchdog + @manual；层 3 预期红） |

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

**P0-C · Push Config / Callback 家族(2026-08-04 v2 spec 落地批次)**
- ✅ `PushConfigCrudTest`(反转:5 method 应返 -32601)
- ✅ `JsonRpcInvalidParamsTest`(partial;结构场景 red-first,L2 §1.3 实现缺口)
- ✅ `InlinePushConfigAsyncAcceptTest`(`@manual`)
- ✅ `InlinePushConfigUntrustedHostTest`
- ✅ `PushNotificationCallbackReceiverTest`
- ✅ `PushNotificationIdempotencyTest`
- ✅ `AgentCardCapabilitiesTest` 扩展(composite check)
- 🟡 `CascadeCallbackRealSearchAgentHappyPathTest`(端到端 smoke + 双向 wire 抓包;assertion 1-3 绿、assertion 4 FEAT-004 auto-resume gap 常红;`@manual`)

**P1 · Agent Card 完整性 + 场景化**
- ✅ `AgentCardCapabilitiesTest`
- ✅ `AgentCardSkillsTest`
- ✅ `AgentCardPublicBaseUrlTest`（partial）
- ⬜ `TenantIdPropagationTest`（partial）
- ⬜ `TenantIsolationTest`（partial，复用 DA-05/06 fixture）
- ✅ `EmptyTextInputTest`（partial；§5.1.6 反推）
- ✅ `InputRequiredFakeCompletedTest`(dual-stack + `@manual`;v2 §5.1.7 反向)

**P2 · 依赖故障注入**
- 🟡 `DownstreamAgentKilledMidStreamTest`（watchdog + @manual；本地拉两 jar，用 SutStack.stop() 中途杀 search）
- ✅ `NonexistentToolRefusalTest`（§5.1.6 正例；LLM 拒答不存在工具走 COMPLETED）
- 🟡 `TaskFailedPayloadTest`（watchdog + @manual；复用 downstream-killed fixture；层 3「程序化判断」当前 SUT 阶段**预期红**，等 SUT 补齐结构化 payload 后自动绿）

**Deferred · 等联测形态明确**
- `WebhookPayloadRefTest`(v2 §2 承接但阈值/落地形态未定)
- webhook-vs-streaming / no-intermediate 已明示为 OUT(v2 §5.2),不再列

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
| §6 错误码未列 | `downstream-agent-killed-mid-stream`（不受影响，层 1/2 硬 MUST） / `task-failed-payload`（层 3「程序化判断」当前 SUT 阶段预期红，评审 §6 定字段后可收紧断言）；`empty-text-input` 走 §5.1.6 反推，不依赖 §6 |
| §7 缺 X-Tenant-Id 落点 | `tenant-id-propagation`（partial）+ `tenant-isolation`（partial） |

### 6.2 实现层风险（非评审风险）

**SDK 版本能力天花板**
- A2A SDK `1.0.0.Final` 的 `JSONRPCTransport.unmarshalResponse` 不按 JSON-RPC error code 分流具体子类——见 [DA-04 §9 备注](deepagent/DA-04-get-task.md)。协议错误 code 断言子用例（`jsonrpc-*` / `webhook-untrusted-target` 若拒绝走 error 分支）需要绕过 SDK，用底层 HTTP client 直接发。
- SDK 是否暴露 push config CRUD 的 Java API 需要在动工前先跑 smoke 探针；若 SDK 未包裹，用 `HttpClient` 直发 JSON-RPC payload（`PushConfigCrudTest` 走此路径）。

**Webhook 占位 endpoint 而非 mock receiver**
- 由于评审 §3 结论"整栈无 receiver"，本档**不引入 WireMock / MockWebServer** 依赖，避免引入"以 SIT 侧 mock 定义 receiver 契约"的隐性假设。
- `webhook-untrusted-target` 可用 `com.sun.net.httpserver.HttpServer`（JDK 自带）临时挂一个占位 endpoint，只用于"观察 SUT 是否曾 POST"（负路径断言用）。
- 完整 webhook 家族用例的实现，**等待评审 §3 落地 receiver 契约后**才决定用什么依赖。

### 6.3 input-required 子用例进展(2026-08-04 更新)

**已落地**:
- 正向:[MultiTurnSearchFollowupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java) —— deep-research + search dual-stack;缺项 prompt(缺型号)触发 search-agent 追问 → 期望 INPUT_REQUIRED → 续答 → COMPLETED。
- 反向:[InputRequiredFakeCompletedTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InputRequiredFakeCompletedTest.java) —— 信息齐全 prompt 应<b>非 INPUT_REQUIRED</b>(v2 §5.1.7 反向:handler 不得滥用追问路径)。

**补充**:input-required 语义的"中断-续接"完整能力由 **FEAT-004** 承接(见 [FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md) §行为矩阵能力 7);本档从 FEAT-001 服务入口视角断言"状态可达 + wire 契约合规",不重复 FEAT-004 的中断-续接完整链路。

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

### 6.8 与 FEAT-004(远程编排 continuation)关系(2026-08-09 补)

- 本档承接 push cascade 的 **wire 契约**面(outbound pushConfig / 反向 callback / receiver 200 accepted / composite check);cascade smoke 用例 `cascade-callback-real-search-happy-path`(§3.5.f)assertion 1-3 就是本档职责。
- **auto-resume 语义**(caller 收到 sub-agent callback 后 → wire 回 ReAct 循环 → emit terminal state) 属 FEAT-004 中断-续接域,由 [FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md) 承接。§3.5.f assertion 4 只作 **红色兜底** 标记该 gap 存在,不在本档扩展修复责任。
- **BUG-009 已翻案**:早期 log-grep 推断"outbound pushConfig 未 wire"错误。2026-08-09 双向 [TransparentA2AProxy](../../src/test/java/com/huawei/ascend/sit/mock/TransparentA2AProxy.java) 抓包证明 outbound + callback + receiver 全通,真正 gap 在 auto-resume。BUG-009 doc(untracked)应关闭或降级为"已验证无问题"。

## 7. 真机实测进展记录（滚动，自 testplan 方案文档迁入）

> 方案级设计文档（docs/testplan 同名档）只锚定场景条目，不承载进展；实测进展、缺陷对时与验证结论统一记录在本节（2026-08-17 口径迁入）。

### 7.1 2026-08-14 首轮真机校准

首次以真实 jar（deep-research/search/verify 0.1.0 + DeepSeek v4-pro）拉通后，把若干「纸面口径」落成实测口径：

| 发现 | 证据 | 对用例 / 文档的影响 |
|---|---|---|
| callback receiver 鉴权是 **profile 门控**，非「配了 token 即启用」 | `DeepResearchCallbackBearerTokenFilter` 标注 `@Profile("callback-auth")`；只设 `DEEP_RESEARCH_CALLBACK_TOKEN` 不激活 profile 时 filter 不注册、入口裸奔（首轮即此状态，一度误判为「完全无鉴权」） | 原单条 D9「未授权拒绝」拆为独立两条：**D9a** 在 `callback-auth` profile 下断言鉴权强制（PASS）；配置陷阱作为部署告警项写入 §7。 |
| callback 幂等去重**先于**绑定校验，`DUPLICATE` 分支跳过校验直接 200 | 字节码：`saveIfAbsent(nid,hash)` 先于 `onAccepted(task)`，仅 `CREATED` 调 `onAccepted`；真机复现 首发 404 → 原样重放 200（改 payload 重放 409） | 新增 **D9b** 幂等重放回归看守，断言「首发被拒 ⇒ 重放不得已受理」，缺陷修复前 FAIL。缺陷单 [#77](https://gitcode.com/openJiuwen/agent-runtime-java/issues/77)。 |
| 特性档的内联 push config 位置 `params.pushNotificationConfig` 在 SDK 1.0.0.Final **不存在**，实测入口为 `params.configuration.taskPushNotificationConfig`（`id` 必填） | D4 真机实测：唯有后者被 runtime 消费 | D1/D4 及 wire 断言以实测位置为准；spec 与 impl 的这处口径分歧需与开发对齐（改文档或 runtime 补旧入口）。 |
| callback 仅在**终态**（COMPLETED / FAILED）触发 | 用户澄清 + 观察 | D 组 callback 用例 payload 一律用终态；非终态回调属契约外。 |
| C9（input-required）状态**可达** | 长调研欠定 prompt 稳定触发 `TASK_STATE_INPUT_REQUIRED` | C9 从条件用例转常规用例。 |
| push=true 的启动前置：`DEEP_RESEARCH_PUBLIC_URL` 必填；`SEARCH/VERIFY_AGENT_URL` 做非空存在性校验但下游不可达不阻塞启动 | boot fail-fast 信息 + 启动日志（`Failed to discover ...retry every 30s` 仍就绪） | D9a/D9b/D8 的 fixture 启动参数据此固定；只测 callback 入口的用例可注入 dummy 下游 URL。 |
| **D2 出向终态投递无法用真实下游链路复现**：带真实 search/verify jar 时，deep-research ReAct 首轮调 `search-agent`，该子代理交互以 `state=INPUT_REQUIRED` 冒泡为父任务 `INPUT_REQUIRED`（`controller - Task ... requires interaction`），任务**驻留 INPUT_REQUIRED、不达终态**；callback 仅终态触发，故出向 POST 不可达 | D2 真机日志（2026-08-14）：deep-research `search-agent state=INPUT_REQUIRED latencyMs=1904` → `requires interaction`；search-agent 系统提示把 `ask_user` 设为歧义触发的一等路径，叠加真实 Tavily/web_search 依赖，实链天然非确定且收敛到 INPUT_REQUIRED（对齐 C9「input-required 可达」） | D2 已建但**核心断言（终态后收到出向 POST）在实链下无法被触发**。**结局（2026-08-17）**：不再走「mock 下游喂 deep-research」路线——终态投递用例（上游整合后的实现）换用 **search-agent 作单节点 sender SUT**（一跳确定性收束终态），真机全过，终态投递闭环实证（见 §7.2）；原 deep-research 级联版实现退役。D1（异步接受）此前已实测 PASS，闭环入口形态已证。 |

### 7.2 2026-08-17 上游用例整合与两轮真机验证

测试仓上游（main@cd1c1f1）平行建成了整套 push notification 用例与配套 fixture。按「同域覆盖以上游为准、真机验证能用后退役本仓平行实现、保留独有覆盖」的原则完成整合：D1、D2、D4、B5、D8 五条场景的本仓平行实现及旧接收桩已退役删除；保留本仓独有覆盖 D9a（`callback-auth` profile 激活的鉴权绿路）、D9b（「首拒⇒重放不得 2xx」缺陷看守，issue #77）与 C8。代码到场景的映射以测试仓当前代码为准。

当日完成**两轮真机**：上午为 PR [#151](https://gitcode.com/openJiuwen/agent-runtime-java/merge_requests/151)（runtime 主干 2026-08-11 合入，修 issue #68/#69/#70）**之前**的构建，下午刷新为其**之后**的构建并回归。按场景条目归档：

| 场景/条目 | 结论 |
|---|---|
| D2+D5+token（终态投递闭环，sender=search） | **两轮均 PASS**：COMPLETED 后恰好一次 POST（观察窗内无中间态/重复投递）；payload 为 JSON-RPC 信封复用 Task 表面（`result.task` + artifacts + `metadata._agentcore_terminal:true`），`notificationId` header/body 双写一致；`SendMessage` config.token 以 `Authorization: Bearer` 携出。出向终态投递闭环首次实证。 |
| D1（异步接受）、D4（非法/未受信 URL 拒绝）、B5（CRUD OUT 全 -32601）、receiver 冒烟（capability⇔可达、malformed 400、token 校验） | 均 PASS。 |
| D3（FAILED 携错投递） | **缺陷闭环样本**：旧包复现「FAILED 任务 60s 零投递」（异常兜底路径吞事件，sender 不触发）；与开发对时确认即 issue #69，PR#151 已修——新包回归 FAILED 投递 **t+1.3~1.4s** 到达，PASS。issue #70（稳定错误码）同 PR 修复：错误码实测位于 `result.task.status.message.metadata."openjiuwen.error"`，结构化错误断言层首次执行并通过。 |
| issue #77（callback 幂等去重越过绑定校验） | **未修**：新包上 D9b 看守仍复现 first=404 `binding not found` → 原样重放=200 `accepted`。PR#151 的幂等修改仅涉成功投递侧；此缺陷需单独跟修，看守保持 FAIL 站岗。 |
| receiver 独测 spec-vs-impl 缺口 | 维持 red-first 记录：实现要求 body 为 JSON-RPC result 信封（扁平 body 400）、绑定优先（无绑定 task→404，不符 spec §2.7 的 200/202）、鉴权 profile 门控默认关（错误 auth 非 401/403）。属契约口径分歧，待 spec 与实现对齐后收敛，非本轮回归对象。 |
| issue #68（callback 回灌自动续跑） | **无反证，已实证生效**：多轮闭环用例（中断驻留→澄清续跑→终态→投递，GetTask 为父任务状态观察面）新包真机 PASS——`INPUT_REQUIRED` 驻留期 0 推送、澄清后父任务达 COMPLETED、登记 URL 收到引用父 taskId 的终态投递；服务端日志同时实锤自动续跑循环（下游子任务 COMPLETED→回灌→**无人工消息**的父任务 RESUME→下一轮检索）。注意旧「auto-resume gap」用例把修复前行为写为期望、且其 Phase1 在本拓扑测的是「下游驻留澄清态」而非续跑缺失，已按新契约重写。 |
| 环境事实 | search jar 注入 `SEARCH_AGENT_PUSH_NOTIFICATIONS=true` 后 capability 仍为 false（不声明 push），依赖该声明做前置的级联探针在本地拓扑会 INCONCLUSIVE 跳过；「下游异步终态回灌」形态的覆盖须由确定性 mock 下游直驱。 |

**测试侧同步修正**（随整合落入测试仓）：receiver/幂等类用例启动前置补齐 push 开关与公开 URL（否则 receiver 恒 501，断言打不到真实路径）；修复 header 与 body notificationId 不一致的构造笔误；callback body 状态取值补 JSON-RPC result 信封路径；多轮闭环用例的观察窗全部参数化（system property 可调），prompt 收窄为单厂商单维度以控制检索轮次。
