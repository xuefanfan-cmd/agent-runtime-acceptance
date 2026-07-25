---
feature_id: FEAT-002
feature_title: Versatile 意图识别工作流适配兼容
sut: versatile-intent-acceptance-demo（单 Adapter + 内置 Versatile mock）；两层链路变体 layer1 → layer2 → downstream
scope: 三字段输入映射、三字段结果提取、显式用户交互中断归一、异常断流分离、失败/取消映射、（阶段3）结果驱动转发与重新分类
status: designed
owner: TBD
priority: P0
tags: [integration, versatile-intent, feat-002]
depends_on:
  - 实现方交付的 agent-service-adapters-versatile / agent-service-app 已按坐标安装到本地 Maven 仓库
  - 被测版本已支持 FEAT-002 的 versatile.* 扩展配置（intents / messages / result-extractions / interrupt.*）
  - versatile-intent-acceptance-demo 模块（内置 mock，无外部 versatile 依赖）
  - 两层链路用例额外依赖：实现方阶段3（A2A 出口投影 + 结果驱动转发 + 动态 agentCard 路由）
related_docs:
  - FEAT-002-versatile-intent-workflow-adapter-compatibility.md（version-scope 行为契约）
  - Feat-Func-002-versatile-intent-workflow-adapter-compatibility.md（L2 设计）
  - FEAT-002-implementation-checklist.md（实现清单，含代码行号锚点）
  - common/example/versatile-intent-acceptance-demo（自包含黑盒验收 example）
---

# FEAT-002 — Versatile 意图识别工作流适配测试用例设计

> **一句话**：以 `versatile-intent-acceptance-demo` 自包含模块为主体，内置 Versatile mock 充当工作流替身，
> 通过被测 runtime 的 A2A 入口黑盒验证三字段输入、正常结果、显式中断、异常断流分离与失败映射；
> 依赖 A2A 出口投影的结构化输出断言标 `egress-gated`，两层链路/重新分类标 `phase3`，
> 黑盒不可观测的适配器内部校验归 `unit-only` 由实现方单测覆盖。

> **仓库边界**：新增测试代码只写入 `versatile-intent-acceptance-demo`（阶段1）与 `agent-runtime-acceptance`（阶段3 两层链路）；
> `agent-solution` 与 `agent-runtime-java` 为只读被测对象，不提交任何修改，不为测试增加 Adapter/SPI 代理端点。

## 1. 状态定义

- **runnable**：单 Adapter + 内置 mock，实现方阶段1代码落地即可 `mvn test` 直接验证，无外部环境。
- **egress-gated**：断言依赖实现方补「A2A 出口投影」（把三字段结构化输出随 artifact/metadata 投出）；投影落地前 `@Disabled`。
- **phase3**：依赖实现方阶段3（结果驱动转发 + 动态 agentCard 路由 + `versatile-intent-boot` 部署模块），用 `SutStack` 两层栈。
- **unit-only**：黑盒经 A2A 观测不到（或属契约冲突项），归实现方单测；本档列出但不在 example 中断言。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 |
|---|---|---|---|
| 三字段输入到达工作流 | `FEAT-002.input.three-field` | runnable | mock 侧请求体捕获 |
| 完整三字段结果 → COMPLETED | `FEAT-002.result.completed` | runnable | A2A Task 终态 |
| 未匹配/需澄清同流程 → COMPLETED | `FEAT-002.result.completed-variants` | runnable | A2A Task 终态 |
| 显式中断（信息完整）→ INPUT_REQUIRED | `FEAT-002.interrupt.explicit` | runnable | A2A Task 终态 + prompt |
| **断流无 terminal → FAILED（不得伪造中断）** | `FEAT-002.error.stream-closed` | runnable | A2A Task 终态（核心回归） |
| 显式中断信息不全 → FAILED | `FEAT-002.error.interrupt-incomplete` | runnable | A2A Task 终态 |
| 缺 agent_id → FAILED（不得出部分结果） | `FEAT-002.error.missing-agent-id` | runnable | A2A Task 终态 |
| 远端 HTTP 5xx/超时 → FAILED | `FEAT-002.error.remote-upstream` | runnable | A2A Task 终态 |
| 取消 → 取消语义 | `FEAT-002.cancel.cooperative` | runnable | A2A cancel + 终态 |
| 三字段结构化输出经 A2A 投影可读 | `FEAT-002.result.structured-egress` | egress-gated | A2A artifact/metadata |
| 两层链路：一层 agent_id→二层转发 | `FEAT-002.chain.two-layer` | phase3 | SutStack 两层栈 + mock@二层 |
| 二层 query=用户原话、一层 response_content 进 messages | `FEAT-002.chain.query-vs-response` | phase3 | mock@二层请求体 |
| 重新分类交接回一层 | `FEAT-002.chain.reclassify` | phase3 | 新调用链 + mock@一层 |
| 多轮用户交互续接 | `FEAT-002.resume.multi-turn` | phase3 | 同 Task 续接 + 再中断 |
| 配置缺失/输入组装失败 → FAILED（阶段标识） | `FEAT-002.input.config-missing` | unit-only | 实现方单测 |
| 字段缺失/类型错/agent_id 不唯一 → FAILED | `FEAT-002.result.contract-violation` | unit-only | 实现方单测 |
| intent-agent-mapping 1:N + 策略（契约冲突项） | `FEAT-002.mapping.strategy` | unit-only | 实现方单测（默认关闭，待评审） |

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 主体 SUT 为 `versatile-intent-acceptance-demo`：薄壳挂实现方 `VersatileAgentHandler`，A2A 入口随机端口。
- `openjiuwen.service.versatile.*` 启用 FEAT-002 扩展键（`intents` / `messages` / `result-extractions` / `interrupt.*`），
  见模块内 `application.yml`；`url-template` 由 `@DynamicPropertySource` 指向内置 mock。
