---
feature_id: FEAT-004
feature_title: 远程 Agent 编排
sut: 跨 SUT（openjiuwen mainplan / trip / hotel / expense-review / deep-research + search 等作为 A2A client → server 对端）
scope: 本档只覆盖 agent-runtime 侧作为 A2A client 发起远程调用（南向）的可外部黑盒断言事实要求；agent 自身作为 A2A server 的入口面（inbound）归 FEAT-001，不列入
status: draft
owner: TBD
tags: [integration, feat-004]
depends_on:
  - version-scope FEAT-004（`chaosxingxc-orion/spring-ai-ascend@experimental` → `version-scope/FEAT-004-remote-agent-orchestration.md`，2026-07-16 版本）
  - L2 设计 Feat-Func-004（`architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-004-remote-agent-orchestration.md`，2026-06-14 版本）
related_docs:
  - FEAT-001 deepagent 覆盖档：[FEAT-001-standardized-agent-service-entrypoint-deepagent.md](FEAT-001-standardized-agent-service-entrypoint-deepagent.md)
---

# FEAT-004 — 远程 Agent 编排 SIT 覆盖评估

> **一句话**：以 agent-runtime 作为 A2A client 为对象，把 version-scope §2.1 的 14 项能力、L2 §5.3 的 8 项错误/取消/降级分支、L2 §3.2 的 5 项远端结果映射，映射到已存在的 SIT 用例并显式标记 Gap。本档是评估档，不是子用例设计档；补齐清单在 §5。

> **状态含义**：
> - ✅ 硬覆盖：有专用断言点，golden path PASS
> - 🟡 隐式覆盖：用例存在但依赖该能力为"用例前提"，非专用断言，失败时表现为其他 assertion 挂
> - ⬜ 未覆盖：无用例触碰此路径

---

## 1. 覆盖来源清单

以下 SIT 用例被本档引用为"隐式或硬覆盖 FEAT-004 能力"的证据：

| 落点 | 触及的 FEAT-004 能力 |
|---|---|
| [StreamingTravelPlanningTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java) | 远程调用主路径（mainplan → trip → hotel）、Card 拉取、Tool 生成 & 安装、结果回灌、父 Task progress 投射、A-08 分支中断-续接 |
| [OpenjiuwenThreeTurnInputRequiredTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/OpenjiuwenThreeTurnInputRequiredTest.java) | 中断-续接（远端 INPUT_REQUIRED → 父挂起 → 用户输入 → 续写，三轮） |
| [StreamInterruptRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamInterruptRecoveryTest.java) | 中断-续接（客户端 TCP-RST 后同 task/context 续传） |
| [ExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java) | 远程调用主路径（main → workflow）、Questioner 人审中断-续接 |
| [TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java) | 远程调用主路径（跨 workflow 调用） |
| [MultiTurnSearchFollowupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java) | 远程调用主路径（deep-research → search-agent）+ 多轮 INPUT_REQUIRED 续接 |
| [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) | 远端故障（连接 reset）→ 父 Task 终态收束 |

---

## 2. version-scope §2.1 能力清单覆盖矩阵

