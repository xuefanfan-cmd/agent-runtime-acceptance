---
id: BUG-008
title: AskUserRail.resolveInterrupt 在 userInput != null 分支错返 approve()，userInput 被丢弃、工具体以原参数 re-run，触发 ask_user 死循环
module: agent-core-java · com.openjiuwen.harness.rails.interrupt.AskUserRail（上游 openjiuwen SUT 侧 Java Rail 层）
sut: deep-research（http://7.209.189.82:18090）+ search-agent（http://7.209.189.82:18091）· agent-core-java 部署版本待与 82 服务器对齐（本地 ~/.m2 落 0.1.13）
upstream: https://gitcode.com/openJiuwen/agent-core-java/issues/48
observed_on: 2026-07-27（DA-08 MultiTurnSearchFollowupTest FAIL 稳定复现）
fixed_verified_on: 2026-07-27（本地替换 openjiuwen 新版 jar 后 DA-08 PASS · search-agent stdout 里三处 tool_result 严格匹配 ask_user tool_call_id，userInput 不再被丢）
severity: P1（HITL 中断-续接链路 Turn 2 完全失效；与 [BUG-002](BUG-002-input-required-prompt-lost-as-ask-user.md) 叠加使 DA-08 从"首观时侥幸绿"退化到"稳定红"）
tests:
  - agent-runtime-acceptance/src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java
spec_refs:
  - FEAT-004 §5.5 · §9.4（[FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md](../cases/FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md)）
  - agent-core-java `BaseInterruptRail.applyDecision` 语义合约（`ApproveResult(null)` = no-op，工具体以原参数执行）
  - agent-core-java `AskUserRail.resolveInterrupt(userInput)` 正确决策：`reject(userInput)` 短路工具体并注入 userInput 为 tool-result
related:
  - BUG-002（追问下发文本丢失 · 同 AskUserRail 桥接层双段互补 bug · [BUG-002](BUG-002-input-required-prompt-lost-as-ask-user.md)）
---

# BUG-008 — AskUserRail resume 分支返回 approve() 致 userInput 丢弃 · ask_user 死循环

## 1. 摘要（TL;DR）

