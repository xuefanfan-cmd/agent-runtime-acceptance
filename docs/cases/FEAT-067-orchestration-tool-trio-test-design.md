---
title: FEAT-067 编排承接：会话内工作流工具组 测试设计初稿
feature_id: FEAT-067
document_type: test-design
status: draft
automation_status: design-only
feature_source: develop/02-features/FEAT-067-orchestration-tool-trio.md @561f02dd sha256=0d93457697288c38e8c98eb62c59d4f935452bb38e6e760072b2da5d3beab7fb
l2_source: develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-067-orchestration-tool-trio.md @561f02dd sha256=16497f1e72ffd44eedf2965e998c2c4b25a7633da623eb04c37871c842c8e967
requirement_catalog: FEAT-067/FEAT-067-需求条目目录.json（29 条：in 24 / out 5；execution 22 / static 7）
requirement_preview: ../02-需求与测试预览-FEAT-065-067.md
change_scope: ../01-变更包范围卡-FEAT-065-067.md
input_maturity: merged
updated: 2026-10-15
---

# FEAT-067 编排承接：会话内工作流工具组 测试设计初稿

> **⚠️ 基线已刷新（2026-10-15 19:21/19:29）——本文件的部分期望值已作废。**
> docs 由 `561f02dd` → `9ab89fc8`、agent-solution 由 `53c1a174a` → `f9da5b54f`，开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。
> **实现 Java 前必须先读** `/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/03-增量审视与基线刷新记录-20261015.md` **§4**，
> 其中列明了本文件哪些用例的期望值必须更正（例如 FEAT-066 的 `066-S15` 主断言已**完全反转**：由"断言静默降级"改为"断言装载期拒绝"）。
> 本文件按 RULES 保留原样作为旧基线下的审计痕迹，不回头改写。


> 测试设计细档，非正式 TestPlan。动态结果只链接覆盖台账。

## 0. 用户必读：需求与测试方案

- **一句话说明**：编排引擎（FEAT-065/066）是程序接口，用户在会话里够不着；本特性把它包装成主 Agent 能调用的六个工具，用户在会话里说一句"用研究管道模板分析这个"，图纸就跑起来。
- **关键链路**：会话用户 → 主 Agent 调 `workflow_run`（模板名或图纸全文 + 问题）→ 宿主解析模板（白名单防穿越）→ 引擎装载并照图执行 → 工具返回状态 + `workflow_id` + 收口产物 + 逐步留痕；另有 `delegate_task`/`task_output` 异步委托对、`send_message` 成员留言、`subagent_transcript` 留痕查询、`workflow_status` 任务板状态；六件统一门控**缺省开启**（未显式配置时六件进主 Agent 工具集），显式 `deepanalyze.workflow.enabled=false` 时不进且无副作用（docs#43 裁决、!954 生效，2026-11-18 评审反转「默认关」旧口径；L1 main 待同步）；护栏规则中显式放行。
- **本期测试**：16 个场景，**分四层**——本轮读码后有一次重要的层次重划（见下）：
  - **L1t 组件层·工具契约（10 个场景）**：直接 `new` 六件工具并调 `invoke(args, kwargs)`，成员执行体用确定性实现 ⇒ **不需要真实模型、不需要 Redis/Docker、不需要拉起宿主进程**；
  - **静态（3 个场景）**：模板资产四方对账、护栏放行对账、事件契约反向断言；
  - **L3 端到端·真实主 Agent（2 个场景）**：显式关闭时工具不可见（缺省下六件可见——正负双向）、真实会话触发 `workflow_run`；
  - **`blocked`（1 项子断言）**：起止事件实际发出面（源码已证明无进程内观测点，见 §6 067-S13）。
- **本期不测**：`workflow_run` 异步化、逐成员进度事件、动态模板工具面入口、成员带工具执行（Feature §2 四项 OUT）；模板**内容**正确性（归模板库特性）；任务板持久化。
- **未决影响**：`DV067-01`（事件面达成度 L2 与 Feature/代码相反）、`DV067-02`（"事件流不得悬挂"被收窄）、`DV067-03`（`branch_id` 无来源）已提交 **ISSUE #33**；`L067-02`（装配路径决定跨运行留言能否构造）未答前，留言类用例按**最保守形态**（同一 Task 内连续两次运行）构造。
- 详细预览：`/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/02-需求与测试预览-FEAT-065-067.md`
- **输入成熟度**：`merged` ⇒ 可用于正式收口
- **用户理解确认**：`confirmed`，2026-10-15（范围卡 §7.1）

### 0.1 本轮读码导致的层次重划（重要，改变了凭据依赖面）

核实六件工具的**公开构造器**后发现，它们的依赖全部可注入，因此工具契约面**可以在组件层测完**：

| 工具 | 公开构造器（已核实 @ `53c1a174a`） | 可注入的依赖 |
|---|---|---|
| `WorkflowRunTool` | `WorkflowRunTool(DaWorkflowRunner runner)`（`WorkflowRunTool.java:55`） | `DaWorkflowRunner(Function<MemberSpec, SubAgentExecutor> memberFactory, int parallelism)`（`DaWorkflowRunner.java:51`）⇒ **成员执行体工厂可注入** |
| `DelegateTaskTool` | `DelegateTaskTool(DaTaskBoard board, Function<String,String> delegate)`（`DelegateTaskTool.java:37`） | **委托执行函数可注入** |
| `TaskOutputTool` | `TaskOutputTool(DaTaskBoard board)`（`TaskOutputTool.java:35`） | 任务板可直接 `new` |
| `SendMessageTool` | `SendMessageTool()`（`SendMessageTool.java:37`） | 无参 |
| `SubagentTranscriptTool` | `SubagentTranscriptTool(TraceForest forest)`（`SubagentTranscriptTool.java:39`） | 森林可直接 `new` |
| `WorkflowStatusTool` | `WorkflowStatusTool(DaTaskBoard board)`（`WorkflowStatusTool.java:36`） | 同上 |

且 `WorkflowRunTool.invoke(Map<String,Object> args, Map<String,Object> kwargs)` 为 public（`:75`）⇒ 可以**用与模型完全相同的入参形态**直接调用。

**后果（对本期最有价值的一点）**：24 条 `in` 条目里有 **17 条不再依赖 `~/.llmrc`、Docker 与宿主进程**，只需 P1（制品构建）+ P2（pom 依赖，已授权）。
真正必须走真实宿主 + 真实模型的只剩：门控可见性（REQ-017/018）与"主 Agent 能否够到工具"（L3 代表性）。
按 `SKILL.md`「环境缺口不得反推 Oracle」：**这不是为了绕开凭据而降低判据**——L1t 断言的是工具的入参/出参/错误表面契约，本来就不需要模型参与；真实模型该测的部分（L3）一条没减。

## 1. 依据、当前口径与范围

- **设计真相源**：`Technical-AF/docs` @ `561f02dd`，`develop/02-features/FEAT-067-orchestration-tool-trio.md`（137 行）
- **L2 实现说明**：同 revision，`develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-067-orchestration-tool-trio.md`（220 行）
- **已确认裁决**：同 FEAT-065 §1；另**已授权**门控注入 `-Ddeepanalyze.workflow.enabled=true` 与 acceptance 仓 pom 改动
- **已自行核实的实现口径**（证据见 `issues/ISSUE统一台账.md` §3）：
  - 六件工具的公开构造器与依赖注入点（见 §0.1）
  - `workflow_run` 出参 `steps[]` = **id/member/skipped/output 四字段，不含 `branchId`**（`WorkflowRunTool.java:27` 类 javadoc）；而引擎侧 `StepOutcome` **有** `branchId`（javadoc 自称"步态审计入口"）⇒ `DV067-03` 成立
  - `WorkflowRunTool.java:30-32` 类 javadoc **自陈**「时序边界（诚实声明）：…本次运行的起止事件对前端实际**不可达**」⇒ 与 Feature 三处一致，**L2 §1.2 的 `✅已有` 是少数派**
  - 模板名白名单 = `[a-zA-Z0-9-_]+`（L2 §2.4，挂点 `WorkflowRunTool.java:123`）；`SHIPPED_TEMPLATES` 常量在 `:44-46`，八套名字与 Feature §3、L2 §2.4、`resources/workflow-templates/` 下**实际 8 个 yaml 文件四方一致**
  - 护栏资产实际路径 = `common/agents/deepanalyze-java/engine/src/main/resources/governance/actrule.yaml`（L2 只写文件名），**六件全部 `allow`**；该文件 `defaults: "*": ask` 为 fail-closed，另有对账锁 `ActruleMetadataConsistencyTest`
  - `DaPushEventBus.getInstance()` 为**进程级单例**，`publish(key, event, data)` 内部 `subscribers.get(key)` ⇒ **无订阅者即丢弃**；`subscribe(key, subscriber)` 为公开 API（`DaPushEventBus.java:24,34,45,63`）
  - `send_message` 的 `from` 为预留字段、**实收恒空串**（L2 §2.3）⇒ 接收方无法从信封区分留言来源
  - `workflow_status` 入参 `action` 取值 `list` / `status`(+`task_id`)（L2 §2.3）
  - 配置面：`deepanalyze.workflow.enabled` 默认 `false`、`deepanalyze.workflow.parallelism` 默认 `4`（L2 §6；`DaProperties.java:668-670`，无 Bean Validation 注解）
- **范围内**：24 条 `scope=in`；**范围外**：5 条 `scope=out`
- **未决问题**：

| 未决项 | 责任人层次 | 状态 | 对本期用例的影响 |
|---|---|---|---|
| `DV067-01` 事件面达成度（L2 `✅已有` vs Feature+代码 javadoc"不可达"） | 设计确认 | 已提 ISSUE #33 V1 | 067-S13 主断言只判 `workflow_id` 外流；事件发出面 `blocked`（GAP-067-01） |
| `DV067-02` "事件流不得悬挂"无条件 vs catch 面内收窄 | 设计 + 开发 | 已提 ISSUE #33 V2 | 067-S13 只做正例 + 装载失败例；面外反例 `partial`（GAP-067-02） |
| `DV067-03` `subagent_transcript` 的 `branch_id` 无来源 | 设计 + 开发 | 已提 ISSUE #33 V3 | 067-S07 的"按标识查询"子断言 `blocked`，只保留 `roots` 列举为 gating（GAP-067-03） |
| `L067-02` 实际装配路径（单例 vs per-Task 工厂） | 开发-L2 确认 | 已提 ISSUE #33 L3 | 067-S05/S06 按**最保守形态**（同一 Task 内两次运行）构造；跨 Task 形态待答复（GAP-067-04） |
| `L067-01` catch 面外失败能否构造 | 开发-L2 确认 | 已提 ISSUE #33 L1 | 同 `DV067-02` |
| `Q067-D3` 门控关闭时工具集有无对外可查面 | 测试侧自行核实（未竟） | 待核实 `DeepAgent` 是否暴露工具清单 / Agent Card 是否列工具 | 决定 067-S11 是**主断言**还是**降级为前置/配置断言**（GAP-067-05） |
| `D1` `delegate_task` 执行语义只在 L2 | 设计确认 | 已提 ISSUE #33 D1 | 不影响用例（按 L2 口径构造，已核实委托函数可注入） |

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| `deepanalyze-java/engine` 的 `da.workflow` 六件工具类（**组件层直接 new**） | 被测对象本体 | REQ-001~015、020（键外流部分）、021~023 共 17 条 | 门控可见性（需 `DeepAgent` 实例）；"主 Agent 能否够到工具"（需真实模型决策） | **适用**。依据：§0.1 的构造器核实——依赖全部可注入，`invoke(args,kwargs)` 公开；这是**工具契约层**的黑盒测试（入参 Map → 出参对象），不是白盒 |
| `deepanalyze`（DA 宿主 SUT，`deepanalyze-engine:<rev>:exec`） | L3 真实会话载体 | REQ-017/018（门控）、REQ-020（事件外流到真实前端面）、REQ-023（宿主不改编排语义）、"主 Agent 够到工具"的代表性 | 装载违规全集、投影段结构（宿主不透出） | **适用**，4 个场景。依据：既有 `SutStack.agent("deepanalyze")` 通路现成；Feature §1 面向角色「会话用户：用自然语言驱动多 Agent 图纸执行」⇒ 必须有真实会话形态 |
| deepagent / reactagent / workflow | — | — | 全部 | **不适用**：本特性 module 为 `deepanalyze-java`（Feature front-matter），六件工具在 `da.workflow` 包内，与其他三个 SUT 无能力交集 |

**结论**：**不新建 fixture**。但需要 acceptance 仓 pom 新增 `deepanalyze-engine`（或其 `engine` 模块）依赖以在组件层 `new` 六件工具——**已授权**（用户 2026-10-15）。

## 3. 黑盒边界、拓扑与前置条件

**黑盒边界**：只经工具的公开构造器与 `invoke(args, kwargs)` 交互，入参形态与模型实际传入的 `Map` 完全一致。**不 Mock LLM**——L1t 注入的是 `Function<MemberSpec, SubAgentExecutor>`（Feature §3 的成员绑定契约 + L2 §2.6 的 `memberFactory` 注入点），即"装配方提供执行体"，与 FEAT-065 L1 同理（用户已确认该判断）；`DelegateTaskTool` 注入的 `Function<String,String> delegate` 同样是 L2 §2.6 明示的生产委托函数注入点（生产实现为 `DaWorkflowRunner.delegateExecutor`）。**不直连数据库**（本特性无数据库；任务板与森林均进程内）。

**拓扑**：L1t 单 JVM，无容器、无网络、无凭据；静态无运行时；L3 需 `SutStack` + Redis 容器 + 模型凭据 + 门控开启。

**前置条件**：

