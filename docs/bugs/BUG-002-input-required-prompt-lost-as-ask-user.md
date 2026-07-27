---
id: BUG-002
title: deep-research 在 INPUT_REQUIRED 时只回传 "ask_user"（tool-call name），实际追问文本丢失
module: DA — deep-research 多轮追问链路 · AskUserRail 桥接层（agent-core-java）
sut: deep-research（http://7.209.189.82:18090） + 下游 search-agent（http://7.209.189.82:18091）
observed_on: 2026-07-13（首观） · 2026-07-27（复观：bug 依然存在，且叠加 [BUG-008](BUG-008-askuser-rail-resolve-interrupt-approve-userinput-discarded.md) 触发死循环） · 2026-07-27 下午二次复观（openjiuwen 新 jar 修好 BUG-008 后 BUG-002 仍在，追问自然语言只在 search-agent 服务端日志出现）
severity: P1（不阻塞协议契约，但破坏客户端可用性——客户端拿到 `"ask_user"` 无从判断该答什么；叠加 BUG-008 后死循环）
tests:
  - agent-runtime-acceptance/src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java
spec_refs:
  - docs/cases/deepagent/DA-08-multi-turn-search-followup.md §4 DA-08.B（追问链路存在性证据）
  - A2A spec §Task.status.message（INPUT_REQUIRED 时应承载给用户的追问文本）
  - A2A spec §Artifact（agent 中间/最终产出的可读内容）
related:
  - BUG-008（AskUserRail.resolveInterrupt userInput 回投丢弃 · gitcode issue-48 · 同 AskUserRail 桥接层双段互补 bug）
---

# BUG-002 — INPUT_REQUIRED 时追问文本丢失，客户端只拿到 `ask_user` 字面量

## 1. 摘要（TL;DR）

用 DA-08 场景验证 deep-research 多轮追问链路时发现：**协议状态机走对了**（Round 1 停在
`TASK_STATE_INPUT_REQUIRED`，Round 2 续答后到达 `COMPLETED`），但 **Round 1 回传给客户端的
可读文本只有一个字符串 `"ask_user"`** —— 无论从 `task.artifacts[].parts` 抽、从
`task.status.message.parts` 抽、还是从 `task.history` 抽，得到的都是同一个字符串。

`"ask_user"` 看起来是 search-agent 内部 tool call 的名字，被 deep-research 未经改写就
透传给了客户端；**search-agent 实际生成的追问句子**（业务上应是"请问您想查询哪一款
DeepSeek 模型？"之类的自然语言）**在 A2A 链路里被吞掉了**。

**影响**：
- **人类用户 / 自然语言客户端**看到 `"ask_user"` 完全不知道该回答什么，只能瞎猜；
- **自动化客户端**（如 DA-08 用例）只能靠场景先验（本档预设 Turn2 答 `"DeepSeek-V3"`）
  才能续答，无法通用；
- **多轮追问的可用性归零**——协议契约通过，但产品语义没通过。

---

## 2. 观测数据（wire-level）

**采集方式**：`MultiTurnSearchFollowupTest.multiTurnSearchFollowupReachesCompletedWithPricing`
在每一轮 `INPUT_REQUIRED`/`COMPLETED` 后立即 `a2a.getTask(tid)` 拉快照，用
`TaskTextExtractor.textOf(task)` 和 `TaskTextExtractor.fullSnapshotTextOf(task)`
分别抽出可读文本；`textOf` 优先 artifacts → status.message → history 三级降级，
`fullSnapshot` 拼 history + artifacts + status.message 完整语料。日志见 §2.3。

### 2.1 Round 1（用户查询「DeepSeek 定价」）

- **Request**（`method = SendMessage`, `streaming = false`）
  ```json
  {
    "role": "USER",
    "contextId": "ctx-da08-followup-c8428bb8",
    "parts": [{"type": "text", "text": "你好,帮我查一下DeepSeek官方定价，请给出官网链接"}]
  }
  ```