| # | 能力 | 覆盖状态 | 落点 | 说明 |
|---|---|---|---|---|
| 1 | YAML 配置远程端点 | 🟡 隐式 | StreamingTravelPlanningTest / ExpenseReviewAcceptanceTest / MultiTurnSearchFollowupTest | 每个跨-agent 用例都依赖 SutStack 在启动时注入 `SEARCH_AGENT_URL`/`TRIP_AGENT_URL` 等 env，间接证明配置驱动生效；无专用正例。 |
| 2 | Agent Card 自动拉取 | 🟡 隐式 | 同上（各用例首轮 SUBMITTED 的前提是 Card Cache 拉取成功） | 无专用断言"启动时拉取成功"，只是失败时用例连不上。 |
| 3 | 本地目录维护（sticky remoteAgentId + 故障降级） | ⬜ 未覆盖 | — | 无用例断言"Card 拉取失败时保留上次 Card"或"pending 状态本地 Agent 正常启动"。 |
| 4 | RemoteAgentToolSpec 生成 | 🟡 隐式 | StreamingTravelPlanningTest（mainplan 调用 `dispatch_travel_plan` 证明 tool 被注入 LLM） | 无用例专门断言 tool 描述内容 / 无 skills 时不生成 tool。 |
| 5 | OpenJiuwen Tool 安装（Placeholder + Interrupt Rail） | 🟡 隐式 | 同上 | 无专用观察点。 |
| 6 | 远程 A2A 调用（SendStreamingMessage 出站） | ✅ 覆盖 | StreamingTravelPlanningTest / ExpenseReviewAcceptanceTest / TransferAfterBalanceAcceptanceTest / MultiTurnSearchFollowupTest | 主路径充分覆盖，golden path PASS。 |
| 7 | 中断-续接（远端 INPUT_REQUIRED → 父挂起 → 用户输入 → 续写） | ✅ 覆盖 | ExpenseReviewAcceptanceTest 场景 1（Questioner 人审）/ OpenjiuwenThreeTurnInputRequiredTest / StreamingTravelPlanningTest A-08 / MultiTurnSearchFollowupTest / StreamInterruptRecoveryTest | 三轮 INPUT_REQUIRED、多轮补齐、断链恢复齐全。 |
| 8 | Metadata 转发（入站 → 出站远程调用） | ⬜ 未覆盖 | — | 无用例断言 `X-Tenant-Id` 等 metadata 出站透传（与 FEAT-001 F1 tenant 双条同 blocker）。 |
| 9 | 结果回灌（COMPLETED → InteractiveInput → LLM resume） | 🟡 隐式 | 所有多-agent 用例的 COMPLETED（LLM 汇总输出的存在就是回灌生效） | 无专用断言"tool call/result pair 出现在 LLM 上下文"，靠 golden answer 兜底。 |
| 10 | 父 Task 进度投射（远程 artifact → 父 Task artifact） | 🟡 隐式 | StreamingTravelPlanningTest（父 stream 收到中间 progress） | 无专用断言"父 artifact 来源于远端 ArtifactUpdate"。 |
| 11 | 取消级联传播 | ⬜ 未覆盖 | — | 无用例做父 Task CancelTask → 断远端 CancelTask 触达。 |
| 12 | 超时检测（REMOTE_TIMEOUT + 孤儿 cancel） | 🟡 已落地 · **当前 expected-red · [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)** | [RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java) | Mock SSE stall 30s 后主动 close；期待父 Task 载荷含 `REMOTE_TIMEOUT` 字面串。首跑证据：mock 30s close 后 openjiuwen `A2ARemoteAgentClient` 3 分钟零日志、父 Task 永久卡 `requires-interaction` → SUT bug（基础错误路径缺失）。详见 §5.2 / BUG-004。 |
| 13 | 嵌套远程调用禁止（NESTED_REMOTE_INVOCATION_UNSUPPORTED） | 🟡 已落地 · **expected-red · spec-⬜ watchdog** | [NestedRemoteInvocationRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java) | L2 §2.1 line 73 明标该能力 ⬜ 未实现；本用例作为未来 spec ✅ 后的自动 flip-green watchdog（详见 §5.3）。 |
| 14 | Graph/Parallel 编排 | ⬜ 未覆盖（当前限制） | — | version-scope 明标"⬜ 仅支持单层"，非 MUST。 |

---

## 3. L2 §5.3 错误 / 取消 / 降级分支

| # | 错误场景 | 覆盖状态 | 落点 | 说明 |
|---|---|---|---|---|
| 1 | Card 初次解析失败（URL 不可达 → pending，本地正常启动） | ⬜ | — | 无负例（构造错 URL 后仍能启动主 Agent）。 |
| 2 | Card 后续刷新失败（保留上次 Card） | ⬜ | — | 无负例。 |
| 3 | 远程超时（REMOTE_TIMEOUT） | 🟡 已落地 · **expected-red · [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)** | [RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java) | 同 §2 #12。 |
| 4 | 远程返回 FAILED | 🟡 部分 | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) | 造成的是"连接被 reset"而不是"远端返回 FAILED status"，最贴近但不等价。 |
| 5 | 父 Task 取消（best-effort cancel 远程） | ⬜ | — | 同 §2 #11。 |
| 6 | 远端 late event（terminal 后到达 → 丢弃） | ⬜ | — | 未覆盖。 |
| 7 | 嵌套远程调用（NESTED_REMOTE_INVOCATION_UNSUPPORTED） | 🟡 已落地 · **expected-red · spec-⬜ watchdog** | [NestedRemoteInvocationRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java) | 同 §2 #13。 |
| 8 | Card Cache 全空（不安装任何远程 tool） | ⬜ | — | 未覆盖。 |

