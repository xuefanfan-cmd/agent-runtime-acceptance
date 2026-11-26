---
feature_id: FEAT-136
feature_title: 对话式开发高码智能体
scope: agent-dev-workbench; 特性文档与 L2 updated 2026-11-19，实现核对基线 e3785f804cf8c8ca81337253159d7824c3b7efa5
deployable_units: [agent-dev-workbench 后端（App/SessionService/WsHub）, jiuwen-agent CLI（dev-scaffold）, OpenCode 开发智能体（用户提供）]
sut: agent-dev-workbench 控制面后端（工作区/开发会话/事件/审批/ZIP 导入 HTTP API）与 jiuwen-agent 开发工具 CLI
status: draft
features: [FEAT-136]
related_suites: [FEAT-137-agent-structure-visualization-source-linkage.md, FEAT-138-agent-runtime-debug-iteration-verification.md]
updated: 2026-11-23
---

# FEAT-136 对话式开发高码智能体 — 测试设计

## 1. 测试目标

验证 agent-dev-workbench 对话式高码开发特性的外部可观察行为：开发者通过工作区与开发会话提交自然语言需求，工作台通过 ACP 接入 OpenCode（engineId=opencode）完成工程生成、事件投影、工具审批、ZIP 导入与验证反馈。观察 surface 限定为 L2 §2.1 外部入口清单所列 HTTP API（`/api/workspaces`、`/api/sessions/*`、`/api/sessions/{id}/events`、`/api/sessions/{id}/approvals/*`、`/api/sessions/{id}/cancel`、`/api/sessions/{id}/upload/*`）、WebSocket `/ws`，以及 `jiuwen-agent` CLI 的 JSON 输出与退出码。

端到端用例中的产物文件与结构投影断言（F136-30、F136-32～F136-34、F136-40）经 FEAT-137 只读接口 `GET /api/sessions/{id}/files`、`GET /api/sessions/{id}/file`（`view=workbench`）与 `GET /api/sessions/{id}/project/model` 作为进程外观察探针完成；这些接口在本套件中仅作观察面使用，其自身契约由 FEAT-137 套件验证（F137-11～F137-21 等）。探针不可用时以 `upload/preview` 变更集差集辅助核对已知文件（差集仅覆盖预览请求中显式列出的文件，不作为生成产物完整性的凭证），并在执行报告中标注；兜底仍不完整时相关断言 SKIPPED 并标注原因。

本测试不编译或调用 SUT 内部类，不读取 SUT 内部状态与私有存储；所有断言写在 wire 契约（HTTP 状态码、JSON 字段与取值、CLI 退出码）上。期望值逐字取自特性文档 §2.3/§3/§5 与 L2 §2 各契约章节。

## 2. 范围与非范围

### 范围

1. 工作区契约：创建（201 + `id`/`ownerId`/`name`/`path`/`createdAt`/`lastUsedAt`）、缺少名称与目录条件 400、首次按路径登记成功 201、同一目录重复登记 409、未知工作区 404 `WORKSPACE_NOT_FOUND`、改名保留原工程路径（L2 §2.2）。
2. 开发会话契约：创建 201 且 `engineId=="opencode"`、含 `llm` 快照；未知 workspaceId 404；会话创建失败不删除工作区（L2 §2.2）。
3. 恢复契约：`POST /api/sessions/{id}/reattach` 返回三值 `restored ∈ {true, false, null}`（L2 §2.3），分场景构造恢复成功（核对旧历史仍在）、远端回退新建（本地恢复失败，核对会话可用且已投影历史不丢）与结果未知（核对会话状态可经事件读取确认）；不存在会话按不可见处理。
4. 回合与事件契约：提交回合 202 + HTTP `turnId` 受理标识；事件读取返回 `items`/`hasMore`/`earliestSeq`/`initialIdle`；`since` 游标过滤；历史分页与实时事件交叠时不丢、不重；事件 `turnId` 独立于 HTTP `turnId`；回合存在 `turn.started`/`turn.ended`（L2 §2.4/§2.5/§5.2）。
5. WebSocket 契约：`ready` 后回放历史，`seq` 连续递增、无重复（L2 §2.5/§3.5）。
6. 审批与取消契约：无效选项 400 `INVALID_APPROVAL_OPTION`；非待决审批 409 `APPROVAL_NOT_PENDING`（含以可访问的会话 B 提交会话 A 的审批 ID 的跨会话挪用）；无权访问的会话提交审批 404 `SESSION_NOT_FOUND`（会话不可见与审批冲突分开核对）；允许正例 200 后实际工具结果由工具事件证明（不以 200 判定执行成功）；拒绝决定保留拒绝状态且原工具请求不得显示为执行成功；`approvalId` 限当前会话当前待决请求，不跨会话复用；cancel 200 仅确认受理（L2 §2.6）。
7. ZIP 导入契约：预览返回 `token`（64 位小写十六进制）+ `changes[]`（`path`/`before`/`after`）；同名覆盖预览呈现覆盖差异且确认（应用）前工程文件保持原样；缺令牌 400 `PREVIEW_REQUIRED`；损坏 ZIP 400 `INVALID_ARCHIVE`；二进制 400 `UNSUPPORTED_TEXT`；路径穿越 400 `INVALID_PATH`；目标变化后旧令牌 409 `PREVIEW_CHANGED`；应用返回实际变更数 `files`（L2 §2.7/§3.6）。
8. 开发工具契约：`select-type` 以命中明确规则的样例覆盖 `moa`/`react`/`deep`/`workflow` 四类选型，输出 `type`/`confidence`/`decidedBy`/`reason`/`signals` 且 `type ∈ {moa, react, deep, workflow}`；低置信度兜底独立单测（`decidedBy`/`reason` 可复核为规则兜底而非模型输出）；`model-to-code` 模型无效退出码 2；`validate` 输出 `checks`/`summary`/`status`/`ok`（L2 §2.8、特性档 §2.4）。
9. 失败语义：`validate` 检查项状态 `pass`/`fail`/`review`/`skip` 与总体状态 `pass`/`blocked`/`unknown` 均须按明细解释（L2 §5.5、CheckResult 契约）；回合执行失败按错误事件与终态原因判断，失败回合也可发出 `turn.ended`（不以缺少 `turn.ended` 判定失败）。
10. 端到端场景链路：FRS-136 §6 五个标准流程用例 WB-01-01～05 各以一条端到端用例整链覆盖（F136-30～F136-34 一一对应，单条标准用例的前置、全部步骤与预期结果在同一用例内闭环，不跨用例拆分步骤），另以 F136-40 补 Workflow 类型生成正例；F136-01～F136-29 与 F136-35～F136-42 为契约级用例，不作为标准流程步骤的替代覆盖（追溯见 §9）。
9. 失败语义：`validate` 检查项状态 `pass`/`fail`/`review`/`skip` 与总体状态 `pass`/`blocked`/`unknown` 均须按明细解释（L2 §5.5、CheckResult 契约）。
10. 端到端场景链路：FRS-136 §6 五个标准流程用例 WB-01-01～05 各以一条端到端用例整链覆盖（F136-30～F136-34 一一对应，单条标准用例的前置、全部步骤与预期结果在同一用例内闭环，不跨用例拆分步骤）；F136-01～F136-29 与 F136-35～F136-37 为契约级用例，不作为标准流程步骤的替代覆盖（追溯见 §9）。

