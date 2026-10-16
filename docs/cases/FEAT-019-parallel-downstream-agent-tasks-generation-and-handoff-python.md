---
feature_id: FEAT-019 (Python B1-only)
feature_title: 智能体生成并行的下游智能体调用委托 —— Python 单载体（deep-research demo）覆盖
sut: multi-deep-research-demo-python（deep-research:18090 + search:18091 + verify:18093；dev 团队通过 scripts/smoke_real_llm.py --serve-only 独立托管）
scope: 本档只覆盖 Python 本轮迭代产物 —— multi-deep-research-demo-python。edpa-plan-agent（B2 载体）本轮 Python 未迭代，B2 线在 Python 侧不可用。B1 单载体承担 FEAT-019 全部可测 MUST；不可测者显式声明并挂 demo 需求
status: designed
owner: TBD
tags: [integration, feat-019, parallel, a2a, python-demo]
depends_on:
  - Python demo 迭代（详见 §3 "Demo 需求清单"），未落地前对应用例挂 @Disabled + 需求编号
  - dev 团队 --serve-only 已托管 3 个 agent；SIT 侧 remoteAgent（地址-only）接入
related_docs:
  - Python 侧 L2 LLD（主要对标）：`openJiuwen/docs` 仓 `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-019b-parallel-downstream-agent-tasks-generation-and-handoff.md`（`feature_id: Feat-Func-019b`，`lang: python`，2026-09-09 新增）
  - 顶层特性规格：`openJiuwen/docs` 仓 `develop/02-features/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff.md`（权威 CL-xxx 条款来源）
  - Java 侧 L2 LLD（对比参考）：`openJiuwen/docs` 仓 `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-019-parallel-downstream-agent-tasks-generation-and-handoff.md`（Python 侧按「有 Java 实现就遵从 Java」据以核对）
  - Java B2/B1 全线设计（本仓）：`docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff.md`
  - B1 单载体现状：`docs/cases/deepagent/DA-09-parallel-search-comparison.md`
---

# FEAT-019 —— Python 单载体（B1）覆盖设计

> **对标 L2**：本档的 L2 对标基准是 `Feat-Func-019b`（Python 版 L2 LLD，`openJiuwen/docs` 仓 2026-09-09 新增，`feature_id: Feat-Func-019b`，`lang: python`），不是 Java 侧 `Feat-Func-019`。`Feat-Func-019b` §1.1 明写 "agent_runtime 侧不新建并行机制"（Python `remote_batch.py` + `batch_runner.py` 本就是 `Feat-Func-004b` 记录的 FEAT-004 Python 实现、从一开始按 N 成员设计），本档只在**黑盒验收面**验证既有机制在 N>1 时成立；不替 `agent-core` 做能力补齐，不设计新机制。

> **本档填补的检出灵敏度缺口**：`Feat-Func-019b` §13 第 4 行自己登记 "N>1 下的并发验证止于直接构造多成员输入，**没有从「agent-core 产出批量中断」这个真实入口走通的端到端用例** —— 真实的检出灵敏度缺口"。本档 5 个用例全部从 Python demo deep-research 的真实入口触发，正是在填这条 019b 自己点名的缺口。另外，`Feat-Func-019b` §2.1 第 8 行把 DeepAgent 的 N>1 支持留作 "未核实"，本档以 `deep-research-python`（DeepAgent 变体）为载体，顺带回答这一问。

> **背景**：本轮 Python 迭代范围只覆盖 multi-deep-research-demo-python；edpa-plan-agent 未迭代 → B2 交互续接面在 Python 侧不可用。既有 B2 系列用例（`PlanAgentParallelTransferStreamingTest` 等）不能作为 Python 覆盖载体。dev 团队确认 deep-research demo **可以承载 FEAT-019 全部可测 MUST**，本档按 B1 单载体重设计 Python 侧覆盖，并挂出必要的 demo 改造需求。

> **组织原则**：
> 1. **同类项合并**：不逐条 MUST 拆一个测试类；同载体多条能力合并为一个测试类的断言组。
> 2. **判据锚语义**：不锚内部字段（`batchId` 不断言可见性）；观察承载位属版本事实（`_remote_invocation` 探针入口或 `agentEvent.type=delegation` 事件，随 wire 演进换探针不换判据方向）。
> 3. **不动 EDPA 依赖**：所有新增用例只依赖 deep-research → search 扇出，不涉及 plan-agent / INPUT_REQUIRED 交互续接。

