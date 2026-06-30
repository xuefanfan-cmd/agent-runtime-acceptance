---
id: C-07
title: 超长消息
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
  - mainplan 启动时已配置 LLM（见 §6；由测试人员准备，用例代码不门禁）
smoke_scope: 不纳入 SmokeTestSuite（P2 边界用例）
---

# C-07 — 超长消息

> **一句话**：在托管栈拉起的 mainplan 上，经 **同步** 与 **流式** 两条路径各发送一条 **≥5000 字**
> 的出差类长文本，验证 **在超时内到达合法终态、SUT 不崩溃**；随后 **同路径** 再发「你好」，
> 必须 `COMPLETED` 且非空，间接证明 **未 OOM / 未打挂进程**。
>
> 本用例为 **P2 异常边界**；主判据为 **韧性**（正常处理或优雅失败均可），
> **不要求** 长输入必须 `COMPLETED`。

---

## 1. 场景目标

验证超长用户输入下 runtime 的 **稳定性** 与 **服务可恢复性**：

1. 构造 `inputCharCount >= minInputChars`（默认 **5000**）的出差场景长文本（模板 + 填充，见 §7）；
2. 发送长文本，在 testdata 规定的 `longMessageTimeoutMs` 内收到 **终态** Task；
3. 硬断言：有非空 `taskId`、终态 `TaskState.isFinal() == true`、无 transport / JSON-RPC 致命错误、mainplan 进程仍可用；
4. **后置健康探测**（硬断言）：同客户端、同传输路径发送 `healthProbeText` → `COMPLETED` + 非空文本；
5. **C-07-Y**（同步）与 **C-07-S**（流式）各执行一次上述流程。

**本用例不覆盖**：

- 精确测堆内存 / GC（黑盒不可测；以健康探测代证「不 OOM」）
- 超长输入的业务回答质量、是否截断及截断位置（SIT「或截断」仅作背景，不做逐字校验）
- 空消息（→ C-06）、LLM 不可用（→ C-09）、连续快速发送（→ C-08）
- 三级链 remote dispatch（刻意 **单 mainplan**，降低变量）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建并可在 `~/.m2` 解析 | **FAIL** |
| 2 | 测试框架拉起 **仅 mainplan**（per-class） | 就绪失败 → **FAIL** |
| 3 | mainplan 已具备可用 LLM 配置（`LLM_*`、`sut.java.system-properties` 等，见 §6） | 由测试人员准备；缺失时后置「你好」探测可能 **FAIL** |
| 4 | 流式 / 同步子场景各 **独立测试类** | — |
| 5 | **无需** trip / hotel / Redis | — |

> LLM 为**运行前置**，但**不在测试类内**做环境变量门禁或 `@EnabledIf` 跳过；凭据与 profile 由执行测试的人员在拉起 agent 前配置。

---

## 3. 场景步骤（总览）

### 3.1 C-07-Y — 同步超长消息

| # | 动作 | 预期 |
|---|------|------|
| 1 | 起栈 `streaming(false)`，仅 mainplan；LLM 由启动环境注入（见 §6） | 就绪 |
| 2 | 按 §7 构造长文本，`sendMessage` + collector | C-07.Y.A～D |
| 3 | 同路径发送 `healthProbeText` | C-07.Y.E |

### 3.2 C-07-S — 流式超长消息

| # | 动作 | 预期 |
|---|------|------|
| 1～2 | 同 C-07-Y，但 `streaming(true)` | — |
| 2 | 流式发送长文本 + collector | C-07.S.A～D |
| 3 | 流式健康探测 | C-07.S.E |

---

## 4. 可观测子断言

> 前缀 **C-07.Y.** = 同步；**C-07.S.** = 流式。

### C-07.*.0b — 传输门禁

- **C-07.Y.0b**：`streaming(false)`。
- **C-07.S.0b**：`streaming(true)`。

### C-07.*.A — 长输入调用不崩溃

- **When**：发送 §7 构造的长文本（长度 ≥ `minInputChars`）。
- **Then**：
  - `longMessageTimeoutMs` 内收到 ≥1 个任务相关事件；
  - 测试 JVM 无未捕获致命异常；
  - JSON-RPC / SSE 无连接级致命错误（单次业务 `FAILED` 允许）。