| # | 前置 | 适用场景 | 状态 |
|---|---|---|---|
| P1 | `deepanalyze-engine`（含 `da.workflow`）与 `agent-core-ext-orchestration` 由 `common`@`53c1a174a` 源码构建 | 全部 L1t + L3 | **未就绪**（公网镜像 404，已实测） |
| P2 | acceptance 仓 pom 新增依赖 | 全部 L1t + L3 | **已授权**，待实施 |
| P4 | 模型凭据 + Redis（Docker） | 仅 L3 的 4 个场景 | **未就绪**（`~/.llmrc` absent；docker/colima 状态矛盾未核实） |
| P5 | 门控开启 `-Ddeepanalyze.workflow.enabled=true` | 仅 L3 的 067-S14/S16 | **已授权** |
| P6 | 门控**关闭**（默认）的对照装配 | 067-S11 | 无需额外授权（默认即关） |

## 4. 需求覆盖矩阵

稳定条目目录：`FEAT-067/FEAT-067-需求条目目录.json`（29 条）。

| REQ | 条目（锚点） | scope | 计划形态 | 用例 ID | 层次 | disposition |
|---|---|---|---|---|---|---|
| REQ-001 | §2 `workflow_run` 入参 | in | execution | 067-S01、S02 | L1t | planned |
| REQ-002 | §2 `workflow_run` 出参四件 | in | execution | 067-S01 | L1t | planned |
| REQ-003 | §2 `workflow_run` 同步执行 | in | execution | 067-S02 | L1t | planned |
| REQ-004 | §2 坏图纸/执行失败可读错误、不炸会话 | in | execution | 067-S03（L1t 文案五态）+ **067-S25（L3 会话存活）** | L1t+**L3** | planned |
| REQ-005 | §2 `delegate_task` | in | execution | 067-S04（L1t 四态+出参契约）+ **067-S26（L3 不阻塞会话）** | L1t+**L3** | planned |
| REQ-006 | §2 `task_output` 四态 | in | execution | 067-S04 | L1t | planned |
| REQ-007 | §2 `send_message` 定向/广播 | in | execution | 067-S05、S06 | L1t | planned |
| REQ-008 | §2 接收方下一步投影自动携带 | in | execution | 067-S05 | L1t | planned |
| REQ-009 | §2 广播达全部已登记成员（含从未被定向过的） | in | execution | 067-S06 | L1t | planned |
| REQ-010 | §2 `subagent_transcript` 按分支标识查询 | in | execution | 067-S07 | L1t | **partial**（`branch_id` 无来源，GAP-067-03） |
| REQ-011 | §2 列出全部执行根 | in | execution | 067-S07 | L1t | planned |
| REQ-012 | §2 `workflow_status` 两动作 | in | execution | 067-S08 | L1t | planned |
| REQ-013 | §2 未知动作可读错误 | in | execution | 067-S08 | L1t | planned |
| REQ-014 | §2 模板名白名单防穿越 | in | execution | 067-S09 | L1t | planned |
| REQ-015 | §2 按名解析图纸 / 未知名可读错误 | in | execution | 067-S09 | L1t | planned |
| REQ-016 | §3 八套模板与工具描述清单对账 | in | **static** | 067-S10（设计口径）+ 067-S21（测试侧加严的漂移检测） | 静态 | planned |
| REQ-017 | §2 门控（缺省开启、显式可关——docs#43 裁决） | in | execution | 067-S11（L1t）+ **067-S23（L3 显式关闭 no-op 实测）** | L1t+L3 | planned |
| REQ-018 | §2 门控关闭时工具不在主 Agent 工具集 | in | execution | 067-S11（L1t）+ **067-S23（L3）** | L1t+L3 | **已闭合**（GAP-067-05 的可观测面已定位并实测：注册期导出量 `event=react_model_call_started … tool_count`，run-40 实收 27 = 开态 33 − 六件） |
| REQ-019 | §2 护栏显式放行 | in | **static** | 067-S12 | 静态 | planned |
| REQ-020 | §2 SSE 键外流 | in | execution | 067-S13 | L1t | planned |
| REQ-020b | §2 SSE 起止成对/失败补发/真实耗时 | in | execution | 067-S13 | L1t | **blocked**（无进程内观测点，GAP-067-01/02） |
| REQ-021 | §5.1.5 参数缺失错误 | in | execution | 067-S03 | L1t | planned |
| REQ-022 | §5.1.3 成员登记与取走即清 | in | execution | 067-S05 | L1t | planned |
| REQ-023 | §6 宿主不改编排语义 | in | execution | 067-S14 | L3 | planned |
| REQ-024~028 | §2 四项 OUT + §5.2 任务板不持久化 | out | static | — | — | out-of-scope |
| — | 事件契约不含无生产者的事件（§3「契约不声明没有生产者的事件」） | in | **static** | 067-S15 | 静态 | planned |

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| 067-S01 | `workflow_run` 模板启动全契约（出参四件） | execution |
| 067-S02 | 图纸直给 + 图纸优先于模板 + 同步返回即结束 | execution |
| 067-S03 | 错误表面五态（问题为空/图纸与模板双缺/坏图纸/未知任务标识/未知 action），会话不炸 | execution |
| 067-S04 | `delegate_task` 提交即返回 + `task_output` 四态 | execution |
| 067-S05 | `send_message` 定向 → 下一步投影携带 → 取走即清 | execution |
| 067-S06 | 广播到达全部已登记成员（含从未被定向过的） | execution |
| 067-S07 | `subagent_transcript`：`roots` 列全部执行根 + 按 `branch_id` 下钻（branch_id 已由 `steps[]` 透出，两者均 gating） | execution |
| 067-S08 | `workflow_status`：`list` / `status`+`task_id` / 未知动作 | execution |
| 067-S09 | 模板名白名单：合法名解析成功、穿越名与未知名给可读错误 | execution |
| 067-S10 | 八套模板全部实装且可装载，工具描述清单与制品资产双向一致（设计口径三处对账） | static |
| 067-S11 | 门控（docs#43 裁决）：缺省装配下六件**均在**工具注册面；显式 `enabled=false` 下**均不在**且无副作用（正负双向；可观测面：宿主能力管理器与进程级资源管理器） | execution |
| 067-S12 | 护栏 `governance/actrule.yaml` 六件显式 `allow` | static |
| 067-S13 | `workflow_id` 随出参外流且形态匹配 `workflow:{id}` | execution |
| 067-S14 | 宿主不改编排语义：L3 结果与 L1 结构逐项对照 | execution |
| 067-S15 | 事件契约不含无生产者的事件声明（!805 删五件死契约的反向看守） | static |
| 067-S16 | 真实 LLM 主 Agent 经会话触发 `workflow_run` | execution |
| 067-S17 | 任务板容量边界（横切层 L0-constraints:35 容量子项） | execution |
| 067-S18 | 重复调用的幂等与重复保护（横切层 L0-constraints:35/41） | execution |
| 067-S19 | 运行期护栏对六件逐一决策（横切层 DFX-003） | execution |
| 067-S20 | 六件工具的 tool.* span 无遗漏（横切层 DFX-001:69） | execution |
| 067-S21 | 模板集合相对冻结基准的漂移检测（测试侧自愿加严，方向分级：缺失 FAIL / 新增 WARN） | static |
| 067-S22 | 跨会话留言隔离端到端探针 PB：会话 A 的定向留言不应被会话 B 的同名成员取走 | execution |
| 067-S23 | 默认装配 no-op 实测：不注入 deepanalyze.workflow.enabled 时真宿主能起能服务，主 Agent 工具面恰好少六件（tool_count 34→28；**34 是 r3 基线的开态实测值**，r2 时为 33→27，见 §7AP.3） | execution |
| 067-S24 | 装配口径一致性门禁：组件层 fixture 与用例的构造方式必须与宿主装配点 DaWorkflowTools.attach 逐项一致（测试侧自我看守，不承接 REQ） | static |
| 067-S25 | 违规图纸 → 工具级可读错误、错误不穿会话，且**同一会话**随后仍能正常完成（REQ-004/REQ-044 的 L3 半） | execution |
| 067-S26 | delegate_task 提交即返回（毫秒级）且**不阻塞会话**——同会话紧接的第二次请求正常完成（REQ-005 的 L3 半） | execution |
| 067-S27 | `workflow_run` 工具调用超时属对外承诺面：ToolCard `resilience.timeout_s` 默认为 1800、可经 `deepanalyze.workflow.run-timeout-seconds` 配置、且与运行预算叠加时取 min（卡片未声明才回落 300） | execution |
| 067-S28 | 成员只读工具集与 FEAT-067:50 的 14 件逐字一致，且与 MUST NOT 的 10 件（write_file/edit_file/bash/powershell + 编排六件）互斥；MEMBER_MAX_ITERATIONS=30 与 L2 一致 | static |
| 067-S29 | 无 caller 会话上下文时成员工具集为空（不挂工具），且范围收敛只在 caller 存在时发生 | static |
| 067-S30 | 成员工具按发起方会话范围收敛：web 关 ⇒ 不含 web 类工具；无 kb 范围 ⇒ 不含 kb 类工具；两者独立可组合 | execution |
| 067-S31 | 成员运行期实际工具面落在 FEAT-067:50 的 14 件只读清单内，且绝不触及 :51 的 MUST NOT 10 件（写工具/执行沙箱工具/编排六件）；并要求 tool_call_count 合计 > 0（工具真被用上，不只是声明挂着）。证据车与 065-S23 为**同一 testcase**——本批唯一能取到 subagent_transcript 的 roots.members[] 明细的端到端场景，共担方式沿用 065-S21/066-S13 先例（同一份证据服务两个特性、各自登记 case id） | execution |
| 067-S32 | 委托执行体的模型口径（REQ-033 的 **L3-C**）：真宿主跑一次 delegate_task，从 SUT 日志取委托执行体侧的模型请求参数，断言 temperature=0.3、max_tokens=4000、is_stream=false（FEAT-067:38 缺省口径，docs !379 改写后），并以主 Agent 的 is_stream=true / max_tokens=null 作**对照**——两种形态若不可区分，就无法把 4000 归给委托执行体 | execution |

<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目（**2026-10-15 21:20 按新基线 `9ab89fc8` / `f9da5b54f` 更新**）

| 缺口 ID | 条目 | 原形态 | **现状态** | 依据 / 恢复条件 |
|---|---|---|---|---|
| GAP-067-01 | REQ-020b 起止事件实际发出 / 失败补发 / 真实耗时 | `blocked` | ⛔ **仍 blocked，但范围已收窄且性质已变** | Feature §2 新增「**当期验收出口**：键外流+失败补发+真实耗时；本次运行的订阅可达性挂异步化/缓冲机制(1130 评估项)，**不作为当期验收内容**」⇒ "订阅可达性"正式出范围（不再是缺口，是 OUT）。**但"失败补发 + 真实耗时"仍无可观测面**：`workflowId = "wf-" + System.nanoTime()`（`DaWorkflowRunner.java:101`）不可预知、`DaPushEventBus.publish` 无订阅者即丢弃且无通配订阅 API、**开发的 UT 也不观测事件面**（`engine/src/test/` grep `DaPushEventBus|workflow_start|workflow_complete` 命中 0）。⇒ 已作为**新发现**写入 `03-增量审视与基线刷新记录-20261015.md` §5B（含三个处置选项），**建议追评到 #33，等用户授权** |
| GAP-067-02 | REQ-020b 的"catch 面外失败仍不悬挂"反例 | `partial` | 🔶 **部分关闭**：异常传播可测，事件补发仍不可测 | Feature §5.1.4 新增「止事件的补发**覆盖任意失败形态**(start 已发则 complete 必发，**含可预期失败族之外的逃逸**)」；L2 §2.5 改为 finally 口径并注明「**#33 V2 方案②**」；代码 `DaWorkflowRunner` 已加 finally 兜底（复核 4 处命中），且「UT 锁定**面外异常原样逃逸不吞不换型**」⇒ **可构造面外失败并断言"异常原样上抛、不吞、不换型"**（升为 gating）；但"complete 确实补发"仍受 GAP-067-01 的观测面限制 |
| GAP-067-03 | REQ-010 的"按 `branch_id` 查询"子断言 | `blocked` | ✅ **已关闭 → 升为 gating** | Feature §2 `workflow_run` 出参已含「**留痕分支标识 branch_id**——subagent_transcript 查询入参的来源」；L2 §2.1 同步并注明「**转测评审 #33 V3**」；代码已透出（复核 4 处命中）⇒ 067-S07 断言 `steps[].branch_id` 非空且可用于 `subagent_transcript` 取回分支详情 |
| GAP-067-04 | REQ-007/008/022 的**跨 Task** 留言形态 | `unassigned` | ✅ **已关闭**（明确为范围外） | Feature §4「成员留言」前置条件改为「**同一任务(Task)内**的两次工作流运行之间」，并新增口径边界「宿主按 **per-Task 工厂装配**，森林与消息区按实例自持——『两次运行』指**同一 Task 内，跨 Task 留言不承诺**」⇒ 测试侧原选的"最保守形态"**即设计口径**；跨 Task 形态属**设计明确不承诺**，按范围外处理（承接方 = Feature §4 该条），不再是 `unassigned` |
| GAP-067-05 | REQ-018 的"工具集"可观测面 | `partial`（可能降级） | ✅ **已关闭 → 升为主断言** | 探针已核实：`DaWorkflowTools.attachIfEnabled`（`DaWorkflowTools.java:37-50`）在 `!enabled` 时**直接 return**；启用时经 `ToolMount.mount`（`da/tools/ToolMount.java:32-36`）写入 **`core.getAbilityManager()`** 与 **`Runner.resourceMgr()`** ⇒ **两个可查面**。另核实 `AgentCard.builder()`（`DaEngineConfiguration.java:138`）只设 id/name/description、**不含 tools** ⇒ Card 不是可查面。**剩余唯一不确定项**：能否在不接真实模型的前提下构造 `BaseAgent`/`DeepAgent` 实例（Java 实现阶段确认；若不能则走 L3 真实宿主） |
| GAP-067-06 | 067-S11 / S14 / S16（L3 场景） | `blocked` | ⛔ **仍 blocked（环境类，按用户指示统一到最后解决）** | **制品已就绪**（`deepanalyze-engine:0.1.3-feat065067-r2:exec`，138 MB，`Start-Class: com.openjiuwen.da.DeepAnalyzeApplication` 已核）；剩余前置只有环境类：`~/.llmrc` absent + Docker Hub 不可达致无 redis 镜像（ENV-1.5）。**注**：067-S11 若能在组件层构造 `BaseAgent`，则**不再依赖环境**（见 GAP-067-05） |
| GAP-067-07（新增） | `WorkflowFullAssemblyRealLlmE2eTest` 判据偏弱在哪、加强方向 | — | 🔶 `partial`（待开发答） | L2 §1.3/§7.1 的该条自陈本轮未修订，已在 **#33 评论中催答**。测试侧不等答复：067-S16 已按**结构性断言**设计（条数/成员/branch_id/收口来源/键形态），**不采用宽泛子串命中** |

