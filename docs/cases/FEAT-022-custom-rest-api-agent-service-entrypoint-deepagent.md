---
feature_id: FEAT-022
feature_title: 自定义 REST API 智能体服务入口
sut: agent-deep-research（配置 custom REST 入口的示例 Agent）
scope: 本档覆盖 custom REST 入口的黑盒验收；协议转换 SPI、conversationId 续轮、同步/流式响应、并发互斥、错误映射和与标准 A2A 入口的语义归一性
status: draft
owner: TBD
tags: [integration, feat-022, custom-rest]
depends_on:
  - version-scope FEAT-022（`chaosxingxc-orion/spring-ai-ascend@experimental/version-scope/FEAT-022-custom-rest-api-agent-service-entrypoint.md`，2026-07-21 版本）
  - L2 设计 Feat-Func-022（`architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-022-custom-rest-api-agent-service-entrypoint.md`，2026-07-22 版本）
related_docs:
  - FEAT-001 标准化 Agent 服务入口：[FEAT-001-standardized-agent-service-entrypoint-deepagent.md](FEAT-001-standardized-agent-service-entrypoint-deepagent.md)
---

# FEAT-022 — 自定义 REST API Agent 服务入口 SIT 覆盖设计

> **一句话**：以配置 custom REST 入口的 agent-deep-research 为对象，把 version-scope §2.1 能力清单、§4 场景表和 §5 行为语义映射为可黑盒断言的子用例；核心验证：协议转换 SPI、conversationId 自动续轮、同步/流式响应、并发互斥、错误映射和与标准 A2A 入口的语义归一性。

> **状态含义**：
> - ✅ 可直接落地：spec 清晰，无外部依赖
> - 🟡 部分可测：主路径可测，某些断言维度需 adapter 实现配合
> - ⬜ 未覆盖：待补齐
> - 🚫 阻塞：依赖上游能力（如 tenant 传播修复）

---

## 1. 覆盖矩阵

对应 FEAT-022 version-scope §2.1 能力清单和 §4 场景表；每行一条子用例。

