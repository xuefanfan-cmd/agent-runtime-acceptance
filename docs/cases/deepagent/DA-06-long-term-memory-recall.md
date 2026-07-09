---
id: DA-06
title: deep-research 长期记忆跨轮复述（流式）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P1
feature: A2A 流式 + deep-research 长期记忆
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ 默认 `streaming(true)`
tags: [integration, deepagent, manual]
depends_on:
  - deep-research 已启动并监听 :18090
  - 算子先手工重启 deep-research（§6 已知：本场景当前存在需手工重置的已知 bug）
  - search agent 已启动并监听 :18091（turn1 需要真实搜索能力）
---

# DA-06 — deep-research 长期记忆跨轮复述（流式）

> **一句话**：同一 `contextId` 内发两轮 SSE 消息——turn1 询问 DeepSeek API 定价（触发搜索），
> turn2 用同 contextId 让 agent"直接复述上一轮的答案要点，不要再搜索"。
> turn2 artifact 应同时命中专有名 `"DeepSeek"` 与至少一个话题词（定价 / token / 价格）。

---

## 1. 场景目标

对 deep-research **长期记忆跨轮复述能力**（流式路径）做端到端验证：

1. 两轮 send 共享 `contextId`——都走默认 `streaming(true)`，与 [deepagent测试结果.txt §6](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt)
   手工脚本口径一致。
2. turn1 询问 DeepSeek 官方 API 输入 token 定价（会真实触发下游 search agent + LLM）；turn2 指令
   "直接复述、不要再搜索"，考察 agent 从长期记忆（消息历史 / 检索缓存 / summarizer）里复述 turn1 主题。
3. turn2 artifact 必须同时命中：
   - **专有名**：`"DeepSeek"`——turn2 用户 query 本身没有"DeepSeek"，命中就是 agent 从记忆里
     捞回来的干净信号。
   - **至少一个话题词**：`"定价"` / `"token"` / `"价格"`——避免只念了"DeepSeek"却没复述主题。
4. **bug 断言**：任一轮 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL——这也是 §6 备注里"运行前需算子手工重启 agent"的
   已知 bug 触发信号。

## 2. 前置条件

- **算子操作**：本场景当前存在需手工重启的已知 bug——测试前需先重启 deep-research jar。
  为避免 CI nightly 误报，本档 `@Tag("manual")` + `Assumptions.assumeTrue(agentCard != null)`
  探活兜底。
- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- search 已启动并监听 SIT 服务器 `http://7.209.189.82:18091`（turn1 需要搜索能力）；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明。
- 无 LLM 密钥客户侧依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 算子：重启 deep-research（消除已知 task-1 冲突 bug） | 算子 | agent 就绪 |
| 2 | 声明 deep-research（remote），默认 `streaming(true)` | `SutStack` | stack 就绪 |
| 3 | Assumptions 探活：`a2a.getAgentCard() != null` | — | 通过；否则跳过 |
| 4 | 生成 `contextId=ctx-da06-longmem-<uuid8>` | — | 每次跑独立 |
| 5 | turn1: 流式 send `"DeepSeek 官方 API 的输入 token 定价目前是多少？请给出官方页面链接。"` | `message/stream` | COMPLETED，artifact 无 bug |
| 6 | turn2: 同 contextId, 流式 send `"我上次问了你什么问题？你上次给我的答案的要点是什么？请直接复述，不要再搜索。"` | `message/stream` | COMPLETED，artifact 无 bug |
| 7 | turn2 artifact 断言：`contains("DeepSeek")` && `containsAny("定价","token","价格")` | — | 均命中 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-06.A — 两轮均 COMPLETED（含 bug 标志缺席）
- **Given**：算子已按 §2 重启 deep-research；agent 探活通过。
- **When**：turn1 / turn2 分别 `awaitTerminalState(300s)`。
- **Then**：两轮终态均 `TASK_STATE_COMPLETED`；artifact 不含 `deep_agent_task_1 already exists`
  / `controller task parameter error`。
