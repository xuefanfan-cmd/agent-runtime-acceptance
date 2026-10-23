---
feature_id: FEAT-XXX
test_type: integration
scope: agent-memory-2.0-acceptance
deployable_units: [agent-memory (Python 2.0), agent-solution personal-assistant-reactagent-demo]
sut: agent-memory 2.0 HTTP 服务（8137，offline profile，dev auth）+ personal-assistant-reactagent-demo（ReActAgent A2A，18138）
features: [FEAT-XXX]
updated: 2026-10-23
owner: TBD
priority: P0
status: reactagent 批次已实现
tags: [integration, memory, feat-xxx]
depends_on:
  - agent-memory 2.0 本地服务（python -m jiuwen_memory_entry.http_server --auth-mode dev，8137）
  - agent-solution MR !849 personal-assistant-reactagent-demo（18138）
  - 测试 profile：application-openjiuwen.yml（-Dtest.env=openjiuwen）
  - LLM_* 环境变量（agent 对话需要真实 LLM；记忆服务本身不需要 LLM 配置）
---

# FEAT-XXX — agent-memory 2.0 接入验收方案

> **定位**：验证 agent（本批次为 ReActAgent）通过 agent-memory 2.0 完成记忆写入与召回的外部可观测行为。
> 依据《记忆服务场景验收用例_agent-memory2.0_20260917-基于姚成老师提供修正.xlsx》15 条用例（6 个 sheet）。
>
> **批次划分**：ReActAgent 批次（本方案，已实现）→ DeepAgent 批次（后续）→ WorkflowAgent 批次（后续，接入成本最高）。
>
> **已知问题**（详见 §9）：按日期召回失效 / 矛盾记忆无时新性信号 / 检索无相关性下限注入——均为 agent-memory 2.0 服务端问题，已按 issue 文档单独跟踪。

---

## 1. 测试目标

以 agent-memory 2.0 HTTP 服务（8137）和 personal-assistant-reactagent-demo（ReActAgent A2A，18138）为被测对象，验证 15 条 Excel 场景的全部外部可观测行为：

1. **信息提取**（acc11~acc15）：无关 query 不召回、精准命中最初条、用户隔离、删除后不召回、按日期召回。
2. **多会话推理**（acc21~acc24）：矛盾最新优先、单主题不混入、复合信息整条召回、补充信息优先。
3. **时间推理**（acc31~acc32）：时间记忆随自然变化召回准确、指定日期召回。
4. **知识更新**（acc41）：变更后召回最新事实。
5. **拒绝回答**（acc51~acc53）：合规拦截、越权 403、冲突保护（后两者 Excel 标注由上层智能体实现，映射为 demo 桥拒绝门验证）。

**Excel 用例的真实形态**：15 条用例原本全部是直接 curl agent-memory 2.0 HTTP API（add/search/get/delete/list），不涉及 agent。因此验收分两层落地同一批场景：

- **第一层（记忆服务底座验收）**：测试侧直调 8137 HTTP API 做确定性断言；
- **第二层（agent 接入记忆验收）**：agent 通过真实 A2A 对话完成记忆写入与召回，黑盒验证记忆行为。

本方案两层合并实现：写入侧经 ReActAgent 真实链路（第二层），读取侧双通道断言（第一层直查 + 第二层应答）。

## 2. 范围与非范围

### 范围

- **写入链路**：A2A 流式对话 → ReActAgent → demo 桥 `MemoryLifecycleAgentHandler` → syncTurn → `POST /v1/add` 落库。
- **召回链路**：demo 桥 prefetch → `POST /v1/search` → `<memory-context>` 注入最后一条 user 消息。
- **隔离与权限**：session 维同构隔离（dev auth 降级口径）、跨 org/user 403、软删 forget 立即退出检索。
- **上层智能体职责**：合规拦截、冲突保护拒绝门（demo 桥行为验证 + 不落库直查）。
- **已知现状暴露**：3 条 Excel 实测"否"用例（acc15/acc21/acc32 关联）按期望正确行为设定断言，红 = 暴露服务端问题（见 §9）。

### 非范围

- Java 侧进程内 `LongTermMemory` 引擎（InMemory/SQLite/Chroma 那套）——与 agent-memory 2.0 是不同系统，不在本验收范围。
- DeepAgent / WorkflowAgent 接入批次（后续单独方案）。
- 记忆服务性能、容量、并发、迁移、Graph、Provider 存储后端矩阵。
- LLM 自然语言输出的精确文案断言（只做关键词命中）。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| 《记忆服务场景验收用例_agent-memory2.0_20260917-基于姚成老师提供修正.xlsx》（15 条用例，6 个 sheet） | 验收场景与期望口径的唯一基线。 |
| agent-solution MR !849 `personal-assistant-reactagent-demo`（1:1 镜像 PR 843 deepagent demo，仅换 agent 核为 ReActAgent） | SUT 交付物与桥契约（`<memory-context>` 注入、syncTurn 写回、合规拒绝门）。 |
| `issue-agent-memory-01/02/03-*.md`（本仓根目录） | 3 个服务端已知问题的最小复现、根因与代码定位。 |