| FEAT-022 能力要求 | 本档子用例 ID | 状态 | 优先级 | 备注 |
|---|---|---|---|---|
| 自定义 REST 服务入口启用 | `FEAT-022.custom-rest-enabled` | ✅ | P0 | 配置 path 后端点可访问 |
| 单 path 约束（多配启动失败） | `FEAT-022.single-path-only` | ⬜ | P1 | 需另起 SUT 变体断言启动日志；当前 SutStack 不便直接注入两个 path |
| 三入口同端口共存 | `FEAT-022.single-entrypoint-coexistence` | ✅ | P0 | §5.1.2 `/a2a` + `/v1/query` + `/v1/{...}` 共存 |
| 用户自定义请求映射 | `FEAT-022.request-mapping` | ✅ | P0 | SPI `toA2ARequest` 正确调用 |
| 用户自定义响应投影 | `FEAT-022.response-projection` | ✅ | P0 | SPI `fromA2ATask` / `fromA2AStreamEvent` 正确调用 |
| 同步消息调用 | `FEAT-022.sync-json-response` | ✅ | P0 | 非流式请求返回 JSON |
| 流式消息调用 | `FEAT-022.streaming-sse-response` | ✅ | P0 | SSE 响应正确投影 |
| SSE 终止语义 | `FEAT-022.sse-termination` | ✅ | P1 | §4.5.2 final/interrupted 后连接关闭 |
| conversationId 隔离 | `FEAT-022.conversation-isolation` | ✅ | P0 | 不同 conversation 创建不同 Task |
| 决策 A · 首轮无 Task → CREATE_NEW | `FEAT-022.auto-create-task` | ✅ | P0 | §4.3.4 无活跃 Task 时新建 |
| 决策 A · 终态后 → 新 Task（不复用） | `FEAT-022.terminal-not-reused` | ✅ | P0 | §4.3.4 COMPLETED/FAILED 后建新 Task |
| 决策 B · 唯一 INPUT_REQUIRED → RESUME | `FEAT-022.auto-resume-task` | ✅ | P0 | §4.3.4 幸福路径续轮 |
| 决策 C · SUBMITTED/WORKING → 409 busy | `FEAT-022.conversation-busy` | ✅ | P0 | §4.3.4 上一轮进行中拒绝 |
| 决策 · AUTH_REQUIRED → 409 not_resumable | `FEAT-022.auth-required-not-resumable` | 🚫 | P1 | 黑盒不可测：deep-research 业务流程不进入 AUTH_REQUIRED；见 §4.3 |
| 决策 · 未知状态 → 409 conflict | `FEAT-022.unknown-state-conflict` | 🚫 | P2 | 黑盒不可测：只有 SDK 版本不匹配 / TaskStore 被污染时才出现；见 §4.3 |
| 决策 D · 多个非终态 → 409 ambiguous | `FEAT-022.conversation-task-ambiguous` | 🚫 | P1 | 黑盒不可测：framework 的 reservation 保证同 conversationId 不产生多个活跃 Task；防御性代码；见 §4.3 |
| 显式 taskId 优先 | `FEAT-022.explicit-task-id` | ⬜ | P1 | adapter 设置 taskId 时跳过 resolver；需自定义 adapter |
| shadow Task 排除 | `FEAT-022.shadow-task-excluded` | ⬜ | P1 | `shadow:` 前缀 Task 不参与续轮；需 TaskStore 后门 |
| 单 JVM conversation 互斥 | `FEAT-022.conversation-mutex` | ✅ | P0 | §4.5.1 并发首轮 reservation |
| Internal contextId 不泄漏 | `FEAT-022.internal-context-id-not-leaked` | ✅ | P1 | §8.3 第 10 条 响应不含 `custom-rest:v1:` 前缀 |
| framework 错误投影 · 415 | `FEAT-022.framework-error.415` | ✅ | P1 | 非 JSON Content-Type |
| framework 错误投影 · 400 invalid_json | `FEAT-022.framework-error.400-invalid-json` | ✅ | P1 | body 非合法 JSON |
| framework 错误投影 · 400 invalid_custom_request | `FEAT-022.framework-error.400-invalid-req` | ✅ | P1 | JSON 非 object |
| framework 错误投影 · 406 stream_not_acceptable | `FEAT-022.framework-error.406` | ✅ | P1 | stream=true 但 Accept 不含 SSE |
| framework 错误投影 · 409 conversation_busy | `FEAT-022.framework-error.409` | ✅ | P1 | 并发同 conversation |
| framework 错误投影 · 503 agent_not_ready | `FEAT-022.framework-error.503` | 🚫 | P2 | 黑盒不可测：需在 SUT 加载完成前发请求，时间窗极短；见 §4.3 |
| 错误兜底 | `FEAT-022.error-fallback` | ✅ | P2 | adapter 错误投影失败时 framework 兜底 |
| A2A 语义归一 · 协议转换透明性 | `FEAT-022.a2a-semantic-consistency` | ✅ | P0 | 双 mock 对比 `params.message.parts[0].text` + `metadata` |
| Task 查询能力 | `FEAT-022.task-query` | 🟡 | P2 | SHOULD 能力，若提供需验证 |
| Task 取消能力 | `FEAT-022.task-cancel` | 🟡 | P2 | SHOULD 能力，若提供需验证 |
| 非空 tenant 下游传播 | `FEAT-022.tenant-propagation` | 🚫 | P1 | 依赖 runtime 修复 tenant 传播 |

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- **Agent 配置**：agent-deep-research 启动时启用 custom REST 入口，配置示例：
  ```yaml
  openjiuwen.service.custom-rest:
    query-path: /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
  ```
- **Adapter 实现**：SUT 必须提供一个 `CustomRestProtocolAdapter` bean，实现请求映射和响应投影。
- **标准 A2A 入口**：保持 `/a2a` 标准入口可用，用于语义一致性对比测试。

### 2.2 共享测试基础设施

- **HTTP 客户端**：直接使用 `HttpClient` 或 `RestTemplate` 发送 custom REST 请求，不依赖 A2A SDK。
- **事件收集**：复用 `A2aEventCollector` 用于标准 A2A 入口的对比测试。
- **断言库**：AssertJ；`@Tag("integration") @Tag("feat-022") @Tag("custom-rest")`。
- **Mock adapter**：SIT 侧可能需要提供测试用 adapter 实现，或依赖 SUT 已有的 example adapter。

### 2.3 共享命名约定

- `conversationId` 用 `conv-feat022-<slug>-<uuid8>`，避免不同子用例互相冲突。
- `projectId` / `agentId` 等 path 变量使用固定测试值：`project-test` / `agent-test`。
- Custom REST 请求 body 字段约定：`{"input": "...", "stream": true/false}`。

---

## 3. 子用例设计

> 约定：每条子用例用 G/W/T（Given/When/Then）格式；结论分 PASS/FAIL/INCONCLUSIVE。