---

## 1. 覆盖矩阵（Python B1 单载体）

| FEAT-019 事实要求（§2） | Python B1 可测性 | 落点类 | 状态 |
|---|---|---|---|
| M1 多下游 Agent ToolCall 生成（同轮 ≥2） | ✅ COMPARISON 天然扇出到 ≥2 vendor search | `ParallelSearchComparisonPythonTest`（已有） | runnable |
| M2 单调用工具形态（不暴露批量工具） | ✅ 伴随观测 N 个互异 toolCallId 无批量形态 | 同上 | runnable |
| M3 下游代理工具识别（中断型） | ⛔ B1 走自动完成面，无 INPUT_REQUIRED 中断，属 B2 独占 | —— | out-of-scope |
| M4 同轮批量中断聚合（不得 last-write-wins） | ✅ 同 batch ≥2 个互异 toolCallId | `ParallelSearchComparisonPythonTest` | runnable |
| M5 `toolCallId` 稳定关联 | ✅ 部分 —— 扇出侧 toolCallId 稳定可观（回灌侧属 B2） | `ParallelSearchComparisonPythonTest` | runnable |
| M6 独立调用上下文 | ✅ 每个 search 子任务 childTaskId 独立 | 新增 `ParallelSearchComparisonPythonTest`.PY-B1.E | runnable |
| M7 批量 checkpoint/resume | ⛔ 一次多 part POST 是 B2 交互续接契约，B1 无对应 | —— | out-of-scope |
| M8 all-settled 后单次继续推理（恰好一次） | ✅ B1 fan-in 汇总到最终 answer；对"恰好一次"用"父输出出现时点在所有子腿之后"作黑盒看守 | 新增 `PythonParallelSearchFanInSingleRecoveryTest` | 需 demo D1 修复（无限重试） |
| M9 结果稳定归位（完成顺序不改变身份） | ✅ 按 producer taskId 分流验证 ≥2 生产者不共流 | 新增 `ParallelSearchComparisonPythonTest`.PY-B1.D | 需 demo D2（emit delegation events 或 保留 `_remote_invocation`） |
| M10 单成员兼容（批次=1） | ✅ 单 vendor query 走 single-member 路径 | 新增 `PythonSingleVendorSearchTest` | runnable（不需 demo 改动） |
| 补 部分失败回灌 | ✅ 一个 search 端点失败时，成功 vendor 不被覆盖 + 父任务收敛 | 新增 `PythonParallelSearchPartialFailureTest` | 需 demo D3（fail-injection 手段） |
| 补 后续轮次再委托不串线 | ✅ 同 context 两轮 query，childTaskId 集合互不相交 | 新增 `PythonParallelSearchNextRoundIsolationTest` | 需 demo D4（同 context followup 支持） |

**统计**：10 MUST 中 8 条 Python B1 可测（M3/M7 属 B2 独占，缺 EDPA 载体不可测），加 2 条补充能力共 10 条可测 → 5 个测试类承载。

---

## 2. 测试类设计

### 2.1 ✅ `ParallelSearchComparisonPythonTest`（已有，扩充断言组）

- **状态**：已存在（PY-B1.A/B/C）；本档新增 PY-B1.D/E。
- **载体端点**：`http://127.0.0.1:18090` deep-research。
- **协议**：仅 `A2A_STREAM`（fan-out 断言依赖流侧 metadata）。
- **G**：Python demo 三体（deep-research + search + verify）就绪；HTTP_PROXY/HTTPS_PROXY 已 unset。
- **W**：发 COMPARISON 三家 vendor 对比查询 → 等 `TASK_STATE_COMPLETED`。
- **T**（断言组）：

| ID | 断言 | 对应 MUST |
|---|---|---|
| PY-B1.A | 终态 COMPLETED | 收束基线 |
| PY-B1.B | 同一 batch ≥2 个不同 toolCallId | M1 + M4（核心） |
| PY-B1.C | 最终 artifact 非空 | 端到端 |
| **PY-B1.D**（新增） | `RemoteInvocationProbe.outputProducers` 返回 ≥2 个 distinct 生产者，且分流后成功 vendor 语义各自落在独立 stream | M9 结果稳定归位 |
| **PY-B1.E**（新增） | fan-out 各成员 childTaskId 互异；`RemoteInvocationProbe.delegations` 按 target.taskId 去重后 ≥2 且各 target agentId 名为 search | M6 独立调用上下文 |

