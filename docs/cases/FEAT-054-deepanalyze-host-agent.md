---
feature_id: FEAT-054
feature_title: deepanalyze-java 宿主装配
scope: v930；权威特性档 Technical-AF/docs `develop/02-features/FEAT-054-deepanalyze-host-agent.md` @ `de5e8baa`
deployable_units: [deepanalyze-java]
sut: deepanalyze-java 宿主 Agent（managed；真实制品 com.openjiuwen:deepanalyze-engine:0.1.4，classifier=exec）
status: executed-real-artifacts
features: [FEAT-054]
updated: 2026-09-27
---

# FEAT-054 验收：deepanalyze-java 宿主装配

> 本档是 `docs/cases/` 的逐场景用例设计（方案级）。场景 ID 与报告 feature 树、测试类的
> `@Feature("FEAT-054: deepanalyze-java 宿主装配")` 一一对应。
> 逐用例判定与执行证据不在本档维护：细档见工作区 `FEAT-054-测试设计初稿.md`，状态与缺口见
> `FEAT-054-覆盖对账与状态页.md`，唯一覆盖台账见 `_evidence/FEAT-054-review-manifest.json`。

## 1. 测试目标

以真实部署的 deepanalyze-java 宿主为 SUT，验证它作为 DA 场景智能体宿主的**外部可观察行为**：装配后的提示词资产是否真的生效、DA 形态兼容端点是否可用且与 DA 既有前端消费契约一致、SSE 事件面与断线重连是否无缺口、控制面与错误表面是否符合契约、服务间认证与共享数据面读取是否按设计工作，以及宿主对既有平台面（标准 A2A 入口、Skill Hub 中间件）的接线是否不破坏既有行为。

SIT 侧是裸协议调用方：经标准 A2A 入口（JSON-RPC）与 DA 形态 HTTP/SSE 端点驱动 SUT；不 import 被测类、不反射读运行时常量、不 Mock LLM、不直连被测数据库做断言。

## 2. 范围与非范围

范围：

- 部署与就绪：独立部署启动、探活端点与能力名片可达，以及名片声明真实性。
- 任务主流程与装配线：经标准入口发起任务并收束到终态；压缩/系统提示词两条装配线的 opt-in 语义与关闭态行为一致。
- DA 兼容适配层：流式事件信封与线格式、15 秒心跳、完成关流、断线重连的 `Last-Event-ID` 续传与缓冲重放、事件翻译映射、缓冲容量逐出与受保护事件集、DA 运行参数矩阵。
- 控制面端点：取消、中断回答、注入消息、同步执行聚合、状态查询、会话任务列表、provider 连通性探测、技能读面。
- 安全面：服务间认证过滤器（无密钥/错密钥/合法密钥、探活端点豁免）；重放与控制面的会话属主绑定。
- 数据面：共享 PostgreSQL 只读读取（会话记忆、设置、provider、技能）与"不写 messages/sessions"；媒体元数据读面与路径穿越防御。
- 运行时接线与既有面回归：Redis 检查点跨进程恢复、轨迹可观测两态、复杂度估算事件、文件输入白名单、全仓聚合构建、Skill Hub 启动期链路、`compat` 关闭态下的标准 A2A 流出面。

非范围：

- DA 工具集本体（62 个工具的语义与正确性）：归工具团队，本特性只承诺装配面。
- 平台内核的模型调用超时分层与主循环弹性轮次预算本体：内核未交付（agent-core-java #171/#172），本档只验证配置接线存在。
- DA 前端与产品形态迁移：前端零改动，仅作为兼容端点契约来源。
- 逐字节提示词比对与模板漂移"检出结论"：需反射读运行时常量或依赖工程内 CI，归开发侧白盒，本档只做制品侧可回溯性核对。

## 3. 事实来源

| 来源 | 路径 | 版本 |
|---|---|---|
| 权威特性档 | `develop/02-features/FEAT-054-deepanalyze-host-agent.md` | docs `de5e8baa` |
| 权威 L2 | `develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-054-deepanalyze-host-agent.md` | docs `de5e8baa` |
| 测试设计细档 | `01-Design_File/20260921/FEAT-054/FEAT-054-测试设计初稿.md` | 2026-09-23 |
| 覆盖台账（唯一数据源） | `01-Design_File/20260921/FEAT-054/_evidence/FEAT-054-review-manifest.json` | 121 个条目行 / 38 个场景 |
| 正式 TestPlan | `01-Design_File/20260921/FEAT-054/FEAT-054-deepanalyze-host-agent-testplan.md` | 2026-09-26 |