### 3.1 基础功能

#### FEAT-022.custom-rest-enabled — 自定义 REST 入口启用

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：version-scope §2.1「自定义 REST 服务入口」+ L2 §2.1「单一可配置 POST path」
- **G**：agent-deep-research 配置了 `openjiuwen.service.custom-rest.query-path=/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}`
- **W**：发送 `POST /v1/project-test/agents/agent-test/conversations/conv-001` with body `{"input": "hello", "stream": false}`
- **T**：
  - HTTP status ∈ {200, 4xx}（说明端点已注册）
  - 若返回 404，检查是否配置未生效
- **PASS**：端点可访问，返回非 404
- **FAIL**：返回 404（配置未生效或 path 错误）
- **INCONCLUSIVE**：SUT 未启动
- **落点**：`CustomRestEnabledTest.java`

#### FEAT-022.request-mapping — 用户自定义请求映射

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：version-scope §2.1「用户自定义请求映射」+ L2 §2.3.1 SPI 契约
- **G**：SUT 配置了 custom REST 入口和 adapter
- **W**：
  1. 发送 `POST /v1/project-test/agents/agent-test/conversations/conv-req-map-001`
  2. Headers: `X-Custom-Header: test-value`
  3. Query: `?debug=true`
  4. Body: `{"input": "test message", "workspace_id": "ws-001"}`
- **T**：
  - 请求成功（200 或合理错误）
  - adapter 的 `toA2ARequest` 方法被调用
  - Context 包含 headers、pathVariables、queryParams、body
- **PASS**：adapter 正确接收到完整 Context
- **FAIL**：Context 缺失字段或未调用 adapter
- **落点**：`CustomRestRequestMappingTest.java`

#### FEAT-022.sync-json-response — 同步消息调用

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：version-scope §2.1「同步消息调用」+ §3.1 同步响应形态
- **G**：SUT 配置了 custom REST 入口
- **W**：发送 `POST /v1/.../conversations/conv-sync-001` with `{"input": "简单问题", "stream": false}`
- **T**：
  - HTTP status = 200
  - Content-Type = application/json
  - 响应 body 可解析为 JSON object
  - 包含 adapter 定义的字段（如 `success`, `output`, `conversation_id` 等）
- **PASS**：返回自定义 JSON 响应
- **FAIL**：返回 SSE 或非 JSON
- **落点**：`CustomRestSyncResponseTest.java`

#### FEAT-022.streaming-sse-response — 流式消息调用

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：version-scope §2.1「流式消息调用」+ §3.1 SSE 形态
- **G**：SUT 配置了 custom REST 入口
- **W**：发送 `POST /v1/.../conversations/conv-stream-001` with `{"input": "复杂问题", "stream": true}`
- **T**：
  - HTTP status = 200
  - Content-Type = text/event-stream
  - 接收到多个 SSE 事件
  - 每个事件有 `event:` 和 `data:` 字段
  - 最后一个事件表示终态（根据 adapter 定义）
- **PASS**：返回合法 SSE 流
- **FAIL**：返回 JSON 或流格式非法
- **落点**：`CustomRestStreamingResponseTest.java`

### 3.2 conversationId 续轮机制

#### FEAT-022.auto-create-task — 自动创建首轮 Task

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：L2 §4.3.4「状态决策 · CREATE_NEW」
- **G**：SUT 配置了 custom REST 入口
- **W**：
  1. 发送首轮请求 `POST .../conversations/conv-create-001` with `{"input": "第一轮问题"}`
  2. 通过标准 A2A 入口 `ListTasks` 查询 internal contextId 对应的 Task
- **T**：
  - 请求成功返回
  - TaskStore 中存在一个新 Task
  - Task.contextId = internal contextId（SHA-256 编码）
  - Task 状态为终态或 INPUT_REQUIRED
- **PASS**：自动创建了新 Task
- **FAIL**：未创建 Task 或创建多个
- **落点**：`CustomRestAutoCreateTaskTest.java`

#### FEAT-022.auto-resume-task — 自动续轮 INPUT_REQUIRED Task

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：L2 §4.3.4「状态决策 · RESUME」
- **G**：
  1. 首轮请求创建了一个 INPUT_REQUIRED Task
  2. 通过标准 A2A 入口确认 Task 状态
- **W**：
  1. 发送第二轮请求 `POST .../conversations/conv-resume-001` with `{"input": "第二轮回答"}`
  2. 使用相同 conversationId
