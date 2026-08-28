---
scope: v0730
deployable_units: [agent-core-ext-intent-suite, agent-runtime-ext-agentcore-ext, agent-runtime]
sut: bank-intent-routing-a2a-demo（openJiuwen 意图路由示例，真实部署，5 个 Spring Boot 服务：IntentBankRouter + 4 个业务 Agent）
features: [FEAT-020]
updated: 2026-08-24
---

# 单集群真实 SUT 验收：DeepAgent 意图匹配与下游 Agent 调用委托

## 1. 测试目标

本方案以真实部署的 `bank-intent-routing-a2a-demo` 五服务集群为对象，验证 `agent-core-ext-intent-suite`
（经 `agent-runtime-ext-java` 的 `agentcore-ext` handler 装配进 `agent-runtime`）作为 FEAT-020 意图匹配与下游调用
委托能力的全部外部可观察行为：意图目录从 Agent Card Skill、custom intent 和 fallback 静态装配，DeepAgent 可见的
`intent_match` Tool 完成匹配并在同一次 ToolCall 内确定性地路由到本地 Tool 或远端 `a2a_delegate`，唯一最终结果
语义，以及与 FEAT-008 用户交互中断（确认型二次校验、中断期间意图变化重新匹配）、多任务规划逐项调用的协作链路。

本方案是 SIT 验收视角：入口 Agent（IntentBankRouter）与四个业务 Agent 均使用真实 LLM 与真实 Reranker（非确定性
Test Agent mock），验证 FEAT-020 的匹配、委托与结果契约在真实模型驱动下依然成立。SIT 侧不读取
`IntentSuite`/`IntentExecutionContext` 等内部 Java 对象状态，只通过公开 A2A 入口和服务日志观察。

本方案要证明：意图目录只暴露开发者静态装配的候选（不因下游 Agent Card 而意外扩大模型可见 Tool 面）；命中远端
Agent 或本地能力后，从匹配完成到调用委托提交之间不存在第二次路由决策的模型调用；状态改变型下游 Tool
（转账、理财购买）在 020 的 `InvokeToolAction` 路由下依然完整保留 FEAT-008 的确认型中断语义；中断续接期间用户
表达新目标时，020 能够被重新调用而不是让旧任务被静默执行；多任务请求由上游 DeepAgent 拆解为多次单任务调用，
020 自身一次只匹配一个 Agent。

## 2. 范围与非范围

范围：

- 意图目录装配：入口 Agent 从四个远端业务 Agent Card 的 Skill（`query_balance` / `execute_transfer` /
  `recommend_wealth` / `purchase_wealth`）、三个 custom intent（`calculator` / `current-date` / `weather`）和一个
  fallback（`bank-intent-fallback`）组装静态意图目录；`expose-agent-card-tools=false` 验证意图 Tool 是模型侧唯一
  对外能力入口。
- 默认 Reranker 匹配与已配置阈值（`match.threshold=0.45`）：正常路由命中远端业务 Agent、命中本地 custom
  intent，或因未达阈值触发 fallback。
- `InvokeToolAction` 两类目标：本地 Tool（calculator/current_date/weather_query，由 custom intent 结果函数生成）
  与保留目标 `a2a_delegate`（四个远端业务 Agent，由默认 `A2ADelegateIntentResultFunction` 生成）。
- `FinishAction` 路径：fallback 结果函数在 Suite 内同步执行，通过 `context.requestForceFinish` 强制结束本轮，
  兜底话术原文直接作为答复，不经过 Tool 查找、不调用任何业务 Agent、不经模型二次转述（`agent-solution!356` 起，
  取代设计文档描述的 `ReturnAction`，见附录 B）。
- 单次 ToolCall 改写与唯一最终结果：`intent_match` 的会话视图与执行视图使用同一 ToolCall ID；目标结果直接作为
  `intent_match` 的最终 Tool 结果返回，不产生额外的中间路由文本被模型二次消费。
- 确认型中断集成 FEAT-008：`execute_transfer` / `purchase_wealth` 追问收款人/金额或产品/金额，并要求显式确认后
  才真正执行（`ConfirmationRail`），验证 020 的 `InvokeToolAction` 与既有 DeepAgent Tool 中断续接机制不冲突。
- 中断期间意图变化重新匹配：`BankInterruptRails` 在续接输入命中新语义关键词时 reject 为 `INTENT_CHANGED`，入口
  路由提示词驱动模型用最新语义重新调用 `intent_match`（FEAT-020 §5.4 默认提示词第 6 条）。
- 同会话语义指代：引用同一 `contextId` 前一轮推荐结果（“刚才推荐的第一个产品”），验证 `intent_match` 接收的是
  上游 AgentLoop 完成指代消解后的完整单任务表达，020 本身不读取完整历史。
- 多任务拆解与逐项单任务路由：`todo_create` 先建计划，再逐个单任务调用 `intent_match`（FEAT-020 §2.7/§5.3 单
  任务契约），验证一次意图调用只匹配一个远端 Agent，020 不在内部做多任务规划或并行调度。
- 确认型中断的拒绝与模糊输入分支：用户在确认阶段回复“取消”（`ConfirmationRail` reject → 合成 `CANCELLED`
  结果）或既非肯定也非否定的模糊输入（重新追问并要求明确答复），验证 020 的 `InvokeToolAction` 路由不影响
  FEAT-008 既有的拒绝/重问分支。
- 下游调用拒绝/失败的如实传播（`@Tag(\"manual\")` 故障注入）：命中远端 Agent 后该 Agent 中途不可用，验证 020
  把标准调用链的失败结果如实返回，不改写为未匹配、不触发 fallback（020 §2.8 MUST）。

非范围：

- 不测试 `agent-core-ext-intent-suite` 的单元测试契约细节（`IntentSuiteTest`、`IntentCatalogReplacementTest`、
  `IntentConcurrencyTest`、`RerankerIntentMatcherTest`、`IntentExecutionContextTest` 等）——已由 L2 文档 §10.1
  覆盖，本方案只做外部可观察行为验证。
- 不测试自定义 `IntentMatcher` / `IntentInitializer` 扩展 SPI 的替换路径——示例只使用默认 Reranker 匹配与默认
  initializer；自定义 SPI 属于开发者扩展面，由框架层单测覆盖。SPI 替换后框架须保证的契约场景见 §9。
