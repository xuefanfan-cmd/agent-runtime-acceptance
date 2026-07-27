---
feature_id: FEAT-002
feature_title: Versatile 意图识别工作流适配兼容
sut: versatile-intent-acceptance-demo（单 Adapter + 内置 Versatile mock）
scope: 三字段输入映射、三字段结果提取、显式用户交互中断归一、异常断流分离、失败/取消映射、多轮续接、可观测性
status: designed
owner: TBD
priority: P0
tags: [integration, versatile-intent, feat-002]
depends_on:
  - 实现方交付的 agent-service-adapters-versatile / agent-service-app 已按坐标安装到本地 Maven 仓库
  - 被测版本已支持 FEAT-002 的 versatile.* 扩展配置（intents / messages / result-extractions / interrupt.*）
  - versatile-intent-acceptance-demo 模块（内置 mock，无外部 versatile 依赖）
  - FEAT-002 特性文档已明确"异常断流必须 FAILED"的专项规则（当前合并后的通用规则仍写"无 terminal → TYPE_INTERRUPT"，需先修正）
related_docs:
  - FEAT-002-versatile-intent-workflow-adapter-compatibility.md（version-scope 行为契约）
  - Feat-Func-002-versatile-intent-workflow-adapter-compatibility.md（L2 设计）
  - FEAT-002-implementation-checklist.md（实现清单，含代码行号锚点）
  - common/example/versatile-intent-acceptance-demo（自包含黑盒验收 example）
---

# FEAT-002 — Versatile 意图识别工作流适配测试用例设计

> **一句话**：以 `versatile-intent-acceptance-demo` 自包含模块为主体，内置 Versatile mock 充当工作流替身，
> 通过被测 runtime 的 A2A 入口黑盒验证三字段输入、正常结果、显式中断、异常断流分离与失败映射；
> 三字段结构化输出直接检查 `QueryChunk.data`（不依赖 A2A 出口投影）；
> 多轮续接在单 Adapter + mock 环境覆盖；
> 两层转发/重新分类/动态路由属于跨特性测试，非 FEAT-002 验收范围。

> **仓库边界**：新增测试代码只写入 `versatile-intent-acceptance-demo`；
> `agent-solution` 与 `agent-runtime-java` 为只读被测对象，不提交任何修改，不为测试增加 Adapter/SPI 代理端点。

## 1. 状态定义

