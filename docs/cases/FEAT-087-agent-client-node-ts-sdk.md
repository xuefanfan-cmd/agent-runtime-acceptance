---
feature_id: FEAT-087
feature_title: Agent-Client支持Node服务TS语言
scope: vNext; FEAT-087 (draft, updated 2026-10-23, rev e7950179) + L2 Feat-Func-087-agent-client-typescript (proposed, non-authoritative, rev e7950179)
deployable_units: ["@openjiuwen/agent-client (TS SDK npm 包)", "mock-runtime (测试替身)", "mock-gateway (测试替身)"]
sut: TypeScript 版 agent-client SDK（agent-solution/common/agent-client/agent-client-sdk-for-typescript，零运行时依赖，Node>=18 与浏览器同包同入口）
status: draft
features: [FEAT-087, FEAT-006, FEAT-007, FEAT-008, FEAT-041, FEAT-026, FEAT-027]
tags: [blackbox, integration, feat-087, dependency-gated]
updated: 2026-10-27
---

# FEAT-087 - Agent-Client 支持 Node 服务 TS 语言测试设计

> 通过公开 npm 包入口 createAgentClient，以可控 A2A 测试服务端（HTTP wire 观测）驱动 TS SDK 完成 BLOCKING / STREAMING / ASYNC 调用、断线恢复、本地工具治理、请求确认与调用树归集，验证"Node 服务（含浏览器直连）不经 Java 即可获得标准调用闭环"的黑盒契约。当前 agent-client-sdk-for-typescript 源码目录尚未交付，本设计为 dependency-gated；产物就绪前用例以 assumeTrue 判 SKIPPED。
>
> 2026-10-27 按特性文档与 L2 最新回刷（e7950179）整改：快照 taskState 断言统一为归一化枚举（wire 才是 TASK_STATE_*，L2 §2.5）；缺 conversationId 归创建配置错误 INVALID_CONFIG（L2 §6.3）；Gateway 入口 agentId 必填由 driver 自动补齐（L2 §2.4）；补齐 A15 创建持续不明有界停止、A16 409 同键重试、A20 A2A OutputSchema 分支、A28 缺 kind、A30 仅调用级 / allExcept、A42 未知动作、A45 两确认独立计时、A48 RESIDUAL_PENDING、A13 非等待输入 / 已关闭变体、A31 确认回答 wire；F087-31 拆分两轮避免 MIXED_PENDING_KINDS（L2 D2）；mock 服务端增加持久规则（onEvery）支撑"每次恢复均失败 / 均成功"注入。

## 1. 测试目标

验证 TS 版 agent-client SDK 在 wire 契约上与 FEAT-087 §3 能力要求一致：npm 交付与同包统一入口（§3.1）、三类入口/三种模式的标准调用与用户补充输入（§3.2）、断线恢复的有界语义（§3.3）、多工具流式输出与调用树（§3.4）、本地工具（§3.5）、请求确认（§3.6）、错误与诊断（§3.7）。

观察 surface 限定为：SDK 公共 API（createAgentClient / invoke / continueInput / getInvocation / respondToConfirmation / registerTool / answerConfirmation / 快照与事件订阅）、HTTP wire 报文（请求 URL、JSON-RPC method、params.message、metadata.clientTools、metadata.attributes、Authorization 头）、handler 与治理回调的被调次数。不读 SDK 内部状态（SessionLoop、ledger、desk 数据结构），不调用内部类，不依赖 L2 的进程内 fake。

断言口径约定：快照 / 事件中的 `taskState` 为归一化枚举（SUBMITTED / WORKING / INPUT_REQUIRED / COMPLETED / FAILED / CANCELED / REJECTED / UNKNOWN，L2 §2.5），wire 报文中的状态才是 `TASK_STATE_*` 原值（L2 §5.8）；用例等待与断言一律使用归一化值，wire 侧用原值。Gateway 入口的创建请求必须携带 agentId（FEAT §3.2、L2 §6.3），driver 对未显式指定 agentId 的 GATEWAY 调用自动补齐固定测试值 `agent-feat087-default`，RUNTIME 调用不上 wire。

## 2. 范围与非范围

### 范围

- **交付面**：npm 包可安装、JS 产物 + TS 类型声明、Node 与浏览器同一入口、运行时零依赖、Node 18 兼容（A1/A2/A3）。
- **注册面（工具）**：描述字段默认值、同 toolId 覆盖、缺 toolId/kind 失败、默认不暴露、两级暴露策略四种组合与过期（A28–A30）。
- **调用面**：Gateway / Runtime 两类入口 × BLOCKING/STREAMING/ASYNC、创建/查询/补充输入三入口、上下文透传、实例约束（A4–A6）。
- **模式语义**：BLOCKING 有界查询与观察窗口、STREAMING 事件投递与轮末待办、不静默降级、ASYNC 受理即结算、查询忙碌（A7–A11）。
- **续接面**：单/多等待点补充输入、缺项本地拒绝、续接校验不新建 Task、续接不重报 ToolView / attributes（A12、A13、A31）。
- **失败语义（恢复）**：流式重订阅优先、订阅失败改查询、有界重试上限（连续失败 3 次、流式恢复 6 次、创建重发 3 次）、Gateway 同键重发幂等、Runtime 受理不确定、SSE 注释帧心跳、空闲超时触发恢复（A14–A19）。
- **输出面**：A2A OutputSchema 与 OpenAI 风格 TOOL_CALL_* 双事件归集、交织保序、缺 START / END 后 RESULT 容错、多跳生产者同 toolCallId 分开、调用树构建/容错/恢复/模式不可用（A20–A27）。
- **本地工具执行面**：请求校验、权限与审批回调、同步/异步 handler、执行超时、一轮串行执行一次批量回填、不重复执行、终态停止回填、执行记录、缺种类待办按 client_tool（A32–A41、A52）。
- **确认面**：确认载荷校验容错、allow/reject/cancel 三动作与 feedback、一轮多确认逐个提交、超时自动 reject 与竞争、重新开放、关闭清理、确认不结算 result（A42–A48、A53）。
- **错误面**：错误类别可编程区分、服务端稳定错误码原样透出并标可重试（A49）。