---

## 4. L2 §3.2 远端结果映射（5 分支）

| 远端事件 | 条件 | 本地结果 | 覆盖状态 |
|---|---|---|---|
| `ArtifactUpdate` / `Message` | text 非空 | progress → 父 Task artifact | 🟡 隐式（StreamingTravelPlanningTest 父 stream 有中间 progress） |
| `TaskStatusUpdate` | COMPLETED | toolResult = TextPart 文本 | 🟡 隐式（所有多-agent 用例走 COMPLETED） |
| `TaskStatusUpdate` | INPUT_REQUIRED | 父 Task → INPUT_REQUIRED + metadata | ✅（中断-续接用例族） |
| `TaskStatusUpdate` | 其他 final state（FAILED/CANCELED/REJECTED） | toolResult = error JSON | ⬜（DownstreamAgentKilledMidStreamTest 走的是 reset，不是 FAILED status） |
| 超时 | 超过 stream-timeout | `{"error":"remote A2A stream timed out","code":"REMOTE_TIMEOUT"}` | 🟡 已落地 · **expected-red · [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)**（[RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java);详见 §5.2） |

---

## 5. 关键约束 / bug 观察点

### 5.1 无 skills Agent Card 不注入 Tool（SUT 违反 · 已归 [BUG-003](../bugs/BUG-003-skills-empty-remote-still-installed-as-tool.md)）

**Spec 依据（已 verbatim 核对 primary source `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-004-remote-agent-orchestration.md`）**：
- **L2 §3.1 远程 Agent 配置接入**（line 120）明标 **⚠️ 关键约束**：「没有 skills 的 Agent Card 不会被 LLM 作为 Tool 调用。如果远端 Agent Card 的 skills 字段为空或不存在，Card Cache 不会为其生成 `RemoteAgentToolSpec`，该 Agent 对 LLM 不可见。」
- **L2 §2.1 能力清单**（同档 line 64）：「RemoteAgentToolSpec 生成 —— 从 Card skills 生成，**无 skills 的 Agent Card 不会被注入为 Tool**。」
- **version-scope §2.1 能力清单**（`version-scope/FEAT-004-remote-agent-orchestration.md` line 30）同款转述。

**覆盖状态**：🟡 已落地 · **当前 expected-red**（SUT 违反关键约束）

**落点**：[SkillsEmptyRemoteAgentTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/SkillsEmptyRemoteAgentTest.java) —— 用 [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java)（JDK 内置 `com.sun.net.httpserver.HttpServer`）顶替下游 search agent，发布 `skills=[]` 的合法 AgentCard；真 deep-research 通过 `SEARCH_AGENT_URL` env 指向 mock；断言 mock `/a2a` 零 POST。

**首次执行观测（2026-07-20）**：
- `mock.cardGetCount=1` —— SUT 拉过 card，触发前置成立 ✅
- `mock.a2aPostCount=1` —— SUT **依然对 skills=[] 的远端发起了 `SendStreamingMessage` 调用** ❌
- POST body 含用户 prompt 完整文本 —— 证明 SUT 把 mock 装配成了合法 tool，planner 真的 route
- `terminalState=TASK_STATE_COMPLETED` —— SUT 走成功终态，无 stream 异常
- **结论**：SUT 违反 L2 §3.1 ⚠️ 关键约束。用例作为 spec 卫兵**正确红**；SUT 侧修复后自动绿。

### 5.2 远端 SSE 超时投射 REMOTE_TIMEOUT（[BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md) watchdog）

