---
id: OJ-05
title: openjiuwen — 三轮信息收集（INPUT_REQUIRED 连续追问）
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P1
feature: agent-core-java UserInputInterruptRail + RequestUserInputTool + Checkpointer 跨 3 轮续接
status: designed
sut: agent-openjiuwen-travel-mainplan（入口）
stack: mainplan → trip → hotel（三 agent 全链，managed 模式）
tags: [integration, openjiuwen]
depends_on:
  - travel-openjiuwen 三 jar 已 install 至 ~/.m2
  - LLM 可用；mainplan→trip→hotel 链路互通
  - 参考 C-03 / StreamingTravelPlanningTest（SUT 换 openjiuwen）
---

# OJ-05 — openjiuwen 三轮信息收集（INPUT_REQUIRED）

> **一句话**：用户分 **3 轮**才说全差旅需求——mainplan 的 `UserInputInterruptRail` 应
> **连续追问两轮**（Turn1/2 终态 `INPUT_REQUIRED`），Turn3 补全后 `COMPLETED` 并给出
> 实质行程回复；三轮 **同一 contextId**，Checkpointer 累积已收集字段。
>
> **关键定位**：OJ-03 是两轮「上下文连续」；OJ-05 是 **rail 驱动的 3 轮 INPUT_REQUIRED
> 状态机**，也是第一步中 **唯一需要三 agent 全链** 的用例（Turn3 完整规划可能委派 trip）。
> 参考 C-03，SUT 改为 openjiuwen + `-Dtest.env=openjiuwen`。

---

## 1. 规约与机制

| 维度 | 依据 |
|------|------|
| INPUT_REQUIRED | `UserInputInterruptRail` + `RequestUserInputTool` |
| 连续追问 | Turn2 仍缺出发地 ⇒ 再次 INPUT_REQUIRED |
| contextId 续接 | 三轮同一 `contextId`；`InteractionFlow` 自动带入 |
| 全链 | Turn3 完整需求触发 trip → hotel；**必须**全链，勿放宽为单 mainplan |
| 传输模式 | 第一步默认 **同步** `streaming(false)`；sync 下不强制完整 SSE 状态序列 |

---

## 2. 场景目标

| 轮次 | 输入 | 期望终态 |
|------|------|---------|
| Turn 1 | 「我要去北京出差。出发地和行程天数都还没定，请先追问缺失信息，不要直接做行程规划。」 | INPUT_REQUIRED（硬等；COMPLETED → FAIL） |
| Turn 2 | 「出差3天，下周二出发。出发城市还没定，不是深圳，请先继续追问，不要调用行程规划。」 | INPUT_REQUIRED（硬等；COMPLETED → FAIL） |
| Turn 3 | 「从上海出发。差标：…偏好：…请据此完成完整行程规划。」 | COMPLETED；`textOf` length > 8 |

**本用例不覆盖**：Redis（→ OJ-06）、流式精确序列（→ OJ-05-S）、长期记忆（→ OJ-12）。

---

## 3. 前置条件

| # | 条件 | 不满足时 |
|---|------|----------|
| 1 | 三 jar 在 ~/.m2（`agent-openjiuwen-travel-*`） | 栈启动 **FAIL** |
| 2 | `-Dtest.env=openjiuwen` | 坐标错误 **FAIL** |
| 3 | LLM 可用 | Turn 失败 **FAIL** |
| 4 | managed leaf-first：hotel → trip → mainplan | wiring 失败 **FAIL** |

---

## 4. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | `SutStack` 全链 `streaming(false)` | 三 agent 就绪 |
| 2 | `InteractionFlow` Turn1（含防提前完成文案）`awaitState(INPUT_REQUIRED)` | 追问非空；COMPLETED → FAIL |
| 3 | Turn2（含防默认深圳文案）`awaitState(INPUT_REQUIRED)` | 再追问非空；COMPLETED → FAIL |
| 4 | Turn3 `awaitState(COMPLETED)`（超时 ≥300s） | 实质回复 length>8 |
| 5 | `FlowResult` 三轮 contextId 一致 | OJ-05.D |

---

## 5. 可观测子断言

### OJ-05.A / B — Turn1/2 均为 INPUT_REQUIRED，文本非空

- **FAIL**：直接 COMPLETED（信息被误判为已全）或 FAILED。
- **INCONCLUSIVE**：LLM 未触发 rail——记录实测。

### OJ-05.C — Turn3 COMPLETED 且实质回复

- **FAIL**：仍 INPUT_REQUIRED / FAILED / 空回复（含 trip 不可达）。

### OJ-05.D — contextId 三轮一致

- **Then**：`FlowResult` 三轮 `contextId` 相同。

---

## 6. 测试数据

外置参考：`src/test/resources/testdata/integration/react_travel/oj-05-three-turn-collection.json`  
文案在 C-03 形状上，Turn1/2 增加「防提前 COMPLETED / 防默认深圳」约束。  
超时：`max(sut.timeout.poll-seconds, 300s)`（全链 Turn3 常超默认 120s）。**不**使用 `main` ScenarioData。

---

## 7. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../cases/integration/react_travel/OpenjiuwenThreeTurnInputRequiredTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")` |
| 基类 | `BaseManagedStackTest` |
| 栈 | `SutStack.builder(config).streaming(false).agent("hotel").agent("trip", d→hotel).agent("mainplan", d→trip)` |
| 驱动 | `InteractionFlow` 三轮硬 `.awaitState(...)`（Turn1/2 勿放宽） |
| 超时 | flow 级 ≥ 300s |
| 配置 | `-Dtest.env=openjiuwen` |

> **不**引入 `OpenjiuwenStackSupport` / `OpenjiuwenThreeTurnRunner` / `model.openjiuwen.*`。  
> Turn1/2 **必须** INPUT_REQUIRED、Turn3 **必须** COMPLETED——勿套用 OJ-03/04 的终态放宽。

---

## 8. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenThreeTurnInputRequiredTest test
```

---

## 9. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| 短期记忆（3 轮累积） | OJ-05.C / D | ✅ |
| A2A 同步 + INPUT_REQUIRED 状态机 | OJ-05.A / B | ✅ |

---

## 10. 风险与备注

- **rail 策略漂移**：Turn1/2 直接 COMPLETED → **FAIL 并提缺陷**，勿 silently 改期望。
- **默认出发地 / 提前完成**：openjiuwen 易默认字段并跳过 rail；Turn1/2 文案必须显式阻断。
- **全链依赖**：Turn3 若 trip/hotel 不可达 → FAILED / 超时，先确认链路 smoke；超时偏紧时先抬到 ≥300s。
- **与 C-03 关系**：状态机等价；Turn1/2 文案相对 C-03 多防默认/防提前完成约束，SUT/profile 不同。
