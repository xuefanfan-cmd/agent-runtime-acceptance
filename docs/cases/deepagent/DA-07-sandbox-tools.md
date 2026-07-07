---
id: DA-07
title: deep-research 沙箱工具调用（流式）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P1
feature: A2A 流式 + deep-research 沙箱（render_comparison_table 等）
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ 默认 `streaming(true)`
tags: [integration, deepagent, manual]
depends_on:
  - deep-research 已启动并监听 :18090 且带 `SANDBOX_ENABLED=true` + `SANDBOX_URL` 环境变量
  - sandbox 服务已启动并可达（默认 `http://7.209.189.82:8321`）
  - search agent 已启动并监听 :18091（deep-research 内部可能间接触发搜索）
  - 算子先手工重启 deep-research（§7 已知：跑此类沙箱场景前需先重置，与 §6 长期记忆同源 bug）
---

# DA-07 — deep-research 沙箱工具调用（流式）

> **一句话**：走 SSE `SendStreamingMessage` 让 deep-research 用 `render_comparison_table`
> 工具对比 DeepSeek V4 PRO 与 GLM 5.2 的输入定价 / 上下文窗口；期望终态 COMPLETED，
> artifact 同时命中两个专有名（`DeepSeek` + `GLM`）和至少一个话题词（`定价` / `价格` / `上下文` /
> `context_window` / `token`），且不含已知 bug 标志串。

---

## 1. 场景目标

对 deep-research **沙箱工具调用能力**（`render_comparison_table` 走 SSE 路径）做端到端 smoke：

1. 单轮 send，默认 `streaming(true)`——与 [deepagent测试结果.txt §7](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt)
   场景 1 首个 curl 口径一致（简单 prompt，只用 `render_comparison_table`；不用 §7 后半段的 `render_chart`
   / `verify_urls` 复合 prompt——那需要 jq 拼 JSON、更适合单独手工验证）。
2. deep-research 在服务端应：接到用户 prompt → 交给 LLM → LLM 决定调用 `render_comparison_table`
   工具 → 沙箱执行渲染 → agent 把结果写回 artifact。
3. artifact 命中：
   - **两个专有名**：`"DeepSeek"` + `"GLM"`——两个对比对象都在输出里。
   - **至少一个话题词**：`"定价"` / `"价格"` / `"上下文"` / `"context_window"` / `"token"`——
     validates 答案在业务主题上，而不是只念了两个名字。
4. **bug 断言**：artifact 命中 `deep_agent_task_1 already exists` / `controller task parameter error` 即 FAIL——
   这也是 §7 备注里"跑前需算子手工重启"的已知 bug 触发信号。

> **不测**：本档不严格证明"沙箱真的执行了 Python"——LLM 有可能仅凭记忆合成 markdown 表格而不
> 真调工具，从 artifact 文本层面难以区分。要严格证明沙箱执行，应看服务端 sandbox 日志或
> `SANDBOX_URL` 侧访问计数——这属于 SUT 内部可观测性，SIT 端无法直接触达。参见 §9 备注。

## 2. 前置条件

- **算子操作**：本场景当前存在需手工重启的已知 bug（与 §6 同源）——测试前需先重启 deep-research jar。
  为避免 CI nightly 误报，本档 `@Tag("manual")` + `Assumptions.assumeTrue(agentCard != null)` 探活兜底。
- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`，且启动时带以下环境变量：
  - `SANDBOX_ENABLED=true`
  - `SANDBOX_URL=http://7.209.189.82:8321`
  - `OPENJIUWEN_DEMO_DEEP_RESEARCH_SANDBOX_SMOKE_TEST=true`
  - `SANDBOX_TIMEOUT_MS=120000`
  - `DEEP_RESEARCH_MAX_ITERATIONS=10`
- sandbox 服务已就绪并可达（`SANDBOX_URL` 内网通）。
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明。
- 无 LLM 密钥客户侧依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 算子：重启 deep-research（含 sandbox 相关 env 已在启动脚本里） | 算子 | agent 就绪 |
| 2 | 声明 deep-research（remote），默认 `streaming(true)` | `SutStack` | stack 就绪 |
| 3 | Assumptions 探活：`a2a.getAgentCard() != null` | — | 通过；否则跳过 |
| 4 | 生成 `contextId=ctx-da07-sandbox-<uuid8>` | — | 每次跑独立 |
| 5 | 流式 send §7 场景 1 首个 curl 的 prompt：`"对比 DeepSeek V4 PRO 与 GLM 5.2 的输入定价和上下文窗口，用 render_comparison_table 出对比表"` | `message/stream` | COMPLETED，artifact 无 bug |
| 6 | artifact 断言：`contains("DeepSeek")` && `contains("GLM")` && `containsAny(话题词)` | — | 均命中 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-07.A — 单轮到达 COMPLETED（含 bug 标志缺席）
- **Given**：算子已按 §2 重启 deep-research + sandbox；agent 探活通过。
- **When**：`awaitTerminalState(300s)`。
- **Then**：终态 `TASK_STATE_COMPLETED`；合并 artifact 非空；不含 `deep_agent_task_1 already exists`
  / `controller task parameter error`。