## 4. 部署拓扑

```text
JUnit 黑盒驱动（MemoryReactAgentAcceptanceTest）
  +-> agent-memory 2.0（Python，http://127.0.0.1:8137，offline profile，dev auth=local/developer）
  `-> personal-assistant-reactagent-demo（18138，agent-solution MR !849）
        +-> ReActAgent（agent-core-java）+ LLM（LLM_* 环境变量）
        `-> MemoryLifecycleAgentHandler（demo 桥，唯一记忆注入点）
              +-> syncTurn  -> POST /v1/add   （每轮对话写回）
              +-> prefetch   -> POST /v1/search（<memory-context> 拼入最后一条 user 消息）
              +-> 拒绝门     （合规拦截 / 冲突保护，不落库）
```

边界要求：

- **防测跑偏约束（本方案核心）**：Java agent"接入记忆"的框架默认路径走进程内 `LongTermMemory`（InMemory/SQLite/Chroma），不经过 8137 服务。若不强制走 HTTP，测试会跑偏成"agent 自己 JVM 内存里有数据、agent-memory 2.0 里一条都没有"的假绿。因此 SUT 必须是 demo 桥经 HTTP 打到 8137 的实现，且测试侧用 `/v1/search` 直查验真——**在 2.0 服务里查得到该条记忆，才算真的接进去了**。
- ReActAgent core 不调用 prefetch，`<memory-context>` 的唯一注入点是 demo 桥 `MemoryLifecycleAgentHandler.withMemory`。
- 记忆服务进程生命周期不受 SutStack 管理：测试前置 `Assumptions.assumeTrue` 探活门（框架既有模式）。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| acc11 | 无关 query 不召回无关信息 | 独立 contextId，写入若干条事实记忆 | 以完全无关的主题提问 | 直查 top 结果不含已写入记忆条目；应答不含已写入记忆的内容 | A2A + 直查 |
| acc12 | 相似多条中精准命中最初条 | 写入多条语义相近记忆 | 以共同关键词查询 | 召回分数最高的是最初提及的那条 | A2A + 直查 |
| acc13 | 用户隔离（session 维同构） | 两个独立 contextId（A/B） | A 写入、B 查询 | B 召回不到 A 的记忆（dev auth 固定身份，user 维降级为 session 维，Excel 已认可此降级口径） | A2A 双会话互查 |
| acc14 | 删除后无法召回 | 写入记忆并确认可召回 | 直调 `/v1/delete`（FORGET）后再查 | FORGET 软删立即退出检索，任何查询不再返回该条 | A2A + FORGET 直调硬断言 |
| acc15 | 长期记忆按日期召回 | 写入带日期的记忆 | 以日期范围查询 | 仅召回日期范围内的记忆（**已知红：服务端查询不解析绝对日期，见 §9 issue-01**） | A2A + 直查 |
| acc21 | 矛盾记忆最新优先 | 先后写入两条相互矛盾的事实 | 查询该事实 | 召回最新一条（**已知红：检索管线无时新性信号，旧事实压过新事实，见 §9 issue-02**） | A2A + 直查 |
| acc22 | 单主题不混入其他主题 | 写入行程类记忆后切换话题问天气 | 只问天气单主题 | 应答只含天气，不引入行程/出差/景点内容（**已知不稳定：低分行程记忆被 prefetch 注入，见 §9 issue-03**） | A2A |
| acc23 | 复合信息单维度查询整条召回 | 写入含多维度复合信息的记忆 | 只按单一维度查询 | 整条复合记忆被完整召回 | A2A + 直查 |
| acc24 | 优先召回补充的明确信息 | 写入模糊信息后补充明确信息 | 查询该信息 | 优先召回补充后的明确版本 | A2A + 直查 |
| acc31 | 时间记忆随自然变化召回准确 | 写入随时间会失效的记忆 | 查询当前状态 | 按当前时间正确回答（不吃已过期信息） | A2A + 直查 |
| acc32 | 指定日期召回 | 写入多天记忆 | 指定某一天查询 | 只召回该日期的记忆（**已知不稳定：排序与日期无关，见 §9 issue-01**） | A2A + 直查 |
| acc41 | 变更后召回最新事实 | 写入旧事实后变更新事实 | 查询该事实 | 召回变更后的最新事实（**已知红：同 acc21 根因，见 §9 issue-02**） | A2A + 直查 |
| acc51 | 合规性内容拦截（上层职责） | Excel 判定"不适用" | 尝试写入合规敏感内容 | demo 桥拒绝门拦截：拒绝应答 + 不落库（直查验证无该条） | A2A + 直查 |
| acc52 | 权限不足越权拒绝 | 写入记忆到本 scope | 跨 org/user 维度直调查询 | 服务端一律 403（dev auth 固定 `local/developer` 身份） | 直调 8137 |
| acc53 | 记忆冲突未解决（上层职责） | Excel 判定"不适用" | 构造未解决冲突场景 | demo 桥对话保护拒绝门：拒绝回答而非给出单一事实 | A2A |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| personal-assistant-reactagent-demo | 真实 SUT | agent-solution MR !849；ReActAgent 核 + `MemoryLifecycleAgentHandler` 桥；1:1 镜像 PR 843 deepagent demo，仅换 agent 核。写入侧全部经它走真实链路。 |
| agent-memory 2.0（8137） | 真实 SUT | Python HTTP 服务，offline profile（进程内存），dev auth。读取侧直查验真对象。 |
| `MemoryReactAgentAcceptanceTest` | 测试类 | 15 条用例逐条对齐 Excel；双通道断言；Allure 附件记录每轮对话与直查结果，红条断言文案自带失败证据（top1 的 unit/score/content）。 |
| SutStack | 框架 | 经 `application-openjiuwen.yml` 拉起 demo jar（`memory-react-agent`）；`agent-memory-2` 为 url-only 声明（8137 探活与直查）；LLM 凭据经 Spring 命令行参数（`.property()`）透传。 |

