---
id: C-09
title: LLM 服务不可用
module: C — 异常边界
owner: fanfan
priority: P2
feature: 特性3-1
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，G3 坏 LLM 注入，无需 trip/hotel）
tags: [component]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - managed：启动时 G3 坏 LLM 属性（§6.1）；remote 可选 url-llm-down（§6.2）
smoke_scope: 不纳入 SmokeTestSuite（P2 边界用例）
---

# C-09 — LLM 服务不可用

> **一句话**：在 **故意配错 LLM** 的 mainplan 上，经 **同步** 与 **流式** 两条路径各发送一次
> testdata 用户消息（默认「你好」），验证任务在超时内到达 **非成功的合法终态**、**SUT 不崩溃**，
> 且错误面可观测（可读错误信息 **或** 非空 `taskId`）。
>
> **LOCAL 默认**：托管拉起 mainplan jar，启动参数注入 G3（无效 key + 不可达 base），**无需** 13005。
> **SIT 可选**：配 `url-llm-down` 连预部署坏 LLM 实例（13005），与 C-06/C-07 的 `mainplan.url` 互斥。
>
> 本用例为 **P2 异常边界**；主判据为 **韧性**（优雅失败、服务仍存活），**不要求**固定错误码
>（与 C-06 严格 `INVALID_INPUT` 区分）；与 SIT 计划「返回 FAILED，含清晰错误信息」对齐为 **宽松版 F3**（§4）。

---

## 1. 场景目标

验证上游 LLM 不可用时 runtime 北向面的 **可预期失败** 与 **进程韧性**：

1. 测试侧拉起 **仅 mainplan**，LLM 为 **G3 故意不可用**（managed 启动参数或 remote `url-llm-down`）；
2. 发送 testdata `inputText`（默认「你好」），在 `llmFailureTimeoutMs` 内收到 ≥1 个任务相关事件；
3. 终态 `TaskState.isFinal() == true`，且 **不得** 为 `TASK_STATE_COMPLETED`（业务未成功完成）；
4. 错误面：聚合文本 **非空** **或** 可提取非空 `taskId`（§5）；
5. **无**后置 message 健康探测（坏 LLM 栈上再发「你好」仍必 FAIL，见 §11）；
6. **C-09-S**（流式）与 **C-09-Y**（同步）各执行上述流程一次。

**本用例不覆盖**：

- 在 `mainplan.url`（13003）正常实例上临时关停 LLM
- 空消息 / 超长消息（→ C-06 / C-07）
- 连续快速发送（→ C-08）
- 流式客户端断连恢复（→ C-10）
- 精确断言 `UPSTREAM_UNAVAILABLE` 错误码（→ 本用例 F3 宽松；若需严格码表另开用例）
- 后置「你好」`COMPLETED` 探测（→ C-06/C-07 模式，本用例明确 **不做**）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建（managed）或 `url-llm-down` 可达（remote） | 启动 / 连接失败 → **FAIL** |
| 2 | mainplan 上 LLM 为 **G3 故意不可用**（§6.1 / §6.2） | 若终态 `COMPLETED` → 配置错误，C-09.*.B **FAIL** |
| 3 | **不**要求 trip / hotel / Redis | — |
| 4 | 测试 JVM **无需** 正常 LLM 凭据（坏 LLM 在 SUT 侧） | — |
| 5 | 流式 / 同步子场景各 **独立测试类** | — |

> 本用例**不在测试类内**使用 `@EnabledIf` 跳过。LOCAL 缺 jar → **FAIL**；remote 配了 `url-llm-down` 但实例不可达 → **FAIL**。

---

## 3. 场景步骤（总览）

### 3.1 C-09-Y — 同步 LLM 不可用

| # | 动作 | 预期 |
|---|------|------|
| 1 | 起栈 `streaming(false)`，仅 mainplan（G3 坏 LLM） | 就绪 |
| 2 | `sendMessage(inputText)` + collector，等待终态 | C-09.Y.A～D |
| — | **无**步骤 3 后置探测 | — |

### 3.2 C-09-S — 流式 LLM 不可用

| # | 动作 | 预期 |
|---|------|------|
| 0～1 | 同 C-09-Y，但 `streaming(true)` | — |
| 2 | 流式 `sendMessage(inputText)` + collector | C-09.S.A～D |
| — | **无**后置探测 | — |

---

## 4. 可观测子断言

> 黑盒：仅经 A2A SDK / JSON-RPC / SSE 观测。  
> 前缀 **C-09.Y.** = 同步；**C-09.S.** = 流式；**C-09.0** = 公共。