引用版本核对（T-M1a）：本档所引特性档与 L2 均取 `de5e8baa` 版本，两者 frontmatter 的 `status` 仍为 `draft`
（特性档 `updated: 2026-09-18`，L2 `updated: 2026-09-16`）。本档按该版本裁剪范围与 Oracle，未使用更新版本的契约；
若设计后续刷新，受影响场景的判据需按增量审视重新对齐。

## 4. 部署拓扑

- **SUT**：单实例 deepanalyze-java 宿主（`com.openjiuwen:deepanalyze-engine:0.1.4`，`exec` classifier），由 `SutStack` 以 managed 方式拉起；模型连接属性经环境变量注入（见 §8）。
- **调用方**：SIT driver 同时充当两类调用方——DA 形态调用方（`/agent/run-stream` 等兼容端点）与标准 A2A 调用方（JSON-RPC），两者共用同一 Host 的同一 Handler。
- **依赖服务**：PostgreSQL（栈自管容器，等价 schema 由 DA 侧权威 DDL 构造）；Redis（**远端预启动**：`application-openjiuwen.yml` 的 `services.redis` 带 `url: localhost:6379`，栈不再自管容器）；共享媒体卷（测试侧等价目录）；遥测后端（时效性条件，执行前预检）。

## 5. 测试场景矩阵

场景 ID 与覆盖台账一一对应；"主断言"列是该场景的 Oracle 摘要，完整前置/步骤/期望见细档与台账。"自动化落点"为当前测试仓的类#方法，报告 feature 树据此回溯。

