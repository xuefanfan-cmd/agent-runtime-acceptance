---
feature_id: FEAT-137
feature_title: 智能体结构可视化与源码联动
scope: agent-dev-workbench; 特性文档与 L2 updated 2026-11-19，实现核对基线 e3785f804cf8c8ca81337253159d7824c3b7efa5；2026-11-24 按 workbench-v1130-testplan-review.md #602 审阅结论整改（MUST 计数、结构/来源核对、注册三态、边端点、跨目录游标、路径错误与越界区分、私钥无正文、Agent Host 链路、页面验收清单、共享前置）
deployable_units: [agent-dev-workbench 后端（App/ProjectAnalysis/ProjectModel/ProjectFiles）, 控制台前端（页面验收单列于 §5.2）]
sut: agent-dev-workbench 工程投影与只读文件访问 HTTP 面（工程形态投影、Workflow 静态分析、目录/文件只读契约）
status: draft
features: [FEAT-137, FEAT-136, FEAT-138]
updated: 2026-11-24
---

# FEAT-137 智能体结构可视化与源码联动 — 测试设计

## 1. 测试目标

验证 agent-dev-workbench 从实际工程分析并呈现智能体结构的外部可观察行为：开发者会话工程经 `GET /api/sessions/{id}/project/ui`（UI 投影，指纹命中复用缓存）与 `GET /api/sessions/{id}/project/model`（即时分析）获得工程投影（`shape.type` ∈ {moa, workflow, deep, react}、`shape.renderer` ∈ {agent-tree, node-graph, tool-list}、`shape.confidence` ∈ {certain, probable, uncertain}、证据、诊断与指纹），经 `GET /api/sessions/{id}/files` 与 `GET /api/sessions/{id}/file`（`view=workbench`）在会话工程范围内分页浏览目录并只读读取源文件（分页游标、脱敏、SHA-256 指纹、只读保护）。

本测试不编译或调用 SUT 内部类，不读取 SUT 内部状态（`project_ui.json` 缓存、分析器内部预算、前端组件状态）；所有断言写在 wire 契约（HTTP 状态码、JSON 字段与取值、错误码）上。期望值逐字取自特性文档 §2/§3/§5 与 L2 §2/§3/§5/§6/§7。

**2026-11-24 整改说明**（依据 `workbench-v1130-testplan-review.md` #602 审阅结论）：

1. 特性档 §2.2 实为 **11 项 MUST**（原方案误写 12 项），事实来源与范围条目已同步修正。
2. 形态识别与 Workflow 场景由「只核对 shape 枚举」升级为「核对实际结构与来源」：补 EDP 主控与候选结构、ReAct/DeepAgent 工具及输入声明、注册三态（`unresolved`/`unregistered`）与整体分析不可用、分支条件/顺序/边端点一致性（F137-03/04/05/07 及新增 F137-24～F137-26）。
3. 修改后刷新不仅断言 `manifestHash` 变化，还核对刷新后的实际结构（F137-22）；补源文件变化后旧位置失效的 wire 前置事实（F137-30）；双流程切换无运行调用、两条投影接口都失败旧图过期、来源定位、选中对象交接、不兼容投影阻断等页面 MUST 移入 §5.2 页面验收清单并要求绑定浏览器证据。
4. 补边界与安全面：其他用户真实会话（F137-31）、跨目录游标（F137-27）、路径错误与访问越界各自结果（F137-16 拆分 + F137-28）、私钥拒绝且无正文（F137-29）、默认独立部署形态 Agent Host 文件与投影读取（F137-32）。
5. 工作区登记与会话创建为共享前置（SharedSetup），不依赖 FEAT-136（#599）套件执行顺序，证据按实际用例 ID 绑定（§8）。

## 2. 范围与非范围

### 范围