**汇总**：原 6 条缺口 → **3 条关闭**（03/04/05）、**1 条部分关闭**（02）、**1 条收窄且性质改变**（01，并带出一条待授权的新发现）、**1 条仍 blocked 于环境**（06）、**新增 1 条 partial**（07）。
**本期 FEAT-067 的 10 个 L1t 场景 + 3 个静态场景已无设计侧阻塞**，只等 Java 实现授权；仅 3 个 L3 场景受环境阻塞。

## 5. 端到端场景

**L1t 主旅程（组件层工具契约，10 个场景）**：
`new TraceForest()` / `new DaTaskBoard(parallelism)` / `new DaWorkflowRunner(确定性 memberFactory, 4)` →
`new WorkflowRunTool(runner)` 等六件 → `tool.invoke(Map.of("template","research-pipeline","question","U-067-S01-<uuid8>"), Map.of())` →
断言返回对象（`status` / `workflow_id` / `final_output` / `steps[]` 或 `error`）。

**L3 主旅程（真实会话）**：`SutStack.agent("deepanalyze")` + Redis + 门控 → 标准 A2A 入口发自然语言 →
主 Agent 自主调 `workflow_run` → 断言出参契约 + 与 L1 同一图纸的**结构对照**。

**静态旅程**：只读 `resources/workflow-templates/*.yaml`、`resources/governance/actrule.yaml`、`WorkflowRunTool.java` 的 `SHIPPED_TEMPLATES` 常量与 `DaPushEvents` 的事件声明。

## 6. 详细用例

### 067-S01：`workflow_run` 模板启动全契约
- 可执行性/优先级：`runnable` / **P0**；形态 `execution`；层次 **L1t**；Oracle：Feature §2「workflow_run」出参契约 + §4「模板启动」场景
- **Given**：`DaWorkflowRunner`（确定性 memberFactory：每成员回显 `M-067-S01-<memberId>-<uuid8>`，parallelism=4）+ `WorkflowRunTool`；入参 `Map.of("template","research-pipeline","question","U-067-S01-<uuid8>")`
- **When**：`invoke(args, Map.of())`
- **Then**：返回对象含 ① `status == "completed"`；② `workflow_id` **非空**；③ `final_output` 非空且 == 末步 output；④ `steps[]` 每条含 `id`/`member`/`skipped`/`output` 四字段，条数 == 该模板图纸的步数，`member` 与图纸声明一致
- **PASS**：四项全成立 **FAIL**：缺任一字段、条数不符、`final_output` 与末步 output 不一致 **INCONCLUSIVE**：模板资产不可解析（先核对 P1 制品是否含 `resources/workflow-templates/`）
- 证据：`invoke` 返回对象的完整序列化 + 该模板图纸的步数基准
- **不应断言**：`steps[]` 含 `branch_id`（`DV067-03` 未修，断言它会 FAIL 但不是本用例的目标）；模型是否参与（L1t 无模型）
- 自动化：`component/orchestration/DaWorkflowRunToolContractTest#templateRunFullContract`，`@Tag("component")` `@Tag("feat-067")`，`@Feature("FEAT-067: 编排承接——会话内工作流工具组")`，`@Story("067-S01: workflow_run 模板启动全契约")`

### 067-S02：图纸直给 + 图纸优先 + 同步返回即结束
- 可执行性/优先级：`runnable` / **P0**；层次 L1t；Oracle：Feature §2「图纸全文或模板名（二选一，**同给时图纸优先**）」+ §5.1.1 同步语义
- **Given**：三个实例——① 只给 `blueprint`（单兵图纸，1 步）；② 只给 `template`（`research-pipeline`，多步）；③ **同时给** `blueprint`（单兵）与 `template`（`research-pipeline`）
- **When**：分别 `invoke`
- **Then**：① `steps[]` 条数 == 1；② 条数 == 模板步数；③ 条数 == **1**（图纸优先，模板被忽略）；三者 `status` 均 `completed`；`invoke` **同步返回**（返回时执行已结束，`steps[]` 已完整）
- **PASS**：三实例符合 **FAIL**：实例 ③ 条数 == 模板步数（图纸未优先） **INCONCLUSIVE**：图纸装载失败
- 证据：三次返回对象的 `steps[]` 条数与成员清单
- **不应断言**：耗时有界或超时行为（Feature §2 OUT「异步 `workflow_run`」；L2 §1.3「无超时无取消」⇒ 构造超时用例即误判）
- 自动化：同测试类，3 参数实例

### 067-S03：错误表面五态，会话不炸
- 可执行性/优先级：`runnable` / **P0**；层次 L1t；Oracle：Feature §5.1.5 错误表全 5 行 + §2「坏图纸与执行失败返回可读错误，**不炸会话**」
- **Given**：五个实例——① `question` 缺失；② `blueprint` 与 `template` **双缺**；③ `blueprint` 为违规图纸（依赖环）；④ `task_output` 传未知 `task_id`；⑤ `workflow_status` 传未知 `action`
- **When**：分别 `invoke`
- **Then**：五者**均返回带 `error` 的结果对象而非抛出异常穿穿调用栈**；① ② 的错误文案**指明缺失参数**；③ 含图纸定位；④ ⑤ 为可读错误、**不悬挂**（在有界等待内返回）
- **PASS**：五态全部返回 `error` 且文案符合要求 **FAIL**：任一实例抛出异常（⇒ 会炸会话）或悬挂 **INCONCLUSIVE**：返回结构无法解析
- 证据：五条 `error` 文案**原文**
- **不应断言**：错误文案的**具体措辞**（Feature 只要求"可读""指明缺失参数"，未给文案模板）
- 自动化：`DaWorkflowErrorSurfaceTest`，5 参数实例

### 067-S04：`delegate_task` + `task_output` 四态
- 可执行性/优先级：`runnable` / **P0**；层次 L1t；Oracle：Feature §2 两行 + §5.1.2；实现依据 L2 §2.2（`DaTaskBoard.submit`，`CompletableFuture` + 固定池）
- **Given**：`DaTaskBoard(4)` + 注入的确定性 `delegate` 函数（可控：立即返回 / 延迟 2s 返回 / 抛异常）+ `DelegateTaskTool` + `TaskOutputTool`
- **When**：① `delegate_task(goal)` → 得 `task_id`；② 立即 `task_output(task_id)`；③ 有界等待后再查；④ 查未知 `task_id`；⑤ 用会抛异常的 delegate 再走一遍
- **Then**：① 返回 `status == "submitted"` + 非空 `task_id`，且 `invoke` **立即返回**（不阻塞，用有界等待证明）；② `running`；③ `completed` 且带 `output`；④ `error` 可读、不悬挂；⑤ `failed` 且带 `error`
- **PASS**：四态齐备且各态字段正确 **FAIL**：缺态、`delegate_task` 阻塞、未知 id 悬挂 **INCONCLUSIVE**：任务板线程池未启动
- 证据：四次 `task_output` 返回对象原文 + `delegate_task` 的返回耗时
- 不应断言：`task_id` 的具体格式（L2 §3.2 提到 `task-<seq>`，属实现约定）；委托执行体是真实模型（L1t 注入确定性函数；真实形态归 067-S16）
- **⚠️ 本用例不承接 REQ-033（委托执行体语义）**（2026-10-20 更正）：REQ-033 要求的是「裸 ReActAgent、温度 0.3/maxTokens 4000、目标只走 subGoal 位、userInput 位恒空、执行体无工具」，而本用例验的是 `delegate_task`/`task_output` **四态**（属 REQ-005）。此前 REQ-033 误绑本用例并记 `covered` ⇒ **覆盖过度声明**，已降级为 `degraded`（`GAP-067-07-REQ-033`）。机械核查佐证：全仓 `maxTokens` 命中 0 个文件、`MEMBER_TOOLS` 0、`memberExecutor` 0、字面量 `0.3` 0；`subGoal`/`userInput` 的命中全部是**我方注入执行体的 lambda 形参名**，不是对产品执行体的断言。
- **REQ-033 的判据为何暂不写定**：Feature-067 §2 / L2-067 写「执行体无工具」，而 `ce9019d86` 的 `DaWorkflowRunner:52-54 MEMBER_TOOLS` 是 **13 件只读工具**且成员工厂(:212)与委托执行体(:223)两处都挂 ⇒ **文档与实现相反**，已追加到 `Technical-AF/docs#37`（comment `194685128`）请裁决。按 SKILL「预期来自需求和使用目标，不照抄当前实现」，裁决前**不按实现改判据**。
- **REQ-033 的规划层次 = L3（不用静态读源码代替）**：真宿主跑一次 `delegate_task`，从 SUT 日志取成员侧 `model client config params ready. {…}` 与 `event=react_model_call_started … tool_count=N`（run-41 已证实该观测面存在）。⚠️ 该日志行**不含 temperature / max_tokens** ⇒ 这两项当前无可观测面，已在 docs#37 第四节第 3 问请设计确认是否仍为承诺值。
- 自动化：`DaTaskBoardToolContractTest`，5 个断言段

### 067-S05 / S06：`send_message` 定向、投影携带、取走即清、广播可达
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"广播可达性"）；层次 L1t；Oracle：Feature §2 三行 + §5.1.3 + §4「成员留言」场景
- **067-S05 Given**：`SendMessageTool` + `DaWorkflowRunner`（其 `messageDrain` 接到该 tool）；两次**同一 Task 内**的 `workflow_run`（保守形态，GAP-067-04）；第一次运行前 `send_message(target="mB", content="MSG-067-S05-<uuid8>")`
  **Then**：第一次运行中 mB 实收 `subGoal` 含 `[pending messages]` 段与该留言；**第二次运行中 mB 实收输入不再含该留言**（取走即清、不重放）
- **067-S06 Given**：图纸声明成员 mA、mB、mC；先只向 mA 定向留言（⇒ mC **从未被定向过**），再 `send_message(target="all", content="BC-067-S06-<uuid8>")`；随后运行
  **Then**：mA、mB、mC **三者**的实收输入都含该广播内容（含从未被定向过的 mC，验证 L2 §2.3 的 !805 修复"绑定期 `registerMember` 预建收件箱"）
- **PASS**：S05 首次含、二次不含；S06 三成员全达 **FAIL**：mC 未收到（广播漏达已登记成员）或二次仍含（未清） **INCONCLUSIVE**：成员未被调用
- 证据：各成员执行体实收 `subGoal` 原文（两次运行各一份）
- **不应断言**：留言的**来源可辨**（L2 §2.3 明示 `from` 为预留字段、实收恒空串 ⇒ 要求来源标识会把符合设计的实现判 FAIL）；"成员在运行中互相留言"（Feature §2 OUT「成员带工具执行」；L2 §1.3「成员为裸 `ReActAgent`，无工具」）；跨 Task 形态（GAP-067-04）
- 自动化：`DaSendMessageToolTest`，2 个方法

### 067-S07：`subagent_transcript`——`roots` 列举 + 按 `branch_id` 下钻
- 可执行性/优先级：`roots` 部分 `runnable` / **P1**；按标识查询部分 **`blocked`**（GAP-067-03）；层次 L1t
- Oracle：Feature §2「按留痕分支标识查询轨迹详情（子分支/回溯路径/深度）；**支持列出全部执行根**」；L2 §2.3
- **Given**：`SubagentTranscriptTool(forest)`；同一宿主实例内连续两次 `workflow_run`（不同图纸）
- **When**：① `invoke(Map.of("branch_id","roots"))`；② 用 `workflow_run` 出参尝试取 `branch_id` 后下钻
- **Then**：① 返回**两个**执行根（森林跨 run 累积可查，Feature §5.1.5 / L2 §2.3）；② **无法从 `workflow_run` 出参取得 `branch_id`**（已核实 `steps[]` 只有四字段）⇒ 该子断言记 `blocked`，改用 `roots` 返回的根标识逐层下钻作为**替代路径**并记录其局限（多工作流跑过后无法定位"刚才那一次"）
- **PASS**：① 成立 **FAIL**：`roots` 未返回两次运行的根（⇒ 森林未跨 run 累积） **INCONCLUSIVE / blocked**：②
- 证据：`roots` 返回原文 + `workflow_run` 出参 `steps[]` 的字段名清单（证明无 `branch_id`）
- 自动化：`DaSubagentTranscriptToolTest`

