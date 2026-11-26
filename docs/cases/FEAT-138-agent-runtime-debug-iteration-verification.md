---
feature_id: FEAT-138
feature_title: 智能体运行调试与迭代验证
scope: agent-dev-workbench; 特性文档与 L2 updated 2026-11-19，实现核对基线 e3785f804cf8c8ca81337253159d7824c3b7efa5
deployable_units: [agent-dev-workbench 后端（App/RuntimeDebugService/RuntimeDebugStore）, Agent Host（预览转发/受限运行控制）, 业务 Runtime（用户工程实例）]
sut: agent-dev-workbench 运行调试控制面（运行目标查询/控制操作/业务预览/历史与取消 HTTP API）及 Agent Host 转发行为
status: draft
features: [FEAT-138, FEAT-136, FEAT-137]
updated: 2026-11-25
---

# FEAT-138 智能体运行调试与迭代验证 — 测试设计

## 1. 测试目标

验证 agent-dev-workbench 运行调试特性的外部可观察行为：开发者通过开发会话查询工程运行目标与实例状态，提交启动/停止/重启控制操作（异步受理 + 幂等去重），向已就绪目标提交文本业务预览（受理 → preview.* 事件 → 真实业务回答/等待输入/失败），读取近期业务调试历史并取消当前转发。观察 surface 限定为 L2 §2.1 外部入口清单所列 HTTP API 与 /ws 下行业务事件。

除契约级用例外，本设计按场景文档 FRS-137 §5 端到端主成功旅程与 §6 标准流程用例（WB-02-01～07）设置端到端用例层（F138-E2E-01～07）：每条端到端用例与一条标准流程用例一一对应，按其操作步骤顺序串联执行并逐条断言预期结果，不将标准流程的步骤拆分到不同测试用例中；FRS §4 功能要求、§7 异常处理与恢复、§8 验收标准的追溯见 §9 追溯矩阵。

本轮验收为实际交付的工程类型各绑定至少一条代表性真实业务调用（见 F138-E2E-02/03/04 的真实输入步骤）；对目标选择、日志展开、等待输入与旧实例结果隔离四处页面交互补浏览器证据（§5.2）。验收结论只覆盖实际执行的工程与输入：未部署或未执行的工程类型不纳入本轮判定；环境阻塞与已观测到的产品失败在报告中分别记录，不以环境阻塞替代产品失败判定。

本测试不编译或调用 SUT 内部类，不读取 SUT 内部状态、控制状态文件或事件 JSONL 存储内部记录；所有断言写在 wire 契约（HTTP 状态码、JSON 字段与取值、事件 type 字段）上。期望值逐字取自特性文档 §2.2/§3/§5 与 L2 §2/§3 各契约章节。

## 2. 范围与非范围

### 范围

1. 运行目标查询契约：GET runtime 返回 `manifestVersion`/`defaultTargetId`/`targets[]`（每项含 `id`/`runtimeId`/`state`/`requires`）/`runtimes[]`（每项含 `id`/`name`/`state` ∈ {idle, stopped, unhealthy, ready}/`instanceId`/`generation`/`affectedTargets`）/`operations`；快照去除进程地址、PID 等内部路由信息；未知会话 404（L2 §2.2）。
2. 运行操作契约：POST operations 202 受理（`status=="running"` 初值，响应不等待执行完成）；非法 operationId/action 400 `INVALID_OPERATION`；未知 Runtime 400 `UNKNOWN_RUNTIME`；同 ID 同内容复用既有记录；同 ID 异内容 400 `IDEMPOTENCY_CONFLICT`；停止/重启携带过期 `impactVersion`/`expectedGeneration` → 409 `CONFIRMATION_STALE`（L2 §2.3）。
3. 业务预览契约：POST preview 202 返回 `runId`（等于 `requestId`）；空白文本与超 4000 字符 → 400 `invalid message`；非法 `conversationId`/`requestId` → 400 相应 INVALID 代码；同 requestId 同内容 → 202 `duplicate==true` 且 runId 与首响一致；同 requestId 异内容 → 400 `IDEMPOTENCY_CONFLICT`；同 `conversationId` 改绑目标或实例 → 400 `CONVERSATION_BINDING_CHANGED`；目标未就绪/实例变化 → 409 `RUNTIME_NOT_READY_OR_CHANGED`；转发占用中 → 409 `preview already running`（L2 §2.4）。
4. 业务事件与分区契约：`preview.started`/`preview.answer`（`mode` ∈ {append, replace}、`awaitingInput`）/`preview.raw`/`preview.ended`/`preview.failed` 事件外壳含 `sessionId`/`seq`/`ts`/`type` 与业务关联字段 `runId`/`targetId`/`instanceId`/`conversationId`；业务回答与原始帧分区，不混作最终答复（L2 §2.4/§5.3）。
5. 历史读取契约：GET preview/history 返回 200 + `items` 数组；与开发会话历史分开，业务历史不返回开发会话的编码历史（L2 §2.5，单向承诺——不约束开发事件流 /events 的呈现）。
6. 取消契约：POST preview/cancel 返回 200 `ok` 对象，仅确认受理，不表达底层业务任务已取消（L2 §2.5）。
7. 迭代复验契约：重启后 `instanceId` 变化且 `generation` 递增；实例变化后旧业务会话不能续接（`CONVERSATION_BINDING_CHANGED` 或要求新业务会话）；仅画布刷新不产生新实例（L2 §3.2/§3.7）。
8. 端到端标准流程：按 FRS-137 §5 主成功旅程与 §6 标准流程用例串联的端到端用例 F138-E2E-01～07，与 WB-02-01～07 一一对应（工程准备、启动目标并完成第一次调用、正常答复与工作日志、Workflow 追问后同会话继续、修改缺陷并使用原输入复验、确认共享实例的停止或重启、连接中断与历史恢复）；每条按标准流程的步骤顺序执行并断言其预期结果（FRS §5/§6）。
9. 补充契约：操作结果查询与受理/就绪分离（F138-25，FR-03）、有效影响确认停止/重启成功路径（F138-26，FR-04 正向：共享实例固定承载至少 2 个目标并核对实际影响）、迟到/历史结果不替换当前实例结果（F138-27，FR-10）、历史窗口裁剪仅返回近期事件（F138-28，FRS §7）、转发失败时已收到内容保留（F138-29，FRS §7）。
10. 负向构造与确认分型（v1130 审阅整改）：无 Host（F138-03）、缺控制模型/缺产物（F138-31）、健康失败（F138-32）、托管目标未注册（F138-33）、操作结果待核实（F138-34）；声明版本过期（F138-10）与实例代次过期（F138-30）分型验证；目标未就绪（F138-18）与旧实例不一致（F138-35）分型验证；在上游已返回部分内容且连接仍保持时取消并以 `preview.failed` 收尾（F138-22）；在部分内容到达后触发上游失败且已收内容保留（F138-29）；同 requestId 幂等复用仅一次转发（上游请求计数，F138-15）。