- **PASS**：五条全成立。**FAIL**：PY-B1.B <2 = last-write-wins 聚合丢失；PY-B1.D 单一生产者 = 归位串腿；PY-B1.E childTaskId 重复 = 子上下文串线。**INCONCLUSIVE**：wire 中完全无 fan-out 承载位（先按 v0815 承载位全字段扫描，再定探针）。

---

### 2.2 ⬜ `PythonSingleVendorSearchTest`（新增，M10 单成员兼容）

- **状态**：runnable（不依赖 demo 改动）｜ **FEAT 依据**：M10 单成员批走既有单中断路径。
- **G**：Python demo 就绪。
- **W**：发**单 vendor** query（"DeepSeek V3 的 API 输入定价是多少"），只覆盖 fixture 一条 route。
- **T**（断言组）：

| ID | 断言 | 对应 MUST |
|---|---|---|
| PY-B1.SV.A | 终态 COMPLETED | 收束 |
| PY-B1.SV.B | `RemoteInvocationProbe.fromClientEvents` 返回 empty（单成员批 <2 不构成 fan-out）或 delegation events 树只有一个 target 叶节点 | M10 单成员批走既有单中断路径 |
| PY-B1.SV.C | artifact 含该单 vendor 的价格标记 | 收尾结果落到 vendor |

- **PASS**：三条全成立。**FAIL**：SV.B 出现 ≥2 target = LLM 错误地把单 vendor 拆成多 tool_call。**INCONCLUSIVE**：LLM 偶发把单 vendor 拆成 web 搜索多轮（非 FEAT-019 缺陷，assumeTrue 跳过留痕）。

---

### 2.3 ⬜ `PythonParallelSearchPartialFailureTest`（新增，补 部分失败回灌）

- **状态**：⬜ 待建，需 demo 改造 **D3**（详见 §3）｜ **FEAT 依据**：§4 场景"部分失败回灌" + §5.3（成功项与失败项均按 toolCallId 归位；失败不覆盖成功）。
- **G**：Python demo 就绪；search agent 挂 fail-injection env（`SEARCH_AGENT_FAIL_VENDOR=qwen`），一路 vendor 强制返错。
- **W**：发 COMPARISON 三 vendor query。
- **T**（断言组）：

| ID | 断言 | 对应能力 |
|---|---|---|
| PY-B1.PF.A | 父任务达终态（COMPLETED 或结构化 FAILED，不能悬挂 WORKING 超时） | 父任务收敛 |
| PY-B1.PF.B | 失败 vendor 出现结构化失败信号（wire 里可见 state=failed 的 status agentEvent，或 artifact 中失败结构） | 失败可归位 |
| PY-B1.PF.C | 其他两 vendor 的价格/标记在最终 artifact 中出现 | 失败不覆盖成功 |
| PY-B1.PF.D | 分流后失败 vendor 的语义与成功 vendor 不共流（producer taskId 不重合） | 归位不串腿 |

- **PASS**：四条全成立。**FAIL**：父悬挂 / 成功结果被失败覆盖 / 语义串流。**INCONCLUSIVE**：demo D3 未落地时用 `@Disabled("waiting on demo D3: SEARCH_AGENT_FAIL_VENDOR fail-injection")`。

---

### 2.4 ⬜ `PythonParallelSearchNextRoundIsolationTest`（新增，补 后续轮次再委托不串线）

- **状态**：⬜ 待建，需 demo 改造 **D4**｜ **FEAT 依据**：§4 场景"后续轮次再次委托"（新批次不混旧状态；已完成成员重发不增节点）。
- **G**：Python demo 就绪。
- **W**：**同 contextId** 连续发两轮 COMPARISON query（第一轮：DeepSeek/Qwen/Doubao；第二轮：GLM/Kimi/Baichuan）→ 每轮等 COMPLETED。
- **T**（断言组）：

| ID | 断言 | 对应能力 |
|---|---|---|
| PY-B1.MR.A | 两轮都收敛到 COMPLETED | 端到端 |
| PY-B1.MR.B | 第一轮 fan-out 的 toolCallId 集合与第二轮完全不相交 | 新批次不混旧 toolCallId |
| PY-B1.MR.C | `delegations` 调用树按 target.taskId 去重后逐轮前缀树单调只增；source 单一根任务不变 | 前缀树单调 + 根唯一 |
| PY-B1.MR.D | 每轮 artifact 各自命中当轮 vendor 集合，无跨轮回显 | 汇总不串轮 |