| ID | 场景 | 主断言（Oracle） | 自动化落点 | 状态 |
|---|---|---|---|---|
| da.deploy.startup | 独立部署启动与就绪 | 服务进入可用态；探活与能力名片均 200 | `DaHostStartupE2EIT#serviceStartsAndReportsReady` | 已执行 |
| da.card.truthfulness | 能力名片真实性 | 声明字段结构合规；声明的每项能力可调用 | `DaCardTruthfulnessE2EIT#cardFieldsAreStructurallyTruthful`、`#declaredSkillsAreCallable` | 已执行 |
| da.run.minimal-task | 最小任务闭环（零工具路径） | 经标准入口发起任务并收束 completed，终态与状态查询一致 | `DaMinimalTaskE2EIT#minimalTaskReachesCompletedWithoutTools` | 已执行 |
| da.asset.version | 制品内资产版本可回溯 | clauses/templates/subagents/segments 四类 meta 齐备且带版本字段 | `DaAssetVersionE2EIT#assetMetadataIsTraceableInArtifact` | 已执行（静态核对） |
| da.otel.twostate | 轨迹可观测两态 | 关闭态外部行为不变；开启后轨迹可见且服务名一致 | `DaTrajectoryEnabledE2EIT#trajectoryIsPersistedWhenEnabled`、`DaCardTruthfulnessE2EIT#otelOffKeepsExternalBehaviourUnchanged` | 已执行 |
| da.redis.recovery | Redis 检查点恢复 | 任务执行中中断并重开服务后，任务/检查点状态仍在 | `DaRedisRecoveryE2EIT#taskStateSurvivesProcessRestart` | 已执行 |
| da.compat.stream-shape | DA 流式事件信封 | 每帧 `id/event/data` 齐备且字段名后带空格；15 秒心跳；done 后关流 | `DaCompatStreamE2EIT#streamFrameShapeAndHeartbeat` | 已执行 |
| da.compat.reconnect | 断线重连无缺口 | 断开期间事件重放补齐、seq 无缺口；完成后补 `reconnect_done` 并关流 | `DaCompatReconnectE2EIT#reconnectReplaysWithoutGap` | 已执行 |
| da.compat.translator-map | 事件翻译映射逐行 | 段落状态机/轮次组织/工具调用配对逐行一致；无源事件不出现、不伪造 | `DaCompatTranslatorMapE2EIT#translatorMapFollowsSegmentStateMachine` | 已执行（错误/中断分支归开发侧，见 §5.1） |
| da.compat.buffer-evict | 事件缓冲逐出策略 | 增量类事件先逐出；受保护事件集不被逐出 | `DaCompatBufferEvictE2EIT#evictsIncrementalEventsButKeepsProtectedOnes` | 已执行（按裁决口径以小容量构造） |
| da.compat.cancel | 协作式取消 | 运行中受理并出现取消+终态组；不存在/已终态 404 同源文案 | `DaCompatControlSuccessE2EIT#cancelRunningTaskSettlesCancelled` | 已执行 |
| da.compat.inject | 注入消息 | 运行中受理并生效；已终态 404、缺参 400 | `DaCompatControlSuccessE2EIT#injectRunningTaskAccepted` | 已执行 |
| da.compat.run-sync | 同步执行聚合 | 成功聚合输出与用量；失败走错误面；转待答返回 200 与待答状态 | `DaCompatControlSuccessE2EIT#runSyncAggregatesCompletedResult` | 已执行 |
| da.compat.status-tasks | 状态查询与会话任务列表 | 投影字段正确、列表按创建时刻升序、未命中 404 | `DaCompatControlSuccessE2EIT#statusTasksAndOwnerBinding` | 已执行 |
| da.compat.providers-test | provider 连通性探测 | 未命中 404；不可达返回失败标志而非异常；未配置 503 | `DaSharedPgReadE2EIT#providerTestReadsProvidersRow` | 已执行 |
| da.compat.skills-read | 技能读面三接口 | 命中返回数据；未命中 404；未配置 503 | `DaSharedPgReadE2EIT#skillsReadPlaneReadsSharedPg` | 已执行 |
| da.compat.errors | 错误表面矩阵 | 400/404/503 与 `{error}` 同源文案逐条匹配 | `DaCompatControlContractE2EIT#statusUnknownTaskReturnsNotFound`、`#runStreamWithoutSessionIdReturnsBadRequest`、`#skillsListWithoutDataPlaneReturnsServiceUnavailable`、`DaFaultInjectionE2EIT#unreachableModelFailsSyncRunWithDiagnosableError`、`#unreachableModelEmitsErrorTerminalGroupOnStream` | 已执行 |
| da.compat.route-alias | DA 原生路由等价 | DA 原生路径与设计定名路径同一处理逻辑、零行为分叉 | `DaCompatRouteAliasE2EIT#daNativeRoutesAreEquivalentToDesignNamedRoutes` | 已执行 |
| da.compat.owner-binding | 会话属主绑定 | 属主失配按端点同源 404 且不泄露存在性；任务列表对异属主返回空数组 | `DaCompatControlSuccessE2EIT#statusTasksAndOwnerBinding` | 已执行 |
| da.compat.concurrent-same-session | 同会话并发 | 已受理的流必须收束终态（被拒必须明确失败），不得悬挂 | `DaConcurrentSiblingTerminalE2EIT#acceptedSiblingStreamsMustReachTerminalState`、`DaConcurrentStreamDiagnosisE2EIT#sameSessionConcurrentStreamsMustNotReturnServerError` | 已执行（产品口径待设计裁决） |
| da.auth.filter | 服务间认证 | 无/错密钥 401 同体；合法密钥放行；探活端点豁免 | `DaServiceAuthE2EIT#requestWithoutSecretIsRejected`、`#requestWithWrongSecretIsRejectedIdentically`、`#requestWithValidSecretPassesFilter`、`#healthEndpointIsNotGuarded` | 已执行 |
| da.data.pg-read | 共享数据面只读读取 | 读取内容与预置数据一致；messages/sessions 零写入 | `DaSharedPgReadE2EIT#hostNeverWritesSharedPg`、`#skillsReadPlaneReadsSharedPg` | 已执行（等价 schema，Oracle `partial`） |
| da.data.media-taverse | 媒体读面与穿越防御 | 合法命中；越界视为不存在且不泄露路径 | `DaDataMediaTaverseE2EIT#existingMediaIsReadAndTaskCompletes` | 已执行 |
| da.estimate.event | 复杂度估算事件 | 档位与建议轮次可见；同一输入两次估算一致 | `DaMinimalTaskE2EIT#complexityEstimateIsObservableInSutLog` | 已执行 |
| da.file.whitelist | 文件输入白名单 | 白名单外类型/路径被拦截并返回业务错误、不进入执行；白名单内对照放行 | `DaFileWhitelistStandardChannelE2EIT#nonWhitelistedFileBytesPartIsRejectedWithBusinessCode`、`#nonWhitelistedFileUriPartIsRejectedWithBusinessCode`、`#whitelistedFilePartIsAcceptedAndReachesExecution`、`DaDataMediaTaverseE2EIT#nonWhitelistedFileTypeIsRejectedWithBusinessCode` | 已执行 |