**Spec 依据（已 verbatim 核对 primary source `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-004-remote-agent-orchestration.md`）**：
- **L2 §2.1 能力清单**（同档 line 72）：「超时检测 | ✅ | REMOTE_TIMEOUT + 孤儿 Task cancel」。
- **L2 §3.2 远程调用管道 · 远端结果映射**（同档 line 163）：「超时 | 超过 stream-timeout | `{"error":"remote A2A stream timed out","code":"REMOTE_TIMEOUT"}` |」。
- **L2 §5.3 错误、取消、降级处理**（同档 line 280）：「远程超时 | 超过 stream-timeout | REMOTE_TIMEOUT → child error | toolResult = `{"error":"REMOTE_TIMEOUT"}` |」。
- **version-scope §2.1 能力清单**（`version-scope/FEAT-004-remote-agent-orchestration.md`）同款转述。
- **agent-runtime README** line 121：「`stream-timeout` caps one streaming invocation of that remote agent. On expiry the runtime keeps the results already received, appends a failed result carrying the stable code `REMOTE_TIMEOUT` (retryable), and best-effort cancels the remote task.」
- **SUT 源常量**：`A2aRemoteAgentOutboundAdapter.REMOTE_TIMEOUT_CODE = "REMOTE_TIMEOUT"`（`spring-ai-ascend/agent-runtime/src/main/.../a2a/A2aRemoteAgentOutboundAdapter.java` line 47）。

**覆盖状态**：🟡 已落地 · **当前 expected-red** · SUT bug（[BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)）

**落点**：[RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java)
—— 复用 [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java)
新增的 `STALL_SSE` 模式：`GET /.well-known/agent-card.json` 返合法 card（含非空 `web_search` skill,
让 tool spec 正常装配),`POST /a2a` 打开 SSE 流,每 500ms 发一条 `:keep-alive` 探活,30s 后 mock 主动 `close()`;
deep-research 通过 `SEARCH_AGENT_URL` env 指向 mock。断言任一 client event 的 status/artifact 文本含字面串
`REMOTE_TIMEOUT`,并要求父 Task 在 180s 内到达终态。

**首次执行观测（2026-07-21）**:
- `mock.cardGetCount=1` —— SUT 拉过 card,触发前置成立 ✅
- `mock.a2aPostCount=1` —— SUT 向 mock `/a2a` 发起了 `SendStreamingMessage`(tool spec 装配成功,planner 路由到远端)✅
- `mock.a2aClientClosedCount=0` —— **SUT 全程未主动关闭 SSE 连接** ❌
- `mock.a2aLastHoldMillis=30068` —— mock 自身 30s deadline 到,由 <b>mock 主动关</b>了连接
- `elapsedMs=180115` —— 用例整体超时(180s):**即使 mock 已在 30s 关流,SUT 又在 150s 后仍未把父 Task 收束到终态**
- SUT 日志硬信号:`16:40:12.492 A2ARemoteAgentClient - A2A streaming call agent=search-agent ...` 之后 **3 分钟零日志**(没有 EOF 感知 / 没有 error / 没有 cancel / 没有失败 tool result)。父 Task 永久卡 `requires-interaction`。
- `terminalState` 未到达 → `awaitTerminalState` 纯 `ConditionTimeoutException`

**判读(bug 归档)**:openjiuwen 侧确有 a2a client 栈(`com.openjiuwen.service.agent.core.*.A2ARemoteAgentClient` /
`A2AEnabledServeOrchestrator`,SUT 日志已确认),但**基础错误路径完全缺失** —— SSE reader 不感知服务端
close/EOF,不发失败 tool result,父 Task 不 un-suspend。这不是"实现了但用了不同错误码"级别的 spec 差异,
而是任何 a2a client 都应处理的通用错误分支,与是否遵循 spring-ai-ascend `REMOTE_TIMEOUT` spec 无关。
因此单独归为 **[BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)**
(P1)。SUT 修复后本用例自动转绿。

### 5.3 嵌套远程调用禁止(spec-⬜ watchdog · 非 bug)