- **runnable**：单 Adapter + 内置 mock，实现方代码落地即可 `mvn test` 直接验证，无外部环境。
- **query-chunk**：直接调用 `VersatileAgentHandler.query()` 检查返回的 `QueryResponse.result` / `QueryChunk.data`，不经 A2A 协议栈，用于验证结构化字段提取。
- **unit-only**：黑盒不可观测（或属契约冲突项），归实现方单测；本档列出但不在 example 中断言。
- **cross-feature**：不属于 FEAT-002 范围（如两层转发、动态路由、完整重新分类链路），作为跨特性端到端测试另行规划，不作为 FEAT-002 验收条件。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 |
|---|---|---|---|
| 三字段输入到达工作流（精确映射） | `FEAT-002.input.three-field` | runnable | mock 侧请求体捕获 + 精确值断言 |
| 输入字段为空/非法 → FAILED | `FEAT-002.input.invalid-fields` | runnable | A2A Task 终态 |
| 完整三字段结果 → COMPLETED | `FEAT-002.result.completed` | runnable | A2A Task 终态 |
| 未匹配/需澄清同流程 → COMPLETED | `FEAT-002.result.completed-variants` | runnable | A2A Task 终态 |
| **三字段结构化结果正确提取** | `FEAT-002.result.structured-chunk` | query-chunk | QueryResponse.result 三字段名称+值 |
| 三字段结构化输出经 A2A 投影可读 | `FEAT-002.result.structured-egress` | cross-feature | A2A artifact/metadata（跨特性，非验收条件） |
| 显式中断（信息完整）→ INPUT_REQUIRED | `FEAT-002.interrupt.explicit` | runnable | A2A Task 终态 + prompt |
| **断流无 terminal → FAILED（不得伪造中断）** | `FEAT-002.error.stream-closed` | runnable | A2A Task 终态（核心回归；依赖特性文档先明确此规则） |
| 显式中断信息不全 → FAILED | `FEAT-002.error.interrupt-incomplete` | runnable | A2A Task 终态 |
| 缺 agent_id → FAILED（不得出部分结果） | `FEAT-002.error.missing-agent-id` | runnable | A2A Task 终态 |
| agent_id 为数组/多值 → FAILED | `FEAT-002.error.agent-id-not-unique` | runnable | A2A Task 终态 |
| 三字段类型错误 → FAILED | `FEAT-002.error.field-type-error` | runnable | A2A Task 终态 |
| 远端 HTTP 5xx/超时 → FAILED | `FEAT-002.error.remote-upstream` | runnable | A2A Task 终态 |
| 取消 → 停止消费 + 取消语义 | `FEAT-002.cancel.cooperative` | runnable | A2A cancel + 终态（mock 连接断开为可选附加断言） |
| 首次中断→续接成功 | `FEAT-002.resume.single-turn` | runnable | 单 Adapter + mock 续接 |
| 续接后再中断 | `FEAT-002.resume.re-interrupt` | runnable | 单 Adapter + mock |
| 续接后工作流失败 | `FEAT-002.resume.fail-after-resume` | runnable | 单 Adapter + mock |
| 续接信息缺失/失效 → FAILED | `FEAT-002.resume.invalid-resume` | runnable | 单 Adapter + mock |
| 远端续接请求失败 → FAILED | `FEAT-002.resume.remote-resume-fail` | runnable | 单 Adapter + mock |
| 调用/中断/续接 trace/correlation 关联 | `FEAT-002.observe.trace-correlation` | runnable | 日志/trace 上下文 |
| 敏感字段不完整记录到日志 | `FEAT-002.observe.sensitive-fields` | runnable | 日志内容检查 |
| 配置缺失/输入组装失败 → FAILED | `FEAT-002.input.config-missing` | unit-only | 实现方单测 |
| intent-agent-mapping 1:N + 策略（契约冲突项） | `FEAT-002.mapping.strategy` | unit-only | 实现方单测（默认关闭，待评审） |

### 跨特性用例（非 FEAT-002 验收范围）

以下用例涉及 A2A 出口投影、结果驱动转发、动态 Agent Card 路由，属于跨特性端到端测试，**不作为 FEAT-002 验收条件**：

| 能力 | 子用例 ID | 状态 | 说明 |
|---|---|---|---|
| 两层链路：一层 agent_id→二层转发 | `cross.chain.two-layer` | cross-feature | 依赖结果驱动转发 + 动态路由 |
| 二层 query=用户原话、一层 response_content 进 messages | `cross.chain.query-vs-response` | cross-feature | 依赖动态路由 |
| 完整重新分类交接回一层 | `cross.chain.reclassify` | cross-feature | 依赖动态 Agent Card 路由 |
| 三字段结构化输出经 A2A 投影 | `cross.result.structured-egress` | cross-feature | 依赖 A2A 出口投影 |

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 主体 SUT 为 `versatile-intent-acceptance-demo`：薄壳挂实现方 `VersatileAgentHandler`，A2A 入口随机端口。
- `openjiuwen.service.versatile.*` 启用 FEAT-002 扩展键（`intents` / `messages` / `result-extractions` / `interrupt.*`），
  见模块内 `application.yml`；`url-template` 由 `@DynamicPropertySource` 指向内置 mock。
- `query-chunk` 用例不启动 Spring 容器，直接 `new VersatileAgentHandler(properties)` 调用 `query()`，
  检查返回的 `QueryResponse.result` 或 `QueryChunk.data`。
- 不引入外部 versatile 服务，一律用内置/受控 mock。

### 3.2 能力复用

- **runnable**：`@SpringBootTest(webEnvironment=RANDOM_PORT)`、`MockVersatileServer`、`A2aDriver`、`DriveResult`（均在 example 模块内）。
- **query-chunk**：直接使用 `VersatileAgentHandler`、`MockVersatileServer`，不经过 Spring Boot 或 A2A 协议栈。
- `InProcessRemoteAgentCaller`、`LayerTestHarness` 可用于验证跨层消息构造逻辑（如 buildForwardRequest/buildReclassifyRequest），
  但跨层转发本身不作为 FEAT-002 验收条件。
