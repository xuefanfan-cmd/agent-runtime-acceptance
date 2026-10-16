---
scope: v0911
deployable_units: [agent-runtime-ext-java, agent-runtime-java, edp-agent-java]
sut: concurrency-agent-runtime（单服务 SUT，端口 18210；验收 example concurrency-throttling-acceptance-demo，fork common 分支）
features: [DFX-006]
builds_on: [DFX-002, FEAT-001, FEAT-017, FEAT-022]
updated: 2026-10-10
---

# Agent Runtime 服务化外部请求过载保护机制（DFX-006）—— 测试计划

## 1. 测试目标

验证 agent-runtime 面向服务化外部执行请求的统一过载保护能力：在 DFX-002 已有的任务级并发控制基础上，新增**单实例 30 个执行轮次固定保护口径**、**后果落地释放**（permit 持有到终态/中断态事件被处理器消费后释放，L2 §3.1）、**事件处理过载保护**（含积压有界 30×1000、pending-release 归因细分、停摆保守拒绝、存量事件排空）、**三种稳定业务错误码**（`CONCURRENCY_LIMIT_REACHED` / `EVENT_QUEUE_OVERLOADED` / `EVENT_QUEUE_STATE_UNAVAILABLE`，判定顺序：停摆 > 额度 pending-release 细分，L2 §2.2.1）、**多入口一致性**（HTTP / Custom REST / agent-bus / 回调续传，前置准入交接 + 补偿释放）、**拒绝无副作用**（不创建 Task / 不创建 FAILED Task / 不创建业务事件 / 不创建 Session-Callback-Rail / 不调用 LLM/MCP/Sandbox/Remote A2A / 不泄露内部信息）、**TTL 兜底释放**（后果丢失 + 持久化失败路径，强制上限 `max(20m, 2×ttl)`，L2 §3.7/§4.4），以及**自动恢复与边界**（执行容量 + 事件积压 + 事件状态恢复 + 永久停摆不自愈边界 + ActiveTask 互证 + limit=-1/0 边界）。

测试分四层覆盖：
- **L1 模块单测**：停摆检测（checkEventSafety）、额度耗尽归因（classifyPoolExhaustion）、释放协调器、businessCode 生成、错误消息安全性。
- **L2 集成测试**：E2E 场景验证（SpringBootTest，复用 DFX-002 E2E 框架扩展）。
- **L3 验收测试**：黑盒验收（独立 SUT，复用 concurrency-throttling-acceptance-demo 扩展）。
- **L4 长稳压测**：24h 短链路 / 24h 长链路 / 事件压力 / 7 天回归（对应 FRS §11.3 后半部分 + §11.4）。

## 2. 范围与非范围

### 2.1 范围（DFX-006 §2 能力要求 MUST 项）

- **统一过载保护**（FR-01）：所有创建或续传 Agent 执行的入口（`SendMessage`、`SendStreamingMessage`、回调续传、agent-bus、Custom REST）必须在执行开始前遵循一致的过载保护规则；`GetTask`、`SubscribeToTask`、取消/中断、健康检查不受阻断。
- **Task 并发保护**（FR-02）：单实例最多同时承载 30 个 Agent 执行轮次；第 31 个请求快速拒绝，不在 Runtime 内等待。
- **事件积压有界性**（FR-03）：后果落地释放使主 deque 深度 ≤ 并发上限 × 子队列容量（SDK 默认 1000，即 30 × 1000 = 30,000 事件），不随累计请求数增长；额度耗尽且存在 pending-release 持有者时返回 `EVENT_QUEUE_OVERLOADED` 拒绝新增执行负载；处理器停摆（主 deque 非空 ∧ 心跳停滞 > 30s）时返回 `EVENT_QUEUE_STATE_UNAVAILABLE` 保守拒绝。
- **存量任务和事件保护**（FR-04）：拒绝新请求时，已准入 Task 继续执行，已接收事件继续处理并最终排空，不丢弃存量事件；已准入 Task 对下游系统的在途调用继续按既有协议完成或失败。
- **自动恢复**（FR-05）：执行负载和事件积压恢复到安全范围后，无需重启或人工操作自动恢复准入；事件状态恢复有效且安全后自动恢复。
- **执行容量完整释放**（FR-06）：Task 完成/失败/取消/`INPUT_REQUIRED`/入口异常后，本轮执行不再占用并发容量；容量释放不重复计算导致虚增；续传重新接受过载判断，拒绝续传不破坏原 Task。
- **结构化临时失败**（FR-07）：HTTP 返回 503 + 稳定 `error.data.businessCode`；非 HTTP 返回可区分于 Agent 业务失败、可重试的等价拒绝；拒绝信息不泄露内部容量/线程/地址/凭据/堆栈，不出现伪成功；多个保护条件同时存在时返回能稳定表达主要保护原因的业务错误。

### 2.2 非范围（DFX-006 §2 OUT 项 + FRS §3.3 边界）

- 请求排队等待（过载时直接拒绝，不排队）。
- 运行负载查询与监控平台（不新增 API，不建设外部采集/存储/展示/告警）。
- 通用熔断与资源治理（不建设 LLM/MCP/Redis/Sandbox/Remote A2A 通用熔断/重试/配额）。
- 动态策略与多租户配额（本期采用统一实例级规则）。
- Issue#196 根因修复（并行专项，不阻塞功能开发，阻塞最终长稳验收）。
- 事件保护内部实现口径（不规定具体队列/容量/阈值/锁/同步算法，由 L2 设计 + 目标环境压测确定）。
- **hosted/multi profile 不在本轮验收范围**（hosted 形态有新增装配变更——HostedRuntimeAutoConfiguration 新增共享 AdmissionReleaseCoordinator bean + destroyMethod；hosted 下停摆检测恒关（null bus）、TTL 退化为首个周期即释放（deque-drain 恒真）、TaskAdmissionService 对桥接可解析使 HTTP 面归因恢复。单实例 SUT 为有意裁剪）。

### 2.3 与 DFX-002 的关系

| DFX-002 已有能力 | DFX-006 复用 | DFX-006 新增 |
|---|---|---|
| `max-concurrent-tasks` 可配置（默认 -1） | 复用准入控制核心（tryAcquire/release/CAS） | 固定 30 保护口径 |
| 超限返回 HTTP 503 | 复用 503 拒绝外形 | 新增 `CONCURRENCY_LIMIT_REACHED` businessCode |
| 容量生命周期释放（完成/失败/取消/INPUT_REQUIRED） | 复用释放链路 | 新增入口异常释放 + 重复释放不虚增验证 |
| Custom REST 只读预检 | 复用预检机制 | 对齐 businessCode |
| 活跃任务查询 | 复用查询接口 | 不新增查询 API |
| hosted 多实例支持 | 复用 HostedTrackers | **有新增装配变更**（HostedRuntimeAutoConfiguration 新增共享 AdmissionReleaseCoordinator bean + destroyMethod；hosted 下停摆检测恒关、TTL 退化为首个周期即释放） |
| — | — | **事件处理过载检测与拒绝（全新）** |
| — | — | **`EVENT_QUEUE_OVERLOADED` / `EVENT_QUEUE_STATE_UNAVAILABLE`（全新）** |
| — | — | **agent-bus 入口准入保护（全新）** |
| — | — | **拒绝信息安全验证（全新）** |
| — | — | **拒绝无副作用验证（全新，含不创建 FAILED Task / 不创建业务事件 / 不创建 Session-Callback-Rail / 不调用 MCP-Sandbox-Remote A2A）** |
| — | — | **多个保护条件同时存在测试（全新）** |
| — | — | **在途下游调用继续完成验证（全新）** |
| — | — | **后果落地释放时点验证（全新，L2 §3.1）** |
| — | — | **pending-release 归因细分验证（全新，L2 §3.3）** |
| — | — | **TTL 兜底释放验证（全新，L2 §3.7/§4.4）** |
| — | — | **判定顺序验证：停摆 > 额度（全新，L2 §2.2.1）** |
| — | — | **永久停摆边界验证（全新，L2 §3.5）** |
| — | — | **ActiveTask 互证验证（全新，L2 §2.4）** |
| — | — | **limit=-1/0 边界验证（全新，L2 §6.3/§6.4）** |
| — | — | **bus 投影 retryable=true 验证（全新，L2 §4.6）** |
| — | — | **前置准入交接与补偿释放验证（全新，L2 §3.8）** |
| — | — | **续传退避抖动 ±20% 验证（全新，L2 §4.6）** |

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `FRS-Agent Runtime 服务化外部请求过载保护机制.md`（FRS-RT-STAB-0911） | §4 FR-01~FR-07 能力要求、§5 主成功旅程/分支旅程、§11.1~§11.4 验收标准——断言契约的唯一来源。 |
| `FEAT-XXX-runtime-service-request-overload-protection.md`（DFX-006 特性文档） | §2 能力要求表、§3 外部接口与入口、§5 行为语义与边界、§6 对下游设计约束——测试覆盖范围的唯一来源。 |
| `Feat-DFX-006-runtime-service-request-overload-protection.md`（L2 LLD） | §1 设计目标与边界、§3 核心运行模型（后果落地释放、TTL 兜底、停摆检测、前置准入）、§4 代码结构与类级设计（AdmissionReleaseCoordinator）、§6 配置项（admission-release-ttl）、§7 验收标准——实现机制与判定逻辑的唯一来源。 |
| `develop/02-features/DFX-002-runtime-concurrency-and-throttling.md` | DFX-002 已有能力清单、并发前提假设、场景——复用基础。 |
| `architecture/L2-Low-Level-Design/agent-runtime/Feat-DFX-002-runtime-concurrency-and-throttling.md` | L2 设计、实现仓定位——复用基础。 |
| 验收 example `concurrency-throttling-acceptance-demo/README.md` | 拓扑、确定性工具、用例映射、依赖版本、激活方式、源码签名。 |
| example `acceptance-tests/`、`concurrency-agent-runtime/src/test` 源码 + `run-acceptance.sh` | fixture 类、测试类与分组（smoke/pending）、运行命令。 |