1. 投影外壳契约：`schemaVersion=="2.3"`、`sessionId`、`project`、`shape.*` 枚举、`payload`、`diagnostics`、`fingerprints.manifestHash`、`generatedAt`（L2 §2.2 出参表）。
2. 会话归属与可见性：未知会话投影/目录/文件读取返回 404 `SESSION_NOT_FOUND`，不暴露目标工程内容（L2 §2.1/§2.2 场景表）。
3. 形态识别（L2 §3.2 识别次序）：MOA 候选配置 → `moa`/`agent-tree`/`certain`；ReActAgent 特征 → `react`/`tool-list`；DeepAgent 特征 → `deep`/`tool-list`；Workflow 注册齐备 → `workflow`/`node-graph`；无证据 → `react`/`tool-list` 但 `confidence=="uncertain"` 且诊断 `SHAPE_UNDETERMINED`。识别不止于 shape 枚举，须核对实际结构与来源：MOA 载荷核对 EDP 主控与候选（主控名与候选名在载荷中可定位）；ReAct/DeepAgent 载荷核对工具声明与输入声明；`shape.evidence` 引用声明来源文件（特性档 §2.1/§5.1，审阅整改项）。
4. 混合证据：Workflow 与 ReAct/DeepAgent 并存时按 Workflow 展示并出现 `SHAPE_AMBIGUOUS`（L2 §3.2）。
5. Workflow 静态分析：Agent/流程/图分层（`payload.workflow`）、注册状态 ∈ {confirmed, unresolved, unregistered}、节点 kind 区别、普通/流式/条件/多来源连接区别（条件边 `order`、`isDefault`、多来源 `sourceGroupId`）、循环/子流程仅外层节点（`WF_SUBGRAPH_NOT_EXPANDED`）、非规范语句 → 部分解析 + 诊断（L2 §3.3/§5.3）。注册三态与整体分析不可用均须覆盖：注册来源无法确认 → `unresolved` + `WF_REGISTRATION_UNRESOLVED`（F137-24）；流程定义无任何 Agent 注册 → `unregistered`，不伪装 confirmed（F137-25）；定义整体无法分析 → 状态降级/诊断标明，不伪装完整识别（F137-26）。条件分支须核对条件表达式文本、`order` 数值与边端点一致性（每条边端点可解析到既有节点 ID，审阅整改项）。
6. 即时分析一致性：`/ui` 与 `/model` 对同一工程返回相同 `shape.type` 与载荷语义（L2 §2.2：同一模型构建器产生业务字段）。
7. 目录分页契约：`entries`/`hasMore`/`nextCursor`/`directoryFingerprint`/`excludedNames`；`limit` 合法范围 1 至 500、默认 200，越界 400 `INVALID_LIMIT`；拼造游标 400 `INVALID_CURSOR`；分页间目录变化 409 `DIRECTORY_CHANGED`（L2 §2.3）。
8. 文件只读契约：`text`/`encoding`/`size`/`mtime`/`fingerprint`（等于测试侧对原文件字节计算的 SHA-256）、`binary`/`editable`/`truncated` 均为 false；路径越界 400 `INVALID_PATH` 或 403；文件不存在 404 `FILE_NOT_FOUND`；超大文件 413 `FILE_TOO_LARGE`；二进制/非 UTF-8 415 `UNSUPPORTED_TEXT`（L2 §2.3）。
9. 只读保护：`PUT /api/sessions/{id}/file?view=workbench` 返回 405 `READ_ONLY_VIEW` 且文件字节不变（特性档 §3、L2 §2.1/§2.3）。
10. 脱敏契约：凭据类文件读取返回 `redacted==true`、`maskedLineRanges` 非空、遮蔽后正文不含原敏感值，而 `fingerprint` 仍为原始字节 SHA-256（L2 §2.3/§5.4）。
11. 修改后刷新：工程文件变更后 `GET /project/model` 返回的 `fingerprints.manifestHash` 与变更前不同（L2 §5.5 输入快照：新增/删除/内容变化影响摘要），且刷新后的实际结构随变更更新（新增候选出现在载荷中，审阅整改项）；源文件变化后旧来源位置失效的前置事实成立（F137-30，特性档 §5.1.4）。
12. 并发合并：同一工程并发请求 `/ui` 与 `/model`，两个响应均 200 且各自 `sessionId` 正确（L2 §7.3：共享一次在途构建，各自响应会话正确）。共享在途分析与实现仓单测 `ProjectAnalysisTest.concurrentRequestsForTheSameRootShareOneAnalysis` 的构建次数断言构成白盒联动证据（审阅整改项：该单测已在实现仓验证共享在途构建只构建一次；本套件 HTTP 用例核对响应与会话归属）。
13. 会话可见性扩展（审阅整改项）：启用多用户隔离的部署形态下，其他用户真实会话的投影/文件读取返回 404 `SESSION_NOT_FOUND`，不泄漏内容（L2 §2.1/§2.2）；单用户部署形态下 SKIPPED 并移交部署验收（F137-31）。
14. 跨目录游标拒绝（审阅整改项）：目录 A 签发的分页游标用于目录 B 的 `/files` 请求 → 400 `INVALID_CURSOR` 或 409 `DIRECTORY_CHANGED`，响应不含目录 B 条目（L2 §2.3：游标由服务端签名且绑定目录）（F137-27）。
15. 路径错误与访问越界区分（审阅整改项；执行轮核实实现语义后修正分类）：语法非法路径（反斜杠、Windows 绝对路径、URI scheme、`\0`、空路径）→ 400 `INVALID_PATH`；语义越界（`..` 相对越界、符号链接指向工程外、私密文件）→ 403（L2 §2.3：访问越界、私密文件或不允许路径 403）；两种结果各自核对，均不返回目标内容（F137-16/F137-28/F137-29）。
16. 私钥文件拒绝且无正文（审阅整改项）：私钥类文件（PEM）读取返回 `403`（L2 §2.3 场景表：私密文件 403 文件访问错误），响应不含私钥材料（正文/密钥体不出现）（F137-29）。
17. Agent Host 形态读取链路（审阅整改项）：默认独立部署形态（仓库内 `deploy/standalone/README.md`）下，经 Agent Host 的目录/文件与投影读取返回 200 且会话归属正确（L2 §1.3/§7.2）；未部署时 BLOCKED 单列，场景 ID 保留（F137-32）。

### 非范围