- 禁止测试自行实现 ProcessBuilder、端口探测、jar 路径解析、SSE 帧手工拼装（用现成驱动器）。

### 3.3 数据与场景约定

- **场景选择**：把「场景名」放进 A2A `contextId` → runtime 透传为 `conversationId` → adapter 填进 versatile URL 的
  `{conversation_id}` → mock 从路径末段取场景名返回对应 SSE。
- **输入证据**：mock 捕获 adapter 发出的请求体，断言 `inputs.query/intents/messages`。
- **状态证据**：解析 A2A SSE 帧的 `TASK_STATE_*`，归一为 COMPLETED / INPUT_REQUIRED / FAILED，取最后终态。
- **SSE fixture**：每场景一个 `versatile-sse/<场景>.sse`；实现方敲定真实报文后只替换 fixture，不改测试逻辑。
- **不写死**：不断言 Adapter 内部 result key 结构（除已定契约字段名），不依赖具体内部帧顺序。

## 4. 输入契约子用例

框架落点：`VersatileIntentContractTest.java`（example 模块）。

### FEAT-002.input.three-field — 三字段输入到达工作流（精确映射）

- **状态**：runnable，P0。
- **追溯**：统一三字段输入 MUST、当前主输入映射 MUST、会话消息数组映射 MUST。
- **G**：`normal-complete` fixture；配置含 `intents`（id/name）、`messages.required=true`。
- **W**：A2A `SendStreamingMessage`，`parts[0].text = "我要订酒店"`。
- **T**：
  - `inputs.query` 精确等于 `"我要订酒店"`（当前请求新增的用户原始文本）。
  - `inputs.intents` 为非空数组，元素含 id/name，值、数量和顺序与配置中 `intents` 列表一致。
  - `inputs.messages` 为非空数组，元素含 role/content，顺序和内容未被 Adapter 改写。
  - 当前用户输入同时出现在 `query` 和 `messages` 中时符合约定（query 即 messages 中的 user 内容）。
- **方法**：`threeFieldInputReachesWorkflow()`。
- **Story/DisplayName**：`FEAT-002.input.three-field: 三字段输入精确映射` / `Feat-002 三字段输入精确到达工作流`。

### FEAT-002.input.invalid-fields — 输入字段为空/非法 → FAILED

- **状态**：runnable，P1。
- **追溯**：结构化技术失败 MUST。
- **G**：配置 `intents` 为空数组；或 `messages` 为空（`messages.required=true`）；或 intent 元素缺 id/name。
- **W**：分别驱动。
- **T**：Task 终态 FAILED（阶段标识为 `config_read` 或 `input_assembly`）。
- **方法**：`emptyIntentsMustFail()` / `emptyMessagesMustFail()` / `invalidIntentFieldsMustFail()`。

## 5. 状态映射与失败子用例

框架落点：`VersatileIntentContractTest.java` + `VersatileIntentNegativeContractTest.java`。

### FEAT-002.result.completed — 完整三字段结果 → COMPLETED

- **状态**：runnable，P0。
- **追溯**：三字段正常结果 MUST、标准结果状态映射 MUST。
- **G**：`normal-complete`（response_content/intent_id/agent_id 齐全 + terminal）。
- **W**：驱动一轮。
- **T**：Task 终态 COMPLETED。
- **方法**：`normalThreeFieldResultCompletes()`。

### FEAT-002.result.structured-chunk — 三字段结构化结果正确提取（核心输出契约）

- **状态**：query-chunk，P0。
- **追溯**：三字段正常结果 MUST、结构化结果保留 MUST。
- **G**：`normal-complete` fixture；MockVersatileServer 返回三字段齐全的 SSE。
- **W**：直接调用 `VersatileAgentHandler.query(serveRequest)`，不经过 A2A 协议栈。
- **T**：`QueryResponse.result` 中可读出 `response_content`、`intent_id`、`agent_id`，名称和值与 fixture 一致。
- **方法**：`structuredThreeFieldExtractionFromQueryResponse()`。
- **说明**：**不作为 `@Disabled`**——这是 FEAT-002 核心输出契约的唯一验收证据。
  `QueryChunk` 中三字段是否投影到 A2A artifact/metadata 属跨特性测试（见 §9 `cross.result.structured-egress`）。