- **T**：
  - 请求成功返回
  - 未创建新 Task
  - 原 Task 状态推进（从 INPUT_REQUIRED 到其他状态）
  - 响应包含续轮后的结果
- **PASS**：自动恢复并续写原 Task
- **FAIL**：创建了新 Task 或未推进状态
- **落点**：`CustomRestAutoResumeTaskTest.java`

#### FEAT-022.conversation-mutex — 单 JVM conversation 互斥

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：L2 §4.5.1「单 JVM reservation」
- **G**：SUT 配置了 custom REST 入口
- **W**：
  1. 并发发送两个请求到同一 conversationId
  2. 请求 1: `POST .../conversations/conv-mutex-001` with `{"input": "并发请求1"}`
  3. 请求 2: `POST .../conversations/conv-mutex-001` with `{"input": "并发请求2"}`（几乎同时发送）
- **T**：
  - 至少一个请求返回 409 Conflict
  - 错误响应包含 `conversation_busy` 或类似错误码
  - 只有一个 Task 被创建
- **PASS**：并发互斥生效，返回 409
- **FAIL**：两个请求都成功或创建多个 Task
- **落点**：`CustomRestConversationMutexTest.java`

### 3.3 错误处理

#### FEAT-022.framework-error-projection — framework 错误投影

- **状态**：✅ 可直接落地
- **优先级**：P1
- **FEAT 依据**：L2 §4.2「command 校验」+ §2.3.1 `CustomRestError`
- **G**：SUT 配置了 custom REST 入口
- **W**：
  1. 场景 1：发送空 conversationId `POST .../conversations/` with `{}`
  2. 场景 2：发送 stream=true 但 Accept 不含 text/event-stream
  3. 场景 3：同 conversation 有 WORKING Task 时再次请求
- **T**：
  - 场景 1：返回 400 + adapter 映射的错误信封（包含 `invalid_custom_request`）
  - 场景 2：返回 406 + 错误信封
  - 场景 3：返回 409 + 错误信封（包含 `conversation_busy`）
  - 所有错误都经过 adapter 的 `fromError` 方法投影
- **PASS**：framework 错误正确投影为自定义格式
- **FAIL**：返回原始 A2A 错误格式或 HTTP 500
- **落点**：`CustomRestFrameworkErrorProjectionTest.java`

#### FEAT-022.error-fallback — 错误兜底

- **状态**：✅ 可直接落地
- **优先级**：P2
- **FEAT 依据**：L2 §2.1「错误兜底」
- **G**：SUT 配置了会抛异常的 adapter（测试场景）
- **W**：
  1. 触发 adapter.fromError 返回 null 的场景
  2. 或触发 adapter.fromError 抛异常的场景
- **T**：
  - 不会返回 HTTP 500 无响应
  - 返回 framework 固定的兜底错误信封
  - HTTP status 合理（4xx 或 500）
- **PASS**：framework 提供兜底错误响应
- **FAIL**：请求挂起或返回空响应
- **落点**：`CustomRestErrorFallbackTest.java`

### 3.4 语义归一性

#### FEAT-022.a2a-semantic-consistency — A2A 语义归一

- **状态**：✅ 可直接落地
- **优先级**：P0
- **FEAT 依据**：version-scope §2.1「A2A 语义归一」+ §5.1.1「入口归一语义」
- **G**：
  1. SUT 同时启用 custom REST 和标准 A2A 入口
  2. 准备相同的测试输入
- **W**：
  1. 通过 custom REST 发送请求并记录结果
  2. 通过标准 A2A `/a2a` 发送相同请求并记录结果
  3. 对比两种入口创建的 Task
- **T**：
  - 两个 Task 的生命周期状态序列相同
  - 两个 Task 的终态相同（COMPLETED / FAILED / 等）
  - 两个 Task 的错误分类相同（若失败）
  - 两个 Task 的执行时长相近（误差 < 20%）
- **PASS**：两种入口行为语义一致
- **FAIL**：终态不同或错误分类不同
- **落点**：`CustomRestA2ASemanticConsistencyTest.java`

---

## 4. 断言层次与 PASS/FAIL 判据

### 4.1 核心 MUST 断言（P0）