### C-09.0 — 坏 LLM 栈门禁

- **When**：解析栈模式（`LlmUnavailableSupport.isRemoteMode()`）。
- **Then**：
  - **managed**（无 `url-llm-down`）：`LlmUnavailableStackSupport` 注入 G3 Spring 属性；
  - **remote**（有 `url-llm-down`）：连预部署坏 LLM 实例，**禁止**回退到 `mainplan.url`。
- **FAIL**：managed jar 缺失；remote URL 不可达；坏 LLM 误配导致 `COMPLETED`。

### C-09.*.0b — 传输门禁

- **C-09.Y.0b**：`streaming(false)` → `message/send`。
- **C-09.S.0b**：`streaming(true)` → `message/stream`。
- **FAIL**：与声明路径不符。

### C-09.*.A — 调用不崩溃

- **When**：发送 testdata `inputText`。
- **Then**：
  - `llmFailureTimeoutMs` 内收到 ≥1 个任务相关事件；
  - 测试 JVM **无**未捕获致命异常；
  - JSON-RPC / SSE **无**连接级致命错误（单次业务终态失败允许）。
- **PASS** / **FAIL**：超时零事件、连接重置、客户端崩溃 → **FAIL**。

### C-09.*.B — 合法非成功终态（宽松主判据，F3）

- **When**：从 collector 取终态 `Task.status().state()`。
- **Then**：
  1. `state.isFinal() == true`；
  2. `state` **不在** testdata `disallowedTerminalStates` 内（默认仅含 `TASK_STATE_COMPLETED`）。
- **PASS**：如 `FAILED` / `CANCELED` / `INPUT_REQUIRED` 等 final 态。**FAIL**：`COMPLETED`、或超时仍 `WORKING`/`SUBMITTED`、或无终态。

> **`FAILED` 为期望常见态**，但不强制；`INPUT_REQUIRED` 在坏 LLM 场景下少见，若出现且满足 1+2 仍 **PASS**。

### C-09.*.C — 错误面可观测（宽松，F3）

- **When**：从终态 Task 按 §5 聚合错误文本；并取 `taskId`。
- **Then**（满足 **其一** 即可）：
  1. 聚合错误文本 trim 后 **非空**；或
  2. 可提取 **非空** `taskId`。
- **PASS** / **FAIL**：两者皆无。

### C-09.*.D — 终态观测日志（不改变主 verdict）

- **When**：B 已 PASS。
- **Then**：日志写入 `llm_unavailable_terminal_state=<STATE>`；若 `FAILED`，附加 `failure_text` 摘要（前 N 字符）。
- **用途**：区分连接拒绝、认证失败、超时等不同失败形态，**不**作为硬断言。

---

## 5. 错误文本提取规则

按顺序聚合（用于 C-09.*.C 与 C-09.*.D 日志）：

1. `status().message()` 的 `TextPart`；
2. 同 message 的 `metadata` 中 `a2a.error` / `a2a.error.code` / `a2a.error.message`（若 SDK 暴露）；
3. `artifacts` 内 `TextPart`。

与 C-06 §5.1 相同聚合顺序；**不**要求命中 `UPSTREAM_UNAVAILABLE` 子串。

---

## 6. 环境与部署

### 6.1 Managed 模式（LOCAL 默认，与 C-06/C-07 对齐）

`LlmUnavailableStackSupport` 托管拉起 mainplan，经 Spring 命令行参数注入 G3（覆盖 `application-local.yml` 中的正常 `LLM_*`）：

| 属性 | 值 |
|------|-----|
| `main-plan-agent.api-key` | `sit-invalid-key-for-c09` |
| `main-plan-agent.api-base` | `http://127.0.0.1:9/v1` |

**无需** `url-llm-down`、**无需** 13005、**无需** 测试侧 LLM key。

### 6.2 Remote 模式（SIT 可选）

当 `sut.agents.mainplan.url-llm-down` 非空时，与 C-06 remote 类似，连预部署实例而非本地起 jar。

**SIT**（`application-sit.yml`）：

```yaml
sut:
  agents:
    mainplan:
      url: http://7.209.189.82:13003          # C-06/C-07；C-09 不使用
      url-llm-down: http://7.209.189.82:13005  # C-09 remote 可选
```

| 键 | 必填 | 说明 |
|----|------|------|
| `sut.agents.mainplan.url-llm-down` | remote 时 **是** | 设则走 remote；不设则 managed G3 |
| `sut.agents.mainplan.url` | 否（对本用例） | C-06/C-07/B-03 |