- **PASS**：四条全成立。**FAIL**：B/C/D 任一断裂 = 跨轮状态串线。**INCONCLUSIVE**：demo D4 未落地（同 context followup 未接通）时用 `@Disabled`。

---

### 2.5 ⬜ `PythonParallelSearchFanInSingleRecoveryTest`（新增，M8 all-settled 单次汇总）

- **状态**：⬜ 待建，**需 demo D1 修复无限重试**（本轮 SIT 已确认：6 tool_calls → 84 delegations，deep-research 侧不识别 search 完成信号）｜ **FEAT 依据**：M8 all-settled 后单次继续推理（不得每个下游完成后各自触发一次）。
- **G**：Python demo 就绪，无限重试 bug 已修复。
- **W**：发 COMPARISON 三 vendor query，等父任务 COMPLETED。
- **T**（断言组）：

| ID | 断言 | 对应能力 |
|---|---|---|
| PY-B1.SR.A | 每个 search 子任务的 delegation `target.taskId` 只出现一次（去重后 == 出现次数） | 已完成成员重发不增节点 |
| PY-B1.SR.B | 父任务的最终 answer 事件（artifact 承载最终对比表）在**所有** search 子任务 producer 最后一条 output 事件**之后** | fan-in 到 all-settled 后才继续推理 |
| PY-B1.SR.C | 父任务在扇出期间**无自身 output 事件**（非空文本），只有子成员输出 + 一次最终收尾 | 父透传期间无中间推理轮 |

- **PASS**：三条全成立。**FAIL**：SR.A 出现重复 = 无限重试（本轮已发现的 bug）；SR.B 父输出穿插子输出 = 逐成员恢复缺陷。**INCONCLUSIVE**：wire 里父/子输出无标签（producer taskId 缺失）时按 §"探针降级"处理。

---

## 3. Demo 需求清单（提给 dev 团队）

按优先级排列。用例落地进度依赖这些改造，未落地前对应用例挂 `@Disabled` + 需求编号。

### D1（P0，必须）：`RemoteDelegationRail` resume 短路修复（**框架层，不是 demo**）
- **状态（2026-09-22）**：已向 dev team 提交根因 MD 与建议修复形态(对齐 `ClientToolRail._submitted_result` 三分支语义),等待框架侧修复。SIT 侧不出手。
- **定性**：dev 组根因排查确认（`DELEGATION_RAIL_RESUME_SHORT_CIRCUIT_BUG.md`）——不是 demo 配置问题，是
  `common/agent-runtime-ext-python/agent_runtime/adapters/outbound/remote/delegation_rail.py:171-239`
  的 `RemoteDelegationRail.before_tool_call` 违反 openjiuwen 0.1.16 resume 契约:不查
  `ctx.extra["_resume_user_input"]` 就无条件 `raise AbortError`,导致 checkpoint 重放同一批
  tool_call 时反复委派 → 循环不终止。
- **对照**:同 repo 里 `ClientToolRail._submitted_result`(agentcore/client_tool_rail.py:77-127)已按契约
  实现;`InterruptRail._get_user_input`(openjiuwen/harness/rails/interrupt/interrupt_base.py:94-224)是官方
  参考。修复形态:命中 `_resume_user_input.user_inputs[tool_call_id]` 时,设
  `inputs.tool_result = value` + `ctx.extra["_skip_tool"] = True` + `return`(不 raise)。
- **触发条件**:DeepAgent(或走 checkpoint 重放的 ReActAgent)+ `RemoteDelegationRail`(不搭 `ClientToolRail`)+
  一轮 emit ≥1 个远端占位工具 tool_call。deep-research 的 `service/application.py:96-106` 只挂
  `RemoteDelegationRail`,必命中。
- **验收**:三 vendor COMPARISON query 60s 内出 `TASK_STATE_COMPLETED`;search-agent 侧 inbound A2A 调用数
  与 LLM 一轮 emit 的 tool_call 数 1:1(不再是 20× 以上)。
- **注意区分**:另一条已修复的通路 `SSE_NO_FINAL_ROOT_CAUSE.md` 是 `RemoteBatchAdmission` 静默截断超预算成员
  导致 barrier 永不满足,已通过 demo 配置修好(`max_concurrency: 8, max_queue_size: 32`);两条通路独立可叠加,
  本 D1 针对的是"预算够齐了成员,但回灌不识别 resume"的第二条。