| 子用例 | 层 1（核心） | 层 2（前置） | 层 3（健康度） |
|--------|------------|------------|--------------|
| custom-rest-enabled | 端点返回非 404 | — | 响应时间 < 5s |
| sync-json-response | 返回 JSON object | Content-Type 正确 | 包含业务字段 |
| streaming-sse-response | 返回 SSE 流 | Content-Type 正确 | 至少 2 个事件 |
| auto-create-task | 创建了新 Task | conversationId 隔离 | Task 可查询 |
| auto-resume-task | 恢复了原 Task | 未创建新 Task | 状态推进正确 |
| conversation-mutex | 并发返回 409 | 只创建一个 Task | 错误码正确 |
| a2a-semantic-consistency | 终态相同 | 状态序列相同 | 执行时长相近 |

### 4.2 SHOULD 能力（P2）

- `task-query`：若 SUT 提供 Task 查询 REST 接口，验证返回标准 Task 投影
- `task-cancel`：若 SUT 提供 Task 取消 REST 接口，验证映射到标准取消语义

### 4.3 已知限制（明确不测 · 原因分类）

#### 4.3.1 spec 层排除

- **跨 JVM 原子创建**：L2 §2.2 明确排除，当前只保证单 JVM 互斥。
- **认证授权**：version-scope §5.2 明确 OUT。

#### 4.3.2 依赖上游修复（🚫 阻塞）

- **tenant 下游传播**：依赖 runtime 修复（当前 bug），等修复后补齐。

#### 4.3.3 黑盒 HTTP 不可稳定构造（记录不测的原因）

以下用例的行为路径是 framework 的**防御性代码**或**极短时间窗**，正常业务流量不产生也无法通过 HTTP API 稳定触发。若需覆盖，只能走 SUT 侧内部集成测试（TaskStore 后门 / test hook / 手工造数据），本 SIT 暂不覆盖：

- **决策 D · 多个非终态 Task → 409 conversation_task_ambiguous**（L2 §4.3.4）
  - **原因**：framework 的 §4.5.1 reservation 保证同 conversationId 不产生 2 个活跃 Task。黑盒 API 无法构造脏数据。
  - **要覆盖需要**：TaskStore 后门，往同一 internal contextId 塞两个 INPUT_REQUIRED Task。
- **决策 · AUTH_REQUIRED → 409 not_resumable**（L2 §4.3.4）
  - **原因**：agent-deep-research 的业务流程里没有 auth 相关工具，Task 不会自然进入 AUTH_REQUIRED 状态。
  - **要覆盖需要**：换用一个会进入 AUTH_REQUIRED 的 Agent，或走白盒测试。
- **决策 · 未知 Task 状态 → 409 conflict**（L2 §4.3.4）
  - **原因**：只有 SDK 版本不匹配 / TaskStore 被外部污染时才会出现，正常运行时不产生。
  - **要覆盖需要**：TaskStore 后门 + 注入非法 state 枚举值。
- **framework 错误 · 503 agent_not_ready**（L2 §7）
  - **原因**：需要在 SUT 加载完成前发请求命中窗口；SutStack 的 `.start()` 已经等启动就绪才返回，SIT 天然不会命中。
  - **要覆盖需要**：SUT 侧提供强制"未就绪"开关，或 SIT 层绕过 startup wait 发探针。
- **单 path 约束启动失败**（version-scope §5.1.2）
  - **原因**：负向启动测试，需要另起一个 SUT 变体（配 2 个 path）看它启动失败。当前 SutStack 的 config 层只接受单一 env 值，改造成本较高。
  - **要覆盖需要**：扩 SutStack 支持多 path 配置注入 + 断言启动日志（如 `IllegalStateException: multiple query-path`）。
- **显式 taskId 优先**（L2 §4.3.4 分支）
  - **原因**：需要 SIT 侧提供自定义 adapter 让 `toA2ARequest` 手工返回带 taskId 的 A2ASendCommand；当前依赖 SUT 自带的 example adapter。
- **shadow Task 排除**（L2 §4.3.3）
  - **原因**：shadow Task 通常由 FEAT-004 remote 调用异常路径产生，需要配合 mock remote agent 制造异常；短期内可以做，但耦合 FEAT-004。当前先记录为待补齐。

---

## 5. 补齐优先级