### 非范围

- **SDK 内部实现**：SessionLoop 状态机内部迁移、ledger/desk/reducer 数据结构归属 L2 单元测试（vitest），本设计只断言公开投影。
- **浏览器 UI**：SDK 不交付 UI 组件（FEAT §6.4），Playwright 仅验证页面可导入并完成调用。
- **真实 Gateway/Runtime 联调（A51）**：A51 要求的完整联调记录与版本清单仍归 agent-client 团队交付；本设计新增 real-server profile（F087-42～45，§9）对真实 Runtime 做行为级增强验证，不替代 A51 交付物。
- **服务端行为改造**：FEAT §8 明确本特性不要求 Gateway/Runtime 改造；服务端缺口不作为本特性失败依据。
- **性能与负载**：资源上限仅以诊断非污染作行为级验证，不做容量测试。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| D:\code\test\docs\develop\02-features\FEAT-087-Agent-Client支持Node服务TS语言.md（draft, 2026-10-23） | 唯一需求 Oracle：§2 实例模型与后端前提、§3 能力要求 MUST 条款、§5 行为语义、§6.3 默认值、§7 与 Java 版差异、§9.2 验收项 A1–A53 |
| D:\code\test\docs\develop\03-architecture\L2-Low-Level-Design\agent-client\Feat-Func-087-agent-client-typescript.md（proposed, non-authoritative） | 接口形态：§2 公共 API 与错误码表、§5 wire 映射样例、§6.1 默认值；仅用于确定断言字段名与 wire 报文，接口落地变化时回到 FEAT 语义裁决 |
| Java 版 agent-client-sdk-for-jvm（dxn-1030） | 未列出差异处的行为基线（间接依据，不直接断言） |

## 4. 部署拓扑

```text
+----------------------+     HTTP (A2A JSON-RPC / SSE)      +------------------------------+
|  Node 子进程驱动      | --->  受控 A2A 测试服务端           |  脚本化响应规则队列：          |
|  resources/feat087/  |     (OkHttp MockWebServer)  <---   |  - SSE 帧序列 / JSON 结果      |
|  driver.mjs          |     记录全部请求报文（wire 观测面）  |  - HTTP/JSON-RPC 错误注入      |
|  import TS SDK 产物  |                                    |  - 连接挂起 / 中途断流 / 幂等   |
+----------------------+                                    +------------------------------+
        |  ^                                                             |
        | NDJSON（快照 / 事件 / op 结果）                                  | 浏览器分支（F087-02）：
        v  |                                                             v Playwright 页面同一入口
   Java 测试断言（JUnit5 + AssertJ，断言全部在 wire 与公开事件投影上）       import 同一包完成调用
```

**边界要求**：

1. **真实依赖（不 mock）**：Node 运行时（>=18）、npm 包解析与产物加载、Playwright 浏览器内核（F087-02）。
2. **真实 SUT**：@openjiuwen/agent-client npm 包产物（npm pack 后安装或直接解析包入口），不经任何 shim 重导内部类。
3. **测试替身**：mock A2A 服务端脚本化 SSE/JSON 响应、错误注入、连接挂起与中途断流；mock-gateway 幂等行为（同键重放缓存、并发 409 IDEMPOTENCY_IN_FLIGHT）在用例内模拟。
4. **观察 surface 限定**：可观察 = SDK 公共 API、HTTP 请求报文与响应、事件订阅回调、handler/审批/权限回调计数；不可观察 = SDK 内部 Map/队列/线程、日志文本、未导出符号。
5. **隔离策略**：每用例随机 conversationId（conv-feat087-<uuid>）；工具名带用例前缀；每用例独立 SDK 实例并 close()。
6. **故障注入手段**：全部在服务端与客户端进程外实施，禁止修改产品对象；SDK 不可达/产物缺失时以 assumeTrue 判 SKIPPED。

## 5. 测试场景矩阵