- **Response**（`getTask` 快照，2026-07-13 SIT 观测）
  ```
  task.id       = 32124f68-8ae0-4cd8-bb66-600c06bb1ce0
  task.status   = TASK_STATE_INPUT_REQUIRED
  TaskTextExtractor.textOf(task)              = "ask_user"
  TaskTextExtractor.fullSnapshotTextOf(task)  = "ask_user"
  ```
  两个抽取路径拿到的是**同一个字符串** `"ask_user"`——说明 artifacts / status.message /
  history 里没有任何自然语言追问文本，只有这个 tool-call name。

- **期望**（举例，具体措辞由 SUT 决定）
  ```
  task.status   = TASK_STATE_INPUT_REQUIRED
  task.status.message.parts[0].text = "请问您想查询哪一款 DeepSeek 模型的定价？"
  ```
  或至少：任一可读字段（artifacts / status.message / history 之一）应包含自然语言追问句子。

### 2.2 Round 2（客户端续答 `"DeepSeek-V3"`）

- **Request**（延续同 `taskId + contextId`）
  ```json
  {
    "role": "USER",
    "contextId": "ctx-da08-followup-c8428bb8",
    "taskId":    "32124f68-8ae0-4cd8-bb66-600c06bb1ce0",
    "parts": [{"type": "text", "text": "DeepSeek-V3"}]
  }
  ```

- **Response**（`getTask` 快照）
  ```
  task.status                                 = TASK_STATE_COMPLETED
  TaskTextExtractor.textOf(task)  ~= 完整 markdown 定价表（含 DeepSeek-V3 输入 ¥2 / 输出 ¥8
                                     每百万 token，以及三条官方链接）
  ```

  说明：Round 2 上游 agent 正常产出 artifact，**只有 Round 1 的追问文本丢了**。

### 2.3 用例原始日志（`target/surefire-reports/` 附出）

```
===== DA-08 round 1 =====
state=TASK_STATE_INPUT_REQUIRED taskId=32124f68-8ae0-4cd8-bb66-600c06bb1ce0 contextId=ctx-da08-followup-c8428bb8
----- artifact(textOf) -----
ask_user
----- full snapshot (history + artifact + status.message) -----
ask_user
==============================
DA-08 round 1 → INPUT_REQUIRED, next reply=DeepSeek-V3

===== DA-08 round 2 =====
state=TASK_STATE_COMPLETED taskId=32124f68-8ae0-4cd8-bb66-600c06bb1ce0 contextId=ctx-da08-followup-c8428bb8
----- artifact(textOf) -----
## DeepSeek 官方定价
...
| **DeepSeek-V3** | ¥2 / $0.27 | ¥8 / $1.10 |
...
- 定价页面：https://api-docs.deepseek.com/zh-cn/quick_start/pricing
...
==============================
```

### 2.4 复观（2026-07-27 SIT · bug 依然存在 · 叠加 [BUG-008](BUG-008-askuser-rail-resolve-interrupt-approve-userinput-discarded.md) 触发死循环）

**运行命令**：`TEST_ENV=SIT ./mvnw -Dtest=MultiTurnSearchFollowupTest test`（`agent-runtime-acceptance` 分支 `main` @ `5affbb1`）

**结果**：用例 FAIL（DA-08.A · 5 轮上限触发）· `trajectory=[INPUT_REQUIRED × 5]`

**每轮 full snapshot**（`target/surefire-reports/TEST-...MultiTurnSearchFollowupTest.xml` UTF-8 解码）：

