---
id: A-11-2
title: 同步模式 — 服务端自动分配 contextId（协议层）
module: A — A2A 协议与通讯模型
owner: TBD
priority: P1
feature: 特性4 / A2A 协议层 contextId 契约
status: designed
sut: 任意已部署 A2A Agent（默认走 `sut.base.url` —— 当前指向 7.209.189.82:13003 mainplan）
stack: 外部已部署 SUT（component 层），不自管栈
tags: [component, smoke]
depends_on:
  - sut.base.url 指向一个可达的 A2A 端点
  - 该端点能在一次 `message/send` 后到达 **任一 final 状态**（COMPLETED / INPUT_REQUIRED / FAILED / CANCELED / AUTH_REQUIRED / REJECTED 均可）
---

# A-11-2 — 服务端自动分配 contextId

> **一句话**：客户端**不**在 `message.contextId` 上传任何值时，服务端必须在返回的 `Task.contextId`
> 上分配一个非空值；客户端将该值带回作为后续 `message.contextId`，服务端**不得**篡改，且两轮的
> `task.id` 必须互异（每次 send 都是新 task，但同一会话 contextId 透传）。
>
> **关键定位**：A-11-1（并发隔离）验证"客户端显式带 contextId 时不串扰"；A-08（INPUT_REQUIRED
> 续轮）验证"客户端显式带 contextId 时业务上下文延续"。A-11-2 是上述两条线的**前置协议契约**：
> 当客户端**省略** `contextId` 时，服务端必须先发地分配一个稳定 ID 供后续轮使用——这是
> A2A `message/send` 协议层不可省略的承诺，与具体 Agent 的业务无关。

---

## 1. 规约与机制（基于 a2a-sdk 与被测代码）

| 维度 | 依据 |
|------|------|
| 缺省时自动分配 | a2a-sdk 服务端 `SimpleRequestContextBuilder.build()` —— 客户端 `MessageSendParams.message.contextId` 为空时，SDK 在构建 `RequestContext` 时生成 UUID，写回 `Task.contextId` |
| 续轮稳定性 | `A2aAgentExecutor.toExecutionContext` 从 `RequestContext.getContextId()` 读取并派生 `RuntimeIdentity.sessionId`，不会重写已存在的 contextId |
| taskId 互异 | a2a-sdk 服务端每次 `message/send` 都创建新 Task（任务 vs 会话的分层），不复用旧 `taskId` |
| 协议无关业务 | 上述行为属 a2a 协议层，与具体 Agent 的业务输出无关。**终态类型也无关**——只要 `TaskState.isFinal()=true`（含 COMPLETED / INPUT_REQUIRED 等任一），契约即可观测 |

> 客户端侧的对应模式见 [InteractionFlow](../../src/main/java/com/huawei/ascend/sit/client/InteractionFlow.java)：
> 第一轮 `A2A.toUserMessage(text)` **不**写 contextId；后续轮经 `A2aEventCollector.findFirstContextId()`
> 取上一轮的 `Task.contextId` 并 `Message.builder(...).contextId(prev).build()` 续轮。A-11-2 是该模式的协议层断言。

## 2. 用例设计

| 项 | 内容 |
|----|------|
| **触发** | 同一进程串行两次 `message/send` |
| **Turn 1** | `Message.builder().role(USER).parts([TextPart("hi")]).build()` —— **不**设置 `contextId` |
| **Turn 2** | `Message.builder().role(USER).parts([TextPart("hi")]).contextId(<Turn 1 返回的 Task.contextId>).build()` |
| **观测面** | 两轮的 `Task.contextId` / `Task.id`，仅经 a2a-sdk 客户端读取，不读 SUT 内部 |
| **期望** | 两轮均到达 `isFinal()` 状态；Turn 1 `contextId` 非空；Turn 2 `contextId` == Turn 1 `contextId`；`taskId` 互异 |
| **不做** | 不校验回答措辞 / artifact 业务语义（黑盒）；不要求 Turn 1 必须 COMPLETED——LLM 对模糊输入返 INPUT_REQUIRED 也不影响契约判定 |

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 拿到 `a2aClient`（默认连 `sut.base.url` = 7.209.189.82:13003 mainplan） | `BaseComponentTest` | 客户端可调用 |
| 2 | Turn 1：发送 `Message.builder()...parts(["hi"]).build()`（**不**带 contextId） | a2a-sdk `Client.sendMessage` | 收到 task-bearing 事件，状态到达 `isFinal()`（COMPLETED 或 INPUT_REQUIRED 均可） |
| 3 | 抽出 Turn 1 的 `Task.contextId` 与 `Task.id` | `A2aEventCollector` | `contextId` 非空、`taskId` 非空 |
| 4 | Turn 2：发送同样文本，**显式带** `Message.builder()...contextId(turn1ContextId).build()` | a2a-sdk `Client.sendMessage` | 同样到达 `isFinal()` 状态 |
| 5 | 抽出 Turn 2 的 `Task.contextId` 与 `Task.id` | `A2aEventCollector` | 见 §4 |
| 6 | 断言 A-11-2.A / B / C | — | 见 §4 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 a2a-sdk 客户端事件流观测，不读 SUT 内部类 / 配置。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### A-11-2.A — Turn 1 不传 contextId 时服务端必须分配
- **Given**：Turn 1 的 `Message` 上未设置 `contextId`。
- **When**：等到 Turn 1 任务终态，读取 `Task.contextId`。
- **Then**：`contextId` 非空、非空字符串。
- **PASS**：满足。**FAIL**：`null` / `""`（违反 a2a-sdk 协议契约：服务端必须分配）。
- **INCONCLUSIVE**：不适用（任一 SUT 用 a2a-sdk 都必然走 `SimpleRequestContextBuilder`）。

