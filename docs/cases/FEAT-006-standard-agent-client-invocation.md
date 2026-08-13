---
feature_id: FEAT-006
feature_title: 客户端发起标准化智能体调用
sut: 正式 agent-client 制品 -> 受治理 Gateway -> multi-react-travel-demo
status: designed-dependency-gated
tags: [blackbox, contract, integration, feat-006]
---

# FEAT-006 - 客户端发起标准化智能体调用测试设计

> 由业务应用只使用正式 `agent-client` facade 调用 `travel-mainplan`，验证当前 L2 交付的 STREAMING 创建、只读状态投影、同 conversation 新调用和 `input_required` 续接；不把 A2A `taskId` 当业务操作句柄。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-006-standard-agent-client-invocation.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-client\Feat-Func-006-standard-agent-client-invocation.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent | `com.openjiuwen.example:travel-demo-mainplan/trip/hotel:0.1.0`，由 `application-openjiuwen.yml` 和 `SutStack` 以外部 JAR 拉起 |

L2 明确生产 `agent-client` 尚未落地，且本迭代只交付 STREAMING 最小链路；Feature 中 BLOCKING、ASYNC、查询、取消、重订阅和 UNKNOWN 恢复仍是 MUST，但按 L2 标为 `deferred`，不生成空测试。未查阅 `agent-runtime-java` 或 `agent-solution` 产品源码；当前 Feature/L2 足以确定范围和 Oracle。

### 1.2 范围

本方案只验证当前 L2 交付给业务应用的 `agent-client` 黑盒行为：`STREAMING invoke`、conversation 传递、invocation 回显、归一化事件流、Task 状态只读投影、用户补充输入续接、端侧工具结果自动续跑入口和 ToolView 上报入口。测试不直接调用 client 内部 transport、映射表或状态存储，也不检查 runtime TaskStore、A2A 信封或 Gateway 路由实现。

FEAT-007 的工具注册、审批、执行和去重由其单特性用例验证；本方案只以“不暴露工具时普通调用不受影响”和 FEAT-007 闭环结果作为等价证据。

当前 `agent-runtime-acceptance` 的 `com.huawei.ascend.sit.client.AgentClient` 是验收辅助类，不是 FEAT-006 产品 SDK；L2 也说明生产实现尚未落地。因此以下用例在正式 `agent-client` 可执行制品和 Gateway 入口可用前均为 **dependency-gated**，不得用 helper/fake 的成功结果宣称 FEAT-006 通过。

## 2. 前置条件与证据

