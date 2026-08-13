---
feature_id: FEAT-007
feature_title: 客户端本地工具注册与执行
sut: 正式 agent-client 制品 + multi-react-travel-demo 的真实 client-tool 请求闭环
status: designed-dependency-gated
tags: [blackbox, integration, feat-007]
---

# FEAT-007 - 客户端本地工具注册与执行测试设计

> 通过正式 client SDK 注册差旅端侧 Observation/Action 工具，并让真实 `travel-mainplan` 根据本次 invocation 的 ToolView 发起请求，验证“显式暴露 -> 本地治理执行 -> continuation 回灌 -> 原 Task 完成”的客户端黑盒闭环。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-007-local-tool-registration-and-execution.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-client\Feat-Func-007-local-tool-registration-and-execution.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent | `com.openjiuwen.example:travel-demo-mainplan/trip/hotel:0.1.0`，外部 JAR |

Feature 是需求 Oracle。L2 front matter 为 `proposed / non-authoritative`，仅用于确定拟议公共形态和当前依赖，不能单独新增验收预期；接口名落地变化时必须回到 Feature 语义重新裁决。未查阅产品源码。

### 1.2 范围

本方案验证 SDK 对业务可见的工具描述与注册、显式暴露、每 invocation ToolView、Observation/Action 治理、单个及多个远端工具请求、结果回传和 toolCallId 去重。runtime 如何产生意图、TaskStore 如何推进、Gateway 如何实现粘滞路由，以及 client 内部 registry/outbox/dispatcher/线程/存储结构不作为断言对象。

正式 `agent-client` 尚无可执行产品制品，且原始 travel demo 没有固定的端侧工具业务脚本。用例执行还要求 runtime 对 `metadata.clientTools` 的公开行为可用，并通过确定性测试提示触发指定工具。因此本方案为 **dependency-gated**；不得用 L2 的 in-process fake 或直接调用 handler 代替真实 Agent 闭环。

## 2. Fixture 与证据

- 拉起 hotel -> trip -> mainplan 三个外部 JAR；正式 client 只连接受治理 Gateway。
- 三个 Agent 仍是真实外部 JAR，但把 LLM endpoint 配置为测试侧可控的确定性服务，按输入标记返回固定的单工具、多工具、拒绝或异常工具意图；不得绕过 Agent 直接构造 `ToolInvocation`。
- 在测试类内注册两个产品 SDK SPI 工具：
  - `travel.policy.read`：OBSERVATION，返回带唯一 canary 的差标；记录执行次数；
  - `travel.booking.confirm`：ACTION，记录审批次数与真正副作用次数。