- 两层链路变体（phase3）用 acceptance `SutStack` 起 layer1 → layer2 →（可选 downstream），
  `remote-agents-prefix: openjiuwen.service.a2a.remote-agents`，mock 走 `service-bindings` 或按 profile 内嵌。
- 不引入外部 versatile 服务（envexplorer 不满足三字段契约），一律用内置/受控 mock。

### 3.2 能力复用

- 阶段1：`@SpringBootTest(webEnvironment=RANDOM_PORT)`、`MockVersatileServer`、`A2aDriver`、`DriveResult`（均在 example 模块内）。
- 阶段3：`SutStack`、`ManagedSutInstance`、`A2aServiceClient`、`InteractionFlow`、`TaskTextExtractor`、`WaitUtils`。
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

### FEAT-002.input.three-field — 三字段输入到达工作流

- **状态**：runnable，P0。
- **追溯**：统一三字段输入 MUST、当前主输入映射 MUST、会话消息数组映射 MUST。
- **G**：`normal-complete` fixture；配置含 `intents`（id/name）、`messages.required=true`。
- **W**：A2A `SendStreamingMessage`，`parts[0].text = "我要订酒店"`。
- **T**：mock 收到的 `inputs.query == "我要订酒店"`；`inputs.intents` 为非空数组且元素含 id/name；`inputs.messages` 为非空数组且元素含 role/content。
- **方法**：`threeFieldInputReachesWorkflow()`。
- **Story/DisplayName**：`FEAT-002.input.three-field: 三字段输入` / `Feat-002 三字段输入到达工作流`。

## 5. 状态映射与失败子用例

框架落点：`VersatileIntentContractTest.java`。

### FEAT-002.result.completed — 完整三字段结果 → COMPLETED

- **状态**：runnable，P0。
- **追溯**：三字段正常结果 MUST、标准结果状态映射 MUST。
- **G**：`normal-complete`（response_content/intent_id/agent_id 齐全 + terminal）。
- **W**：驱动一轮。
- **T**：Task 终态 COMPLETED。
- **方法**：`normalThreeFieldResultCompletes()`。

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

### FEAT-002.error.remote-upstream — 远端 5xx/超时 → FAILED

- **状态**：runnable，P0。
- **追溯**：结构化技术失败 MUST（远端调用失败/超时）。
- **G**：`remote-500`（mock 返回 HTTP 500）；`remote-timeout`（mock 延迟超过 `versatile.timeout`）。
- **W**：分别驱动。
- **T**：Task 终态 FAILED，保留可诊断远端错误/超时分类。
- **方法**：`remoteServerErrorMapsToFailed()` / `remoteTimeoutMapsToFailed()`。