- 由 `SutStack` 按 hotel -> trip -> mainplan 拉起 `multi-react-travel-demo` 三个外部 JAR；使用有效 LLM。
- 正式 agent-client 只配置 Gateway 地址和测试凭证，业务测试代码不得配置 runtime endpoint、routeHandle、broker、topic 或 taskId。
- 每个测试生成唯一 `conversationId`、业务标记和请求文本；服务端响应需包含该轮业务语义。
- 主要证据为产品 client facade 返回对象、事件流、最终业务结果以及平台公开审计；Gateway/runtime 增量日志只用于证明请求确实到达真实 Agent和敏感字段未泄漏，不检查内部结构。
- `diagnosticTaskRef` 即使存在也只断言“非必填、非操作性”，测试后续步骤始终使用 `invocationRef`。
- 断流通过 acceptance 现有 `FaultLink.resetPeer()/restore()` 在 client 与 Gateway 之间制造；网络超时使用独立外部延迟代理（Toxiproxy latency toxic 或等价黑盒 HTTP 代理）并把 client 超时调到测试值，不能声称现有 `FaultLink` 已提供 latency API。未知 TaskState 分支使用只改写一个状态枚举值的黑盒 HTTP/SSE 协议代理，其他帧仍来自真实 Agent，禁止用 fake Agent 替代。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-006.streaming.lifecycle` | Feature §2/§4/§5.1.1-5.1.4；L2 §2.1 | blackbox | dependency-gated, P0 | design-only | STREAMING 创建、回显、事件、终态、同 conversation 新 invocation | client facade 对象、事件流、真实 Agent 结果 | 启动和无工具基线并入本例 |
| `FEAT-006.streaming.continue-input` | Feature §2/§4/§5.1.3；L2 §2.1 | blackbox | dependency-gated, P0 | design-only | INPUT_REQUIRED、新 invocation 关联续接、多等待状态消歧、错误关联拒绝 | invocationRef、状态投影、真实 Task 结果 | 工具治理不在本特性 |
| `FEAT-006.streaming.failure-boundary` | Feature §5.1.4/§5.1.6；L2 §5.2/§6 | blackbox | dependency-gated, P0 | design-only | 网络、路由、服务端、业务失败和 SSE 中断分类 | client 错误、Failed 投影、公开审计 | 未知枚举代理分支不计入本例 |
| `FEAT-006.streaming.unknown-state-contract` | Feature §5.1.5；L2 §2.1/§3.4 | contract | dependency-gated, P1 | design-only | 未识别 TaskState 映射 UNKNOWN 且不崩溃 | SDK 公开事件/快照 | 协议代理只改写状态枚举，不证明真实 Agent 产生该状态 |
| `FEAT-006.deferred.lifecycle-operations` | Feature §2/§4/§6；L2 §2.2 | contract/blackbox | deferred | design-only | BLOCKING、ASYNC、查询、取消、重订阅、UNKNOWN 同键恢复和显式降级 | 待公共接口和 runtime/Gateway 依赖交付 | L2 明确本迭代不交付；不生成任何占位测试 |

### 当前交付能力追踪

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| facade 创建、STREAMING、conversation 传入/委托生成、字段传递、invocation 回显、统一平台入口、普通多轮 | `FEAT-006.streaming.lifecycle` |
| Accepted/Status/Content/InputRequired/Completed/Failed 归一化与 TaskState 只读投影 | 两条用例合并覆盖 |
| ToolView 上报与端侧工具结果自动续跑 | `FEAT-006.streaming.lifecycle` 的显式工具分支；FEAT-007 闭环提供交叉证据 |
| 用户补充输入、目标消歧、续接幂等与关联错误 | `FEAT-006.streaming.continue-input` |
| 网络/路由/A2A/业务/SSE 错误分类和拓扑隐藏 | `FEAT-006.streaming.failure-boundary` |
| 未识别 TaskState 的 UNKNOWN 兜底 | `FEAT-006.streaming.unknown-state-contract` |
| BLOCKING、ASYNC、查询、取消、重订阅、UNKNOWN 同键恢复、显式降级 | `FEAT-006.deferred.lifecycle-operations`（L2 §2.2，deferred） |

## 4. 详细用例

### FEAT-006.streaming.lifecycle - 标准流式调用生命周期

- **状态/优先级**：dependency-gated, P0。
- **自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.1-5.1.4；L2 §2.1、§3.2-§3.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 conversation/invocation、STREAMING、平台入口和状态投影语义；L2 当前交付的公开 facade 与六类事件。
- **G**：三 Agent 就绪，Gateway 默认 Agent 指向 `travel-mainplan`；业务应用创建正式 client；分别准备业务传入的唯一 conversation 和由 client 生成器委托生成的 conversation；基线不暴露工具，附加分支显式暴露一个 Observation 工具；为 agentId、业务输入、correlation、trace、幂等键和凭证上下文准备唯一标记。
- **W**：以 `STREAMING` 分别发起指定/委托生成 conversation 的调用，并增加一次省略 agentId 的调用；同一 invocation 的 `events()` 由正常订阅者和按 demand 逐项请求的慢订阅者同时消费；终态后在指定 conversation 发起“改住朝阳”的新调用；附加分支让真实 Agent 请求已暴露工具并由 SDK 自动回传结果。故障分类和未知状态不混入本例。
- **T**：
  - 指定 conversation 原值回显；委托生成时得到非空且稳定回显的 conversationId；每次调用有非空且不同的 `invocationRef`、幂等键和实际模式 `STREAMING`；
  - 省略 agentId 时请求由 Gateway 默认 Agent 正常受理，client 不发送空字符串 agentId；显式 agentId 时目标标识原样到达受治理入口；
  - agentId、输入、correlation、trace 和幂等关联到达受治理入口及真实 Agent；凭证在 Gateway 被正确鉴权，授权请求才到达 Agent，凭证原文不出现在事件、结果或日志中；
  - 首次事件至少包含 Accepted/StatusChanged、内容或 artifact 投影以及 Completed，顺序不得在终态后倒退；
  - 两个订阅者看到同一有序业务事件事实，慢订阅者的 demand 不触发第二次 Agent 调用，也不使事件顺序倒退；
  - 两次最终结果均来自真实 travel Agent；第二次形成新的 invocation，公开审计显示两次请求稳定使用同一 conversationId，本用例不把 runtime 是否利用历史记忆作为 FEAT-006 断言；
  - 基线请求不携带 clientTools；显式工具分支的当前 ToolView 到达服务端，工具结果由 SDK 内部续跑原 Task且业务应用不提交 taskId；
  - 业务全程不提交 taskId，不需要解析 A2A JSON-RPC/SSE；返回对象不泄漏 runtime endpoint、routeHandle、topic。
- **不应断言**：固定自然语言、固定 token 数、固定状态轮数、runtime 是否利用历史记忆、client 内部 taskRef 映射结构。
- **失败归类**：合同字段、状态或结果不符为 Failure；正式 client/Gateway/Agent 制品或密钥缺失为 Skipped；测试代码和环境意外异常为 Error。
- **方法**：`feat006StreamingInvocationProjectsLifecycleAndKeepsConversation()`。
- **标签**：类级 `@Feature("FEAT-006: 客户端发起标准化智能体调用")`、`@Tag("feat-006")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-006.streaming.lifecycle: 标准流式调用生命周期")`、`@Tag("story-feat-006-streaming-lifecycle")`。
- **DisplayName**：`Feat-006 标准流式调用回显 invocation 并保持 conversation`。