### 067-S08：`workflow_status` 两动作 + 未知动作
- 可执行性/优先级：`runnable` / **P1**；层次 L1t；Oracle：Feature §2 + §5.1.5；L2 §2.3（`action` = `list` / `status`+`task_id`）
- **Given**：`DaTaskBoard` 内已有 1 个 completed 与 1 个 running 任务
- **Then**：`action=list` 返回任务板快照（含两个任务）；`action=status` + 已知 `task_id` 返回单任务；`action=<未知>` 返回可读错误不悬挂
- 自动化：`DaWorkflowStatusToolTest`，3 参数实例

### 067-S09：模板名白名单与穿越拒绝
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"模板白名单与穿越拒绝"）；层次 L1t；Oracle：Feature §2「模板名白名单（字母数字连字符下划线）防路径穿越；未知名返回可读错误」；实现正则 `[a-zA-Z0-9-_]+`（L2 §2.4）
- **Given**：正例 `research-pipeline`、`zero_hallucination`（含下划线）；反例 `../secret`、`a/b`、`..\\x`、`a%00b`、`""`、未知但合法字符集的 `no-such-template`
- **Then**：正例解析成功并执行；**全部反例返回可读 `error`**，且**不发生任何文件系统访问**（用不存在的穿越目标 + 断言错误文案为"非法名"而非"文件不存在"来间接证明白名单先生效）
- **PASS**：正例通过、反例全拒 **FAIL**：任一穿越名被当作路径解析 **INCONCLUSIVE**：模板资产缺失
- 证据：各反例的 `error` 文案原文
- 自动化：`DaWorkflowTemplateResolutionTest`，8 参数实例

### 067-S10：八套模板资产与工具描述清单对账（静态，**设计口径**）
> **2026-10-19 Oracle 变更（批次记录 §7W.5 处置③，经用户授权）**：原为「四方集合完全相等」，
> 现改为**设计口径的三处对账 + 双向一致性**，并把「冻结基准漂移检测」拆出为独立用例 **067-S21**。
> 起因：模板资产的**归属方是 FEAT-068**（L2-067:30「模板内容资产(归模板库特性;本特性只提供名字→资产的解析接缝)」），
> 而 FEAT-068 L2 §1.3:51 明写「实装清单(SHIPPED_TEMPLATES)与资产目录靠**人工对账**：测试断言『八套可装载』，
> **不断言『目录下只有八套』——多放一套资产不会红**，清单漂移只能评审拦截」，§2.5:104 亦记「已知弱化：**无自动化漂移检测**」。
> 原「四方相等」硬断言会在 FEAT-068 合法落地第 9 套模板时产生**假红**并被误读成产品缺陷。

- 可执行性/优先级：`runnable` / **P0**；形态 **`static`**（Feature §3 明写"实装八套（**对账基准**）；工具描述清单必须与资产对账一致"⇒ 设计本来要求静态对账）
- **Given/When**：读取三个来源——① Feature §3 / L2-067 §2.4 列出的八套（冻结基准 `EXPECTED`）；② `WorkflowRunTool.SHIPPED_TEMPLATES` 常量，即拼进 `template` 参数描述 `ships:` 段、**对模型可见**的清单；③ 制品内 `resources/workflow-templates/` 的实际资产
- **Then**（四条硬断言）：
  1. 八套资产**都在**制品里（`actual ⊇ EXPECTED`）；
  2. 八套**逐套可装载**——`BlueprintLoader.load` 通过、`id` 非空、`type` 非空、`members` 非空（证明不是死文件）；
  3. 八套**都对模型可见**（`template` 参数描述逐个点名）；
  4. **清单与制品资产双向完全相等**（`advertised == actual`）——Feature §3「工具描述清单必须与资产对账一致」+ FEAT-068 §2.5「三处漂移即缺陷」。
  **不**断言「目录下只有八套」（该方向归 067-S21，按 WARN 处理）。
- **PASS**：四条全过 **FAIL**：任一条不成立
- **故障与主断言**：若目标行为出现「下架/漏装某套模板」故障 ⇒ 断言 1 应失败；若出现「资产与清单不同步（加资产未改常量，或改常量未加资产）」故障 ⇒ 断言 4 应失败。**两者均已实测触发**（见下 NC-A）。
- 证据：`DaWorkflowTemplateAssetReconciliationTest` 的 surefire XML + 组日志（run-35）
- **不应断言**：模板**内容**正确性（归 FEAT-068 模板库特性，范围卡 §4.1）
- 自动化：`DaWorkflowTemplateAssetReconciliationTest#shippedTemplatesReconcileAcrossSources`（静态）
- **⚠️ 方法名变更**：原 `shippedTemplatesReconcileAcrossFourSources` → `shippedTemplatesReconcileAcrossSources`（不再"四方"）。台账 `bindings`/`executions` 已同步。

### 067-S21：模板集合相对**冻结基准**的漂移检测（静态，**测试侧自愿加严**，方向分级）
> 本条**不是设计要求的自动化检测**，而是 SIT 主动补上 FEAT-068 §1.3/§2.5 自陈的「已知弱化：无自动化漂移检测」。
> 为避免假红污染验收判定，Oracle 按**方向分级**——这正是处置③的核心。

- 可执行性/优先级：`runnable` / **P2**（测试侧加严项，非设计 MUST）；形态 **`static`**
- **Given/When**：把本期**冻结基准**（八套名单，源自 Feature §3 + L2-067 §2.4）与制品实际集合（资产 + `ships:` 清单）做双向差集
- **Then**（方向分级）：
  - **缺失方向**（基准有、制品无）⇒ 违反 Feature §3「实装八套」MUST ⇒ **判 FAIL**；
  - **新增方向**（制品有、基准无）⇒ FEAT-068 §1.3 明写「多放一套资产不会红」⇒ **只记 WARN，不判 FAIL**，并在 stdout 打出三行：漂移清单、归属方依据原文、以及**正确动作是「更新测试侧冻结基准并对齐 FEAT-068 §2.1 模板表」而非「提产品 ISSUE」**。
- **PASS**：无缺失（新增仅 WARN） **FAIL**：存在缺失
- **故障与主断言**：若目标行为出现「模板被下架/漏装」故障 ⇒ 缺失方向断言应失败（**已实测**：NC-A 把基准塞入一个不存在的名，2/2 FAIL）；若出现「设计合法扩张到第 9 套而测试基准未更新」⇒ **不应**判 FAIL，只应 WARN（**已实测**：NC-B 把基准砍到 7 套，2/2 PASS + WARN 三行全部打出）。
- 证据：`evidence/20261019-run-35/groups/DaWorkflowTemplateAssetReconciliationTest.{xml,log}`（正向）；
  负向对照 `evidence/20261017-run-28-rebase/nc-a-missing-direction.log`、`…/nc-b-extra-direction.log`
- **不应断言**：模板内容正确性；也不应把「新增方向」升级为产品缺陷
- 自动化：`DaWorkflowTemplateAssetReconciliationTest#frozenBaselineDriftIsJudgedByDirection`（静态）
- **归属登记**：本条属**测试侧加严**，不计入设计要求的覆盖分母；REQ-016 的承接仍以 067-S10 为主、067-S21 为附加检测。

### 067-S23：显式关闭 **no-op 实测**（L3，2026-10-19 新增；2026-11-18 裁决反转为显式关闭口径）
> **为什么补这条**：变更包范围卡 §3 第 1 条把「门控默认关 ⇒ FEAT-067 对默认部署是 **no-op**」当作
> 排除**全部 26 个 FEAT-054 宿主装配面回归用例**的唯一支点（承接方类型 (a)，范围卡 §4.2）。
> 但该句此前只以"依据/理由"形式出现在 4 份制品里（范围卡 :22/:90、FEAT-065 TestPlan :71、
> 需求与测试预览 :124、用户版测试方案 :334），**无一处挂过运行结果** ⇒ 是论证不是证据（批次记录 §7W.6）。
> **反证线索**：`Feat-Func-067:180`「护栏（actrule.yaml）：六件显式 allow」改的是**宿主共享配置**，
> 即使六件门控关着，护栏规则集也变了 ⇒「配置层面 no-op」并不显然成立。
> **2026-11-18 更新（docs#43 裁决、!954 生效）**：门控缺省值已由 false 翻转为 true——本场景支点由
> 「默认部署 no-op」改为「**显式 `enabled=false` 装配 no-op**」；缺省装配下六件应在场（067-S11 正向断言）。
> 排除 26 个 FEAT-054 回归用例的逻辑随之改为「显式关闭 ⇒ 对该部署 no-op」。

- 可执行性/优先级：`runnable` / **P0**（它撑着 26 个回归用例的排除决定）；层次 **L3**；形态 `execution`
- **Given**：SUT 条目 `deepanalyze-feat065067-default`——与门控开的 `deepanalyze-feat065067`
  **逐键相同、只删掉 `deepanalyze.workflow.enabled` 与 `deepanalyze.workflow.parallelism` 两个键**
  （已核对：ON 独有键恰为这两个、OFF 独有键为空、共同键值全等 ⇒ **门控是唯一变量**）
- **When**：拉起真宿主，发一次不含工具调用的普通会话（`POST /api/agents/run`）
- **Then**：① 宿主启动成功（日志含 `Started DeepAnalyzeApplication`）且会话返回 200 + 非空 `taskId`；
  ② 主 Agent（`agent_id=deepanalyze-agent`）的 **`tool_count` 最大值 == 27**；
  ③ 六件在全部 `tool=<name>` 运行期记录里命中 **0**
- **可观测面为什么选 `tool_count`（关键）**：`tool=<name>` 只在**调用时**打印，默认装配下没人调用六件 ⇒
  用"日志搜不到六件"来判会把**观测通道失效**误读成**门控生效**（以无证据当证据）。
  `event=react_model_call_started … tool_count=N` 是**注册期导出量**，与模型是否调用无关。
  又因主 Agent 一次会话里也会出现 `tool_count=0` 的调用（run-39 实测 24 次 33 / 2 次 0）⇒ 判据取**最大值**而非全等。
- **正向对照（证明该通道看得见六件）**：门控**开**的同一制品在 run-39 实测 `max(tool_count)=33`
  （`evidence/20261019-run-39/sut-stdout.log`，sha256 前32 `49777646b9121d8e5e516bb515959865`）⇒ 关态必须恰为 `33−6=27`。
  依 SKILL《执行与证据》「已有同判据的真实失败证据可复用」，不再另拉一套开态宿主。
- **PASS**：三条全过 **FAIL**：`max==33`（门控失效，六件被无条件装配）/ `max<27`（门控过度抑制，连既有工具一起丢）/ 宿主起不来
- **故障与主断言**：若目标行为出现「门控形同虚设」故障 ⇒ 断言 ② 应失败（max 会是 33）；
  若出现「本期代码让默认部署起不来」故障 ⇒ 栈启动或断言 ① 应失败。**实测结果：`tool_count=27`、六件命中 0、启动标记 1 次 ⇒ PASS**（run-40，8.877 s）
- **⚠️ 范围缩减（如实登记，非等价于设计原文）**：067-S11 原文要求「两套装配**分别拉起宿主**」；
  本条只拉**①默认装配**那一半。理由：②开态相对关态「新增恰好六件、且不移除任何既有工具（零副作用）」
  已由 L1t 的 `DaWorkflowGatingToolSetTest`（067-S11）**确定性证明**，且开态 `tool_count=33` 已有 run-39 实测对照
  ⇒ 再拉一套开态宿主只增机时、不增判据。**代价**：本条不含"同轮自比"，开态基线来自另一轮证据。
- **⚠️ 残余未测（不得越读）**：本条证明的是**行为层面** no-op（工具面恰好少六件、宿主可用）。
  `actrule.yaml` 里那 6 条 `allow` 在默认装配制品中**依然存在**（只是因工具未注册而失效）
  ⇒「**配置字节层面** no-op」**不成立、也未被本条证明**。若要收口这一层，需另设判据比对护栏规则集。
- **⚠️ 沿用的代表性偏差**：SUT 条目仍带 `checkpointer.type=in_memory` 绕行（产品缺陷 **D4**）
  ⇒ 本条 PASS **不得**读成"默认配置（redis 检查点）下也能起"。
- 证据：`evidence/20261019-run-40/`（`failsafe/TEST-…DaWorkflowDefaultAssemblyNoOpE2EIT.xml`
  sha256 前32 `470c5866c375b8e7d39a042f1c310d99`、`sut-stdout.log` `accd7ea96c159773a9221817e0690419`、
  `mvn-verify.log` 经 runner 脱敏、`run-metadata.json`）
- 自动化：`DaWorkflowDefaultAssemblyNoOpE2EIT#defaultAssemblyIsNoOpForShippedDeployment`（L3）
- fixture：`src/test/resources/application-openjiuwen.yml` 新增 `sut.agents.deepanalyze-feat065067-default`
  （**经用户 2026-10-19 授权的 fixture 变更**）

### 067-S25：违规图纸 → 工具级错误、**会话不炸**（L3，2026-10-20 新增）
> 承接 REQ-004 / REQ-044 的 **L3 那一半**。§7AD.2 把这两行判为「A 类：必须补 L3」——
> 因为「**会话不炸**」是宿主线程与 Task 生命周期性质，组件层 067-S03 只能证"工具返回 error 对象而非抛异常"，
> **证不到会话存活**。

- 可执行性/优先级：`runnable` / **P0**；层次 **L3**；形态 `execution`
- **Given**：真宿主（门控开）+ 真实模型 + compat 端点；违规图纸刻意写成**单行 flow-style YAML**
  `blueprint: {id: T-067-S25-bad, type: mesh, members: [{id: m1, instruction: x}]}`
  ——多行块状 YAML 极易被模型重新格式化/修正掉违规点，导致用例退化为 INCONCLUSIVE；
  `type: mesh` 与组件层 065-S03 的 `T-065-S03-type` **同源**，保证 L1t 与 L3 打的是同一个违规族