### 非范围

1. 节点级调试（Trace/断点/单步/任意节点重跑）— 特性档 §2.1 显式 OUT。
2. 生产发布、版本发布与审批 — 特性档 §2.1 显式 OUT。归属修正：FEAT-139 仅负责智能体工程源码导出与归档管理（FRS-137 FR-11/AC-08）；生产发布、版本发布与审批属交付流程责任，不由 FEAT-139 承接，本设计对以上各项均不产生断言。
3. 控制台前端组件（RuntimeDebugState/PreviewChatComponent）内部投影逻辑 — 前端实现及页面一致性验收单独验证（特性档标题注记），白盒归属前端仓。
4. MOA/ReAct/DeepAgent/Workflow 各工程类型的业务级回答正确性矩阵 — 依赖真实业务模型与用户工程产物，按 L2 §7.1 验收标准在具备完整运行环境后扩展；本轮以契约面为主门禁。
5. 平台重启后 running→unknown 核对、Host 控制锁与进程归属核验 — 需要 Host 进程级故障注入手段与持久卷布局，本轮标注为环境依赖场景（F138-11 前置门禁处理），不作为本轮判定门槛。
6. Code Agent（OpenCode）软件与开发模型可用性 — FEAT-136 责任边界，本测试不承接。
7. 工程接入与 ZIP 导入能力本体（FRS-137 FR-01 的差异预览/确认导入实现）— 由 FEAT-136 测试设计承接；本设计仅在 F138-E2E-01 中将其作为进入调试上下文的旅程前置核验（ZIP 分支不可用时按门禁 SKIPPED），差异预览与覆盖算法不在本轮判定。
8. 开发对话修改与构建执行能力本体（FRS-137 FR-08 前半：源码定位、开发对话修改说明、构建检查）— 由 FEAT-136/FEAT-137 测试设计承接；本设计在 F138-E2E-05 中仅核验"产物更新 → 实例更新 → 原输入复验"的归属链路，构建/修改通道不可用时按门禁 SKIPPED 并标注承接文档。
9. 源码导出与归档管理（FRS-137 FR-11/AC-08）— 由 FEAT-139 测试设计承接，本设计不产生断言，追溯矩阵中标注归属。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `docs/develop/02-features/FEAT-138-智能体运行调试与迭代验证.md`（updated 2026-11-19） | §2.2 功能要求（13 项 MUST）、§3 外部接口清单、§5.1 行为语义、§5.1.7 错误事实要求、§6 测试覆盖要求 |
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-dev-workbench/FEAT-138-智能体运行调试与迭代验证.md`（updated 2026-11-19，基线 e3785f804） | §1.3 实现差距（unknown 语义、冲突 400/409 表面、非 SSE 限制）、§2.1 入口清单、§2.2 运行目标查询契约、§2.3 运行操作契约、§2.4 业务预览契约、§2.5 历史与取消契约、§3.2 启停与共享控制、§5.3 业务事件映射、§7 验收标准 |
| `docs/develop/01-scenarios/FRS-137-智能体运行调试与迭代.md`（v1.4，updated 2026-11-18） | §4 功能要求（FR-01～11）、§5 端到端主成功旅程、§6 标准流程用例（WB-02-01～07）、§7 异常处理与恢复、§8 验收标准（AC-01～08）；作为端到端用例层（F138-E2E-01～07）与 §9 追溯矩阵的依据 |

断言中的错误码 `INVALID_OPERATION`、`IDEMPOTENCY_CONFLICT`、`UNKNOWN_RUNTIME`、`CONFIRMATION_STALE`、`CONTROL_OPERATION_IN_PROGRESS`、`CONVERSATION_BINDING_CHANGED`、`RUNTIME_NOT_READY_OR_CHANGED`、`invalid message`、`preview already running`、`REMOTE_RUNTIME_UNAVAILABLE` 均在 L2 §2.2/§2.3/§2.4 场景表逐字出现。

## 4. 部署拓扑

```text
+--------------------------+     HTTP / WS       +------------------------------------------+
|  测试客户端 (JUnit)       | ------------------> |  agent-dev-workbench 后端                 |
|  integration 层           |  /api/sessions/*/   |  App / RuntimeDebugService / Store       |
|  HttpClient（wire 断言）   |  runtime            |  SessionService / RemoteSession          |
+--------------------------+  runtime/operations +---------------------+--------------------+
                            |  preview            | 受限 Host HTTP（认证远端会话）              |
                            |  preview/history    v                     v
                            |  preview/cancel   +------------------+  +--------------------+
                            |  ws://.../ws      | Agent Host        |  | 业务 Runtime 实例   |
                            +------------------>| 预览转发器/控制器  |->| (用户工程 java-jar) |
                                                +------------------+  +--------------------+
```

边界要求：

- **真实依赖（不 mock）**：agent-dev-workbench 后端正式制品、Agent Host 正式制品、用户工程构建产物与运行声明（`deploy/deploy.json` schema 3）、业务模型服务（凭据走环境变量/受限引用）。
- **真实 SUT**：workbench 后端 HTTP wire 为正式制品；不接受任何验收 Fixture 替身；预览回答必须来自选定实例的真实业务入口，不接受画布或模板拼装回答。
- **观察 surface 限定**：仅 L2 §2.1 入口清单所列 HTTP 端点与 `/ws` 下行事件包；不观察 RuntimeDebugStore 控制状态文件、业务事件 JSONL、Host 实例记录目录、进程 PID/地址等内部路由信息。
- **隔离策略**：每个测试类使用独立 `feat138-ws-<run>` 前缀工作区（`run` 为 6 位随机 hex）与独立开发会话；`operationId`/`requestId`/`conversationId` 均带 `feat138-<run>` 前缀，工作区级幂等键互不污染。
- **故障注入手段**：仅进程外手段 — 提交非法 operationId/action、复用同 ID 异内容请求、分别使用过期 `impactVersion`（F138-10）与过期 `expectedGeneration`（F138-30）、发送空白/超长文本、改绑 `conversationId`、使用不存在对象 ID、在上游已返回部分内容且连接仍保持时取消转发（F138-22）、在部分内容到达后触发上游失败（F138-29）；不修改 SUT 对象与内部数据。
- **页面证据要求**：目标选择、日志展开、等待输入、旧实例结果隔离四处页面交互必须绑定浏览器证据（截图/录屏），按 §5.2 与用例 ID 关联归档；无浏览器手段时对应分支按门禁 SKIPPED 并在报告中标注。
- **部署形态与结论边界**：默认以独立部署形态（仓库内 `deploy/standalone/README.md`）覆盖真实会话与业务调用用例；环境阻塞（后端未部署、无 Host、无就绪实例、注入手段缺失）与已观测到的产品失败分别记录，不以环境阻塞替代产品失败判定；验收结论只覆盖实际执行的工程与输入。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F138-01 | 运行目标查询返回快照契约字段 | 开发会话已创建；运行声明可读取 | GET `/api/sessions/{id}/runtime` | 200；body 含 `manifestVersion`(string)、`defaultTargetId`(string)、`targets[]`（每项含 `id`/`runtimeId`/`state`/`requires`）、`runtimes[]`（每项含 `id`/`name`/`state` ∈ {idle,stopped,unhealthy,ready}、`instanceId`/`generation`/`affectedTargets`）、`operations`(object)；`observedAt` 为数值 | workbench 后端 + 工程运行声明 |
| F138-02 | 未知会话查询运行状态 | 无 | GET `/api/sessions/nonexistent-{run}/runtime` | 404；错误表面 `session not found` | 无 |
| F138-03 | 无远端运行通道时不借本机能力判可运行 | 会话已创建；环境未配 Agent Host | GET `/api/sessions/{id}/runtime` | 409 且错误表面含 `REMOTE_RUNTIME_UNAVAILABLE`；不得返回 `runtimes[].state=="ready"` 的推断值 | 无 |
| F138-04 | 公开快照不含内部路由信息 | 快照可读 | 解析 F138-01 响应 body | `runtimes[]` 各项字段名不含 `pid`/`address`/`port`/`cmd`/`env` 等内部路由键 | 同 F138-01 |
| F138-05 | 启动操作异步受理 | 会话就绪；声明与产物有效 | POST `/api/sessions/{id}/runtime/operations`（operationId=`feat138-op-{run}-1`、action=`start`、runtimeId=声明主 Runtime） | 202；body `operationId` 与请求一致；`status=="running"`；`request` 对象回显已受理内容 | workbench 后端 + Host + 产物 |
| F138-06 | 非法操作被拒绝 | 会话就绪 | POST operations：a) operationId 含非法字符 b) operationId 超过 80 字符 c) action=`pause` | 每次均 400；错误码 `INVALID_OPERATION`；不产生有效控制记录 | 无 |
| F138-07 | 同 ID 同内容复用既有记录 | F138-05 已受理 | 完全相同 operationId+action+runtimeId 再次 POST | 202；返回既有记录（`operationId` 相同且 `createdAt` 与首响一致），`status` 不回退为新建 | F138-05 |
| F138-08 | 同 ID 异内容冲突 | F138-05 已受理 | 相同 operationId，action 改为 `stop` | 400；错误码 `IDEMPOTENCY_CONFLICT`；旧记录不被覆盖 | F138-05 |
| F138-09 | 未知 Runtime 拒绝任意进程控制 | 会话就绪 | POST operations（runtimeId=`feat138-no-such-runtime-{run}`） | 400；错误码 `UNKNOWN_RUNTIME` | 无 |
| F138-10 | 声明版本过期拒绝停止/重启 | 存在已就绪实例（generation=g1）；impactVersion 为过期 manifestVersion，expectedGeneration 为当前值 | POST operations（action=`stop`、impactVersion=旧值、expectedGeneration=当前值） | 409；错误码 `CONFIRMATION_STALE`；实例未被停止 | 就绪实例 |
| F138-11 | 同工作区控制串行化 | 工作区存在 running 控制记录 | 并发提交第二条 operations | 409；错误码 `CONTROL_OPERATION_IN_PROGRESS` | running 控制记录（环境依赖，不可稳定构造时按门禁处理） |
| F138-12 | 业务预览受理返回 runId | 目标与实例就绪 | POST `/api/sessions/{id}/preview`（text=`feat138-preview-{run}`、targetId/instanceId=快照值、conversationId=`feat138-conv-{run}`、requestId=`feat138-req-{run}-1`） | 202；body `runId==requestId`；`duplicate` 不为 true | 就绪目标 |
| F138-13 | 空白与超长文本拒绝 | 会话就绪 | POST preview：a) text 为纯空白 b) text 长度 4001 | 每次均 400；错误表面 `invalid message` | 无 |
| F138-14 | 非法会话/请求 ID 拒绝 | 会话就绪 | POST preview：conversationId 含非法字符 / requestId 超过 80 字符 | 400；相应 INVALID 代码 | 无 |
| F138-15 | 业务请求同 ID 同内容幂等复用 | F138-12 已受理 | 相同 requestId+相同请求体再次 POST preview；以业务侧上游请求计数核对转发次数 | 202；`duplicate==true`；`runId` 与首响一致；仅一次转发 — 该 runId 仅一条 `preview.started`（等价上游请求计数为 1），不产生第二次转发 | F138-12 |
| F138-16 | 业务请求同 ID 异内容冲突 | F138-12 已受理 | 相同 requestId，text 改为不同内容 | 400；错误码 `IDEMPOTENCY_CONFLICT` | F138-12 |
| F138-17 | 业务会话不可改绑目标/实例 | F138-12 已绑定 conversationId→targetId+instanceId | 同 conversationId 携带不同 instanceId POST preview | 400；错误码 `CONVERSATION_BINDING_CHANGED` | F138-12 |
| F138-18 | 目标未就绪拒绝转发 | 目标 state 非 ready（启动中/已停止/unhealthy） | POST preview（targetId=非就绪目标） | 409；错误表面含 `RUNTIME_NOT_READY_OR_CHANGED` 或远端错误正文 | 非 就绪实例 |
| F138-19 | 单会话转发槽占用互斥 | 存在未结束的预览转发 | 转发进行中再次 POST preview | 409；错误表面 `preview already running` | 进行中的转发（时序场景） |
| F138-20 | 业务事件分区与关联字段 | F138-12 已受理；订阅 `/ws` 或轮询历史 | 收集本轮 `runId` 关联事件 | 出现 `preview.started`；`preview.answer` 的 `mode` ∈ {append, replace}；原始帧走 `preview.raw`，不混入 answer 正文；终态为 `preview.ended` 或 `preview.failed`；事件外壳含 `sessionId`/`seq`/`ts`/`type` 且业务关联字段 `runId`/`targetId`/`instanceId`/`conversationId` 等于请求值 | 就绪目标 |
| F138-21 | 业务历史与开发历史分离 | F138-12/20 产生过业务回合 | GET `/api/sessions/{id}/preview/history`；GET `/api/sessions/{id}/events` | 历史 200 且 `items` 为数组、仅含业务预览事件（type 以 `preview.` 为前缀）；开发事件流可读（L2 §2.5 单向承诺：不约束 /events 是否含业务事件） | F138-12 |
| F138-22 | 取消仅结束转发通道 | 转发进行中且上游已返回部分内容、连接仍保持 | POST `/api/sessions/{id}/preview/cancel` | 200；body 为 `ok` 对象；随后本轮以 `preview.failed` 收尾；不产生任何"业务任务已取消"的确认字段 | 进行中的转发 |
| F138-23 | 断线后核对不自动重发 | 存在已登记请求记录；浏览器页面保持打开 | ①断开 /ws 后重连，从浏览器观察重连、历史去重及未自动重发 ②GET runtime + GET preview/history | runtime 200 可读、history 200 可读；请求记录仍为原 `requestId`，无以新 requestId 自动补发的业务请求；浏览器证据：历史与实时结果不重复、无自动重发 | 已登记请求 + 浏览器 |
| F138-24 | 实例变化后旧业务会话不可续接 | 已就绪实例（generation=g1）有绑定会话 | 重启目标产生新实例（g2）后，以旧 conversationId+新实例 POST preview | 拒绝续接旧上下文：400 `CONVERSATION_BINDING_CHANGED` 或要求新业务会话；快照 `generation` 递增且 `instanceId` 变化 | 就绪实例 + 重启能力 |
| F138-25 | 操作结果查询与受理/就绪分离（FR-03） | F138-05 已受理 | 通过运行快照 `operations` 及操作结果查询入口读取该 `operationId` 的记录与执行状态 | 操作记录可查询且 `operationId` 可追溯；目标/实例就绪状态独立于受理状态呈现，不得因已受理即显示 `ready`；受理状态不得替代启动失败/健康未通过的实际结果 | F138-05 |
| F138-26 | 有效影响确认停止/重启成功（FR-04 正向） | 共享实例固定承载至少 2 个目标（fixture 固定）；就绪实例（generation=g1）；持有当前 manifestVersion 与 expectedGeneration | 读取快照 `affectedTargets`（覆盖全部共享目标，≥2）后 POST operations（action=`stop`，impactVersion/expectedGeneration=当前值）；再以同法验证 `restart` | 202 受理；影响范围与确认一致；实例最终离开 `ready`（stop）或产生新代次（restart）；实际影响与确认范围一致 — 全部受影响目标均离开就绪或产生新代次，未受影响目标不受波及；操作结果可查询 | 共享实例（≥2 目标，fixture 固定） |
| F138-27 | 迟到/历史结果不替换当前实例结果（FR-10） | 旧实例 g1 存在已归档业务回合；实例已更新为 g2 | 读取 preview/history 并与新实例新回合结果比对 | 历史回合的业务关联字段 `instanceId` 保持 g1 原值，不并入/替换 g2 回合；新实例的答复归属 g2 | 实例更新 |
| F138-28 | 历史窗口裁剪仅返回近期事件 | 已产生超出保留窗口的历史回合 | GET `/api/sessions/{id}/preview/history` | 200；仅返回仍保存的近期事件；被裁剪回合不出现且不报错 | 长回合历史 |
| F138-29 | 转发失败时已收到内容保留 | 转发进行中且部分内容已到达后触发上游失败（进程外注入：上游失败/不可用目标） | 收集该 runId 事件并读 preview/history | `preview.failed` 终态含失败信息；失败前已收到的 `preview.answer`/`preview.raw` 事件在事件流与历史中保留，不被失败清除 | 上游失败注入手段 |
| F138-30 | 实例代次过期拒绝停止/重启 | 存在已就绪实例（generation=g1≥1）；impactVersion 为当前 manifestVersion，expectedGeneration 为过期值（g1-1） | POST operations（action=`stop`、impactVersion=当前值、expectedGeneration=过期值） | 409；错误码 `CONFIRMATION_STALE`；实例未被停止 | 就绪实例 |
| F138-31 | 缺产物/缺控制模型不判就绪 | 工程产物缺失或运行声明缺控制模型（进程外构造无效声明） | GET runtime；对受影响 Runtime 提交 start 并轮询操作结果 | 目标/实例不得显示 `ready`；缺失/失败问题在操作结果或快照可见；受理状态不替代就绪 | 无效声明/缺产物 fixture |
| F138-32 | 健康检查失败不判就绪 | 实例已启动但健康检查未通过（进程外注入：healthPath 指向不存在路径） | 提交 start 并轮询操作结果至终态 | 操作以失败终态（START_HEALTH_TIMEOUT）收尾；实例不判 ready；失败原因可查询（共享进程引擎下启动窗口内的 unhealthy 中间态经控制会话串行化，不作 wire 呈现；unhealthy 枚举一致性由 F138-01 覆盖） | 健康失败注入 |
| F138-33 | 托管目标未注册拒绝控制 | 运行声明中的目标未在 Agent Host 注册（进程外构造） | GET runtime；对该目标提交 operations/preview | 操作或预览被拒绝且错误表面可观察（`UNKNOWN_RUNTIME`/`REMOTE_RUNTIME_UNAVAILABLE` 或未注册错误正文）；不得借本机能力判就绪 | 未注册目标 fixture |
| F138-34 | 操作结果待核实可查询且不替代就绪 | start 进行中控制会话被进程外终止（pkill 'opencode run'；业务实例以 start_new_session 分离存活） | 通过快照 `operations` 轮询该 `operationId` | 操作呈现待核实（unknown）状态；unknown 期间目标不判 ready；reconcile 收敛后终态与实际一致 | 控制会话进程外终止注入 |
| F138-35 | 旧实例不一致拒绝转发 | 目标就绪但请求携带与快照不符的旧 instanceId（g1，当前 g2） | POST preview（instanceId=旧实例值或占位旧值） | 409；错误表面含 `RUNTIME_NOT_READY_OR_CHANGED` 或远端错误正文 | 新旧实例可构造 |
| F138-E2E-01 | 准备调试工程（对应 WB-02-01） | 开发者具有工作区访问权限；工作区已含工程（可选 ZIP） | ①打开/恢复工作区会话，读取当前工程文件 ②如需导入：上传 ZIP 预览新增与覆盖差异→确认导入 ③核对实际写入的文件、入口和配置 ④打开画布或源码确认当前调试工程 | 调试上下文对应当前工程；导入成功后文件和结构可重新核对；已有文件变化使旧预览失效时要求重新预览，旧确认不能覆盖未核对的变化 | 工作区 + 工程（可选 ZIP） |
| F138-E2E-02 | 启动目标并完成第一次调用（对应 WB-02-02） | 工程具备可用产物和运行入口，尚无就绪实例 | ①核对目标和当前运行状态（按 F138-01 契约）②发起启动并确认 202 受理（按 F138-05 契约）③轮询操作结果（F138-25）与 runtime 直至目标就绪 ④向该目标发送测试输入（F138-12 契约）⑤核对实际回复、目标和实例信息（F138-20 契约） | 启动请求受理与实例就绪分别可观察；就绪后业务调用返回实际结果且归属正确目标/实例；产物缺失、启动失败或健康检查未通过时展示对应问题，不得以已受理状态替代就绪 | workbench 后端 + Host + 产物 |
| F138-E2E-03 | 检查正常答复和工作日志（对应 WB-02-03） | 目标已就绪；已知该输入应得到的业务结果 | ①提交输入并收集本回合事件 ②按当前回合查看 answer 与 raw 工作日志事件 ③查看运行端提供的思考、工具状态、摘要或原始事件 ④比较实际业务答复和预期 | 答复归属本次请求；日志事件来自实际执行，未提供的日志类别不补造；未知事件可按 `preview.raw` 原始信息查阅；业务通过与否以实际答复判定 | 就绪目标 |
| F138-E2E-04 | Workflow 追问后同会话继续（对应 WB-02-04） | 当前工程含追问分支（如大于阈值需确认）；目标已就绪 | ①提交进入追问分支的业务输入（如大额订单）②核对 `awaitingInput` 问题与等待补充信息状态（页面证据：等待输入可见）③保持目标、实例和业务会话，在原 conversationId 中发送「确认」并得到第一层结果 ④在同一业务会话中再次提交可触发追问的输入，验证同会话连续追问仍进入等待并按工程定义收尾 ⑤查看最终业务答复 | 首轮呈现工程实际发出的问题与等待状态；回答进入原业务会话并最终得到工程定义的结果；同会话连续追问不丢失上下文；全程目标/实例不变 | 含追问分支的工程 |
| F138-E2E-05 | 修改缺陷并使用原输入复验（对应 WB-02-05） | 已有一轮实际结果与预期不符的记录；构建/修改通道可用 | ①查看该回合日志，从画布或工程目录定位相关源码 ②在开发对话中说明问题和修改要求并触发构建 ③核对更新后的产物已生效（如新阈值/新文案已进入产物）④根据变更重启适用实例 ⑤确认当前目标已就绪（页面证据：目标选择与状态可见）⑥发送原输入复验，核对原失败输入得到修改后的预期结果 ⑦补测修改影响的另一条分支的回归结果 | 新结果归属更新后的实例（`instanceId` 变化且 `generation` 递增）；原失败记录仍有原归属（F138-27 契约，页面证据：旧实例结果隔离）；复验同时核对更新后的产物、新实例、原失败输入的预期结果与另一条分支的回归结果；仅修改文件而仍运行旧产物（未产生新代次）时，不能判定修复已生效。示例：业务阈值由 1000 元改为 2000 元时，以 1500 元输入验证改为直接返回，以 2500 元输入验证追问分支仍有效 | 可构建工程 + Host |
| F138-E2E-06 | 确认共享实例的停止或重启（对应 WB-02-06） | 共享实例固定承载至少 2 个目标（fixture 固定）；实例就绪 | ①查看实例和相关目标（`affectedTargets` 覆盖 ≥2 个共享目标），发起操作确认 ②按当前 impactVersion/expectedGeneration 提交并检查操作结果（F138-26 契约）③另测确认期间实例或运行声明变化的情况（F138-10/F138-30 契约：`CONFIRMATION_STALE` 分型）④刷新状态后重新核对并提交成功 ⑤核对实际影响：全部受影响目标均离开就绪或产生新代次 | 操作按已确认的当前范围处理且操作结果可查；实际影响与确认范围一致（≥2 个目标均受影响，其他目标不受波及）；过期确认返回冲突并要求重新核对；重新核对后提交成功，用户能看见操作结果或待核实状态 | 共享实例（≥2 目标，fixture 固定） |
| F138-E2E-07 | 连接中断与历史恢复（对应 WB-02-07） | 已收到一次业务调用的部分内容，随后连接中断（进程外手段：断开 /ws 连接）；浏览器页面保持打开 | ①从浏览器观察重连、历史去重及未自动重发（页面证据）②重连后读取可用的近期历史和当前实例状态（F138-21/23 契约）③检查历史与实时结果是否重复或错误归属 ④根据已核对状态显式决定是否发起新请求 | 已收到内容和实际错误可见（F138-29 契约）；恢复过程不自动重发业务输入；近期历史按保留范围恢复且与实时结果不重复、归属正确（浏览器证据：重连后历史去重、无自动重发）；已换实例时旧回合不能作为新实例的答复（F138-24/27 契约） | 进行中转发 + 中断手段 + 浏览器 |

### 5.1 显式 deferred / 环境依赖项

- 契约级环境依赖：F138-10/11/18/19/22/24/26/27/28/29/30/31/32/33/34/35 依赖真实就绪实例、共享实例 fixture（≥2 目标）、进行中转发、失败注入与控制并发时序；SUT 未部署或无 Host 时按 `assumeTrue` 门禁 SKIPPED 并在报告中标注，解除环境后为回归基线必跑项。
- 端到端环境依赖：F138-E2E-01 的 ZIP 导入分支（FEAT-136 承接能力）与 F138-E2E-05 的构建/修改通道（FEAT-136/137 承接能力）不可用时，对应分支按门禁 SKIPPED 并标注承接文档，其余步骤照常判定；F138-E2E-02/04/06/07 依赖就绪实例与真实业务 Runtime，门禁策略同契约级环境依赖项；§5.2 页面证据项无浏览器手段时对应分支按门禁 SKIPPED 并标注。
- MOA/ReAct/DeepAgent/Workflow 各类型真实业务回答矩阵（L2 §7.1）依赖用户工程产物与业务模型，本轮不判定；F138-E2E-04 的追问分支使用工程内已定义的真实追问路径。

场景分层覆盖说明：契约正例（F138-01/05/07/12/15/20/21/25/26）、异常（F138-02/03/06/08/09/13/14/16/17/18/29/31/32/33/35）、边界（F138-04/14、4000 字符界、80 字符界）、时序/并发（F138-11/19/22/23/34）、权限与状态机非法迁移（F138-02/10/17/24/27/30）、历史保留（F138-21/28/29）；端到端层（F138-E2E-01～07）与标准流程用例 WB-02-01～07 一一对应，按其步骤串联执行，覆盖 FRS §5 主成功旅程全部步骤与 §7 异常处理各行（追溯见 §9）。

### 5.2 页面证据要求（浏览器证据）

| 页面交互 | 绑定用例 | 证据要点 |
|---|---|---|
| 目标选择 | F138-E2E-02 ① | 浏览器可见目标/实例列表与状态，所选目标与实际调用归属一致 |
| 日志展开 | F138-E2E-03 ②③ | 工作日志/原始帧可展开查看，来自实际执行，未提供的类别不补造 |
| 等待输入 | F138-E2E-04 ② | `awaitingInput` 问题与等待状态在页面可见 |
| 旧实例结果隔离 | F138-E2E-05 ⑥⑦、F138-27 | 页面上旧实例回合与新实例回合不混排，旧失败记录保持原归属 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| agent-dev-workbench 后端（App/RuntimeDebugService/RuntimeDebugStore） | 真实 SUT | HTTP wire 观察；正式制品，无替身 |
| Agent Host（预览转发器/受限运行控制） | 真实 SUT | 平台内认证远端会话访问；不直接构造 Host 内部地址 |
| 业务 Runtime 实例（用户工程 java-jar） | 真实依赖 | 由运行声明与产物启动；产物/声明/模型凭据为运行前提 |
| 开发会话（`/api/sessions`） | Fixture | 每测试类独立创建，作为 runtime/preview 路由入口；创建失败整类门禁 |
| 运行声明（deploy.json schema 3） | Fixture | 来自用户工程，不由浏览器改写；错误声明用于负向场景 |
| 业务模型服务 | 真实依赖 | 凭据走环境变量/受限引用，不落库 |
| OpenCode / Code Agent | 真实依赖（FEAT-136 边界） | 仅控制路径可能间接依赖；本测试不验证其自身行为 |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

1. **F138-01**：运行快照字段契约完整（`manifestVersion`/`targets[]`/`runtimes[]`/`operations`），目标状态枚举不出 {idle, stopped, unhealthy, ready} 之外。
2. **F138-05**：控制操作异步受理 — 202 + `status=="running"`，受理不等于执行成功。
3. **F138-08/F138-16**：两级幂等冲突 — 控制 `IDEMPOTENCY_CONFLICT` 400、业务 `IDEMPOTENCY_CONFLICT` 400，旧记录不被静默覆盖。
4. **F138-13**：预览输入校验 — 空白与超长文本 400 `invalid message`，不产生转发。
5. **F138-17/F138-24/F138-35**：业务会话绑定不可改绑、实例变化不可续接旧上下文、旧实例不一致拒绝转发。
6. **F138-21**：业务历史与开发历史分区返回。
7. **F138-E2E-02**：受理与就绪分离 — 启动受理（202 + `status=="running"`）不得替代目标就绪，就绪后业务调用返回实际结果并归属正确目标/实例（WB-02-02）。
8. **F138-E2E-04**：追问闭环 — 追问问题与等待状态可见，同一业务会话回答后得到工程定义的最终结果，全程目标/实例不变（WB-02-04）。
9. **F138-E2E-05**：复验归属链路 — 实例更新（`generation` 递增）后原输入复验结果归属新 `instanceId`，旧失败记录保持原归属；未产生新代次不得判定修复生效（WB-02-05）。
10. **F138-E2E-06**：影响确认闭环 — 有效确认执行成功且操作结果可查，过期确认返回 `CONFIRMATION_STALE`，重新核对后提交成功（WB-02-06）。
11. **F138-10/F138-30/F138-26**：过期确认分型拒绝（声明版本过期与实例代次过期均 `CONFIRMATION_STALE`，实例未被停止）+ 共享实例（≥2 目标）有效确认的实际影响核对（FR-04/WB-02-06）。

失败语义与基线同构：负向场景必须同时命中"目标错误码 + 不产生副作用"（冲突/校验失败不得覆盖既有记录、不得创建转发）。声明缺失/产物缺失不得显示为 `ready`（F138-03/F138-04/F138-31/F138-33 辅助佐证）；过期确认分型（F138-10/F138-30）与待核实操作结果（F138-34）不得以受理状态替代实际结果。

## 8. 执行策略

- **Smoke 集合**（必跑子集）：F138-01、F138-02、F138-05、F138-06、F138-12、F138-13、F138-21、F138-E2E-02。
- **Full suite**：F138-01～F138-35 与 F138-E2E-01～F138-E2E-07。
- **P0 必须全绿项**：F138-01、F138-05、F138-08、F138-13、F138-16、F138-17、F138-21、F138-25、F138-26、F138-30、F138-35、F138-E2E-02、F138-E2E-04、F138-E2E-05、F138-E2E-06。
- **依赖门禁**：BeforeAll 探测 `GET /api/workspaces`（后端就绪）→ 创建工作区/开发会话；F138-10/11/18/19/22/24/26/27/28/29/30/31/32/33/34/35 增加就绪实例/共享实例（≥2 目标）/进行中转发/失败注入前置门禁；F138-E2E-01/05 增加 ZIP 导入/构建修改通道门禁（不可用时 SKIPPED 并标注 FEAT-136/137 承接；源码导出与归档属 FEAT-139），未满足按 SKIPPED 记录。
- **结论边界**：验收结论只覆盖实际执行的工程与输入；环境阻塞（后端未部署、无 Host、无就绪实例、注入手段缺失）与已观测到的产品失败分别记录，环境阻塞不作为产品失败的替代判定。
- **推荐执行 agent**：`agent_dev_workbench` 模块，与 FEAT-136/137 同目录复用 wire 客户端模式。

执行命令示例：

```bash
export SUT_SOURCE_DIR=D:\code\test\agent-solution
./scripts/sut/provision-sut.sh --env openjiuwen
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-138 -Dmaven.test.failure.ignore=true
```

## 9. 追溯矩阵（FRS-137 → 用例）

### 9.1 功能要求（FRS §4）

| 需求 | 覆盖用例 | 说明 |
|---|---|---|
| FR-01 取得调试工程 | F138-E2E-01 | ZIP 导入能力本体由 FEAT-136 承接（非范围第 7 项），E2E-01 核验旅程前置 |
| FR-02 发现和选择目标 | F138-01、F138-E2E-02 | 目标/实例快照 + 业务输入归属目标 |
| FR-03 运行操作反馈 | F138-05、F138-25、F138-34、F138-E2E-02 | 受理可查询、就绪与受理分离、待核实结果可观察 |
| FR-04 影响确认 | F138-10、F138-30、F138-26、F138-E2E-06 | 声明版本/实例代次过期分型拒绝 + 有效确认成功并核对实际影响（共享实例 ≥2 目标） |
| FR-05 实际业务调用 | F138-12、F138-20、F138-E2E-02/03 | 实际答复与归属；为交付工程类型绑定代表性真实调用 |
| FR-06 调试信息查看 | F138-20、F138-21、F138-E2E-03 | 工作日志/原始帧；页面展示属前端（非范围第 3 项） |
| FR-07 追问和回答 | F138-E2E-04 | 追问后同会话继续 |
| FR-08 修改与复验 | F138-E2E-05 | 修改/构建本体由 FEAT-136/137 承接（非范围第 8 项），E2E-05 核验更新实例与复验归属 |
| FR-09 断线与历史 | F138-21、F138-23、F138-E2E-07 | 不自动重发、历史恢复 |
| FR-10 请求与实例归属 | F138-15/16、F138-27、F138-E2E-05/07 | 去重 + 迟到/历史结果归属隔离 |
| FR-11 源码交接 | —（非范围第 9 项） | FEAT-139 测试设计承接 |

### 9.2 验收标准（FRS §8）

| AC | 覆盖用例 |
|---|---|
| AC-01 | F138-E2E-01（导入实现属 FEAT-136） |
| AC-02 | F138-05、F138-25、F138-E2E-02 |
| AC-03 | F138-20、F138-21、F138-E2E-03 |
| AC-04 | F138-E2E-04 |
| AC-05 | F138-E2E-05 |
| AC-06 | F138-10、F138-24、F138-26、F138-27、F138-30、F138-E2E-06 |
| AC-07 | F138-21、F138-23、F138-E2E-07 |
| AC-08 | —（FEAT-139 承接，非范围第 9 项） |

### 9.3 标准流程用例（FRS §6）

| 标准流程用例 | 端到端用例（一一对应） | 串联的契约断言 |
|---|---|---|
| WB-02-01 准备已有或生成的调试工程 | F138-E2E-01 | 导入实现属 FEAT-136 |
| WB-02-02 启动目标并完成第一次调用 | F138-E2E-02 | F138-01/05/12/20/25 |
| WB-02-03 检查正常答复和工作日志 | F138-E2E-03 | F138-20/21 |
| WB-02-04 Workflow 追问后同会话继续 | F138-E2E-04 | F138-20 |
| WB-02-05 修改缺陷并使用原输入复验 | F138-E2E-05 | F138-24/27 |
| WB-02-06 确认共享实例的停止或重启 | F138-E2E-06 | F138-01/10/26/30 |
| WB-02-07 连接中断与历史恢复 | F138-E2E-07 | F138-21/23/24/29 |

### 9.4 异常处理与恢复（FRS §7）

| §7 异常行 | 覆盖用例 |
|---|---|
| ZIP 确认已失效 | F138-E2E-01 |
| 构建产物缺失/配置不合法/健康失败/缺控制模型 | F138-31、F138-32、F138-E2E-02（含"受理不替代就绪"断言） |
| 实例代次或影响范围已变化 | F138-10、F138-30、F138-26、F138-E2E-06 |
| 模型、工具或目标返回错误 | F138-20（`preview.failed` 终态）、F138-29 |
| 回答前切换实例或业务会话 | F138-17、F138-24、F138-35 |
| 连接恢复请求效果尚不能确认 | F138-23、F138-E2E-07 |
| 停止回复转发已中止 | F138-22 |
| 历史读取窗口裁剪 | F138-28 |
| 托管目标未注册 | F138-33 |
| 操作结果待核实 | F138-34 |
| 源码交接异常 | —（FEAT-139 承接，非范围第 9 项） |

### 9.5 端到端主成功旅程（FRS §5）

| 旅程步骤 | 覆盖用例 |
|---|---|
| ① 检查目标、无就绪实例时启动并观察运行结果 | F138-E2E-02 |
| ② 正常输入直接返回 | F138-E2E-02/03 |
| ③ 大额输入触发追问等待 | F138-E2E-04 |
| ④ 同会话回复「确认」得到最终结果 | F138-E2E-04 |
| ⑤ 查看日志、定位并向开发助手说明 | F138-E2E-03/05（定位与修改说明属 FEAT-136/137） |
| ⑥ 修改构建、更新实例并以原输入复验 | F138-E2E-05 |
| ⑦ 导出工程 | —（FEAT-139 承接） |