| 轮 | User next reply | Task 状态 | fullSnapshot（含追问段） |
|---|---|---|---|
| 1 | 首查询"帮我查一下 DeepSeek 官方定价，请给出官网链接" | INPUT_REQUIRED | `RUNNINGINPUT_REQUIREDask_user` |
| 2 | `帮我查DeepSeek-R1的官方定价` | INPUT_REQUIRED | `ask_user 帮我查DeepSeek-R1的官方定价 RUNNINGINPUT_REQUIRED RUNNINGCOMPLETED RUNNINGINPUT_REQUIRED ask_user` |
| 3 | `请直接给我 DeepSeek-V3 的官方定价链接` | INPUT_REQUIRED | 前两轮 userInput 叠加 + 新 `ask_user` |
| 4 | 同 Round 3 | INPUT_REQUIRED | 继续叠加 |
| 5 | 同 Round 3 | INPUT_REQUIRED | 触发 DA-08.A 5 轮上限 → FAIL |

**BUG-002 复观定性**：
- 首观 2026-07-13 → 复观 2026-07-27（14 天后），**bug 完全未修**：每一轮 status.message / snapshot 的追问段依然只有字面串 `"ask_user"`，无自然语言追问句子。
- Round 1 的 `textOf(task)` 抽出 `"RUNNINGINPUT_REQUIRED"`（TaskState enum 名拼接）；`fullSnapshotTextOf` 抽出 `"RUNNINGINPUT_REQUIREDask_user"` —— 三处（artifacts / status.message / history）依旧没有自然语言追问文本。

**首观 vs 复观的差异 —— 与 [BUG-008](BUG-008-askuser-rail-resolve-interrupt-approve-userinput-discarded.md) 的叠加效应**：
- 首观时 Round 2 回 `"DeepSeek-V3"` → agent 走到 COMPLETED，用例侥幸 PASS（只是 BUG-002 被观测到）；
- 复观时 Round 2/3/4/5 无论回什么型号 → agent 都继续 `ask_user`，从不消费 userInput → 稳定死循环 → 用例 FAIL。

原因：`agent-core-java` 侧 [BUG-008](BUG-008-askuser-rail-resolve-interrupt-approve-userinput-discarded.md)（`AskUserRail.resolveInterrupt` 错返 `approve()`，userInput 被丢弃、工具体以原参数 re-run）现在稳定触发，与 BUG-002（追问下发文本丢失）叠加使 DA-08 从"首观时侥幸绿"退化到"复观时稳定红"。**BUG-002 本身没有变化，只是 BUG-008 从"侥幸不触发"退化到"必触发"，把 BUG-002 的可用性影响放大到 DA-08 无法完成**。

### 2.5 二次复观（2026-07-27 下午 · 本地替换 openjiuwen 新 jar · BUG-008 已修但 BUG-002 仍在）