分层标记：正例（P）、异常（N）、边界（B）、时序/并发（C）、权限与状态机非法迁移（S）。

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F087-01 (P) | npm 产物冒烟：CJS/ESM/Node18 无 crypto | 已 npm pack 产物 | 空白 CJS 工程 require、ESM 工程 import、删除全局 crypto 的 Node18 进程各执行一次 STREAMING 调用 | 三种方式均收到 TASK_STATE_COMPLETED 终态事件；无 Buffer/fs/http 引用报错；产物扫描无 Node 内建模块 | mock SSE |
| F087-02 (P) | Node 与浏览器同包同入口 | 产物 + Playwright Chromium | Node 与页面分别 import 同一入口 createAgentClient 各完成一次 BLOCKING 调用 | 两侧均返回 taskState=COMPLETED、finalText 非空；浏览器常规构建产物扫描无 Node 内建依赖报错 | mock JSON |
| F087-03 (P) | 两类入口 × 三种模式创建与查询 | mock 服务端就绪 | 对 kind=GATEWAY 与 kind=RUNTIME 分别以 BLOCKING/STREAMING/ASYNC 创建调用，ASYNC 后 getInvocation 查询 | GATEWAY 创建 wire 含 params.metadata.agentId，RUNTIME 不含；三模式各自收到受理响应；ASYNC 查询返回 taskState 与 invocationRef 回显，observation=IDLE 且无后续 GetTask 请求 | mock SSE/JSON |
| F087-04 (C,S) | 实例约束：单会话、单活动调用、单请求在途 | 已创建 client | 1) 缺 conversationId 创建（createAgentClient 配置校验）；2) 活动调用未终态时第二次 invoke；3) 服务端挂起响应期间统计并发连接 | 1) 同步抛 code=INVALID_CONFIG（L2 §6.3：创建配置错误）；2) 同步抛 code=CLIENT_BUSY；3) 在途请求数峰值=1，无第二条并发连接 | mock 挂起 |
| F087-05 (P) | 上下文透传与结果回显 | 配置 traceId/correlationId/attributes 与凭证回调 | 创建调用并检查 wire；检查快照回显 | 创建 wire metadata.attributes 含 traceId、correlationId、自定义键值；params.message.contextId=conversationId；每个请求 Authorization 头为凭证回调当前值；快照回显 conversationId、invocationRef、mode、taskState | mock SSE |
| F087-06 (C) | BLOCKING 有界查询与观察窗口 | unaryMs 与观察窗口注入短值 | 创建 BLOCKING，响应为非终态 WORKING，轮询期间服务端持续返回 WORKING 直到窗口耗尽 | 客户端按 initialDelayMs 间隔发 GetTask；窗口耗尽后 observation=ENDED、错误 code=BLOCKING_WINDOW_ENDED、taskState 保持 WORKING（未被改为 FAILED）；Task 未被取消 | mock JSON |
| F087-07 (P,C) | STREAMING 事件投递与轮末待办处理 | SSE 脚本含 output/status/input.required 帧 | 读流并记录事件顺序；同帧携带本地工具待办 | 收到 output、status、input.required、terminal 事件且 seq 递增；待办在响应 EOF 之后才处理（wire 上回填请求晚于最后一个 SSE 帧接收时刻） | mock SSE |
| F087-08 (N) | 入口不支持流式不静默降级 | 服务端对 SendStreamingMessage 返回 -32052 STREAM_NOT_AVAILABLE | 发起 STREAMING 调用 | 快照 observation=INTERRUPTED、错误 code=STREAMING_NOT_SUPPORTED；wire 上无 SendMessage 非流式重发请求 | mock 错误 |
| F087-09 (P) | ASYNC 受理即结算与查询驱动回填 | ASYNC 创建 + GetTask 返回 client_tool 待办 | 创建 ASYNC，result 兑现后 getInvocation 并检查回填 | result 在受理响应后立即兑现（observation=IDLE）；getInvocation 后 wire 依次出现 GetTask 与 SendMessage(returnImmediately=true) 回填；查询 Promise 不等待工具完成 | mock JSON |
| F087-10 (C,N) | 查询忙碌与无 Task 关联 | 流式读取中 / 工具审批等待中；未产生 Task 的失败调用 | 1) 读流期间 getInvocation；2) 工具审批等待期间 getInvocation；3) 无 Task 关联调用 getInvocation | 1)2) 立即 rejected code=CLIENT_BUSY 且 wire 无新 GetTask；3) rejected code=NO_TASK_ASSOCIATION；均不以本地缓存冒充查询结果 | mock SSE |
| F087-11 (P,B) | 用户补充输入：单点与多等待点 | 服务端一轮返回两个显式 user_input 等待点（带 toolCallId） | 单等待点用 input；多等待点用 answers 全量；再用 answers 缺一项 | 单点续接 wire parts 无定向 metadata；多点每个 TextPart 带 metadata.toolCallId 且集合与待办完全相等；缺项在本地 rejected code=INVALID_ARGUMENT，wire 无续接请求，未新建 Task | mock SSE |
| F087-12 (S) | 续接校验非法迁移 | 旧调用已终态 / 非等待输入（停在本地工具待办）/ 已关闭 / 不存在 | 分别对四种旧调用 continueInput | 均本地 rejected code=INVALID_CONTINUATION（不存在为 INVOCATION_NOT_FOUND）；wire 无携带 taskId 的续接请求；Task 数量不变 | mock SSE/JSON |
| F087-13 (C) | 流式断线恢复：重订阅优先 | 已知 taskId 后 SSE 中途断流 | 分别以 kind=GATEWAY 与 kind=RUNTIME 各执行一次断流后观察恢复请求序列 | 两类入口下恢复均先发 SubscribeToTask（params.id=taskId）；订阅失败时改发 GetTask；无 SendStreamingMessage 创建重发；每轮 wire 创建请求总数=1 | mock 断流 |
| F087-14 (C,B) | 有界恢复与预算用尽 | 默认配置 | 恢复观察持续失败（每次均网络错误） | 连续失败第 3 次后停止（观察请求总数不超过 3，不发第 4 次）；observation=ENDED、错误 code=RECOVERY_EXHAUSTED、stale=true；配置 maxConsecutiveFailures=2 后上限随之变为 2；停止后 wire 无取消请求 | mock 错误 |
| F087-15 (C,B) | Gateway 创建结果不明同键重发与有界停止 | mock-gateway 幂等行为三分支：首个创建响应丢失后返回缓存 JSON；并发同键 409；创建持续不明 | 创建 STREAMING：a) 首个创建无响应；b) 首个创建返回 409 IDEMPOTENCY_IN_FLIGHT；c) 每次创建均无响应（持久规则） | a) 相同 params.message.messageId 的创建请求最多重发 3 次（200/400/800ms 退避），缓存命中返回同一 taskId；b) 409 可重试，同键重试直至成功；c) 有界停止：创建请求总数≤原始 1+重发 3，acceptance=UNKNOWN，报告 ACCEPTANCE_UNKNOWN / OBSERVATION_INTERRUPTED | mock 幂等/挂起 |
| F087-16 (N) | Runtime 创建结果不确定 | 直连 Runtime，创建响应丢失 | 创建 STREAMING，首个创建无响应 | 不重发创建（wire 创建请求总数=1）；快照 acceptance=UNKNOWN、错误 code=ACCEPTANCE_UNKNOWN，携带核对线索（diagnosticTaskRef 或诊断事件）；不新建 Task | mock 挂起 |
| F087-17 (C) | SSE 注释帧心跳与空闲超时 | 流先发注释帧再静默 | 注释帧间隔小于 sseIdleMs，随后静默超过阈值 | 注释帧期间不产生业务事件（无 output/status）且链路保持活动；静默超阈后触发一次重订阅（wire 出现 SubscribeToTask），无创建重发 | mock SSE |
| F087-18 (P) | 双事件体系归集与错误隔离 | SSE 含 A2A OutputSchema 产物（toolCallId 读 Artifact 内容 payload、producer 读 artifact.metadata.agentEvent.source，L2 §3.9；字段形态待 W6 校准）与 OpenAI 风格 TOOL_CALL_START/ARGS/END/RESULT；一个工具 RESULT 前注入错误事件 | 读流并读取 tool.output 归集 | 两体系各归集出 tool.output：名称、参数、最终结果正确；交织到达时同 toolCallId 按到达顺序追加；错误只落在该工具的归集结果，其他工具不受影响；无 toolCallId 的输出走 output 事件并保留 artifactId/index/顺序 | mock SSE |
| F087-19 (B) | 归集容错与多跳生产者 | 缺 START 从 ARGS 起步；END 后 RESULT；两个生产者复用同一 toolCallId | 读流 | 缺 START 仍从首个事件开始归集；END 后到达的 RESULT 不丢失；两生产者复用同一 toolCallId 时分开归集不合并；外层父 Task 不覆盖实际生产者 | mock SSE |
| F087-20 (P,B) | 调用树构建、容错与模式不可用 | 五层委派 + 兄弟分叉；环/多父/超限诊断注入 | STREAMING 读流订阅 callTree；再以 BLOCKING 调用尝试订阅 | 五层委派结构与兄弟活跃状态正确（节点输出、状态、当前活跃发言者齐全）；输出先于关系到达时补挂；重复关系不重复建节点；环/多父/超限仅产生诊断且已知结构不受污染；断线恢复后树标记 PARTIAL；BLOCKING 下 callTreeAvailability=UNAVAILABLE_FOR_MODE | mock SSE |
| F087-21 (B) | 工具注册与默认值 | 注册完整描述符、缺 toolId、缺 kind、同 toolId 二次注册 | 逐项注册并读取有效描述 | 缺 toolId 或缺 kind 均同步抛 code=INVALID_ARGUMENT；缺省 timeoutMs=30000、requiresApproval 按 kind（ACTION=true/OBSERVATION=false）、inputSchema 为 {"type":"object"}；同 toolId 后注册覆盖前注册；展示名不参与执行匹配 | 本地 |
| F087-22 (S) | 默认不暴露与空清单拒绝 | 注册两个工具但未配置任何暴露策略 | 创建调用；服务端仍请求该工具 | 创建 wire 无 clientTools 字段（清单为空）；请求按 REJECTED/tool_not_declared 回填，handler 执行次数=0，审批回调调用次数=0 | mock SSE |
| F087-23 (S,B) | 两级暴露策略组合与过期 | 会话级/调用级组合矩阵（仅会话级 allow、会话级 all + 调用级 allow、仅调用级 allow、会话级 allExcept）+ 过期时间 | 逐组合创建调用并检查创建 wire，含 expiresAt 已过 | 创建 wire clientTools 为两级交集（仅一级时为该级；两级均未配置时省略字段）；allExcept 排除黑名单后计算清单；expiresAt 取较早者；过期后请求回填 REJECTED/context_expired | mock SSE |
| F087-24 (P) | 创建上报与续接不重报 | 显式暴露一个工具 | 创建、工具回填、补充输入、确认回答逐个检查 wire（确认轮回答 allow） | 仅创建请求 params.metadata.clientTools 含该工具；工具回填、补充输入、确认回答请求 params.metadata 均无 clientTools 且无 attributes；确认回答 wire 为占位 TextPart（动作字符串 "allow"）+ guardrail_confirm DataPart（含 action、tool_call_id） | mock SSE |
| F087-25 (N,B) | 请求校验与参数检查 | 注册带 required=["city"] 工具；服务端请求变体：未声明、未注册、已过期、缺必填、参数缺失、参数为数组 | 逐变体创建调用并检查回填 | 未声明回填 tool_not_declared；未注册回填 tool_not_found；已过期回填 context_expired；缺必填回填 invalid_tool_arguments 且 handler 执行=0；参数缺失或非对象（含数组）按空对象归一，无必填参数的空参数可通过检查并执行；回填 TextPart 带 metadata.toolCallId 且 JSON 含 toolCallId/status/errorCode | mock SSE |
| F087-26 (S) | 治理回调与审批 | Observation+Action 各一；四种配置：无回调、仅审批拒绝、仅权限拒绝、审批批准 | 逐配置创建调用（显式暴露）并请求两个工具 | 未配置时两者均执行（审批/权限计数=0）；审批拒绝时 Action 执行=0、记录 status=REJECTED、errorCode=rejected、回填 rejected；权限拒绝时不执行、errorCode=permission_denied（或自定义码）；批准时执行=1 | mock SSE |
| F087-27 (C,B) | 同步/异步 handler 与执行超时 | timeoutMs=1000：异步 handler 挂起、同步 handler 睡 3s（两者均在请求集合内）、异步正常、同步抛错 | 一轮请求四个工具 | 挂起异步在 1s 记 status=TIMEOUT 并继续下一项，迟到完成只写 lateCompletion=ERROR 不改结果不重复回填（L2 §3.5）；同步越过期限返回时按实际经过时间记 TIMEOUT（FEAT §5.3）；正常项 status=OK；抛错项 status=ERROR、errorCode=tool_execution_error；ctx signal 已 abort | mock SSE |
| F087-28 (C) | 一轮串行执行一次批量回填 | 一轮 _interrupt.items[] 两个工具请求（同名同参数） | 观察执行时序与回填 wire | 两个 handler 串行执行（start2 晚于 end1）；第一项完成后 wire 无请求；全部就绪后一次提交一个续接请求，含两个 TextPart 各带 metadata.toolCallId，集合与待办完全相等；一项失败也随批提交 | mock SSE |
| F087-29 (C) | 不重复执行与回填结果不明 | 同一 toolCallId 在流、恢复重订阅中重复出现；回填请求超时 | 重复投影后恢复；回填挂起超时 | handler 与审批各只调用一次；按 FEAT §5.2 核对一次：Task 仍停原待办则同批次原报文重发至多一次（messageId 不变），仍不明则 observation=ENDED、code=SUBMISSION_UNKNOWN；handler 计数始终=1 | mock 挂起 |
| F087-30 (S) | 终态停止回填与执行记录 | 回填前 Task 已进入终态 | 工具结算后检查 wire 与 toolRecords | 无回填请求（delivery=STOPPED）；toolRecords 可读出每项 status、approval、resultSummary（不超过 256 字符）、错误；服务端拒绝回填时映射 code=CONTINUATION_REJECTED 诊断 | mock JSON |
| F087-31 (B) | 缺种类待办处理 | 两个独立轮次变体：a) 单项带工具名无种类；b) 单项缺 toolName（不与 a 同轮注入，避免命中同轮不混合种类前提 MIXED_PENDING_KINDS，L2 D2） | 逐变体创建调用并观察 | a) 带工具名的项按本地工具请求处理并执行（与 Java 一致，A52）；b) 缺 toolName 的项不执行，按用户输入等待开放（input.required 事件）并产生 INPUT_RESUME_TARGET_MISSING 诊断 | mock SSE |
| F087-32 (B) | 确认载荷校验容错 | guardrail_ask 变体：缺 command_summary、risk_hint 类型错误、risk_hint=extreme、actions 为空数组、actions 含未知动作、规则/文件为空数组 | 逐变体注入确认待办 | 非法变体只产生 INVALID_CONFIRMATION/UNKNOWN_RISK 诊断，无 confirmation.required 事件、不默认批准；extreme 显示 risk=UNKNOWN 且诊断；actions 为空不开放；actions 含未知动作时忽略该动作、诊断 UNKNOWN_ACTION，保留可识别动作正常开放；空规则/文件列表合法并正常开放 | mock SSE |
| F087-33 (P) | 三种动作与 feedback | 一个开放确认，allowedActions 含三动作 | 分别回答 allow/reject/cancel 并附 feedback | wire 为 TextPart（动作字符串）+ guardrail_confirm DataPart（含 action、tool_call_id、feedback）；allow 后 Task 继续至 COMPLETED；reject 后 Task 继续；cancel 后 Task 到 CANCELED；均未新建 invocation | mock SSE |
| F087-34 (C) | 一轮多个确认逐个提交 | 一轮 items[] 两个确认 | 依次回答 | 两个 confirmation.required 事件均投递；第一回答提交且响应结束后才提交第二个；第二个回答按服务端最新状态提交；全程在途请求不超过 1 | mock SSE |
| F087-35 (C) | 确认超时与回答竞争 | confirmation.timeoutMs=200/300；可选动作含/不含 reject 两分支；同轮两个确认；用户回答与超时竞争 | 等待超时；回答第一个确认且其提交在途期间观察第二个 | 默认（未配置）不自动回答；含 reject 时到期发 confirmation.timeout(autoRejected=true) 并提交 reject；不含 reject 时仅 confirmation.timeout(autoRejected=false) 加 ACTION_NOT_AVAILABLE 诊断且保持 OPEN 可再答；同轮两确认各自计时：回答第一个、提交在途期间第二个照常到期 autoRejected=true（其回答按最新待办集合结算，L2 §3.8 计时隔离）；竞争时仅一次提交，另一方 rejected code=ROUND_ALREADY_ANSWERED | mock SSE |
| F087-36 (S) | 重新开放与关闭清理 | 回答提交失败；提交后服务端仍请求同一确认；关闭有未决确认的调用 | 回答、失败、再答；close | 失败或仍在请求时确认重新开放（round 加 1、重新计时、再次 confirmation.required）；close() 后回答本地 rejected code=CLOSED、计时器清理、发 confirmation.resolved(CLOSED)；有未决待办时产生 RESIDUAL_PENDING 诊断（FEAT §5.4 / L2 §3.11）；wire 无取消请求 | mock 挂起 |
| F087-37 (S) | 确认不结算 result | BLOCKING 调用中途出现确认 | 不回答观察 result；回答后继续观察 | 未回答且未启用超时时 result 保持未结算（无终态）；回答后同一 result 兑现为最终快照（taskState=COMPLETED 或下一结算点）；期间无轮询请求 | mock SSE |
| F087-38 (N) | 错误表面与稳定错误码 | 服务端注入 401 AUTH_INVALID、409 IDEMPOTENCY_PAYLOAD_MISMATCH、-32001 TASK_NOT_FOUND、429 RATE_LIMITED | 逐错误创建/恢复 | 错误对象 category 分别为 AUTH/SERVER/SERVER/TRANSPORT 且可编程区分；serverCode 原样等于 AUTH_INVALID/IDEMPOTENCY_PAYLOAD_MISMATCH/TASK_NOT_FOUND/RATE_LIMITED；retryable 分别为 false/false/false/true；TASK_NOT_FOUND 不新建 Task（observation=INTERRUPTED） | mock 错误 |
| F087-39 (C,B) | STREAMING 恢复成功但 Task 持续运行时有界停止 | 默认流式恢复上限（6） | 创建后断流；每次恢复订阅“成功”（返回 RUNNING 帧后流正常结束），Task 始终不进入终态 | 恢复订阅次数不超过 6（不发第 7 次）；observation=ENDED、错误 code=RECOVERY_EXHAUSTED、stale=true；创建请求总数=1，无取消请求 | mock SSE |
| F087-40 (B) | 目录更新与新工具不进在途 | 创建前仅注册并暴露 t-40a | 服务端一轮同时请求 t-40a 与 t-40b（t-40b 在调用结束后才注册） | 创建清单 clientTools 只含 t-40a；t-40b 在途分发时按 REJECTED/tool_not_declared 拒绝且仍回填其 toolCallId；handler 执行 t-40a=1、t-40b=0；中途注册不追溯改变已上报清单 | mock SSE |
| F087-41 (B) | 同 Task 续接共享树，新 Task 新树 | STREAMING 调用中途等待用户补充输入 | 续接前/后观察调用树快照；再发起新调用观察其树 | 续接后修订号继续增长（树未重建、根不变）；新调用生成独立新树（不同调用句柄、修订号从头开始）；字段名与 Java CallTreeSnapshot 1:1（L2 §3.10） | mock SSE |
| F087-42 (P, real) | 真实 Runtime 直连流式调用闭环 | real-server profile：feat087.real.tool.url 已配置（client-tool DeepAgent demo 已启动，§9） | kind=RUNTIME 直连真实 Runtime，STREAMING 发起普通对话调用 | result 兑现 taskState=COMPLETED、finalText 非空、conversationId / invocationRef / mode 回显 | 真实 Runtime |
| F087-43 (P, real) | 真实 DeepAgent 本地工具闭环 | 同上且 LLM 可用 | 注册 getLocalWeather（required=[city]）与 createCalendarEvent（required=[title,date,durationMinutes]）并全量暴露，构造强工具倾向提示发起 STREAMING 调用 | Task 终态 COMPLETED、finalText 非空；产生的本地工具执行记录全部 status=OK（已声明且已暴露，不得出现 rejected/timeout/error）；handler 执行与回填一致（A31/A33/A35/A37 行为级增强） | 真实 Runtime |
| F087-44 (P, real) | 真实 travel 用户补充输入续接 | feat087.real.travel.url 已配置（multi-react-travel demo，§9） | 信息不全提示触发 INPUT_REQUIRED（request_user_input），continueInput 补全信息 | 等待点出现（LLM 未触发等待则按假设 SKIPPED 并记录）；续接沿用同一 taskId、不新建 Task；终态 COMPLETED（A12/A13 行为级增强） | 真实 Runtime |
| F087-45 (P, real) | 真实多跳流式调用树冒烟 | 同上 | STREAMING 触发 mainplan→trip→hotel 多跳委派 | callTreeAvailability=AVAILABLE；委派关系与树快照为软断言（LLM 行为非确定，仅记录证据） | 真实 Runtime |