## 7. 关键链路断言

- **双通道**：A2A 对话触发 agent 的记忆写入/召回（行为通道）+ 测试侧直调 `/v1/search` 验证记忆已落库、可召回（数据通道，确定性断言）。行为断言只做关键词命中，不做精确文案断言。
- **防测跑偏**：每条用例的记忆验真必须以"8137 服务里查得到"为准，agent 进程内状态不作数。
- **写入路径唯一性**：写入必须经 ReActAgent 真实对话链路（syncTurn），不允许测试侧直调 `/v1/add` 伪造写入侧绿条（acc14 的 FORGET 直调除外，删除操作本就是 Excel 用例步骤）。
- **注入点唯一性**：`<memory-context>` 仅由 demo 桥拼入最后一条 user 消息，ReActAgent core 不二次注入——归因实验（§9 issue-03 四组对照）依赖此事实。
- **已知现状暴露口径**：acc15/acc21/acc32/acc41 的断言按期望正确行为设定，红 = 暴露服务端真问题，不算测试失败跑偏。

## 8. 执行策略

- Smoke：acc11、acc12、acc14、acc52（不依赖 LLM 输出质量，确定性最高）。
- Full suite：acc11~acc53 全部 15 条。
- 依赖门禁：8137 探活失败 → 用例跳过（assumeTrue）；demo jar 缺失 → SutStack ERROR；LLM 不可达 → 对话轮失败不静默跳过。
- 标识隔离：每条用例独立 contextId（`newContextId("accNN")`），offline profile 进程内存数据靠 session 维隔离，无需清理。
- 实现映射：`src/test/java/com/huawei/ascend/sit/cases/integration/agent_memory/MemoryReactAgentAcceptanceTest.java`，类级 `@Tag("integration")`，Allure Feature 为 `agent-memory 2.0 acceptance`，Story 使用 accNN 场景 ID。

```bash
# 前置：8137 记忆服务 + 18138 demo 已就位
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=MemoryReactAgentAcceptanceTest
```

## 9. 已知问题（验收发现的服务端真 Bug）

| # | 问题 | 关联用例 | issue 文档 |
|---|---|---|---|
| 1 | 按日期召回失效：查询侧不解析绝对日期 + 写入侧 `t_event=None` + 日期谓词哨兵空转 | acc15、acc32 | `issue-agent-memory-01-date-scoped-recall-not-effective.md` |
| 2 | 无时新性信号：RRF 全管线无时间信号 + 直存无矛盾抑制，旧事实压过新事实 | acc21、acc41 | `issue-agent-memory-02-no-recency-signal-old-fact-wins.md` |
| 3 | 无相关性下限注入：阈值全 0 + prefetch 无分数过滤，低分记忆注入并经写回放大（四组对照归因实验证实：注入是更强触发源，LLM 无责任） | acc22 | `issue-agent-memory-03-no-relevance-cutoff-irrelevant-injection.md` |

## 10. 后续批次（非本方案范围）

| 批次 | 接入路线 | 成本评估 |
|---|---|---|
| DeepAgent | harness `ExternalMemoryRailProvider` 配置化挂同一 HTTP 桥 | 1.2x（复用桥，换挂载方式） |
| WorkflowAgent | 官方 `MemoryWrite/RetrievalComponent` 绑死本地 `LongTermMemory` 具体类，无 Provider 级插槽；需自定义 workflow 组件直调 HTTP + 手工构造带记忆节点的 DSL | 3x（替换具体类 + 自建组件 + 造 DSL） |
