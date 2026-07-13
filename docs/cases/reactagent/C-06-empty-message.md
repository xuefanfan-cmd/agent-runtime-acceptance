---
id: C-06
title: 空消息
module: C — 异常边界
owner: haozhizhuang
priority: P2
feature: —
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，无需 trip/hotel）
tags: [component]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - mainplan 启动时已配置 LLM（见 §6；由测试人员准备，用例代码不门禁；含后置健康探测）
smoke_scope: 不纳入 SmokeTestSuite（P2 边界用例）
---

# C-06 — 空消息

> **一句话**：在托管栈拉起的 mainplan 上，经 **同步** 与 **流式** 两条路径各发送一次空字符串 `""`，
> 验证任务 **优雅失败**（`FAILED` + `INVALID_INPUT` + 可识别错误文案）、**SUT 不崩溃**，
> 且随后一条正常消息（「你好」）仍可 **COMPLETED**，证明 agent 仍可用。
>
> 本用例为 **P2 异常边界**；断言对齐当前 runtime 实现（`A2aAgentExecutor` 对 blank query 拒绝），
> 与 SIT 计划字面「返回提示或忽略」的差异见 §11。

---

## 1. 场景目标

验证空用户输入在 A2A 北向面的 **可预期失败** 与 **服务韧性**：

1. 发送 `inputText=""`（空字符串，非 null、非省略 message）；
2. 收到合法 A2A 响应面（有 `taskId`、有终态事件），**无**未处理 transport 崩溃；
3. 终态为 `TASK_STATE_FAILED`，错误含 `INVALID_INPUT` 及 detail「无文本」语义；
4. **后置健康探测**：同传输路径再发 `healthProbeText`（默认「你好」）→ `COMPLETED` + 非空文本；
5. **C-06-S**（流式）与 **C-06-Y**（同步）各执行上述流程一次。

**本用例不覆盖**：

- 纯空白字符 `"   "`、无 `TextPart` 的畸形 message（后续扩展）
- JSON-RPC 层 malformed body（→ runtime 单测 / 其他协议用例）
- LLM 不可用（→ C-09）
- 超长消息（→ C-07）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建并可在 `~/.m2` 解析 | **FAIL** |
| 2 | 测试框架拉起 **仅 mainplan**（per-class） | 就绪失败 → **FAIL** |
| 3 | mainplan 已具备可用 LLM 配置（`LLM_*`、`sut.java.system-properties` 等，见 §6） | 由测试人员准备；缺失时后置「你好」探测可能 **FAIL** |
| 4 | **不**要求 trip / hotel / Redis | — |
| 5 | 流式 / 同步子场景各 **独立测试类**（`streaming` 栈级互斥） | — |

> LLM 为**运行前置**，但**不在测试类内**做环境变量门禁或 `@EnabledIf` 跳过；凭据与 profile 由执行测试的人员在拉起 agent 前配置。

---

## 3. 场景步骤（总览）

### 3.1 C-06-Y — 同步空消息

| # | 动作 | 预期 |
|---|------|------|
| 1 | 起栈 `streaming(false)`，仅 mainplan；LLM 由启动环境注入（见 §6） | 就绪 |
| 2 | `sendMessage("")` + collector，等待终态 | C-06.Y.A～D |
| 3 | 同客户端 `sendMessage("你好")` + collector | C-06.Y.E 健康探测 |

### 3.2 C-06-S — 流式空消息

| # | 动作 | 预期 |
|---|------|------|
| 1～2 | 同 C-06-Y，但 `streaming(true)` | — |
| 2 | 流式 `sendMessage("")` + collector | C-06.S.A～D |
| 3 | 流式发送 `healthProbeText` | C-06.S.E |

---

## 4. 可观测子断言

> 黑盒：仅经 A2A SDK / JSON-RPC / SSE 观测。  
> 前缀 **C-06.Y.** = 同步；**C-06.S.** = 流式。

### C-06.*.0b — 传输门禁

- **C-06.Y.0b**：`streaming(false)` → `message/send`。
- **C-06.S.0b**：`streaming(true)` → `message/stream`。
- **FAIL**：与声明路径不符。

### C-06.*.A — 调用不崩溃