- **When**：① 会话内让主 Agent 用该图纸调 `workflow_run`；② **同一 sessionId** 再发一次不含工具调用的普通请求
- **Then**（三条，全部走**产品侧观测面**）：
  1. 第一次请求的任务 `status == completed` ⇒ 工具错误**没有升级成任务失败**；
  2. SUT 日志有 `tool_name=workflow_run outcome=success` ⇒ 工具体**正常返回**（错误以 `ToolOutput(success=false)` 返回，**不是异常穿出**）；
  3. 第二次请求 `status == completed` + 答复正文非空，且日志里 `All tasks completed for session <该会话>` **≥ 2 次** ⇒ **会话存活**
- **PASS**：三条全过 **FAIL**：任一条不成立 **INCONCLUSIVE**：主 Agent 未调用 `workflow_run`（模型未配合，`Assumptions.abort`）
- **故障与主断言**：若"错误穿会话"（工具异常冒到 Task 层）⇒ 断言 1 或 3 失败；若会话被污染 ⇒ 断言 3 的第二次请求失败。
  **run-44 实测 PASS（30.05 s）**：`outcome=success duration_ms=7.89`、两次 `All tasks completed`、全日志 **0 条 ERROR**
- **⚠️ 不在 L3 重复计功的部分**：错误**文案**（五态 + 违规定位 `mesh`）由 **L1t 067-S03** 确定性承接。
  原因：`toolCalls[].output` 被 `agent-solution#462` 阻断（compat 聚合层未回填 `tool_result`），
  L3 拿不到工具出参原文。用例对此**显式打 WARN 并说明由谁承接**，不静默降级、也不判产品 FAIL。
  若 `output` 非空则**仍然严判**（`success=false` + `workflow_run failed` + `mesh`）——即通道恢复后判据自动变强。
- 证据：`evidence/20261020-run-44/`（`failsafe/` + **`allure-results/` 全量归档** + `sut-stdout.log`）

### 067-S26：`delegate_task` 提交即返回、**不阻塞会话**（L3，2026-10-20 新增）
> 承接 REQ-005 的 **L3 那一半**（"不阻塞会话"）。

- 可执行性/优先级：`runnable` / **P1**；层次 **L3**；形态 `execution`
- **Given/When**：① 会话内让主 Agent 调 `delegate_task`（goal 为一段约 600 字的真实分析任务，委托体是真实模型的裸 ReActAgent）；
  ② **同一 sessionId** 紧接着再发一次普通请求
- **Then**：
  1. SUT 日志 `tool_name=delegate_task outcome=success duration_ms=<X>` 且 **X < 5000 ms** ⇒ **提交即返回**，不等委托体跑完
  2. 第二次请求 `status == completed` + 正文非空，且 `All tasks completed for session <该会话>` **≥ 2 次** ⇒ **会话未被委托堵住**
- **PASS**：两条全过 **FAIL**：`duration_ms` 接近委托体真实耗时（说明变成同步阻塞）或第二次请求未完成 **INCONCLUSIVE**：模型未调用 `delegate_task`
- **故障与主断言**：若 `delegate_task` 变成同步等待 ⇒ 断言 1 的 5 s 上限失败；若会话被占住 ⇒ 断言 2 失败。
  **run-44 实测 PASS**：`duration_ms=21.5`、两次 `All tasks completed`
- **不重复计功**：`task_id` / `status=submitted` 的**出参契约**与"四态齐备 + 有界等待证明立即返回"由 **L1t 067-S04** 承接
- 证据：同 067-S25

### 067-S11：门控双向（缺省开启在场 / 显式关闭不在）
- 可执行性/优先级：**`runnable`** / **P0**（docs#43 裁决、!954 生效后可观测面已定位）；层次 **L3**
- Oracle：现行 Feature §2（门控缺省开启；显式 `enabled=false` 时六件**不进主 Agent 工具集**且零副作用）+ §4「门控关闭」场景；L2 §6 同口径
- **Given**：两套装配——① 缺省（不注入 `deepanalyze.workflow.enabled`，六件**应在场**）；② 显式注入 `=false`（六件**不在**）
- **When**：分别拉起宿主，查询主 Agent 工具集
- **Then**：① 六件**均在**工具集；② 六件**均不在**且无副作用
- **PASS**：两套装配的工具集差异恰好为六件、方向正确 **FAIL**：缺省下六件不可见（门控失效）或显式关闭下仍可见（形同虚设）
- **可观测面**：已定位——宿主能力管理器（`BaseAgent.getAbilityManager()`）与进程级资源管理器（`Runner.resourceMgr()`）
- 自动化：`e2e/deepanalyze/DaWorkflowGatingE2EIT`

### 067-S12：护栏 `actrule.yaml` 六件显式放行（静态）
- 可执行性/优先级：`runnable` / **P0**；形态 **`static`**；Oracle：Feature §2「六件在护栏规则中**显式放行**；新增编排工具必须同步护栏登记」
- **Given/When**：读 `common/agents/deepanalyze-java/engine/src/main/resources/governance/actrule.yaml`
- **Then**：`tools:` 节下六件**全部为 `allow`**（已预核实）；且该文件 `defaults: "*": ask` 为 fail-closed ⇒ 未登记的工具默认需确认，故"显式放行"是必需而非冗余
- **PASS**：六件均 `allow` **FAIL**：任一件缺失或非 `allow`（L2 §6 自陈"登记缺口曾是评审发现项"⇒ 有真实缺陷史，建议作 **gating**）
- 证据：`actrule.yaml` 相关行原文 + 六件逐项命中结果
- 自动化：`DaWorkflowGuardrailRegistrationTest`（静态）

### 067-S13：SSE 键外流（主断言）+ 事件发出面（`blocked`）
- 可执行性/优先级：主断言 `runnable` / **P0**；事件发出面 **`blocked`**（GAP-067-01/02）；层次 L1t（主断言）
- Oracle：Feature §2「订阅键随 `workflow_run` 出参外流」+ §3「订阅键 `workflow:{workflowId}`」+ §5.1.4「`workflow_id` 不出流的事件面等于不存在——**订阅键以外流为准**」
- **Given**：L1t 装配，一次成功的 `workflow_run`
- **Then（主断言，gating）**：出参 `workflow_id` 非空，且拼接后的订阅键 `workflow:` + `workflow_id` 形态合法（非空、无空白、可作 map key）
- **Then（增强证据，`blocked`，非 gating）**：起止事件实际发出、失败补发 failed `complete`、`complete` 带真实耗时——**无进程内观测点**：`DaPushEventBus.publish(key,…)` 在 `subscribers.get(key)` 为空时丢弃（`DaPushEventBus.java:45-46`），而 `workflowId` 在 run 内部生成、run 结束后才外流（`WorkflowRunTool.java:30-32` 自陈），`subscribe(key,…)` 需先知道 key，无通配订阅 API
- **PASS**：主断言成立 **FAIL**：`workflow_id` 缺失或为空（⇒ 违反 Feature §5.1.4「不出流的事件面等于不存在」） **blocked**：事件发出面（**不得**写成 FAIL——Feature §2 已明示本次运行不可达为当期口径与已知缺口）
- **下一项探针（可能把 `blocked` 升级为 `execution`）**：核实 `workflowId` 的生成规则（`DaWorkflowRunner.java:97` 附近）。**若可预测**（如递增序号 `wf-1`），则可预先 `DaPushEventBus.getInstance().subscribe("workflow:wf-1", …)` 从而在组件层直接观测起止事件与耗时 ⇒ GAP-067-01 可解除。若为随机 UUID 则维持 `blocked`。
- 证据：出参 `workflow_id` 原文 + `DaPushEventBus` 的源码依据（用于 `blocked` 的四项门槛留痕）
- 自动化：`DaWorkflowEventKeyTest`

### 067-S14：宿主不改编排语义（L3 与 L1 结构对照）
- 可执行性/优先级：**`blocked`**（P1+P4）/ **P0**；层次 L3；Oracle：Feature §6 第 2 条「宿主不得改变 FEAT-065/066 的执行语义——本特性只做装配与暴露」
- **Given**：**同一张** dag 图纸（与 FEAT-065 065-S22 共用），分别经 L1（`BlueprintScheduler` 直接执行、确定性执行体）与 L3（宿主 `workflow_run`、真实模型成员）跑
- **Then**：两侧的**结构**逐项一致——步数、每步 `member`、每步 `skipped`、登记序、`final_output` 来源（== 末步 output）；差异只允许出现在 output **内容**层
- **PASS**：结构逐项一致 **FAIL**：结构不一致（⇒ 宿主改了执行语义，违反 Feature §6 第 2 条） **INCONCLUSIVE**：主 Agent 未调用 `workflow_run`
- 证据：两侧结构导出 + 逐项 diff + Failsafe XML
- 自动化：`e2e/deepanalyze/DaOrchestrationRealMemberE2EIT`（与 FEAT-065 065-S22 **同一个类**，覆盖台账登记共担）

### 067-S15：事件契约不含无生产者的事件声明（静态反向断言）
- 可执行性/优先级：`runnable` / **P1**；形态 **`static`**；Oracle：Feature §3「契约**不声明没有生产者的事件**」+ §5.1.4「契约诚实：只声明已接线的事件，缺口标注为缺口」；L2 §2.5 自陈「!805 **删五件死契约**」
- **Given/When**：读 `da/compat/DaPushEvents.java` 的公开事件方法集合，与 Feature §3 声明的事件清单（`workflow_start` / `workflow_complete` 两件）比对
- **Then**：公开声明的工作流事件**恰好两件**，且每件都有生产者（`DaWorkflowRunner` 调用点可指认：`workflowStart` 与 `workflowComplete`）；不存在无生产者的死契约
- **PASS**：两件且有生产者 **FAIL**：出现第三件工作流事件声明，或某件无调用点（⇒ 死契约复活，正是 !805 修过的问题）
- 证据：`DaPushEvents` 的工作流事件方法清单 + 各自的调用点 `文件:行号`
- 自动化：`DaWorkflowEventContractStaticTest`（静态）

### 067-S16：真实 LLM 主 Agent 经会话触发 `workflow_run`
- 可执行性/优先级：**`blocked`**（P1+P4+P5）/ **P0**（L3 代表性的唯一承接）；层次 L3
- Oracle：Feature §1 面向角色「会话用户：用自然语言驱动多 Agent 图纸执行」+ §4「模板启动」场景
- **Given**：门控开启的 `deepanalyze` + 真实模型 + Redis
- **When**：经标准 A2A 入口发「请用 research-pipeline 模板分析：<问题>」（显式点名模板以提高触发率）
- **Then**：主 Agent **自主调用** `workflow_run`；返回 `status=completed`、`workflow_id` 非空、`steps[]` 条数与该模板图纸步数一致、`final_output` 非空
- **PASS**：如上 **FAIL**：工具被调用但契约不符（条数/字段/收口错位） **INCONCLUSIVE**：主 Agent **未调用**该工具（模型未配合——设计未承诺措辞→调用的确定性，需重试或强化语料，**不判产品 FAIL**）
- 证据：Failsafe XML + 会话事件流 + 工具返回原文
- **不应断言**：措辞与调用之间的必然性；**不得断言"检索发生了"**（`research-pipeline` 名字含"检索"，但 L2 §1.3 明示成员是裸 `ReActAgent`、无工具——"检索员不能检索"）；不断言输出内容含某关键词（L2 §1.3 自陈开发侧真机用例"判据偏弱、子串命中宽泛"，本期改用结构性断言）
- 自动化：`e2e/deepanalyze/DaWorkflowToolSessionE2EIT`

### 067-S27：`workflow_run` 工具调用超时——**对外承诺面**（L1t，2026-10-21 新增）

**为什么新增**：`docs@b4f45c06`（!343，提交 `3ccbad5d`）把这条**从"零记载"变成了明文承诺**——
Feature-067 §3 外部接口表新增 `deepanalyze.workflow.run-timeout-seconds` 行，§6 新增约束条明写
「workflow_run 的工具调用超时**属对外承诺面**：默认 **1800** 秒，可经该键配置（经 ToolCard `resilience.timeout_s` 声明进 core）；
core 叠加运行预算(FEAT-057)时实际上限取 `min(该值, 剩余预算)`——**配置键必须随本特性文档登记，不许先进实现后补文档**」；
L2-067 §6 配置表补全作用点链与边界。⇒ 按 SKILL「新输入改变范围/预期/Oracle 时返回审视阶段」，
新增目录条目 **REQ-052**（scope=in）与本场景。**它同时是 D3（`agent-solution#460`）的本体**：
我方原报「`WorkflowRunTool` 未声明 call timeout ⇒ 回落 core 硬上限 300.0 s ⇒ 两个 shipped 模板跑不完」，
现已源码级核实 r3 修好，但**尚无执行证据** ⇒ `#460` 仍未关，本场景就是它的取证口。

**Oracle 的三个来源必须一致（按 §7AP.8 硬门禁第②问，期望常量要指出它在当前基线上的出处）**

| 来源 | 原文 / 实测 |
|---|---|
| Feature-067 §3（`docs@b4f45c06`） | 「`deepanalyze.workflow.run-timeout-seconds` \| YAML 配置 \| workflow_run 工具调用超时(秒)，**默认 1800**」 |
| L2-067 §6 配置表 | 「默认 **1800**；作用点 `DaWorkflowRunner` 三参构造(`DaEngineConfiguration.java:162-164`) → `WorkflowRunTool` ToolCard `resilience.timeout_s`(`:109`)；core 只在**卡片未声明时回落默认 300**、叠加运行预算时取 min(`AbilityManager.java:1030-1034`)，**硬上限 3600**」 |
| 源码（`common@ce9019d86`，我方 2026-10-21 实测） | `DaProperties$Workflow:672` `private int runTimeoutSeconds = **1800**`；`DaWorkflowRunner:50` `DEFAULT_RUN_TIMEOUT_SECONDS = **1800**`、`:79` 两参构造委托三参、`:96` `Math.max(1, runTimeoutSeconds)`、`:105` `getRunTimeoutSeconds()`；`WorkflowRunTool:109` `.properties(Map.of("resilience", Map.of("timeout_s", runner.getRunTimeoutSeconds())))` |