- invocation 输入必须明确要求 Agent 使用给定工具名，并在完成答案中引用 canary；若模型未发起请求，测试记为环境/提示不满足，不能绕过 Agent 手工调用 handler。
- 事件/结果证据：client 公开 ToolView/Invocation 投影、`ToolExecutionRecord`、handler/approval 计数、最终 Agent 文本；wire log 仅辅助确认 `clientTools` 与 `toolCallId` 原样通过。
- 工具 payload、token、授权材料使用无敏感测试数据；失败消息不得回显 handler 堆栈或本地路径。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-007.exposure-and-observation` | Feature §2/§4/§5.1.0-5.1.3 | blackbox | dependency-gated, P0 | design-only | 描述/注册冲突、默认空、两级暴露、动态可用性、ToolView 快照、Observation 闭环 | SDK facade、ToolView、执行记录、真实 Agent 结果 | 启动装配并入本例 |
| `FEAT-007.action-governance` | Feature §2/§4/§5.1.2-5.1.5 | blackbox | dependency-gated, P0 | design-only | Action 审批、参数/权限/可用性/超时/异常、Task 终态冲突 | 执行记录、审批/副作用计数、平台拒绝 | 工具未注册分支依赖公开生命周期入口 |
| `FEAT-007.idempotent-result` | Feature §2/§5.1.4-5.1.5 | blackbox | dependency-gated, P0 | design-only | 重复投影去重、多 pending 完整收集、单次 continuation | toolCallId、执行计数、唯一结果 | broker 重投不作为内部实现断言 |

### 当前交付能力追踪

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| 描述符、SPI 注册、toolId 唯一、register/replace | `FEAT-007.exposure-and-observation` |
| 默认不暴露、conversation/invocation 策略、当前 ToolView、历史快照 | `FEAT-007.exposure-and-observation` |
| Observation/Action、schema、可见性、策略、审批、动态可用性 | 前两条用例合并覆盖 |
| 错误闭集、执行记录、最小 observation/payloadRef、审计边界、Task 终态冲突 | `FEAT-007.action-governance` |
| toolCallId 去重、单次最终结果、自动续跑、多 pending 完整提交 | `FEAT-007.idempotent-result` |

## 4. 详细用例

### FEAT-007.exposure-and-observation - 显式暴露与 Observation 闭环

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.0-§5.1.3；L2 §2.1-§3.1 仅作为拟议接口形态。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的注册、默认不暴露、两级策略、不可变 ToolView 快照和 Observation 执行语义。
- **G**：准备字段完整的两个描述符（稳定 toolId/name、description、Observation/Action 类型、input/output schema、版本、授权/审批策略、审计策略、timeout、幂等和可用性约束）；先 `register`，再对同一 toolId 执行重复 `register` 与显式 `replace`；conversation 策略允许二者；准备无策略、继承 conversation、invocation 收窄和当前上下文不可用的调用。
- **W**：通过正式 client 调用真实 mainplan，要求使用端侧差标工具完成北京差旅建议；在旧 invocation 进入等待后再 replace 描述符并改变全局策略，随后创建新 invocation。
- **T**：
  - 缺必填描述字段在注册边界明确失败；重复 `register` 拒绝且不覆盖原工具；`replace` 明确覆盖，同一 toolId 始终唯一；
  - 无策略时 ToolView 为空，两个 handler 均不执行；服务端即使请求未声明工具，client 也返回 `tool_not_declared`，不动态注册；
  - 继承策略时本次 ToolView 含两个当前已注册且可用的工具；invocation 策略覆盖/收窄后只含 Observation，不含 Action；过期、达到最大使用次数或上下文不匹配的工具不进入当前 ToolView；
  - 每个 invocation 使用创建时的 ToolView 快照，replace 或后续策略变化只影响新 invocation，不能使历史 invocation 改名、换版本或新增工具；
  - Observation 请求参数通过 schema 校验后只执行一次，形成 OK 执行记录和审计引用；client 创建一个 continuation invocation，最终结果包含 canary；
  - 工具定义只通过 `metadata.clientTools` 上报，业务应用不直接调用 handler，也不使用 taskId 续接。
- **不应断言**：client 内部 registry/dispatcher/outbox 结构、固定模型措辞、服务端缓存实现。
- **失败归类**：公开行为不符为 Failure；正式 SDK/runtime 工具合同缺失为 Skipped；测试工具或确定性 LLM 异常为 Error。
- **方法**：`feat007ExposurePoliciesProducePerInvocationToolViewAndExecuteObservation()`。
- **标签**：类级 `@Feature("FEAT-007: 客户端本地工具注册与执行")`、`@Tag("feat-007")`、`@Tag("integration")`、`@Tag("blackbox")`；方法级 `@Story("FEAT-007.exposure-and-observation: 显式暴露与 Observation 闭环")`、`@Tag("story-feat-007-exposure-and-observation")`。
- **DisplayName**：`Feat-007 仅显式暴露的 Observation 工具进入真实 invocation 闭环`。

### FEAT-007.action-governance - Action 本地治理与结构化失败

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.2/§5.1.4/§5.1.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 Action 本地授权审批、错误闭集、最小结果和 Task 终态冲突语义。
- **G**：Action 工具已注册并显式暴露；按参数化场景配置调用级策略覆盖、批准、拒绝、无权限、当前不可用、暴露策略过期/次数耗尽、执行 deadline 已过期、上下文失效、非法参数、短超时、handler 异常和超阈值大结果。`tool_not_found` 仅在产品提供公开注销/生命周期入口后增加参数分支；当前不得靠修改内部 registry 制造。
- **W**：每场景由真实 mainplan 请求 `travel.booking.confirm`；client 完成本地治理并提交结果 observation；终态冲突分支在工具执行完成后、结果提交前，通过公开 Task 生命周期让原 Task 进入终态，再由 SDK 提交该结果。
- **T**：
  - 批准场景审批一次、副作用一次、记录为 OK；拒绝/无权限不执行副作用，分别记录 `rejected`/`permission_denied`；
  - invocation 策略优先于 conversation 策略；当前不可用和上下文过期分别返回 `tool_not_available`、`stale_context`，均不执行 handler；公开注销/生命周期入口交付后，已进入历史 ToolView 但已注销的工具返回 `tool_not_found`；
  - 非法参数在 handler 前返回 `invalid_tool_arguments`；已过执行 deadline 返回 `expired`，执行超时/异常分别形成 TIMEOUT/ERROR 记录，client 进程和 invocation 事件流保持可用；
  - 每场景只向 runtime 提交最小 observation 文本或受治理引用；超阈值结果使用 payloadRef + 必要摘要，不把完整大结果、本地结构化对象、授权材料或异常堆栈放上 wire；
  - 服务端只收到最终工具结果，不得观察或驱动 client 内部审批步骤。
  - 原 Task 已终态时不得被结果提交重新推进；SDK 不再提交或把平台拒绝映射为明确 `task_terminal`/等价错误，副作用和最终结果均不重复。
- **不应断言**：审批 UI 实现、线程模型、内部存储 key、异常堆栈原文。
- **失败归类**：越权执行、终态 Task 被推进或错误结构不符为 Failure；公开终态触发入口缺失的该参数分支为 Skipped；夹具异常为 Error。
- **方法**：参数化 `feat007ActionToolAppliesLocalApprovalAndReturnsStructuredOutcome()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-007.action-governance: Action 本地治理与结构化失败")`、`@Tag("story-feat-007-action-governance")`。
- **DisplayName**：`Feat-007 Action 工具在客户端完成审批且失败可编程`。