- **PASS**：满足。**FAIL**：任一轮未达 COMPLETED / 命中 bug 标志（"是否忘了按 §6 说明手工重启 agent？"）。
- **INCONCLUSIVE**：`getAgentCard()` 返 null 或抛异常 → Assumptions 跳过。

### DA-06.B — turn2 命中专有名 `"DeepSeek"`（**核心档 · 1/2**）
- **Given**：DA-06.A PASS。
- **When**：`turn2.artifactText.contains("DeepSeek")`。
- **Then**：`true`。
- **PASS**：命中——agent 从记忆里复述了 turn1 主题的专有名。
- **FAIL**：未命中——长期记忆丢失 / LLM 未复述主体。

### DA-06.C — turn2 命中至少一个话题词（**核心档 · 2/2**）
- **Given**：DA-06.B PASS。
- **When**：`turn2.artifactText` 命中 `"定价"` / `"token"` / `"价格"` 中的**至少一个**。
- **Then**：`true`。
- **PASS**：命中。**FAIL**：turn2 只念了 "DeepSeek" 却没复述"要点"（回答太空泛，说明记忆信息量不足）。

## 5. 测试数据

- 无外置数据文件；两轮用户输入固定：
  - turn1 = `"DeepSeek 官方 API 的输入 token 定价目前是多少？请给出官方页面链接。"`
  - turn2 = `"我上次问了你什么问题？你上次给我的答案的要点是什么？请直接复述，不要再搜索。"`
- 召回专有名 = `"DeepSeek"`；话题词候选 = `["定价", "token", "价格"]`。
- `contextId` 用 `UUID` 后缀化，避免不同次跑之间在 agent 记忆里相互串扰。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/LongTermMemoryRecallTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/LongTermMemoryRecallTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent") @Tag("manual")` |
| 基类 | `BaseManagedStackTest` |
| streaming | 默认 `streaming(true)`——SSE |
| 超时 | `ROUND_TIMEOUT_MS = 300_000`（含 LLM + 下游搜索，比 DA-02/03 更宽松） |
| 探活兜底 | `Assumptions.assumeTrue(a2a.getAgentCard() != null, ...)`；连接异常 → `Assumptions.abort` |
| 客户端 | `client("deep-research").sendMessage(...)`（两次流式） |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `collectArtifactText` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `contains("DeepSeek")` / 自定义 `anyMatch` 话题词 / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
# 算子先重启 deep-research jar（消除已知 task-1 命名冲突 bug）
# 然后：
./mvnw -Dtest.env=SIT -Dtest=LongTermMemoryRecallTest test
```

> **CI 默认不跑**：`@Tag("manual")` + Assumptions 探活双保险；只在算子协作窗口里跑。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| deep-research 长期记忆跨轮复述（SSE 路径） | DA-06.B / C | ✅ |
| A2A 流式 send 契约 + 已知 bug 回归 | DA-06.A | ✅ |
| Assumptions 探活兜底（remote agent 不可达时不误 FAIL） | Step 3 | ✅ |

## 9. 风险与备注

- **依赖算子重启**：已知 §6 bug 在跑本档前需要算子手工重启 agent；否则 turn1 / turn2 会命中
  bug 标志串。本档 `@Tag("manual")` 让 CI 默认跳过；不要在项目组把这个 bug 修掉前把它塞进 `-P integration`。
- **对下游 search agent 的依赖**：turn1 会真实调 search；search 侧不可达时 turn1 会失败——该情况下
  agent 卡在 WORKING / 超时，DA-06.A FAIL，日志会给出终态信息，与 DA-01.D（search 最小 well-formed）
  互相印证。
- **LLM 依赖**：turn2 的复述质量取决于 LLM；`containsAny` 三个话题词是相对宽松的门槛，避免过度拟合
  具体 LLM 的措辞。
- **对比 DA-05 系列**：DA-05-1 / DA-05-3 验证短期消息历史（"张三" 级别）在 sync / stream 两侧的召回；
  DA-05-2 / DA-05-4 验证跨 JVM 持久化在两侧的召回；DA-06 验证 turn 之间"研究报告主题"级别的长期记忆
  ——记忆颗粒度更粗，考察 summarizer / 检索缓存等中层组件。