### 5.1 显式 deferred / 白盒联动项

| 项 | 承接方 |
|---|---|
| SessionLoop 状态机全迁移、代际失效、名额计数内部不变式 | L2 §7.1 状态机测试（vitest + 假 Transport），黑盒仅以"在途不超过 1 / 立即忙碌"间接覆盖 |
| sse-decoder 分帧、exposure 组合纯函数、interrupt 解析、observation 文本键顺序 | L2 §7.1 单元测试 |
| 官方 @a2a-js/sdk 1.3.0 fromJSON 与 toJSON 往返契约 | L2 §7.1 契约测试（devDependency） |
| CallTreeReducer 细粒度结构/诊断矩阵（多父节点、256 节点/512 边/8 MiB 超限、补挂顺序、重复边全矩阵）与断线恢复 PARTIAL 标记（A25/A26 细项） | L2 §3.10：移植 Java CallTreeReducerTest 的 TS 单元测试；黑盒 F087-20/F087-41 仅覆盖五层链、兄弟分叉、环诊断与共享/新建树冒烟 |
| 执行记录的订阅推送（A41 的“可订阅”半边） | 黑盒 F087-30 覆盖可读（toolRecords）；订阅推送随 L2 §7.1 单元测试 |
| 真实 Gateway/Runtime 联调与版本记录（A51） | agent-client 团队联调交付，本设计不代验收 |
| 兼容清单 Node/浏览器/TS 版本矩阵（A3） | 发布说明交付物，用例仅验证 Node 18 冒烟可运行 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| @openjiuwen/agent-client npm 产物 | 真实 SUT | feature_source 下 agent-client-sdk-for-typescript 构建产物；产物缺失时全部用例 SKIPPED（dependency-gated） |
| Node 子进程驱动 driver.mjs | Fixture | 从 stdin 读场景操作序列，import SDK 公共入口执行，按 NDJSON 输出快照/事件/op 结果；进程外超时防挂死 |
| MockWebServer mock A2A 服务端 | Fixture | 脚本化 SSE/JSON 响应、HTTP/JSON-RPC 错误、连接挂起、中途断流、幂等缓存；支持一次性规则与持久规则（onEvery，用于每次恢复均失败 / 均成功的注入）；记录全部请求报文供 wire 断言 |
| Playwright Chromium 页面 | Fixture | F087-02 浏览器直连分支：页面 import 同一入口完成一次调用 |
| handler / approvalProvider / policyGuard / credentials 计数器 | Fixture | 在 driver 内实现，被调次数回传 Java 侧断言 |
| 真实 client-tool DeepAgent demo（agentcore-ext-client-tool-deepagent-demo） | real-server Fixture | F087-42/43：本地 `agent-solution/common/example` 真实 Runtime（/a2a/ 入口），LLM 凭据经进程环境变量注入（§9）；仅 real-server profile 加载 |
| 真实 multi-react-travel demo | real-server Fixture | F087-44/45：mainplan→trip→hotel 三跳真实链路，验证用户补充输入续接与流式调用树（§9）；仅 real-server profile 加载 |
| 凭证测试值 / 工具 canary 数据 | Canary | 无敏感数据；凭证仅验证 Authorization 头传递 |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