- **阻塞用例**:PY-B1.SR.*(2.5)+ 现有 `ParallelSearchComparisonPythonTest` 真机跑通。

### D2（P1）：fan-out 承载位规范化 —— delegation events 或 保留 `_remote_invocation`
- **状态（2026-09-22）**：dev team 回复"这条可能是 runtime 侧诊断问题,别急归 demo,需要 SIT 侧先出抓包证据"。SIT 侧本轮动作 = **给出 wire capture 证据**(见 §3.D2.evidence 下方)——先证明"当前 wire 里既无 `_remote_invocation` 也无 `agentEvent.type=delegation`",再由 dev team 判定归位到 runtime 还是 demo emit 层。
- **需求**：Python demo 的 A2A wire 需要在扇出侧至少保留一种 metadata 承载位供 SIT 探针识别：
  - 方案 A（对齐 Java runtime v0815）：artifact `metadata.agentEvent = {type:"delegation", source:{agentId,taskId}, target:{agentId,taskId}}`，一次扇出每 child 各 emit 一条。
  - 方案 B（legacy 兼容）：part `metadata._remote_invocation = {batchId, toolCallId}`，同 batch 内 ≥2 members 表现为 batchId 相同 toolCallId 不同。
- **验收**：SIT 侧 `RemoteInvocationProbe.delegations(events)` 或 `.fromClientEvents(cid, events)` 之一返回 ≥2 条。
- **阻塞用例**：PY-B1.D/E（2.1）、PY-B1.MR.C（2.4）、PY-B1.SR.A（2.5）——本质是所有需要按 target 归位分流的断言。

**D2.evidence(SIT 侧交付物,2026-09-22 排期)**:
- 落一个 diagnostic helper(在 `PythonWireDumpDiagnostic` 或复用 `ParallelSearchComparisonPythonTest` 的 assertThat lambda)——发一次 COMPARISON,timeout 60s(D1 未修可预期超时),但在**超时前**遍历 `ctx.events()` 并把每个 InboundEvent 的 raw metadata 打印成 JSON。
- 交付物内容:
  1. 前 10 条 ClientEvent 的 `metadata` / `artifact.metadata` / `part.metadata` 三层 dump;
  2. 全量 event 里 `_remote_invocation` 出现次数、`agentEvent` 出现次数(应均为 0);
  3. tool_call / tool_result 事件的裸 dict 结构(证明 target agentId/taskId 是否在别的位置)。
- 交给 dev team,由他们判"是 runtime emitter 该补 metadata,还是 demo `service/application.py` 该显式装事件包装 rail"。

### D3（P1）：SEARCH 侧 fail-injection env
- **状态（2026-09-23）**：**dev team 已交付**。落地形态:
  - env 变量:`SEARCH_AGENT_FAIL_VENDOR`(**大小写不敏感的子串匹配**,匹配 last_user_query);
  - error_code 字面值:`SEARCH_AGENT_FAIL_INJECTED`(A2A wire 里 artifact/status 携带);
  - 注入层:handler 层 `FailInjectingHandler`(而非 tool 层,避免 LLM 兜住 error 生成"抱歉未找到"的自然语言收尾)——见 `agent-search/src/search_agent/service/fail_injecting_handler.py`;
  - 语义:`QueryChunk.of_error` → chunk_mapper `TYPE_ERROR` → 下游 search 子任务 `TaskStatus(state=TASK_STATE_FAILED)`;
  - 续接不注入:`request.is_resume=True` 直接透传,不破坏 batch 屏障;
  - 关掉方式:不设 env / 空值 → 等价于裸 inner handler。
- **fixture 覆盖**:web_search_tool stub 只覆盖 `DeepSeek / GLM / 豆包` 三家,故 `INJECTED_FAIL_VENDOR` 只能从这三家里挑,query 也须命中这三家(否则 fixture 兜底,与 D3 场景无关)。
- **SIT 侧已对齐(`PythonParallelSearchPartialFailureTest`)**:
  - query 改为 `DeepSeek V3、GLM-4、豆包 Pro`;
  - `INJECTED_FAIL_VENDOR = "DeepSeek"`;
  - `SURVIVING_VENDOR_MARKERS = ["智谱", "火山方舟"]`(分别来自 GLM / 豆包 fixture title);
  - `INJECTED_ERROR_CODE = "SEARCH_AGENT_FAIL_INJECTED"` 作 PF.B 主锚,通用 `state=TASK_STATE_FAILED` 关键词作 fallback。