**上游报告**：[openJiuwen/agent-core-java issue-48](https://gitcode.com/openJiuwen/agent-core-java/issues/48)。

**代码位置**：`com.openjiuwen.harness.rails.interrupt.AskUserRail`（`agent-core-java` 0.1.7+，本地 `~/.m2` 落 0.1.13）。

**bug**：`AskUserRail.resolveInterrupt(userInput)` 方法在 `userInput != null` 分支返回 `approve()`（即 `ApproveResult(null)`）。交由 `BaseInterruptRail.applyDecision` 处理时被识别为 no-op —— 工具体以**原始参数**执行，`userInput` 被丢弃，agent 拿不到用户答复。

**正确决策**：`reject(userInput)` —— 短路工具体，把 `userInput` 直接注入 tool-result 供 LLM 消费。

**当前部署实测**：LLM 在没拿到 userInput 的情况下再次决策，产生新的 `ask_user` 请求 → 父 Task 再挂 INPUT_REQUIRED → 用户再答 → 依然被丢 → **死循环**。DA-08（MultiTurnSearchFollowupTest）2026-07-27 复观：连续 5 轮 INPUT_REQUIRED，从未收敛，触发 DA-08.A 5 轮上限保护 FAIL。

---

## 2. 观测数据

### 2.1 SIT 用例 FAIL 记录

**运行命令**：`TEST_ENV=SIT ./mvnw -Dtest=MultiTurnSearchFollowupTest test`（`agent-runtime-acceptance` 分支 `main` @ `5affbb1`，2026-07-27 11:52 CST）

**FAIL 断言**（`target/surefire-reports/TEST-...MultiTurnSearchFollowupTest.xml`）：
```
[DA-08.A: 循环退出时终态应为 COMPLETED（超上限 5 轮或非法终态判 FAIL）
trajectory=[TASK_STATE_INPUT_REQUIRED, TASK_STATE_INPUT_REQUIRED,
            TASK_STATE_INPUT_REQUIRED, TASK_STATE_INPUT_REQUIRED,
            TASK_STATE_INPUT_REQUIRED]]
expected: TASK_STATE_COMPLETED
 but was: TASK_STATE_INPUT_REQUIRED
    at MultiTurnSearchFollowupTest.multiTurnSearchFollowupReachesCompletedWithPricing(:165)
```

### 2.2 每轮 wire 观测

| 轮 | User 输入 | Task 状态 | fullSnapshot（history + artifact + status.message）|
|---|---|---|---|
| 1 | 首查 "帮我查一下 DeepSeek 官方定价，请给出官网链接" | INPUT_REQUIRED | `RUNNINGINPUT_REQUIREDask_user` |
| 2 | `帮我查DeepSeek-R1的官方定价`（**明确型号**） | INPUT_REQUIRED | `ask_user 帮我查DeepSeek-R1的官方定价 RUNNINGINPUT_REQUIRED RUNNINGCOMPLETED RUNNINGINPUT_REQUIRED ask_user` |
| 3 | `请直接给我 DeepSeek-V3 的官方定价链接`（**明确型号 + 意图**） | INPUT_REQUIRED | 前两轮 userInput 叠加 + 新 `ask_user` |
| 4 | 同 Round 3 | INPUT_REQUIRED | 继续叠加 |
| 5 | 同 Round 3 | INPUT_REQUIRED | 触发 DA-08.A 5 轮上限 → FAIL |

### 2.3 判读证据链

**userInput 在 wire 层正确到达 SUT**：fullSnapshot 从 Round 2 起就包含用户答复 `"帮我查DeepSeek-R1的官方定价"`、`"请直接给我 DeepSeek-V3 的官方定价链接"` —— A2A 通信、Task history 存储没问题。

**但 agent 从不消费 userInput**：Round 2 用户已经点明 DeepSeek-R1、Round 3 已经点明 DeepSeek-V3（有型号 + 有意图 + 有关键动词），agent 仍继续 `ask_user` 提出**同一个"缺型号"追问**。这只能是 `AskUserRail.resolveInterrupt` 错返 `approve()` → `BaseInterruptRail.applyDecision` no-op → 工具体以**原参数**（"未选定型号"）重新执行 → 再次 `ask_user` 的教科书式循环。

---

## 3. 期望行为与依据

### 3.1 依据：`BaseInterruptRail` 决策语义合约（agent-core-java）

| Decision | 语义 |
|---|---|
| `approve()` / `ApproveResult(null)` | no-op：工具体以**原始参数**继续执行 |
| `approve(newArgs)` | 覆盖参数后执行工具体 |
| `reject(toolResult)` | **短路工具体**，把 `toolResult` 直接作为 tool-call 的 result 注入 LLM 上下文 |
| `interrupt(request)` | 抛出，parent Task 再次挂起 |

### 3.2 依据：AskUserRail 语义

`ask_user` tool 的用户答复本质是 tool-call 的 result（用户告诉 agent "型号是 DeepSeek-V3"），应短路工具体（不需要重新问一次）并把答复作为 tool-result 回填 LLM。因此 `resolveInterrupt(userInput)` 在 `userInput != null` 分支的**唯一合理决策是 `reject(userInput)`**，而非 `approve()`。

---

## 4. 根因（依 issue-48 verbatim）

`AskUserRail.resolveInterrupt(userInput)` 大约在 line 40：

```java
if (userInput != null) {
    return approve();     // ❌ bug：等价 no-op，工具体以原参数 re-run，userInput 丢
}
```

正确形态：

```java
if (userInput != null) {
    return reject(userInput);   // ✅ 短路工具体，注入 userInput 为 tool-result
}
```

---

## 5. 复现步骤

### 5.1 前置

- deep-research @ `http://7.209.189.82:18090`
- search-agent @ `http://7.209.189.82:18091`
- 部署的 `agent-core-java` 版本触发此 bug 的分支（本地 `~/.m2` 落 0.1.13，与 82 服务器版本对齐待确认）

### 5.2 复现命令

```bash
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
cd agent-runtime-acceptance
TEST_ENV=SIT ./mvnw -Dtest=MultiTurnSearchFollowupTest test
```

**期望**（bug 未修）：用例 FAIL @ DA-08.A（5 轮 INPUT_REQUIRED 后终态非 COMPLETED）。
**修复后**：Round 2 或 3 内到达 COMPLETED，用例 PASS。

### 5.3 手工复现（可选）

按 [BUG-002 §5.3](BUG-002-input-required-prompt-lost-as-ask-user.md#L179) 相同 curl 序列发 Round 1 拿 taskId，再多轮 SendMessage 携同 taskId + 明确型号 userInput，观察 `getTask` 响应始终返回 INPUT_REQUIRED 且 history 里累加 userInput 但从不进入 COMPLETED。

---

## 6. 修复方向

### 6.1 主修复：`AskUserRail.resolveInterrupt` 使用 reject(userInput)

按 §4 一行改动。上游 `agent-core-java` 已收到 [issue-48](https://gitcode.com/openJiuwen/agent-core-java/issues/48) 报告，等 openjiuwen 侧 rev。

### 6.2 与 BUG-002 的关系

BUG-008（本档，Turn 2 回投丢 userInput）与 [BUG-002](BUG-002-input-required-prompt-lost-as-ask-user.md)（Turn 1 下发丢自然语言追问）是同 `AskUserRail` 桥接层的**双段互补 bug**，一次修复应对两处：

- `AskUserRail.buildInterruptRequest`（或等价方法）应把 `ask_user.arguments.question` 落到 InterruptRequest 的 user-visible message 字段 → 修 BUG-002
- `AskUserRail.resolveInterrupt(userInput)` 应返回 `reject(userInput)` → 修 BUG-008

修完两处后 DA-08 应恢复到"首观 2026-07-13 状态"（甚至更好：Round 1 追问文本非 `"ask_user"`、Round 2 直接 COMPLETED）。

---

## 7. 附：证据文件

- **SUT 部署**：deep-research @ 7.209.189.82:18090 · search-agent @ 7.209.189.82:18091
- **用例代码**：`agent-runtime-acceptance/src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java`
- **surefire 输出**：`agent-runtime-acceptance/target/surefire-reports/TEST-com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.MultiTurnSearchFollowupTest.xml`（2026-07-27 11:52 CST）
- **上游追踪**：https://gitcode.com/openJiuwen/agent-core-java/issues/48

---

## 8. 修复验证清单

修复后期望现象：

1. `MultiTurnSearchFollowupTest` PASS（DA-08.A 5 轮上限内到 COMPLETED）；
2. Round 2（明确型号答复后）Task 状态即为 COMPLETED，artifact 反映 userInput 明确指向的型号定价；
3. 若 BUG-002 一同修好：Round 1 `textOf(task)` 抽出自然语言追问句子（含"?"、"哪款"等语义信号），不再是 `"ask_user"` 字面串。

建议同时在 FEAT-004 §9.4 落地精细断言（Round 1 追问文本非 tool-name + Round 2 userInput 语义命中），作为修复后**双段一起绿**的回归 watchdog。

---

## 9. 2026-07-27 本地验证：BUG-008 已修复

### 9.1 环境

- 换掉本地 `~/.m2/repository/com/openjiuwen/example/agent-{deep-research,search}/0.1.0/*.jar` 为 openjiuwen 侧新 build（含 issue-48 fix）
- `agent-runtime-acceptance` 侧 `MultiTurnSearchFollowupTest` 改造成双 stack（同 `DownstreamAgentKilledMidStreamTest`），先启 search 拿 baseUrl，再通过 `SEARCH_AGENT_URL` env 注给 deep-research
- Shell env：`LLM_* + MCP_DOCSERVER_URL + MCP_SERVER_NAME + TAVILY_API_KEY`（去 HTTP_PROXY/HTTPS_PROXY）

### 9.2 结果

`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 148.6 s`（2026-07-27 14:47-14:50 CST，4 轮内 COMPLETED，未触 5 轮上限）

### 9.3 硬证据：`tool_result` 严格匹配 `ask_user tool_call_id`

`grep '"role":"tool","content":".*","tool_call_id":"call_00_...' target/sit-logs/search/stdout.log` 抽出以下三条 user-reply 类 tool_result（其余全部是 web_search fail 型）：

```
"role":"tool","content":"帮我查DeepSeek-R1的官方定价","tool_call_id":"call_00_HZpnv1HaqYtdiX9gcxzR2464"
"role":"tool","content":"请直接给我 DeepSeek-V3 的官方定价链接","tool_call_id":"call_00_7dBRRpuFMLKhYxC3SYpr7020"
"role":"tool","content":"请直接给我 DeepSeek-V3 的官方定价链接","tool_call_id":"call_00_q0X4a2gQrmN5wz3jov6w4568"
```

对上每一条 `tool_call_id` 都能在同 log 里定位到匹配的 `{"name":"ask_user","id":"call_00_..."}` 上游 tool_call：

```
"name":"ask_user"},"type":"function","id":"call_00_HZpnv1HaqYtdiX9gcxzR2464"
"name":"ask_user"},"type":"function","id":"call_00_7dBRRpuFMLKhYxC3SYpr7020"
"name":"ask_user"},"type":"function","id":"call_00_q0X4a2gQrmN5wz3jov6w4568"
```

**这就是 `reject(userInput)` 的观测特征** —— 用户回复以 tool_result content 落到 ask_user 对应 tool_call_id 上，供 LLM 消费。若 §4 那行 `approve()` 未修，此三条 tool_result **应该缺席**（工具体以原 args re-run，LLM 只看到重复的 ask_user 请求而非 user reply）。

### 9.4 二级证据：3 个 A2A resume 循环全部干净闭环

`grep 'INPUT_REQUIRED preserved|A2A RESUME|toServeRequest' target/sit-logs/search/stdout.log` 显示 3 组严格配对的 `INPUT_REQUIRED preserved` → `A2A RESUME`（同 taskId）→ `toServeRequest textLen=`：

| 循环 | INPUT_REQUIRED taskId | RESUME textLen | 对应用户 reply |
|---|---|---|---|
| 1 | 414d3d08-2238-451d-b098-6cb75b9e0625 | 19 | "帮我查DeepSeek-R1的官方定价" |
| 2 | ce4aecbe-f441-490b-8f6c-d9cb9391a285 | 25 | "请直接给我 DeepSeek-V3 的官方定价链接" |
| 3 | 304593d2-1385-4802-9415-c4b4ad7beb1e | 25 | "请直接给我 DeepSeek-V3 的官方定价链接" |

无死循环、无 taskId 漂移、textLen 与用户 reply 字节长度精确匹配。

### 9.5 保留问题

- **BUG-002 依然存在**：test 侧 `fullSnapshot(task)` 每轮 INPUT_REQUIRED 段仍是 `ask_user` 字面 4 字符 + 用户 reply 拼接，缺 search-agent 真正的追问自然语言（"DeepSeek 有多款模型..."）—— 客户端拿到的仍是 opaque tool-name。这段"追问文本丢失"目前只能在 search-agent 服务端日志里看到，客户端仍是黑盒。详见 [BUG-002 §2.4](BUG-002-input-required-prompt-lost-as-ask-user.md) 复观段。
- **TAVILY key 已注入但受内网出口限制**：本次日志里 web_search 均 `HTTP connect timed out`（不再是 `env var is required`），说明 env 已透进子进程；但公网出口被 unset proxy 卡住。这与 BUG-008 判定无关，DA-08.D 通过 deep-research 兜底知识 + MCP 满足。