### FEAT-007.idempotent-result - 重复请求投影与单次最终结果

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§5.1.4/§5.1.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 toolCallId 关联、单次最终结果、重复提交和多 pending 语义。
- **G**：真实 mainplan 在同一 INPUT_REQUIRED 中请求两个已暴露工具，每项有不同 `toolCallId`；在 Gateway 到 client 的公开流边界把其中一项重复投影；两个 handler 均有原子计数，且一项配置为结构化拒绝。
- **W**：client 接收完整 items 集合并执行治理；在较快项完成后观察尚未续跑，待两项均得到最终 outcome 后提交；随后重复投递相同 items 和相同最终结果。
- **T**：所有 pending item 按原顺序保留且各自关联正确 `toolCallId`；每个 handler 最多执行一次，拒绝项不执行副作用；结果未收齐前不续跑，收齐后只发送一条包含全部 TextPart 的 continuation，每个 Part 带对应 `metadata.toolCallId`；重复投影复用记录，重复结果返回同一结果或明确冲突；原 Task 只推进一次并完成。
- **不应断言**：本地去重表、outbox/ACK 写入顺序或并发实现；只断言公开执行计数和结果。
- **失败归类**：重复执行、遗漏 pending 或二次推进为 Failure；正式投影合同缺失为 Skipped；重放夹具异常为 Error。
- **方法**：`feat007DuplicateToolProjectionExecutesOnceAndSubmitsOneFinalResult()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-007.idempotent-result: 重复工具投影与单次最终结果")`、`@Tag("story-feat-007-idempotent-result")`。
- **DisplayName**：`Feat-007 重复工具请求只执行一次并提交一个最终结果`。

## 5. 文件、执行与退出标准

SPI bean 能装配、client 能启动、工具目录存在都不能单独证明注册/暴露/执行通过，已由上述真实 invocation 闭环等价覆盖。工具描述完整性、register/replace 冲突和动态可用性作为现有用例的参数分支，不新增测试文件。

计划仅新增：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
  Feat007LocalToolBlackboxTest.java
```

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`。travel JAR 坐标见 §1.1。正式 agent-client 坐标、Gateway 别名和 runtime `metadata.clientTools` 合同未交付，是当前门禁。确定性 LLM 响应、工具 payload 和 canary 由版本化测试资源生成，不要求人工导入。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-007 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-007-action-governance test
```

测试结束关闭 client、Agent/Gateway、确定性 LLM 和故障代理，恢复网络并确认端口释放。退出标准：Feature 当前所有 MUST 均通过或具有明确门禁；没有直接调用 handler、fake Agent、内部表或固定自然语言 Oracle；敏感信息和本地路径不进入报告。