**Spec 依据(已 verbatim 核对 primary source `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-004-remote-agent-orchestration.md`)**:
- **L2 §2.1 能力清单**(同档 line 73):「嵌套远程调用 | **⬜** | resume 后再次请求远程 → 返回错误 NESTED_REMOTE_INVOCATION_UNSUPPORTED」。**注意 ⬜ = 未实现**(同表其他 12 条能力均为 ✅)。
- **L2 §2.3 行为承诺 · 显式禁止**(同档 line 89):「禁止:嵌套远程调用(resume 后再次请求远程 → 返回 NESTED_REMOTE_INVOCATION_UNSUPPORTED)」。
- **L2 §3.4 结果回灌 · 结束条件**(同档 line 209-212):「parent task 的最终结束由本地 OpenJiuwen resume 后的结果决定:`result_type=answer` → parent COMPLETED;`result_type=interrupt (REMOTE_AGENT_INVOCATION)` → 嵌套调用 → **FAILED (NESTED_REMOTE_INVOCATION_UNSUPPORTED)**;`result_type=interrupt (其他)` → parent INPUT_REQUIRED」。
- **L2 §5.3 错误、取消、降级处理**(同档 line 284):「嵌套远程调用 | resume 后 LLM 再次请求远程 | 返回 NESTED_REMOTE_INVOCATION_UNSUPPORTED | parent task FAILED」。

**为何定性为 "spec-⬜" 而非 SUT bug**:

L2 能力总表(§2.1)自身把该能力标为 ⬜ (未实现),这与 REMOTE_TIMEOUT([BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md);L2 §2.1 line 72 是 ✅)**根本不同**:后者是"spec ✅ 说要实现,SUT 实际实现有缺陷"→ bug;本条是"spec 自己都承认还没实现" → **特性未落地**。全代码库 `grep NESTED_REMOTE_INVOCATION_UNSUPPORTED` 也**零命中**(spring-ai-ascend agent-runtime 侧和 openjiuwen SUT 侧均无常量定义),与 L2 ⬜ 状态自洽。

**覆盖状态**:🟡 已落地 · **当前 expected-red** · **spec-⬜ watchdog**(<em>非 bug 报告</em>)

**落点**:[NestedRemoteInvocationRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java) —— 复用 [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) 同款双 SutStack 启动序(先启 search 拿 baseUrl,再 build deep-research 并注 `SEARCH_AGENT_URL` env);单轮发一条强 prompt 要求 planner 在同一父 Task 内**严格分两次**调用 `web_search`(不允许合并、不允许凭记忆),从而最大化触发 L2 §3.4 line 210 的嵌套判定路径。

**断言层次**:
- 层 2(前置):任务在 240s 内到达终态。
- 层 1(核心 spec):任一 client event 的 status/artifact 文本含字面串 `NESTED_REMOTE_INVOCATION_UNSUPPORTED` —— 首次预期红。
- 层 3(spec 终态):`terminalState == FAILED`(spec §5.3 line 284)—— 首次预期红。

**处置**:作为**特性未落地**的活体观察点,待:
1. L2 能力总表把「嵌套远程调用」从 ⬜ 升级到 ✅;
2. SUT 侧实现 resume-back 嵌套检测并投射 `NESTED_REMOTE_INVOCATION_UNSUPPORTED` 常量;

之后本用例自动转绿。**不作为 SUT bug 单独归档**(与 [BUG-003](../bugs/BUG-003-skills-empty-remote-still-installed-as-tool.md) / [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md) 的功能层违约不同 —— 那是 spec ✅ 的能力被实现搞坏;本条是 spec 自己 ⬜)。

**INCONCLUSIVE 情形**(已知非确定性):
- LLM 决定把两个查询**合并成一次** web_search 调用 → 嵌套根本不发生;当前不加机器可读的 INCONCLUSIVE 分支,通过 haystack + terminalState 组合信息在诊断消息里由人肉判读。可选后续:改用 mock 反射二次 invocation 的 wire 形态直接触发路径(需要 mock 精确产出 openjiuwen `runtime.remote.kind=REMOTE_AGENT_INVOCATION` 结构化 payload)。

