---
id: A-07
title: 流式模式 — 一次性完整输入问答
module: A — A2A 协议与通讯模型
owner: liubin
priority: P1
feature: 特性3 / 3-1 流式模式（message/stream）
status: designed（TC 已落地，待人工拉通三级链路）
sut: mainplan→trip→hotel（三级链）
stack: mainplan（downstream trip）+ trip（downstream hotel）+ hotel（叶子）
tags: [integration]
depends_on: [A-01（mainplan 可发现）、三级链路 wiring、真实 LLM]
---

# A-07 — 流式模式：一次性完整输入问答

> **一句话**：一次 `message/stream` 提交**完整出差需求**，mainplan 经 mainplan→trip→hotel
> 三级远程 a2a 派发，**单轮即到达 `COMPLETED` 且有有效回答**（完整行程），并断言流式状态序列为
> `SUBMITTED → WORKING → COMPLETED`。
>
> **关键定位**：这是首个覆盖**全链路**的用例。A-01/A-02 只起单 agent（无需 LLM、无需链路）；
> A-07 必须三智能体同时在线、真实 LLM 驱动 ReAct + 远程 `dispatch_travel_plan`，故与 A-01/A-02
> 共用框架但**栈不同**（全链 vs 单 agent）。

---

## 1. 规约与机制（基于被测代码与 a2a-sdk，非 SUT 自述）

| 维度 | 依据 |
|------|------|
| 流式通讯 | a2a-sdk `Client.sendMessage(Message, consumers, err, ctx)` + `ClientConfig.setStreaming(true)` —— JSONRPC transport 下即 `message/stream`；consumers 收一系列 `TaskEvent`/`TaskUpdateEvent`，末个承载终态 `Task` |
| 状态序列 | 每个 task-bearing 事件的 `Task.status().state()` 即该时刻状态；按到达顺序去重得状态轨迹：`SUBMITTED → WORKING → COMPLETED`（`WORKING` 的多次进度更新只计一次） |
| 终态判定 | `Task.status().state()` ∈ {COMPLETED, INPUT_REQUIRED, AUTH_REQUIRED, CANCELED, FAILED, REJECTED}；非终态 SUBMITTED/WORKING |
| 三级链 wiring | `agent-runtime.remote-agents[0].url`（仅 base，不带 `/a2a`）：mainplan→trip→hotel；元素结构 `RemoteAgent(String url)`，无 name 字段（派发工具名由被测从下游 Card `name` 派生） |
| mainplan LLM | `@Value("${main-plan-agent.api-key:}")` 等；agent **首条消息时**才构造（`createOpenJiuwenAgent`），缺 api-key 会报错——故 A-01 不需要 LLM，A-07 必须配齐 |
| 有效回答来源 | `Task.artifacts()` 的 text part（回退 `status().message()`、`history()` 末条） |

## 2. 用例设计

| 项 | 内容 |
|----|------|
| **触发** | `message/stream`，文本 = "明天从上海到北京出差3天，住宿2晚（含差标/偏好）" |
| **栈** | mainplan(→trip) + trip(→hotel) + hotel（叶子），`SutStack` leaf-first 自动 wiring；默认 `streaming=true` ⇒ 流式 `message/stream` |
| **期望状态序列** | `SUBMITTED → WORKING → COMPLETED`（按到达去重断言） |
| **期望回答** | 终态 `COMPLETED`；回答文本非空且**实质**（完整行程，非空错误/拒绝，`length>8`） |
| **不做** | 不校验回答具体措辞（黑盒，避免固化 SUT 输出） |

> 输入一次给齐：出发地（上海）、目的地（北京）、时长（3天）、住宿（2晚）。信息完整，mainplan
> 无需触发 `request_user_input` rail，应直接 dispatch→trip→hotel→返回。

## 3. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/StreamingTravelPlanningTest.java`（**与 A-08 共用全链栈**，继承 `BaseManagedStackTest`） |
| 方法 | `oneShotRequestStreamsSubmittedWorkingCompleted()`（A-07.A） |
| 客户端调用 | `InteractionFlow.of(client("mainplan")).send(COMPLETE_REQUEST).awaitState(COMPLETED).assertThat(ctx -> 序列断言).assertTask(...).execute()`；栈以默认 `streaming=true` 构造→流式 `message/stream`。`InteractionFlow` 每轮 await 终态后对事件快照断言：`distinctStatesInOrder(ctx.events())` == `[SUBMITTED, WORKING, COMPLETED]`，再 `textOf` 抽文本断言非空且 `length>8` |
| 配置 | `LLM_*` 环境变量（三机共用一组）；HTTP 代理经 `application-local.yml` 的 `sut.java.system-properties` 以 `-D` 注入 |
| 标签 | `@Tag("integration")` |

## 4. 当前状态与启用步骤

**全链路远程 a2a 派发仍在联调**。启用前：

1. 配齐统一的 `LLM_*` 环境变量（三机共用一组）；需 HTTP 代理时在 `application-local.yml` 的 `sut.java.system-properties` 配置。
2. 确认 `~/.m2` 中三个 fat-jar 存在（`agent-travel-mainplan-a2a` / `agent-trip-a2a` / `agent-hotel-a2a`，0.1.0-SNAPSHOT）。
3. `./mvnw -Dtest=StreamingTravelPlanningTest test`，从 A-07.A 起逐个验证。

## 5. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性3-1 流式模式 | message/stream 单轮：状态序列 SUBMITTED→WORKING→COMPLETED + 有效回答 | ✅ |
| 特性1-2 远程 Agent(YAML) | mainplan→trip→hotel 三级链派发 | ✅（与 B-10 重叠） |
| 特性4-2 message/stream | 流式调用返回状态序列与终态 | ✅ |