| 优先级 | 子用例 | 落地状态 | 说明 |
|--------|--------|---------|------|
| **P0** | 基础功能 6 条 | ✅ 已落地 | enabled / sync / streaming / request-mapping / auto-create / mutex |
| **P0** | 续轮决策 A/B/C 3 条 | ✅ 已落地 | auto-create / auto-resume / conversation-busy / terminal-not-reused |
| **P0** | 三入口共存 1 条 | ✅ 已落地 | single-entrypoint-coexistence |
| **P0** | 语义归一 1 条 | ✅ 已落地 | a2a-semantic-consistency（双 mock 对比 message.parts / metadata） |
| **P1** | 错误处理 5 条 | ✅ 已落地 | framework-error: 415 / 400 invalid_json / 400 invalid_custom_request / 406 / 409 |
| **P1** | Internal contextId 不泄漏 1 条 | ✅ 已落地 | internal-context-id-not-leaked |
| **P1** | SSE 终止语义 1 条 | ✅ 已落地 | sse-termination |
| **P2** | 兜底 1 条 | ✅ 已落地 | error-fallback |
| **⬜** | 单 path 启动失败 | 未落地 | 需扩 SutStack 支持多 path 注入 |
| **⬜** | 显式 taskId 优先 | 未落地 | 需 SIT 自定义 adapter |
| **⬜** | shadow Task 排除 | 未落地 | 需配合 FEAT-004 mock 制造 shadow |
| **🚫** | 决策 D · ambiguous | 黑盒不可测 | framework 防御性代码；见 §4.3.3 |
| **🚫** | 决策 · AUTH_REQUIRED / 未知状态 | 黑盒不可测 | 业务流程不进入 / TaskStore 污染才出现；见 §4.3.3 |
| **🚫** | framework-error 503 agent_not_ready | 黑盒不可测 | 时间窗极短；见 §4.3.3 |
| **🚫** | Task query / cancel | SHOULD 能力 | 待 SUT 提供实现 |
| **🚫** | tenant 传播 | 阻塞 | 等 runtime 修复 |

---

## 6. 与 FEAT-001 / FEAT-004 的关系

- **FEAT-001**（标准 A2A 入口）：custom REST 是 FEAT-001 的**边缘服务化扩展**，底层执行链完全复用。
- **FEAT-004**（远程 Agent 编排）：custom REST 入口调用的 Agent 可以是 FEAT-004 client（如 deep-research → search）。
- **本特性重点**：验证 custom REST → A2A 的**协议转换正确性**和**语义归一性**，而非重复测试 Agent 执行逻辑。

---

## 7. Mock 与测试 Adapter 设计

### 7.1 测试 Adapter 最小实现

SIT 侧需要一个测试用 `CustomRestProtocolAdapter` 实现，或依赖 SUT 已有的 example adapter（如 `CustomRestDemoAdapter`）。

**最小 adapter 示例**：

```java
@Component
public class SitTestCustomRestAdapter implements CustomRestProtocolAdapter {
    
    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String input = (String) context.body().get("input");
        Boolean stream = (Boolean) context.body().getOrDefault("stream", false);
        String conversationId = context.pathVariables().get("conversation_id");
        
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart(input)))
                .build();
        
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .build();
        
        return new A2ASendCommand(params, conversationId, stream);
    }
    
    @Override
    public Object fromA2ATask(Task task, Context context) {
        return Map.of(
            "success", true,
            "conversation_id", context.pathVariables().get("conversation_id"),
            "task_id", task.id(),
            "status", task.status().state().name()
        );
    }
    
    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        return new SseEvent("message", Map.of("data", event.toString()));
    }
    
    @Override
    public Object fromError(CustomRestError error, Context context) {
        return Map.of(
            "success", false,
            "error_code", error.code(),
            "error_message", error.message()
        );
    }
    
    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        return new SseEvent("error", Map.of("code", error.code()));
    }
}
```

### 7.2 测试前置条件

- SUT 启动时加载上述 adapter bean
- 配置 `openjiuwen.service.custom-rest.query-path`
- 保持标准 A2A 入口 `/a2a` 可用（用于对比测试）

---

## 8. 未决问题与评审项

1. **Adapter 实现归属**：SIT 侧提供测试 adapter 还是依赖 SUT example？
2. **tenant 传播时机**：等 runtime 修复后补齐 tenant 相关子用例？
3. **Task query/cancel REST 形态**：若 SUT 提供这些能力，API 形态是什么？
4. **并发测试规模**：conversation-mutex 需要多少并发请求才算充分？

---

## 9. 总结

- **核心覆盖**：8 个 P0 子用例，覆盖协议转换、续轮、并发、语义归一
- **扩展覆盖**：5 个 P1/P2 子用例，覆盖错误处理、显式 taskId、shadow 排除
- **阻塞项**：1 个 tenant 传播子用例，等 runtime 修复
- **落地路径**：P0 → P1 → P2 → 评审澄清后补齐阻塞项

**Gap 摘要**：主路径清晰可测；tenant 传播需 runtime 修复；Task query/cancel 依赖 SUT 实现决策。
