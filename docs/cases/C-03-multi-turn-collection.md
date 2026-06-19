---
id: C-03
title: 端到端 — 多轮信息收集（3 轮）
module: C — 端到端业务场景与异常
owner: liubin
priority: P0
feature: 特性1 / 1-1 OpenJiuwenAdapter（ReAct + request_user_input rail）+ 特性2 / 2-1 Checkpointer（contextId 多轮续接）；与 A-07/A-08 共用流式栈 ⇒ 兼带 特性3-1 流式
status: designed（TC 已落地，待人工拉通三级链路 + 真实 LLM 验证 rail 多轮触发）
sut: mainplan→trip→hotel（三级链）
stack: mainplan（downstream trip）+ trip（downstream hotel）+ hotel（叶子）
tags: [integration]
depends_on: [A-08（同一测试类、同一全链栈；C-03 是 A-08 两轮的 3 轮推广）、三级链路 wiring、真实 LLM]
---

# C-03 — 端到端：多轮信息收集（3 轮）

> **一句话**：用户分 **3 轮**才把差旅需求说全——轮1 仅说"意图+目的地"、轮2 补"时长+日期"但**仍缺出发地**、
> 轮3 才补"出发地+差标+偏好"。期望 mainplan 的 `request_user_input` rail **追问两轮**再执行：
> 轮1 `SUBMITTED → WORKING → INPUT_REQUIRED`、轮2 `WORKING → INPUT_REQUIRED`、轮3 `WORKING → COMPLETED`，
> **每轮都有有效回答**（轮1/2 是追问文本，轮3 是完整行程）。
>
> **关键定位**：C-03 是 A-08（两轮）的 **3 轮推广**——验证 rail 在"信息仍不全"时**连续追问**而非凑合执行，
> 以及 Checkpointer 在同一 `contextId` 上**跨 3 轮累积**已收集字段（轮3 能引用轮1 的目的地、轮2 的日期/时长）。
> 与 A-07/A-08 共用全链栈与测试类，仅新增一个 `@Test` 方法。

---

## 1. 规约与机制（基于被测代码与 a2a-sdk）

| 维度 | 依据 |
|------|------|
| 连续 INPUT_REQUIRED | mainplan 注册 `UserInputInterruptRail` + `RequestUserInputTool`；信息不全时任务停在 `TASK_STATE_INPUT_REQUIRED`（终态）。轮2 仍缺出发地（核心字段，A-08 已证其缺失触发 INPUT_REQUIRED）⇒ rail 应再次追问而非执行 |
| 多轮续接（contextId） | `Message` 携带同一 `contextId`（a2a-sdk `Message.builder(A2A.toUserMessage(text)).contextId(cid)`）；`InteractionFlow` 自动把上一轮 `contextId` 带入下一轮。轮3 因此能看到轮1 的目的地、轮2 的日期/时长 |
| 续轮状态序列 | 续轮复用 context、任务已存在，首个观测状态为 `WORKING`，故轮2/轮3 的去重序列均从 `WORKING` 起步（无 `SUBMITTED`） |
| 有效回答来源 | `Task.artifacts()` text part；INPUT_REQUIRED 轮的回答在 `status().message()` |
| 三级链 wiring | 同 A-07/A-08：`agent-runtime.remote-agents[i].url` 串 mainplan→trip→hotel |

## 2. 用例设计

| 轮次 | 输入 | 信息完整度 | 期望状态序列 | 期望终态 | 有效回答 |
|------|------|-----------|-------------|---------|---------|
| Turn 1 | "我要去北京出差。" | 仅意图+目的地，**缺日期/时长/出发地** | `SUBMITTED → WORKING → INPUT_REQUIRED` | `INPUT_REQUIRED` | 追问文本（非空） |
| Turn 2 | "出差3天，下周二出发。" | +时长+日期，**仍缺出发地** | `WORKING → INPUT_REQUIRED` | `INPUT_REQUIRED` | 追问文本（非空） |
| Turn 3 | "从上海出发。差标：…偏好：…" | +出发地+差标+偏好（完整） | `WORKING → COMPLETED` | `COMPLETED` | 完整行程（非空、实质 length>8） |