- 不测试 Workflow + 意图 Node——FEAT-020 第一阶段不交付该形态（特性档 §2.10 后续阶段）。
- 不重复验证远端调用本身的 Task 生命周期、SSE 协议表面、JSON-RPC 错误码、callback 投递等标准入口契约——这些
  由 FEAT-001/FEAT-004 已有方案覆盖；020 只负责匹配和委托形成，本方案只在必要处复用其调用链结果，不重复断言
  协议细节。
- 不测试真正的同轮并发下游委托（FEAT-019）：示例的多目标转账场景是“先建计划、再顺序逐笔路由”，不是同轮并行
  下游调用。
- 不硬断言模型推理措辞；涉及 LLM 生成的确认文案、追问文案只做关键词级弱断言，断言分层，LLM 与 Reranker 波动
  只影响弱层。
- 不测试 `IntentAutoConfiguration` / `IntentDeepAgentInstaller` 等 Runtime 层 Spring Bean 装配单测——由
  `agent-runtime-ext-java` 框架层单测覆盖。

以下是特性档中明确的 MUST 级异常/边界语义，但本 SUT 结构性地无法在 e2e 层面确定性触发，逐条列出原因与替代覆
盖方式，避免被误读为“遗漏”：

- **未配置 fallback 时的 `UNMATCHED`**（020 §2.8 MUST）：IntentBankRouter 恒配置 `bank-intent-fallback`，无法
  通过该部署形态关掉 fallback；由 `IntentSuiteTest`（L2 §10.1）覆盖“matcher 未命中且无 fallback → `UNMATCHED`”
  分支。
- **空/非法 `semantic` 输入触发 `FAILED`**（020 §2.3 MUST）：`semantic` 由 LLM 生成，SIT driver 不能在协议层
  直接注入空值伪造模型输出；由 `IntentExecutionContextTest`/`IntentSuiteTest` 覆盖输入校验分支。
- **matcher/Reranker 执行异常触发 `FAILED`**（020 §2.4 MUST）：本方案没有对 Reranker 服务本身的故障注入手
  段；由 `RerankerIntentMatcherTest` 覆盖异常/非法分数/异常映射路径。
- **意图目录初始化校验失败**（020 §2.2 MUST）：需要故意构造非法静态配置（重复意图 ID、无效阈值等），不属于
  真实业务部署形态；由 `IntentSuiteTest`/`IntentCatalogReplacementTest` 覆盖。
- **递归防护（`InvokeToolAction.toolName=intent_match` 被拒绝）与目标 Tool 未注册**（L2 §5.2/§10.2 MUST）：
  银行示例的全部结果函数都指向已正确注册的目标，业务配置不会产生这两类内部防御路径；由
  `IntentRoutingRailTest` 覆盖。

## 3. 事实来源

| 文档/代码 | 用途 |
|---|---|
| `develop/02-features/FEAT-020-agent-intent-matching-and-action-routing.md`（2026-08-13 版） | 能力表（§2）、接口入口（§3）、场景旅程（§4）、结果语义（§5.5~5.7）、验收口径（§6.3）——本方案全部断言的需求基线。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-020-agent-intent-recognition-and-downstream-task-matching.md`（2026-08-18 版） | Suite/SPI/DeepAgent 接入的实现事实：`IntentRoutingRail` 优先级 110、`IntentPromptRail` 优先级 10、ToolCall 会话/执行双视图、默认 Reranker 流程——用于把抽象需求钉到可断言的实现细节。**注意**：该版本文档的 `IntentAction` 仍只定义 `ReturnAction`/`InvokeToolAction` 二选一，未覆盖 `agent-solution!356` 新增的 `FinishAction`（见附录 B）。 |
| `agent-solution/common/example/bank-intent-routing-a2a-demo/README.md` | 示例拓扑、端口、场景清单与手工验证完整 A2A 报文——本方案场景矩阵的直接来源。 |
| `.../intent-agent-runtime/src/main/resources/application.yml` | `openjiuwen.service.intent.*` 静态配置事实：阈值 0.45、三个 custom intent、fallback id、四个 `remote-agents`（见附录 A）。 |
| `.../bank-demo-common/src/main/java/.../BankTools.java`、`BankIntentFunctions.java`、`BankInterruptRails.java`、`BankPlanProgressRail.java` | 本地工具确定性返回值（余额 `12800.50`、calculator 表达式解析）、确认/中断/意图变化/计划进度的具体实现，用于把用例期望值钉到确定性结果而非模型自由文本。 |
| `agent-solution/.../bank-intent-routing-a2a-demo/smoke-bank-intent.sh`、`smoke-bank-intent.ps1` | 已有自动化 smoke 脚本，覆盖本方案 B~G 组的等价场景，可作为测试代码落地的直接起点。 |
| `agent-solution!56`（`21129315`）+ `agent-solution!356`（`7a20e669`）+ `agent-runtime-java!146`（`f306caf9`）+ `agent-core-java!264`（`016b93a5`） | 四个交付 PR 的代码版本对照（见附录 B）——`!356`/`!264` 改变了 B4 与 D3/D5/E2/G2/G3 的断言依据。 |

## 4. 部署拓扑

```text
SIT test driver (A2A SDK client + 底层 HttpClient)
  -> IntentBankRouter (intent-agent-runtime, :18200)   意图目录 + intent_match Tool + 路由/提示词 Rail（真实 LLM + 真实 Reranker）
       -> BalanceAgent (:18201)              远端业务 Agent，query_balance
       -> TransferAgent (:18202)             远端业务 Agent，execute_transfer（确认型中断）
       -> WealthAdvisorAgent (:18203)        远端业务 Agent，recommend_wealth
       -> WealthPurchaseAgent (:18204)       远端业务 Agent，purchase_wealth（确认型中断）