- **PASS** / **FAIL**：超时零事件、连接重置、客户端崩溃 → **FAIL**。

### C-07.*.B — 合法终态（宽松主判据）

- **When**：从 collector 取终态 `Task.status().state()`。
- **Then**：`state.isFinal() == true`（如 `COMPLETED` / `FAILED` / `CANCELED` / `INPUT_REQUIRED` 等）。
- **PASS**：终态且 final。**FAIL**：超时仍 `SUBMITTED`/`WORKING`，或无终态事件。

> **`COMPLETED` 与 `FAILED` 均可 PASS**；长输入导致 `FAILED`（如上游拒绝、输入过长）不单独判 FAIL，只要 B 满足。

### C-07.*.C — taskId 非空

- **Then**：长输入调用可提取单一非空 `taskId`。
- **PASS** / **FAIL**：无 taskId。

### C-07.*.D — 终态观测（不改变主 verdict）

- **When**：B 已 PASS。
- **Then**：日志写入 `long_message_terminal_state=<STATE>`；若 `FAILED`，附加记录 `failure_text` 摘要（便于区分 OOM 与业务/上游错误）。
- **PASS**（主判据）：A～C 满足。**FAIL**：不适用本子断言。

### C-07.*.E — 后置健康探测（硬断言）

- **Given**：C-07.*.A～C 已通过。
- **When**：**同一** `client("mainplan")`、**同一传输路径**，发送 `healthProbeText`（默认「你好」），等待 `healthProbeTimeoutMs`。
- **Then**：终态 `TASK_STATE_COMPLETED`；`textOf(Task)` trim 后非空。
- **PASS**：满足。**FAIL**：FAILED、超时、空文本、transport 错误。

> **「不 OOM」的测试代证**：若进程因 OOM 退出或不可恢复，本步骤必 **FAIL**。

---

## 5. 文本抽取

### 5.1 终态与失败摘要

- 终态：`collector.awaitTerminalState(longMessageTimeoutMs)` 或等价；
- 失败摘要：对 `FAILED` 任务，从 `textOf(Task)` 或 `a2a.error.message` 取前 N 字符写日志（**不**要求特定错误码）。

### 5.2 健康探测

与 C-06 / A-04 相同 `textOf(Task)`；只断言非空。

---

## 6. LLM 配置（测试人员准备，用例不门禁）

C-07 **不在测试代码中**读取 `SIT_LLM_API_KEY`、不使用 `@EnabledIf` 按 profile 跳过。执行前由测试人员保证 mainplan 能调用 LLM（长输入与健康探测均需要），方式与 C-06 / A-04 对齐：

| 方式 | 说明 |
|------|------|
| `LLM_*` 环境变量 | `ProcessLauncher` 传给 managed agent |
| `application-<env>.yml` → `sut.java.system-properties` | JVM `-D` 注入 |
| remote 模式 | LLM 在预部署 SUT（如 13003） |

未配置时不在测试侧提前 FAIL；长输入路径仍可能 PASS（终态 `FAILED` 允许），后置健康探测可能 **FAIL**。

---

## 7. 测试数据与长文本构造

文件：`src/test/resources/testdata/component/boundary/c07-long-travel-message.json`

```json
{
  "_doc": "C-07 long travel message — min length + template padding builder",
  "minInputChars": 5000,
  "travelTemplatePrefix": "我要安排一次出差：从上海到北京，出发日期下周三，停留5天4晚。请根据以下详细要求给出规划建议。",
  "paddingSentence": "此外还需要考虑市内交通、会议间隙用餐、发票与报销凭证、以及与同事拼车往返机场等细节。",
  "healthProbeText": "你好",
  "longMessageTimeoutMs": 180000,
  "healthProbeTimeoutMs": 60000,
  "healthProbeExpectedTerminalState": "TASK_STATE_COMPLETED"
}
```