### 5.1 显式未承接项（不在本轮 PASS 口径内）

| ID | 未承接原因 | 责任方 / 恢复条件 |
|---|---|---|
| da.deploy.reproducible | 部署脚本与运维指南在当前快照缺件 | 开发/L2 补齐后按手册从零复现（GAP-054-03） |
| da.compact.presence | 压缩事件在 core 侧无源，且条款在场性归白盒 | core 提供压缩事件源或设计给出黑盒判据（GAP-054-19） |
| da.prompt.offbyte | 逐字节比对需反射读运行时常量 | 开发侧白盒承接 |
| da.timeout.params | 内核超时分层与弹性轮次预算未交付 | core 特性合入并完成接线（GAP-054-02） |
| da.compat.answer | 成功路径需可控反问（ask_user）触发 | 开发侧脚本化事件源承接（GAP-054-18 / A-Q8） |
| da.compat.tools-list | 工具列表端点以工具团队接口壳为前置 | 工具团队交付接口壳（GAP-054-05） |
| da.compat.run-skill | 技能执行需内核提供每请求系统提示词覆盖通道 | 内核/场景配置提供方（GAP-054-05） |
| da.compat.preprocess | 预处理端点组以预处理场景配置为前置 | 场景配置就绪（GAP-054-05） |
| da.guardrail.sandbox | 护栏放行需真实工具与沙箱服务 | 工具团队交付最小工具集（GAP-054-01） |
| da.skill.runtime-access | 技能搜索/下载/调用闭环依赖工具团队的技能工具 | 工具团队交付技能工具（GAP-054-01） |
| rg.build.aggregate | 属工程内聚合构建，本线无外部可观察面 | 开发侧 CI 承接 |
| rg.a2a.outflow | 零工具路径不存在工具调用，`compat` 开启的流出面增量无从观察 | 工具集交付后补增量用例（GAP-054-20） |
| rg.skillhub.startup | 台账未绑定执行记录（存在独立 run `runs/rg-skillhub-startup-20260922-0031`，见附录 A） | 测试侧补绑定 |

## 6. Test Agent 与 Fixture

本方案的 SUT 是**真实部署的业务 Agent**，不使用 Test Agent mock 制造业务状态；场景可控性来自以下支撑物：

| 支撑物 | 用途 | 边界 |
|---|---|---|
| 真实 SUT（deepanalyze-java 宿主 + 真实模型 + 真实下游依赖） | 全部端到端与集成场景的被测对象 | 不 Mock LLM；不直连被测数据库做断言 |
| DA 形态调用方（`DaHttpProbe` / `DaSseCollector` / `DaCompatSseSupport`） | 按 DA 既有契约发起流式请求、消费 SSE、重连、调用控制面 | 只使用契约声明字段，不引入实现内部兜底 |
| 共享数据面等价环境（PostgreSQL 容器 + DA 侧权威 DDL 副本 + 等价媒体目录） | 数据面读取、属主绑定、媒体读面场景 | schema 由 L2 §2.12 引用构造，与 DA 真实 schema 的差异在结论中声明（Oracle `partial`） |
| 远端 Redis（`localhost:6379`，须预启动） | 任务状态/检查点、兼容端点 TaskStore | 上游把 `services.redis` 改为远端模式（`url:`），栈不再自管容器，属执行前置 |
| 模型连接绑定（`DaModelEnvironment`） | 把运行环境的 `LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER` 绑到宿主 | API key 以最高优先级的 `--` 参数注入（见 §8），值只在运行时从环境读取，不落文件、不落日志 |
| 故障注入（黑洞模型地址） | 错误面与静默失败用例 | 只改模型可达性，不改产品逻辑 |

**Fixture 变更登记**：本特性在 acceptance 仓新增 DA 宿主 SUT 定义与起停支持——`application-openjiuwen.yml` 的 `deepanalyze` 段（坐标 + `classifier: exec` + 开关）、`MavenArtifact`/`SutStack` 的 classifier 支持、`pom.xml` 的 postgresql test 依赖、`sut-sources.yml` 的 `common/agents/deepanalyze-java` 构建步。该变更按 fixture 变更流程单独确认后实施，理由与影响见 `FEAT-054-测试设计初稿.md` §7 与 `FEAT-054-提交清单` 系列记录。

