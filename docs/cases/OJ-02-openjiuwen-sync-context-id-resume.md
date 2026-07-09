---
id: OJ-02
title: openjiuwen — A2A 同步 contextId 续接（协议层）
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P0
feature: agent-runtime-java A2A 协议层 contextId 契约（SendMessage 同步路径）
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent，managed 模式）
tags: [component, openjiuwen]
depends_on:
  - OJ-01 同类前置（mainplan jar + LLM + openjiuwen profile）
  - a2a-sdk 服务端缺省 contextId 时自动分配 UUID（SimpleRequestContextBuilder）
---

# OJ-02 — openjiuwen A2A 同步 contextId 续接

> **一句话**：在 openjiuwen mainplan 上串行两次同步 `message/send`——Turn 1 **不**传
> `contextId`，Turn 2 **显式带回** Turn 1 的 `Task.contextId`——验证服务端分配 ID、
> 续轮不篡改、且两轮 `taskId` 互异。
>
> **关键定位**：OJ-01 验证「能通」；OJ-02 验证「会话 ID 协议契约」。参考 A-11-2，
> 但改为 **managed openjiuwen 栈 + 同步 send**，不连远端预部署 SUT。

---

## 1. 规约与机制

| 维度 | 依据 |
|------|------|
| 缺省分配 contextId | a2a-sdk `SimpleRequestContextBuilder`：客户端未设 `message.contextId` 时服务端生成 UUID |
| 续轮稳定性 | agent-runtime `A2aAgentExecutor` 从 `RequestContext.getContextId()` 派生 session，不重写已有 contextId |
| taskId 互异 | 每次 `message/send` 创建新 Task；contextId 表示会话，taskId 表示单次任务 |
| 同步路径 | 栈 `streaming(false)`；每轮 `sendMessage` 阻塞至 `isFinal()` |

---

## 2. 场景目标

1. Turn 1 省略 `contextId` → 服务端返回非空 `Task.contextId`；
2. Turn 2 显式设置 `Message.contextId = turn1ContextId` → 返回相同 `contextId`；
3. 两轮 `task.id` 不同；
4. 两轮均到达终态（`COMPLETED` / `INPUT_REQUIRED` 等 `isFinal()` 均可，与 A-11-2 同口径）。

**本用例不覆盖**：

- 业务语义续轮（Turn2 是否理解 Turn1）→ OJ-03
- 并发隔离 → OJ-04
- 流式 `message/stream` → 后续扩展

---

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | managed 拉起 mainplan，`streaming(false)` | `SutStack` | 就绪 |
| 2 | Turn 1：`Message.builder().role(USER).parts(TextPart("hi")).build()` **无 contextId** | `message/send` | 终态 `isFinal()` |
| 3 | 记录 Turn 1 的 `contextId`、`taskId` | A2aEventCollector | 均非空 |
| 4 | Turn 2：同样文本，**显式** `.contextId(turn1ContextId)` | `message/send` | 终态 `isFinal()` |
| 5 | 记录 Turn 2 的 `contextId`、`taskId` | — | 见 §4 |
| 6 | 断言 OJ-02.A / B / C | — | 见 §4 |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-02.A — Turn 1 服务端分配 contextId

- **Given**：Turn 1 Message 未设置 `contextId`。
- **When**：终态后读 `Task.contextId`。
- **Then**：非空、非空白。
- **PASS**：满足。**FAIL**：null / ""。

### OJ-02.B — Turn 2 contextId 与 Turn 1 一致

- **Given**：Turn 2 显式携带 Turn 1 的 `contextId`。
- **When**：终态后读 Turn 2 的 `Task.contextId`。
- **Then**：`turn2ContextId.equals(turn1ContextId)`。
- **PASS**：相等。**FAIL**：不等（服务端篡改或重生成）。

### OJ-02.C — 两轮 taskId 互异

- **Given**：两轮均取得 `task.id`。
- **When**：比较。
- **Then**：`turn1TaskId != turn2TaskId`。
- **PASS**：不等。**FAIL**：相等。

---

## 5. 测试数据

无外部文件。Turn 1 / Turn 2 固定文本 `"hi"`（与 A-11-2 同口径，契约判定与业务回答无关）。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/OpenjiuwenSyncContextIdTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `.streaming(false).agent("mainplan")` |
| 客户端 | **不**走 `InteractionFlow`（DSL 会自动续 contextId，屏蔽协议观测面）；直接 `a2aClient.sendMessage(Message, ...)` |
| 参考实现 | `ServerAssignedContextIdTest`（改为 managed + openjiuwen + sync） |

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSyncContextIdTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 同步（协议续接） | OJ-02.A / B / C | ✅ |

---

## 9. 风险与备注

- **勿用 InteractionFlow**：首轮必须显式省略 contextId；DSL 的自动续接会掩盖 OJ-02.A。
- **终态类型无关**：mainplan + LLM 对 "hi" 可能 COMPLETED 或 INPUT_REQUIRED，均满足 `isFinal()`。
- **与 OJ-03 边界**：OJ-02 只验协议 ID；OJ-03 验业务上下文是否延续。