### 6.3 服务端部署 — `13005` 专实例（remote / SIT 运维参考）

与 `13003` **同一 fat jar**，仅 **端口** 与 **LLM 环境变量** 不同。采用 **双保险**：

| 项 | 值 | 作用 |
|----|-----|------|
| `LLM_API_KEY` / `main-plan-agent.api-key` | `sit-invalid-key-for-c09`（固定占位，勿用真实 key） | 即使连上网关也认证失败 |
| `LLM_API_BASE` / `main-plan-agent.api-base` | `http://127.0.0.1:9/v1` | 在 **服务器本机** 不可达，连接快速失败 |

**启动示例**（在 `7.209.189.82` 上，路径按实际 jar 位置调整）：

```bash
export LLM_API_KEY=sit-invalid-key-for-c09
export LLM_API_BASE=http://127.0.0.1:9/v1

java -jar agent-travel-mainplan-a2a-0.2.0-SNAPSHOT.jar \
  --server.port=13005 \
  --main-plan-agent.api-key=sit-invalid-key-for-c09 \
  --main-plan-agent.api-base=http://127.0.0.1:9/v1
```

或使用 Spring 等价参数（与 `application.yaml` 占位符一致）：

```bash
java -jar agent-travel-mainplan-a2a-0.2.0-SNAPSHOT.jar \
  --server.port=13005 \
  --LLM_API_KEY=sit-invalid-key-for-c09 \
  --LLM_API_BASE=http://127.0.0.1:9/v1
```

**就绪探针**（跑 C-09 前人工或脚本执行）：

```bash
curl -s "http://7.209.189.82:13005/.well-known/agent.json" | head -c 200
```

应返回含 `main-plan-agent` 的 JSON；**不要求** LLM 可用。

**与 `13003` 的关系**：

| 端口 | LLM | 用途 |
|------|-----|------|
| 13003 | 正常 | C-06、C-07、B-03 等 |
| 13005 | 故意坏（G3） | **仅 C-09** |

> `13005` 的 checkpointer / Redis 配置可与 `13003` 相同或简化；本用例单轮「你好」不依赖多轮状态。

### 6.4 测试 JVM 凭据

| 变量 | 必填 | 说明 |
|------|------|------|
| `SIT_LLM_API_KEY` | **否** | 坏 LLM 在服务端；测试侧不注入 |

---

## 7. 测试数据

文件：`src/test/resources/testdata/component/boundary/c09-llm-unavailable.json`

```json
{
  "_doc": "C-09 LLM unavailable — managed G3 or remote url-llm-down",
  "inputText": "你好",
  "llmFailureTimeoutMs": 120000,
  "disallowedTerminalStates": ["TASK_STATE_COMPLETED"]
}
```

| 字段 | 用途 |
|------|------|
| `inputText` | 触发 LLM 调用的用户消息（默认「你好」，勿用长出差文案以免引入 trip 变量） |
| `llmFailureTimeoutMs` | 等待终态上限（含连接超时 + executor 失败路径） |
| `disallowedTerminalStates` | C-09.*.B：出现即 **FAIL**（默认禁止 `COMPLETED`） |

读取方式：文案与超时写在 `LlmUnavailableFlow` 常量；JSON 仅外置参考。**不**使用 `main` ScenarioData。

---

## 8. 框架落点

| 项 | C-09-Y（同步） | C-09-S（流式） |
|----|----------------|----------------|
| 测试类 | `LlmUnavailableSyncTest.java` | `LlmUnavailableStreamTest.java` |
| 路径 | `src/test/java/.../component/boundary/` | 同左 |
| 标签 | `@Tag("component")` **无** `@Tag("smoke")` | 同左 |
| 基类 | `BaseManagedStackTest` | 同左 |
| 栈 | managed G3 或 `remoteAgent(url-llm-down)` + `streaming(false)` | 同左 + `streaming(true)` |
| 收集 | `A2aEventCollector` + `sendMessage` | 同左 |
| 模式 | `LlmUnavailableSupport.isRemoteMode()`：`url-llm-down` ⇒ remote，否则 managed | 同左 |

**单测方法建议**：

```text
llmUnavailable_reachesNonSuccessTerminalState()
  → 步骤 2: 断言 C-09.*.A～D
  → 无后置健康探测
```

**实现检查清单**：

