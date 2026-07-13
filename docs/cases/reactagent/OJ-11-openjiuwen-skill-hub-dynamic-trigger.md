---
id: OJ-11
title: openjiuwen — skill-hub 动态触发
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P0
feature: hotel skill profile + bundled hotel_ranking SKILL.md + sysop readFile/executeCode
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel（单 agent，`skill` profile；**不** 与 sandbox 同启）
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-04：`HotelSkillSupport` + `skills/hotel_ranking/SKILL.md`
  - LLM 可用
---

# OJ-11 — openjiuwen skill-hub 动态触发

> **一句话**：仅 hotel（`skill` profile），流式触发 `hotel_ranking` skill，硬等 `COMPLETED`，
> 回复体现 skill 工作流模板（差标标签 / 推荐 / 星级价格），且日志有真实 `readFile(SKILL.md)`。

---

## 1. 场景目标

1. hotel：`streaming(true)` + `profile=skill`（**不**组合 sandbox/mcp）；
2. `InteractionFlow` 单轮；终态 **硬等** `COMPLETED`；
3. 弱语义 B/C/D + OJ-11.E 日志门禁。

**不覆盖**：远程 skill、自定义 `SKILLS_DIR`、skill+sandbox 同启、MCP。

---

## 2. 前置条件

hotel jar 在 ~/.m2；`-Dtest.env=openjiuwen`；**无需** jiuwenbox / Mock MCP / Redis。

---

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | `SutStack` hotel + skill profile | OJ-11 profile / skill hub 门禁 |
| 2 | `InteractionFlow` send hotel_ranking 需求 | COMPLETED + B/C/D |
| 3 | `assertSkillWorkflowToolsSucceeded` + hotel 探针 | OJ-11.E / P |

---

## 4. 子断言

- **OJ-11.A**：COMPLETED  
- **OJ-11.B**：`mustMatchAny` ≥ 2（`[符合差标]` / `推荐：` / `★` / `¥` 等）  
- **OJ-11.C**：含「北京」+ 价格/星级关键词  
- **OJ-11.D**：编号列表或具体酒店名，非仅能力介绍  
- **OJ-11.E**：日志 `End to read file`；无 readFile SKILL.md 越权失败  

---

## 5. 测试数据

外置参考：`testdata/component/singleagent/oj-11-skill-hotel-ranking.json`  
文案写在测试常量；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../singleagent/OpenjiuwenSkillHubTest.java` |
| 栈 | `streaming(true).agent("hotel", profile=skill)` 内联 |
| 驱动 | `InteractionFlow`：`awaitState(COMPLETED)` |
| 弱语义 | `OpenjiuwenSkillHubTest` 类内私有断言（原始参数，无 ScenarioData） |
| 门禁 | `OpenjiuwenSkillHubTest` 类内日志、工具和 HTTP 私有断言 |

> **不**引入 `OpenjiuwenStackSupport` / Runner / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSkillHubTest test
```