---

## 6. 汇总

| 桶 | 数量 | 说明 |
|---|---|---|
| ✅ 硬覆盖 | 2 | 远程调用主路径、中断-续接 |
| 🟡 隐式覆盖 / expected-red | 8 | YAML 配置生效 / Card 拉取成功 / Tool 生成 / Tool 安装 / 结果回灌 / 父 Task 进度投射 / 远程 FAILED（部分）/ **§5.1 无 skills 反例**（已落地 · SUT 违反,当前红 · [BUG-003](../bugs/BUG-003-skills-empty-remote-still-installed-as-tool.md)）/ **§5.2 REMOTE_TIMEOUT watchdog**（已落地 · SUT bug,当前红 · [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md)）/ **§5.3 嵌套禁止 watchdog**（已落地 · spec-⬜,当前红） |
| ⬜ 未覆盖 | 6 | Card 故障降级 (×3) / Metadata 转发 / 取消级联 / late event 丢弃 / Card Cache 全空 |

**Gap 摘要**：主路径成熟；**§5.1 关键约束**首次落硬断言,SUT 侧当前违反(SkillsEmptyRemoteAgentTest expected-red · [BUG-003](../bugs/BUG-003-skills-empty-remote-still-installed-as-tool.md));**§5.2 REMOTE_TIMEOUT** 首次落 watchdog(RemoteStreamTimeoutTest expected-red · [BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md),openjiuwen A2ARemoteAgentClient 未感知 SSE close);**§5.3 嵌套禁止** 首次落 watchdog(NestedRemoteInvocationRefusalTest expected-red · L2 明标 ⬜ · 特性未落地);其余错误 / 取消 / 降级分支仍缺硬断言。

---

## 7. 补齐优先级（收益 vs 故障注入成本）

1. **P1 · 无 skills Agent Card 反例** —— ✅ **已落地**（[SkillsEmptyRemoteAgentTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/SkillsEmptyRemoteAgentTest.java) + [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java)）· 当前 expected-red · SUT 违反 §3.1 关键约束 · 详见 [BUG-003](../bugs/BUG-003-skills-empty-remote-still-installed-as-tool.md) / §5.1。
2. **P1 · 嵌套远程调用禁止** —— ✅ **已落地**([NestedRemoteInvocationRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java))· 当前 expected-red · **spec-⬜ watchdog**(L2 §2.1 line 73 明标 ⬜,全代码库 `NESTED_REMOTE_INVOCATION_UNSUPPORTED` 常量零命中,非 SUT bug)· 详见 §5.3 / §9.2。
3. **P2 · REMOTE_TIMEOUT** —— ✅ **已落地**（[RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java) + [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java) `STALL_SSE` 模式)· 当前 expected-red · **SUT bug([BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md))**:openjiuwen `A2ARemoteAgentClient` 完全未感知 SSE close/EOF,父 Task 永久卡 `requires-interaction` · 详见 §5.2。
4. **P2 · 取消级联** —— 父 Task CancelTask → 断远端 SUT 侧也收到 CancelTask 或最终状态。需要在远端 SUT 侧观察 cancel 触达。
5. **P3 · Card 刷新失败保留上次 Card** —— 启动后 kill 远端 → 断主 Agent tool 仍可见。与 downstream-agent-killed 有部分重叠。
6. **P3 · Card 全空 / 初次拉取失败** —— 低价值，负 fallback 路径。

---

## 8. 与 FEAT-001 的关系

- **FEAT-001**：agent 作为 A2A **server**（inbound 入口面）的黑盒验收 —— Agent Card 发现 / JSON-RPC 错误面 / SendMessage / SendStreamingMessage / GetTask / push config CRUD / task 生命周期。
- **FEAT-004**（本档）：agent 作为 A2A **client**（南向出站）的编排能力 —— 远程 Tool 生成 / 调用 / 中断-续接 / 结果回灌 / 取消 / 超时。

同一个 SIT 用例可能同时命中两个特性的验收点（如 StreamingTravelPlanningTest 的 mainplan 既是 FEAT-001 server 又是 FEAT-004 client），但断言维度不同：FEAT-001 断入口协议合规，FEAT-004 断出站编排语义。