**背景**：openjiuwen 侧发布了新版 `agent-{deep-research,search}` jar（含 [issue-48](https://gitcode.com/openJiuwen/agent-core-java/issues/48) 修复），本地覆盖 `~/.m2/repository/com/openjiuwen/example/agent-{deep-research,search}/0.1.0/*.jar` 后走 `MultiTurnSearchFollowupTest` 双 stack 重跑。

**结果**：用例 PASS（`Tests run: 1, Failures: 0, ..., Time elapsed: 148.6 s`，4 轮内 COMPLETED）。BUG-008 已修（详见 [BUG-008 §9](BUG-008-askuser-rail-resolve-interrupt-approve-userinput-discarded.md#L156)：search-agent 日志里 3 处 `tool_result` 严格挂到 `ask_user tool_call_id` 上）。

**但 BUG-002 依然存在**：客户端侧每一轮 `INPUT_REQUIRED` 拿到的 `fullSnapshot(task)` 追问段仍然是字面 `ask_user` 4 字符 + 用户前几轮 reply 拼接：

| 轮 | Task 状态 | fullSnapshot 追问段 |
|---|---|---|
| 1 | INPUT_REQUIRED | `RUNNINGINPUT_REQUIREDask_user` |
| 2 | INPUT_REQUIRED | `ask_user 帮我查DeepSeek-R1的官方定价 ... ask_user` |
| 3 | INPUT_REQUIRED | 前轮 userInput 叠加 + 新 `ask_user` |
| 4 | COMPLETED | 最终 artifact（V3 + R1 定价表 + 官网链接）|

**search-agent 服务端日志同期确实产生了自然语言追问文本**（`grep '\[LLM\]   tool_call: ask_user' target/sit-logs/search/stdout.log`）：
```
[LLM] tool_call: ask_user({"question": "DeepSeek 有多款模型，请问您要查询哪个模型的官方定价？常见的有：V3、R1、V2.5、Coder 等..."})
```

**结论**：追问自然语言只出现在 **search-agent 服务端**日志里（服务端 LLM 生成的 `question` 字段），**没有**沿 A2A 链路（search-agent → deep-research → client）向上冒到客户端可见字段（status.message / artifacts / history）。这就是 BUG-002 的定位仍然精确：`AskUserRail.buildInterruptRequest`（或等价方法）**未把 `ask_user.arguments.question` 落到 A2A InterruptRequest 的 user-visible message 字段** —— 服务端有话说、客户端拿不到。

**这一段的价值**：BUG-008 修复后**首次能干净地隔离出 BUG-002 单点**（此前 BUG-008 死循环会拉长每轮 log，二者交织难以拆开）。现在的 log 是 BUG-002 精细反例断言（Round 1 追问文本非 `ask_user` tool-name + 含"?"或"哪款"等语义信号）落地的最好素材。

---

## 3. 期望行为与依据

### 3.1 依据 A：A2A spec — INPUT_REQUIRED 语义

按 A2A 规范：任务停在 `INPUT_REQUIRED` 时，**服务端必须在 `Task.status.message` 里承载
向客户端提问的文本**（或至少在 artifacts 里以可读形式暴露），否则客户端无法生成合理续答，
多轮对话链路只对场景先验的自动化脚本成立，对真实用户不成立。

### 3.2 依据 B：DA-08 设计契约

`docs/cases/deepagent/DA-08-multi-turn-search-followup.md` §1 明文：
> deep-research 收到「查 DeepSeek 定价」的模糊查询后，通过 search-agent
> 得出"缺型号"的判断，把 A2A task 停在 `TASK_STATE_INPUT_REQUIRED`，
> **通过 `status.message` 或 artifact 呈现追问文本给客户端**。

当前实现只做了「停在 INPUT_REQUIRED」这一半，**追问文本呈现**这一半未落实。

### 3.3 依据 C：观测证据

Round 1 的 `textOf` 和 `fullSnapshotTextOf` 输出完全一致，都只有 `"ask_user"`——
这意味着 artifacts / status.message / history **三处都没有自然语言追问句子**，
只有 tool-call 名字被当作 text 塞进了其中之一。

---

## 4. 根因分析（推测）

`"ask_user"` 是 search-agent 内部一个 tool 的名字（惯用命名 `ask_user` / `request_user_input`
是 langgraph / deepagents 生态的常见 human-in-the-loop tool）。看起来 SUT 侧的桥接层：

1. search-agent 决定调用 `ask_user` tool，tool call 的 **name** 是 `"ask_user"`，
   **arguments** 里理论上应包含 `question` 字段（自然语言追问句子）；
2. deep-research 侧把这次 tool call 转成 A2A `INPUT_REQUIRED` 状态；
3. **bug 点**：转换时**只取了 tool call 的 name (`"ask_user"`)** 作为要展示给客户端的文本，
   **arguments.question 被丢弃**——所以客户端只看到 `"ask_user"` 字面量。

正确做法：把 `ask_user.arguments.question` 字段（或等价的自然语言 prompt）填入
`Task.status.message.parts[0].text`；tool 名字仅用于服务端内部路由，不应作为用户可见文本。

---

## 5. 复现步骤

### 5.1 前置

- deep-research @ `http://7.209.189.82:18090`
- search-agent @ `http://7.209.189.82:18091`，且 deep-research 侧 `SEARCH_AGENT_URL` 已配好

### 5.2 复现命令

```bash
cd agent-runtime-acceptance
TEST_ENV=SIT ./mvnw -Dtest=MultiTurnSearchFollowupTest test
```

用例本身会 PASS（协议契约层的 5 项断言都过），但**观察日志或
`target/surefire-reports/` 里的 `===== DA-08 round 1 =====` 段**，可见
`textOf` / `fullSnapshot` 抽出的都是 `"ask_user"`——这就是 bug 现象。

### 5.3 手工复现（可选）

```bash
# Turn1
curl -sS -X POST http://7.209.189.82:18090/a2a \
  -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0","id":"1","method":"SendMessage",
    "params":{"message":{"role":"USER","contextId":"ctx-bug002-manual",
      "parts":[{"type":"text","text":"你好,帮我查一下DeepSeek官方定价，请给出官网链接"}]}}
  }'

# 从 Turn1 response 抽 taskId，然后 GetTask 观察 status.message
curl -sS -X POST http://7.209.189.82:18090/a2a \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","id":"2","method":"GetTask","params":{"id":"<TASK_ID>"}}'
```

`GetTask` 响应的 `result.status.message` / `result.artifacts` / `result.history` 里
应能看到 `"ask_user"` 字面量而非自然语言问句。

---

## 6. 建议修复方向

### 6.1 主修复：把追问文本填入 `Task.status.message`

在 deep-research 把 search-agent 的 `ask_user` tool call 桥接为 A2A `INPUT_REQUIRED`
状态时：

```
// 伪代码
if (toolCall.name == "ask_user" || toolCall.name == "request_user_input") {
    String question = toolCall.arguments.get("question");   // 或 "prompt"、"message" 等 tool schema 字段
    task.status.message = Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .parts(List.of(new TextPart(question)))
        .build();
    task.status.state = TASK_STATE_INPUT_REQUIRED;
}
```

若 tool schema 不同，需按 search-agent 实际 `ask_user` 的参数名调整。

### 6.2 兜底：至少保证 `history` 里含有追问文本

如果 status.message 语义要留给别的用途，退而求其次：把追问文本写入 `task.history` 里
一个 role=AGENT 的 Message，这样 `textOf` 的三级降级至少能命中。

### 6.3 附加：`"ask_user"` 不应作为用户可见文本

不管上面的修复走哪一路径，都应该**保证 `"ask_user"` 这个 tool 名不出现在客户端可读文本里**。
可以在桥接层加一个断言/单测：INPUT_REQUIRED 时 status.message 或 artifact 之一必须包含
非 tool-name 的自然语言（长度 > 5 且不等于任何已知 tool name）。

---

## 7. 附：证据文件路径

- **用例代码**（含日志构造）
  - `agent-runtime-acceptance/src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MultiTurnSearchFollowupTest.java`
- **用例设计文档**
  - `agent-runtime-acceptance/docs/cases/deepagent/DA-08-multi-turn-search-followup.md`
- **surefire 输出**（跑测后生成）
  - `agent-runtime-acceptance/target/surefire-reports/com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.MultiTurnSearchFollowupTest.txt`

---

## 8. 修复验证清单

修复后期望现象：

1. Round 1 `textOf(task)` 或 `fullSnapshotTextOf(task)` 抽出的文本**不再是 `"ask_user"`**，
   而是自然语言追问（含 `DeepSeek` / `模型` / `哪` / `?` 等语义信号）；
2. 客户端在**不知道场景先验**的情况下也能根据追问句子回答（举例：一个通用聊天机器人客户端
   把追问原样呈现给人类用户，人类应能理解要答什么）；
3. `MultiTurnSearchFollowupTest` 仍 PASS（本 bug 修复不应破坏现有协议契约层断言）。

建议同时新增一条**追问文本可读性**断言到 DA-08（或另开 DA-08.F）：Round 1 `INPUT_REQUIRED`
时 `textOf(task)` 必须 (a) 非空且 (b) 不等于 `"ask_user"` 或任何已知 tool name。
本 bug 修复后该断言应转 PASS。