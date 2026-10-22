# FEAT-058 ｜ 请教裁定：模型调用超时错误 details 四字段是否要透传 HTTP response body

- 提出：SIT ｜ 2026-10-22 ｜ 性质：契约边界裁定请求（非缺陷报告）
- 追加：2026-10-22 晚补 §二实证——HTTP wire 面完整打印，印证"metadata.numericCode"字段当前不存在
- 背景：SIT 执行 FEAT-058 用例首轮撞到判据层矛盾，回源文档后发现契约没有明文；需要开发侧裁定承载面，SIT 据此对齐判据，不替特性做决定。

## 事实（三方对照，均为原文或行号可核）

### 1. 契约层说了什么

**特性档 §2 MUST#12**（2026-10-20 版，!340 由 SHOULD 升为 MUST）：
> 各层超时失败产生的错误应携带三段阶段信息——连接建立前 / 首段数据到达前 / 首段数据到达后……三段口径**供调用方与重试策略程序消费**。

**L2 §2.3 L193**（关键，一字原文）：
> 错误载荷（**details，程序可消费**）：`phase`、`timeout_seconds`、`layer`、`stream`。消息模板渲染人读摘要；**details 供调用方与后续重试特性程序消费**。

**L2 §5.2** 独立一节「超时错误载荷（details）」给出四字段表格承诺。
**L2 §7.4 L644** 验收口径："UT-M07、UT-I03 验 details 四元组"。

**契约对承载面的明文**：无。L1/L2 都只说"程序消费"，不说消费面是什么——可以是异常对象上 `getDetails()`，也可以是 wire 面透传。

### 2. 实现层做了什么

| 层 | 行为 | 代码位置 |
|---|---|---|
| core 错误构造 | `details` 对象四字段齐备（phase/timeout_seconds/layer/stream + 分类/可重试标记） | `ModelTimeoutErrorFactory.java:78-89` |
| core 序列化 | `BaseError.toMap()` / `toJson()` 包含 details 对象 | `BaseError.java:104-114` |
| core 错误码 | `StatusCode.MODEL_CALL_TIMEOUT = 181008` | `StatusCode.java:443` |
| core 消息模板 | `"model call timeout at {phase}, effective timeout {timeout}s, reason: {error_msg}"` | 同上 |
| runtime adapter | `JiuwenCoreAgentHandler.toQueryResponse` 将 BaseError 简化为 `"ModelError: " + getMessage()` 文本塞入 `output` 字段 | arj `JiuwenCoreAgentHandler.java:695-722` |

## §二 ｜ HTTP wire 面完整打印（2026-10-22 ECS 实证，S1 connect-timeout 场景）

### 响应 headers（原样）

```
HTTP/1.1 200 
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
Content-Type: text/event-stream
Transfer-Encoding: chunked
Date: Thu, 22 Oct 2026 10:36:57 GMT
```

**自定义 headers：零**。

### SSE 流完整内容（5 条 data event，原样复制）

```
data: {"type":"model_retry","index":0,"payload":{"retry_count":1,"max_retries":2,"delay_ms":466,"delay_source":"exponential_backoff","message":"模型请求失败，正在进行第 1/2 次重试","exception_type":"java.net.http.HttpConnectTimeoutException"}}

data: {"type":"model_retry","index":1,"payload":{"retry_count":2,"max_retries":2,"delay_ms":911,"delay_source":"exponential_backoff","message":"模型请求失败，正在进行第 2/2 次重试","exception_type":"java.net.http.HttpConnectTimeoutException"}}

data: {"type":"model_retry","index":2,"payload":{"retry_count":1,"max_retries":2,"delay_ms":379,"delay_source":"exponential_backoff","message":"模型请求失败，正在进行第 1/2 次重试","exception_type":"java.net.http.HttpConnectTimeoutException"}}

data: {"type":"model_retry","index":3,"payload":{"retry_count":2,"max_retries":2,"delay_ms":956,"delay_source":"exponential_backoff","message":"模型请求失败，正在进行第 2/2 次重试","exception_type":"java.net.http.HttpConnectTimeoutException"}}

data: {"type":"answer","index":0,"payload":{"output":"ModelError: model call timeout at BEFORE_CONNECT, effective timeout 5.0s, reason: connect timeout after 5.0s","result_type":"error"}}
```

### 字段枚举（逐事件穷尽）

| event type | payload 字段全集 |
|---|---|
| `model_retry`（×4） | `retry_count`, `max_retries`, `delay_ms`, `delay_source`, `message`, `exception_type` |
| `answer`（×1） | `output`, `result_type` |

### 2026-10-22 晚补充观察：layer 其实"藏在 output.reason 后缀里"

同日跑 S2 场景（stub `header-wait-silent`、legacy `timeout=8s`），/v1 后缀修复后 output 实证：
```
"output": "ModelError: model call timeout at BEFORE_FIRST_SEGMENT, effective timeout 8.0s, reason: header_wait timeout after 8.0s"
```

对照 S1 场景的 output：
```
"output": "ModelError: model call timeout at BEFORE_CONNECT, effective timeout 5.0s, reason: connect timeout after 5.0s"
```

**reason 字符串的前缀即为 `layer` 字段值**（`connect` / `header_wait` / `stream_idle`，三值合同与 L2 §5.2 一致）。即 wire 面"实际能抽到三样"而非之前所述"两样"：

