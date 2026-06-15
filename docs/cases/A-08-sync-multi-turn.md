---
id: A-08
title: 同步模式 — 多次请求补充输入场景
module: A — A2A 协议与通讯模型
owner: liubin
priority: P1
feature: 特性3 / 3-1 同步模式（message/send）
status: designed（TC 已落地，@Disabled 待人工拉通三级链路）
sut: mainplan→trip→hotel（三级链）
stack: mainplan(ENTRY) + trip(MIDDLE) + hotel(LEAF)
tags: [integration]
depends_on: [A-07（同一全链栈，先调通 A-07.A）、三级链路 wiring、真实 LLM]
---

# A-08 — 同步模式：多次请求补充输入场景

> **一句话**：首轮**信息不全** → mainplan 触发 `request_user_input` rail，返回 `INPUT_REQUIRED`
> 且有有效回答（追问缺字段）；用户**在同一 `contextId` 上补齐信息**续轮 → 链路完成派发，
> 返回 `COMPLETED` 且有有效回答（完整行程）。**每轮都有有效回答**。
>
> **关键定位**：A-08 在 A-07 的全链栈上额外验证两点——(1) mainplan 的多轮信息收集 rail
> （`INPUT_REQUIRED` 终态）；(2) 同步模式下用 `contextId` **续轮**继续同一任务。与 A-07 共用栈与测试类。

---

## 1. 规约与机制（基于被测代码与 a2a-sdk）

| 维度 | 依据 |
|------|------|
| INPUT_REQUIRED | mainplan 注册 `UserInputInterruptRail` + `RequestUserInputTool`；信息不全时任务停在 `TASK_STATE_INPUT_REQUIRED`（终态），其 `status().message()` 携带追问文本 |
| 续轮（多轮） | `Message` 携带同一 `contextId`（a2a-sdk `Message.builder(A2A.toUserMessage(text)).contextId(cid)`），服务端按 context 关联会话；`MessageSendParams` 无独立 taskId 字段，靠 contextId 续接 |
| 有效回答来源 | `Task.artifacts()` text part；INPUT_REQUIRED 轮的回答在 `status().message()` |
| 三级链 wiring | 同 A-07：`agent-runtime.remote-agents[0].url` 串 mainplan→trip→hotel |

## 2. 用例设计

| 轮次 | 输入 | 信息完整度 | 期望终态 | 有效回答 |
|------|------|-----------|---------|---------|
| Turn 1 | "到北京出差3天" | 有目的地+时长，**缺出发日期/出发地** | `INPUT_REQUIRED` | 追问文本（非空） |
| Turn 2 | "明天从上海出发" | 补齐：明天/上海/北京/3天 | `COMPLETED` | 完整行程（非空、实质） |

- Turn 2 **必须复用 Turn 1 的 `contextId`**，否则 mainplan 视为新会话、丢失已收集信息。
- 两轮都断言"有效回答非空"；Turn 2 额外断言回答 `length>8`（实质，非空错误）。

> 与 B-01/B-02（Checkpointer 多轮连续性）的区别：B-* 关注**状态持久化中间件**；A-08 关注
> **同步模式 + INPUT_REQUIRED 终态 + contextId 续轮**的通讯契约。二者在全链路上有重叠，
> 但断言视角不同（协议终态 vs 上下文记忆）。

## 3. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/SyncTravelPlanningTest.java`（**与 A-07 共用全链栈**，继承 `BaseManagedStackTest`，避免重复拉起三级链） |
| 方法 | `incompleteThenFollowUpProgressesInputRequiredToCompleted()`（A-08.A，`@Disabled`） |
| 客户端调用 | 复用既有 `sendMessage` 流程（栈以 `streaming(false)` 构造→同步 `message/send`，阻塞至终态）：Turn1 `sendAndCollect(mainplan, INCOMPLETE_TURN_1, null)` → 断言 `INPUT_REQUIRED`；取 `task.contextId()`；Turn2 `sendAndCollect(mainplan, INCOMPLETE_TURN_2, contextId)`（同 contextId 续轮）→ 断言 `COMPLETED` + 文本非空；`textOf` 抽文本断言 |
| 配置 | `LLM_*` 环境变量（三机共用一组）；HTTP 代理经 `application-local.yml` 的 `sut.java.system-properties` 以 `-D` 注入 |
| 标签 | `@Tag("integration")` |

## 4. 当前状态与启用步骤

**全链路远程 a2a 派发仍在联调**，本用例 `@Disabled`。**先调通 A-07.A**，再启用本例：

1. 配齐统一的 `LLM_*`（三机共用一组）；需 HTTP 代理时在 `application-local.yml` 的 `sut.java.system-properties` 配置。
2. 确认 mainplan 的 `request_user_input` rail 对"到北京出差3天"确实追问出发信息（需与开发确认触发条件）。
3. 移除 `@Disabled`，`./mvnw -Dtest=SyncTravelPlanningTest test`。

> 若首轮未触发 `INPUT_REQUIRED` 而直接 `COMPLETED`/`FAILED`，说明 rail 触发条件与设计不符——
> 保留用例、记录实测行为，与开发对齐 rail 触发策略（黑盒不默认 SUT 行为正确）。

## 5. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性3-1 同步模式 | message/send 多轮：INPUT_REQUIRED → 续轮 COMPLETED | ✅ |
| 特性1-1 OpenJiuwenAdapter | ReAct + request_user_input rail 多轮信息收集 | ✅（与 C-03 重叠） |
| 特性2-1 Checkpointer | 同 contextId 续轮保持上下文 | ✅（与 B-01/B-02 视角互补） |