## 4. 部署拓扑

```text
acceptance test driver (JDK HttpClient + ExecutorService 并发 worker)
  -> concurrency-agent-runtime (:18210)
       DeepAgent（确定性工具集 + mock LLM）
       任务级准入控制 TaskAdmissionGate（max-concurrent-tasks=30）
       准入释放协调器 AdmissionReleaseCoordinator（新增，L2 §4.1）
         ├── 后果落地释放（permit 持有 → 事件消费后释放）
         ├── 停摆检测（心跳 + 30s 阈值 + 主 deque 非空）
         ├── pending-release 归因细分（OVERLOADED / CONCURRENCY）
         └── TTL 兜底定时器（admission-release-ttl，强制上限 max(20m, 2×ttl)）
       前置准入服务 TaskAdmissionService（新增，L2 §4.1，供 bus/REST 桥接）
       多入口保护桥接（HTTP / Custom REST / agent-bus / 回调续传，前置准入交接 + 补偿释放）
       活跃任务查询 GET /v1/current_active_tasks
```

边界要求：

- test driver 只通过 SUT 公开面（`/a2a` JSON-RPC、`/health`、`/v1/current_active_tasks`、Custom REST path）观察系统，不读 SUT 内部状态、日志或存储。
- SUT 的 Agent 工具全部确定性（echo / slow / confirm / fail / delegate_subagent / **event_flood**），LLM 用 mock 模式（固定延迟 + 按输入 token 确定性路由到工具），隔离真实 LLM 波动，保证并发断言可重复。
- 长稳压测（L4）需要独立环境，持续运行 24h~7d，使用 JVM 监控（heap / direct memory / thread count）采集内存有界性证据。

## 5. 测试场景矩阵

### 5.0 用例族说明

| 用例族 | 前缀 | 定位 | 对应 FRS 验收 |
|---|---|---|---|
| **AC** | `AC-` | 主流程 + 分支异常验收（§11.1 + §11.2） | AC-01~AC-12 |
| **AC-MC** | `AC-MC-` | 多保护条件同时存在（FRS §8 + DFX-006 §5.1.7） | AC-04/AC-05 补充 |
| **SEC** | `SEC-` | 安全与可观测验收（§11.3 前半部分） | §11.3 安全 + 可观测 |
| **LT** | `LT-` | 长稳压测验收（§11.3 后半部分 + §11.4） | §11.3 长稳 + §11.4 交付物 |
| **U** | `U-` | 模块级单测（L1，不依赖 SUT 启动） | AC-04/AC-05/AC-11 底层 |
| **I** | `I-` | 集成测试（L2，SpringBootTest） | AC 交叉验证 |

### 5.1 L3 验收测试场景 — 主流程与分支异常（对应 §11.1 + §11.2）

#### 5.1.1 MaxThirty 组（max=30，对应 AC-01/AC-02/AC-05/AC-07/AC-08/AC-11）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 AC | 标签 |
|---|---|---|---|---|---|---|
| AC-01-S | 正常非流式执行透明 | max=30，负载安全 | 发送 `SendMessage`「echo:hello」 | `COMPLETED`，结果含 `hello` | AC-01 | smoke |
| AC-01-ST | 正常流式执行透明 | max=30，负载安全 | 发送 `SendStreamingMessage`「echo:stream」 | SSE 正常推送 + `COMPLETED` | AC-01 | smoke |
| AC-01-GT | 只读入口不受阻断 | max=30，有运行中 Task | 发送 `GetTask` / `SubscribeToTask` / 取消请求 | 正常返回，不受过载保护阻断 | AC-01 | pending |
| AC-02-R | 第 31 个请求被拒 | 并发 30 个 `slow:30000` | 发送第 31 个 `echo:test` | HTTP 503 + `CONCURRENCY_LIMIT_REACHED` | AC-02 | pending |
| AC-02-F | 拒绝不等待 | 同上 | 发送第 31 个请求并计时 | 响应时间 < 1s（快速拒绝，不在 Runtime 内等待） | AC-02 | pending |
| AC-02-Rcv | 容量释放后恢复 | AC-02-R 后结束 1 个 slow | 重试第 31 个请求 | 请求成功准入（轮询等待额度恢复） | AC-02 | pending |
| AC-05-NT | 拒绝不创建 Task | 并发 30 个 slow | 发送第 31 个请求后查询活跃任务 | 被拒绝请求不在活跃任务列表中 | AC-05 | pending |
| AC-05-NF | 拒绝不创建 FAILED Task | 并发 30 个 slow | 发送第 31 个请求后通过 `GetTask` 查询活跃任务列表 | 被拒绝请求不在活跃任务列表中（L3 公开面断言）；TaskStore 无 FAILED 记录由 L2 I-BC-05 进程内断言覆盖 | AC-05 | pending |
| AC-05-NE | 拒绝不创建 Task 业务事件 | 并发 30 个 slow | 发送第 31 个请求后查 Task 事件流 | 不产生任何 Task 状态变更/内容/产物事件 | AC-05 | pending |
| AC-05-LLM | 拒绝不调用 LLM | 并发 30 个 slow + Mock LLM 计数 | 发送第 31 个请求 | Mock LLM 调用计数不增加 | AC-05 | pending |
| AC-05-MCP | 拒绝不调用 MCP | 并发 30 个 slow + Mock MCP 计数 | 发送第 31 个请求 | Mock MCP 调用计数不增加 | AC-05 | pending |
| AC-05-SB | 拒绝不调用 Sandbox | 并发 30 个 slow + Mock Sandbox 计数 | 发送第 31 个请求 | Mock Sandbox 调用计数不增加 | AC-05 | pending |
| AC-05-RA | 拒绝不调用 Remote A2A | 并发 30 个 slow + Mock Remote A2A 计数 | 发送第 31 个请求 | Mock Remote A2A 调用计数不增加 | AC-05 | pending |
| AC-05-NS | 拒绝不创建 Session/Callback/Rail | 并发 30 个 slow | 发送第 31 个请求后通过 `GetTask`/`SubscribeToTask` 验证无新 Task 上下文 | 不创建新 Session/Callback/Rail/执行上下文（L3 公开面断言）；内部计数器由 L2 I-BC-08 进程内断言覆盖 | AC-05 | pending |
| AC-05-EO | 事件过载拒绝无副作用 | 注入慢消费使额度被 pending-release 持有者占满（tryAcquire 失败且 pending-release > 0） | 发送新请求后查活跃任务+Mock LLM/MCP 计数+Task 事件流 | 不创建 Task、不调用 LLM/MCP、不创建业务事件 | AC-05 | pending |
| AC-05-SU | 状态不可用拒绝无副作用 | 主 deque 非空 + 处理器心跳停滞 > 30s（停摆） | 发送新请求后查活跃任务+Mock LLM/MCP 计数+Task 事件流 | 不创建 Task、不调用 LLM/MCP、不创建业务事件 | AC-05 | pending |
| AC-07-C | 完成释放 | max=30 | 顺序 60 轮 `echo:round-i` | 全部 `COMPLETED`，每轮后 `currentActiveTasks` **轮询等待**归 0（释放时滞：permit 持有到终态事件消费后） | AC-07 | pending |
| AC-07-F | 失败释放 | max=30 | 发 `fail:boom` 后发 `echo:next` | `fail` 后额度恢复（轮询等待），`echo` 成功 | AC-07 | pending |
| AC-07-Cx | 取消释放 | max=30 | 发 `slow:5000` 后取消，再发 `echo:next` | 取消后额度恢复（轮询等待） | AC-07 | pending |
| AC-07-IR | INPUT_REQUIRED 释放 | max=30 | 发 `confirm:op` 触发 INPUT_REQUIRED 后发 `echo:next` | IR 后额度恢复（轮询等待后果落地释放） | AC-07 | pending |
| AC-07-IE | 入口异常释放 | max=30 | 发 `slow:5000` 占额度后模拟入口异常（连接断开/请求校验异常），再发 `echo:next` | 异常路径不造成容量泄漏，`echo` 成功准入 | AC-07 | pending |
| AC-07-LK | 多轮无泄漏 | max=30 | 顺序 60 轮混合（echo/fail/cancel/confirm） | 全部正常，结束时 `currentActiveTasks==0` | AC-07 | pending |
| AC-07-DP | 容量重复释放不虚增 | max=2 | 占满 2 个额度后通过异常路径触发释放，再重复释放同一轮次，然后尝试准入新请求 | 可用容量不超过 max（不因重复释放导致容量虚增） | AC-07 | pending |
| AC-08-R | 执行容量恢复 | 30 个 slow 占满 + 全部结束 | 发送新请求 | 无需重启自动恢复准入（轮询等待后果落地释放） | AC-08 | pending |
| AC-11-S | 错误消息安全 | 并发 30 个 slow | 发送第 31 个请求，检查 response body | 不含 `currentCount`/`maxConcurrent`/`queue`/`thread`/`model`/`credential`/`stack` | AC-11 | pending |
| AC-11-NS | 不出现伪成功 | 并发 30 个 slow | 发送第 31 个请求 | 不返回 `COMPLETED` + 空结果，返回 503 | AC-11 | pending |
| AC-01-M1 | limit=-1 监听器配对 | max=-1 | 发送 echo 请求并观察 `onAdmitted`/`onReleased` 配对触发 | 配对触发，`currentActiveTasks` 随执行结束回落，无条目累积；本特性机制不激活 | AC-01 | pending |
| AC-02-ZERO | limit=0 零容量边界 | max=0 | 发送执行请求 + 只读查询 | 执行请求全部 503 + `CONCURRENCY_LIMIT_REACHED`；GetTask/SubscribeToTask 正常 | AC-02 | pending |
| AC-07-CL | 后果落地释放时点 | max=1，发送 1 个 echo | L3 黑盒下观察窗口为毫秒级，**需慢消费注入手段或挪到 L2 用确定性钩子**；L2 版本（I-CL-01）可稳定断言 | 执行返回后 permit 仍持有（`currentActiveTasks` = 1）；终态事件消费后 permit 释放（`currentActiveTasks` = 0） | AC-07 | pending |
| AC-07-TTL | TTL 兜底释放 | max=1，配置 `admission-release-ttl=30s`（默认 10m，否则单用例等待过长）；后果事件丢失（处理器线程 take 后崩溃，事件已离队） | 等待 TTL 到期后查 `currentActiveTasks` | TTL 到期后容量回收（`currentActiveTasks` = 0）；命中"deque 排空 → 首个 TTL 检查释放"分支 | AC-07 | pending |
| AC-07-PE | 持久化失败路径释放 | max=1，配置 `admission-release-ttl=30s`；触发 `updateTaskStore` 抛异常 | 观察释放行为（InternalError 替换事件不触发 `onTaskFinalized`） | 释放依赖 TTL 回收（命中"deque 排空 → 首个 TTL 检查释放"分支），最终容量归零 | AC-07 | pending |
| AC-08-PS | 永久停摆边界 | 注入无超时永久阻塞（冻结处理器线程） | 持续提交请求 | 持续 `STATE_UNAVAILABLE` + 查询可用 + 内存有界（**不验证恢复**） | AC-08 | pending |
| AC-08-AT | ActiveTask 互证 | 过载期（30 个 slow 占满） | 查询 `/v1/current_active_tasks` 并发新请求 | 读数 ≈ 30 与 503 拒绝同时成立；恢复期读数随后果排空回落 | AC-08/AC-01 | pending |