1. **F087-03**：两入口 × 三模式创建与查询闭环，TS SDK 可用性的最低证明。
2. **F087-05 + F087-24**：wire 契约与 Java 版同构：contextId / clientTools / attributes / 续接不重报，是"与 Java 行为一致"的硬证据。
3. **F087-28**：一轮多工具串行执行 + 一次批量回填（TextPart 定向集合与待办集合完全相等），TS 版新增核心能力，失败即集成不通。
4. **F087-13 + F087-14**：断线恢复只观察不重发创建、有界停止，失败语义必须与基线同构，禁止无限重试或新建 Task。
5. **F087-33 + F087-37**：确认三动作语义与"等待确认不结算 result"。
6. **F087-04**：实例约束（缺 conversationId 失败、第二调用 CLIENT_BUSY、在途不超过 1），单实例并发模型根约束。

负向场景必须同时命中"目标错误码 + 未发生非法推进"：F087-08（STREAMING_NOT_SUPPORTED + 无非流式重发）、F087-12（INVALID_CONTINUATION + 无新建 Task）、F087-16（ACCEPTANCE_UNKNOWN + 创建请求总数=1）、F087-38（TASK_NOT_FOUND + 不新建 Task）。

## 8. 执行策略

- **Smoke 集合**（产物就绪后必跑）：F087-01、F087-03、F087-05、F087-07、F087-28、F087-33。
- **Full suite**：全部矩阵场景，按类分组执行（delivery / invocation / recovery / output-calltree / local-tool / confirmation）。
- **P0 必须全绿**：F087-01、02、03、04、06、07、08、09、11、13、15、16、18、20、21、22、23、24、25、26、27、28、29、31、32、33、34、35、37、38、39（对应 FEAT §9.2 P0 验收项）。
- **依赖门禁**：系统属性 feat087.sdk.dir（或环境变量 FEAT087_SDK_DIR）指向已构建的 agent-client-sdk-for-typescript；node 在 PATH（>=18）；Playwright 仅 F087-02 需要。任一缺失则相关用例 assumeTrue SKIPPED，不计 FAIL。
- **推荐 agent**：配置未指定 recommended_agent，默认在本地 openjiuwen 环境以 JUnit Tag 过滤执行。
- **real-server profile**：F087-42～45 归入独立 Tag `real-server`，仅在提供真实服务端地址（feat087.real.tool.url / feat087.real.travel.url）时执行，缺省 SKIPPED；CI 主门禁不包含 real-server，由人工联调触发（部署与环境准备见 §9）。
- **执行命令**：