- [ ] 两测试类，`streaming` 显式 false / true
- [ ] 不在测试类内使用 `@EnabledIf` 按 profile 跳过
- [ ] managed：`LlmUnavailableStackSupport.applyBadLlm` 注入 G3 属性
- [ ] remote：读 `url-llm-down`，**禁止**回退到 `mainplan.url`
- [ ] C-09.*.B：`isFinal()` 且非 `disallowedTerminalStates`
- [ ] C-09.*.C：错误文本非空 **或** `taskId` 非空
- [ ] **无**后置「你好」探测（E1）
- [ ] 同步长阻塞路径：长超时场景下 `sendMessage` 宜放后台线程再 `awaitTerminalState`（同 C-07-Y 教训）
- [ ] 不进 `SmokeTestSuite`

---

## 9. 运行方式

```bash
# managed LOCAL（默认，G3 注入，无需 13005）
./mvnw -Dtest=LlmUnavailableSyncTest,LlmUnavailableStreamTest test

# remote SIT（url-llm-down 已配置且 13005 已启动）
export TEST_ENV=SIT
./mvnw -Dtest.env=SIT -Dtest=LlmUnavailableSyncTest,LlmUnavailableStreamTest test
```

> P2 边界用例：**不**加入 smoke 套件。

---

## 10. 覆盖特性追溯

| 特性 | 覆盖 |
|------|------|
| SIT「LLM 服务不可用 → FAILED + 清晰错误」 | C-09.*.B/C ✅（F3 宽松版） |
| 特性 3-1 同步 | C-09-Y ✅ |
| 特性 3-2 流式 | C-09-S ✅（双子场景扩展 SIT 矩阵仅标 3-1 的覆盖） |
| 特性 4-2 / 4-3 | ⚠️ 传输前置（双路径） |

---

## 11. 风险与备注

- **与 C-06/C-07 对称**：同为 `BaseManagedStackTest` + 单 mainplan；C-09 注入坏 LLM 而非正常 LLM，且无后置「你好」探测。
- **managed 优先**：LOCAL 不必运维 13005；SIT 可保留 `url-llm-down` 捷径。
- **F3 宽松判据**：不强制 `UPSTREAM_UNAVAILABLE`；若产品统一错误码后可收紧为 F1/F2 并修订 testdata。
- **G3 双保险**：无效 key + 本机不可达 base；避免 CI 依赖外网 LLM 网关仍返回 200 的偶发情况。
- **13005 误配真实 LLM**：若终态 `COMPLETED`，说明部署错误，非测试 flaky。
- **两测试类**：栈级 `streaming` 互斥，同 C-06/C-07。
- **运维**：`13005` 应长期常驻专用于 C-09；勿与 `13004`（B-04 in-memory）端口混淆。
- **进程存活**：无 E1/E2 探测时，以「请求有响应 + 栈 close 无异常」为弱保证；若需更强保证可后续扩展 Agent Card 探测（当前 **不在** v0 范围）。

---

## 12. 与 C-06 / C-07 对照

| 维度 | C-06 空消息 | C-07 超长 | C-09 LLM 不可用 |
|------|-------------|-----------|-----------------|
| SUT | mainplan + 正常 LLM | mainplan + 正常 LLM | mainplan + **G3 坏 LLM** |
| 配置 | `mainplan.url` 或 managed LLM | 同左 | managed G3 或 **`url-llm-down`** |
| 输入 | `""` | ≥5000 字 | testdata（默认「你好」） |
| 主终态 | 必须 `FAILED` | 任意 final | final 且 **非** `COMPLETED` |
| 错误断言 | 严格 `INVALID_INPUT` | 宽松 | **F3 最宽松** |
| 后置探测 | 「你好」`COMPLETED` | 「你好」`COMPLETED` | **无** |
| 测试侧 LLM key | 远端不需要 | 远端不需要 | 远端不需要 |

---

## 13. 需求决策记录（设计评审摘要）

| 决策项 | 选定 | 说明 |
|--------|------|------|
| 故障注入 | managed G3 + remote 13005 可选 | LOCAL managed；SIT 可 `url-llm-down` |
| 传输 | **同步 + 流式** | C-09-Y / C-09-S |
| 后置探测 | **E1 无** | 坏 LLM 栈不做 message 复测 |
| 错误严格度 | **F3 宽松** | 非 `COMPLETED` final + 错误或 taskId |
| 服务端 LLM | **G3** | 无效 key + `127.0.0.1:9` base |
| 用户输入 | **H3** | testdata `inputText`，默认「你好」 |
| 门禁 | 无 `@EnabledIf` | jar / remote 就绪即可 |
| 部署文档 | **L1 完整** | §6.3 remote `java -jar` 示例（可选） |