#### 5.1.2 EventOverload 组（max=30 + event_flood 工具，对应 AC-03/AC-04/AC-06/AC-08）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 AC | 标签 |
|---|---|---|---|---|---|---|
| AC-03-B | 事件积压有界 | 注入慢消费使后果落地释放生效 + 持续发执行请求 | 监控 JVM heap 使用（外部 GC/JMX）；`current_active_tasks` 读数 ≤ 30 | 内存存在上界，不随请求数持续增长（主 deque 深度 ≤30×1000 的结构性上界由 L2 I-EO-04 进程内断言覆盖） | AC-03 | pending |
| AC-03-R | 事件过载前拒绝 | 注入慢消费使额度被 pending-release 持有者占满 | 发送新执行请求 | 额度耗尽且 pending-release > 0 时返回 `EVENT_QUEUE_OVERLOADED` | AC-03 | pending |
| AC-04-EO | 事件过载错误码 | 注入慢消费使额度被 pending-release 持有者占满（tryAcquire 失败且 pending-release > 0） | 发送新执行请求 | HTTP 503 + `EVENT_QUEUE_OVERLOADED` | AC-04 | pending |
| AC-04-SU | 事件状态不可用错误码 | 主 deque 非空 + 处理器心跳停滞 > 30s | 发送新执行请求 | HTTP 503 + `EVENT_QUEUE_STATE_UNAVAILABLE` | AC-04 | pending |
| AC-04-CL | 三种错误码可区分 | 分别触发三种过载条件 | 检查三组响应的 `businessCode` | 三种 businessCode 互不相同且稳定 | AC-04 | pending |
| AC-04-PR | pending-release 归因细分 | 注入慢消费使部分 permit 为 pending-release 状态，全部额度占满 | 发送新执行请求 | 返回 `EVENT_QUEUE_OVERLOADED`（而非 `CONCURRENCY_LIMIT_REACHED`，因存在 pending-release 持有者） | AC-04 | pending |
| AC-06-T | 存量 Task 继续执行 | 事件过载 + 有运行中 Task | 观察 Task 状态 | 存量 Task 继续执行至终态 | AC-06 | pending |
| AC-06-IC | 在途下游调用继续完成 | 事件过载 + 有运行中 Task 正在调用 LLM/MCP | 观察在途调用结果 | 已准入 Task 对 LLM/MCP/Sandbox/Remote A2A 的在途调用继续按既有协议完成或失败 | AC-06 | pending |
| AC-06-E | 存量事件排空 | 事件过载 + 拒绝新请求 | 等待并观察事件积压量 | 积压量递减并最终排空 | AC-06 | pending |
| AC-06-ND | 事件不丢弃 | 事件过载期间 | 检查已接收事件 | 全部事件被处理，无静默丢弃 | AC-06 | pending |
| AC-08-E | 事件恢复后准入 | 后果排空 → pending-release 清零 → 额度恢复 | 发送新执行请求 | 请求成功准入 | AC-08 | pending |
| AC-08-SU | 事件状态恢复后准入 | 处理器恢复消费 → 心跳刷新 → 停摆条件解除（deque 排空或心跳 <30s） | 发送新执行请求 | 请求成功准入，无需重启或人工操作 | AC-08 | pending |
| AC-08-GT | 事件过载期间 GetTask 可用 | 事件过载 + 有存量 Task | 发送 GetTask | 正常返回，不受阻断 | AC-08/AC-01 | pending |
| AC-08-Cx | 事件过载期间取消可用 | 事件过载 + 有存量 Task | 发送取消请求 | Task 正常取消 | AC-08/AC-01 | pending |

#### 5.1.3 MultiEntryConsistency 组（对应 AC-10/AC-12）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 AC | 标签 |
|---|---|---|---|---|---|---|
| AC-10-HT | HTTP SendMessage 拒绝 | max 已满 | 通过 `SendMessage` 发送 | 503 + `CONCURRENCY_LIMIT_REACHED` | AC-10 | pending |
| AC-10-HS | HTTP SendStreamingMessage 拒绝 | max 已满 | 通过 `SendStreamingMessage` 发送 | 503 + `CONCURRENCY_LIMIT_REACHED`（SSE 建立前返回） | AC-10 | pending |
| AC-10-CR | Custom REST 拒绝 | max 已满 | 通过 Custom REST path 发送 | 503 + `CONCURRENCY_LIMIT_REACHED` | AC-10 | pending |
| AC-10-CR-SU | Custom REST 停摆归因 | 主 deque 非空 + 心跳停滞 >30s | 通过 Custom REST path 发送 | 503 + `EVENT_QUEUE_STATE_UNAVAILABLE`（停摆归因，非额度拒绝） | AC-10 | pending |
| AC-10-BU | agent-bus 拒绝 | max 已满 | 通过 agent-bus 触发执行 | 可区分、可重试的等价临时拒绝（bus 投影 `retryable=true`，增补 `businessCode`） | AC-10 | pending |
| AC-10-BU-SU | agent-bus 停摆归因 | 主 deque 非空 + 心跳停滞 >30s | 通过 agent-bus 触发执行 | bus 投影含 `EVENT_QUEUE_STATE_UNAVAILABLE`（停摆归因，非额度拒绝） | AC-10 | pending |
| AC-10-DF | 非HTTP拒绝可区分于Agent业务失败 | max 已满 | 分别触发 agent-bus 过载拒绝和 Agent 业务失败，对比结果 | 非 HTTP 拒绝结果可区分于 Agent 业务失败（不同错误标识/类型），且语义为临时可重试 | AC-10 | pending |
| AC-10-CB | 回调续传拒绝 | max 已满 + 有 INPUT_REQUIRED Task | 通过回调触发续传 | 临时拒绝，原 Task 状态不变 | AC-10 | pending |
| AC-12-NB | 所有入口无旁路 | max 已满 | 分别从 HTTP/REST/bus/回调 发送 | 全部被拒，无执行旁路 | AC-12 | pending |
| AC-12-CM | 前置准入交接与补偿释放 | max=1，桥接前置准入后同步失败（执行器尚未采纳） | 验证原子 remove + 补偿释放 | 容量不泄漏，补偿释放后可用容量 = max | AC-07/AC-12 | pending |

#### 5.1.4 MultiCondition 组（对应 FRS §8 + DFX-006 §5.1.7）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 AC | 标签 |
|---|---|---|---|---|---|---|
| AC-MC-01 | 多保护条件同时存在 | 并发已满（30/30）+ pending-release > 0（部分 permit 为 pending-release 状态） | 发送新执行请求 | 按判定顺序取主因：停摆优先（`STATE_UNAVAILABLE`）→ pending-release 细分（`OVERLOADED`）→ 纯并发（`CONCURRENCY`） | AC-04/AC-05 | pending |
| AC-MC-02 | 停摆+额度同时满足 | 处理器停摆（心跳 >30s + deque 非空）+ 额度已满 | 发送新执行请求 | 返回 `STATE_UNAVAILABLE`（停摆优先于额度） | AC-04/AC-05 | pending |

