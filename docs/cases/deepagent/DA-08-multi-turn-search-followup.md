---
id: DA-08
title: deep-research 通过 search-agent 多轮追问补全查询意图（同 contextId 循环回答）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-2 同步消息（`method: SendMessage`）+ INPUT_REQUIRED 多轮延续 + 下游 agent 追问链路
status: designed
sut: deep-research-agent（+ 下游 search-agent，由 SUT 端 `SEARCH_AGENT_URL` 自持）
stack: 单 agent（remote，url-only）+ `streaming(false)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090
  - search-agent 已启动并监听 :18091；`SEARCH_AGENT_URL` 已在 deep-research 侧配置
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现（否则 FAIL——沿用 DA-02/03/06 守卫）
---

# DA-08 — deep-research 通过 search-agent 多轮追问补全查询意图

> **一句话**：向 deep-research 发起一个**信息缺项**的查询（"DeepSeek 官方定价"），
> deep-research 把请求转给下游 search-agent 后，search-agent 判定意图不完整并发起追问
> （问用户具体是哪一款 DeepSeek 模型）；客户端用**同一 `contextId`** 回答 `"DeepSeek-V3"`，
> 若还被追问则**继续同 contextId 循环回答**，直到 agent 输出模型定价（终态 `COMPLETED`）。
> 本档验证**端到端多轮追问链路**：客户端 ↔ deep-research ↔ search-agent 之间的 `INPUT_REQUIRED`
> 传递与 `contextId + taskId` 延续语义。

---

## 1. 场景目标

对 A2A **同步消息 + INPUT_REQUIRED 多轮**路径做端到端契约验证：

1. **入口**：`SutStack.Builder.streaming(false)` 让 SDK 走 `message/send`（阻塞式一发一收）。
2. **首轮触发下游追问**：deep-research 收到「查 DeepSeek 定价」的模糊查询后，通过 search-agent
   得出"缺型号"的判断，把 A2A task 停在 `TASK_STATE_INPUT_REQUIRED`，通过 `status.message` 或
   artifact 呈现追问文本给客户端。
3. **同 contextId 续答**：客户端用 turn1 返回的 `contextId + taskId` 组装 turn2 消息
   （text = `"DeepSeek-V3"`），继续走 `message/send`。
4. **允许链式追问**：若 turn2 仍是 `INPUT_REQUIRED`（例如 search-agent 再问要输入 token 还是
   输出 token 定价），客户端继续同 `contextId + 最新 taskId` 回答；本档最多允许 **5 轮**，
   超上限判 FAIL（防止无限循环 / agent 死板）。
5. **终态与结果断言**：最终终态 `TASK_STATE_COMPLETED`；最终 artifact 中同时命中
   `"DeepSeek-V3"` 与至少一个**价格信号词**（`价格 / 定价 / token / 元 / USD / $`），
   证明 agent 实际给出了模型报价而不是套话。
6. **bug 守卫**：沿用 DA-02/03/06 —— artifact 不得出现 `deep_agent_task_1 already exists` /
   `controller task parameter error`。

## 2. 前置条件

- deep-research 已启动并监听 `http://7.209.189.82:18090`；
- **search-agent 已启动并监听 `http://7.209.189.82:18091`**，且 deep-research 侧的
  `SEARCH_AGENT_URL` 环境变量已指向此地址（本档不直接对 search 发 A2A 请求，只探活由
  DA-01 保障）；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中
  `sut.agents.deep-research.url` 已声明；
- 无 LLM 密钥客户侧依赖——deep-research / search-agent 服务端自持 `LLM_*` 环境变量。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），`streaming(false)` | `SutStack` | stack 就绪 |
| 2 | 生成本次 `contextId=ctx-da08-followup-<uuid8>` | — | 每次跑独立 |
| 3 | **Turn1**：`Message(role=USER, contextId=<...>, parts=[TextPart("你好,帮我查一下DeepSeek官方定价，请给出官网链接")])` → `sendMessage` | A2A SDK `message/send` | task 出现 |
| 4 | `awaitAllowedOutcome([INPUT_REQUIRED, COMPLETED], 240s)` | — | 一般应为 `INPUT_REQUIRED`（agent 需要追问型号） |
| 5 | 从 turn1 事件抽出 `taskId1 / contextId1`；断言 `contextId1 == <§2 值>` | — | 延续锚已就绪 |
| 6 | **Turn2**：`Message(contextId=contextId1, taskId=taskId1, parts=[TextPart("DeepSeek-V3")])` → `sendMessage` | 同 API | task 延续 |
| 7 | `awaitAllowedOutcome([INPUT_REQUIRED, COMPLETED], 240s)` | — | `COMPLETED` 或再次 `INPUT_REQUIRED` |
| 8 | 若仍 `INPUT_REQUIRED`：抽出**新** `taskId_n` 作为下一轮延续锚，客户端**给出下一轮回答**（复用同 `contextId` + `taskId_n`），返回 §7 | — | 最多 5 轮兜底 |
| 9 | 循环退出条件：终态 `COMPLETED`（PASS 路径）或超上限 5 轮（FAIL） | — | — |
| 10 | 从最后一轮 `Task` 快照抽 artifact 文本 → 断言 §4 | — | — |