## 7. 关键链路断言

| 断言组 | 断言 | 来源 |
|---|---|---|
| 入口共用 | 容器内只有一个 AgentHandler；标准 A2A 入口与 DA 兼容端点由同一 Handler 服务 | 特性 §3.2；L2 §4.6 |
| 事件帧契约 | 每帧 `id: {taskId}-{seq}` / `event: <名>` / `data: <单行 JSON>`，字段名后带空格；15 秒 `: keepalive` 心跳；完成后关流 | 特性 §2 验收出口 #12；L2 §2.11 |
| 事件序列 | 断开期间已产生事件在重连后无缺口补齐、seq 单调；无源事件不伪造 | 特性 §2 验收出口 #13；L2 §2.11、§3.11 |
| 控制面契约 | 取消受理后出现取消与结束终态组；无待答 404；缺参 400；同步执行成功/失败/待答三态 | 特性 §4 端点明细 |
| 错误表面 | 统一 `{error}` 形态；400/404/401/503 与各自 DA 同源文案 | 特性 §4；L2 §6.2、§7.3 |
| 安全面 | 无/错密钥一律 401 同体；属主失配按端点同源 404；任务列表对异属主返回空数组 | 特性 §3.2；L2 §2.11 |
| 数据面只读 | 宿主对 messages/sessions 零写入；媒体读面有路径穿越防御 | 特性 §3.2；L2 §2.12 |
| 装配生效 | 开关开启时条款进入提示词且任务续跑；关闭时行为与未引入本特性一致 | 特性 §3.1、§3.3；L2 §3.3、§3.7 |
| 既有面不破坏 | `compat` 关闭时标准 A2A 流出面与未引入本特性一致；Skill Hub 启动期链路行为不变；全仓聚合构建通过 | 范围卡 §3 |

## 8. 执行策略

- **构建与执行分工**：构建在 Windows 侧执行（写共享 Maven 本地仓库）；测试在 WSL 内执行并只读共享仓库。
- **制品与构建顺序**：被测制品 `com.openjiuwen:deepanalyze-engine:0.1.4`（`exec` classifier，sha256 `ee86ccfc…031f07`，由 `agent-solution common@b3155d440` 构建）。从源码构建时必须先安装 `agent-runtime-ext-java` 再构建 `deepanalyze-java`（顺序颠倒会因缺少扩展构件失败）。
- **运行前置（逐条预检）**：① 共享 Maven 仓库存在 0.1.4 制品；② 运行环境导出 `LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER`（WSL 侧 `~/.llmrc`，由 runner source）；③ 测试主机 `localhost:6379` 有可用 Redis；④ Docker 可用（PostgreSQL 容器）。
- **模型口径（2026-09-26 基线）**：本轮证据使用运行环境的模型配置 `LLM_API_BASE=https://api.deepseek.com`、`LLM_MODEL=deepseek-flash`、`LLM_PROVIDER=OpenAI`（宿主读 `DA_MODEL_*`，由 `DaModelEnvironment` 从同一环境变量透传）。换模型后重跑需在覆盖台账按新证据记账；仓库 `application-openjiuwen.yml` 里全局的 `LLM_API_BASE`/`LLM_MODEL` 对本宿主不生效（宿主不读这两个键）。
- **模型 key 的供给方式**：仓库 `application-openjiuwen.yml` 的全局 `sut.java.system-properties.LLM_API_KEY` 为空串，框架会以 `-DLLM_API_KEY=` 注入并覆盖环境变量。本线用例经 `DaModelEnvironment` 把 key 以最高优先级的 `--LLM_API_KEY=<env>` 注入，本地无需修改任何被 git 跟踪的文件。
- **执行命令**（单类示例，全量把 selector 换成 18 个 DA 类）：

  ```bash
  cd 04-Environment/local-sit
  SUT_AGENTS_DEEPANALYZE_VERSION=0.1.4 SIT_RUNNER_DIR=<acceptance-feat054 工作树> \
    ./Run-Sit-Wsl-With-Llm.sh -Dtest=DaHostStartupE2EIT -Dit.test=DaHostStartupE2EIT -DfailIfNoTests=false verify
  ```