#### 5.1.5 ResumeOverload 组（max=1，对应 AC-09）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 AC | 标签 |
|---|---|---|---|---|---|---|
| AC-09-NR | 正常续传重新准入 | max=1，Task A INPUT_REQUIRED，负载安全 | 续传 Task A | 成功，新执行轮次计入负载 | AC-09 | pending |
| AC-09-RR | 过载续传被拒 | max=1，Task A INPUT_REQUIRED，Task B 占额度 | 续传 Task A | 临时拒绝，不创建新执行 | AC-09 | pending |
| AC-09-NT | 拒绝续传不破坏原 Task | 同上 | 续传后查询 Task A 状态/上下文 | 原 Task 状态不变，上下文保留 | AC-09 | pending |
| AC-09-NE | 拒绝续传不写新事件 | 同上 | 续传后查询 Task A 事件 | 无新业务事件写入 | AC-09 | pending |
| AC-09-Rcv | 续传释放后可重试 | AC-09-RR 后 Task B 完成 | 重试续传 Task A | 成功准入 | AC-09 | pending |
| AC-09-OL | 同 Task 轮次重叠 FIFO 释放 | max=1，Task A INPUT_REQUIRED，续传在上一轮后果落地前准入 | 观察释放顺序 | 上一轮后果先落地 → 先释放上一轮 permit；新一轮 permit 不受影响 | AC-09 | pending |
| AC-09-BF | bus/REST 续传不被转写 FAILED | max=1，Task A INPUT_REQUIRED，经 bus/自定义 REST 续传且过载 | 续传后查询 Task A 状态 | 原 Task 保持 INPUT_REQUIRED 可续传，不被 SDK 转写 FAILED | AC-09 | pending |

#### 5.1.6 已有 DFX-002 验收测试（回归基线）

| 已有用例 | 场景 | max | 状态 |
|---|---|---|---|
| smoke-1~smoke-4 | echo/slow/confirm/fail 基础链路 | 2 | 回归基线 |
| F-10 | 并发达上限后新请求被拒 | 2 | 回归基线 |
| F-11 | max=-1 不限额 | -1 | 回归基线 |
| F-20/F-21/F-22/F-23/F-25 | 额度释放链路 | 1/2 | 回归基线 |
| F-40/F-42/F-50 | 快照/隔离 | 2/8 | 回归基线 |
| T-01/D-2/P-01 | 线程安全/一致性/压测 | 2/8 | 回归基线 |

### 5.2 L3 验收测试场景 — 安全与可观测（对应 §11.3 前半部分）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 对应 §11.3 |
|---|---|---|---|---|---|
| SEC-01 | 上下文不串用 | 不同 conversation_id 并发请求 + 过载触发 | 检查各 Task 上下文 | 不同用户/会话/Task 上下文不因拒绝/重试/恢复串用 | §11.3 第 1 条 |
| SEC-02 | 拒绝可关联诊断 | 触发过载拒绝 | 检查响应中的请求标识/Task 标识 | 准入/拒绝/执行/事件可通过既有标识关联 | §11.3 第 2 条 |
| SEC-03 | 三种拒绝原因稳定区分 | 分别触发三种过载条件 | 对比三组响应 `businessCode` | 三类拒绝原因可稳定区分 | §11.3 第 3 条 |
| SEC-04 | 诊断信息不泄露 | 触发三类拒绝 | 检查响应 body 和日志 | 不包含凭据/内部地址/容量明细/异常堆栈 | §11.3 第 3 条 |

### 5.3 L3 验收测试场景 — 长稳压测（对应 §11.3 后半部分 + §11.4）

| ID | 场景 | 条件 | 持续时间 | 预期结果 | 对应 §11.3/§11.4 |
|---|---|---|---|---|---|
| LT-01 | 短链路 24h 长稳 | EDPA `/a2a`、Mock LLM、每请求固定 5 轮、20 并发、max=30 | 24h | 正常请求体验 + 容量闭环，无容量泄漏，内存有界；`current_active_tasks` 归零（公开面）；租约表/permit 计数由 JMX 或测试观测端点采集（见 §9.2） | §11.3 第 4 条 |
| LT-02 | 长链路 24h 长稳 | 超过 5 轮的长链路、20 并发、max=30 | 24h | 长任务不突破并发上限，内存有界；`current_active_tasks` 归零；租约表/permit 计数由 JMX 或测试观测端点采集（见 §9.2） | §11.3 第 5 条 |
| LT-03 | 事件压力验证 | 人工制造事件处理压力、max=30 | 至排空 + 恢复 | 事件相关内存有界、存量事件排空、自动恢复；终态队列清理、子队列回收、租约表 ≤ 上限（见 §9.2 采集手段） | §11.3 第 6 条 |
| LT-04 | 7 天压力回归 | 持续压力、max=30 | 7 天 | 保护能力自身不新增无界对象/线程/事件积压；租约表 ≤ 上限、permit 计数归零、子队列回收（见 §9.2 采集手段） | §11.3 第 7 条 |

### 5.4 L1 模块单测场景（新增）

#### 5.4.1 事件过载检测器单测

事件过载检测由两个独立机制组成（AdmissionReleaseCoordinator）：
- **停摆检测**（`checkEventSafety()`）：主 deque 非空 ∧ 心跳停滞 >30s → `EVENT_QUEUE_STATE_UNAVAILABLE`；deque 为空或心跳新鲜即返回安全。
- **额度耗尽归因**（`classifyPoolExhaustion()`）：仅在 `tryAcquire` 失败后按 `pendingReleaseCount()>0` 归因——存在 pending-release 持有者 → `EVENT_QUEUE_OVERLOADED`；全部 RUNNING → `CONCURRENCY_LIMIT_REACHED`。

| ID | 场景 | 前置条件 | 操作 | 预期结果 | 对应 FR |
|---|---|---|---|---|---|
| U-EO-01 | 停摆检测-心跳新鲜 | 心跳新鲜（<30s）+ deque 非空 | 调用 `checkEventSafety()` | 返回安全（不触发停摆），允许继续准入检查 | FR-03 |
| U-EO-02 | 停摆检测-心跳停滞 | 心跳停滞 >30s + deque 非空 | 调用 `checkEventSafety()` | 返回 `EVENT_QUEUE_STATE_UNAVAILABLE`，保守拒绝 | FR-03 |
| U-EO-03 | 额度归因-pending-release | `tryAcquire` 失败 + `pendingReleaseCount()>0` | 调用 `classifyPoolExhaustion()` | 返回 `EVENT_QUEUE_OVERLOADED` | FR-03 |
| U-EO-04 | 额度归因-纯并发 | `tryAcquire` 失败 + `pendingReleaseCount()==0`（全部 RUNNING） | 调用 `classifyPoolExhaustion()` | 返回 `CONCURRENCY_LIMIT_REACHED` | FR-03 |
| U-EO-05 | 并发安全 | 多线程同时检测 | 并发调用检查 | 无竞态，结果一致 | FR-03 |
| U-EO-06 | 停摆阈值 30s 常量 | 构造心跳停滞场景 | 分别测试 <30s / >30s / deque 为空 | <30s 不判定停摆；>30s 且 deque 非空判定停摆；deque 为空不判定停摆 | FR-03 |

#### 5.4.2 businessCode 单测

| ID | 场景 | 前置条件 | 操作 | 预期结果 | 对应 FR |
|---|---|---|---|---|---|
| U-BC-01 | 并发拒绝 businessCode | max=2，已有 2 个执行 | tryAcquire 失败 | 拒绝结果含 `CONCURRENCY_LIMIT_REACHED` | FR-07 |
| U-BC-02 | 事件过载 businessCode | tryAcquire 失败 + pendingReleaseCount() > 0 | 调用 classifyPoolExhaustion() | 拒绝结果含 `EVENT_QUEUE_OVERLOADED` | FR-07 |
| U-BC-03 | 状态不可用 businessCode | 主 deque 非空 + 心跳停滞 > 30s（停摆） | 调用 checkEventSafety() | 拒绝结果含 `EVENT_QUEUE_STATE_UNAVAILABLE` | FR-07 |
| U-BC-04 | 错误消息不泄露 | 任意拒绝场景 | 检查错误消息 | 不含任务数/队列容量/线程/地址/凭据/堆栈 | FR-07 |
| U-BC-05 | 多保护条件 businessCode | 并发已满 + 事件过载 | 检查拒绝结果 | 返回能稳定表达主要保护原因的 businessCode | FR-07 |
| U-BC-06 | error.details → error.data 序列化 | 构造含 businessCode 的 503 响应 + 非准入错误响应 | 检查 HTTP 响应体 | 503 体呈现 `error.data.businessCode`（非 `error.details`）；非准入错误路径响应体不受后处理影响（仅含 businessCode 键时注入） | FR-07 |

#### 5.4.3 释放协调器单测

| ID | 场景 | 前置条件 | 操作 | 预期结果 | 对应 FR |
|---|---|---|---|---|---|
| U-CL-01 | TTL 强制上限恒等派生 | 设置不同 `admission-release-ttl` 值 | 检查强制上限计算 | 强制上限 = `max(20m, 2×ttl)`，不可独立配置（如 ttl=10m → 20m，ttl=30m → 60m） | FR-06 |
| U-CL-02 | 租约 CAS 恰好一次释放 | 多线程并发调用 `consequenceLanded` + TTL 释放同一租约 | 验证 gate.release 调用次数 | CAS 保证恰好一次释放，gate.release 恰好调用一次 | FR-06 |
| U-CL-03 | ttl≤0 禁用边界 | `admission-release-ttl` 设为 ≤0 | 检查 TTL 与 force cap 行为 | ≤0 同时禁用 TTL 与 force cap，后果丢失不回收（声明边界，L2 §4.4/§6.2） | FR-06 |

### 5.5 L2 集成测试场景（新增，SpringBootTest）