### FEAT-002.result.completed-variants — 未匹配/需澄清同流程

- **状态**：runnable，P1。
- **追溯**：正常业务结果一致处理 MUST。
- **G**：`unmatched` / `clarify` fixture（同为完整三字段，仅 intent_id/agent_id/response_content 不同）。
- **W**：分别驱动。
- **T**：均 COMPLETED，与匹配成功走相同技术流程（无 `_interrupt`）。
- **方法**：`unmatchedResultCompletes()` / `clarifyResultCompletes()`。

### FEAT-002.interrupt.explicit — 显式中断 → INPUT_REQUIRED

- **状态**：runnable，P0。
- **追溯**：用户交互中断归一 MUST。
- **G**：`interrupt-complete`（signal-match 命中 + prompt/input-requirement/resume-token 齐全）。
- **W**：驱动一轮。
- **T**：Task 终态 INPUT_REQUIRED，帧中带 prompt 文本。
- **方法**：`explicitInterruptMapsToInputRequired()`。

### FEAT-002.error.stream-closed — 断流无 terminal → FAILED（核心回归）

- **状态**：runnable，P0。
- **前置条件**：FEAT-002 特性文档须先将"意图工作流异常断流必须 FAILED"明确写入（当前原始通用规则仍写"无 terminal event → TYPE_INTERRUPT"）。
- **追溯**：标准结果状态映射 MUST「异常断流不得误报为完成/中断」。
- **G**：`stream-closed-no-terminal`（有中间帧，无 End、无 signal，连接关闭）。
- **W**：驱动一轮。
- **T**：Task 终态 **FAILED**，**断言 ≠ INPUT_REQUIRED**（改前应红：现状 `finish()` 把断流当 `TYPE_INTERRUPT`）。
- **方法**：`streamClosedWithoutTerminalMustFailNotInterrupt()`。

### FEAT-002.error.interrupt-incomplete — 中断信息不全 → FAILED

- **状态**：runnable，P0。
- **追溯**：用户交互中断归一 MUST「信息不完整不虚构中断」。
- **G**：`interrupt-incomplete`（signal 命中但缺 prompt/resume-token）。
- **W**：驱动一轮。
- **T**：Task 终态 FAILED，不产生带缺失 prompt 的 INPUT_REQUIRED。
- **方法**：`incompleteInterruptMustFail()`。

### FEAT-002.error.missing-agent-id — 缺 agent_id → FAILED

- **状态**：runnable，P0。
- **追溯**：唯一 Agent 目标 MUST、结构化技术失败 MUST。
- **G**：`missing-agent-id`（response_content/intent_id 有，agent_id 缺 + terminal）。
- **W**：驱动一轮。
- **T**：Task 终态 FAILED，不输出部分正常结果。
- **方法**：`missingAgentIdMustFail()`。

### FEAT-002.error.agent-id-not-unique — agent_id 为数组/多值 → FAILED（黑盒）

- **状态**：runnable，P0。
- **追溯**：唯一 Agent 目标 MUST、结构化技术失败 MUST。
- **G**：`agent-id-array`（agent_id 为数组或包含多个值 + terminal）。
- **W**：驱动一轮。
- **T**：Task 终态 FAILED。
- **方法**：`agentIdArrayMustFail()`。
- **说明**：此用例原标 `unit-only`，但 A2A 黑盒完全可以验证最终 Task 是否为 FAILED，改为 runnable。

### FEAT-002.error.field-type-error — 三字段类型错误 → FAILED（黑盒）

- **状态**：runnable，P1。
- **追溯**：结构化技术失败 MUST。
- **G**：新增 fixture，如 `response_content` 为数字/嵌套对象、`intent_id` 为非字符串。
- **W**：分别驱动。
- **T**：Task 终态 FAILED。
- **方法**：`responseContentNotStringMustFail()` / `intentIdNotStringMustFail()`。

### FEAT-002.error.remote-upstream — 远端 5xx/超时 → FAILED