### FEAT-002.cancel.cooperative — 协作式取消

- **状态**：runnable，P1。
- **追溯**：协作式取消 MUST。
- **G**：`slow-stream`（mock 缓慢逐帧、不终止）。
- **W**：驱动过程中经 A2A cancel 入口取消。
- **T**：停止继续消费；最终 Task 落取消语义（非 COMPLETED）；mock 侧连接被断开。
- **方法**：`cooperativeCancelStopsConsumption()`。

## 6. 出口投影与两层链路

### FEAT-002.result.structured-egress — 三字段结构化输出经 A2A 投影

- **状态**：egress-gated，P0。
- **追溯**：结构化结果保留 MUST。
- **G**：`normal-complete`。
- **W**：驱动一轮，检查 A2A 输出帧。
- **T**：帧中可读出 `response_content`/`intent_id`/`agent_id` 结构化值。
- **方法**：`structuredThreeFieldOutputIsProjected()`（当前 `@Disabled`；实现方补出口投影后转绿）。
- **说明**：现状 `A2AAgentExecutor.executeQuery` 只投影 `content`，结构化字段黑盒不可观测——这是应向实现方提的可测性要求。

框架落点（以下 phase3）：`agent-runtime-acceptance` → `cases/integration/workflow_call/VersatileIntentTwoLayerAcceptanceTest.java`。

### FEAT-002.chain.two-layer — 一层 agent_id → 二层转发

- **状态**：phase3，P0。
- **追溯**：单实例单工作流 + 结果驱动转发（L2 §4.9）。
- **G**：`SutStack` 起 layer1→layer2；两实例各配 mock 场景。
- **W**：调 layer1，一层返回指向二层的 agent_id。
- **T**：runtime 按 agent_id 转发到 layer2；layer2 被调用；最终产出下游结果。
- **方法**：`twoLayerChainForwardsByAgentId()`。

### FEAT-002.chain.query-vs-response — 二层 query 与一层 response_content

- **状态**：phase3，P0。
- **追溯**：当前主输入映射 MUST、会话消息数组映射 MUST（一层 response_content 作 assistant 进 messages，不替代二层 query）。
- **G**：两层栈。
- **W**：完成一层→二层转发。
- **T**：mock@二层收到的 `inputs.query == 用户本轮原话`；`inputs.messages` 末尾含一层 `response_content` 的 assistant 消息。
- **方法**：`secondLayerQueryStaysUserInputWithFirstLayerResponseInMessages()`。

### FEAT-002.chain.reclassify — 重新分类交接

- **状态**：phase3，P1。
- **追溯**：重新分类场景（L2 §4.7）。
- **G**：下游业务 mock 返回指向固定一层 Agent 的三字段（response_content=重分类上下文）。
- **W**：完成一次下游→一层的重新分类调用链。
- **T**：一层被以重分类上下文为新 query 再调用；不恢复原一层 Task 而是新调用链。
- **方法**：`reclassificationHandsBackToFirstLayer()`。

### FEAT-002.resume.multi-turn — 多轮用户交互续接

- **状态**：phase3，P1。
- **追溯**：原工作流续接请求适配 MUST、再次中断适配 MUST。
- **G**：一层中断 → 客户端同 Task 续接。
- **W**：中断后携续接输入再入。
- **T**：Adapter 组装续接请求恢复原工作流；工作流可完成/再中断/失败，分别映射正确终态。
- **方法**：`multiTurnInteractionResumesOriginalWorkflow()`。

## 7. 单测归属（黑盒不可观测 / 契约冲突）

以下经 A2A 黑盒观测不到或属契约冲突项，**不在 acceptance 断言**，归实现方单测（`VersatileRequestExtractorTest` / `VersatileResponseExtractorTest`）：

| 子用例 ID | 内容 | 归属说明 |
|---|---|---|
| `FEAT-002.input.config-missing` | intents 空/缺 id·name、messages 空 → 带阶段标识的 FAILED（config_read / input_assembly） | 阶段标识黑盒不可见，单测断言异常 message |
| `FEAT-002.result.contract-violation` | 字段类型错、agent_id 数组/多值 → 对应错误码 | 错误码黑盒不可见，单测断言 TYPE_ERROR 分类 |
| `FEAT-002.mapping.strategy` | intent-agent-mapping 1:N + first/priority/round-robin | **契约冲突项**：与需求「工作流须返回唯一 agent_id」冲突；默认关闭、待 version-scope 评审后再启用与测试 |