三处一致 ⇒ **1800 是承诺值，不是我方推测**。

**Given**：以**生产口径**构造被测对象——`DaWorkflowRunner`（两参构造，走 `DEFAULT_RUN_TIMEOUT_SECONDS`）+
`WorkflowRunTool`（两参 `runner, baseRoot`，即 `DaWorkflowTools.attach:40` 的形态；由 `DaWorkflowTestSupport.newRunTool` 提供，
其构造方式受静态门禁 067-S24 看守）。**无需真机、无需宿主、无需容器与网络**（L1t）。

**When**：读该工具对外导出的 ToolCard（公开导出面，不用反射）。

**Then（主断言，三条）**

1. **卡片确实声明了 `timeout_s`**（键存在且非空）——这条最关键：L2-067 明写「core **只在卡片未声明时**回落默认 300」，
   所以"声明了"本身就是承诺的一部分，也是 D3 的直接反证；
2. **默认值 == 1800**（两参构造 / 未配置时）；
3. **可配置**：经 `DaProperties.Workflow.setRunTimeoutSeconds(n)`（或 `DaWorkflowRunner` 三参构造传入 n）后，卡片值 **== n**
   ——取 n = 900 与 n = 3600 两个点（一个低于默认、一个等于文档所述硬上限），证明它不是被写死的常量。

**明确不断言的内容（按承接方判定门禁与"不得由测试侧自行补写产品规则"）**

| 不断言项 | 理由 |
|---|---|
| `min(1800, 剩余预算)` 的钳制行为、剩余预算 ≤0 时不走钳制分支、**硬上限 3600** 的实际截断 | 全部是 **core 侧** `AbilityManager.java:1030-1034` 的行为，**不在本特性 SUT 内** ⇒ 承接方＝core（`agent-core-java`），SIT 不重复覆盖；已按 L2-067 的引用登记为承接方证据（源码级已核、执行级由 core 自己的测试承接） |
| "卡片未声明时回落 300" | 同上，属 core 行为；且本用例的**断言 1 已保证卡片声明了** ⇒ 该回落路径在本特性下不应被触发 |
| `DaWorkflowRunner:96` 的 `Math.max(1, runTimeoutSeconds)`（非法值收敛为 1） | 这是**实现事实、文档未承诺** ⇒ 按门禁**只登记不断言**。**若设计后续把它写成承诺**（例如"非法值拒绝"或"下限 1"），再补断言；在那之前断它就是我方自行加码 |
| shipped 模板在 1800 下**能跑完** | 需真机 + 真宿主，且当前会先撞 **D5**（成员继承 60 s 模型超时，`agent-solution#461` 未落地）⇒ 无法干净归因。本场景只证"承诺面已按文档声明"，**不证"模板跑得完"**；后者等 `#461` 落地后由 067-S16 / 065-S23 / 066-S16 一并回归 |

**主断言能否拦住目标故障（负向自检）**

| 目标故障 | 会不会红 |
|---|---|
| D3 回归：`WorkflowRunTool` 不再写 `resilience.timeout_s` | ✅ 断言 1 红（键不存在） |
| 默认值被改成 300 / 600 | ✅ 断言 2 红 |
| 配置键被写成死的（读不到 `DaProperties`） | ✅ 断言 3 红（改配置后卡片值不动） |
| 卡片声明了但值与 runner 不同源（写死 1800） | ✅ 断言 3 红（n=900 时卡片仍 1800） |

**层次与落点**：L1t 组件层。建议落点＝既有 `DaWorkflowRunToolContractTest`（run-48 已含 8 例）内新增 3 个方法
（对应三条主断言），**不新建测试类**——避免多一个类进矩阵组、也便于与既有 `workflow_run` 出参契约断言共用 fixture。
最终落点以 Java 实现时的 `DaWorkflowTestSupport.Fixture` 是否能直接给出 ToolCard 为准；若需要新的支撑方法，
必须同时受 067-S24（`DaWorkflowToolsWiringParityTest`）看守，防止 fixture 漂离生产装配口径。

**取证后动作**：本场景 PASS ⇒ REQ-052 由 `degraded` 改判 `covered`，并给 `agent-solution#460`（D3）
附验证说明后**申请关单**（不自行关）。

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| acceptance 仓 pom 缺 `deepanalyze-engine` 与 `agent-core-ext-orchestration` 依赖 | L1t 全部 10 个场景 + L3 | 增两条依赖 | 仅测试仓 | **已授权**（2026-10-15） |
| 被测制品未构建 | 同上 | 由 `common`@`53c1a174a` 源码 `mvn install`（`-Drevision=<X>`） | 构建动作 | **待授权** |
| 门控开启 | 067-S11 ②、S14、S16 | SUT 启动参数注入 `-Ddeepanalyze.workflow.enabled=true` | 仅测试侧 SUT 装配 | **已授权**（范围卡 §7.1 第 4 项） |
| `~/.llmrc` + Docker/Redis | 仅 L3 的 4 个场景 | 用户配置凭据；复核 `docker context ls` | 执行排期，**不改变任何判据** | **只有用户能做** |
| 新增测试包 `cases/component/orchestration/`（与 FEAT-065/066 共用） | L1t 落点 | 新建 7 个测试类 | 仅测试仓 | 属 Java 实现阶段，**需双文档确认后**才动 |

**不修改任何 fixture、不修改业务代码**：六件工具的依赖全部可经公开构造器注入（§0.1），无需改 `agent-solution`。

## 8. 自动化落点与精确执行规划

| 层次 | 包 | 测试类 | 覆盖场景 |
|---|---|---|---|
| component（L1t） | `cases/component/orchestration/` | `DaWorkflowRunToolContractTest` | 067-S01、S02 |
| component | 同上 | `DaWorkflowErrorSurfaceTest` | 067-S03 |
| component | 同上 | `DaTaskBoardToolContractTest` | 067-S04 |
| component | 同上 | `DaSendMessageToolTest` | 067-S05、S06 |
| component | 同上 | `DaSubagentTranscriptToolTest` | 067-S07 |
| component | 同上 | `DaWorkflowStatusToolTest` | 067-S08 |
| component | 同上 | `DaWorkflowTemplateResolutionTest` | 067-S09 |
| component | 同上 | `DaWorkflowTemplateAssetReconciliationTest#shippedTemplatesReconcileAcrossSources` | 067-S10（静态） |
| component | 同上 | `DaWorkflowTemplateAssetReconciliationTest#frozenBaselineDriftIsJudgedByDirection` | 067-S21（静态，测试侧加严） |
| component | 同上 | `DaWorkflowGuardrailRegistrationTest` | 067-S12（静态） |
| component | 同上 | `DaWorkflowEventKeyTest` | 067-S13 |
| component | 同上 | `DaWorkflowEventContractStaticTest` | 067-S15（静态） |
| e2e（L3） | `cases/e2e/deepanalyze/` | `DaWorkflowDefaultAssemblyNoOpE2EIT`（**已实现**，run-40 PASS） | 067-S23（承接 067-S11 的 L3 那一半；原设计的 `DaWorkflowGatingE2EIT` **从未实现**，已由本类承接，详见 §067-S23 的"范围缩减"登记） |
| e2e | 同上 | `DaOrchestrationRealMemberE2EIT`（与 FEAT-065/066 共用） | 067-S14、S16 |

Allure：类级 `@Feature("FEAT-067: 编排承接——会话内工作流工具组")`，方法级 `@Story("<用例 ID>: <描述>")`，`@Tag("component"|"e2e")` + `@Tag("feat-067")`。

```bash
# 预检
pwsh -NoProfile -File <skill>/scripts/validate-project.ps1 -Phase execution -OutputPath <batch>/gate-output/validate-project-execution.json
# L1t（无凭据、无容器）
mvn -B -Dmaven.repo.local=<工作区>/04-Environment/m2repo -Dtest='com.huawei.ascend.sit.cases.component.orchestration.Da*Test' test
# L3
mvn -B -Dmaven.repo.local=<工作区>/04-Environment/m2repo -Dit.test='DaWorkflowDefaultAssemblyNoOpE2EIT,DaOrchestrationRealMemberE2EIT,DaCrossValidationRealMemberE2EIT,DaWorkflowGuardrailRuntimeE2EIT,DaWorkflowMessageIsolationE2EIT,DaWorkflowSessionIsolationE2EIT' verify
```

## 9. 状态汇总与退出标准

- 状态链接 `FEAT-067/FEAT-067-动态状态页.md`。本轮 16 个场景全部 `not-run`（S11/S14/S16 为 `blocked`），`automation_status: design-only`。
- ISSUE 状态：#33 `已创建`。特性收口：**`open`**。
- **退出标准**：24 条 `in` 条目各获适当形态的实际证据；GAP-067-01~06 全部升级或经用户书面裁决延期。**REQ-018 若最终只有降级形态，则 FEAT-067 不得声明 `closed`**（RULES 第 11 条）。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| NC-01 | 事件发出面记 `blocked` 而**非产品 FAIL** | ① 实现缺陷（事件根本没发）；② 设计已明示的当期口径 | Feature §2 同格既写 MUST 又写「本次运行的起止事件落在空键被丢弃——事件面对本次运行**不可达**」；§5.1.4「当前版本事件面的意义是**接线就位与键外流**」；**代码 javadoc `WorkflowRunTool.java:30-32` 自陈同一事实** ⇒ 是设计口径，不是缺陷。按门禁"缺少可观测接口时标记 `blocked`，不得自行伪造前置条件" | 事件是否**真的**被发出（无观测点） | 核实 `workflowId` 生成规则；若可预测则预订阅 `DaPushEventBus` |
| NC-02 | `delegate_task` 的执行体语义**不外问设计**、按 L2 口径构造 | ① Feature 未定义 ⇒ 必须问；② L2 已给唯一口径 | L2 §2.2/§2.6 明确：委托执行体 = 裸 `ReActAgent`、宿主模型口径、`goal` 只走 `subGoal` 位、`userInput` 恒空串、单点为 `delegateExecutor`；且已核实 `DelegateTaskTool(DaTaskBoard, Function<String,String>)` 的委托函数**可注入** ⇒ 测试可完全控制。文档纪律问题已单独提 ISSUE #33 D1，不阻塞用例 | 无 | — |
| NC-03 | `send_message` 的**来源可辨**不作为断言 | ① 广播应能区分来源；② 设计未承诺 | L2 §2.3 明示「`from` 为预留字段（schema 未暴露，**实收恒空串**）」⇒ 接收方无从区分来源。要求它会把符合设计的实现判 FAIL | 无 | — |
| NC-04 | "成员运行中互相留言"**不构造用例** | ① 应测但环境不具备；② 设计明文 OUT | Feature §2 OUT「成员带工具执行 \| 工作流成员默认无工具（裸执行体）」；§5.1.3「工作流成员默认无工具、**运行中发不出留言**，当前可达形态 = 主 Agent 在运行间隙留言」；L2 §1.3「检索员不能检索」 | 无 | — |
| NC-05 | 模板**内容**正确性不测 | ① 应测；② 归模板库特性 | Feature §1「不包含…**工具本体之外的模板内容**（归模板库特性）」；§2 OUT「动态模板工具面入口」；范围卡 §4.1 承接方 = 后续模板特性（三份文档独立指向） | 无 | — |
| NC-06 | `parallelism` 非法值致宿主启动失败**不提单、不 handoff** | ① 产品缺陷；② 合理 fail-fast 但可诊断性弱 | 已读码核实：`DaWorkflowRunner.java:53-55` 抛 `IllegalArgumentException`、`DaProperties.java:668-670` 无 `@Min(1)`、`DaEngineConfiguration.java:183-185` 在 Bean 方法内直接 `new` ⇒ fail-fast 属合理设计；且**无任何权威文档承诺配置非法值的行为或错误形态** ⇒ ISSUE 四项准入第 1 项不满足。另：范围外三关核对亦不通过（责任边界/默认态/所有权） | 若将来某特性承诺"配置面给可读错误"则重新判定 | 无（DFX 观察项留档于 ISSUE 台账 §3.1） |
| NC-07 | 067-S01 **不断言 `steps[]` 含 `branch_id`** | ① 应断言以逼出缺口；② 缺口另行分账 | 该缺口已由 `DV067-03` / ISSUE #33 V3 独立分账；在本用例里断言会把"已知未修缺口"混入契约用例的 FAIL，违反"一个问题一个 ISSUE、不双重归属" | 无 | — |

## 最小可执行例子

### 例 1：L1t 组件层·工具契约（代表 067-S01~S10、S13、S15，共 12 个场景）

- **真实输入声明**：与模型实际传入完全相同的 `Map`：
  ```java
  Map<String,Object> args = Map.of(
      "template", "research-pipeline",
      "question", "U-067-S01-" + uuid8);
  ```
  装配：`new DaWorkflowRunner(memberFactory, 4)`，其中 `memberFactory` 为测试提供的
  `memberSpec -> (userInput, subGoal) -> "M-067-S01-" + memberSpec.id() + "-" + uuid8`