### FEAT-006.streaming.continue-input - 等待输入续接

- **状态/优先级**：dependency-gated, P0。
- **自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.3；L2 §2.1、§3.4。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的新 invocation 续接、同 conversation、消歧和关联错误；L2 当前 `continueInput` facade 与 wire 约束。
- **G**：同一 client 和唯一 conversation；Agent 使用测试侧确定性 LLM endpoint，按两个业务标记分别生成 INPUT_REQUIRED，使两个 invocation 均稳定进入等待输入。
- **W**：只选择第一个投影的 `invocationRef`/等待输入引用调用 `continueInput`，并用相同新 invocationId/幂等键/正文重复一次；随后续接第二个等待状态；再参数化提交不存在、其他 conversation 和已终态的关联引用。
- **T**：
  - 两个首轮投影均为 INPUT_REQUIRED 且非终态；每次补充输入形成新的 `invocationRef`，保持同一 `conversationId` 并最终 COMPLETED；
  - 第一次续接只推进被明确指定的等待状态，第二个等待状态不被误选；两份结果分别保留各自首轮意图与补充条件；
  - 重复的同一 continuation 返回同一业务可见结果且原 Task 只推进一次，不产生重复副作用；
  - 错误关联返回稳定可编程错误，不产生新的可观察 Agent 执行或成功 invocation；
  - 测试只用 client 公开关联引用，不能把响应中的诊断 taskRef 作为入参。