```bash
# 1. 构建并打包 SUT（产物就绪后）
cd D:/code/test/agent-solution/common/agent-client/agent-client-sdk-for-typescript
npm install && npm run build && npm pack

# 2. 执行本特性全部用例（按 Tag 过滤）
cd D:/code/test/agent-runtime-acceptance
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-087 \
  -Dfeat087.sdk.dir=D:/code/test/agent-solution/common/agent-client/agent-client-sdk-for-typescript \
  -Dmaven.test.failure.ignore=true

# 3. 仅 Smoke
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-087 -DincludeTags="smoke-feat-087"

# 4. 仅 real-server profile（需先按 §9 启动真实服务端）
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-087 -DincludeTags="real-server" \
  -Dfeat087.sdk.dir=D:/code/test/agent-solution/common/agent-client/agent-client-sdk-for-typescript \
  -Dfeat087.real.tool.url=http://127.0.0.1:18210/a2a/ \
  -Dfeat087.real.travel.url=http://127.0.0.1:8091/a2a/ \
  -Dmaven.test.failure.ignore=true
```

## 9. 真实服务端联调 profile（real-server）

### 9.1 目标与断言策略

对真实 Runtime（本地 example demo，FEAT §8 服务端基线）执行行为级增强验证，不替代 mock 主门禁，也不替代 A51 联调交付。真实 LLM 输出非确定，断言只覆盖 SDK 确定性契约：