- **状态**：runnable，P0。
- **追溯**：结构化技术失败 MUST（远端调用失败/超时）。
- **G**：`remote-500`（mock 返回 HTTP 500）；`remote-timeout`（mock 延迟超过 `versatile.timeout`）。
- **W**：分别驱动。
- **T**：Task 终态 FAILED，保留可诊断远端错误/超时分类。
- **方法**：`remoteServerErrorMapsToFailed()` / `remoteTimeoutMapsToFailed()`。

### FEAT-002.cancel.cooperative — 协作式取消（放宽断言）

- **状态**：runnable，P1。
- **追溯**：协作式取消 MUST。
- **G**：`slow-stream`（mock 缓慢逐帧、不终止）。
- **W**：驱动过程中经 A2A cancel 入口取消。
- **T**：
  - **必须断言**：runtime 停止继续输出，最终 Task 落取消语义（非 COMPLETED）。
  - **可选附加断言**：若远端协议支持取消通知，mock 侧连接被断开（不作为通用通过条件；FEAT-002 只保证停止继续消费，远端连接立即断开不是强制承诺）。
- **方法**：`cooperativeCancelStopsConsumption()`。

## 6. 多轮续接子用例（单 Adapter + mock，非 phase3）

框架落点：`VersatileIntentResumeTest.java`（example 模块，新增）。

多轮续接是 FEAT-002 与 FEAT-008 的核心组合能力，不依赖动态 Agent Card 路由。
在单 Adapter + mock 环境中直接覆盖。

### FEAT-002.resume.single-turn — 首次中断后续接成功

- **状态**：runnable，P1。
- **追溯**：原工作流续接请求适配 MUST。
- **G**：`interrupt-complete` → 客户端同 Task 续接 → 工作流返回 `normal-complete`。
- **W**：中断后携续接输入再入同 Task。
- **T**：工作流恢复执行，最终 COMPLETED。
- **方法**：`singleTurnResumeCompletes()`。

### FEAT-002.resume.re-interrupt — 续接后再次中断

- **状态**：runnable，P1。
- **追溯**：再次中断适配 MUST。
- **G**：中断 → 续接 → 工作流再次返回中断信号。
- **W**：中断后携续接输入再入，工作流再次中断。
- **T**：第二次中断正确映射为 INPUT_REQUIRED。
- **方法**：`resumeThenInterruptAgain()`。

### FEAT-002.resume.fail-after-resume — 续接后工作流失败

- **状态**：runnable，P1。
- **追溯**：结构化技术失败 MUST。
- **G**：中断 → 续接 → 工作流返回错误/超时/断流。
- **W**：续接后工作流产生失败。
- **T**：Task 终态 FAILED。
- **方法**：`resumeThenWorkflowFails()`。

### FEAT-002.resume.invalid-resume — 续接信息缺失/失效 → FAILED

- **状态**：runnable，P1。
- **追溯**：续接信息完整性校验。
- **G**：续接请求中 resume_token 缺失、过期或无效。
- **W**：以无效续接信息发起续接。
- **T**：Task 终态 FAILED（不虚构中断，不静默丢失）。
- **方法**：`resumeWithMissingTokenMustFail()` / `resumeWithExpiredTokenMustFail()`。

### FEAT-002.resume.remote-resume-fail — 远端续接请求失败 → FAILED

- **状态**：runnable，P1。
- **追溯**：结构化技术失败 MUST。
- **G**：mock 对续接 HTTP 请求返回 5xx 或超时。
- **W**：发起续接，mock 返回失败。
- **T**：Task 终态 FAILED。
- **方法**：`remoteResumeServerErrorMustFail()`。

## 7. 可观测性与敏感信息子用例

框架落点：`VersatileIntentObservabilityTest.java`（example 模块，新增）。

### FEAT-002.observe.trace-correlation — 调用/中断/续接 trace/correlation 关联

- **状态**：runnable，P1。
- **追溯**：可观测性 MUST「调用、中断、续接保持同一 trace/correlation」。
- **G**：正常调用 + 中断 + 续接场景。
- **W**：完成调用→中断→续接链路。
- **T**：同一次交互的调用、中断、续接日志/事件携带相同 trace/correlation 标识。
- **方法**：`traceCorrelationAcrossCallInterruptResume()`。

### FEAT-002.observe.sensitive-fields — 敏感字段不完整记录到日志