### 非范围

1. 画布结构浏览、来源定位与图识别 — 由 FEAT-137 承接（特性档 §5.2）。
2. 长驻运行目标控制、实例启停与交互式业务调试（`preview.*` 事件）— 由 FEAT-138 承接（L2 §2.5）。
3. 源码归档包格式与管理 — 由 FEAT-139 承接（L2 §1.1）。
4. 套件构建、获取与本机安装 — 由 FEAT-140 承接（L2 §1.1）。
5. Code Agent（OpenCode）软件与开发模型服务本身的可用性 — 特性档明确由用户提供并维护，本测试不承接其维护责任，只验证工作台的接入与契约。
6. 任意 Code Agent 引擎替换、任意框架无损类型转换 — 特性档 §2.3 显式 OUT。
7. 控制台前端组件（ConversationStore/ChatPanel 等）内部投影逻辑与页面一致性验收 — 单独验证（特性档标题注记），白盒归属前端仓。其中以下页面 MUST 项须由页面验收方案绑定浏览器证据，本套件只覆盖其 wire 前置事实：「快速开始」上传同名 ZIP 时用户看到覆盖差异并确认（确认前文件保持原样，F136-39 为其 wire 前置）、画布来源定位与对象上下文交接、会话切换后的迟到结果隔离、工程刷新失败提示（审阅 #599/#602 E/G/H2 组）。
8. 远端 Agent Host 形态（pod/docker 隔离）的专属限额差异（1 MiB/100 文件）— 本轮仅验证本地形态公开契约，远端形态需独立部署拓扑另测（L2 §1.3 差距清单）。
9. Workflow 六类节点的业务级生成产物反向分析 — 依赖 `jiuwen-agent` CLI 与生成模型实际可用，本轮以 CLI 契约场景覆盖入口行为，业务产物矩阵在 CLI 交付后扩展。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `docs/develop/02-features/FEAT-136-对话式开发高码智能体.md`（updated 2026-11-19） | §2.3 功能要求（14 项 MUST）、§3 外部接口清单、§5.1 行为语义、§5.1.6 错误事实要求、§6 测试覆盖要求 |
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-dev-workbench/FEAT-136-对话式开发高码智能体.md`（updated 2026-11-19，基线 e3785f804） | §2.1 入口清单、§2.2 工作区/会话契约、§2.3 恢复三值、§2.4 回合契约、§2.5 事件契约（含 `approval.requested`/`approval.resolved`、`tool.started/completed/failed`）、§2.6 审批/取消契约、§2.7 ZIP 导入契约、§2.8 CLI 契约、§5.5 状态解释规则、§7 验收标准 |
| `docs/develop/01-scenarios/FRS-136-从零构建智能体并在画布呈现.md`（v1.4，updated 2026-11-19） | §4 功能要求（FR-01～FR-10）、§5 端到端主成功旅程、§6 标准流程用例（WB-01-01～05）、§7 异常与恢复、§8 验收标准（AC-01～06）的场景级追溯来源（映射见 §9） |

断言中的错误码 `WORKSPACE_NOT_FOUND`、`INVALID_APPROVAL_OPTION`、`APPROVAL_NOT_PENDING`、`PREVIEW_REQUIRED`、`INVALID_ARCHIVE`、`UNSUPPORTED_TEXT`、`INVALID_PATH`、`PREVIEW_CHANGED` 均在 L2 §2.2/§2.6/§2.7 逐字出现。

## 4. 部署拓扑

```text
+--------------------------+     HTTP / WS      +---------------------------------+
|  测试客户端 (JUnit)       | -----------------> |  agent-dev-workbench 后端        |
|  integration 层           |  /api/workspaces   |  App / SessionService / Store   |
|  HttpClient + WS 客户端   |  /api/sessions/*   |  ProjectArchive / Providers     |
+--------------------------+  /api/.../events   +----------------+----------------+
                            /api/.../upload/*                     | ACP stdio / Host HTTP
                            /api/.../approvals/*                  v
                            /api/.../cancel    +---------------------------------+
                            ws://.../ws        |  OpenCode 子进程 (用户提供)      |
+--------------------------+                    |  开发模型服务 (用户提供)         |
|  jiuwen-agent CLI        | <– 测试直接调用 –  |  dev-scaffold 模板/规则/Skill    |
|  (dev-scaffold 交付物)    |                    +---------------------------------+
+--------------------------+
```

边界要求：

- **真实依赖（不 mock）**：agent-dev-workbench 后端正式制品、OpenCode CLI（用户安装，本机 1.16.0）、开发模型服务（用户配置，凭据走环境变量）。
- **真实 SUT**：workbench 后端（HTTP wire）与 `jiuwen-agent` CLI（进程退出码 + stdout JSON）为正式制品；不接受任何验收 Fixture 替身。
- **观察 surface 限定**：仅 L2 §2.1 入口清单所列 HTTP/WS 端点与 CLI stdout/exit code；不观察 Store 内部 JSON 记录、`.jiuwen/` 管理数据、AcpSession 进程内部状态、事件 JSONL 文件。
- **隔离策略**：每个测试类使用独立 `feat136-ws-<run>` 前缀工作区（`run` 为 6 位随机 hex），会话/审批/导入对象均归属该工作区，避免并行污染。
- **共享前置与套件独立性**：工作区登记和会话创建在各测试类 `BeforeAll` 中作为共享前置独立完成（审阅合入前优先项 3），本套件与 FEAT-137/138 套件之间不存在执行顺序依赖；FEAT-137 只读/投影接口仅作观察探针调用，不消费其套件产物，证据按实际用例 ID 绑定。
- **故障注入手段**：仅进程外手段 — 构造损坏 ZIP/二进制/路径穿越条目、提交无效审批选项、复用过期令牌、使用不存在对象 ID；不修改 SUT 对象与内部数据。
- **文件级观察探针**：FEAT-137 只读接口 `GET /api/sessions/{id}/files`、`GET /api/sessions/{id}/file` 与投影接口 `GET /api/sessions/{id}/project/model` 仅作为端到端用例（F136-30、F136-32～F136-34、F136-40）工程文件内容与结构投影的进程外观察手段，不作为其自身契约的验证场景；探针不可用时以 `upload/preview` 变更集差集辅助核对已知文件并在执行报告标注（差集仅能辅助核对预览请求中显式列出的已知文件，不作为生成产物完整性的凭证）。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F136-01 | 创建工作区（名称方式）正例 | 后端可达 | `POST /api/workspaces` body `{"name":"feat136-ws-<run>"}` | `201`；body 含 `id`(非空 string)、`ownerId`(非空 string)、`name`==`feat136-ws-<run>`、`path`(非空 string)、`createdAt`、`lastUsedAt` | workbench 后端 |
| F136-02 | 创建工作区缺少 name 与 path | 后端可达 | `POST /api/workspaces` body `{}` | `400` | 同上 |
| F136-03 | 按路径首次登记成功与重复登记冲突 | 后端可达；测试进程创建真实工程目录（同机部署 SUT 可见） | ①以 `{"path":"<本用例创建的临时工程目录>"}` 首次 `POST /api/workspaces`；②以同一 `path` 再次 `POST /api/workspaces` | ①`201`，body 含 `id`(非空)与 `path`==请求值（首次按路径登记成功）；②`409`（同一目录重复登记冲突） | 同上 |
| F136-04 | 未知工作区不可见 | 后端可达 | `PATCH /api/workspaces/nonexistent-feat136` body `{"name":"x"}` | `404`，错误码 `WORKSPACE_NOT_FOUND` | 同上 |
| F136-05 | 创建开发会话正例 | F136-01 工作区存在；OpenCode ACP 与开发模型可连 | `POST /api/sessions` body `{"workspaceId":"<wsId>","title":"feat136-session"}` | `201`；`engineId`=="opencode"、`workspaceId`==请求值、`id`(非空)、`cwd`(非空)、`status`(非空)、`live`(boolean)、`llm` 为对象 | 同上 + OpenCode |
| F136-06 | 未知工作区创建会话 | 后端可达 | `POST /api/sessions` body `{"workspaceId":"nonexistent-feat136"}` | `404`，且不产生任何会话记录 | workbench 后端 |
| F136-07 | 工作区改名保留工程路径 | F136-01 工作区存在 | `PATCH /api/workspaces/{id}` body `{"name":"feat136-ws-<run>-renamed"}` | `200`；`path` 与 F136-01 创建时逐字一致，`name`==新值 | 同上 |
| F136-08 | 会话创建失败不删除工作区 | F136-01 工作区存在；开发模型/引擎不可连 | 停用开发模型后 `POST /api/sessions`；随后 `GET /api/workspaces` | 会话创建返回 `500`；工作区 `feat136-ws-<run>` 仍出现在列表 | 同上 |
| F136-09 | reattach 三值恢复分场景构造 | F136-05 会话存在且已产生历史事件 | `POST /api/sessions/{id}/reattach`（无请求体）；按返回值分支核对：`restored==true`（恢复成功）读取事件历史核对旧历史仍在；`restored==false`（远端回退新建/本地恢复失败）核对返回的当前引擎会话 ID 非空且旧历史仍可读；`restored==null`（结果未知）核对会话状态可经事件读取确认 | `200`；`restored` ∈ {`true`, `false`, `null`} 三值之一；三种取值下已投影的旧历史均可经 `GET events` 读到（恢复成功、回退新建与结果未知均不丢失已投影历史） | 同上 + OpenCode |
| F136-10 | 不存在会话 reattach 不可见 | 后端可达 | `POST /api/sessions/nonexistent-feat136/reattach` | `404` | workbench 后端 |
| F136-11 | 提交回合受理 202 | F136-05 会话存在 | `POST /api/sessions/{id}/turns` body `{"blocks":[{"text":"列出当前工程目录结构"}]}` | `202`；body 含受理 `turnId`(非空 string) | 同上 + OpenCode + 开发模型 |
| F136-12 | 事件读取契约 | F136-05 会话存在 | `GET /api/sessions/{id}/events` | `200`；`items` 为 array、`hasMore` 为 boolean、`earliestSeq` 为 integer、`initialIdle` 为 boolean | 同上 |
| F136-13 | since 游标过滤 | F136-12 已取得 maxSeq | `GET /api/sessions/{id}/events?since=<maxSeq>` | `200`；所有 `items[].seq` 严格 `> since` | 同上 |
| F136-14 | 事件回合归组与独立 turnId | F136-11 回合已结束（事件流出现 `turn.ended`） | `GET /api/sessions/{id}/events`，按 `type` 过滤 | 存在 `turn.started` 与 `turn.ended`；二者 `turnId` 非空；事件 `turnId` 归组本次回合的用户消息与结束事件 | 同上 + OpenCode + 开发模型 |
| F136-15 | WS ready 与回放序号连续 | F136-14 已产生历史事件 | 建 `WS /ws` 订阅 `{"sessionId":"<id>","since":0}` | 首帧 `ready`(object)；后续 `event` 帧按 `seq` 严格递增、无重复 `seq` | 同上 |
| F136-16 | 审批无效选项拒绝 | 存在待决审批（依赖真实开发回合触发权限请求） | `POST /api/sessions/{id}/approvals/<approvalId>` body `{"optionId":"nonexistent-opt"}` | `400`，错误码 `INVALID_APPROVAL_OPTION`；原待决请求未被批准 | 同上 + OpenCode + 开发模型 |
| F136-17 | 非待决审批提交 409 | 后端可达；会话存在 | `POST /api/sessions/{id}/approvals/nonexistent-<run>` body `{"decision":"reject"}` | `409`，错误码 `APPROVAL_NOT_PENDING` | 同上 + 会话 |
| F136-18 | 取消入口受理确认 | F136-05 会话存在（空闲） | `POST /api/sessions/{id}/cancel` | `200`；随后 `GET events` 仍可读（取消受理不代表回滚） | 同上 |
| F136-19 | 运行中取消后核对终态与已写文件 | F136-11 回合已产生工程文件写入 | 回合执行中 `POST /api/sessions/{id}/cancel`；等待回合到达终态（`turn.ended` 或失败/终止事件）；终态后再次预览导入已知探测文件并读取事件明细 | 回合到达终态且终态可从事件明细确认（取消后回合终止；失败回合也可发出 `turn.ended`，终态原因随事件呈现）；事件中已观察到写入证据（`tool.completed` 涉及探测文件）时，该已写入文件不在新预览变更集中（不产生文件回滚） | 同上 + OpenCode + 开发模型 |
| F136-20 | ZIP 预览正例 | F136-05 会话存在 | 构造含 1 个新文件 `feat136-import/README.md`(UTF-8) 的 ZIP；`POST /api/sessions/{id}/upload/preview` | `200`；`token` 为 64 位小写十六进制；`changes[]` 恰含该文件，`before`==`null`、`after` 非空 | 同上 |
| F136-21 | ZIP 应用正例与零变更重复应用 | F136-20 已取得 token | `POST /api/sessions/{id}/upload` 头 `X-Archive-Preview: <token>` 提交同一 ZIP；随后以相同内容再次预览 | 应用返回 `200` 且 `files`==1；再次预览 `changes[]` 为空数组 | 同上 |
| F136-22 | 应用缺少预览令牌 | F136-20 ZIP 存在 | `POST /api/sessions/{id}/upload`（不带 `X-Archive-Preview` 头） | `400`，错误码 `PREVIEW_REQUIRED` | 同上 |
| F136-23 | 损坏 ZIP 拒绝 | 后端可达 | 提交截断/非 ZIP 字节到 `upload/preview` | `400`，错误码 `INVALID_ARCHIVE` | 同上 |
| F136-24 | 二进制条目拒绝 | 后端可达 | ZIP 含 `feat136-bin/data.bin`（含控制字符的非 UTF-8 字节）；`upload/preview` | `400`，错误码 `UNSUPPORTED_TEXT` | 同上 |
| F136-25 | 路径穿越条目拒绝 | 后端可达 | ZIP 含条目 `../feat136-escape.txt`；`upload/preview` | `400`，错误码含 `INVALID_PATH`（预览阶段即拒，不产生工程文件变更） | 同上 |
| F136-26 | 目标变化后旧令牌失效 | F136-21 文件已存在 | 预览覆盖版 ZIP（同 path 内容 v2）得 token-A；以新预览导入 v3 成功；再提交 v2 ZIP + token-A | `409`，错误码 `PREVIEW_CHANGED`；文件内容保持 v3 | 同上 |
| F136-27 | select-type 明确规则样例四类型判定 | `jiuwen-agent` CLI 可执行；选型样例按 dev-scaffold 规则关键词校准，命中明确规则（不依赖模型，审阅 #599 F1 实测：旧样例 3 条落入低置信度兜底全部判 `moa`） | 分别以命中 `moa`（主控按意图分派查询/推荐/购买）/`react`（ReAct 反应式逐步推理并按需调用工具）/`deep`（DeepAgent 深度研究：工作区、子任务拆解与多轮迭代）/`workflow`（工作流固定步骤与分支可预先枚举）明确规则的需求文本运行 `select-type --requirement <text>` | 退出码 0；stdout JSON 含 `type`/`confidence`/`decidedBy`/`reason`/`signals`；四次输出 `type` 依次 == {`moa`,`react`,`deep`,`workflow`}；`decidedBy` 指示命中规则而非模型兜底 | dev-scaffold CLI |
| F136-28 | model-to-code 无效模型退出 2 | CLI 可执行；模型缺 Start/End 或分支缺默认路径 | `jiuwen-agent model-to-code --model <invalid.json> --out <tmpdir>` | 退出码 `2`（模型无效），不产生目标工程写入 | 同上 |
| F136-29 | validate 状态契约 | CLI 可执行；工作区已有骨架工程 | `jiuwen-agent validate --project <wsPath>` | 退出码 0；stdout JSON 含 `checks`(array)、`summary`、`ok`(boolean)；总体 `status` ∈ {`pass`,`blocked`,`unknown`}；`checks[].status` ∈ {`pass`,`fail`,`review`,`skip`} 且逐项含明细；`status=="blocked"` 时存在 `status=="fail"` 的检查项（L2 §5.5） | 同上 + CLI |
| F136-30 | 端到端 WB-01-01 从空工作区生成智能体 | 新注册空工作区（`feat136-ws-<run>`）可用；OpenCode ACP 与开发模型可连 | ①建立开发会话并发送订单核算需求（输入数量与单价、工具计算金额、大于 1000 元先确认再返回、其余直接返回）；②按实际回复补充约束并处理出现的工具授权请求（允许）；③等待工具执行结束（`turn.ended`），经只读探针 `GET /api/sessions/{id}/files`、`GET /file` 展开工程目录读取业务定义和注册入口；④检查助手实际执行的静态校验及其结果（事件明细 + `jiuwen-agent validate` 同工程对照） | 工作区存在实际生成文件（业务源码、配置与注册入口，经探针以文件实际内容断言）；工具和回复事件归组当前会话 `turnId`；执行失败的检查可找到具体问题明细；尚未执行的检查保留未执行（`skip`）状态；CLI `validate` 与事件呈现不矛盾 | 同上 + OpenCode + 开发模型 + F137 只读接口 |
| F136-31 | 端到端 WB-01-02 生成期间处理工具授权 | 开发回合进行中，助手已发出待授权工具请求（事件 `approval.requested` 含 `approvalId`/`toolCallId`/标题与原始可选项），且回合仍会产生新的待决请求 | ①查看工具请求和作用目标（断言 `approval.requested` 事件与原始选项语义 `once`/`reject`）；②允许一个请求（合法 `optionId` 提交）并检查后续工具事件和工程文件；③对另一个待处理请求选择拒绝（`{"decision":"reject"}`）并查看其状态；④继续补充开发要求（同会话提交补充回合） | 被允许的工具按实际结果呈现（`tool.completed`/`tool.failed`，工程文件按实际结果变化）；拒绝的请求未获准执行（无 `tool.completed`、无对应文件变更、事件保留拒绝状态）；工具授权始终归属原会话和原请求；事件流不将拒绝解释为业务校验失败；补充回合按独立 `turnId` 归组 | 同上 + OpenCode + 开发模型 |
| F136-32 | 端到端 WB-01-03 画布核对与源码定位 | F136-30 工程已生成且静态结构可解析；FEAT-137 投影与只读文件探针可达 | ①核对工程形态、流程入口及注册状态（`GET /project/model`）；②查看节点或边的详情，核对实际 ID、绑定和分支条件（大于 1000 元条件边）；③使用来源入口打开文件（`GET /file`），检查相关代码；④返回画布继续查看其他对象（再次读取投影） | 画布对象来自实际工程（投影对象与探针文件内容对应），详情与源码对应；未确认注册的定义明确显示其状态（`unresolved`/`unregistered`）；文件仍匹配来源时定位有效（内容与投影一致），来源变化时提示需要重新核对（`manifestHash` 变化前置事实成立）；投影契约细节由 F137-06/07/15/22 单独验证 | 同上 + F137 投影与只读接口 |
| F136-33 | 端到端 WB-01-04 修改已选对象并重看画布 | F136-32 已定位到金额分支对象；已记录目标文件内容与 `manifestHash` | ①从对象进入开发对话并提交修改要求（"确认阈值从 1000 元改为 2000 元"）；②检查实际工具执行及文件内容（`tool.completed` + 探针读取文件）；③等待刷新或主动重新读取工程（`GET /project/model`）；④核对新条件及来源位置，确认其他对象仍可浏览 | 工程文件中的条件实际更新（文件内容含 2000 且与事件声明一致），刷新后的图反映新条件（`manifestHash` 变化）；其他对象仍可浏览；工具声称修改但文件未变化时本用例判 FAIL | 同上 + OpenCode + 开发模型 + F137 投影与只读接口 |
| F136-34 | 端到端 WB-01-05 检查失败和部分解析 | 经 ZIP 导入准备一个存在未注册定义或当前分析无法识别的装配语句的工程（F136-21 导入链可用） | ①查看结构中已确认的对象与诊断（`GET /project/model`）；②从诊断检查源码（`GET /file`），说明需要修正的具体行为；③通过对话修改并重新运行适用的校验（修改回合 + `jiuwen-agent validate`）；④再次加载画布（重新读取投影） | 部分可解析内容仍可浏览（已确认结构保留）；未确认结构有实际来源和原因（诊断明细指向具体文件）；修正后的完整性由新一轮输入决定；失败、待复核（`review`）和未执行（`skip`）的检查均可区分；投影契约细节由 F137-08/09/10 单独验证 | 同上 + CLI + F137 投影与只读接口 |
| F136-35 | 审批跨会话挪用冲突（契约级） | 会话 A 存在待决审批；同部署内可访问的会话 B 存在（同工作区或另一工作区） | 以会话 B 的路径 `POST /api/sessions/{bId}/approvals/<A 的 approvalId>` 提交 | `409`，错误码 `APPROVAL_NOT_PENDING`（可访问会话提交他者审批 ID 按非待决冲突核对，`approvalId` 限当前会话当前待决请求）；A 的待决审批状态不变，仍可由会话 A 正常决定（后续经 A 提交无效选项得 400 `INVALID_APPROVAL_OPTION` 佐证） | 同上 + OpenCode + 开发模型 |
| F136-36 | 回合执行失败可观察 | F136-05 会话存在；开发模型已停用（复用 F136-08 故障注入） | 提交回合后读取事件流直至回合到达终态；`GET /api/workspaces` | 失败按错误事件与终态原因判断：出现失败/错误事件，或 `turn.ended` 携带失败终态原因（失败回合也可发出 `turn.ended`，不以缺少 `turn.ended` 判定失败）；工作区仍出现在列表 | 同上 |
| F136-37 | 会话事件隔离与旧结果不覆盖 | 两个工作区各有会话且均有历史事件 | 分别读取两会话 `GET /api/sessions/{aId}/events` 与 `{bId}/events`；A 历史分页读取期间并行读取 B | 两会话事件集按 `sessionId` 隔离，无跨会话 `turnId`/事件串扰；A 的历史读取不改变 B 的事件集（AC-06 的 wire 前置事实） | 同上 + OpenCode |
| F136-38 | 历史分页与实时事件交叠 | F136-05 会话存在且已有历史事件；回合可产生新实时事件 | ①读取历史事件页并记录 `hasMore`/末位 `seq`；②分页以 `since` 游标向后推进期间，并行提交一个回合产生实时事件；③回合终态后以交叠前游标读取新增事件 | 分页各页事件无重复 `seq`；交叠期间产生的新事件（新回合 `turn.started`/`turn.ended`）在 `since` 游标后续读取中完整取回，不丢、不重；分页与实时交叠不破坏序号单调性 | 同上 + OpenCode + 开发模型 |
| F136-39 | 同名 ZIP 覆盖预览差异与确认前文件保持原样 | F136-05 会话存在；目标文件已存在于工程（先用 v1 内容导入） | ①构造同名不同内容（v2）ZIP 预览；②核对 `changes[]` 中该文件 `before`（工程现有 v1 内容）与 `after`（v2 新内容）差异；③确认（应用）前再次预览核对文件保持原样；④带 token 应用 v2 后再次预览 | 预览呈现覆盖差异（`before`==v1 现有内容、`after`==v2 新内容）；确认前工程文件保持原样（再次预览 `before` 仍为 v1，未自动应用）；应用后变更生效（files==1，重复预览无变更）；「快速开始」上传同名 ZIP 的浏览器确认场景归页面验收（本用例为其 wire 前置事实） | 同上 |
| F136-40 | 端到端 Workflow 类型生成正例 | 新注册空工作区（`feat136-ws-<run>`）可用；OpenCode ACP 与开发模型可连；F136-27 规则样例已校准 | ①建立开发会话并提交命中 workflow 选型规则的生成需求（步骤与分支可预先枚举，如订单信息提取→计算→金额阈值分支追问→输出）；②处理出现的工具授权请求（允许）并等待回合结束（`turn.ended`）；③经只读探针 `GET /api/sessions/{id}/files`、`GET /file` 核对生成产物为 Workflow 形态（流程定义与注册入口文件存在），ZIP 预览差集辅助核对已知文件 | 事件链含 `turn.started`/`turn.ended` 且归组当前回合；工程目录出现 Workflow 工程定义与注册入口（探针以文件实际内容断言）；编译、启动、调用与业务断言的分阶段结果由 FEAT-138 套件承接（本用例只验证生成产物存在且与事件声明一致） | 同上 + OpenCode + 开发模型 + F137 只读接口 |
| F136-41 | select-type 低置信度兜底单测 | `jiuwen-agent` CLI 可执行 | 以未命中任何明确选型规则的叙述性需求文本运行 `select-type --requirement <text>` | 退出码 0；`type` ∈ {`moa`,`react`,`deep`,`workflow`}；`decidedBy`/`reason` 可复核为低置信度规则兜底（未调用模型），输出含兜底依据与 `signals` | dev-scaffold CLI |
| F136-42 | 无权访问会话的审批不可见 | 后端可达 | 以不存在/无权访问的会话 ID `POST /api/sessions/{nonexistent}/approvals/<任意 approvalId>` 提交 | `404`，错误码 `SESSION_NOT_FOUND`（会话不可见与审批非待决冲突分开核对，与 F136-35 的 `409` 语义互斥） | workbench 后端 |

### 5.1 显式 deferred / 白盒联动项

- F136-16/F136-19/F136-30～F136-36/F136-38～F136-40 依赖真实开发回合触发 OpenCode 权限请求；OpenCode 默认权限为 `{"*":"ask"}`（L2 §6.3），待决审批是否出现取决于需求文本是否触发需审批的编码操作 — 环境门禁不满足时按 `assumeTrue` SKIPPED 并在报告中标注。
- F136-30/F136-32/F136-33/F136-34/F136-40 的文件与投影断言依赖 FEAT-137 只读接口探针；探针不可用时以 `upload/preview` 变更集差集辅助核对已知文件（差集仅能辅助核对预览请求中显式列出的已知文件，不作为生成产物完整性的凭证），兜底仍不完整时相关断言 SKIPPED 并标注原因（不静默降级为仅事件断言）。
- FEAT-137/138 联合验收（"选择对象→开发对话修改→工程刷新"、"修改代码→更新运行产物→重新调试"）不在本特性套件内，另行设计。
- 远端 Host 形态（`P1_ISOLATION=pod/remote/docker`）专属场景：`session/load` 恢复、500 项内存缓冲、远端导入限额 — 待远端部署拓扑可用后补充矩阵（ID 预留 F136-43 起）。
- F136-16/F136-35 的待决审批依赖真实回合触发权限请求；若需求文本未触发审批，两用例按环境门禁 SKIPPED，不以 mock 审批对象替代（审阅 #599 D5 要求以真实待决审批核对 409）。

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| agent-dev-workbench 后端（HTTP wire） | 真实 SUT | 正式制品；控制面端口为 `P1_PORT`（本机验收 4500，`7310` 为远端形态 agent-host 端口），经 `sut.workbench.base-url` 配置；除 `/api/deployment`、`/api/auth/*` 外需本地账号登录（`sut.workbench.username/password` 或 `WORKBENCH_ADMIN_PASSWORD`），会话 Cookie 由用例共享前置注入 |
| `jiuwen-agent` CLI（dev-scaffold 交付） | 真实 SUT | 正式交付物；经 PATH 或 `sut.workbench.cli-path` 定位，不可用时相关场景环境门禁 SKIPPED |
| OpenCode CLI | 真实依赖 | 用户提供的 Code Agent（本机 1.16.0）；工作台经 ACP 接入，本测试不直接调用其内部协议 |
| 开发模型服务 | 真实依赖 | 用户提供；凭据经环境变量注入，不落库、不写入日志 |
| `feat136-ws-<run>` 工作区 | Fixture | 每测试类独立随机后缀，测试结束后通过 `DELETE /api/workspaces/{id}` 清理 |
| 导入 ZIP 构造器 | Fixture | 进程内构造合法/损坏/二进制/穿越 ZIP 字节；不预置共享文件 |
| FEAT-137 只读接口（`/files`、`/file`、`/project/model`） | 观察探针 | 仅作为端到端用例（F136-30、F136-32～F136-34、F136-40）工程文件内容与结构投影的进程外观察面；其自身契约由 FEAT-137 套件（F137-11～F137-21 等）验证，不可用时以 `upload/preview` 变更集差集辅助核对已知文件 |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

1. **F136-01 + F136-03 + F136-05**：工作区与会话分离成立 — 按名称与按路径首次登记均返回 201，重复登记 409；会话创建返回 `engineId=="opencode"`，工作区记录独立存在；引擎失败时工作区不被删除（F136-08）。
2. **F136-11 + F136-14**：回合受理 202 且实际执行以事件 `turn.started`/`turn.ended` 确认；HTTP `turnId` 不被当作事件关联键（L2 §1.3 差距条款的可观察面）；回合失败按错误事件与终态原因判断、失败回合也可发出 `turn.ended`（F136-36）。
3. **F136-20 + F136-22 + F136-26 + F136-39**：ZIP 先预览后应用；缺令牌 `PREVIEW_REQUIRED`；旧令牌对已变化目标 `PREVIEW_CHANGED`；同名覆盖预览呈现差异且确认前文件保持原样 — 导入安全链完整。
4. **F136-17 + F136-35 + F136-42**：非待决审批 `APPROVAL_NOT_PENDING`；可访问会话挪用他者审批 ID `409`；无权访问会话 `404 SESSION_NOT_FOUND` — 审批不可跨请求/跨会话挪用且会话不可见语义与冲突语义互斥。
5. **F136-09**：恢复结果如实返回三值之一，不伪报恢复成功；恢复成功、回退新建与结果未知三场景下旧历史均不丢失。
6. **F136-30 + F136-31**：端到端 WB-01-01/WB-01-02 — 生成产物与审批正反链以文件实际内容和工具事件为凭证，不把助手回复当写入凭证；允许后实际结果由工具事件证明，拒绝保留拒绝状态且不得显示为执行成功（AC-01/AC-02）。
7. **F136-32 + F136-34**：端到端 WB-01-03/WB-01-05 — 画布对象与源码对应、未确认注册状态可见，失败/待复核/未执行检查可区分（AC-03/AC-05）。
8. **F136-33**：端到端 WB-01-04 — 修改以文件实际更新为凭证，工具声称修改但文件未变化不得判 PASS（AC-04）。
9. **F136-27 + F136-41**：选型判定以命中明确规则的样例覆盖四类型；低置信度兜底独立可复核（`decidedBy`/`reason` 指示规则兜底，不以模型输出伪装确定性判定）。

失败语义必须与基线同构：所有错误码断言与 L2 §2.2/§2.6/§2.7 逐字一致；负向场景同时命中"目标错误码 + 工程无变更"（F136-25 预览即拒、F136-28 退出码 2 且无写入）。

## 8. 执行策略

- **Smoke 集合**：F136-01、F136-03、F136-04、F136-10、F136-12、F136-17、F136-18、F136-22、F136-23、F136-42（无 OpenCode/模型依赖的纯控制面契约）。
- **Full suite**：F136-01～F136-42 全量。
- **P0 必须全绿**：F136-01、F136-05、F136-11、F136-14、F136-20、F136-21、F136-22、F136-26、F136-35、F136-39、F136-30、F136-31、F136-32、F136-33、F136-34、F136-40（五条端到端用例与 Workflow 生成正例全部纳入 P0）。
- **依赖门禁**：控制面场景需后端可达；回合/审批/导入应用/文件探针场景需 OpenCode ACP 与开发模型可连；CLI 场景需 `jiuwen-agent` 在 PATH；F136-30/F136-32～F136-34/F136-40 需 FEAT-137 只读文件与投影探针可达（或以 preview 差集辅助核对已知文件）。未满足时按 `assumeTrue` SKIPPED 并在执行报告中单列阻塞项，不计 PASS。
- **修订重跑（审阅 #599）**：F136-03（补首次按路径登记成功）、F136-09（三值分场景构造）、F136-19（终态与已写文件核对）、F136-27（明确规则样例）、F136-35（409 语义修正）与新增 F136-38/F136-39/F136-40/F136-41/F136-42 修订后重跑受影响用例；工作区登记与会话创建作为共享前置，消除与 FEAT-137 套件的先后依赖。

```bash
# 从源码重新打包 SUT（feature_source 指向工作台实现仓）
export SUT_SOURCE_DIR=<agent-dev-workbench 实现仓路径>
./scripts/sut/provision-sut.sh --env openjiuwen

# 按特性 Tag 执行
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-136 -Dmaven.test.failure.ignore=true
```

## 9. FRS-136 场景追溯矩阵

FRS-136 由 FEAT-136 与 FEAT-137 联合承接（FRS front matter `drives: [FEAT-136, FEAT-137, ...]`）。下表中 `F137-xx` 由 `FEAT-137-agent-structure-visualization-source-linkage.md` 套件承接并另行执行；两套件合并构成 FRS-136 场景级验收基线。

### 9.0 对应关系原则

端到端用例 **F136-30～F136-34 与 FRS-136 §6 五个标准流程用例一一对应**：每条端到端用例在单用例内闭环覆盖对应标准用例的全部前置条件、操作步骤与预期结果，不将单个标准用例的步骤拆分到多个测试用例。F136-40 为 Workflow 类型生成正例（补充 F136-30 的形态覆盖）。F136-01～F136-29 与 F136-35～F136-42 为契约级用例（单项契约与异常语义），仅作补充佐证，不作为标准流程步骤的替代覆盖。

### 9.1 功能要求（FRS §4，FR-01～FR-10）

| FR | 覆盖用例 | 说明 |
|---|---|---|
| FR-01 建立开发上下文 | F136-01、F136-03、F136-05、F136-07、F136-30 | 按名称/按路径登记、重复登记冲突、工作区与会话分离、改名保留工程路径；端到端入口 |
| FR-02 提交与补充需求 | F136-11、F136-30、F136-31、F136-38 | 单回合提交；同会话补充回合及 turnId 归组在端到端内验证；分页与实时交叠不丢事件 |
| FR-03 工具操作反馈 | F136-31、F136-35、F136-42、F136-16、F136-17 | 允许/拒绝正反链（端到端）+ 跨会话挪用 409 + 不可见会话 404 + 无效选项与非待决负向 |
| FR-04 生成真实工程 | F136-30、F136-40 | 文件级产物核验（业务源码、配置与注册入口）；Workflow 形态生成正例 |
| FR-05 呈现工程结构 | F136-32 + F137-06/08/09/10 | 端到端旅程级 + 形态识别/注册状态契约 |
| FR-06 详情与来源定位 | F136-32 + F137-15、F137-22 | 端到端旅程级 + 文件浏览与指纹契约；UI 高亮层单独验收 |
| FR-07 按对象修改 | F136-33 + F137-22 | 对话修改落盘（端到端）+ 刷新后结构变化契约 |
| FR-08 解析不确定反馈 | F136-34 + F137-08/09/10 | 端到端旅程级 + 诊断码契约 |
| FR-09 刷新与会话归属 | F136-37、F136-38 + F137-22 | 旧结果不覆盖与分页/实时交叠（wire 事实）+ 刷新指纹变化 |
| FR-10 检查结果表达 | F136-30、F136-34、F136-29 | 端到端事件呈现 + 导入后明细 + CLI 契约；UI 呈现层单独验收 |

### 9.2 端到端主成功旅程（FRS §5，步骤 1～7）

| 旅程步骤 | 覆盖用例 |
|---|---|
| 1. 新建工作区进入会话（空目录状态可区分） | F136-01、F136-05（端到端入口 F136-30）+ F137-10 |
| 2. 输入需求文本 | F136-11（端到端入口 F136-30） |
| 3. 补充约束 + 处理工具授权请求 | F136-31（步骤①②④） |
| 4. 目录出现流程/工具/注册入口；核对静态校验 | F136-30（步骤③④） |
| 5. 打开画布核对流程/工具/分支/条件 | F136-32 |
| 6. 来源定位 + 阈值修改 + 文件/画布刷新 | F136-33 |
| 7. 进入运行调试 | FRS-137 范围，FEAT-138 套件承接 |

### 9.3 标准流程用例（FRS §6，WB-01-01～05）—— 1:1 端到端对应

| FRS 标准用例 | 对应端到端用例 | 闭环内容（前置 + 步骤 1～4 + 预期结果均在单用例内） | 契约级佐证（不替代步骤） |
|---|---|---|---|
| WB-01-01 从空工作区生成智能体 | **F136-30** | 建会话发需求→补充约束并授权→探针核对业务定义与注册入口→静态校验结果；产物归属当前会话、失败检查明细、未执行保留状态 | F136-05/11/14（会话/回合/归组契约）、F136-29（validate 契约） |
| WB-01-02 生成期间处理工具授权 | **F136-31** | 查看请求→允许并核对结果与文件→拒绝并核对状态→补充要求；授权归属原会话原请求、拒绝不解释为校验失败 | F136-16/17（无效选项/非待决负向）、F136-35（跨会话挪用 409）、F136-42（不可见会话 404） |
| WB-01-03 画布核对与源码定位 | **F136-32** | 核对形态与注册→节点/边详情与分支条件→来源打开文件→返回画布继续浏览；投影对象与源码对应、来源变化提示前置事实 | F137-06/07/15/22（投影与指纹契约细节） |
| WB-01-04 修改已选对象并重看画布 | **F136-33** | 对象发起修改→核对工具执行与文件→刷新/重读工程→核对新条件与其他对象；未落盘不判过 | F137-22（manifestHash 刷新契约） |
| WB-01-05 检查失败和部分解析 | **F136-34** | 导入含未注册定义工程→已确认对象与诊断→诊断检查源码→对话修改重跑校验→再载画布；失败/待复核/未执行可区分 | F137-08/09/10（部分解析诊断契约）、F136-29（validate 契约） |

### 9.4 异常与恢复（FRS §7）

| 异常行 | 覆盖用例 | 说明 |
|---|---|---|
| 进入开发（拒绝进入目标资源） | F136-04、F136-06、F136-10、F136-42 | 未知资源 404；无权访问会话的审批不可见；身份失效由部署环境提供（FEAT-141），不在本套件 |
| 会话恢复（恢复成功/回退新建/结果未知） | F136-09 | 三值分场景构造，均核对旧历史不丢失 |
| 建立会话（模型不可用、回合失败） | F136-08、F136-36 | 工作区仍可识别 + 回合失败按错误事件与终态原因判断 |
| 工具执行（拒绝/失败保留状态） | F136-31（端到端）、F136-35（跨会话契约） | 拒绝状态保留，无对应文件变更 |
| 运行中取消（终态与已写文件） | F136-19 | 取消后核对终态与已写文件不回滚 |
| 工程解析（诊断） | F136-34（端到端）+ F137-08/09/10 | 呈现已确定部分与诊断 |
| 来源定位（文件已改变） | F136-32（端到端前置事实）+ F137-22 | 指纹变化；UI 提示层单独验收 |
| 工程刷新（新读取失败） | ➖ 待补 | F137-22 仅覆盖刷新成功分支；失败分支在 FEAT-137 套件差距清单预留 |
| 会话切换（旧结果不覆盖） | F136-37 | 事件按 sessionId 隔离；前端上下文代次投影属页面验收 |

### 9.5 验收标准（FRS §8，AC-01～06）

| AC | 主覆盖（端到端） | 契约级佐证 |
|---|---|---|
| AC-01 | F136-30、F136-40 | F136-01、F136-03、F136-05、F136-11、F136-14 |
| AC-02 | F136-31 | F136-35、F136-42、F136-16、F136-17 |
| AC-03 | F136-32 | F137-06、F137-15、F137-22 |
| AC-04 | F136-33 | F137-22 |
| AC-05 | F136-34 | F136-29、F137-08/09/10 |
| AC-06 | F136-37 | F136-38 |

已知残留差距：FRS §7"工程刷新失败"分支两套件均未覆盖（FEAT-137 差距清单预留）；"始终允许"（`always`）语义按界面实际提供的选项另行验证（FRS WB-01-02 输入注记），不在本轮矩阵强制项内。页面 MUST 浏览器证据项（「快速开始」同名 ZIP 覆盖差异确认、画布来源定位与对象上下文交接、会话切换后的迟到结果隔离、工程刷新失败提示）由页面验收方案绑定浏览器证据，本套件只覆盖其 wire 前置事实（F136-39 等）。

- **执行状态更新（2026-11-24，全量验收）**：42 个场景全部落码并通过 `-Dgroups=feat-136` 全量回归：**40 PASS / 1 FAIL / 1 SKIP**。1 FAIL = F136-04 产品缺陷（工作区 404 缺 `WORKSPACE_NOT_FOUND` 机器可读码，issue：`draft-feat136-workspace-404-missing-code.md`）；1 SKIP = F136-36 环境门禁（无法在模型可连环境构造回合失败）。7 个端到端标准流程用例（F136-30～34、37、40）全部 PASS，FRS-136 WB-01-01～05 与 AC-01～06 验收证据补齐。开发模型：火山 Ark `glm-5.3-flash`。Windows local 形态下 `/files`、`/file` 探针因 `SecureDirectoryStream` 不受支持返回 403，端到端产物断言降级为同机工作区目录直读（Linux 部署形态下探针可用）。详见 `docs/reports/FEAT-136-test-execution-report.md`。