1. **终态可达**：调用最终结算（COMPLETED），conversationId / invocationRef / mode 回显一致。
2. **同 Task 续接**：用户补充输入续接沿用同一 taskId，不新建 Task（A12/A13）。
3. **已声明工具不拒绝**：客户端已注册且全量暴露的工具，任何执行记录 status 必须为 OK（不得 rejected/timeout/error，A31/A35/A37）。
4. **调用树模式可用**：STREAMING 下 callTreeAvailability=AVAILABLE（A24/A27）。
5. LLM 是否触发工具/委派/等待输入属非确定行为：未触发时以 SKIPPED（assumption）或软断言记录证据，不计 FAIL。

### 9.2 服务端部署（两个 demo）

| Demo | 用途 | 入口 | F087 场景 |
|---|---|---|---|
| agentcore-ext-client-tool-deepagent-demo | 本地工具闭环（F087-42/43） | `http://127.0.0.1:18210/a2a/`（agentId `client-tool-deepagent`） | F087-42、F087-43 |
| multi-react-travel-demo | 多跳委派 + request_user_input（F087-44/45） | `http://127.0.0.1:8091/a2a/`（mainplan 入口） | F087-44、F087-45 |

> URL 以 demo 启动日志为准；SDK 对不以 `/a2a` 结尾的 url 自动补齐（L2 §6.2）。