| ID | 场景 | 前置条件 | 操作 | 预期结果 | 对应 AC |
|---|---|---|---|---|---|
| I-BC-01 | 并发拒绝 businessCode | max=1，已有 1 个 slow | 发送第 2 个请求 | 503 + `CONCURRENCY_LIMIT_REACHED` | AC-05 |
| I-BC-02 | 拒绝不创建 Task | max=1，已有 1 个 slow | 发送第 2 个请求后查活跃任务 | 被拒绝请求不在列表中 | AC-05 |
| I-BC-03 | 拒绝不调用 LLM | max=1 + Mock LLM | 发送第 2 个请求 | LLM 调用计数不增加 | AC-05 |
| I-BC-04 | 拒绝消息不泄露 | max=1，已有 1 个 slow | 发送第 2 个请求，检查 body | 不含当前任务数/队列容量/线程/模型地址 | AC-11 |
| I-BC-05 | 拒绝不创建 FAILED Task | max=1，已有 1 个 slow | 发送第 2 个请求后查 TaskStore 全部 Task | 不存在被拒绝请求对应的 Task 记录（含 FAILED） | AC-05 |
| I-BC-06 | 拒绝不创建业务事件 | max=1，已有 1 个 slow | 发送第 2 个请求后查 Task 事件流 | 无新 Task 状态变更/内容/产物事件 | AC-05 |
| I-BC-07 | 拒绝不调用 MCP/Sandbox | max=1 + Mock MCP/Sandbox | 发送第 2 个请求 | MCP/Sandbox 调用计数不增加 | AC-05 |
| I-BC-08 | 拒绝不创建 Session/Callback/Rail | max=1，已有 1 个 slow | 发送第 2 个请求后检查 Session/Callback/Rail 计数 | 不创建新 Session/Callback/Rail | AC-05 |
| I-EO-01 | 事件过载错误码 | 注入慢消费使额度被 pending-release 持有者占满（tryAcquire 失败且 pending-release > 0） | 发送新执行请求 | 503 + `EVENT_QUEUE_OVERLOADED` | AC-04 |
| I-EO-02 | 状态不可用错误码 | 主 deque 非空 + 处理器心跳停滞 > 30s（停摆） | 发送新执行请求 | 503 + `EVENT_QUEUE_STATE_UNAVAILABLE` | AC-04 |
| I-EO-03 | 过载时存量 Task 继续 | 有运行中 Task + 事件过载 | 观察 Task 状态 | 存量 Task 继续执行至终态 | AC-06 |
| I-EO-04 | 过载时存量事件排空 | 有积压事件 + 拒绝新请求 | 等待并观察 | 积压事件递减并排空 | AC-06 |
| I-EO-05 | 事件回落后恢复 | 后果排空 → pending-release 清零 → 额度恢复 | 发送新请求 | 请求成功准入 | AC-08 |
| I-EO-06 | 事件状态恢复后恢复 | 处理器恢复消费 → 心跳刷新 → 停摆条件解除 | 发送新请求 | 请求成功准入 | AC-08 |
| I-EO-07 | 过载拒绝不创建 Task | 事件过载 | 发送新请求后查活跃任务 | 不在列表中 | AC-05 |
| I-EO-08 | 过载时在途调用继续 | 有运行中 Task 正在调用 LLM + 事件过载 | 观察在途 LLM 调用结果 | 在途调用按既有协议完成或失败 | AC-06 |
| I-ME-01 | Custom REST 拒绝 | max=1，已有 1 个执行 | Custom REST 发送 | 503 + `CONCURRENCY_LIMIT_REACHED` | AC-10 |
| I-ME-02 | agent-bus 拒绝 | max=1，已有 1 个执行 | agent-bus 触发 | 可区分可重试等价拒绝 | AC-10/12 |
| I-ME-03 | 非HTTP拒绝可区分于业务失败 | max=1，已有 1 个执行 | 分别触发 agent-bus 过载拒绝和 Agent 业务失败 | 两种结果可区分（不同错误标识/类型） | AC-10 |
| I-ME-04 | 回调续传拒绝 | max=1 + INPUT_REQUIRED Task | 回调触发续传 | 临时拒绝，原 Task 不变 | AC-10/12 |
| I-MC-01 | 多保护条件同时存在 | 并发已满 + pending-release > 0 | 发送新执行请求 | 返回能稳定表达主要保护原因的 businessCode | AC-04/05 |
| I-RT-01 | 过载续传不破坏原 Task | max=1，IR + 占额度 | 续传 | 临时拒绝，原 Task 不变 | AC-09 |
| I-RT-02 | 过载续传不写新事件 | 同上 | 续传后查 Task 事件 | 无新业务事件 | AC-05/09 |
| I-RT-03 | 正常续传重新准入 | max=1，IR + 负载安全 | 续传 | 成功，计入负载 | AC-09 |
| I-DP-01 | 容量重复释放不虚增 | max=2，占满后异常路径释放 + 重复释放 | 尝试准入新请求 | 可用容量不超过 max | AC-07 |
| I-CL-01 | 后果落地释放时点 | max=1，发送 echo | 执行返回后查 `currentActiveTasks`；终态事件消费后再查 | 执行返回后 permit 持有（计数=1）；消费后释放（计数=0） | AC-07 |
| I-CL-02 | TTL 兜底释放 | max=1，后果事件丢失 | 等待 TTL 到期后查容量 | TTL 到期后容量回收，归零 | AC-07 |
| I-EO-09 | pending-release 归因细分 | 注入慢消费使部分 permit 为 pending-release，全部占满 | 发送新请求 | 返回 `EVENT_QUEUE_OVERLOADED`（非 `CONCURRENCY_LIMIT_REACHED`） | AC-04 |
| I-EO-10 | 永久停摆边界 | 注入无超时永久阻塞 | 持续提交请求 + 查询 | 持续 `STATE_UNAVAILABLE` + 查询可用 + 内存有界（不验证恢复） | AC-08 |
| I-RT-04 | 同 Task 轮次重叠 FIFO | max=1，IR 续传在上一轮后果落地前准入 | 观察释放顺序 | FIFO：上一轮先落地先释放，新一轮不受影响 | AC-09 |
| I-RT-05 | 续传退避抖动 ±20% | max=1，过载续传被拒多次 | 记录退避间隔 | 退避序列有 ±20% 随机抖动（非固定值） | AC-09 |
| I-BC-09 | limit=-1 监听器配对 | max=-1 | 发送 echo 并观察监听器 | `onAdmitted`/`onReleased` 配对触发，`currentActiveTasks` 随执行结束回落 | AC-01 |

## 6. Test Agent 与 Fixture

本 demo 不使用真实 LLM：SUT 的 DeepAgent 装配确定性工具，每个工具对应一个明确触发 token，mock LLM 按输入 token 确定性选择工具（`temperature=0`）。

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `echo` | 确定性工具（DFX-002 已有） | 触发 token `echo:`，立即返回原文；验证正常准入与额度释放。 |
| `slow_task` | 确定性工具（DFX-002 已有） | 触发 token `slow:`，sleep 可配置时长后返回；验证占用额度、超限 503。 |
| `confirm_action` | 确定性工具（DFX-002 已有） | 触发 token `confirm:`，触发 INPUT_REQUIRED 中断；验证续传链路。 |
| `fail_task` | 确定性工具（DFX-002 已有） | 触发 token `fail:`，抛 RuntimeException；验证失败释放。 |
| `delegate_subagent` | 确定性工具（DFX-002 已有） | 触发 token `delegate:`，触发 subagent_wait 中断；验证远端额度链路。 |
| **`event_flood`** | 确定性工具（**DFX-006 新增**） | 触发 token `event_flood:`，产生大量状态/内容/产物事件使事件处理积压；验证事件过载检测、有界性、排空与恢复。 |
| `ConcurrencyDemoAgentFactory` | Agent 工厂（DFX-002 已有） | per-Task 创建 DeepAgent（`create()` 用 ReentrantLock 串行化）。 |
| `SutStack` | Fixture（DFX-002 已有） | 本地拉起/停止 SUT jar，支持传 `maxConcurrentTasks` 参数。 |
| `A2aServiceClient` | Fixture（DFX-002 已有） | A2A 驱动 blocking/streaming/查询。 |
| `ConcurrentDriver` | Fixture（DFX-002 已有） | 并发 worker，驱动多路并发请求。 |
| **`businessCode` 解析** | Fixture 扩展（**新增**） | 在 `A2aServiceClient` 或测试中新增 `error.data.businessCode` 解析。 |
| **Mock MCP/Sandbox/Remote A2A** | Fixture 扩展（**新增**） | 支持 MCP/Sandbox/Remote A2A 调用计数，验证拒绝时不调用下游系统。 |

## 7. 关键链路断言

### 7.1 主流程与分支异常（§11.1 + §11.2）