- **运行约定**:dev 团队拉起 search-agent 时必须设 env `SEARCH_AGENT_FAIL_VENDOR=DeepSeek`(SIT 侧 `remoteAgent` 是地址-only,无法从 Java 端注入进程 env)。
- **@Disabled 仍在,blocker 换成 D1**:D3 已到位但用例 fan-in 收敛仍依赖 D1 修复,D1 未修用例超时。D1 修好后可解除 `@Disabled` 跑通。
- **验收**:三 vendor query 下 DeepSeek 一路返 structured error(wire 里含 `SEARCH_AGENT_FAIL_INJECTED`),其他两路正常;父任务 COMPLETED(不悬挂);GLM/豆包 markers 至少命中 1 个在最终 artifact 中。
- **阻塞用例**:`PythonParallelSearchPartialFailureTest`(2.3)——现只被 D1 阻塞。

### D4（P2）：同 contextId followup 支持
- **状态（2026-09-22）**：dev team 回复"demo 层无操作空间,由 runtime 侧核查当前能力现状";SIT 侧被动等待 runtime team 回信,不主动动作。
- **需求**：deep-research 接受 `SendStreamingMessage` 时若带 `contextId` 且该 context 上已有已完成 task，能作为**新轮**继续推理（新 batch、新 toolCallId、共享同一 parentContextId）。
- **验收**：同 contextId 连发两轮，第二轮 task 状态从 SUBMITTED 起，扇出的 childTaskId 集合与第一轮不相交。
- **阻塞用例**：`PythonParallelSearchNextRoundIsolationTest`（2.4）。

### D5（P2）：LLM 适配加固 —— reasoning-content 通道 + rate-limit 退避
- **问题 1**：使用 deepseek-v4-pro 等 reasoning model 时，demo 的 LLM adapter 未回传 `reasoning_content`，第二次 turn 立刻 400 报错 `The reasoning_content in the thinking mode must be passed back to the API`。
- **问题 2**：内部 huawei endpoint 有 per-user 20 req/min 限流，deep-research + search 6 路并发极易触发 429，demo 无退避机制。
- **验收**：deepseek-v4-pro / o3 / o1 等 reasoning model 支持；429 有指数退避（不是立刻硬失败）。
- **阻塞用例**：不阻塞用例本身，但影响 SIT 稳定性（改善后可用更多 LLM 交叉验证）。

---

## 4. 落地建议（优先级 & 顺序）

1. **P0（必做）**：dev 团队修复 **D1**（fan-in 收敛），然后跑通现有 `ParallelSearchComparisonPythonTest`（PY-B1.A/B/C）——这是所有其他 Python 用例的前置绿。
2. **P1**：dev 团队落 **D2**（承载位）→ SIT 侧扩充 `ParallelSearchComparisonPythonTest` 加 PY-B1.D/E（§2.1）。
3. **P1**：SIT 侧新增 `PythonSingleVendorSearchTest`（§2.2，不依赖 demo 改造，可先落）。
4. **P1**：dev 团队落 **D3**（fail-injection）→ SIT 侧新增 `PythonParallelSearchPartialFailureTest`（§2.3）。
5. **P2**：dev 团队落 **D4**（followup）→ SIT 侧新增 `PythonParallelSearchNextRoundIsolationTest`（§2.4）。
6. **P2**：D1 修复后追加 `PythonParallelSearchFanInSingleRecoveryTest`（§2.5）作恰好一次显式看守。
7. **P2**：D5 加固后可以扩到多 LLM 交叉验证矩阵。

---

## 5. 运行方式

```bash
# 前置：source ~/.llmrc；unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
# Python demo 由 dev 团队独立托管：
#   cd multi-deep-research-demo-python && python scripts/smoke_real_llm.py --serve-only

# 已有：三家 vendor 对比 fan-out
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelSearchComparisonPythonTest test

# 新增（依 demo 需求落地渐次开放）
./mvnw -Dtest.env=openjiuwen -Dtest=PythonSingleVendorSearchTest test                      # M10 单成员
./mvnw -Dtest.env=openjiuwen -Dtest=PythonParallelSearchPartialFailureTest test            # 需 D3
./mvnw -Dtest.env=openjiuwen -Dtest=PythonParallelSearchNextRoundIsolationTest test        # 需 D4
./mvnw -Dtest.env=openjiuwen -Dtest=PythonParallelSearchFanInSingleRecoveryTest test       # 需 D1
```