- **不应断言**：固定追问文案、内部恢复点 key、TaskStore 状态布局或续接实现算法。
- **失败归类**：续接错目标、重复推进或非法关联假成功为 Failure；确定性 LLM/正式制品缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat006ContinueInputCreatesRelatedInvocationAndRejectsInvalidRelations()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.continue-input: 等待输入续接")`、`@Tag("story-feat-006-streaming-continue-input")`。
- **DisplayName**：`Feat-006 补充输入以新 invocation 续接指定等待状态`。

### FEAT-006.streaming.failure-boundary - 调用失败边界

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §5.1.4/§5.1.6；L2 §5.2/§6。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的错误分类与 SSE 中断恢复语义；L2 当前公开错误/事件投影。
- **G**：真实 Agent 正常流式调用可完成；在 client-Gateway 和 Gateway-runtime 外部网络边界准备可控超时/断流，并准备能经公开输入稳定触发路由、A2A/Task 与业务失败的场景。
- **W**：参数化执行各故障场景并消费 client 的公开返回和事件流。
- **T**：每类故障返回不混淆的可编程错误或 Failed 投影；SSE 中断不得伪造 Completed，也不得泄漏拓扑。
- **不应断言**：源码异常字符串、内部重试次数或固定超时实现。
- **失败归类**：合同不符为 Failure；正式依赖缺失为 Skipped；故障代理异常为 Error。
- **方法**：参数化 `feat006FailuresRemainClassifiedWithoutFalseCompletion()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.failure-boundary: 调用失败边界")`、`@Tag("story-feat-006-streaming-failure-boundary")`。
- **DisplayName**：`Feat-006 网络、路由与业务失败保持分类且不伪造完成`。

### FEAT-006.streaming.unknown-state-contract - 未知状态兼容

- **状态/优先级**：dependency-gated, P1；**自动化状态**：design-only。
- **Story/来源**：Feature §5.1.5；L2 §2.1/§3.4。
- **测试类型**：contract。
- **Oracle 来源**：Feature 与 L2 的未知 TaskState 只读 UNKNOWN 兜底合同。
- **G**：真实 Agent 流包含一个非终态状态和后续真实终态；协议代理只把该非终态枚举改为未来值，其他帧保持原样。
- **W**：SDK 通过公开流接口消费完整序列。
- **T**：未来枚举映射为只读 UNKNOWN 且 SDK 不崩溃，随后仍按真实终态完成。
- **不应断言**：真实 runtime 会产生该未来枚举、SDK 内部反序列化类型或分支实现。
- **失败归类**：映射错误或崩溃为 Failure；代理异常为 Error；正式 SDK 缺失为 Skipped。
- **方法**：`feat006UnknownTaskStateMapsToReadonlyUnknown()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-006.streaming.unknown-state-contract: 未知状态兼容")`、`@Tag("story-feat-006-streaming-unknown-state-contract")`。
- **DisplayName**：`Feat-006 未识别状态映射 UNKNOWN 且不阻断后续终态`。

## 5. 文件、执行与退出标准

计划仅新增一个测试文件：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
  Feat006StandardAgentClientBlackboxTest.java
```

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；Maven 本地仓库默认 `~/.m2/repository`。travel JAR 坐标见 §1.1。正式 agent-client、Gateway 的 group/artifact/version/classifier、构建 SHA 和 `application-openjiuwen.yml` 服务别名尚未交付，是当前门禁；不得把 acceptance helper 放入正式 client 的位置。

除 `LLM_API_BASE/LLM_MODEL/LLM_API_KEY` 等标准密钥外，确定性 prompt、payload、代理规则和唯一 canary 由测试资源自动准备。测试结束必须关闭 client、Agent/Gateway 进程和代理，恢复 `FaultLink`，删除临时目录，并确认占用端口释放。落地后执行：

```bash
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-006 test
# Story 示例
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-006-streaming-lifecycle test
```

退出标准：当前 L2 可交付用例通过或具有明确门禁；所有 Feature MUST 已直接覆盖或标为 deferred；无 helper/fake 核心链路通过、无固定 LLM 文本 Oracle、无敏感信息和进程/端口泄漏。
