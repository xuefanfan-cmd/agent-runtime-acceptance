---
id: DA-09
title: parallel-search profile — COMPARISON 一个 turn 批量并行 search 触发验收
module: DA — deep-research 场景（parallel-search 并行变体）
owner: TBD
priority: P1
feature: agent-runtime 同一轮多 remote A2A 工具调用并行分发 · deep-research parallel-search 变体
status: designed
sut: deep-research-agent（deep-research-auto 隔离别名，激活 parallel-search profile）+ search-agent（use-stub=true）+ verify-agent（ReAct 判官）
stack: 单 stack — deep-research-auto(parallel-search, streaming) .downstreams(SEARCH=stub, VERIFY)；extends BaseManagedStackTest
tags: [integration, deepagent, parallel]
depends_on:
  - agent-deep-research jar 已含 application-parallel-search.yml（0.1.0）
  - agent-runtime-java 已支持同一轮多 remote A2A 调用并行分发（RemoteInvocationBatchCoordinator）
  - openjiuwen 运行环境 + LLM_API_KEY（root LLM 决策需真实 LLM）
---

# DA-09 — parallel-search profile COMPARISON 并行触发

> **一句话**：给 deep-research 激活 `parallel-search` profile，发一句 COMPARISON 查询
> （「对比 DeepSeek、通义千问、豆包 三家的 API 输入定价」）；root 应在**同一个 assistant turn**
> 内批量发出多个 per-vendor `search-agent` 调用，由 runtime 经 `parentContextId` **并发分发**。
> 本档黑盒观测 kickoff 流里的 `_remote_invocation.{batchId, toolCallId}`，断言**同一 batch ≥2 个
> 不同 toolCallId**，并验证最终 `COMPLETED` 的多 vendor 对比报告。客户端不发并发续轮
> （search-agent 无状态、自动完成），故不撞 parallel-transfer 的 `@Disabled` 瓶颈。

---

## 1. 场景目标

对 `parallel-search` profile 的并行触发做端到端契约验证：

1. **profile 激活**：`.agent(DEEP_RESEARCH, a -> a.profile("parallel-search"))` → `--spring.profiles.active=parallel-search`。
2. **search 确定性**：`SEARCH_AGENT_USE_STUB=true` → search-agent 走 `fixtures/search-results.json`，不烧 Tavily。
3. **COMPARISON 触发并行**：对比类查询 → root 一个 turn 批量发出多个 per-vendor search 调用。
4. **观测 fan-out**：扫事件 `_remote_invocation.{batchId, toolCallId}`，断言同一 batch ≥2 个不同 toolCallId。
5. **终态报告**：`COMPLETED` + 多 vendor 对比矩阵。

## 2. 前置条件

- openjiuwen 环境 + `LLM_API_KEY`（root + search-agent + verify-agent 均需 LLM 决策）。
- `application-openjiuwen.yml` 已声明 `deep-research-auto`（隔离别名，带 `remote-agents-prefix` + [0]/[1] name）、`search`、`verify`（managed jar）。
- verify-agent 一并拉起：root 真调 verify（ReAct 判官，判定 对比矩阵/引用来源/置信度 三锚点覆盖）；verify 无工具、单轮自动完成，不阻塞 COMPLETED。

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | 单 stack：deep-research-auto(parallel-search, streaming) `.downstreams(SEARCH=use-stub, VERIFY)`（extends `BaseManagedStackTest`；deep-research-auto 已声明 `remote-agents-prefix` + [0]/[1] name） | stack 就绪 |
| 2 | `InteractionFlow.of(client(DEEP_RESEARCH)).protocol(A2A_STREAM).withContextId(ctx-par-sr-<uuid8>).send(COMPARISON_QUERY)`（`@EnumSource` 本期仅 `A2A_STREAM`） | task 出现 |
| 3 | `.awaitState(COMPLETED)`（`withTimeoutMs(300s)`） | `COMPLETED` |
| 4 | `.assertThat(ctx → fromClientEvents(ctx.contextId(), 解包 ctx.events().raw → ClientEvent))`（A2A_STREAM-only，其余协议 no-op） | 同一 batch ≥2 个不同 toolCallId |
| 5 | `.assertAnswer(artifact → vendor + 价格 + bug 守卫)` | 通过 |

## 4. 可观测子断言

### DA-09.A — 终态 `COMPLETED`
- **PASS**：`awaitTerminalState == COMPLETED`。**FAIL**：`FAILED`/`CANCELED`；超时。

### DA-09.B — 并行 fan-out 证据（核心）
- **When**：`RemoteInvocationProbe.fromClientEvents(parentCid, events)`。
- **PASS**：存在 batch ≥2 个不同 toolCallId。**FAIL**：所有 batch 仅 1 成员（仍串行）。
- **INCONCLUSIVE/降级**：若事件流完全扫不到 `_remote_invocation` → 见 spec §8 探活降级，不直接判 FAIL。