- 执行类请求在执行前接受一致的过载判断；正常负载下透明准入（FR-01 / AC-01）。
- 单实例 30 个执行轮次已满时，第 31 个请求快速返回 503 + `CONCURRENCY_LIMIT_REACHED`，不等待、不创建 Task、不创建 FAILED Task、不创建业务事件、不创建 Session/Callback/Rail、不调用 LLM/MCP/Sandbox/Remote A2A（FR-02 / FR-07 / AC-02 / AC-05）。
- 三类过载拒绝（并发已满、事件过载、状态不可用）均不创建执行副作用（AC-05-NT/NF/NE/LLM/MCP/SB/RA/NS/EO/SU）。
- 事件处理停摆（主 deque 非空 + 心跳 >30s）时返回 503 + `EVENT_QUEUE_STATE_UNAVAILABLE`；额度耗尽且 pending-release > 0 时返回 503 + `EVENT_QUEUE_OVERLOADED`；三种错误码可稳定区分（FR-03 / FR-07 / AC-04）。
- 多个保护条件同时存在时返回能稳定表达主要保护原因的业务错误（FR-07 / AC-MC-01）。
- 拒绝新请求时，已准入 Task 继续执行，已接收事件继续处理并最终排空，不丢弃；已准入 Task 对下游系统的在途调用继续按既有协议完成或失败（FR-04 / AC-06）。
- 执行容量和事件积压恢复到安全范围后，无需重启自动恢复准入；事件状态恢复有效且安全后自动恢复（FR-05 / AC-08）。
- Task 完成/失败/取消/INPUT_REQUIRED/入口异常后，本轮执行不再占用并发容量；容量释放不重复计算导致虚增；续传重新准入，拒绝续传不破坏原 Task（FR-06 / AC-07 / AC-09）。
- HTTP 与非 HTTP 入口遵循同一承载规则，不存在绕过保护启动执行的路径；非 HTTP 拒绝可区分于 Agent 业务失败（FR-01 / FR-07 / AC-10 / AC-12）。
- 拒绝响应不泄露内部容量/线程/地址/凭据/堆栈；不出现"已完成但结果为空"的伪成功（FR-07 / AC-11）。
- 执行容量在终态/中断态事件被处理器消费后才释放（后果落地释放），执行返回后 permit 仍持有，事件消费后释放（FR-06 / AC-07-CL）。
- 后果事件丢失时由 TTL 兜底回收容量；持久化失败路径（InternalError 替换事件不触发回调）也依赖 TTL 回收；TTL 强制上限 `max(20m, 2×ttl)` 无条件释放（FR-06 / AC-07-TTL/PE）。
- 额度耗尽时 pending-release 持有者存在则归因为 `EVENT_QUEUE_OVERLOADED`，全部 RUNNING 则 `CONCURRENCY_LIMIT_REACHED`（FR-03 / AC-04-PR）。
- 判定顺序：停摆（`STATE_UNAVAILABLE`）优先于额度（pending-release 细分）（FR-07 / AC-MC-01/02）。
- 处理器永久停摆时持续 `STATE_UNAVAILABLE` + 查询可用 + 内存有界，不自愈（FR-05 / AC-08-PS）。
- 过载期 `current_active_tasks` 读数 ≈ 上限与 503 拒绝同时成立；恢复期读数回落（FR-01 / AC-08-AT）。
- limit=-1 时监听器配对保持，本特性机制不激活；limit=0 时全部请求以 `CONCURRENCY_LIMIT_REACHED` 拒绝，只读入口可用（FR-02 / AC-01-M1/AC-02-ZERO）。
- bus/REST 桥接前置准入后同步失败时原子 remove + 补偿释放，容量不泄漏（FR-06 / AC-12-CM）。
- 经 bus/自定义 REST 续传被拒时原 Task 保持 INPUT_REQUIRED，不被 SDK 转写 FAILED（FR-06 / AC-09-BF）。
- 续传退避序列有 ±20% 随机抖动，避免多实例同步重试惊群（FR-07 / I-RT-05）。

### 7.2 安全与可观测（§11.3 前半部分）

- 不同用户/会话/Task 上下文不因拒绝/重试/恢复串用（§11.3 第 1 条 / SEC-01）。
- 准入/拒绝/执行/事件可通过既有请求标识或 Task 标识关联诊断（§11.3 第 2 条 / SEC-02）。
- 三类拒绝原因可稳定区分；诊断信息不包含凭据/内部地址/容量明细/异常堆栈（§11.3 第 3 条 / SEC-03/SEC-04）。

### 7.3 长稳压测（§11.3 后半部分 + §11.4）

- 24h 短链路（5 轮/请求、20 并发、max=30）：正常请求体验 + 容量闭环，无泄漏，内存有界（§11.3 第 4 条 / LT-01）。
- 24h 长链路（>5 轮、20 并发、max=30）：长任务不突破并发上限，内存有界（§11.3 第 5 条 / LT-02）。
- 事件压力验证：事件内存有界、存量事件排空、自动恢复（§11.3 第 6 条 / LT-03）。
- 7 天压力回归：保护能力自身不新增无界对象/线程/事件积压（§11.3 第 7 条 / LT-04）。
- 交付物：代码/配置/错误契约交付联调；自动化测试覆盖全部 MUST 要求及所有场景；事件安全承载范围压测证据 + 长稳报告；更新调用方接入说明（§11.4）。

## 8. 执行策略

### 8.1 分层执行顺序

```
Phase 1: L1 模块单测（不依赖 SUT 启动）
  ├── DFX-002 已有单测全部通过（回归）
  └── 新增停摆检测单测 + 额度耗尽归因单测 + businessCode 单测 + 释放协调器单测（含多保护条件 + TTL 禁用边界）

Phase 2: L2 集成测试（SpringBootTest，不需要独立 SUT）
  ├── DFX-002 已有 E2E 全部通过（回归）
  ├── 新增 businessCode 验证（含不创建 FAILED Task / 业务事件 / Session-Callback-Rail）
  ├── 新增事件过载 E2E（含在途调用继续 / 状态恢复）
  ├── 新增多入口一致性（含可区分于业务失败）
  ├── 新增多保护条件同时存在
  ├── 新增续传过载
  └── 新增容量重复释放不虚增

Phase 3: L3 验收测试（需要独立 SUT）
  ├── DFX-002 已有 4 组验收测试全部通过（回归）
  ├── 新增 MaxThirty 组（max=30）
  ├── 新增 EventOverload 组（max=30 + event_flood）
  ├── 新增 MultiEntryConsistency 组（含可区分于业务失败）
  ├── 新增 MultiCondition 组（多保护条件同时存在）
  ├── 新增 ResumeOverload 组（max=1）
  └── 新增安全与可观测组（SEC-01~SEC-04）

Phase 4: L4 长稳压测（独立环境）
  ├── LT-01: 24h 短链路
  ├── LT-02: 24h 长链路
  ├── LT-03: 事件压力验证
  └── LT-04: 7 天回归
```

### 8.2 P0 必须全绿

| 用例 | 说明 |
|---|---|
| smoke-1~smoke-4 | 基础链路（回归） |
| AC-01-S/AC-01-ST | 正常执行透明 |
| AC-02-R/AC-02-F/AC-02-Rcv | 第 31 个拒绝 + 恢复 |
| AC-04-EO/AC-04-SU | 事件过载/状态不可用错误码 |
| AC-05-NT/AC-05-NF/AC-05-NE/AC-05-LLM | 拒绝不创建 Task / 不创建 FAILED Task / 不创建业务事件 / 不调用 LLM |
| AC-06-T/AC-06-E | 存量保护 |
| AC-07-C/AC-07-IE/AC-07-LK/AC-07-CL | 容量完整释放（含入口异常 + 后果落地释放时点） |
| AC-08-R/AC-08-E/AC-08-SU/AC-08-PS | 三重恢复（执行容量 + 事件积压 + 事件状态）+ 永久停摆边界 |
| AC-09-NR/AC-09-RR/AC-09-BF | 正常续传 + 过载续传 + bus/REST 不被转写 FAILED |
| AC-10-HT/AC-10-DF/AC-12-NB/AC-12-CM | 多入口一致性（含可区分于业务失败 + 补偿释放） |
| AC-11-S/AC-11-NS | 信息安全 |
| AC-MC-01/AC-MC-02 | 多保护条件判定顺序 |
| AC-04-PR | pending-release 归因细分 |
| AC-07-TTL | TTL 兜底释放 |
| F-10/F-20/F-21/F-22/F-23/F-25 | DFX-002 回归 |

### 8.3 运行命令

```bash
# ── L1: 模块单测 ──
# runtime 仓单测（AdmissionReleaseCoordinatorTest 等被测类在 agent-service-app 模块）
cd agent-runtime-java && mvn -pl service/agent-service-app -am test

# solution 侧单测（bus-consumer / custom-rest 兄弟模块，-am 不覆盖兄弟模块，需显式列出 -pl）
cd agent-solution/common/agent-runtime-ext-java
mvn -pl agent-service-adapters/agent-service-adapters-agentcore-ext,\
  agent-service-bus/agent-service-bus-consumer,\
  agent-service-app/agent-service-app-custom-rest \
  -am test

# ── L2: 集成测试（SpringBootTest，同上模块路径） ──
# runtime 仓
cd agent-runtime-java && mvn -pl service/agent-service-app -am test
# solution 侧（同上 -pl 列表）
cd agent-solution/common/agent-runtime-ext-java
mvn -pl agent-service-adapters/agent-service-adapters-agentcore-ext,\
  agent-service-bus/agent-service-bus-consumer,\
  agent-service-app/agent-service-app-custom-rest \
  -am test

# ── L3: 验收测试（独立 SUT） ──
cd agent-solution/common/example/concurrency-throttling-acceptance-demo
bash run-acceptance.sh smoke pending

# ── L4: 长稳压测（独立环境） ──
# LT-01: 24h 短链路
# LT-02: 24h 长链路
# LT-03: 事件压力
# LT-04: 7 天回归
```

> **模块拓扑说明**：U-CL/U-EO/U-BC 的被测类（AdmissionReleaseCoordinator、A2aJsonRpcProtocol、A2AAgentExecutorAdmission、A2aJsonRpcControllerAdmission 等）在 **agent-runtime-java** 仓 `service/agent-service-app` 模块；RuntimeBusEventConsumerTest、RequestHandlerBusA2aBridgeAdmissionTest、CustomRestA2ABridgeAdmissionTest 在 **agent-solution** 仓 `agent-service-bus-consumer` 和 `agent-service-app-custom-rest` 两个兄弟模块。`-am` 只构建目标模块的依赖，不含兄弟模块，因此 solution 侧必须显式 `-pl` 列出全部目标模块。