启动命令（PowerShell，LLM 凭据与代理仅经进程环境变量传入，不写入代码 / YAML / README / 请求报文）：

```powershell
# ---- 当前联调环境 LLM 与代理（内部凭据，勿外传；正式执行请替换为个人 key）----
$env:LLM_API_BASE  = "https://ark.cn-beijing.volces.com/api/coding/v3"   # demo 按需拼接 /chat/completions，以 demo 实际拼接方式为准
$env:LLM_API_KEY   = "<your-ark-api-key>"
$env:LLM_MODEL     = "glm-5.3-flash"
$env:HTTP_PROXY    = "http://<username>:<password>@proxyhk.huawei.com:8080"
$env:HTTPS_PROXY   = "http://<username>:<password>@proxyhk.huawei.com:8080"
$env:NO_PROXY      = "localhost,127.0.0.1,::1"

# ---- 1) client-tool DeepAgent demo（F087-42/43）----
cd D:/code/test/agent-solution/common/example/agentcore-ext-client-tool-deepagent-demo
$env:DEEPSEEK_BASE_URL = $env:LLM_API_BASE   # 该 demo 以 DEEPSEEK_* 命名读取同源 OpenAI 兼容配置
$env:DEEPSEEK_API_KEY  = $env:LLM_API_KEY
$env:DEEPSEEK_MODEL    = $env:LLM_MODEL
$env:CLIENT_TOOL_DEMO_PORT = "18210"
mvn spring-boot:run

# ---- 2) multi-react-travel demo（F087-44/45，另开终端）----
cd D:/code/test/agent-solution/common/example/multi-react-travel-demo
# mainplan:8091 / trip:8092 / hotel:8093 按其 README 启动，LLM 配置复用上述 LLM_* 环境变量
mvn spring-boot:run
```

### 9.3 执行门禁

| 系统属性 | 环境变量回退 | 控制范围 | 缺省行为 |
|---|---|---|---|
| feat087.real.tool.url | FEAT087_REAL_TOOL_URL | F087-42、F087-43 | 未配置 → assumeTrue SKIPPED |
| feat087.real.travel.url | FEAT087_REAL_TRAVEL_URL | F087-44、F087-45 | 未配置 → assumeTrue SKIPPED |

SDK 依赖门禁（feat087.sdk.dir / FEAT087_SDK_DIR、node>=18）与主 profile 共用；real-server 用例复用同一 driver.mjs 驱动，仅服务端换为真实 Runtime（kind=RUNTIME 直连），无 MockWebServer 参与。

**附录：与 FEAT §9.2 验收项追溯**

| 验收项 | 场景 ID | 验收项 | 场景 ID |
|---|---|---|---|
| A1 | F087-01 | A31 | F087-24 |
| A2 | F087-02 | A32 | F087-25、F087-40 |
| A4 | F087-03 | A34 | F087-26 |
| A5 | F087-04 | A35、A36 | F087-27 |
| A6 | F087-05 | A37 | F087-28 |
| A7 | F087-06 | A38、A39 | F087-29 |
| A8 | F087-07 | A40、A41 | F087-30 |
| A9 | F087-08 | A52 | F087-31 |
| A10 | F087-09 | A42 | F087-32 |
| A11 | F087-10 | A43 | F087-33 |
| A12 | F087-11 | A44 | F087-34 |
| A13 | F087-12 | A45、A46 | F087-35 |
| A14 | F087-13 | A47、A48 | F087-36 |
| A15 | F087-14、F087-39 | A53 | F087-37 |
| A16 | F087-15 | A49 | F087-38 |
| A17 | F087-16 | A18 | F087-14 |
| A19 | F087-17 | A20、A22 | F087-18 |
| A21、A23 | F087-19 | A24、A25 | F087-20 |
| A28 | F087-21 | A29 | F087-22 |
| A30 | F087-23 | A33 | F087-25 |
| A27 | F087-20、F087-41 | A26 | L2 §3.10 单测（见 §5.1） |
| A3、A50、A51 | 非范围（见 §2 / §5.1） | A31 | F087-24、F087-40 |

real-server profile（F087-42～45，§9）为行为级增强证据，不改变上表归属：A4/A7/A8 ← F087-42；A28/A31/A33/A35/A37/A52 ← F087-43；A12/A13 ← F087-44；A24/A27 ← F087-45。