### DA-09.C — 多 vendor 对比报告
- **PASS**：artifact 含 ≥2 vendor 名 + ≥1 价格信号词。
- **marker 选取原则**：vendor/价格 marker **只取 stub fixture 结果里出现、而查询串里没有**的串
  （`qwen-max`/`火山方舟`/`$0.27` 等模型名、venue、具体价格），**不**用 `DeepSeek`/`通义`/`豆包`/`定价`
  这类查询子串 —— 否则 agent 只回显查询主题就能假通过。对照 DA-08 用 `DeepSeek-R1`（只存于结果）的同款手法。

### DA-09.D — bug 标志串缺席
- **PASS**：不含 `deep_agent_task_1 already exists` / `controller task parameter error`。

## 5. 测试数据

- 查询：`"对比 DeepSeek、通义千问、豆包 三家的大模型 API 输入定价"`（fixture 覆盖三条 route：deepseek / 通义|qwen|百炼 / 豆包|火山方舟）。
- `contextId` UUID 后缀化。
- marker（仅取 fixture 结果串，非查询子串）：vendor `[qwen-max, 火山方舟, $0.27]`（一家一个，≥2 即 ≥2 家真实结果入库）；价格 `[元/千 tokens, million tokens, $0.27]`。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `ParallelSearchComparisonTest`（extends `BaseManagedStackTest`，单 stack + `.downstreams(SEARCH, VERIFY)`） |
| 标签 | `@Tag("integration") @Tag("deepagent") @Tag("feat-parallel-search")` |
| 协议 | `InteractionFlow` 参数化（`@ParameterizedTest` + `@EnumSource(INCLUDE, {"A2A_STREAM"})`，参照 `StreamingTravelPlanningTest`）；本期仅 `A2A_STREAM`，实测后逐步放开 A2A_SYNC/REST。终态/报告断言协议中立；DA-09.B fan-out 仅 `A2A_STREAM` 生效（其余 no-op） |
| profile | deep-research-auto `parallel-search`；search `SEARCH_AGENT_USE_STUB=true`；verify 默认（ReAct 判官） |
| 观测 | `InteractionFlow` `RoundContext.events()`（`List<InboundEvent>`）→ 解包 `InboundEvent.raw()` 携带的 A2A `ClientEvent` → `RemoteInvocationProbe.fromClientEvents` |
| 断言 | AssertJ：`COMPLETED` / fan-out `≥2` / vendor+价格词 / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelSearchComparisonTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| parallel-search profile 激活 | 栈构建 | ✅ |
| COMPARISON 一个 turn 批量多 search 调用 | DA-09.B | ✅ |
| agent-runtime 同轮多 remote A2A 并行分发 | DA-09.B | ✅ |
| 多 vendor 对比报告语义 | DA-09.C | ✅ |
| deep-research bug 回归看门狗 | DA-09.D | ✅ |

## 9. 风险与备注

- **无并行实测线帧**：`_remote_invocation` 在 deep-research 流里的真实形状未实测。首跑按 spec §8 探活：先看事件/wire，再固化或降级 DA-09.B。
- **DA-09.B 协议门控**：`_remote_invocation` fan-out 是 A2A 远端工具分发独有产物（REST 无对应），故 fan-out 断言仅在 `A2A_STREAM` 生效；参照 `StreamingTravelPlanningTest#assertStreamTrajectory`，其余协议为 no-op（终态+报告已覆盖）。现仅参数化 `A2A_STREAM`，该门控分支是「逐步放开」时的预留。
- **报告文本落点（探活关注）**：C/D 走 `.assertAnswer(artifact)` —— 读**离散 ANSWER**（= 最终对比报告，与老的 `collectArtifactText()` 语义一致）。若首跑发现报告不在 `answerText`（被拆成 `LLM_OUTPUT` 流式块、未聚合为 ANSWER），把 C/D 改挂 `.assertGenerated`（answer+llm_output 超集）即可，一行改动；不影响 fan-out（B 读 `events()`）。
- **LLM 不确定性**：root 是否真在一个 turn 批量发出多个调用，取决于 DeepSeek 对 prompt 的遵循；偶发退化为串行会让 DA-09.B 失败（真实失效信号）。
- **stub 仍需 LLM**：use-stub 只替换 web_search 数据源，search-agent 的 ReAct 决策仍需 LLM。
- **verify 真调**：root 调 verify 判定三锚点覆盖；若判 FAIL，deep-research 按 `max-replan=1` 触发一次 steering 重叠（可能拉长流），criteria 为 `对比矩阵/引用来源/置信度 已覆盖`，合格报告应 PASS。首跑确认 verify 不阻塞 COMPLETED。
- **`REMOTE_BATCH_ALREADY_ACTIVE`**：全仓库零引用；若并行分发触发此 runtime 错误，DA-09.A 会以 FAILED 暴露，届时作新缺陷记录。
- **marker 假通过风险（已规避）**：DA-09.C 的 vendor/价格 marker 刻意只取 fixture 结果串、避开查询子串，防止「查询回显」造成假通过（见 §4.C 原则）。