- **PASS**：满足。**FAIL**：未达 COMPLETED / artifact 空 / 命中 bug 标志（"是否忘了按 §7 说明手工重启 agent？"）。
- **INCONCLUSIVE**：`getAgentCard()` 返 null 或抛异常 → Assumptions 跳过。

### DA-07.B — artifact 同时命中两个对比对象（**核心档 · 1/2**）
- **Given**：DA-07.A PASS。
- **When**：`artifactText.contains("DeepSeek")` && `artifactText.contains("GLM")`。
- **Then**：两者皆命中。
- **PASS**：满足——LLM/沙箱产出的对比表覆盖了两家。**FAIL**：漏其中之一——deep-research 未能完整
  执行用户 prompt。

### DA-07.C — artifact 命中至少一个话题词（**核心档 · 2/2**）
- **Given**：DA-07.B PASS。
- **When**：`artifactText` 命中 `["定价", "价格", "上下文", "context_window", "token"]` 中的至少一个。
- **Then**：`true`。
- **PASS**：命中。**FAIL**：只念了名字没落到主题——输出偏离用户 prompt 要求。

## 5. 测试数据

- 无外置数据文件；用户输入固定为 §7 场景 1 首个 curl 的 prompt：
  `"对比 DeepSeek V4 PRO 与 GLM 5.2 的输入定价和上下文窗口，用 render_comparison_table 出对比表"`
- 对比对象 = `["DeepSeek", "GLM"]`；话题词候选 = `["定价", "价格", "上下文", "context_window", "token"]`。
- `contextId` 用 `UUID` 后缀化，避免不同次跑之间在 agent 记忆里相互串扰。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/deepagent/SandboxToolsTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/deepagent/SandboxToolsTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent") @Tag("manual")` |
| 基类 | `BaseManagedStackTest` |
| streaming | 默认 `streaming(true)`——SSE |
| 超时 | `SEND_TIMEOUT_MS = 300_000`（沙箱冷启 + LLM 决策 + 工具渲染，比 DA-02/03 更宽松） |
| 探活兜底 | `Assumptions.assumeTrue(a2a.getAgentCard() != null, ...)`；连接异常 → `Assumptions.abort` |
| 客户端 | `client("deep-research").sendMessage(...)`（单次流式） |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `collectArtifactText` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `contains("DeepSeek")` / `contains("GLM")` / 自定义 `anyMatch` 话题词 / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
# 算子先重启 deep-research（含 SANDBOX_ENABLED=true 等 env）+ 确保 sandbox 服务就绪
# 然后：
./mvnw -Dtest.env=SIT -Dtest=SandboxToolsTest test
```

> **CI 默认不跑**：`@Tag("manual")` + Assumptions 探活双保险；只在算子协作窗口里跑。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| deep-research 沙箱工具调用契约（`render_comparison_table` SSE 路径） | DA-07.B / C | ✅ |
| A2A 流式 send 契约 + 已知 bug 回归 | DA-07.A | ✅ |
| Assumptions 探活兜底 | Step 3 | ✅ |

## 9. 风险与备注

- **不严格证明沙箱真被调用**：LLM 有可能凭记忆合成 markdown 表格而不真跑 `render_comparison_table`。
  从 artifact 层面难以区分"真调工具"与"LLM 幻觉表格"。要严格证明，需看服务端 sandbox log 或
  `SANDBOX_URL` 侧访问计数——本档定位为 smoke。可在后续版本引入 SUT 侧观测埋点后再收紧。
- **依赖算子重启**：与 §6 同源 bug——跑本档前需算子手工重启 deep-research，否则会命中 bug 标志串。
  `@Tag("manual")` 让 CI 默认跳过。
- **不覆盖 §7 复合 prompt**：文档 §7 场景 1 后半段用 jq 拼一个含 `render_chart` + `verify_urls` 的长
  prompt——那属于多工具编排，且需要 sandbox 网络访问外网 URL；单独作为 DA-07-2 更清晰，本档不含。
- **不覆盖 §7 场景 1 尾部的召回轮**：§7 尾巴那条 `你上次问了你什么问题？` 属于长期记忆复述，
  已由 [DA-06](DA-06-long-term-memory-recall.md) 覆盖，不重复。
- **对比 DA-06**：DA-06 考察"记忆跨轮复述"、DA-07 考察"沙箱工具调用"——两条正交能力，都因 §7 备注里
  同一个 bug 需要 `@Tag("manual")`。