DFX-002 并发控制实现需源码 install 一次落地 jar（README §6）：

```bash
# 1) base（runtime 仓，-DskipTests 仅跳过测试做 install）
cd agent-runtime-java && mvn install -DskipTests
# 2) ext（solution 仓）
cd agent-solution/common/agent-runtime-ext-java \
  && mvn install -pl agent-service-adapters/agent-service-adapters-agentcore-ext -am -DskipTests
```

## 9. 前置条件与依赖

### 9.1 代码实现前置

| 前置项 | 说明 | 阻塞范围 |
|---|---|---|
| 后果落地释放协调器实现 | `AdmissionReleaseCoordinator`：租约生命周期、FIFO 队列、释放迁移、CAS 恰好一次 | **阻塞全部释放时点/容量完整性测试** |
| TTL 兜底定时器实现 | 后果丢失容量回收，强制上限 `max(20m, 2×ttl)` | **阻塞 AC-07-TTL/PE, I-CL-02** |
| 停摆检测实现 | 心跳刷新 + 30s 阈值常量 + 主 deque 非空判定 | **阻塞 AC-04-SU, AC-08-PS/SU, U-EO-06** |
| 三种 businessCode 实现 | HTTP/REST/bus 入口错误响应承载 + `error.details` → `error.data` 序列化映射 | **阻塞 businessCode 测试** |
| pending-release 归因细分 | `tryAcquire` 失败时按 pending-release 持有数细分 `OVERLOADED` / `CONCURRENCY` | **阻塞 AC-04-PR, AC-MC-01/02** |
| 判定顺序实现 | 停摆优先于额度，pending-release 细分 | **阻塞 AC-MC-01/02** |
| bus 投影 retryable=true + businessCode | `RuntimeBusEventConsumer` 投影改造 | **阻塞 AC-10-BU, I-ME-02/03** |
| 前置准入交接与补偿 | bus/REST 桥接预取-交接模式，同步失败原子 remove + 补偿释放 | **阻塞 AC-12-CM** |
| 续传退避抖动 ±20% | `A2ATaskContinuation` 退避序列增加随机抖动 | **阻塞 I-RT-05** |
| limit=-1 直通实现 | 协调器直通模式，监听器配对保持 | **阻塞 AC-01-M1, I-BC-09** |
| 永久停摆故障注入能力 | 注入无超时永久阻塞 | **阻塞 AC-08-PS, I-EO-10** |
| agent-bus 入口准入检查 | bus 消费触发执行时接入保护 | **阻塞多入口一致性测试** |
| 非 HTTP 拒绝可区分于业务失败 | agent-bus/回调拒绝结果与 Agent 业务失败结果可区分 | **阻塞 AC-10-DF** |
| `event_flood` 工具开发 | 在 ConcurrencyTools 中新增 | **阻塞事件过载验收测试** |
| Mock MCP/Sandbox/Remote A2A | 支持调用计数的 Mock 组件 | **阻塞 AC-05-MCP/SB/RA** |
| max=30 配置支持 | application.yml 或环境变量 | 不阻塞 |
| `admission-release-ttl` 配置支持 | application.yml 或环境变量（默认 10m；TTL 用例需配置 30s 以缩短等待） | 不阻塞 |

### 9.2 环境前置

| 前置项 | 说明 |
|---|---|
| Mock LLM | 替代真实 LLM，支持调用计数 |
| JVM 监控 | heap / direct memory / thread count（L4 需要） |
| JMX / 测试观测端点 | L4 长稳压测需通过 JMX MBean 或测试观测端点采集租约表大小、permit 计数、子队列深度等内部状态（公开面 `current_active_tasks` 只覆盖活跃任务读数，无法覆盖租约表条目数） |
| 7 天稳定环境 | 用于 LT-04 |
| Issue#196 根因修复 | 不阻塞功能开发，**阻塞最终长稳验收** |

### 9.3 依赖项

| 依赖项 | 所属代码仓 | 本需求中的作用 | 处理方式 | 依赖状态 |
|---|---|---|---|---|
| A2A 请求与 Task 生命周期能力 | agent-runtime-java | 区分执行类与控制类请求 | 扩展 | 已具备基础能力 |
| A2A 事件处理能力 | agent-runtime-java | 处理已接收事件 | 扩展 | 安全承载边界待压测确认 |
| EDPA A2A 调用链 | Agent-Solution | 提交 HTTP 请求 | 扩展 | 已具备，需联调 503 语义 |
| Agent Bus 与回调续传入口 | Agent-Solution | 触发非 HTTP 新执行轮次 | 扩展 | 需核查并消除保护旁路 |
| Agent 业务执行能力 | agent-core | 执行已准入任务 | 复用 | 已具备 |
| 目标环境稳定性压测 | 测试环境 | 确定事件安全承载范围 | 新增验证 | 待执行 |
| Issue#196 根因修复 | agent-runtime-java / Agent-Solution | 消除已知资源增长和锁竞争根因 | 并行专项 | 不阻塞功能开发，阻塞最终长稳验收 |

## 10. 用例统计

| 层级 | 已有用例 | 新增用例 | 总计 |
|---|---|---|---|
| L1 模块单测 | ~40 | ~19 | ~59 |
| L2 集成测试 | ~25 | ~32 | ~57 |
| L3 验收测试（主流程+分支+多条件） | 17 | ~59 | ~76 |
| L3 验收测试（安全与可观测） | 0 | 4 | 4 |
| L4 长稳压测 | 0 | 4 | 4 |
| **合计** | **~82** | **~118** | **~200** |

## 11. 验收标准映射

| 验收 ID | 验收内容 | 对应测试用例 | 覆盖状态 |
|---|---|---|---|
| AC-01 | 正常执行 + 只读入口不受阻断 | AC-01-S/ST/GT/M1, 已有 smoke + E2E getTask | 完全覆盖 |
| AC-02 | 第 31 个请求被拒 + 恢复 | AC-02-R/F/Rcv/ZERO | 完全覆盖 |
| AC-03 | 事件积压有界 | AC-03-B/R, LT-01/02/03 | 完全覆盖 |
| AC-04 | 事件过载/状态不可用错误码 | AC-04-EO/SU/CL/PR, AC-MC-01/02, I-EO-01/02/09, I-MC-01 | 完全覆盖 |
| AC-05 | 拒绝不创建执行副作用 | AC-05-NT/NF/NE/LLM/MCP/SB/RA/NS/EO/SU, I-BC-02/03/05/06/07/08 | 完全覆盖 |
| AC-06 | 存量 Task 和事件保护 | AC-06-T/IC/E/ND, I-EO-03/04/08 | 完全覆盖 |
| AC-07 | 容量完整释放 | AC-07-C/F/Cx/IR/IE/LK/DP/CL/TTL/PE, I-CL-01/02, I-DP-01, 已有 F-20~F-25 | 完全覆盖 |
| AC-08 | 自动恢复 | AC-08-R/E/SU/GT/Cx/PS/AT, I-EO-05/06/10 | 完全覆盖 |
| AC-09 | 续传重新准入 + 过载续传 | AC-09-NR/RR/NT/NE/Rcv/OL/BF, I-RT-01~05 | 完全覆盖 |
| AC-10 | HTTP/非 HTTP 入口一致性 | AC-10-HT/HS/CR/CR-SU/BU/BU-SU/DF/CB, I-ME-01~04 | 完全覆盖 |
| AC-11 | 拒绝信息安全 | AC-11-S/NS, I-BC-04, SEC-04, U-BC-06 | 完全覆盖 |
| AC-12 | 所有执行入口无旁路 | AC-12-NB/CM, I-ME-01~04 | 完全覆盖 |
| §11.3 安全 | 上下文不串用 + 可观测 + 不泄露 | SEC-01/02/03/04 | 完全覆盖 |
| §11.3 长稳 | 24h + 7d 压测 | LT-01/02/03/04 | 完全覆盖 |
| §11.4 交付物 | 代码/自动化/压测证据/接入说明 | 全部 L1~L4 通过 + 压测报告 | 完全覆盖 |

## 附录 A. 相对 DFX-002 测试基线的差异