- **轮2 的设计要点**：故意**继续扣留出发地**（只在轮3 给出）。出发地是 A-08 已验证会触发 `INPUT_REQUIRED` 的核心字段，故轮2 仍不全 ⇒ rail 预期再次追问。这是 C-03 区别于 A-08（两轮即完成）的关键。
- 三轮**必须复用同一 `contextId`**，否则 mainplan 视为新会话、丢失已收集信息（轮3 就无法引用轮1 的目的地）。
- 三轮都断言"有效回答非空"；轮3 额外断言回答 `length>8`（实质，非空错误）。
- 每轮独立的事件快照上断言各自的状态序列（`InteractionFlow` 每轮新建 collector）。
- 使用与 A-07/A-08 **不同的 `sessionId`**（`manual-session-002`），避免 checkpointer 跨用例串状态。

> 与 B-01/B-02（Checkpointer 多轮连续性）的区别：B-* 关注**状态持久化中间件**本身；C-03 关注
> **rail 连续追问 + 跨 3 轮 contextId 累积**的端到端业务契约。视角互补。

## 3. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/StreamingTravelPlanningTest.java`（**与 A-07/A-08 共用全链栈**，继承 `BaseManagedStackTest`，避免重复拉起三级链） |
| 方法 | `threeTurnCollectionFollowsExpectedStateSequences()`（C-03） |
| 客户端调用 | `InteractionFlow` 三轮 `.send(...)`（栈默认 `streaming=true`→流式 `message/stream`）：轮1 `.send(COLLECTION_TURN_1).awaitState(INPUT_REQUIRED).assertThat(序列==[SUBMITTED,WORKING,INPUT_REQUIRED]).assertTask(非空)`；轮2 `.send(COLLECTION_TURN_2).awaitState(INPUT_REQUIRED).assertThat(序列==[WORKING,INPUT_REQUIRED]).assertTask(非空)`；轮3 `.send(COLLECTION_TURN_3).awaitState(COMPLETED).assertThat(序列==[WORKING,COMPLETED]).assertTask(非空且 length>8)`。`InteractionFlow` 自动逐轮带入 `contextId` |
| 配置 | `LLM_*` 环境变量（三机共用一组）；HTTP 代理经 `application-local.yml` 的 `sut.java.system-properties` 以 `-D` 注入 |
| 标签 | `@Tag("integration")`（类级；与 A-07/A-08 同类） |

## 4. 当前状态与启用步骤

**全链路远程 a2a 派发仍在联调**。**先调通 A-07.A / A-08.A**，再启用本例：

1. 配齐统一的 `LLM_*`（三机共用一组）；需 HTTP 代理时在 `application-local.yml` 的 `sut.java.system-properties` 配置。
2. 确认 mainplan 的 `request_user_input` rail 对"轮2 仍缺出发地"确实**再次追问**而非凑合执行（需与开发确认 rail 的触发/收敛策略）。
3. `./mvnw -Dtest=StreamingTravelPlanningTest#threeTurnCollectionFollowsExpectedStateSequences test`（或整类）。

> **若 rail 未连续追问**（如轮2 直接 `COMPLETED`/`FAILED`，即把"目的地+时长+日期"视为足够、默认出发地），
> 说明 rail 收敛策略与本设计不符——保留用例、记录实测行为，与开发对齐 rail 触发条件（黑盒不默认 SUT 行为正确）。
> 若轮2/轮3 实际仍以 `SUBMITTED` 起步，说明续轮被当作新任务，需与服务端对齐 `contextId` 续接语义。

## 5. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性1-1 OpenJiuwenAdapter | ReAct + request_user_input rail **连续两轮**追问后再执行 | ✅ |
| 特性2-1 Checkpointer | 同 contextId 跨 **3 轮**累积已收集字段（轮3 引用轮1/2 信息） | ✅（与 B-01/B-02 视角互补） |
| 特性3-1 流式模式 | message/stream 多轮：序列 INPUT_REQUIRED→INPUT_REQUIRED→COMPLETED | ✅（与 A-08 共用流式栈） |