1. 前端页面一致性中的**渲染实现细节**（EDP Agent 展示名称映射、画布绘制）— L2 §2.2.1 明示"页面实现和验收结果另行记录"，属前端仓白盒。但页面 MUST 行为（来源定位、选中对象交接、双流程切换无运行调用、旧位置失效提示、双投影失败旧图过期、不兼容投影阻断）为验收必测项，单列于 §5.2 页面验收清单并要求绑定浏览器证据（审阅整改项：不得以"另行记录"为由整体豁免）。
2. 流程浏览选择（`workflowKey` 切换）不改变业务运行时 — 浏览选择是控制台事件（L2 §2.1），wire 上无独立端点可观察；其**页面行为**（双流程切换且无业务运行调用）列入 §5.2 页面验收清单（审阅整改项）；运行期行为由 FEAT-138 承接。
3. 对象详情、来源定位高亮与"文件变化提示重新分析"的交互呈现 — 控制台事件面（SourceRef → SourcePanel），按 §5.2 页面验收清单绑定浏览器证据验证；其 wire 前置事实（指纹、manifestHash、旧位置失效）由 F137-15/F137-22/F137-30 覆盖。
4. 对话修改上下文交接（选中对象 → 开发对话）— 控制台事件面，页面行为列入 §5.2 清单绑定浏览器证据；后续修改由 FEAT-136 承接并已在该特性套件验证。
5. 远端 Agent Host 形态的**镜像限额细节**（ProjectMirror 镜像限额、`SESSION_NOT_CONNECTED` 503、远端镜像路径映射、远端读取限额 512 KiB/1500 文件）— 需独立远端部署拓扑，保留 deferred（L2 §1.3 差距清单同样单列远端限制）。默认独立部署形态下 Agent Host 的文件与投影**基础读取链路**已移入范围第 17 项（F137-32，审阅整改项）。
6. 队列满/分析超时/中断错误面（`PROJECT_ANALYSIS_BUSY`/`PROJECT_ANALYSIS_TIMEOUT`/`PROJECT_ANALYSIS_INTERRUPTED` 503 族）— 需受控压满 2 线程/16 队列或注入分析耗时，无法进程外构造确定性触发；列入 deferred，待故障注入手段交付后以预留 ID F137-33 起扩展。
7. 预算限额边界（500 文件/2 MiB 单文件/20 MiB 总输入/4 MiB 输出预算、`WF_INPUT_CHANGED` 并发写竞态）— 依赖分析器内部预算时序，进程外无法确定性复现，同上 deferred。
8. `multi`/`studio` 混合工程还原 — 特性档 §2.1/§5.2 显式不承诺独立画布形态。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `docs/develop/02-features/FEAT-137-智能体结构可视化与源码联动.md`（updated 2026-11-19） | §2.1 类型与展示范围、§2.2 功能要求（**11 项 MUST**，审阅勘误：原方案误写 12 项）、§2.3 Workflow 节点与连接、§3 外部接口与入口、§5.1 行为语义（形态识别/解析完整性/来源定位/刷新）、§5.1.6 错误事实要求、§6 对下游测试约束 |
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-dev-workbench/FEAT-137-智能体结构可视化与源码联动.md`（updated 2026-11-19，基线 e3785f804） | §2.1 入口清单、§2.2 投影契约与错误码表、§2.3 目录/文件只读契约与错误码表、§3.2 形态识别决策、§3.3 Workflow 静态分析、§5.1/§5.3/§5.4/§5.5 数据对象与缓存、§6.2 固定限额、§7 验收标准 |

断言中的错误码 `SESSION_NOT_FOUND`、`INVALID_LIMIT`、`INVALID_CURSOR`、`INVALID_PATH`、`FILE_NOT_FOUND`、`FILE_TOO_LARGE`、`UNSUPPORTED_TEXT`、`READ_ONLY_VIEW`、`DIRECTORY_CHANGED` 及诊断码 `SHAPE_UNDETERMINED`、`SHAPE_AMBIGUOUS`、`WF_SUBGRAPH_NOT_EXPANDED`、`WF_NONCANONICAL_FORM` 均在 L2 §2.2/§2.3/§3.2/§3.3/§5.3 逐字出现。

## 4. 部署拓扑

```text
+------------------------------+   HTTP    +--------------------------------------------+
|  测试客户端 (JUnit)           | --------> |  agent-dev-workbench 后端                   |
|  integration 层               |  POST /api/workspaces（登记 fixture 工程）               |
|  HttpClient（wire 断言）      |  POST /api/sessions（开发会话）                         |
|                              |  GET /api/sessions/{id}/project/ui | /project/model    |
|                              |  GET /api/sessions/{id}/files | /file                 |
|                              |  PUT /api/sessions/{id}/file?view=workbench            |
+---------------+--------------+           +---------------------+----------------------+
                |                                                  |
                | 进程内构造 fixture 目录（MOA YAML / ReAct / DeepAgent /   |
                | Workflow Java / 凭据 / 二进制 / 超大文件）                  |
                v                                                  v