| 字段 | 用途 |
|------|------|
| `minInputChars` | 输入长度下限（**≥5000**，与 SIT 一致） |
| `travelTemplatePrefix` | 出差语义开头 |
| `paddingSentence` | 循环追加直至 `length >= minInputChars` |
| `longMessageTimeoutMs` | 长消息终态等待上限（**由 testdata 配置，文档不规定固定默认值**） |
| `healthProbeTimeoutMs` | 健康探测上限 |

**构造算法**（实现 `LongMessageInputBuilder`）：

```text
sb = travelTemplatePrefix
while sb.length() < minInputChars:
    sb += paddingSentence
assert sb.length() >= minInputChars
return sb.toString()
```

读取：`LongTravelMessageScenarioData` + `TestDataLoader`；**禁止**在测试方法内硬编码 5000 字文本。

---

## 8. 框架落点

| 项 | C-07-Y（同步） | C-07-S（流式） |
|----|----------------|----------------|
| 测试类 | `LongMessageSyncTest.java` | `LongMessageStreamTest.java` |
| 路径 | `src/test/java/.../component/boundary/` | 同左 |
| 标签 | `@Tag("component")`，**无** `@Tag("smoke")` | 同左 |
| 基类 | `BaseManagedStackTest` | 同左 |
| 栈 | `.streaming(false).agent("mainplan")` | `.streaming(true).agent("mainplan")` |
| 收集 | `A2aEventCollector` + `sendMessage` | 同左 |

**单测方法建议**：

```text
longTravelMessage_reachesTerminalState_thenHealthProbeCompletes()
```

**实现检查清单**：

- [ ] 两测试类，`streaming` 显式 false / true
- [ ] 不在测试类内门禁 `SIT_LLM_API_KEY` 或按 `test.env` 跳过
- [ ] `LongMessageInputBuilder`：模板 + 填充，`length >= minInputChars`
- [ ] C-07.*.B：`isFinal()`，**不**强制 `COMPLETED`
- [ ] C-07.*.E：后置「你好」硬断言
- [ ] 超时一律读 testdata
- [ ] 不进 SmokeTestSuite

---

## 9. 运行方式

```bash
# managed LOCAL（LLM 见 application-local.yml 的 LLM_*）
./mvnw -Dtest=LongMessageSyncTest test
./mvnw -Dtest=LongMessageStreamTest test
./mvnw -Dtest=LongMessageSyncTest,LongMessageStreamTest test

# remote SIT
./mvnw -Dtest.env=SIT -Dtest=LongMessageSyncTest,LongMessageStreamTest test
```

> 长输入 + LLM 可能较慢；`longMessageTimeoutMs` 可在 testdata 调大，不改断言逻辑。缺 LLM 时后置探测可能 **FAIL**（不再 Skip）。

---

## 10. 覆盖特性追溯

| 特性 | 覆盖 |
|------|------|
| SIT 矩阵「—」 | 异常边界 / 韧性 ✅ |
| 特性 3-1 / 3-2 | ⚠️ 传输前置（双路径） |
| 中间件 Checkpointer 上限 | ⚠️ 间接（长文本压力；专项见 integration-test-design 16KiB 备注） |

---

## 11. 风险与备注

- **与 SIT「正常处理或截断」**：本用例 **不验证** 是否截断及截断后内容；只验证 **终态可达 + 服务仍可用**。
- **`FAILED` 仍可能 PASS**：符合主判据 A；若需强制长输入成功，应另开业务向用例。
- **LLM 配额 / 上下文窗**：超长文本可能导致上游 `FAILED`；日志保留 `long_message_terminal_state` 与 failure 摘要便于区分。
- **与 C-06 对称**：C-06 测空输入硬失败；C-07 测长输入韧性 + 同一套后置健康探测模式。
- **两测试类**：栈级 `streaming` 互斥，同 C-06。
- **单 mainplan**：避免 trip/hotel 远程超时干扰「长输入韧性」判定。

---

## 12. 与 C-06 对照

| 维度 | C-06 空消息 | C-07 超长消息 |
|------|-------------|---------------|
| 输入 | `""` | ≥5000 字出差模板 |
| 长输入主终态 | 必须 `FAILED` | **任意 final** |
| 后置「你好」 | 硬断言 | 硬断言 |
| 传输 | 同步 + 流式 | 同步 + 流式 |
| Smoke | 否 | 否 |