- **拟发请求**：`new WorkflowRunTool(runner).invoke(args, Map.of())`
- **入口与必要资源**：入口 = 六件工具的公开构造器 + `invoke`；必要资源 = **仅 P1+P2**（制品 + pom 依赖）。**无需**凭据、容器、网络、数据库、宿主进程
- **可观察输出**：返回对象的 `status` / `workflow_id` / `final_output` / `steps[]`（或 `error`）；各成员执行体实收的 `userInput` 与 `subGoal` 原文；`DaTaskBoard` 快照；`TraceForest` 分支
- **证据落点**：`target/surefire-reports/TEST-…Da*Test.xml` + Allure 附件（返回对象序列化、实收输入原文）+ `<batch>/evidence/<run-id>/`
- **代表性**：材料来源 = Feature §4「模板启动」场景行（"会话中说'用研究管道模板分析 X' → 主 Agent 调 `workflow_run(template=research-pipeline)`"）与 §3 的入参清单；保留的真实使用条件 = **入参形态与模型传入的 `Map` 完全一致**、模板名来自实装八套、图纸经真实装载与调度；关键差异 = 成员是确定性执行体、且**不经过主 Agent 的自主决策**（那一层由例 3 承接）；替代办法 = 例 3 用真实模型补"主 Agent 能否够到工具"
- **故障与主断言**：若 `workflow_run` 出参缺 `workflow_id` → 067-S13 主断言失败（且违反 Feature §5.1.4「不出流的事件面等于不存在」）；若图纸未优先于模板 → 067-S02 实例 ③ 的"条数 == 1"失败；若错误以异常穿会话 → 067-S03 的"五态均返回 `error` 对象"失败；若广播漏达未定向过的成员 → 067-S06 失败；若模板名未做穿越防护 → 067-S09 反例失败；若护栏漏登记 → 067-S12 静态断言失败

### 例 2：静态合同核对（代表 067-S10、S12、S15）

- **真实输入声明**：`resources/workflow-templates/*.yaml`、`resources/governance/actrule.yaml`、`WorkflowRunTool.java:44-46` 的 `SHIPPED_TEMPLATES`、`da/compat/DaPushEvents.java` 的公开事件方法
- **拟发请求**：无运行时请求；测试内解析资源与源码文本
- **入口与必要资源**：源码/资源检出（按 commit，不硬编码本机路径）；**无**制品/凭据/容器
- **可观察输出**：四方模板清单的集合 diff；`actrule.yaml` 中六件的级别值；`DaPushEvents` 的工作流事件方法清单与各自调用点
- **证据落点**：Surefire XML + 用例内附命令原文与命中计数
- **代表性**：三项均为设计**本来要求静态验证**的条目（Feature §3「实装八套（**对账基准**）；工具描述清单必须与资产对账一致」、§2「护栏对账」、§3「契约不声明没有生产者的事件」）⇒ 静态即充分，不属降级
- **故障与主断言**：若模板资产与工具描述清单漂移 → 067-S10 四方集合不等而失败（L2 §6 明写"漂移即改"）；若护栏漏登记某件 → 067-S12 失败（L2 §6 自陈"登记缺口曾是评审发现项"）；若无生产者的死契约复活 → 067-S15 失败（!805 曾删五件）

### 例 3：L3 端到端·真实 LLM 主 Agent（代表 067-S11、S14、S16）

- **真实输入声明**：一句自然语言「请用 research-pipeline 模板分析：<问题>」（显式点名模板以提高触发率）；对照装配：显式 `enabled=false` vs 缺省（开启）
- **拟发请求**：经标准 A2A 入口向 `SutStack.agent("deepanalyze")` 发起会话
- **入口与必要资源**：P1 制品（`deepanalyze-engine:<rev>:exec`）+ P4（Redis 容器 + 模型凭据 `LLM_API_KEY`/`LLM_API_BASE`/`LLM_MODEL`/`LLM_PROVIDER`，**只写变量名**）+ P5 门控
- **可观察输出**：会话事件流；`workflow_run` 工具返回原文；主 Agent 工具集（可观测面待 GAP-067-05 核实）；Agent Card
- **证据落点**：`target/failsafe-reports/TEST-…E2EIT.xml` + 出参原文附件 + `run-metadata.json`
- **代表性**：材料来源 = Feature §1 面向角色「会话用户：用自然语言驱动多 Agent 图纸执行」与 §4「模板启动」「门控关闭」两行场景；保留的真实使用条件 = 真实模型自主决策、真实宿主装配、真实 A2A 入口、真实 Redis；关键差异与限制 = **模型是否调用工具不确定**（设计未承诺措辞→调用的确定性）⇒ `INCONCLUSIVE` 与 `FAIL` 必须分开记；**不得断言"检索发生了"**（成员无工具）
- **故障与主断言**：若门控形同虚设 → 067-S11 的"默认装配下六件不可见"失败；若宿主改了编排语义 → 067-S14 的"与 L1 结构逐项一致"失败；若工具契约在真实会话下变形 → 067-S16 的"条数与图纸步数一致 + `final_output` 非空"失败

## 两轮自审记录

**第一轮（真相源、范围、责任边界、黑盒性、Oracle）**

- 真相源：预期取自 Feature 与 L2 @ `561f02dd`；代码事实（构造器注入点、`steps[]` 字段、`actrule.yaml` 内容、`DaPushEventBus` 语义、`WorkflowRunTool` javadoc）只用于**层次划分**、`blocked` 的四项门槛留痕与静态断言，**未回写为设计预期**（NC-01/NC-02/NC-06 明确记录）✓
- 范围：24 条 `in` 全部有承接；5 条 `out` 有承接方；模板内容、异步化、逐成员事件、成员带工具均未纳入 ✓
- 责任边界：不改业务代码、不改 fixture；唯一改动是测试仓 pom 依赖 + 新建测试包（已授权 / 已登记）✓
- 黑盒性：L1t 经公开构造器 + `invoke(Map,Map)`，入参形态与模型一致；注入 `memberFactory`/`delegate` 是 L2 §2.6 明示的生产注入点，**不是 Mock LLM**（用户已确认该判断）✓
- Oracle：16 个场景逐条给 PASS/FAIL/INCONCLUSIVE；6 个缺口登记 `resume_when`；7 条 NC 记录负结论归因；8 处"不应断言"防止把未承诺项当 FAIL ✓

**第二轮（需求处置、G/W/T、SUT 适用性、证据、状态、自动化落点）**

- 需求处置：29 条逐条有 `disposition`（含 1 组 `unassigned`、2 条 `partial`、4 项 `blocked`）✓
- G/W/T：16 个场景全备；参数实例数明确（S02 三实例、S03 五实例、S08 三实例、S09 八实例）✓
- SUT 适用性：§2 三行结论 + 依据；**§0.1 的层次重划有源码逐条挂点**，且明确声明"不是为了绕开凭据而降低判据"（对齐 SKILL.md「环境缺口不得反推 Oracle」）✓
- 证据：L1t 用 Surefire XML；L3 用 Failsafe XML；静态用命令原文 + 命中计数；**不采信开发的"离线账本"**（L2 §1.3/§7.4 自陈真机 e2e CI 不跑）✓
- 状态：四种状态分开；设计期不填执行数字；收口 `open`；REQ-018 的降级预案已写明"不得记为已覆盖、特性不得 closed" ✓
- 自动化落点：13 个测试类（其中 1 个与 FEAT-065/066 共用）+ 包路径 + Allure 标注 + 分层执行命令 ✓

**反向挑战一个关键假设**

- **假设**：L1t 用 `invoke(Map, Map)` 直接调工具，等价于"主 Agent 在会话里调用该工具"。
- **条件变化及依据**：把调用方从**测试直接构造的 Map** 换成**真实模型自主生成工具调用**（依据：Feature §1 面向角色「会话用户：用自然语言驱动」、§2「六件统一门控…门控关闭时工具**不出现在主 Agent 工具集**」——"工具集"这个面只在真实主 Agent 装配下才存在；`review-and-design.md`「代表性」要求检查"输入来源、执行方式与外部依赖"）。
- **检查结果**：**发现两处 L1t 覆盖不到的东西，已由 L3 承接**：
  ① **门控与工具集可见性**（REQ-017/018）——`attachIfEnabled` 作用在 `DeepAgent` 上，L1t 直接 `new` 工具**绕过了挂载环节**，因此门控在 L1t 完全测不到 ⇒ 已由 067-S11（L3）承接，并给出可观测面待核实与降级预案；
  ② **模型能否够到工具**——L1t 的入参是测试构造的，无法证明"说一句'用研究管道模板分析这个'就会触发 `workflow_run`" ⇒ 已由 067-S16（L3）承接，并把"模型未配合"明确记为 `INCONCLUSIVE` 而非 `FAIL`。
  另发现一处**L1t 反而更强**的地方：护栏登记（REQ-019）在 L3 下只能间接观察（工具被拦截），而静态对账能直接证明"六件显式 `allow`"⇒ 保留静态形态，不用 L3 替代。
- **不再扩展**：本轮只挑战这一项最关键假设；未发现其他新的具体风险，不新增自审轮次，也不把一次挑战解释为已穷尽未知问题。

---

## 11. 新基线更正（**权威**；与 §1/§4/§6 冲突时以本节为准）

> 触发：docs `561f02dd` → **`9ab89fc8`**、agent-solution `53c1a174a` → **`f9da5b54f`**（2026-10-15 19:21/19:29），
> 开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。本节把 §6 各用例中**已作废或需升级**的期望值集中更正，
> 避免实现 Java 时读到旧文。§6 原文按 RULES 保留不改写（旧基线下的审计痕迹）。
> 完整依据见 `../03-增量审视与基线刷新记录-20261015.md`。
> **本节更新后，三份设计里已无"待核实/待裁决"类的设计侧阻塞；剩余阻塞全部是环境类（凭据 / redis 镜像），按用户 2026-10-15 指示统一到最后解决。**

### 11.1 §1「未决问题」表的现状（7 项 → 1 项待开发答 + 1 项新发现待授权 + 环境类）

| 原未决项 | 现状态 |
|---|---|
| `DV067-01` 事件面达成度（L2 虚标 `✅已有`） | ✅ **已答**：L2 §1.2 改为「**部分达成**…本次运行订阅可达性未达成——挂 **1130 异步化/缓冲评估项**」；Feature §2 增「**当期验收出口**：键外流+失败补发+真实耗时；本次运行的订阅可达性…**不作为当期验收内容**」；L2 §7.2 改为「起止事件成对**仅在异步化/缓冲落地后验收**」 |
| `DV067-02` "事件流不得悬挂"被收窄 | ✅ **已答（逐字采用本单建议的方案②）**：Feature §5.1.4 增「失败补发义务**以起事件已发出为前提**…**无起即无悬挂义务**；止事件补发**覆盖任意失败形态**（含可预期失败族之外的逃逸）」；L2 §2.5 改为 **finally 口径**并注明「**#33 V2 方案②**」；代码 `DaWorkflowRunner` 已加 finally 兜底（复核 4 处 `finally` 命中） |
| `DV067-03` `branch_id` 无来源 | ✅ **已答**：Feature §2 出参增「**留痕分支标识 branch_id**——subagent_transcript 查询入参的来源」；L2 §2.1 同步并注明「**转测评审 #33 V3**」；代码已透出（复核 4 处命中） |
| `L067-02` 装配路径 / 跨运行留言 | ✅ **已答**：Feature §4 前置条件改为「**同一任务(Task)内**的两次工作流运行之间」+ 新增口径边界「宿主按 **per-Task 工厂装配**，森林与消息区按实例自持——**跨 Task 留言不承诺**」⇒ 测试侧原选的"最保守形态"即设计口径 |
| `L067-01` catch 面外失败能否构造 | ✅ **已答**：面外逃逸**同样补发 failed**，且「UT 锁定**面外异常原样逃逸不吞不换型**」⇒ 可用"成员执行体抛未枚举 `RuntimeException`"构造 |
| `Q067-D3` 门控的工具集可观测面 | ✅ **已答（测试侧读码自答）**：`DaWorkflowTools.attachIfEnabled`（`:37-50`）在 `!enabled` 时**直接 return**；启用时经 `ToolMount.mount`（`da/tools/ToolMount.java:32-36`）写入 **`core.getAbilityManager()`** 与 **`Runner.resourceMgr()`** ⇒ 两个可查面。`AgentCard.builder()`（`DaEngineConfiguration.java:138`）只设 id/name/description、**不含 tools** ⇒ Card 不是可查面 |
| `D1` `delegate_task` 执行语义只在 L2 | ✅ **已答**：Feature §2 已回写「裸 `ReActAgent`（宿主模型口径，温度 0.3/maxTokens 4000）、任务目标只走 `subGoal` 位、`userInput` 位恒空、执行体无工具」 |
| `L067-04` 真机用例判据偏弱在哪 | ⚠️ **仍未答**（已在 #33 评论催答）。测试侧不等答复：067-S16 已按**结构性断言**设计，不采用宽泛子串命中 |
| **新发现** | 🔶 Feature 新写的当期验收出口「键外流 + **失败补发** + **真实耗时**」中，**后两项在开发与测试两侧都无可观测面**（`workflowId = "wf-" + System.nanoTime()` 不可预知；`DaPushEventBus.publish` 无订阅者即丢弃、无通配订阅 API；**开发的 UT 也不观测事件面**——`engine/src/test/` grep 命中 0）。三个处置选项见 `../03-增量审视与基线刷新记录-20261015.md` §5B。**建议追评到 #33，等用户授权**（本轮未做任何远端写） |

### 11.2 §6 各用例的期望值更正