| 字段 | wire 可抽 | 抽取方式 |
|---|---|---|
| `phase` | ✅ | `output` regex "timeout at (BEFORE_CONNECT\|BEFORE_FIRST_SEGMENT\|AFTER_FIRST_SEGMENT)" |
| `timeout_seconds` | ✅ | `output` regex "effective timeout (\\d+\\.?\\d*)s" |
| `layer` | ✅ | `output` regex "reason: (connect\|header_wait\|stream_idle) timeout" |
| `stream` | ❌ | wire 面不可观察（UT 承接） |
| 错误码 181008 | ⚠️ 日志有 / HTTP body 无 | 本件 §请教问题 第 2 问即此 |
| retryable | ❌ wire 面无 | 本件 §请教问题 第 1 问 |

**纠正**：§二起始段"开发所述三样东西 vs wire 面实证"表中的"message / retryable 零出现 / status_code 零出现"仍然成立；本节补充说明是——"layer 字段"若允许从 output 文本 regex 抽，SIT 侧可实现三值合同验证，无需等待 details 对象透传到 wire。开发可裁定：是否接受此 regex 消费路径作为契约承诺的一部分（在 L2 §2.3 补一行注记即可），还是坚持要求 details 对象透传到 HTTP body（走 B 路线）。

### 开发所述三样东西 vs wire 面实证

| 开发所述字段 | wire 面出现？ | 位置 |
|---|---|---|
| 错误码（181008 / numericCode） | ❌ **零出现** | 整条流 grep 不到 `181008`、`numericCode`、`metadata`、`code`、`status_code` 任何一个 |
| 脱敏后 message | ⚠️ **部分近似** | 仅出现在 `answer.payload.output` 文本内，带 `ModelError:` 前缀和 `reason:` 后缀，不是独立 `message` 字段 |
| retryable 标记 | ❌ **零出现** | details 对象未透传；`model_retry` 事件表达"过程重试次数"但不表达"错误是否可重试" |

对照日志通道：SUT 日志 `[181008] model call timeout at BEFORE_CONNECT, effective timeout 5.0s, reason: connect timeout after 5.0s` 行存在——即 **181008 只在日志、不在 HTTP body**。

## 两种可能的解读（请裁定）

### 解读 A：现有实现已达契约

**依据**：契约说"details 供程序消费"，没规定承载面。details 对象在 BaseError 层已齐备，调用方以 Java 异常对象消费是满足契约的；wire 面给 phase + timeout_seconds + 消息文本 + 日志行已足够"可区分三类超时"（MUST#10）。runtime adapter 不透传 details 对象到 HTTP body 是**有意选择**，与 FEAT-057 的"子对象不穿透 HTTP body"通用规则一致。

**后果**：UT 层（UT-M07/UT-I03）承接 details 四元组验证，**SIT 侧 wire 面判据应该收窄**为：
- 硬断言：日志行 `[181008]` + 日志中的 phase 枚举 + output 文本 phase+timeout_seconds；
- UT 承接：layer 和 stream 两字段。

### 解读 B：details 应当透传到 HTTP body（含 metadata.numericCode / retryable）

**依据**：MUST#12 把阶段信息列为 **MUST**，上游 FEAT-064 的流式首段判定线以此为"MUST 级消费依赖"（特性档 §2 原文）；跨进程调用方（HTTP 调用方不是 Java 调用方）通过 wire 面消费也是合理场景；L2 §2.3 "程序可消费"若排除 HTTP 侧，表述需要收窄。

**后果**：runtime adapter 需补 details 透传（arj 侧 scope，可能是新增 answer payload 字段或新错误 chunk），**SIT 侧原判据正确**，等 runtime 补齐后重跑。

## 请教问题（已据 §二实证重排）

1. 开发口头所述"调用方拿到错误码 / 脱敏 message / retryable 三样"——指**同进程 Java 调用方**（catch BaseError 后调 getCode/getMessage/getDetails 消费）、**HTTP 响应消费方**（从 wire body 消费），还是**两种都要**？
2. 若"HTTP 消费方也要有"：§二实证显示当前 wire 整条流 5 条事件里**没有** `numericCode`、`metadata`、`retryable`、`status_code`、`code` 任何字段；answer.payload 只有 `output` + `result_type`，headers 也无自定义——这是**透传遗漏缺陷**还是 numericCode 在**另一条咱们没找到的事件通道**？若是后者请指路（事件 type 名 + payload 字段），SIT 补 fixture 解析即可。
3. 若"HTTP 消费方只承诺 output 文本"：请在 L2 §2.3 补一句"details 不穿透 HTTP response body，HTTP 调用方消费 phase + timeout_seconds 走 message 文本 regex 或日志"，SIT 据此对齐判据。
4. 独立问题（与本裁定无关）：下游 FEAT-064 对 details 的消费是在**同进程 Java 层**还是**跨进程 wire 面**？这个问题的答案会影响第 1 问的判定路径。

## 独立附带：另一个待开发裁定的点

同一次 SIT 执行还撞到 S2 场景的 HTTP 404 `/chat/completions`，不是契约问题，是 **demo stub mode env 没切对**导致 probe-agent 走到 OpenAI SDK 对未知端点的 fallback——怀疑是 `TimeoutDemoLauncher` fixture 没透传 `LLM_STUB_DEFAULT_MODE` env。本件由 SIT 自查修复，不进本裁定清单。