## 8. 框架落点汇总

| Java 类 | 子用例 | 状态 | 类内 fixture |
|---|---|---|---|
| `VersatileIntentContractTest`（example 模块） | input.*、result.*、interrupt.*、error.*、cancel.* | runnable / egress-gated | `MockVersatileServer`、`A2aDriver`、`versatile-sse/*.sse` |
| `VersatileIntentTwoLayerAcceptanceTest`（acceptance） | chain.*、resume.* | phase3 | `SutStack` 两层栈 + 受控 mock |
| 实现方单测（agent-solution） | input.config-missing、result.contract-violation、mapping.strategy | unit-only | 直接构造 ServeRequest / SSE 帧 |

落点目录：

```text
common/example/versatile-intent-acceptance-demo/src/test/java/.../VersatileIntentContractTest.java
agent-runtime-acceptance/src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/VersatileIntentTwoLayerAcceptanceTest.java
```

## 9. 需求 MUST 追溯

| version-scope MUST | 本档落点 |
|---|---|
| 统一三字段输入 / 当前主输入映射 / 会话消息数组映射 | input.three-field、chain.query-vs-response |
| 三字段正常结果 / 结构化结果保留 / 唯一 agent_id | result.completed、result.structured-egress、error.missing-agent-id |
| 正常业务结果一致处理 / 标准结果状态映射 | result.completed-variants、error.stream-closed |
| 用户交互中断归一 / 原工作流续接 / 再次中断 | interrupt.explicit、resume.multi-turn |
| 结构化技术失败 | error.interrupt-incomplete、error.remote-upstream、result.contract-violation、input.config-missing |
| 协作式取消 | cancel.cooperative |
| 单工作流实例适配（两层链路） | chain.two-layer、chain.reclassify |

## 10. 标签与报告

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

Allure 报告必须区分 blackbox（runnable）、egress-gated、phase3、unit-only，禁止把单测覆盖冒充黑盒通过。

## 11. 运行方式

```bash
# 阶段1 全部黑盒（example 模块，交付 jar 后先 bump 版本号）
mvn -f common/example/versatile-intent-acceptance-demo/pom.xml test

# 阶段3 两层链路（acceptance）
./mvnw -Dtest.env=openjiuwen -Dtest=VersatileIntentTwoLayerAcceptanceTest test
```

## 12. 风险与代码生成约束

1. `result-extractions` / `interrupt.*` 的配置键名须与实现方最终命名一致；不一致改 `application.yml` 与 fixture，不改测试逻辑。
2. 三字段结构化**输出**黑盒不可观测（`A2AAgentExecutor.executeQuery` 只投影 content）；egress-gated 用例在出口投影落地前保持 `@Disabled`。
3. `intent-agent-mapping` 与需求 MUST 冲突，默认关闭；未过 version-scope 评审前不写启用态用例。
4. 断流/超时/取消依赖 mock 的连接控制；SSE fixture 必须精确区分「有 End」「有 signal」「都无」。
5. 阶段3 依赖实现方结果驱动转发 + 动态 agentCard 路由；这些是全新路径而非「SPI 迁移」，代码就绪前 phase3 用例保持不激活。
6. 判别性用例（input.three-field、error.stream-closed 等）**改前应红、改后应绿**；改后仍红即实现方 bug，附 `DriveResult.rawText()` 帧给开发定位。

## 13. 退出标准

- `VersatileIntentContractTest` 全部 runnable 用例在实现方阶段1 jar 上转绿。
- 判别性负向用例（断流→FAILED、中断不全→FAILED、缺 agent_id→FAILED、三字段输入到达）均有真实黑盒证据。
- egress-gated 用例在出口投影落地后去除 `@Disabled` 并通过，或在报告中明确记录「OUTPUT 未端到端覆盖 + 已提可测性要求」。
- phase3 两层链路/重新分类/续接在实现方阶段3 就绪后于 acceptance 覆盖。
- unit-only 项由实现方单测覆盖并在追溯表登记。
- 新增测试代码只在 example 模块与 acceptance；两实现仓工作树无修改。
```