**Turn2+ 追问回答策略**（在 turn2 之后的兜底追问里）：
- 观察最新一轮 artifact / `status.message` 的追问语义（本档只做**同 contextId + 简短标准回答**，
  不做 LLM-level 理解）。
- 兜底策略：回答内容按 **"最信息完整 + 与查询强相关"** 原则组装。首推 `"输入 token 定价"`；
  若追问包含 `"输出"`、`"context"`、`"上下文"`、`"tokens"` 等词，回答对应词
  （见 §5 追问 → 回答映射表）。
- 若映射表未命中，回答默认句 `"请直接给我 DeepSeek-V3 的官方定价链接"`——把"给答案"的责任
  推回 agent，避免死锁。

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-08.A — 多轮循环内终态达成 `COMPLETED`
- **Given**：deep-research 就绪；search-agent 就绪；send 无异常。
- **When**：`while (state == INPUT_REQUIRED && rounds < 5)` 循环发送。
- **Then**：某轮终态为 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：任一轮 `FAILED` / `CANCELED`；超上限 5 轮仍 `INPUT_REQUIRED`；单轮超时
  240s 未产出任何终态 / `INPUT_REQUIRED`。

### DA-08.B — 首轮应触发 `INPUT_REQUIRED`（追问链路存在的存在性证据）
- **Given**：Turn1 send 无异常。
- **When**：观察 turn1 终态。
- **Then**：轨迹里应至少出现过 `TASK_STATE_INPUT_REQUIRED`（首轮或后续轮）。
- **PASS**：至少一轮观察到 `INPUT_REQUIRED`。**FAIL**：整个对话 turn1 一次就 `COMPLETED`——
  意味着 agent 没有走「转发到 search-agent → search-agent 发起追问」链路，即使 DA-08.A 也 PASS，
  本档目标（**验证追问链路本身**）未达成，判 FAIL 并给出诊断。

### DA-08.C — 每轮 `contextId` 保持一致
- **Given**：DA-08.A 循环期间收集每轮 `contextId`。
- **When**：跨轮比较。
- **Then**：所有轮的 `contextId` 均等于 turn1 送出的值（客户端锚）。
- **PASS**：全部一致。**FAIL**：任一轮 `contextId` 变化——违反 A2A 会话锚定契约。

### DA-08.D — 最终 artifact 命中"模型 + 定价语义"
- **Given**：DA-08.A PASS 后，取最后一轮 `Task` 快照的 artifact 文本
  （`TaskTextExtractor.textOf(task)`）。
- **When**：字串匹配。
- **Then**：
  - **必含**：`"DeepSeek-V3"`（专有名，证明 agent 定位到用户指定的型号）；
  - **至少含其一**（价格信号词）：`价格 / 定价 / token / 元 / USD / $`。
- **PASS**：均满足。**FAIL**：缺 `"DeepSeek-V3"`（agent 忘了 turn2 上下文）
  或价格信号词全无（agent 给了非价格性回答，如"请访问官网"套话）。

### DA-08.E — bug 标志串缺席（沿用 DA-02/03/06 守卫）
- **Given**：所有轮次的 artifact 文本合并。
- **When**：检查是否含 `deep_agent_task_1 already exists` / `controller task parameter error`。
- **Then**：均不含。
- **PASS**：均不含。**FAIL**：任一命中——SUT 复现已知 bug，用例作为看门狗触发。

## 5. 测试数据

- 无外置数据文件；用户输入固定：
  - **Turn1**：`"你好,帮我查一下DeepSeek官方定价，请给出官网链接"`（与用户明示原文一致）；
  - **Turn2**：`"DeepSeek-V3"`（与用户明示原文一致）。