| 变化 | 对用例的影响 |
|---|---|
| 新增 30 并发固定保护口径 | DFX-002 用 max=1/2/8/-1 验收，DFX-006 新增 max=30 验收组 |
| 新增事件处理过载保护 | DFX-002 无事件过载测试，DFX-006 新增 `event_flood` 工具 + EventOverload 验收组 |
| 新增三种稳定 businessCode | DFX-002 用通用 `-32603`，DFX-006 需解析 `error.data.businessCode` 并断言三种稳定值 |
| 新增 agent-bus 入口保护 | DFX-002 只测 HTTP + Custom REST，DFX-006 新增 agent-bus 和回调续传入口 |
| 新增拒绝无副作用验证（全维度） | DFX-002 只验证不创建 Task，DFX-006 新增不创建 FAILED Task / 不创建业务事件 / 不创建 Session-Callback-Rail / 不调用 LLM/MCP/Sandbox/Remote A2A |
| 新增拒绝信息安全验证 | DFX-002 无信息安全断言，DFX-006 新增错误消息不泄露内部信息验证 |
| 新增多保护条件同时存在 | DFX-002 无多条件测试，DFX-006 新增并发已满 + 事件过载同时触发的验证 |
| 新增在途下游调用继续完成 | DFX-002 无在途调用验证，DFX-006 新增已准入 Task 在途 LLM/MCP 调用继续完成的验证 |
| 新增入口异常容量释放 | DFX-002 只测完成/失败/取消/IR 释放，DFX-006 新增入口异常路径释放 |
| 新增容量重复释放不虚增 | DFX-002 只测不泄漏，DFX-006 新增重复释放后可用容量不超过 max |
| 新增事件状态恢复 | DFX-002 无事件状态，DFX-006 新增 STATE_UNAVAILABLE 恢复后自动恢复准入 |
| 新增非 HTTP 拒绝可区分于业务失败 | DFX-002 无此断言，DFX-006 新增 agent-bus 拒绝与 Agent 业务失败可区分验证 |
| 新增长稳压测 | DFX-002 无长稳压测，DFX-006 新增 24h×2 + 事件压力 + 7 天回归 |
| 新增后果落地释放时点验证 | DFX-002 在 finally 立即释放，DFX-006 释放点后移到终态/中断态事件被处理器消费后（L2 §3.1） |
| 新增 pending-release 归因细分 | DFX-002 一律报并发满，DFX-006 按 pending-release 持有者区分 `OVERLOADED` / `CONCURRENCY`（L2 §3.3） |
| 新增 TTL 兜底释放 | DFX-002 无后果丢失路径回收，DFX-006 新增 TTL + 强制上限 `max(20m, 2×ttl)` 无条件释放（L2 §3.7/§4.4） |
| 新增判定顺序验证 | DFX-002 无多级分类，DFX-006 新增停摆 > 额度的判定顺序（L2 §2.2.1） |
| 新增永久停摆边界 | DFX-002 无停摆检测，DFX-006 新增永久停摆不自愈 + 查询可用边界（L2 §3.5） |
| 新增 ActiveTask 互证 | DFX-002 无过载期语义验证，DFX-006 新增读数 ≈ 上限与 503 同时成立（L2 §2.4） |
| 新增 limit=-1/0 边界 | DFX-002 测 -1 不限额，DFX-006 新增 -1 监听器配对 + 0 零容量边界（L2 §6.3/§6.4） |
| 新增 bus 投影 retryable=true | DFX-002 bus 投影硬编码 false，DFX-006 改为 retryable=true + businessCode（L2 §4.6） |
| 新增前置准入交接与补偿 | DFX-002 bus/REST 只读预检，DFX-006 升级为预取-交接 + 补偿释放（L2 §3.8） |
| 新增续传退避抖动 ±20% | DFX-002 固定退避序列，DFX-006 新增随机抖动避免惊群（L2 §4.6） |
| 新增 error.details → error.data 映射 | DFX-002 无 businessCode，DFX-006 新增 Gson 序列化后处理映射（L2 §4.6） |

## 附录 B. event_flood 工具设计

> **检视修正**：原实现仅返回一条 `audited(...)` 结果，不产生 count 个事件。以下修正为循环 enqueue count 个事件，使事件处理真正积压。同时补充慢消费注入手段说明。

```java
// 在 ConcurrencyTools.java 中新增
public static final String EVENT_FLOOD = "event_flood";

public static Tool eventFlood() {
    return tool(EVENT_FLOOD, "产生大量事件模拟事件积压",
        schema(Map.of("count", numberProperty("产生的事件数量")), List.of("count")),
        inputs -> {
            int count = intValue(inputs.get("count"), 100);
            // 循环 enqueue count 个状态/内容事件，使事件处理积压
            for (int i = 0; i < count; i++) {
                emitStatusEvent(EVENT_FLOOD, Map.of("index", i, "phase", "flooding"));
            }
            return audited(EVENT_FLOOD, Map.of("status", "FLOODED", "count", count));
        });
}
```

> **慢消费注入手段**："注入慢消费"在黑盒 SUT 上无现成开关。Fixture 设计需补充以下方案之一：
> 1. **event_flood 工具内置 sleep**：在 `emitStatusEvent` 循环中加 `sleep(100ms)`，使事件产生速率 > 消费速率，自然形成 pending-release 积压
> 2. **slow_task + event_flood 组合**：先用 `slow_task` 占满全部 30 个 permit，再触发 `event_flood` 产生大量事件，后果事件排队等待 permit 释放形成 pending-release
> 3. **配置事件处理延迟**：SUT 暴露 `event-processing-delay` 配置项（测试 fixture 注入）

## 附录 C. businessCode 断言模式

```java
// 验证 HTTP 503 + businessCode
HttpResponse<String> response = client.sendMessageRaw("ctx-test", "echo:test");
assertThat(response.statusCode()).isEqualTo(503);

JsonNode error = MAPPER.readTree(response.body()).path("error");
assertThat(error.path("data").path("businessCode").asText())
    .isEqualTo("CONCURRENCY_LIMIT_REACHED");

// 验证错误消息不泄露内部信息
String body = response.body();
assertThat(body).doesNotContain("currentCount", "maxConcurrent",
    "queue", "thread", "model", "credential", "stack");
```

## 附录 D. 新增遗漏点补充说明

### D.1 对照需求文档的 10 个遗漏点（FRS + DFX-006）

以下 10 个测试用例为对照需求文档逐项检查后发现的遗漏，已补充至本文档对应位置：

| # | 遗漏点 | 需求来源 | 补充用例 |
|---|---|---|---|
| 1 | 入口异常容量释放 | FRS §11.1 AC-07 + DFX-006 §5.1.6 + §6 | AC-07-IE, I-DP-01 |
| 2 | 三类拒绝无副作用 | FRS §11.2 AC-05 | AC-05-EO, AC-05-SU |
| 3 | 拒绝不创建 Task 业务事件 | FRS §11.2 AC-05 + DFX-006 §5.1.4 | AC-05-NE, I-BC-06 |
| 4 | 拒绝不创建 FAILED Task | DFX-006 §5.1.4 | AC-05-NF, I-BC-05 |
| 5 | 多个保护条件同时存在 | FRS §8 + DFX-006 §5.1.7 | AC-MC-01, I-MC-01, U-BC-05 |
| 6 | 事件状态不可用恢复 | FRS §5.2 + DFX-006 §5.1.6 | AC-08-SU, I-EO-06 |
| 7 | 容量重复释放不虚增 | DFX-006 §5.1.6 | AC-07-DP, I-DP-01 |
| 8 | 不调用 MCP/Sandbox/Remote A2A | FRS §11.2 AC-05 + DFX-006 §5.1.4 | AC-05-MCP/SB/RA, I-BC-07 |
| 9 | 非 HTTP 拒绝可区分于 Agent 业务失败 | DFX-006 §5.1.7 + FRS §10.2 | AC-10-DF, I-ME-03 |
| 10 | 已准入 Task 在途下游调用继续完成 | DFX-006 §5.1.5 | AC-06-IC, I-EO-08 |

### D.2 对照 L2 实现文档（DFX-006）的补充遗漏点

以下补充用例为对照 L2 设计文档实现机制后发现的遗漏，已补充至本文档对应位置：

| # | 遗漏点 | L2 实现来源 | 补充用例 |
|---|---|---|---|
| 11 | 后果落地释放时点验证 | L2 §3.1（释放点从执行返回后移到事件消费后） | AC-07-CL, I-CL-01 |
| 12 | pending-release 归因细分 | L2 §3.3（额度耗尽时按 pending-release 区分 OVERLOADED/CONCURRENCY） | AC-04-PR, I-EO-09 |
| 13 | TTL 兜底释放 + 持久化失败路径 | L2 §3.7/§4.4（后果丢失 TTL 回收，强制上限 max(20m, 2×ttl)） | AC-07-TTL/PE, I-CL-02, U-CL-01 |
| 14 | 判定顺序：停摆 > 额度 | L2 §2.2.1（分类序列逐级短路） | AC-MC-02 |
| 15 | 永久停摆不自愈边界 | L2 §3.5（无超时永久阻塞 → 持续 STATE_UNAVAILABLE） | AC-08-PS, I-EO-10 |
| 16 | ActiveTask 互证 | L2 §2.4（过载期读数 ≈ 上限与 503 同时成立） | AC-08-AT |
| 17 | limit=-1 监听器配对 / limit=0 零容量 | L2 §6.3/§6.4（直通实现 + 零容量形态） | AC-01-M1, AC-02-ZERO, I-BC-09 |
| 18 | bus 投影 retryable=true | L2 §4.6（从硬编码 false 改为 true + businessCode） | AC-10-BU（修改） |
| 19 | 前置准入交接与补偿释放 | L2 §3.8（预取-交接模式，原子 remove + 补偿释放） | AC-12-CM |
| 20 | 同 Task 轮次重叠 FIFO 释放 | L2 §3.6（per-task FIFO 租约队列） | AC-09-OL, I-RT-04 |
| 21 | bus/REST 续传不被转写 FAILED | L2 §3.8（前置准入消除 FAILED 转写） | AC-09-BF |
| 22 | 续传退避抖动 ±20% | L2 §4.6（退避序列增加随机抖动） | I-RT-05 |
| 23 | error.details → error.data 序列化映射 | L2 §4.6（Gson 后处理注入） | U-BC-06 |
| 24 | 停摆阈值 30s 常量 | L2 §4.5（STALL_DEADLINE 常量，大于 5s 重启退避） | U-EO-06 |
| 25 | 租约 CAS 恰好一次释放 | L2 §4.1（released AtomicBoolean CAS） | U-CL-02 |
| 26 | 长稳验收租约表/permit 检查 | L2 §7.4（复用 #196 泄漏检查口径） | LT-01~04（修改） |