| 用例 | §6 原文（旧基线） | **更正为（新基线，权威）** |
|---|---|---|
| **067-S01** | `steps[]` 四字段（id/member/skipped/output）；"不应断言 `steps[]` 含 `branch_id`" | `steps[]` **五字段**：id/member/skipped/output/**branch_id**。原"不应断言 branch_id"的告诫**作废**——现在**必须**断言其非空（见 067-S07） |
| **067-S05 / S06** | "同一 Task 内两次运行"是**测试侧选的最保守形态**（因装配路径未答）；跨 Task 形态 `unassigned` | "同一 Task 内"已是**设计口径**（Feature §4 明文）；**跨 Task 留言属设计明确不承诺 ⇒ 按范围外处理**（承接方 = Feature §4 该条口径边界），不再是 `unassigned` |
| **067-S07** | "按 `branch_id` 查询"子断言 `blocked`，只保留 `roots` 列举为 gating | **两者均为 gating**：断言 ①`steps[].branch_id` 非空；②用该值调 `subagent_transcript` 能取回对应分支详情（子分支/回溯路径/深度）；③`branch_id="roots"` 列出全部执行根 |
| **067-S11** | 层次 L3；"可观测面待核实"；附**降级预案**（若无对外可查面则降为配置断言，REQ-018 不得记已覆盖） | **降级预案作废**。可观测面已定位 ⇒ **主断言**：门控关（默认）时 `BaseAgent.getAbilityManager()` 与 `Runner.resourceMgr()` **均查不到六件**；门控开时**均查得到**。层次可为 L1t（若能不接真实模型构造 `BaseAgent`/`DeepAgent`）或 L3（若不能）——这是 Java 实现阶段的唯一待确认点，**不影响判据** |
| **067-S13** | 主断言 = 键外流；事件发出面整体 `blocked` | 拆三段：① **键外流 = gating**（`workflow_id` 非空且形态匹配 `workflow:.+`）；② **"本次运行订阅可达性"= OUT**（Feature 已明文挂 1130 评估项，**不再是缺口**）；③ **失败补发 + 真实耗时 = 仍 `blocked`**（无可观测面，四项门槛留痕齐备）。**新增可执行的替代断言**：面外异常场景断言「异常**原样上抛**、不被吞、不换型」——这是 L2 §2.5 与开发 UT 都承诺且**可观测**的行为，作为 finally 兜底未被绕过的**间接**证据，并须注明它**不等于**"事件已补发" |
| **067-S03** | 装载失败例断言"工具级 error 含定位 + 无任何事件产生"，并登记 `DV067-02` 待裁决 | 待裁决已消除：Feature §5.1.4 明文「无起即无悬挂义务」⇒ 该断言**升为 gating**，不再是待裁决项 |
| **067-S16** | `blocked`（制品 + 凭据 + 容器） | 制品 ✅ 已就绪（`deepanalyze-engine:0.1.3-feat065067-r2:exec`，`Start-Class` 已核）；仍 `blocked` 的只剩 `~/.llmrc` + redis 镜像，**统一到最后解决** |

### 11.3 缺口状态（与 §4.1 一致，此处为速查）

GAP-067-03/04/05 **关闭**；GAP-067-02 **部分关闭**（异常传播可测并升 gating，事件补发仍不可测）；GAP-067-01 **收窄且性质改变**（"订阅可达性"转为 OUT；"失败补发/真实耗时"仍 blocked，并带出 §11.1 的新发现）；GAP-067-06 仍 `blocked`（纯环境）；**新增 GAP-067-07**（真机判据偏弱，待开发答）。
⇒ **FEAT-067 的 10 个 L1t + 3 个静态场景已无设计侧阻塞**；仅 3 个 L3 场景受环境阻塞。


### 11.4 2026-10-21 三轮追评裁决落地（**权威**；与 §1/§4/§4.1/§6/§11.1~11.3 冲突时**以本小节为准**）

> 触发：docs `1c0f4594` → **`b4f45c06`**（!343，关键提交 `3ccbad5d`「转测三轮追评裁决——run-timeout 登记/挂点随行/容量幂等边界/分支标识形态」）；
> 责任人 2026-10-21 09:47 在 `docs#31`/`#32`/`#33` 各答一条（comment `194890332` / `194890340` / `194890365`，
> 原文已固化存证 `../issues/rulings/20261021-三轮追评答复原文.md`，sha256 `d10ed32d42dbd0fc…`）。
> **制品未变**（仍 `0.1.3-feat065067-r3` / `common@ce9019d86`）⇒ run-48/49 的 XML 结论**不作废**；
> 但两份冻结 run-spec 的 6 份文档哈希已过期 ⇒ **下一轮矩阵必须重冻到 `b4f45c06`**，否则 Closeout 的 `file-hash` 检查必红。
> §11.1~§11.3 与 §4/§4.1 均为**历史快照**（§4 矩阵表头还写"29 条"、§4.1 还写 r2 制品与"只等 Java 实现授权"），按 RULES 保留不改写。

**当前权威数字**：目录 **55** 条（in 45 / out 10）；场景 **27** 个（execution 22 / static 5）；
覆盖台账 **55 行 = 34 covered / 11 degraded / 10 out-of-scope**；开放缺口 **11** 个。

#### (1) 新增两条目录条目（分母 53 → 55）

| 条目 | scope | 内容 | 承接 |
|---|---|---|---|
| **REQ-052** | **in** | `deepanalyze.workflow.run-timeout-seconds` 属**对外承诺面**（默认 1800、经 ToolCard `resilience.timeout_s` 进 core；core 只在卡片未声明时回落 300、叠加预算取 min、硬上限 3600） | **新场景 067-S27**（见 §6，L1t 组件层）；行状态 `degraded`、`owner=测试侧`、gap `GAP-067-RT-REQ-052` |
| **REQ-053** | **out** | 留言来源归因：`WorkflowMessage.from` 为预留字段**恒空串** ⇒ 同会话内留言不可归因（跨会话已由 `DaOrchestrationScope` 分区隔离） | 承接方＝实现侧后续小改（工具调用处填 caller 标识），登记入 1130 批；`scope_decision` 绑裁决存证件 |

#### (2) 判据改写：067-S17 / 067-S18（REQ-047 容量、REQ-048 幂等）

裁决到手 ⇒ **阻塞性质从「等外部裁决」变为「等测试侧实现判据」**，两行仍 `degraded`、`owner` 已改为**测试侧**。

| 场景 | 原判据（作废） | **新判据（按裁决口径）** |
|---|---|---|
| **067-S17**（REQ-047 容量） | 把"任务板终态无界累积、500 个任务无一淘汰"当**疑似缺陷**取证 | 断 **①轨迹面有界**：`RunTranscripts` 按 owner 分区且**每 owner 留存上限生效**（超出后最旧运行不可查、当前运行仍可查）；**②"重启即清"是已明示边界**（任务板/收件箱**当期不承诺**上限与淘汰，挂 1130）。⇒ **不断**任务板有上限、**不判**无界累积为缺陷。裁决原文：「L0-constraints:35 的当期满足面 = 轨迹有界 + 重启即清的明示边界，REQ-047 可按此口径写判据」 |
| **067-S18**（REQ-048 幂等） | 把"同参连调两次得两个 `workflow_id`"当**疑似缺幂等保护**取证 | 断 **重复调用 = 独立新执行**（各自生成标识）**且四类副作用各自可经公开查询面取回**：留痕累积→`subagent_transcript`、任务登记→`workflow_status`/任务板、消息投递→`send_message` 收件箱、token 消耗→（**若不可观测则只登记不断言**，需先核实导出面）。⇒ **不断**幂等键存在（裁决：不支持，挂后续版本）。裁决原文：「重复调用 workflow_run/delegate_task=独立新执行(各自生成标识;副作用=留痕累积/任务登记/消息投递/token 消耗,**均为可审计留痕**)…满足『留下审计证据』的当期兑现面」 |

⚠️ 两场景原有的 **run-21 特征化证据保留**（不删），但**定性改写**：由"疑似缺陷"改为"**符合已明示的当期边界**"，作为对照留档。
⚠️ 责任人另确认「**同会话内重复调用的幂等保护不因 `DaOrchestrationScope` 关闭——你们的判断正确**」⇒ 不得把跨会话隔离当成同会话幂等。

#### (3) 改判 out-of-scope：REQ-050（067-S20）+ D7 结案

**DFX-001:69 裁 (b) 范围外**：`tool.*` span 是 **agentteams 运行时路径专属**（`ObservabilityRail:306` 发射、`:95-104` 要求 team span 前置），
DA 宿主基座（orchestration + reactrails.loopforest）与 agentteams 是**不相交的两条运行时**（责任人明写「你们 grep 命中 0 的取证正确」）。
DA 宿主的工具观测面由 **reactrails 观测体系 + 编排留痕（branch_id / 留痕森林）** 承接。
⇒ **REQ-050 改判 `out-of-scope`**（承接方＝agentteams 运行时 + DFX-001 文档 scope 登记）；**067-S20 不需实现**；
**D7 确定不提单**（作用域既判范围外，目标仓与缺陷类型都无从成立——我方此前"未提单、等裁决"的处置正确）。
⚠️ 连带：`:63`/`:67`/`:68` 同族 MUST 一并按同口径标注适用域 ⇒ FEAT-065 的 **REQ-060 依据文字已改写、`covered` 判定不变**（见 FEAT-065 设计细档 §11.4）。

#### (4) D8（`agent-solution#457`）定性为 **(a) 文档已承诺、制品未做到**

裁决 1 明写「`r<n>/b-<n>` 是对外承诺的 branch_id 形态…**编号值不透明**（不承诺连续/起始值/跨 owner 可比较）；
不同运行/不同会话的同号分支**不再碰撞**」，并点名「**067-S07 的判据可按带前缀形态改写**」。
我方据此**改写并刻意收窄**判据（只断①形态匹配 `r\d+/b-\d+`、②两次运行 4 步应互不相同；**不断**连续性/起始值），
run-54 复跑**仍 FAIL**：PROBE 实收 `branchIds=[b-2, b-3, b-4]`（无前缀、4 步只 3 个不同值）。
根因精确到一行：`RunTranscripts` 有 `qualify()`（`:174-175`）、`:23` 类注释明写「never collide」，
但 **`OutputArchive:87` 是 `step.put("branch_id", outcome.branchId())`，未调 `qualify`**。
⇒ REQ-010/011/043 **维持 `degraded`**、`#457` **不关**；同轮 D1 的 L1t 用例 PASS ⇒ 已固定为修复前对照基线。
**067-S07 的判据以本轮改写后的版本为准**（§6 原文按 RULES 保留）。

#### (5) 067-S23 期望值更正 + fixture 两项撤销

- **期望值**：门控开态 `tool_count` 由 r2 的 **33** 变为 r3 的 **34**（run-44/45/51 三轮一致）⇒ 判据由 `33−6=27` 改为 **`34−6=28`**。
  run-51 首跑 FAIL（`expected 27 but was 28`）是**我方常量过期、不是产品缺陷**；三条排除依据见 §7AP.3，run-52 修正后 PASS。
  ⚠️ 常量旁已注明"**随制品基线漂移，换基线必须重测**"。
- **fixture 撤销两项**（`application-openjiuwen.yml` 我方两个 SUT 条目）：
  ① `openjiuwen.service.middleware.checkpointer.type: in_memory` **已撤**——D4 已修（`CheckpointerFactory` SPI 发现改用类自身类加载器，
  随 core `!400`/合并点 `14516e4e8` 落地；我方 `git merge-base --is-ancestor 14516e4e8 f7b76133` → **真** 独立核实），
  **run-53 容器实证通过**：宿主以默认 redis 起成功、`Unsupported checkpointer` 0 命中、
  🔒 正向对照＝redis 落下本轮真实键 `sess-067-s23-02688395:agent:deepanalyze-agent:agent_state_blobs`(+`_dump_type`)、`DBSIZE=2`
  ⇒ **§3/§7 里"checkpointer in_memory 绕行"这条代表性偏差登记作废**，端到端结论不再带该保留意见；
  ② `deepanalyze.model.timeout.stream-idle-seconds: 1800` **已移除**——责任人点名：FEAT-058 solution 侧接线后
  显式字段越界（`stream-idle ∉[300,900]`）会抛 `IllegalArgumentException`（**启动/首次调用即拒**）⇒ 属定时炸弹。
  **现存唯一已登记偏差只剩 `max-iterations: 4`**（生产默认 15），`#461` 落地后撤回并重跑。

#### (6) 仍未裁、判据不得改写的一项：`MEMBER_TOOLS`（`docs#37`，open）

Feature-067 当前 HEAD 上**三处**仍写"成员无工具"（`:50` OUT 表、`:95` 可达性口径、`:123` §5.2 边界「检索等能力**挂接待裁决**」），
而实现侧 `DaWorkflowRunner:52-54` 的 `MEMBER_TOOLS` 给成员与委托执行体挂了 **13 件只读工具**（`MEMBER_MAX_ITERATIONS = 16`）。
⇒ **REQ-033（067-S04）维持 `degraded`**，`resume_when` = 设计侧书面裁决 **且** L3-C 执行取得判定，两者缺一不得改判 covered。
⛔ **在裁决前，067-S04 的判据两个方向都不得改写**：既不能按文档断"执行体无工具"（会与实现相反、产生假缺陷），
也不能按实现断"挂 13 件只读工具"（会把未经裁决的实现事实当成承诺）。**两边相反时判哪边都是猜。**
（我方已于 2026-10-21 在 `docs#37` 追评 comment `194922282`，并指出 **L2-065 `:91`/`:123` 与 `docs#31` 新裁决自相矛盾**。）

#### (7) 记账口径定案：`branch_id` 形态**不在 FEAT-065 另立条目**

用户 2026-10-21 定案（§7AW.2）：065/066/067 同属本批，该承诺的**被测代码在 FEAT-067 的工具出参面**
（`WorkflowRunTool` → `OutputArchive:87`），FEAT-065 侧只有 `RunTranscripts`/`TraceForest` 的登记语义 ⇒
由本特性 **REQ-010/011/043（067-S07）独家承接**，不两边各立一行（否则 D8 一个缺陷记两次、分母虚增、修好后要改判两处）。
交叉关系记在 FEAT-065 REQ-061 的 `out_reason` 与两份目录的 `completeness_audit.ruling_refresh_2026_10_21`。