- **When**：发送 `inputText=""`。
- **Then**：
  - 在 `emptyMessageTimeoutMs` 内收到 ≥1 个任务相关事件；
  - 测试 JVM / 客户端 **无**未捕获致命异常；
  - mainplan 进程在调用后仍存活（后置探测可间接验证；若 A 阶段已导致进程退出则 E 必 FAIL）。
- **PASS**：有响应面且无客户端崩溃。**FAIL**：超时零事件、连接重置、进程不可用。

### C-06.*.B — 终态 FAILED

- **When**：从 collector 终态事件取 `Task.status().state()`。
- **Then**：`== TASK_STATE_FAILED`。
- **PASS** / **FAIL**：终态为 `COMPLETED`/`WORKING` 等 → **FAIL**（与当前 SUT 规约不符）。

### C-06.*.C — INVALID_INPUT + 错误文案（严格）

- **When**：从终态 Task 按 §5 提取错误文本与结构化 error（若有）。
- **Then**（**全部**满足）：
  1. 可读文本或 metadata 中含 **`INVALID_INPUT`**；
  2. 同一错误面中含 **`no text`** 子串（对应当前 detail：`message contains no text content`；大小写按实现提取后匹配）；
  3. `retryable == false`（若暴露 `a2a.error` / DataPart，则断言；未暴露则仅记录 observation gap，**不**单独 FAIL）。
- **PASS**：1+2 满足。**FAIL**：缺 `INVALID_INPUT` 或缺 `no text` 语义。

### C-06.*.D — taskId 非空

- **Given**：C-06.*.A 收到的事件流。
- **Then**：可提取单一非空 `taskId`。
- **PASS** / **FAIL**：无 taskId。

### C-06.*.E — 后置健康探测

- **Given**：C-06.*.B～D 已通过（空消息已 FAILED，未破坏服务）。
- **When**：**同一** `client("mainplan")`、**同一传输路径**，发送 testdata `healthProbeText`（默认「你好」）。
- **Then**：终态 `TASK_STATE_COMPLETED`；`textOf(Task)` trim 后非空。
- **PASS**：满足。**FAIL**：FAILED、超时、空文本、或 transport 错误。

---

## 5. 错误与健康探测提取规则

### 5.1 失败 Task 错误面

按顺序聚合（拼接后做 C-06.*.C 子串匹配）：

1. `status().message()` 的 `TextPart`（期望形态：`INVALID_INPUT: message contains no text content`）；
2. 同 message 的 `metadata` 中 `a2a.error.code` / `a2a.error.message`（若 SDK 暴露）；
3. `artifacts` 内 TextPart / DataPart `code` 字段。

### 5.2 健康探测文本

与 A-04 相同 `textOf(Task)` 规则；只断言非空，不断言具体措辞。

### 5.3 空 message 构造

```java
Message empty = A2A.toUserMessage("");  // text 长度为 0
```

**禁止**发送 null message 或省略 parts（不属于本用例「空字符串」范围）。

---

## 6. LLM 配置（测试人员准备，用例不门禁）

C-06 **不在测试代码中**读取 `SIT_LLM_API_KEY`、不使用 `@EnabledIf` 按 profile 跳过。执行前由测试人员保证 mainplan 能调用 LLM（后置「你好」探测需要），方式与 A-04 / A-05 对齐：

| 方式 | 说明 |
|------|------|
| `LLM_*` 环境变量 | `ProcessLauncher` 传给 managed agent |
| `application-<env>.yml` → `sut.java.system-properties` | JVM `-D` 注入 |
| remote 模式 | LLM 在预部署 SUT（如 13003） |

未配置时不在测试侧提前 FAIL；空消息路径仍可能 PASS，后置健康探测可能 **FAIL**。

---

## 7. 测试数据

文件：`src/test/resources/testdata/component/boundary/c06-empty-message.json`

```json
{
  "_doc": "C-06 empty user message — sync + stream sub-scenarios",
  "inputText": "",
  "healthProbeText": "你好",
  "emptyMessageTimeoutMs": 30000,
  "healthProbeTimeoutMs": 60000,
  "expectedTerminalState": "TASK_STATE_FAILED",
  "expectedErrorCode": "INVALID_INPUT",
  "expectedErrorDetailFragments": ["no text"],
  "healthProbeExpectedTerminalState": "TASK_STATE_COMPLETED"
}
```