---

## 9. P1 补齐用例卡片（doc-only 设计，代码未落）

> **用法**：以下两条对应 §7 的 P1 项，采 FEAT-001 §3 同款 G/W/T 卡片格式。每张卡片带 **spec 依据 / 触发路径 / 断言层 / 落点建议 / 未决问题**。所有卡片当前状态均为 **draft**，代码未新建；作为下一步实现的原始规格入口。

### 9.1 FEAT-004.remote-agent-no-skills-not-installed — 无 skills Agent Card 不注入 Tool

- **状态**：✅ **已落地** · 当前 expected-red（首次执行观测:mock `/a2a` 收到 1 个 `SendStreamingMessage` POST,SUT 违反 §3.1）
- **FEAT 依据**：L2 §3.1 line 120 ⚠️ 关键约束「如果远端 Agent Card 的 `skills` 字段为空或不存在，Card Cache 不会为其生成 `RemoteAgentToolSpec`，该 Agent 对 LLM 不可见」；L2 §2.1 line 64 能力清单（RemoteAgentToolSpec 生成）；version-scope §2.1 line 30 同款转述。
- **G**：SIT 侧起 [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java)（JDK 内置 `com.sun.net.httpserver.HttpServer`），其 `GET /.well-known/agent-card.json` 返回合法 AgentCard 但 `skills=[]`，`POST /a2a` 计数+返 JSON-RPC internal error；主 Agent（deep-research）通过 `SEARCH_AGENT_URL` env 指向该 mock。
- **W**：拉起主 Agent，发一个明确的 search 类型 prompt。
- **T**：mock `/a2a` 入口零 POST（远端从未被 route）—— 层 1 核心；`cardGetCount>=1`（触发前置）—— 层 2。
- **PASS**：mock `/a2a` 零 POST。**FAIL**：mock `/a2a` 被 POST（tool 被误注入）。**INCONCLUSIVE**：`cardGetCount=0`（`SEARCH_AGENT_URL` 未被 SUT 消费,层 1 失效）。
- **落点**：[SkillsEmptyRemoteAgentTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/SkillsEmptyRemoteAgentTest.java)。

### 9.2 FEAT-004.nested-remote-invocation-refused — 嵌套远程调用禁止

- **状态**:✅ **已落地** · 当前 expected-red · **spec-⬜ watchdog**(L2 §2.1 line 73 「嵌套远程调用 ⬜」;非 bug,详见 §5.3)
- **FEAT 依据**:L2 §2.1 line 73「嵌套远程调用 ⬜ resume 后再次请求远程 → 返回错误 NESTED_REMOTE_INVOCATION_UNSUPPORTED」;L2 §2.3 line 89 显式禁止;L2 §3.4 line 209-212 结束条件(`result_type=interrupt (REMOTE_AGENT_INVOCATION)` → 嵌套调用 → FAILED);L2 §5.3 line 284 错误分支(parent task FAILED)。
- **G**:SIT 侧启动 deep-research + search 双 SutStack(同 [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) 启动序:先启 search 拿 baseUrl,再 build deep-research 并注 `SEARCH_AGENT_URL` env)。
- **W**:单轮发一条强 prompt 明确要求 planner 分两次调用 `web_search`("第一次搜 DeepSeek-R1 官方定价,第二次搜 DeepSeek-V3 官方定价,不允许合并、不允许凭记忆")。
- **T**:层 2(前置):任务在 240s 内到达终态。层 1(核心 spec):任一 client event 的 status/artifact 文本含字面串 `NESTED_REMOTE_INVOCATION_UNSUPPORTED`。层 3(spec §5.3):`terminalState == FAILED`。
- **PASS**:终态 FAILED + payload 含 `NESTED_REMOTE_INVOCATION_UNSUPPORTED`。**FAIL(spec-⬜ 当前预期形态)**:终态 COMPLETED + 无 NESTED 常量(LLM 静默两次搜索,SUT 未识别嵌套)。**INCONCLUSIVE**:LLM 把两个查询合并成一次 web_search 或只调用一次(嵌套未触发) —— 通过 haystack + terminalState 组合人肉判读,不作机器分支。**更严重的 FAIL**:awaitTerminalState 纯超时(与嵌套无关的更基础问题,planner 甚至没跑到终态)。
- **不断言**:mock 或 search-agent 侧的 wire 计数(用真 search-agent,无 wire 级 count),具体错误消息措辞。
- **落点**:[NestedRemoteInvocationRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java)。