+------------------------------+          +---------------------------------+
|  <tmp>/feat137-fx-<run>/     |          |  OpenCode 引擎（会话创建依赖）    |
|  工程文件为唯一业务定义来源     |          |  开发模型服务（用户提供）         |
+------------------------------+          +---------------------------------+
```

边界要求：

- **真实依赖（不 mock）**：agent-dev-workbench 后端正式制品（HTTP wire）；OpenCode 引擎仅在创建开发会话时被后端调用，本测试不直接与其交互。
- **真实 SUT**：工程投影入口（`/project/ui`、`/project/model`）与文件只读面（`/files`、`/file`）为正式制品行为，不接受任何 Fixture 替身投影。
- **观察 surface 限定**：仅 L2 §2.1 入口清单所列 HTTP 端点；不读取 `project_ui.json` 缓存、分析器预算状态、镜像目录、前端组件状态。
- **隔离策略**：每个测试类在系统临时目录创建独立 `feat137-fx-<run>` 工程目录（run 为 6 位随机 hex），经 `POST /api/workspaces {path}` 登记，会话对象归属该工作区，结束后删除 fixture 目录并尽力删除工作区。
- **共享前置（审阅整改项）**：工作区登记（`POST /api/workspaces`）与开发会话创建（`POST /api/sessions`）由本套件 `@BeforeAll` 共享前置自行完成，**不依赖 FEAT-136（#599）套件的执行顺序或其遗留数据**；全部证据按本套件实际用例 ID（F137-xx）绑定记录。
- **Agent Host 形态（审阅整改项）**：默认独立部署形态按实现仓 `deploy/standalone/README.md` 部署；F137-32 经该形态入口核对文件与投影读取，环境不可用时按 BLOCKED 单列，不删项。
- **故障注入手段**：仅进程外手段 — 在 fixture 工程目录写入畸形 Java/凭据/二进制/超大/私钥文件、增删目录文件、创建符号链接、分页间隙增删目录文件、对只读端点发起 PUT；不修改 SUT 对象、缓存与内部数据。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F137-01 | 未知会话投影不可见 | 后端可达 | 对不存在会话 ID feat137-nope-<run> 依次 `GET /project/ui`、`GET /project/model`、`GET /files`、`GET /file` | 四个请求均 `404`，`project/*` 响应错误码 `SESSION_NOT_FOUND`（L2 §2.2 场景表） | 无 |
| F137-02 | 投影外壳契约 | 后端可达；会话已创建且工程可分析 | `GET /project/ui` | `200`；`schemaVersion=="2.3"`；`sessionId==<会话 ID>`；`shape.type` ∈ {moa,workflow,deep,react}；`shape.renderer` ∈ {agent-tree,node-graph,tool-list}；`shape.confidence` ∈ {certain,probable,uncertain}；`shape.evidence` 为数组；`diagnostics` 为数组；`fingerprints.manifestHash` 非空；`generatedAt` 非空（L2 §2.2 出参表） | 任意可分析工程会话 |
| F137-03 | MOA 形态 → 智能体树（主控与候选核对） | 会话工程含 MOA 候选配置 YAML（1 主控 + 2 候选） | `GET /project/model` | `200`；`shape.type=="moa"`；`shape.renderer=="agent-tree"`；`shape.confidence=="certain"`；`payload` 为对象；载荷可核对实际结构：主控名 `feat137-moa-master` 与候选名 `feat137-moa-worker-a/b` 均在载荷中出现（EDP 主控与候选，特性档 §2.1/§5.1；审阅整改项）（L2 §3.2 次序 1：候选配置 YAML 为确定证据） | feat137-moa 工程 |
| F137-04 | ReAct 形态 → 工具清单（工具与输入声明核对） | 会话工程含 ReActAgent 特征 Java 源码 + 工具类源码（含输入参数） | `GET /project/model` | `200`；`shape.type=="react"`；`shape.renderer=="tool-list"`；`shape.confidence` ∈ {certain,probable}；载荷含工具声明（`OrderQueryTool`/`RefundTool` 可定位）与输入声明（输入参数名 `orderId` 可定位）；`shape.evidence` 引用声明来源（`DemoReactAgent`）（特性档 §2.1/§5.1；审阅整改项）（L2 §3.2 次序 4） | feat137-react 工程 |
| F137-05 | DeepAgent 形态 → 工具清单（工具与输入声明核对） | 会话工程含 DeepAgent 特征 Java 源码 + 工具类源码（含输入参数） | `GET /project/model` | `200`；`shape.type=="deep"`；`shape.renderer=="tool-list"`；`shape.confidence` ∈ {certain,probable}；载荷含工具声明（`ResearchTool` 可定位）与输入声明（输入参数名 `query` 可定位）；`shape.evidence` 引用声明来源（`DemoDeepAgent`）（特性档 §2.1/§5.1；审阅整改项）（L2 §3.2 次序 3） | feat137-deep 工程 |
| F137-06 | Workflow 形态与注册状态 | 会话工程含 WorkflowAgent 构造 + 规范注册 + 流程定义 | `GET /project/model` | `200`；`shape.type=="workflow"`；`shape.renderer=="node-graph"`；`payload.workflow` 为对象；流程对象含 `registration.status=="confirmed"`；图对象含 `nodes`（数组）与 `edges`（数组）；诊断数组无 `WF_REGISTRATION_UNRESOLVED`（L2 §3.3：注册确认后才可 confirmed） | feat137-workflow 工程 |
| F137-07 | Workflow 连接语义区别（条件/顺序/边端点核对） | F137-06 同工程，流程含普通边、流式边、条件分支、多来源声明 | `GET /project/model` 后遍历 `nodes` 与 `edges` | 边对象含 `kind` 字段且取值区分普通与流式；条件边含 `order`（数值）且存在默认分支标记；多来源边共享相同非空 `sourceGroupId`；分支条件表达式文本（`amount > 1000`）在载荷中保留；每条边端点可解析到 `nodes` 中既有节点 ID（分支条件、顺序与边端点一致性，审阅整改项）（L2 §3.3/§5.3：条件顺序、默认分支与多来源分组保留） | feat137-workflow 工程 |
| F137-08 | 非规范语句部分解析 | 会话工程 Workflow 流程体内含无法静态确定的语句 | `GET /project/model` | `200`；`shape.type=="workflow"`；已确认结构仍在（图对象存在）；分析状态非 `complete`（`partial`）；诊断或未解析项含 `WF_NONCANONICAL_FORM`（L2 §3.3：词法失败以文件诊断保留；§5.3 状态枚举） | feat137-wf-partial 工程 |
| F137-09 | 混合证据按 Workflow 展示 | 会话工程同时含 Workflow 证据与 ReAct/DeepAgent 特征源码 | `GET /project/model` | `200`；`shape.type=="workflow"`（L2 §3.2：并存时按 Workflow 展示）；`shape.evidence` 数组非空；诊断中出现 `SHAPE_AMBIGUOUS` | feat137-mixed 工程 |
| F137-10 | 空工程不确定识别 | 会话工程目录为空（无任何源码/配置） | `GET /project/model` | `200`；`shape.confidence=="uncertain"`；诊断含 `SHAPE_UNDETERMINED`；`shape.type` 为兜底值（L2 §3.2 次序 6：带诊断兜底，不标确定识别） | feat137-empty 工程 |
| F137-11 | `/ui` 与 `/model` 同形态一致 | 后端可达；会话已创建 | 先 `GET /project/model`，再 `GET /project/ui` | 两者均 `200` 且 `shape.type` 相同、`shape.renderer` 相同（L2 §2.2：两入口由同一模型构建器产生业务字段） | F137-02 会话 |
| F137-12 | 目录分页契约与 limit 边界 | 会话工程目录含 ≥3 个文件 | ①`GET /files?limit=1` 翻页至尽 ②`GET /files?limit=501` ③`GET /files?limit=0` | ①`200`，`entries` 为数组（长度 1），条目字段按 wire 契约（`name`/`path`/`type`/`size`，审阅执行轮核实），`hasMore`/`nextCursor`/`directoryFingerprint`/`excludedNames` 字段存在，翻页拼接覆盖全部文件且无重复；②③`400`，错误码 `INVALID_LIMIT`（L2 §2.3：合法范围 1 至 500） | feat137-files 工程 |
| F137-13 | 拼造游标拒绝 | F137-12 已取得合法游标样式 | `GET /files?cursor=<拼造字符串>` | `400`，错误码 `INVALID_CURSOR`（L2 §2.3：客户端不解析、不拼造，服务端签名游标） | 同上 |
| F137-14 | 分页间目录变化 | F137-12 ①已取得第一页游标 | 以旧游标读下一页前，向目录新增 1 个文件；再以旧游标请求下一页 | `409`，错误码 `DIRECTORY_CHANGED`（L2 §2.3：目录变化导致旧游标失效） | 同上 |
| F137-15 | 文件快照正例与指纹一致 | 会话工程含已知内容文本文件 README.md | 测试侧先计算该文件原始字节 SHA-256；`GET /file?path=README.md&view=workbench` | `200`；`text` 与写入内容一致；`encoding` 等值比较 UTF-8（wire 实际返回小写 `utf-8`，L2 §2.3：脱敏后的 UTF-8 正文及编码）；`size`==写入字节数；`binary==false`、`editable==false`、`truncated==false`；`fingerprint` 剥离 `sha256:` 前缀后==测试侧 SHA-256 值（L2 §2.3 出参表：fingerprint 为原始文件字节的 SHA-256 指纹） | feat137-files 工程 |
| F137-16 | 路径错误与访问越界各自结果（审阅整改项；按 L2 §2.3 场景表与实现语义核对） | 会话工程可读 | ①语法非法路径：含反斜杠路径、Windows 绝对路径（`C:\...`）、URI scheme、`\0` → `GET /file?path=<变体>&view=workbench` ②相对越界：`GET /file?path=../outside.txt&view=workbench` | ①各变体 `400` 且错误码 `INVALID_PATH`（L2 §2.3：路径不合法 400）；②`403`（L2 §2.3：访问越界或不允许路径 403，实现错误码 `PATH_OUTSIDE_PROJECT`）；两类结果各自核对且均不含目标文件内容 | feat137-files 工程 |
| F137-17 | 文件不存在 | 会话工程可读 | `GET /file?path=feat137-no-such-file.txt&view=workbench` | `404`，错误码 `FILE_NOT_FOUND`（L2 §2.3 场景表） | 同上 |
| F137-18 | 超大文件拒绝 | 会话工程含大于 P1_WORKBENCH_MAX_BYTES(524288) 字节的文本文件 | `GET /file?path=<big>.txt&view=workbench` | `413`，错误码 `FILE_TOO_LARGE`（L2 §2.3：不返回截断文本冒充完整源码；§6.2 单文件上限 524288） | feat137-big 工程 |
| F137-19 | 二进制文件拒绝 | 会话工程含非 UTF-8 字节文件 | `GET /file?path=data.bin&view=workbench` | `415`，错误码 `UNSUPPORTED_TEXT`（L2 §2.3：二进制或无效 UTF-8 拒绝文本展示） | feat137-files 工程 |
| F137-20 | 只读视图写入拒绝 | 会话工程含已知内容文件；已读取其原始字节 | `PUT /file?path=README.md&view=workbench` body 文本；随后重新 `GET` 同文件 | `405`，错误码 `READ_ONLY_VIEW`；再次读取 `200` 且 `fingerprint` 与写入前一致（文件未被修改）（特性档 §3、L2 §2.1/§2.3） | feat137-files 工程 |
| F137-21 | 凭据脱敏与指纹分离 | 会话工程含含明文口令行（password: s3cret-<run>）的 YAML | `GET /file?path=secret.yaml&view=workbench`；测试侧计算原文件字节 SHA-256 | `200`；`redacted==true`；`maskedLineRanges` 非空数组；`text` 不含原口令值；`fingerprint`==原始字节 SHA-256（L2 §2.3：脱敏不改变来源指纹依据；§5.4） | feat137-secret 工程 |
| F137-22 | 修改后刷新 manifestHash 变化且实际结构更新 | F137-02 会话已取得投影 | 记录当前 `manifestHash` 与当前载荷；向 `moa-candidates/master-agent.yaml` 追加新候选 `feat137-moa-worker-c`；`GET /project/model` | `200`；新 `fingerprints.manifestHash` != 记录值（L2 §5.5：新增输入影响摘要；特性档 §5.1.5：以新分析结果判断结构变化）；刷新后的实际结构核对：新候选名 `feat137-moa-worker-c` 出现在载荷中（审阅整改项：不只看指纹变化，须核对刷新后实际结构） | F137-02 会话 |
| F137-23 | 并发 `/ui`+`/model` 共享在途分析 | 后端可达；会话已创建且工程可分析 | 并发发起 `GET /project/ui` 与 `GET /project/model`（CountDownLatch 同时放行） | 两者均 `200`；各自 `sessionId` 均等于会话 ID；`shape.type` 相同（L2 §7.3：共享一次在途构建，各自响应会话正确）。白盒联动证据：实现仓单测 `ProjectAnalysisTest.concurrentRequestsForTheSameRootShareOneAnalysis` 已断言同一 root 共享一次构建（构建次数 =1），审阅确认代码已实现（审阅整改项 G2） | F137-02 会话 |
| F137-24 | Workflow 注册未解析（unresolved） | 会话工程含 WorkflowAgent 构造但注册来源无法静态确认（流程由运行期装配，未出现可确认的注册语句） | `GET /project/model` | `200`；`shape.type=="workflow"`（保留疑似 Workflow 证据）；存在流程对象 `registration.status=="unresolved"`，或诊断含 `WF_REGISTRATION_UNRESOLVED`；不伪装 `confirmed`（L2 §3.3/§5.3 注册三态；审阅整改项） | feat137-wf-unresolved 工程 |
| F137-25 | Workflow 未注册（unregistered） | 会话工程仅含 WorkflowFlow 定义、无任何 Agent 引用或注册语句 | `GET /project/model` | `200`；存在流程对象 `registration.status=="unregistered"`，或诊断标明未注册；不出现 `confirmed`（L2 §3.3：未注册不进入 confirmed；审阅整改项） | feat137-wf-unregistered 工程 |
| F137-26 | Workflow 整体分析不可用 | 会话工程 Workflow 定义文件整体语法失败（词法级破损） | `GET /project/model` | `200`；不伪装完整识别：`shape.confidence != "certain"` 或诊断含 `WF_NONCANONICAL_FORM`/`SHAPE_UNDETERMINED` 或流程分析状态为不可用（L2 §5.3 状态枚举；特性档 §2.2：不能静默伪装为已完整识别；审阅整改项） | feat137-wf-broken 工程 |
| F137-27 | 跨目录游标拒绝 | F137-12 已取得 files 会话第一页游标；另有第二会话（不同目录，如 secret 会话） | 以 files 会话游标请求 secret 会话 `/files?cursor=<A会话游标>` | `400` `INVALID_CURSOR` 或 `409` `DIRECTORY_CHANGED`；响应不含目标目录条目（`entries` 不出现或为空）（L2 §2.3：游标由服务端签名且绑定目录；审阅整改项） | F137-12 + secret 会话 |
| F137-28 | 访问越界（符号链接）拒绝 | 会话工程内存在符号链接指向工程外文件（宿主无符号链接权限时经 VM/Linux 侧 `ln -s` 创建，`sut.workbench.wsl-distro` 注入发行版） | `GET /file?path=link-out.txt&view=workbench` | `403`（L2 §2.3 场景表：访问越界 403，实现错误码 `FILE_ACCESS_DENIED`：路径包含符号链接；审阅整改项：与 F137-16 的 400 各自核对）；响应不含工程外文件内容 | feat137-files 工程 + 工程外文件 + 符号链接 |
| F137-29 | 私钥文件拒绝且无正文 | 会话工程含 `id_rsa`（PEM 私钥文本，非凭据遮蔽语义） | `GET /file?path=id_rsa&view=workbench` | `403`（L2 §2.3 场景表：私密文件 403 文件访问错误；L2 §7.1：私密文件不可读取；实现 `rejectPrivateFile` 私钥名单含 id_rsa）；响应不含私钥材料（`BEGIN`/密钥体不出现在响应正文，审阅整改项：拒绝且无正文） | feat137-secret 工程 |
| F137-30 | 源文件变化后旧位置失效（来源定位前置事实） | F137-15 已读取 `src/Main.java` 原文；`/project/model` 已取 `manifestHash` | 修改 `src/Main.java`（分析器输入范围内的文件，L2 §5.5 输入清单）；重读同文件；再取 `/project/model` | 重读正文 != 原文（旧行位置内容已失效，特性档 §5.1.4 来源失效提示的前置事实成立）；`manifestHash` 变化（审阅整改项 G1；manifestHash 覆盖分析器输入：Java 源码/pom/配置目录，根下任意文件不在清单内） | feat137-files 工程 |
| F137-31 | 其他用户真实会话不可见（自含多用户流程） | 后端本地账号体系（admin 登录） | ①管理员 `POST /api/admin/users` 创建第二用户 ②以第二用户登录并创建工作区+真实会话（opencode 引擎拉起） ③以本用户上下文 `GET` 该会话 `/project/ui`、`/file` ④清理第二用户 | 访问其他用户真实会话均 `404`，错误码 `SESSION_NOT_FOUND`，不泄漏目标会话工程内容（L2 §2.1/§2.2 会话可见性；审阅整改项） | 同一后端，双用户 |
| F137-32 | Agent Host 形态文件与投影读取（自含流程） | remote 隔离拓扑可达：agent-host（agenthost/index.mjs，`AGENT_HOST_PORT=7310`）+ remote 后端（`P1_ISOLATION=remote`、`P1_DEPLOYMENT_ID`、`P1_REMOTE_AGENT_HOST/P1_REMOTE_AGENT_TOKEN_FILE`、`P1_VOLUME_ROOT` 与 agent 卷同根），入口经 `sut.workbench.agent-host.base-url` 注入 | ①登录 remote 后端 ②登记工作区（经 agent `files/mkdir` 建目录）③fixture 写入 Agent Host 卷（宿主路径映射）④创建会话（agent 拉起引擎）⑤`GET /files`、`GET /file`、`GET /project/ui` | 读取均 `200`；`entries` 非空；文件正文与 fixture 一致；`sessionId` 归属正确且 `shape.type=="moa"`（L2 §1.3/§7.2 Agent Host 数据面；审阅整改项） | remote 后端 + agent-host（VM 部署） |

### 5.1 显式 deferred / 白盒联动项

- F137 预留 ID `F137-33` 起（审阅整改后顺延，F137-24～F137-32 已被矩阵占用）：分析队列满（`PROJECT_ANALYSIS_BUSY`）、分析超时旧 Workflow 图（`WF_ANALYSIS_LIMIT`）、无缓存超时（`PROJECT_ANALYSIS_TIMEOUT`）、分析中断（`PROJECT_ANALYSIS_INTERRUPTED`）、预算限额与 `WF_INPUT_CHANGED` — 均需进程内故障注入或受控并发压满，待注入手段交付后补充矩阵。
- 远端 Agent Host 形态的镜像限额细节（`SESSION_NOT_CONNECTED`、镜像路径映射、远端读取限额 512 KiB/1500 文件）— 待远端部署拓扑可用后补充（L2 §7.2 远端工程旅程）；默认独立部署形态的基础读取链路已入矩阵 F137-32。
- 白盒联动项：F137-23 的共享在途构建与实现仓单测 `ProjectAnalysisTest.concurrentRequestsForTheSameRootShareOneAnalysis`（构建次数断言）构成互补证据 — 单测验证"只构建一次"，本套件验证"HTTP 响应与会话归属正确"（审阅整改项 G2）。

### 5.2 页面验收清单（审阅整改项：页面 MUST 绑定浏览器证据，按用例 ID 记录）

以下场景 wire 上无独立端点可观察，属控制台页面行为；按审阅结论须指定负责方案并以浏览器证据（截图/录屏/Trace）逐项记录，不得以"另行记录"为由豁免：

| ID | 页面场景 | 步骤概要 | 期望结果（浏览器证据绑定点） |
|---|---|---|---|
| F137-P01 | 来源定位高亮 | 画布选中节点 → 打开源码面板 | 源码面板定位到 SourceRef 指示的文件与行并高亮；截图含节点 ID 与高亮行 |
| F137-P02 | 选中对象交接开发对话 | 画布选中对象 → 发起开发对话 | 对话输入上下文包含选中对象信息；录屏含交接前后画面 |
| F137-P03 | 双流程切换且无业务运行调用 | 工程含两个流程，切换 `workflowKey` 浏览 | 画布切换为目标流程图；期间无业务运行调用（运行日志/网络面板无运行请求）；录屏 |
| F137-P04 | 源文件变化后旧位置失效提示 | 按 F137-30 修改源文件后回到旧画布/来源面板 | 出现"文件已变化，请重新分析"类提示，旧位置不再静默展示为有效；截图 |
| F137-P05 | 两条投影接口都失败时旧图标记过期 | 断开后端使 `/ui` 与 `/model` 均失败后刷新页面 | 旧图保留但标记过期/不可用状态，不伪装为最新结构；截图 |
| F137-P06 | 不兼容投影阻断 | 打开包含不兼容版本/renderer 的工程投影 | 控制台给出阻断/降级提示，不渲染错误结构；截图 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| agent-dev-workbench 后端（HTTP wire） | 真实 SUT | 正式制品；本机默认 `http://127.0.0.1:7310`，经 `sut.workbench.base-url` 配置；Agent Host 形态入口经 `sut.workbench.agent-host.base-url` 配置（F137-32） |
| `feat137-<variant>-<run>` fixture 工程目录 | Fixture | 每测试类独立临时目录（系统 `java.io.tmpdir`），进程内写入 MOA 候选 YAML / ReAct / DeepAgent（含工具类与输入参数）/ Workflow（含规范、非规范、unresolved、unregistered、整体破损变体）/ 凭据 / 二进制 / 超大 / 私钥文件等变体源码；工程文件为业务定义唯一来源 |
| 工作区登记（`POST /api/workspaces {path}`）+ 开发会话 | Fixture（共享前置） | 将 fixture 目录登记为工作区后创建开发会话；由本套件共享前置自行完成，不依赖 FEAT-136 套件顺序；会话创建依赖 OpenCode 引擎可连，未就绪时整类 `assumeTrue` SKIPPED |
| 符号链接/联接（link-out.txt） | Fixture | F137-28 进程内创建指向工程外文件的符号链接；环境不支持时该用例 SKIPPED 单列 |
| 多用户隔离部署 / 外部用户会话 | 自含流程 | F137-31 由用例自行经管理员 API（`POST /api/admin/users`）创建第二用户并以该用户建立真实会话，无需外部注入；用例结束清理账号 |
| OpenCode 引擎 / 开发模型服务 | 真实依赖 | 用户提供；仅作为会话创建的依赖存在，本测试不提交开发回合 |
| SHA-256 指纹计算器 | Fixture | 测试进程内对 fixture 文件原始字节计算，用于 F137-15/F137-21/F137-30 的 `fingerprint` 逐字节比对 |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

1. **F137-01**：会话归属边界成立 — 未知会话在全部四个入口均 404 且不泄漏内容（安全前置）。
2. **F137-02 + F137-11**：投影外壳契约完整（`schemaVersion=="2.3"`、`shape.*` 枚举合法、`fingerprints.manifestHash` 非空）且 `/ui` 与 `/model` 同源一致。
3. **F137-03 + F137-06**：形态识别两大确定路径 — MOA 配置投影为 `agent-tree`/`certain` 且载荷核对 EDP 主控与候选实际结构；Workflow 注册齐备投影为 `node-graph` 且注册 `confirmed`（审阅整改项：核对实际结构和来源）。
4. **F137-10**：空工程不伪装 — `uncertain` + `SHAPE_UNDETERMINED`，不用完整识别掩盖（特性档 §2.2：不能静默伪装为已完整识别）。
5. **F137-15 + F137-21 + F137-29**：文件指纹 = 原始字节 SHA-256；脱敏正文与指纹依据分离；私钥文件拒绝且无正文（审阅整改项）。
6. **F137-20 + F137-16/F137-28**：只读画布语义 — 写请求 `405 READ_ONLY_VIEW` 且文件字节不变；路径语法错误与访问越界各自结果正确且无内容泄漏（审阅整改项）。
7. **F137-22 + F137-30**：工程变化反映到新分析 — `manifestHash` 变化且刷新后实际结构更新；源文件变化后旧位置失效的前置事实成立（审阅整改项 G1）。
8. **F137-24～F137-26**：Workflow 注册三态与整体分析不可用均如实呈现，不伪装 `confirmed`/完整识别（审阅整改项）。

失败语义必须与基线同构：错误码断言与 L2 §2.2/§2.3 逐字一致；负向场景同时命中"目标错误码 + 无越权内容泄漏"（F137-01/16/27/28/31）或"目标错误码 + 文件未被修改"（F137-20）或"拒绝 + 无正文泄漏"（F137-29）。

## 8. 执行策略

- **Smoke 集合**：F137-01、F137-02、F137-11、F137-15、F137-17、F137-20、F137-29（仅需后端 + 任意可分析会话，无重 fixture 依赖）。
- **Full suite**：F137-01～F137-32 全量（含 §5.2 页面验收清单 P01～P06 的浏览器证据记录）。
- **P0 必须全绿**：F137-01、F137-02、F137-03、F137-06、F137-10、F137-15、F137-16、F137-20、F137-21、F137-22、F137-24、F137-25、F137-26、F137-29（审阅整改项：结构/来源核对、注册三态、路径各自结果、私钥无正文入 P0）。
- **环境门禁（第三轮后不再存在跳过项）**：F137-28/31/32 已按"依赖与部署问题解决"要求全部实机执行——F137-28 经 VM/Linux 侧创建符号链接（`sut.workbench.wsl-distro` 配置发行版）；F137-31 自含多用户流程（管理员建用户 API）；F137-32 的 Agent Host 拓扑已部署（agent-host Node 服务 :7310 + remote 后端实例 :4502）。若部署不可用时按 SKIPPED 单列原因，不删项、不计 PASS。
- **共享前置（审阅优先项 3）**：工作区登记与会话创建为本套件共享前置，消除与 #599（FEAT-136）的套件级先后依赖；证据按实际用例 ID 绑定，不引用其他套件的遗留数据。
- **依赖门禁**：全部场景需 workbench 后端可达；F137-03～F137-09/18/19/21/24～F137-26 需对应 fixture 工程登记 + 会话创建成功（依赖 OpenCode 引擎可连）；未满足时按 `assumeTrue` SKIPPED 并在执行报告中单列阻塞项，不计 PASS。

```bash
# 从源码重新打包 SUT（feature_source 应指向工作台实现仓）
export SUT_SOURCE_DIR=<agent-dev-workbench 实现仓路径>
./scripts/sut/provision-sut.sh --env openjiuwen

# 按特性 Tag 执行
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-137 -Dmaven.test.failure.ignore=true
```

- **执行结论（2026-11-24 执行轮次，替代原 BLOCKED-ARTIFACT-MISSING 声明）**：
  1. `feature_source`（`D:\code\test\agent-solution`，git `common@9cbbf6492`）已包含工作台后端实现；已从源码重建 `platform-agent-backend.jar` 并以 local 隔离形态本机执行（`P1_PORT=4500`、`P1_AUTH_MODE=local`、`P1_ISOLATION=local`、`P1_MAX_SESSIONS=64`、`P1_ENGINE_CMD=opencode.cmd`）。套件前置整改：登录接入（后端 local 模式强制鉴权）、命名工作区登记（fixture 内容拷入用户根下工作区）、fixture 改写为实现仓规范写法 v1（MOA 候选目录/application.yml、ToolCard.builder()、Workflow 规范 DSL）、错误码断言适配 `error.code` 嵌套。
  2. **执行结果（2026-11-24 第三轮：三个跳过项的依赖与部署问题解决后）**：32 例 —— **31 PASS / 1 FAIL（F137-10，产品缺陷，唯一）/ 0 SKIP**。第三轮新增转绿的用例：F137-28（符号链接经 VM/Linux 侧 `ln -s` 创建，消除宿主权限问题）、F137-31（自含多用户流程：管理员 `POST /api/admin/users` 建第二用户、独立登录建真实会话、跨用户访问 404 SESSION_NOT_FOUND）、F137-32（Agent Host 形态自含部署与执行：VM 内 agent-host（Node 22 + ws/yaml 依赖）+ remote 后端（4502 端口，`P1_ISOLATION=remote`）+ 工作区卷映射，files/file/project 全链路 200 且 moa 识别正确）。此前第二轮的 28 PASS 明细见 git 历史；F137-10 为唯一产品失败，见第 3 条。
  2a. **Linux VM 复跑（第一轮 Windows 宿主的 13 例 BLOCKED-WINDOWS-FS 已全部转 PASS）**：后端运行于 WSL2 podman-machine VM（Fedora 44，JDK 21 Temurin，`P1_WS_ROOT=/mnt/d/runtime-vm137/workspaces`），工作区文件系统支持 `SecureDirectoryStream`，文件端点契约（分页/游标/指纹/脱敏/私钥/路径语义/只读）全部实测通过；测试套件仍在 Windows 宿主执行，经 `SUT_WORKBENCH_BASE-URL` 指向 VM、`SUT_WORKBENCH_HOST-PATH-MAP=/mnt/d/=D:/` 做工作区路径桥接（fixture 经宿主写入、VM 后端读取同一物理文件）；opencode 引擎经 WSL interop 使用真实引擎。Windows 本机形态的 `403 FILE_ACCESS_DENIED` 确证为平台限制（非产品缺陷），与本设计文档 §4/§8 的环境门禁处理一致。
  3. **已观测产品失败（1 项，非环境问题）**：F137-10 空工程兜底识别返回 `react/uncertain` 但 `diagnostics` 为空，缺 L2 §3.2 次序 6 要求的 `SHAPE_UNDETERMINED`（`/ui` 与 `/model` 双通道一致缺失；Windows 宿主与 Linux VM 两轮执行均复现）。缺陷定位：`ProjectModel.dependencyDecision`（agent-dev/java，基线 9cbbf6492）无证据分支未追加诊断码。issue 文件：`D:\code\test\issues\draft-feat137-shape-undetermined-missing-on-no-evidence.md`。修复后该用例应转绿，不放宽用例断言。
  4. **执行轮用例侧修正记录**（依 wire 实测核对实现契约，均为测试问题整改）：条目字段 `path`/`name`（原误用 `relativePath`）；`encoding` 实际返回小写 `utf-8`（改等值忽略大小写）；`fingerprint` 实际带 `sha256:` 前缀（断言剥离前缀比对）；`../` 属语义越界 403（`PATH_OUTSIDE_PROJECT`），语法非法（反斜杠/绝对路径/URI scheme/NUL）才 400 `INVALID_PATH`；`manifestHash` 输入清单为分析器输入（Java 源码 `src/main/java`、pom、配置目录），F137-30 改为修改 `src/main/java/demo/Main.java`；错误响应码位于 `error.code` 嵌套；活跃会话上限由 `P1_MAX_SESSIONS_PER_POD`（默认 10）独立控制，VM 后端已设 64。
  5. 按审阅优先项 2：环境阻塞（F137-28/31/32 门禁）与产品失败（F137-10）分别记录；页面验收（§5.2 P01～P06）需默认独立部署形态与浏览器，本轮未执行，单列待补。