| 字段 | 用途 |
|------|------|
| `inputText` | 必须为空字符串 `""` |
| `healthProbeText` | 后置正常消息 |
| `expectedErrorDetailFragments` | C-06.*.C 子串匹配（全部命中） |

读取方式：文案与超时写在 `EmptyMessageFlow` 常量；JSON 仅外置参考。**不**使用 `main` ScenarioData。

---

## 8. 框架落点

| 项 | C-06-Y（同步） | C-06-S（流式） |
|----|----------------|----------------|
| 测试类 | `EmptyMessageSyncTest.java` | `EmptyMessageStreamTest.java` |
| 路径 | `src/test/java/.../component/boundary/` | 同左 |
| 标签 | `@Tag("component")` **无** `@Tag("smoke")` | 同左 |
| 基类 | `BaseManagedStackTest` | 同左 |
| 栈 | `.streaming(false).agent("mainplan")` | `.streaming(true).agent("mainplan")` |
| 收集 | `A2aEventCollector` + `sendMessage` | 同左 |

**单测方法建议**（每类一个主场景）：

```text
emptyMessage_failsWithInvalidInput_thenHealthProbeCompletes()
  → 步骤 2: 空消息断言 A～D
  → 步骤 3: 健康探测 E
```

**实现检查清单**：

- [ ] 两测试类，`streaming` 显式 false / true
- [ ] 不在测试类内门禁 `SIT_LLM_API_KEY` 或按 `test.env` 跳过
- [ ] 空字符串非 null/省略
- [ ] C-06.*.C：`INVALID_INPUT` + `no text`
- [ ] 后置探测同路径、`COMPLETED`、文本非空
- [ ] 不进 `SmokeTestSuite`

---

## 9. 运行方式

```bash
# managed LOCAL（LLM 见 application-local.yml 的 LLM_*）
./mvnw -Dtest=EmptyMessageSyncTest test
./mvnw -Dtest=EmptyMessageStreamTest test
./mvnw -Dtest=EmptyMessageSyncTest,EmptyMessageStreamTest test

# remote SIT
./mvnw -Dtest.env=SIT -Dtest=EmptyMessageSyncTest,EmptyMessageStreamTest test
```

> P2 边界用例：日常 `mvn test` **可**包含本类；**不**加入 smoke 套件。缺 LLM 时后置探测可能 **FAIL**（不再 Skip）。

---

## 10. 覆盖特性追溯

| 特性 | 覆盖 |
|------|------|
| SIT 矩阵「—」 | 异常边界专项 |
| 特性 3-1 / 3-2 | ⚠️ 传输前置（双路径） |
| 特性 4-2 / 4-3 | ⚠️ 协议输入校验前置 |
| 错误码分类 INVALID_INPUT | C-06.*.C ✅（release 7.3 错误码分类） |

---

## 11. 风险与备注

- **与 SIT 计划字面偏差**：计划写「返回提示或忽略，不崩溃」；当前 SUT 为 **`FAILED` + INVALID_INPUT**（`A2aAgentExecutor` L174–179）。本用例 **以代码行为为准**；若产品改为友好 `COMPLETED` 提示，需修订 C-06 与 SIT 表。
- **空消息不调 LLM**：失败发生在 executor 入参校验；后置「你好」仍需运行时可用的 LLM。
- **两测试类**：同 A-06 / B-04，因 `BaseManagedStackTest` 栈级 `streaming` 不可混用。
- **空白字符**：仅测 `""`；`"   "` 是否同样 `isBlank()` 拒绝留待扩展用例。
- **与 C-07 边界**：C-07 测超长输入；C-06 不测 OOM / 截断。

---

## 12. 规约依据（被测代码）

空 query 拒绝路径（实现参考，非测试直接依赖）：

```174:179:D:\code-agent\spring-ai-ascend\agent-runtime\src\main\java\com\huawei\ascend\runtime\engine\a2a\A2aAgentExecutor.java
            if (inputText.isBlank() && !remote.isRemoteContinuation(ctx)) {
                LOG.warn("[A2A] rejecting task with empty query taskId={}", taskId);
                emitter.fail(failureMessage(emitter, "INVALID_INPUT",
                        "message contains no text content", false));
                LOG.info("[A2A] task state=FAILED taskId={}", taskId);
                return;
            }
```

北向错误文本形态：`INVALID_INPUT: message contains no text content`（`failureMessage` TextPart）。
