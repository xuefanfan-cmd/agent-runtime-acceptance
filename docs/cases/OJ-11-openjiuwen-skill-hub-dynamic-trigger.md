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
tags: [integration, openjiuwen, nightly]
depends_on:
  - 第二步 S-04：`HotelSkillSupport` + `skills/hotel_ranking/SKILL.md`
  - LLM 可用（须能按 skill 工作流调用 readFile / hotel_search / executeCode）
---

# OJ-11 — openjiuwen skill-hub 动态触发

> **一句话**：仅拉起 hotel（8093，`skill` profile），经 A2A **流式** `message/stream` 发送明确触发
> **`hotel_ranking` skill** 的差旅酒店排序任务，验证回复 **体现 skill 工作流执行结果**
>（排序模板、`[符合差标]` 标签、`推荐：…` 尾行），而非仅 Agent Card 中的 skill 元数据声明。
>
> **关键定位**：openjiuwen 第三步 **skill-hub 主测点**；参考 core `SkillUseExample`，
> SUT 为 travel-openjiuwen 内置 `hotel_ranking` skill。

---

## 1. 场景目标

验证 hotel agent 在 `skill` profile 下能：

1. 通过 `registerSkill` 加载 bundled `hotel_ranking/SKILL.md`；
2. 注册 LOCAL sysop `readFile` / `executeCode`（`HotelSkillSupport.addSkillSysOpTools`）；
3. 用户显式要求使用 `hotel_ranking` skill 时，ReActAgent 按 SKILL.md workflow 执行：
   - 调用 `hotel_search`（mock 数据，禁止编造）；
   - 用 `executeCode` 排序；
   - 按 Output Template 输出 markdown。

**本用例不覆盖**：

- GitHub 远程 skill 拉取
- `SKILLS_DIR` 自定义目录（→ 可选扩展 OJ-11b）
- skill + sandbox 同时启用（README 警告工具重复；本用例 **仅** `skill` profile）
- MCP `district_hint`（→ OJ-08）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-openjiuwen-travel-hotel:0.1.0` 在 ~/.m2 | **FAIL** |
| 2 | hotel `profile=skill`（**非** sandbox,mcp 组合） | skill 工具链不完整 → **FAIL** |
| 3 | 启动日志含 `Registered skill hub from ...` | 可选 meta |
| 4 | LLM 可用 | **FAIL** / INCONCLUSIVE |
| 5 | mock `hotels.json` 含 testcase 城市数据 | hotel_search 空 → **FAIL** |
| 6 | 栈 `streaming(true)`，客户端 `message/stream` | — |
| 7 | **无需** jiuwenbox / Mock MCP / Redis | skill 使用 **LOCAL** executeCode |

---

## 3. 场景步骤

| # | 动作 | 协议 | 预期 |
|---|------|------|------|
| 1 | 拉起 hotel：`profile=skill` | SutStack | 就绪 |
| 2 | SendStreamingMessage：见 testdata `inputText`（含 skill 名 + 完整出差要素） | message/stream | 终态 COMPLETED |
| 3 | 断言 OJ-11.A / B / C / D | — | 见 §4 |

**推荐 inputText 示例**（testdata 可微调）：

```text
请使用 hotel_ranking skill，为以下需求排序并推荐酒店：
城市：北京；入住 2026-07-10，离店 2026-07-13；每晚价格上限 800 元；最低 4 星；偏好国贸附近。
```

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-11.A — 终态 COMPLETED

- **Then**：`TaskState == COMPLETED`；回复非空。
- **PASS** / **FAIL** / **INCONCLUSIVE**：同前序用例。

### OJ-11.B — skill 工作流痕迹（弱语义，正向）

- **When**：`textOf(task)`。
- **Then**：至少满足 testdata `mustMatchAny` 中 **2 项**（启发式）：
  - 含 `hotel_ranking` 或「排序」类表述（可选，LLM 可能省略 skill 名）；
  - 含 **编号列表** 形态（如 `1.` 开头）或 Output Template 字段（`★` 星级、`¥` 价格）；
  - 含 `[符合差标]` 或 `[不符合差标]` 标签（SKILL.md Policy tags）；
  - 含尾行 `推荐：`（SKILL.md 第 6 步）。
- **PASS**：≥2 项命中。**FAIL**：像普通 hotel_search 裸列表，无 skill 模板特征。

### OJ-11.C — 业务要素承接

- **Then**：文本含「北京」；含价格或星级相关表述（`800`、`4星`、`四星` 等任一）。
- **PASS** / **FAIL**：完全未提及目的地。

### OJ-11.D — 非仅 Card 声明

- **Then**：回复 **不是** 仅复述「我支持 hotel_ranking skill」而无具体酒店条目。
- **FAIL**：无具体酒店名 / 无排序列表（仅 capability 描述）。

---

## 5. 测试数据

`src/test/resources/testdata/openjiuwen/integration/oj-11-skill-hotel-ranking.json`

```json
{
  "inputText": "请使用 hotel_ranking skill，为以下需求排序并推荐酒店：城市：北京；入住 2026-07-10，离店 2026-07-13；每晚价格上限 800 元；最低 4 星；偏好国贸附近。",
  "expectedTerminalState": "COMPLETED",
  "mustMatchAny": [
    "[符合差标]",
    "[不符合差标]",
    "推荐：",
    "★",
    "¥"
  ],
  "mustMatchMinCount": 2,
  "mustMentionCity": "北京",
  "mustNotBeOnlyCapabilityBlurb": true,
  "timeoutMs": 180000
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/integration/OpenjiuwenSkillHubTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；建议 `@Tag("nightly")` |
| 栈 | `.streaming(true).agent("hotel", a -> a.profile("skill"))` |
| 客户端 | `A2aEventCollector`；参考 A-04 / A-07 |
| 断言 | AssertJ + `OpenjiuwenTextAssertions` 扩展 skill 模板计数 |
| 参考 | `agent-core-java/examples/skill_use/SkillUseExample.java`；`HotelSkillSupport.java`；`skills/hotel_ranking/SKILL.md` |

---

## 7. 运行方式

```bash
cd agent-runtime-acceptance
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSkillHubTest test
```

---

## 8. 覆盖特性追溯

| 能力（0.2.0 七项） | 子断言 | 覆盖 |
|-------------------|--------|------|
| skill-hub | OJ-11.A / B / C / D | ✅ |

---

## 9. 风险与备注

- **LLM 非确定性**：可能跳过 `executeCode` 直接格式化；B 断言 intentionally 弱，FAIL 时保留全文日志。
- **日期占位**：inputText 中日期可改为相对表述（「下周」）若 mock 数据不敏感；默认固定日期便于复现。
- **与 OJ-09 工具差异**：skill 的 `executeCode` 为 LOCAL sysop；sandbox profile 的 `executeCode` 走 jiuwenbox——**勿混 profile**。
- **全链扩展**：mainplan 委派 hotel 时测 skill 为可选 OJ-11c，非 0.2.0 最低标准。