```

边界要求：

- SIT driver 只通过 IntentBankRouter 的公开面（`/.well-known/agent-card.json`、`/a2a/`、`/health`）观察系统；不
  直连四个业务 Agent，也不读取任一服务的内部状态或 `IntentSuite` 快照。
- 四个业务 Agent 由 `SutStack` 按 README 顺序拉起（先四个业务 Agent 待其 `/health` 就绪，再启动入口 Agent），
  提供真实 A2A 响应；意图匹配使用真实 Reranker 与真实 LLM，不使用确定性 mock，以验证 020 在真实模型驱动下依然
  满足匹配、委托与结果语义契约。
- 本地工具（`bank_calculator`/`current_date`/`weather_query`）与 fallback 结果函数在 IntentBankRouter 进程内
  同步执行，不产生任何下游 A2A 调用。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| A1 | 入口 Agent Card 只暴露路由 Skill | 五服务就绪 | GET IntentBankRouter `/.well-known/agent-card.json` | `skills` 只含 `route_bank_intent`；不出现 `query_balance`/`execute_transfer`/`recommend_wealth`/`purchase_wealth`（`expose-agent-card-tools=false`，020 §3.1 意图 Tool 是唯一对外能力入口） | `A2aHttpProbe` |
| A2 | 四个业务 Agent Card Skill 真实性 | 四个业务 Agent 就绪 | 分别 GET 四个业务 Agent 的 Agent Card | 每张 Card 恰好包含预期 Skill id，`description`/`examples` 非空（020 §2.2 远端 Agent 意图 MUST） | `A2aHttpProbe` |
| A3 | 意图目录初始化可观测 | IntentBankRouter 启动 | 尾随入口启动日志 | 出现意图目录/Agent Card 相关初始化记录，可关联 4 个远端 Skill + 3 个 custom intent + 1 个 fallback（020 §2.6 意图与 Agent 关联须可观测轨迹保留） | `LogTailProbe` |
| A4 | 模型侧只可见 `intent_match` 及既有本地控制 Tool | IntentBankRouter 就绪 | 触发一次 `SendStreamingMessage` 并检查入口日志中的可用 Tool 声明 | 除 `intent_match`（及 `todo_create`/`ask_user` 等既有 DeepAgent 控制 Tool）外，不暴露四个业务 Tool 名（020 §3.1） | `LogTailProbe` |
| B1 | 计算器本地路由 | 同上，新 `contextId` | `SendStreamingMessage`「帮我计算 6 * 7」 | 终态 `COMPLETED`；结果含 `42`；入口日志 `tool=bank_calculator` **恰好 1 条**（见附录 B 执行次数依赖）；不产生任何 `a2a_delegate` 调用 | `A2aServiceClient` + `LogTailProbe` |
| B2 | 日期本地路由 | 同上 | `SendStreamingMessage`「今天是几号」 | 终态 `COMPLETED`；结果含当天日期；入口日志 `tool=current_date` 恰好 1 条 | `A2aServiceClient` + `LogTailProbe` |
| B3 | 天气本地路由 | 同上 | `SendStreamingMessage`「深圳天气怎么样」 | 终态 `COMPLETED`；结果含“深圳”；入口日志 `tool=weather_query` 恰好 1 条 | `A2aServiceClient` + `LogTailProbe` |
| B4 | fallback `FinishAction` 强制终态 | 同上 | `SendStreamingMessage`「请帮我写一首关于星空的诗」 | 终态 `COMPLETED`；最终答复**精确等于**兜底话术字面量“未匹配到可执行的银行业务或本地能力，请补充说明。”（`FinishAction` 经 `context.requestForceFinish` 强制结束本轮，不经模型二次转述，可做强断言而非弱断言，见附录 B）；四个业务 Agent 日志均无新执行记录（020 §2.8 fallback 结果） | `A2aServiceClient` + 四个业务日志 Probe |
| B5 | 计算器非法算式的结果分层 | 同上 | `SendStreamingMessage`「帮我计算苹果乘以香蕉」 | 唯一 FAIL 分支是终态 `COMPLETED` 且结果被伪装为成功；合规分支二选一：命中 calculator 后 Tool 如实返回 `status=INVALID_INPUT`（020 不得改写为 fallback/未匹配），或因语义未达阈值走 fallback/未匹配（020 §2.6 成功业务结果原样返回 + §2.8 失败不触发 fallback 的边界扩展） | `A2aServiceClient` |
| C1 | 余额查询路由到 BalanceAgent | 五服务就绪，新 `contextId` | `SendStreamingMessage`「查询我的账户余额」 | 终态 `COMPLETED`；结果含余额 `12800.5`；BalanceAgent 日志 `tool=query_balance` 恰好 1 条；入口日志出现 `a2a_delegate` 且 `agentName=balance-agent`（020 §2.5 下游调用委托） | `A2aServiceClient` + 双服务日志 Probe |
| C2 | 理财推荐路由到 WealthAdvisorAgent | 同上 | `SendStreamingMessage`「推荐一款稳健的三个月理财」 | 终态 `COMPLETED`；结果含“稳盈90天”；WealthAdvisorAgent 日志 `tool=recommend_wealth` 恰好 1 条 | 同上 |
| C3 | 唯一最终结果、无二次路由 | 同 C1 | 记录 C1 全部 SSE 事件 | 会话中只有一个 `intent_match` ToolCall（会话视图）及其对应结果消息；不出现额外的、需模型二次解读的中间路由文本 ToolCall（020 §2.6/2.9 无二次路由决策） | `A2aEventCollector` |
| C4 | 下游调用委托进入标准 Task 生命周期 | 同 C1 | streaming 调用并按序记录 `status.state` | `SUBMITTED → WORKING → COMPLETED` 单调收束，与 FEAT-001/FEAT-004 标准语义一致（020 §5.6 复用标准调用链） | `A2aEventCollector` |
| C5 | 下游调用拒绝/失败如实传播（`@Tag(\"manual\")`） | 五服务就绪 | 发起余额查询，进入 `WORKING` 后 `SutStack.stop(BalanceAgent)` | 终态 ∈ {`FAILED`,`CANCELED`,`REJECTED`}（`COMPLETED` 即 FAIL）；错误信息可关联到本次 `balance-agent` 调用；020 不得把该失败改写为 `UNMATCHED` 或触发 fallback（020 §2.8 MUST「调用拒绝」——特性档 §6.3 明确列为验收项，不是可选行为） | `SutStack` + `A2aEventCollector` |
| D1 | 转账信息追问 | 五服务就绪，新 `contextId` | `SendStreamingMessage`「我要转账」 | 终态 `INPUT_REQUIRED`；追问文案要求补充收款人和金额（弱断言）；TransferAgent 未执行任何转账 | `A2aServiceClient` |
| D2 | 转账续接补充收款人 | 同 D1，复用 `contextId`+`taskId` | `SendMessage`「收款人是李四」 | 仍为 `INPUT_REQUIRED`，追问金额 | `A2aServiceClient` |
| D3 | 转账确认后执行且仅执行一次 | 同 D2，补充金额后 | `SendMessage`「金额是200元」→ `SendMessage`「确认」 | 最终 `COMPLETED`；结果含“李四”与 `200`；TransferAgent 日志 `tool=execute_transfer` 恰好一次；确认前各轮 TransferAgent 均无执行记录（020 §2.5 调用结果关联 + FEAT-008 协同） | `A2aServiceClient` + TransferAgent 日志 Probe |
| D4 | 理财购买追问与确认文案 | 五服务就绪，新 `contextId` | `SendStreamingMessage`「购买一万元稳盈90天」 | 终态 `INPUT_REQUIRED`；确认文案含“稳盈90天”和“10000元” | `A2aServiceClient` |
| D5 | 理财购买确认后执行 | 同 D4 | `SendMessage`「确认」 | 终态 `COMPLETED`；WealthPurchaseAgent 日志 `tool=purchase_wealth` 恰好一次；D4 阶段该日志尚不存在 | `A2aServiceClient` + WealthPurchaseAgent 日志 Probe |
| D6 | 确认阶段用户取消 | 同 D3，走到最后一次确认追问前 | `SendMessage`「取消」（命中 `ConfirmationRail.NEGATIVE`） | `BaseInterruptRail.reject(...)` 跳过真实 Tool 执行、注入 `{status:CANCELLED,message:\"用户已取消操作\"}` 合成结果，DeepAgent 据此正常收束为 `COMPLETED`（不是特殊的 A2A 终态）；结果含“取消”类关键词（弱断言）；TransferAgent 全程无 `tool=execute_transfer` 执行记录 | `A2aServiceClient` + TransferAgent 日志 Probe |
| D7 | 确认阶段模糊输入重新追问 | 同 D6 前置，改发送中性词 | `SendMessage`「我再想想」（既不在 `AFFIRMATIVE` 也不在 `NEGATIVE` 精确匹配集合） | 仍为 `INPUT_REQUIRED`；追问文案在原确认文案后追加“请明确回复确认或取消。”（硬编码文本，可强断言）；TransferAgent 未执行 | `A2aServiceClient` |
| E1 | 转账追问期间切换为理财购买 | 同 D1 已进入 `INPUT_REQUIRED` | 同一 `taskId` 内 `SendMessage`「改为购买1000元稳盈90天理财」 | 仍为 `INPUT_REQUIRED`，但确认文案已变为购买“稳盈90天”1000 元，不再确认原转账（020 §5.4 匹配与模型调用边界之外的中断续接重新匹配，由 FEAT-008 承接触发时机） | `A2aServiceClient` |
| E2 | 确认新意图后原任务未执行 | 同 E1 | `SendMessage`「确认」 | 终态 `COMPLETED`；结果含“稳盈90天”和“1000元”；TransferAgent 日志全程无该收款人的转账记录；WealthPurchaseAgent 出现一次 `tool=purchase_wealth`（020 §2.6 匹配与执行分层：旧匹配不得被静默执行为新结果） | `A2aServiceClient` + 双 Agent 日志 Probe |
| E3 | 意图变化未产生冗余匹配 | 同 E1/E2 全流程 | 统计入口日志中真正触发的 `intent_match` 匹配次数 | 全程恰好两次匹配（初始转账 1 次 + 变化后重新匹配 1 次）；确认/追问续接本身不重复触发匹配（020 §2.4 单次匹配契约） | `LogTailProbe` |
| F1 | 引用同会话前一轮推荐结果 | 五服务就绪，新 `contextId` | `SendStreamingMessage`「推荐一款稳健的三个月理财」（`COMPLETED`）→ 同 `contextId` 新任务 `SendStreamingMessage`「购买刚才推荐的第一个产品，投入5000元」 | 第二次请求进入 `INPUT_REQUIRED`，确认文案将指代解析为“稳盈90天”并含“5000元”（020 §2.3 上下文处理责任在 AgentLoop，020 只接收处理后的单任务表达） | `A2aServiceClient` |
| F2 | 指代解析后确认执行 | 同 F1 | `SendMessage`「确认」（使用第二次响应的 `taskId`，不复用第一次已完成 Task 的 `taskId`） | 终态 `COMPLETED`，结果含“稳盈90天”和“5000元”；WealthPurchaseAgent 执行一次 | `A2aServiceClient` |
| G1 | 多目标转账先建计划 | 五服务就绪，新 `contextId` | `SendStreamingMessage`「给张三和李四各转100元」 | 终态 `INPUT_REQUIRED`；首次确认前 SSE 出现 `bank_plan_progress` Artifact，含两步计划且标注“当前执行第 1/2 步”；入口日志先出现一次 `todo_create` 再出现 `intent_match`（020 §2.7/§5.3 单任务契约，多任务拆解由上游 AgentLoop 完成） | `A2aServiceClient` + `A2aEventCollector` |
| G2 | 逐笔确认与单任务路由 | 同 G1 | 依次 `SendMessage`「确认」（第一笔）→ 观察进度 → `SendMessage`「确认」（第二笔） | 第一次确认后进度切到“第 2/2 步”；TransferAgent 分别执行两次 `execute_transfer`，收款人分别为张三、李四（不是一次收到两个收款人）；入口日志出现两次独立 `intent_match` 调用（020 §2.4 单次匹配最多命中一个 Agent；§5.3 多任务由上游拆解为多次单任务调用） | `A2aServiceClient` + `A2aEventCollector` + TransferAgent 日志 Probe |
| G3 | 计划汇总结果无串线 | 同 G2，第二笔确认后 | 观察最终 `COMPLETED` 结果 | 汇总结果同时包含张三、李四及两笔 100 元，不发生结果丢失或收款人错配（020 §2.5 并发场景下不得发生结果串线或错误回填，此处为顺序场景下的结果关联正确性对照） | `A2aServiceClient` |
| H1 | 非流式 LocalFunction 单次执行回归看守 | 五服务就绪；被测环境 `agent-core-java` 版本已知（见 §8 前置版本检查） | 复跑 B1（calculator）与 C1（balance），分别统计入口日志 `tool=bank_calculator` 与 BalanceAgent 日志 `tool=query_balance` 的出现次数 | 每次业务请求对应**恰好 1 条**执行日志；出现 2 条即命中 `agent-core-java` issue #124 的重复执行模式（流式会话下返回普通对象——非 `Iterator`/`Iterable`——的 `LocalFunction` 被执行两次，写入/扣款等副作用重复触发，银行示例全部业务 Tool 均属此模式） | `LogTailProbe` |
| H2 | 同一 Agent Card 多 Skill 分别命中 | 入口 Agent 配置的远端 Agent 中有一个 Agent 声明两个文本兼容 Skill | 分别发送匹配 Skill-A 和 Skill-B 的请求 | 两次请求均命中同一 `balance-agent`，但匹配的意图 ID 不同；日志可区分两次不同 Skill 的匹配（L2 §3.4 同一 Agent Card 多 Skill 分别参与匹配） | —（框架层覆盖，见表后注） |
| I1 | 目录热替换期间正在执行的请求不受影响 | 一个 `intent_match` 调用正在进行（已读取快照），此时远端 Agent Card 注册表变更触发目录重建（`RemoteAgentCatalogChangedEvent`，`agent-runtime-java!146`） | 等待正在执行的请求完成 | 正在执行的请求使用旧目录快照完成，不受新目录影响；新请求使用新目录（L2 §2.2 目录热替换契约） | —（框架层覆盖，见表后注） |
| I2 | Reranker 分数相同时按 ID 升序 | 构造两个候选意图描述极其相似，使 Reranker 返回相同分数 | 发送请求 | 命中 ID 字典序靠前的意图；稳定可复现（L2 §3.5 分数相同时按意图 ID 升序） | —（框架层覆盖，见表后注） |

场景 ID 与验收 example `bank-intent-acceptance-demo` 的 `BankIntentAcceptanceTest` 测试方法按 `@DisplayName` 前缀一一对应（A1↔`a1_entryCardOnlyExposesRoutingSkill`、B1↔`b1_calculatorLocalRouting`、C1↔`c1_balanceRoutingToBalanceAgent`、D1↔`d1_transferAsksForDetails`、E1↔`e1_intentChangeDuringTransfer`、F1↔`f1_semanticReferenceWithinConversation`、G1↔`g1_multiTargetTransferPlansFirst`、H1↔`h1_localFunctionSingleExecutionGuard`，其余同组场景沿用同前缀命名）。
H2 / I2 / I1 三个场景的底层契约已由框架层单元测试覆盖（见 §2 非范围 / L2 §10.1），不在本示例 e2e 范围：H2（同 Agent Card 多 Skill 分别命中）↔ `DefaultIntentInitializerTest.createsCardSkillIntentsWithSharedResultFunction`；I2（Reranker 同分按意图 ID 升序）↔ `RerankerIntentMatcherTest.appliesThresholdAndBreaksScoreTiesByIntentId`；I1（目录热替换在飞请求用旧快照）↔ `IntentCatalogReplacementTest` / `IntentSuiteTest` + runtime 层 `agent-runtime-java!146`（`RemoteAgentCatalogChangedEvent`）。

## 6. Test Agent 与 Fixture

本方案不使用确定性 Test Agent：IntentBankRouter 与四个业务 Agent 均为真实 DeepAgent（真实 LLM + 真实
Reranker），受控状态通过 prompt 设计、既定 `contextId`/`taskId` 续接顺序和示例内置的确定性业务 Tool 返回值制造。

| 对象 | 类型 | 设计说明 |
|---|---|---|
| IntentBankRouter | 真实 SUT | `intent-agent-runtime`，承载 FEAT-020 全部外部可观察行为（意图目录、`intent_match` Tool、路由/提示词 Rail）；`handler=agentcore-ext`，真实 LLM + 真实 Reranker。 |
| BalanceAgent / TransferAgent / WealthAdvisorAgent / WealthPurchaseAgent | 真实下游 Agent | 四个独立 `handler=agentcore` 部署的 DeepAgent，提供确定性业务 Tool 返回值（如余额固定 `12800.50`），作为 020 下游委托的真实执行目标；Transfer/WealthPurchase 额外挂载 `ConfirmationRail` 承载 FEAT-008 确认型中断。 |
| `SutStack` | Fixture | 按 README「手工逐步验证」顺序拉起/停止五个 Spring Boot Jar，注入 `*_AGENT_URL`/`*_AGENT_PORT` 等 env；等价复用示例自带 `smoke-bank-intent.sh`/`.ps1` 的编排逻辑。 |
| `A2aServiceClient` / `A2aEventCollector` | Fixture | 复用 FEAT-001 方案同名 fixture，通过 IntentBankRouter 的 `/a2a/` 驱动 blocking/streaming 调用并收集 SSE 事件、Artifact（含 `bank_plan_progress`）、等待终态。 |
| `A2aHttpProbe` | Fixture | 直发 JSON-RPC/HTTP 探测五张 Agent Card 内容与 Skill 声明。 |
| `LogTailProbe` | Fixture | 尾随五个服务日志文件，按固定前缀（`BANK_DEMO_EXECUTION tool=`、`Intent selected`/`intent_match`/`a2a_delegate`、`BANK_DEMO_PLAN_PROGRESS`）做结构化断言，替代对内部状态的直接访问。 |

## 7. 关键链路断言

- 入口 Agent Card 只暴露 `intent_match` 对应的 `route_bank_intent` Skill，不因四个远端 Agent Card 而膨胀出业务
  Skill（`expose-agent-card-tools=false`）；四个业务 Agent 各自的 Card 必须真实声明自身 Skill。
- 入口路由提示词（`!356` 起）明确要求**全部**用户请求——含银行范围外请求、模型本可直接回答的问题——都必须先进
  入 `intent_match`，禁止模型直接作答或绕过路由自行选择工具；这把 A4/B4 从“弱概率大概率成立”收紧为“提示词硬约
  束”，可以据此做强断言而不只是关键词弱断言。
- 命中远端 Agent 或本地 custom intent 后，从匹配完成到调用委托提交之间不得出现第二次面向路由决策的模型调用；
  `intent_match` 的 Tool 结果必须是目标执行的真实结果，不是“请调用 XXX”式自由文本指令。
- fallback（`FinishAction`）只在正常匹配没有命中且已配置时触发，通过 `requestForceFinish` 立即结束本轮并把兜
  底话术原文作为答复，不经模型二次转述；不得调用任何业务 Agent；各类失败（如非法算式）不得被伪装成 fallback
  或未匹配，也不得反向把失败伪装成成功。
- 状态改变型业务 Tool（`execute_transfer` / `purchase_wealth`）必须先经过确认型中断，只有显式确认后才产生一次
  真实执行；确认前的追问轮次不得触发副作用。**该“恰好一次”断言依赖被测 `agent-core-java` 版本（见 §8 前置版本
  检查与附录 B），执行前须确认版本，避免 020 自身的断言与已知的流式重复执行问题相互混淆。**
- 中断续接期间用户表达新目标时，必须重新调用 `intent_match`（而不是把原任务的追问答案强行套用到新目标），
  原任务不得被静默执行。
- 确认阶段的拒绝与模糊输入必须走既有 FEAT-008 分支，不受 020 路由影响：显式取消不得触发真实业务执行；模糊
  输入必须重新追问而不是被误判为确认或取消。
- 标准调用链返回拒绝或失败时，020 必须原样传播为 Agent 调用阶段结果，不得改写为未匹配、不得自动改选其他
  Agent、不得触发 fallback（020 §2.8 MUST，特性档 §6.3 明确列为验收项）。
- 多任务请求必须先由入口 DeepAgent 完成拆解（`todo_create`）再逐个调用 `intent_match`；每次意图调用只匹配一个
  远端 Agent，不允许一次调用内混合处理多个收款人或多个目标。
- 结果关联必须准确：每次 `intent_match` 的最终结果必须能与其驱动的下游 Agent 执行日志一一对应，不出现结果
  串线（尤其是 G 组两笔转账场景）。
- SIT 侧只通过公开 A2A 入口与服务日志观察系统，不读取 `IntentSuite`/`IntentExecutionContext` 内部状态；
  INCONCLUSIVE（SUT 不可达、下游 Agent 未就绪）与 FAIL 严格区分。

## 8. 执行策略

- **前置版本检查（阻断项）**：执行本方案前必须确认被测环境的 `agent-core-java` ≥ `0.1.14.post1`（含 `!264` /
  commit `016b93a5`，`bugfix/issue-124-execute-stream-duplicate-invoke`），否则流式会话下返回普通对象的
  `LocalFunction` 会被执行两次，银行示例全部业务 Tool（余额/转账/理财推荐/理财购买/计算器/日期/天气）均受影响，
  D/G 组「恰好一次」断言将失效。版本不满足时先跑 H1 确认现象，再决定是否继续执行 D/G 组。
- Smoke：A2、B1、B4、C1、C3、D1、D3。
- Full suite：A1~A4、B1~B5、C1~C5、D1~D7、E1~E3、F1~F2、G1~G3、H1~H2、I1~I2。
- P0 必须全绿：A1、B4、C1、C3、C4、D3、D5、D6、E2、G2、H1。
- 全部用例依赖真实 LLM 与真实 Reranker；LLM/Reranker 依赖用例使用 README 提供的确定性 prompt，并采用分层
  断言（硬层：终态、日志、结构化字段；弱层：确认/追问文案关键词）与 watchdog 超时，避免模型抖动导致误报。
- D/E/F/G 组必须使用同一 `contextId` 贯穿多轮请求，第 2 轮起复用首轮返回的 `taskId`；F 组第二次请求是新任务，
  不携带已完成 Task 的 `taskId`（与示例 README 手工验证章节完全一致）。
- C5（下游调用拒绝/失败传播）依赖本地多 Jar 与 `SutStack` 故障注入，CI 默认 `@Tag(\"manual\")`，不计入 P0；
  拦截手段稳定后再转常规用例。
- 每条用例使用独立 `contextId`（`ctx-feat020-<slug>-<uuid8>`），避免语义指代跨用例串扰（尤其 F 组必须与其他组
  隔离）。
- 五个默认端口 `18200`~`18204` 与示例自带 smoke 脚本一致，不能被其他进程占用；首次运行前需按示例 README
  「构建跨仓依赖和示例」完成 `agent-core-java`/`agent-runtime-java`/`agent-solution` 的相互配套安装。

```bash
./mvnw -Dtest.env=SIT -Dgroups=feat-020 test            # 全量（跳过 manual）
./mvnw -Dtest.env=SIT -Dgroups='feat-020 & manual' test # 含下游故障注入补充用例
```

## 9. SPI 替换扩展测试场景

本方案（验收视角）不直接测试自定义 SPI 替换路径（§2 非范围），但 FEAT-020 设计了三个可通过公开 API 替换的
SPI，更换默认实现后框架仍须满足 L2 §10.1/§10.2 的契约。本节从「更换 SPI 实现」视角，列出框架层单元测试需
补充覆盖、而当前 L2 §10.1 尚未明确或存在缺口的场景，作为开发侧补单测与补契约的依据。

### 9.1 三个 SPI 与替换入口

| SPI | 替换入口 | 默认实现 | 特性档契约 |
|---|---|---|---|
| `IntentMatcher` | `IntentSuite.Builder.matcher()` | `RerankerIntentMatcher` | §2.4/§3.2 外部 SPI（SHOULD） |
| `IntentInitializer` | `IntentSuite.Builder.initializer()` | `DefaultIntentInitializer` | 无需求级契约 |
| `IntentResultFunction` | `CustomIntentRegistration.resultFunction` / `DefaultIntentInitializer(a2aResultFunction)` | `A2ADelegateIntentResultFunction` | 无需求级契约 |

三个接口都在 `com.openjiuwen.agents.intent.spi` 包，均为 `@FunctionalInterface`。特性档 §3.2「外部 SPI」表
只有 `IntentMatcher` 一行；`IntentInitializer` 与 `IntentResultFunction` 虽可替换却无需求级契约，这是 §2「非
范围」未覆盖后两者替换路径的根因。

### 9.2 结果函数 SPI 缺「结果方向」扩展点

`IntentResultFunction.apply(context)` 在目标 Tool 执行【之前】被调用一次（`IntentRoutingRail.beforeToolCall`
→ `IntentSuite.resolve` → `applyResultFunction`），只覆盖「路由方向」——把意图映射为 `InvokeToolAction` /
`ReturnAction` / `FinishAction`。目标 Tool（如 `a2a_delegate`）执行后的返回直接成为该 ToolCall 的 ToolMessage，
意图套件内没有任何代码路径在目标 Tool 返回之后重新进入套件或任一结果函数检查该返回。

因此：无论更换为哪种 `IntentResultFunction` 实现，它在时序上永远拿不到下游 `a2a_delegate` 的 `{ok=false}`
返回，无法把下游失败转化为 `FinishAction` 终态。下游失败到终态的映射不在任一 `IntentResultFunction` 的可达范围
内，其断言需落在「结果方向」扩展点（目标 Tool 返回之后）或 Runtime 的 `a2a_delegate` Rail / `BaseInterruptRail`
resume 阶段，本方案不将其列为 `IntentResultFunction` 替换场景。

### 9.3 框架层单元测试补充场景

| 场景 ID | 目标 SPI | 场景 | 期望 | 落点测试类 |
|---|---|---|---|---|
| S-B1 | `IntentResultFunction` | 桩结果函数返回 `InvokeToolAction(\"intent_match\")`，直接调用 `suite.resolve()` | Suite 层即返回 `FAILED`（`intent_match` 递归调用被拒绝） | `IntentSuiteTest` |
| S-C1 | `IntentMatcher` | 自定义 matcher 返回「值相等但引用不同」的 `IntentDefinition`（按 id 重建） | `FAILED`（`isValidMatch` 引用相等），且日志可区分「外部对象」与「过期快照」 | `IntentSuiteTest` |
| S-C2 | `IntentMatcher` | 有状态 matcher 首次缓存 `matchableIntents()`，`replaceCatalog` 后仍返回旧快照实例 | `FAILED`（引用不等，框架防御生效） | `IntentSuiteTest` / `IntentCatalogReplacementTest` |
| S-D1 | `IntentSuiteConfig` | `matchThreshold` 取 `-1.0` / `1.5` / `2.0` | 装配期失败（需求 §2.4「有效阈值」，`matchThreshold` 越界须拒绝） | `IntentSuiteConfig` / `IntentSuiteTest` |
| S-E1 | `IntentInitializer` | 自定义 initializer 硬编码目录、无视 empty 入参 | `build` 失败且信息可诊断（「初始目录必须为空」） | `IntentSuiteTest` |
| S-E2 | `IntentInitializer` | 自定义 initializer 抛 `NPE`/`IllegalStateException`（非 `IntentInitializationException`） | 旧版本保留（`current.set` 未执行）；异常类型统一或文档明确泄漏语义 | `IntentSuiteTest` / `IntentCatalogReplacementTest` |
| S-F1 | `IntentResultFunction` | matched 意图返回 `FinishAction` 且 Agent 尚有后续计划 | `FinishAction` 触发 `requestForceFinish` 结束本轮（其“abandons 后续计划”语义属 javadoc 文档化行为，见 §9.3 降级说明） | `IntentRoutingRailTest` |
| S-J1 | `AgentCardInput` | `remoteAgentId` 为 null/blank | 构造期拒绝 | `IntentSuiteValidationContractTest` |
| S-K1~S-K10 | 配置/输入/失败传播 | 输入校验负向（semantic 缺失/非字符串/inputs null）、失败不触发 fallback（输入无效+已配 fallback、结果函数异常+已配 fallback）、配置校验负向（空 description、null resultFunction、跨来源重复 ID）、`IntentChangeSignal` 工具类分支 | 全部防御正确（FAILED/拒绝） | `IntentSuiteValidationContractTest` |
| S-L1 | `RerankerIntentMatcher` | `score` 恰好等于 `matchThreshold` 的边界语义 | `top.score() >= threshold` 闭区间命中；score 略低不命中（无 fallback 转 UNMATCHED） | `IntentSuiteRuntimeContractTest` |
| S-L2 | `IntentMatcher` / `IntentResultFunction` | 多个不同 semantic 并发 `resolve`（40 路），结果函数记录各自 semantic→intent | 每个结果函数收到的 semantic 与其 selectedIntent 严格对应，不跨线程串线 | `IntentSuiteRuntimeContractTest` |
| S-M1 | `DefaultIntentPrompt` | prompt 配置 fallback（defaults/blank/null → 内置默认，显式 → 配置） | `configuredOrDefault` 三态回退 | `DefaultIntentPromptTest` |
| S-N1~S-N5 | `IntentSuite.isValidAction` | 非法 action 细分负向：ReturnAction.result=Throwable、FinishAction.result=Throwable、FinishAction.output=null、InvokeToolAction.toolName=null/blank | 全部判非法转 `FAILED`（防御正确） | `IntentActionValidationTest` |
| S-O1~S-O2 | `RerankerIntentMatcher` | reranker 抛 IllegalArgumentException / 返回 null | 包装 IntentMatchException 转 FAILED，不误判未匹配、不触发 fallback | `RerankerIntentMatcherContractTest` |
| S-P1~S-P3 | `IntentRoutingRail` | 非法 JSON toolArgs、非 Map 非 String toolArgs、InvokeToolAction 缺 ToolCall | 均写 FAILED 结果（\"intent call inputs are invalid\" / \"intent call has no ToolCall\"） | `IntentRoutingRailValidationTest` |
| S-Q1 | `IntentSuiteConfig` | matchThreshold=NaN/Infinity、prompt/extensionOptions=null | 非有限抛 IllegalArgumentException、null 抛 NPE（与 S-D1 越界校验形成对照） | `IntentSuiteConfigValidationTest` |
| S-R1 | `IntentExecutionContext` | 无 user message 时 latestUserInput/conversation | 均 empty（反射构造 context，不注入 ModelContext） | `IntentExecutionContextSnapshotTest` |
| S-K11 | `DefaultIntentInitializer` | Agent Card 全部 skill 均为非 text 模态 | 全部过滤，候选为空（supportsText 只保留 text/text/*） | `IntentSuiteValidationContractTest` |
| S-S1 | `IntentResultFunction` | 自定义结果函数将 `context.toolKwargs().get("session")` 保存到 static 字段，第二次调用时检查是否为同一引用 | 框架不保证 Session 跨调用为同一引用；自定义 SPI 持有引用不影响 Suite 正确性；文档化约束即可，无需框架级防御 | 文档约束 |
| S-S2 | `IntentRoutingRail` | 注册优先级 120 的 `BEFORE_TOOL_CALL` Rail，观察它是否在 `IntentRoutingRail` 之前执行 | 高优先级 Rail 先执行，读到未改写的 `intent_match` ToolCall；属配置错误，框架不做防御；文档化优先级约束 | `IntentRoutingRailPriorityTest` |
| S-S3 | `IntentPromptRail` | 启用 Progressive Tool 过滤，观察 `intent_match` 是否在过滤后仍在 tools 列表中 | `IntentPromptRail` 在过滤后合并 `intent_match` ToolInfo 到 tools 列表（不受 Progressive 可见性影响）；优先级 10 在过滤 Rail 之后执行 | `IntentPromptRailProgressiveTest` |
| S-S4 | `FinishAction` | Agent 有 3 步计划，在第 1 步的 `intent_match` 中触发 fallback `FinishAction` | 后续 2 步不执行；Agent 轮次结束；结果为 fallback 兜底话术 | `IntentRoutingRailTest`（已有 `writesFinishActionResultAndEndsTheAgentTurn`，需确认含多步计划场景） |
| S-S5 | `IntentExecutionContext` | 注册一个 `BEFORE_MODEL_CALL` Rail 改写消息列表，然后触发 `intent_match`，检查 `context.conversation()` 是否包含改写后的消息 | `conversation` 反映 `ModelContext` 的活动消息（可能不含其他 Rail 的请求级改写）；属设计约束，非缺陷；文档化 | 文档约束 |
| S-S6 | `DefaultIntentInitializer` | 构造两个 `AgentCardInput` 使用相同 `remoteAgentId`，且 Skill ID 相同 | 初始化时抛 `IntentInitializationException`（ID 重复校验）；旧目录保留 | `IntentSuiteValidationContractTest` |

### 9.4 需求契约缺口

特性档 §3.2 只声明 `IntentMatcher` 一个外部 SPI。`IntentInitializer` / `IntentResultFunction` 的「替换后框架
须保证什么」没有需求条目，导致验收测试无断言依据。建议补齐 §3.2 或明确后两者为「内部 SPI、替换不受支持」，
再据契约补验收断言（对应 §9.3 S-E1/S-E2/S-F1 的期望口径）。

## 附录 A. `openjiuwen.service.intent` 配置事实对照

| 配置项（`intent-agent-runtime/application.yml`） | 对应 020 概念 | 用于 |
|---|---|---|
| `openjiuwen.service.intent.match.threshold=0.45` | `IntentSuiteConfig.matchThreshold`（L2 §3.1） | A/B/C 组匹配阈值边界断言的样例部署实际配置值（低于特性档默认值 `0.65`）。 |
| `openjiuwen.service.intent.match.query-instruction=\"用户请求：\"` | `RerankerIntentMatcher` 的可选任务前缀（`agent-solution!356` 新增，不在 L2 设计文档范围内） | 该前缀把裸口语请求重述为路由请求，是 `0.45` 低阈值下匹配稳定性的配置依据；B/C 组阈值边界断言以此配置为基准。 |
| `openjiuwen.service.intent.expose-agent-card-tools=false` | Runtime 层是否额外注入逐 Agent Card Tool | A1/A4 断言入口只暴露 `intent_match` 的配置依据。 |
| `openjiuwen.service.intent.custom-intents.{calculator,current-date,weather}` | `CustomIntentRegistration`（L2 §3.2） | B 组三个本地意图的意图 ID 与结果函数来源。 |
| `openjiuwen.service.intent.fallback.id=bank-intent-fallback` | fallback `IntentDefinition`（L2 §3.3） | B4 fallback 场景断言意图 ID；结果函数自 `!356` 起改为 `FinishAction`，见附录 B。 |
| `openjiuwen.service.a2a.remote-agents[].name` | `AgentCardInput.remoteAgentId`（L2 §3.2） | C 组四个远端 Agent 的 `agentName` 断言来源（如 `balance-agent`）。 |

本方案覆盖 FEAT-020 第一阶段（特性档 §6.2）交付要求中可外部观察的部分；不可外部观察的内部契约（SPI 组合、并
发目录原子替换、`IntentExecutionContext` 字段隔离等）由 `agent-core-ext-intent-suite` 单元测试（L2 文档 §10.1）
覆盖，不在本方案重复。

## 附录 B. 跨仓关键提交对照（相对 L2 设计文档基线 2026-08-18 的实现增量）

本方案初稿基于 FEAT-020 特性档（2026-08-13）与 L2 设计文档（2026-08-18）编写；随后四个交付 PR 引入了设计文档
未覆盖的实现细节，直接影响下表用例的可断言内容：

| 仓库 / PR | commit | 内容 | 影响的用例 |
|---|---|---|---|
| `agent-solution!56` | `21129315` | 主体交付：Suite/SPI、DeepAgent 接入、Runtime 自动装配、银行示例五服务 | 全部用例的基线代码来源 |
| `agent-runtime-java!146` | `f306caf9` | 远端 A2A Agent Card 注册表增加线程安全全量目录快照、版本号与目录变更事件（`A2ARemoteAgentCardRegistry`/`RemoteAgentCatalogChangedEvent`），供意图扩展重建目录 | A3（目录初始化可观测的具体机制来源） |
| `agent-solution!356` | `7a20e669` | 新增 `IntentAction` 第三种实现 `FinishAction`（`IntentRoutingRail` 命中后调用 `context.requestForceFinish`，答复文本不经模型转述）；fallback 结果函数由 `ReturnAction` 改为 `FinishAction`；新增 `RerankerIntentMatcher` 的 `queryInstruction` 前缀；路由提示词改为强制全部请求（含范围外请求）进入 `intent_match`；`WealthPurchaseAgentApplication` 确认文案改为 `{product}`/`{amount}` 模板 | B4（结果语义与强断言依据）、A4/B4（提示词硬约束）、附录 A 阈值依据、D4（确认文案模板依据） |
| `agent-core-java!264` | `016b93a5` | 修复流式会话下非 `Iterator`/`Iterable` 返回值的 `LocalFunction` 被执行两次的问题（issue #124） | B1~B3、C1、C2、D3、D5、E2、G2、G3、H1 的“恰好一次执行”类断言 |

`FinishAction`/`IntentChangeSignal` 是 `!356` 新增的模型类型，不在 2026-08-18 版 L2 设计文档的 `IntentAction`
定义（`ReturnAction`/`InvokeToolAction` 二选一）范围内；如需继续以 L2 文档做需求评审依据，建议同步推动 L2 文档
补充 `FinishAction` 一节，避免设计文档与实现出现持续性偏差。