- **Turn3+**（若发生）追问 → 回答映射（简单启发式，避免 LLM-level 理解）：

  | 追问 artifact 出现关键词 | 客户端回答 |
  |---|---|
  | `输入` + `token` | `输入 token 定价` |
  | `输出` + `token` | `输出 token 定价` |
  | `context` / `上下文` | `标准上下文` |
  | `缓存` / `cache` | `不使用缓存` |
  | 未命中 | `请直接给我 DeepSeek-V3 的官方定价链接` |

- `contextId` 用 UUID 后缀化：`ctx-da08-followup-<uuid8>`，避免不同次跑之间在 SUT 侧
  checkpointer 里相互串扰。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest`（per-class 栈；deep-research remote） |
| streaming | `streaming(false)` —— 同步 `message/send`（每轮阻塞返回） |
| 单轮超时 | `ROUND_TIMEOUT_MS = 240_000`（deep-research 内部含 LLM 调用，需宽松窗口） |
| 循环上限 | `MAX_ROUNDS = 5`（防止 agent 无限追问） |
| 客户端 | `client("deep-research").sendMessage(msg, consumers, errorHandler)` |
| 事件收集 | 每轮独立 `A2aEventCollector`；`awaitAllowedOutcome` 使用 `[INPUT_REQUIRED, COMPLETED]` 白名单 |
| 延续消息 | `Message.builder().contextId(...).taskId(priorTaskId).parts(...).build()`（`taskId` **必须**在 `INPUT_REQUIRED` 延续时携带） |
| 文本抽取 | `TaskTextExtractor.textOf(task)`（artifacts → status message → last history 三级降级） |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `contains("DeepSeek-V3")` / 价格词 `anyMatch` / `doesNotContain(BUG_MARKER)` |

**为什么不复用 `OpenjiuwenSyncTwoTurnRunner`**：
- 那个 runner 是 **2 轮定长**（turn1 → turn2 结束）；本档需要 `n` 轮循环
  （直到 `COMPLETED` 或超上限）；
- 那个 runner 里 turn2 的期望答案由外部 scenario 数据驱动，本档是**追问驱动的动态回答**；
- 强行复用会把 runner 语义污染，故本档在测试类内内联多轮循环控制流（约 40 行）。

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=MultiTurnSearchFollowupTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 特性 4-2 同步 `message/send` 路径 | DA-08.A | ✅ |
| A2A `INPUT_REQUIRED` 状态承载与多轮延续（`contextId + taskId`） | DA-08.A / B / C | ✅ |
| deep-research → search-agent 下游转发链路 + 追问回传 | DA-08.B | ✅ |
| 语义任务结果验证（模型 + 价格） | DA-08.D | ✅ |
| deep-research 已知 bug 回归看门狗 | DA-08.E | ✅ |

## 9. 风险与备注

- **循环上限 5 轮的选择**：DA-08 追问链条现实中通常 1~2 轮就应结束；给 5 轮是**兜底放宽**，
  真跑到 5 轮意味着 agent 追问策略有问题，判 FAIL 即等于抓到了这种失效模式。
- **追问 → 回答映射的启发式**（§5）**故意保持简单**：不引入客户端侧 LLM 帮客户端理解 agent 追问
  语义。这样：
  - 用例可复现（同一 agent 输出对应同一客户端回答）；
  - 失败原因清晰（agent 追问超出简单映射能力 = 追问策略过激进）。
- **`taskId` 必须在延续时携带**：`INPUT_REQUIRED` 是非终态，A2A 规定同 task 内继续对话必须带
  `taskId`；只带 `contextId` 会在 SUT 侧开新 task，破坏对话延续（体现为 turn2 又是全新首轮），
  实现里靠 `Message.builder().taskId(...)` 保障。
- **首轮就 `COMPLETED` 的情况**（DA-08.B FAIL）：可能 agent 装了"回答任何 DeepSeek 问题"的
  兜底逻辑；此时本档目标（**验证追问链路**）实际未达成，DA-08.B 判 FAIL 是"用例质量断言"
  而非"agent 功能断言"。
- **不测流式**：本档只测 sync；流式多轮由 DA-05-3 / DA-05-4 覆盖（那些是**记忆**回归，
  不是**追问**）。追问链的流式变体如需要，后续可开 DA-08-2。
- **不测服务端签发 contextId**：本档由客户端签发 `contextId`；服务端签发场景由
  [A-11-2](../A-11-2-server-assigned-context-id.md) 覆盖。