### 9.3 FEAT-004.remote-stream-timeout — 远端 SSE 超时投射 REMOTE_TIMEOUT

- **状态**：✅ **已落地** · 当前 expected-red · **[BUG-004](../bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md) watchdog**(openjiuwen `A2ARemoteAgentClient` 未感知 SSE close;详见 §5.2)
- **FEAT 依据**：L2 §2.1 line 72「超时检测 ✅ REMOTE_TIMEOUT + 孤儿 Task cancel」;L2 §3.2 line 163 远端结果映射;L2 §5.3 line 280 错误、取消、降级处理;agent-runtime README line 121 stream-timeout 描述;SUT 源常量 `A2aRemoteAgentOutboundAdapter.REMOTE_TIMEOUT_CODE`。
- **G**:SIT 侧起 [MockRemoteAgentServer](../../src/test/java/com/huawei/ascend/sit/mock/MockRemoteAgentServer.java) 的 `STALL_SSE` 模式;`GET /.well-known/agent-card.json` 返合法 card(含非空 `web_search` skill,让 tool spec 正常装配);`POST /a2a` 设 `Content-Type: text/event-stream` 打开 SSE 流,不发任何事件,连接保持 30s;主 Agent(deep-research)通过 `SEARCH_AGENT_URL` env 指向该 mock。
- **W**:拉起主 Agent,发一个明确的 search 类型 prompt("帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价...")。
- **T**:任一 client event 的 `status.message.parts` 中 TextPart / artifact 文本内含字面串 `REMOTE_TIMEOUT` —— 层 1 核心;`mock.cardGetCount >= 1` AND `mock.a2aPostCount >= 1` —— 层 2 前置(证明 tool 装配 + planner 路由到远端);终态非空 —— 层 3 健康度。
- **PASS**:任一 client event 文本含 `REMOTE_TIMEOUT`。**FAIL(BUG-004)**:通篇无 `REMOTE_TIMEOUT` 字面串 + `awaitTerminalState` 纯超时 —— openjiuwen `A2ARemoteAgentClient` 未感知 SSE close/EOF,父 Task 永久 hang(当前预期形态)。**INCONCLUSIVE**:层 2 前置不成立(`cardGetCount=0` 或 `a2aPostCount=0`,说明 tool 未装配 / planner 未 route,层 1 无意义)。
- **不断言**:具体父终态类型(FAILED / COMPLETED 均合规,spec §3.2 只规定 toolResult 内容);mock 侧是否收到 `CancelTask` POST(best-effort cancel 属实现细节,时序不硬约束)。
- **落点**:[RemoteStreamTimeoutTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java)。

---

## 10. 与 FEAT-001 的重叠断言点

以下断言在 FEAT-001 已由 SIT 用例硬钉，FEAT-004 复用但角度不同：

| 断言 | FEAT-001 落点 | FEAT-004 复用视角 |
|---|---|---|
| 终态 Task 结构化 payload 存在（`status.message.parts` 非空） | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) 层 2 | §9.2 层 2 嵌套禁止的 payload 常量检查复用同一入口 |
| 终态 ∈ {FAILED, CANCELED, REJECTED} 而非 COMPLETED（handler 异常收束） | [DownstreamAgentKilledMidStreamTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/DownstreamAgentKilledMidStreamTest.java) 层 1 | §9.2 层 1 一致；同时对应 §3 错误场景 4（远程 FAILED）—— 未来 `RemoteFailedTaskTest` 也复用 |
| LLM 拒答不存在工具（COMPLETED + 拒答关键词） | [NonexistentToolRefusalTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NonexistentToolRefusalTest.java) | §9.1 层 2 复用同款关键词字典 |