- **状态**：runnable，P1。
- **追溯**：敏感信息保护 MUST「日志避免完整记录用户输入、messages、工作流响应和续接回答」。
- **G**：日志级别设为 DEBUG 或启用敏感信息审计。
- **W**：完成正常调用 + 中断 + 续接。
- **T**：日志中不出现完整的用户输入原文、messages 内容、工作流 response_content、续接回答原文。
- **方法**：`sensitiveDataNotLoggedInFull()`。

## 8. 单测归属（黑盒不可观测 / 契约冲突）

以下经 A2A 黑盒观测不到或属契约冲突项，**不在 acceptance 断言**，归实现方单测（`VersatileRequestExtractorTest` / `VersatileResponseExtractorTest`）：

| 子用例 ID | 内容 | 归属说明 |
|---|---|---|
| `FEAT-002.input.config-missing` | intents 配置缺失 → 带阶段标识的 FAILED | 阶段标识黑盒不可见，单测断言异常 message |
| `FEAT-002.mapping.strategy` | intent-agent-mapping 1:N + first/priority/round-robin | **契约冲突项**：与需求「工作流须返回唯一 agent_id」冲突；默认关闭、待 version-scope 评审后再启用与测试 |

## 9. 跨特性用例（非 FEAT-002 验收范围）

以下用例依赖 A2A 出口投影、结果驱动转发、动态 Agent Card 路由，属于跨特性端到端测试。
**不作为 FEAT-002 验收条件**，在相关特性就绪后于 `agent-runtime-acceptance` 另行覆盖。

### cross.chain.two-layer — 一层 agent_id → 二层转发

- **状态**：cross-feature，P0（跨特性）。
- **追溯**：单实例单工作流 + 结果驱动转发（L2 §4.9）。
- **说明**：FEAT-002 明确规定 Adapter 只调当前 runtime 配置的工作流，一层到二层的 agent_id 路由和转发由调用方（A2AEnabledServeOrchestrator）负责，不属于 FEAT-002。

### cross.chain.query-vs-response — 二层 query 与一层 response_content

- **状态**：cross-feature，P0（跨特性）。
- **追溯**：跨层消息构造（query 保留 + messages 追加）。
- **说明**：消息构造逻辑（`buildForwardRequest`）可通过 `InProcessRemoteAgentCaller` 单独验证，但自动转发链路属于跨特性。

### cross.chain.reclassify — 重新分类交接

- **状态**：cross-feature，P1（跨特性）。
- **追溯**：重新分类场景（L2 §4.7）。
- **说明**：从下游业务工作流自动返回一层重新分类依赖动态 Agent Card 路由，不属于 FEAT-002。

### cross.result.structured-egress — 三字段结构化输出经 A2A 投影

- **状态**：cross-feature，P0（跨特性）。
- **追溯**：A2A 出口投影。
- **说明**：三字段在 `QueryChunk` 中的正确性由 `result.structured-chunk` 验证；投影到 A2A artifact/metadata 是独立的可测性特性。

## 10. 框架落点汇总

| Java 类 | 子用例 | 状态 | 类内 fixture |
|---|---|---|---|
| `VersatileIntentContractTest`（example 模块） | input.*、result.completed、result.completed-variants、interrupt.explicit、error.*、cancel.* | runnable | `MockVersatileServer`、`A2aDriver`、`versatile-sse/*.sse` |
| `VersatileIntentNegativeContractTest`（example 模块） | error.agent-id-not-unique、部分 error.field-type-error | runnable | 同上 |
| `VersatileIntentStructuredChunkTest`（example 模块，新增） | result.structured-chunk | query-chunk | `MockVersatileServer`、`VersatileAgentHandler` 直调 |
| `VersatileIntentResumeTest`（example 模块，新增） | resume.* | runnable | `MockVersatileServer`、`A2aDriver` 续接 |
| `VersatileIntentObservabilityTest`（example 模块，新增） | observe.* | runnable | 日志/事件捕获 |
| 实现方单测（agent-solution） | input.config-missing、mapping.strategy | unit-only | 直接构造 ServeRequest / SSE 帧 |

落点目录：

```text
common/example/versatile-intent-acceptance-demo/src/test/java/.../
  VersatileIntentContractTest.java
  VersatileIntentNegativeContractTest.java
  VersatileIntentStructuredChunkTest.java（新增）
  VersatileIntentResumeTest.java（新增）
  VersatileIntentObservabilityTest.java（新增）
```