### A-11-2.B — Turn 2 带回 Turn 1 contextId 时服务端不得篡改
- **Given**：Turn 2 的 `Message.contextId` 显式设为 Turn 1 的 `Task.contextId`。
- **When**：等到 Turn 2 任务终态，读取 `Task.contextId`。
- **Then**：`Turn 2 contextId == Turn 1 contextId`（字面相等）。
- **PASS**：相等。**FAIL**：Turn 2 contextId 与 Turn 1 不等（服务端篡改/重生成）。
- **INCONCLUSIVE**：Turn 1 因 A-11-2.A 已失败时本档不可判（前置缺失）。

### A-11-2.C — 两轮 taskId 互异（任务 vs 会话分层）
- **Given**：两轮均成功取得 `Task.id`。
- **When**：比较两个 `taskId`。
- **Then**：`Turn 1 taskId != Turn 2 taskId`（每次 send 都是新 task）。
- **PASS**：不等。**FAIL**：相等（服务端将 contextId 误等同于 taskId，或复用旧 task）。
- **INCONCLUSIVE**：任一轮 `taskId` 为空时（前置失败，归到通用流断言）。

## 5. 测试数据

无外部数据文件。Turn 1 / Turn 2 文本固定为 `"hi"`——文本内容与契约判定无关，
A-11-2 不依赖 SUT 对该字符串的业务回答。若日后默认 SUT 切换为强结构化输入的 Agent，
再外置到 `testdata/component/protocol/a11-2-context-id-cases.json`。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/ServerAssignedContextIdTest.java` |
| 标签 | `@Tag("component") @Tag("smoke")`（外部 SUT、单 boundary 协议契约，与 [A-01](A-01-agent-card-discovery.md) 同口径） |
| 基类 | `BaseComponentTest`（外部 SUT，不自管栈，地址走 `sut.base.url`） |
| 客户端调用 | `a2aClient.sendMessage(Message, metadata=null, consumers, errorHandler)` —— **不**走 `InteractionFlow`（DSL 自动续 contextId 屏蔽了"不带 contextId" 的协议面） |
| 事件收集 | 每轮独立 `A2aEventCollector` + `awaitTerminalState(timeoutMs)`；用 `findFirstContextId()` / `findFirstTaskId()` 取观测值 |
| 断言 | AssertJ：`isNotBlank` / `isEqualTo` / `isNotEqualTo` |
| 配置 | 沿用 `sut.base.url`（默认 7.209.189.82:13003 指向预部署 mainplan，见 `application-sit.yml`） |

## 7. 运行方式

```bash
# 仅本类（component 层）—— 默认连远端预部署 mainplan
./mvnw -Dtest=ServerAssignedContextIdTest test

# 或经 smoke 套件
./mvnw -Dtest=SmokeTestSuite test
```

> 前置：`sut.base.url` 指向的端点可达（默认 `application-sit.yml` 已配为
> `http://7.209.189.82:13003`）。无需本地启动任何进程；测试直接对远端 mainplan 发起 `message/send`。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 4-2 `message/send` 协议层 contextId 契约 | A-11-2.A / B / C | ✅ |
| 特性 2 Runtime session（首轮无 sessionId 时的派生路径） | A-11-2.A（侧面：派生需要服务端先分配 contextId 才能成立） | 🟡 间接 |

## 9. 风险与备注

- **与 A-11-1 / A-08 的边界**：
  - A-11-1 关注客户端**显式**带 contextId 且并发不串扰；
  - A-08 关注客户端**显式**带 contextId 续轮业务上下文延续；
  - A-11-2 关注客户端**省略** contextId 时服务端先发地分配 + 后续轮稳定不篡改。
  三者覆盖 contextId 在 `message/send` 上的三种典型形态，互为补充。
- **SDK 升级风险**：a2a-sdk 升级若改变 `SimpleRequestContextBuilder` 的默认值生成策略
  （例如把"缺省时分配"改为"缺省时返回 null 并交由 application 层补"），A-11-2.A 会暴露该回归——
  这是用例的预警价值，不能降级当作 INCONCLUSIVE。
- **SUT 切换**：默认 SUT 是 7.209.189.82:13003 上的 mainplan（LLM 驱动）。把"hi"传给 mainplan，
  LLM 大概率返回 INPUT_REQUIRED（追问）或 COMPLETED（简短问候）——**两者都满足 `isFinal()`**，
  契约判定与终态类型无关。若部署到无 LLM 的最简 SUT（如 PingPongDemo），通常落 COMPLETED，
  契约同样成立。所以测试代码对 SUT 类型不敏感，只对协议契约敏感。
- **不引入 InteractionFlow**：DSL 把首轮自动留空 contextId、后续轮自动带回的逻辑封进了 `executeRound`，
  对本用例而言反而屏蔽了我们要观测的协议面（首轮**显式无** contextId、第二轮**显式有** contextId）。
  直接用 `a2aClient.sendMessage(Message, ...)` + `Message.builder().contextId(...)` 更直观、断言意图更明确。
- **Tenant 不在范围**：A-11-2 不传 `X-Tenant-Id`，runtime 落 `"default"`（与 A-11-1 同口径）。