- **执行分组**：① 启动与能力名片；② 装配与资产；③ 主流程与估算；④ 兼容流式与重连；⑤ 控制面与错误矩阵；⑥ 安全面；⑦ 数据面；⑧ 运行时接线与既有面回归。
- **证据与台账**：每轮产出 `_evidence/runs/<run-id>/`（Surefire/Failsafe XML + run-metadata + 行为证据），执行记录回填唯一覆盖台账；合并 main 后的基线证据为 `runs/feat054-postmerge-full18-20260926-0035`。
- **退出策略**：结论由覆盖对账推导，不由 PASS 数推导；存在 `unassigned`、`degraded` 或未验证条目时特性状态只能是 `open` / `partial-open`，`deferred` 不解除验收阻塞。

## 附录 A. 差异、门禁与待澄清项

> **2026-09-26 更新**：合并 `origin/main`（`2d41cfa`）后，以真实制品 `deepanalyze-engine:0.1.4`
> 重跑 18 个 DA 类 / 49 testcase，全部通过（49/0/0/0）。合并带出的两条环境变化——上游全局
> `sut.java.system-properties.LLM_API_KEY: ""` 以 `-D` 覆盖环境变量、`services.redis` 改为远端
> 预启动（`url: localhost:6379`）——已在用例侧（key 以 `--` 参数注入）与运行前置（预启动 Redis）
> 分别处置，详见 `01-Design_File/20260921/FEAT-054/FEAT-054-合并main与回归记录-20260926.md`。
> 特性收口仍为 `open`：§5.1 的 13 个场景未承接。

**A1 与权威口径的差异**

- 共享 PG 使用"等价 schema"（DA 侧权威 DDL 副本，sha256 `E44A65B9…B599F`），非 DA 源仓权威件 ⇒ `da.data.pg-read`、`da.data.media-taverse` 的 Oracle 记 `partial`。
- 压缩链路与提示词逐字节比对在黑盒不可观察，按设计裁决分别记未承接/白盒（见 §5.1）。
- `sut-sources.yml` 的三条源引用沿用仓库的分支名（`930` / `develop` / `common`），本特性验收使用的具体提交写在相邻注释中，未把共享 provisioning 冻在快照提交上。

**A2 门禁与执行结果**

| 项 | 结果 | 证据 |
|---|---|---|
| 覆盖交付校验（acceptance） | `valid=True`、`errors=0` | `_evidence/verify-review-acceptance-20260926-merge.json` |
| 场景视图一致性 | `valid=True` | `_evidence/views-checkonly-20260926-merge.json` |
| 合并 main 后全量回归 | 18 类 / 49 testcase → 49 / 0 / 0 / 0 | `_evidence/runs/feat054-postmerge-full18-20260926-0035` |

**A3 未承接项与解锁条件**

| 项目 | 影响场景 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 工具团队最小工具集 / 接口壳 | da.compat.tools-list、da.skill.runtime-access、da.guardrail.sandbox、rg.a2a.outflow | 未承接（GAP-054-01 / 05 / 20） | 工具团队交付最小工具集与接口壳 |
| 内核超时分层与弹性轮次预算 | da.timeout.params | 未承接（GAP-054-02） | agent-core-java #171 / #172 合入并完成宿主接线 |
| 部署脚本与运维指南 | da.deploy.reproducible | 未承接（GAP-054-03） | 开发/L2 补齐材料，可按手册从零复现 |
| 压缩事件源与在场性判据 | da.compact.presence | 未承接（GAP-054-19） | core 提供压缩事件源，或设计给出黑盒可观察判据 |
| 待答（ask_user）成功路径事件源 | da.compat.answer | 未承接（GAP-054-18） | 开发侧脚本化事件源测试承接结论回填 |
| 内核每请求系统提示词覆盖通道 / 预处理场景配置 | da.compat.run-skill、da.compat.preprocess | 未承接（GAP-054-05） | 内核或场景配置提供方交付 |
| 白盒 / 工程内 CI 项 | da.prompt.offbyte、rg.build.aggregate | 开发侧承接 | 开发侧执行后回填结论 |
| 同会话并发产品口径 | da.compat.concurrent-same-session | 已执行，判据写法待定稿 | 设计裁决"排队串行 vs 立即拒绝" |
| 台账绑定缺口 | rg.skillhub.startup（有独立 run 未绑定）、da.prompt.offbyte / rg.build.aggregate（未挂覆盖行） | 测试侧 | 补执行记录绑定 / 挂入覆盖行 |
| 上游全局空 `LLM_API_KEY` | 所有依赖 `${LLM_API_KEY}` 的 SUT | 本线用例内规避 | 上游修共享配置，或框架跳过空值声明 |