## 11. 需求 MUST 追溯

| version-scope MUST | 本档落点 |
|---|---|
| 统一三字段输入 / 当前主输入映射 / 会话消息数组映射 | input.three-field |
| 三字段正常结果 / 结构化结果保留 / 唯一 agent_id | result.completed、result.structured-chunk、error.missing-agent-id、error.agent-id-not-unique |
| 正常业务结果一致处理 / 标准结果状态映射 | result.completed-variants、error.stream-closed |
| 用户交互中断归一 / 原工作流续接 / 再次中断 | interrupt.explicit、resume.* |
| 结构化技术失败 | error.interrupt-incomplete、error.remote-upstream、error.field-type-error、input.config-missing |
| 协作式取消 | cancel.cooperative |
| 可观测 trace/correlation | observe.trace-correlation |
| 敏感信息保护 | observe.sensitive-fields |

## 12. 标签与报告

```java
@Feature("FEAT-002: Versatile 意图识别工作流适配")
@Tag("feat-002")
@Tag("integration")
class VersatileIntentContractTest {
    @Test
    @Tag("blackbox")
    @Stories({@Story("FEAT-002.error.stream-closed: 异常断流分离")})
    @DisplayName("Feat-002 断流无 terminal 映射为 FAILED 而非 INPUT_REQUIRED")
    void streamClosedWithoutTerminalMustFailNotInterrupt() { }
}
```

Allure 报告必须区分 runnable（含 blackbox 和 query-chunk）、unit-only、cross-feature，禁止把单测覆盖冒充黑盒通过，禁止把跨特性用例当作 FEAT-002 验收证据。

## 13. 运行方式

```bash
# FEAT-002 全部黑盒 + query-chunk（example 模块，单 Adapter + mock）
mvn -f common/example/versatile-intent-acceptance-demo/pom.xml test

# 跨特性两层链路（acceptance，另行规划）
./mvnw -Dtest.env=openjiuwen -Dtest=VersatileIntentTwoLayerAcceptanceTest test
```

## 14. 风险与代码生成约束

1. `result-extractions` / `interrupt.*` 的配置键名须与实现方最终命名一致；不一致改 `application.yml` 与 fixture，不改测试逻辑。
2. FEAT-002 特性文档须先明确"意图工作流异常断流必须 FAILED"的专项规则后，`error.stream-closed` 才能成为正式验收用例。
3. `intent-agent-mapping` 与需求 MUST 冲突，默认关闭；未过 version-scope 评审前不写启用态用例。
4. 断流/超时/取消依赖 mock 的连接控制；SSE fixture 必须精确区分「有 End」「有 signal」「都无」。
5. 判别性用例（input.three-field、error.stream-closed 等）**改前应红、改后应绿**；改后仍红即实现方 bug，附 `DriveResult.rawText()` 帧给开发定位。
6. `cancel.cooperative` mock 连接断开为可选附加断言，不作为通用通过条件。
7. `result.structured-chunk` 是 FEAT-002 核心输出契约的唯一验收证据，**不得标记 @Disabled**。
8. 两层转发/重新分类/动态路由的测试属跨特性范围，不在本档 FEAT-002 验收条件中；已在 §9 列出并说明归属。

## 15. 退出标准

- `VersatileIntentContractTest` + `VersatileIntentNegativeContractTest` 全部 runnable 用例转绿。
- `VersatileIntentStructuredChunkTest` 全部 query-chunk 用例转绿（**不得 @Disabled**）。
- `VersatileIntentResumeTest` 全部续接用例转绿。
- `VersatileIntentObservabilityTest` 全部可观测用例转绿。
- 判别性负向用例（断流→FAILED、中断不全→FAILED、缺 agent_id→FAILED、agent_id 多值→FAILED、三字段输入精确映射）均有真实黑盒证据。
- FEAT-002 特性文档已明确"异常断流必须 FAILED"的专项规则。
- unit-only 项由实现方单测覆盖并在追溯表登记。
- 跨特性用例（§9）明确标注 cross-feature，不移入 FEAT-002 验收范围。
- 新增测试代码只在 example 模块；实现仓工作树无修改。
