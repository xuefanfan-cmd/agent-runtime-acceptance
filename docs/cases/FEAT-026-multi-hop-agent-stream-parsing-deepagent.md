---
feature_id: FEAT-026
feature_title: 多跳智能体调用的流式数据解析
sut: agent-client SDK (com.openjiuwen:agent-client-sdk-for-jvm:0.1.0, RuntimeTransportProvider 直连) -> multi-deep-research-demo (deep-research + search + verify)
status: designed-runtime-artifact-gated
tags: [blackbox, contract, integration, feat-026, call-tree]
depends_on:
  - L2 设计 Feat-Func-026（`develop/03-architecture/L2-Low-Level-Design/agent-client/Feat-Func-026-multi-hop-agent-stream-parsing.md`，2026-08-14 版本，status: proposed, authority: non-authoritative）
  - L2 边界 Feat-Func-006（STREAMING 帧取得与恢复，FEAT-026 只消费归一化后的 agentEvent）
  - Runtime 生产者 Feat-Func-004（delegation/output/status 事件生成与透传）
related_docs:
  - FEAT-006 客户端调用测试设计：[FEAT-006-standard-agent-client-invocation.md](FEAT-006-standard-agent-client-invocation.md)
  - FEAT-004 远程编排覆盖档：[FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md](FEAT-004-remote-agent-orchestration-entrypoint-deepagent.md)
---

# FEAT-026 - 多跳智能体调用的流式数据解析测试设计

> 由业务应用只使用正式 `agent-client` facade 以 `STREAMING` 调用 `multi-deep-research-demo` 的 root `deep-research-agent`，验证 SDK 增量消费 Runtime 透传的 delegation/output/status 事件、构建多 Agent 调用树并对外发布不可变 `CallTreeSnapshot` 快照；业务全程只持 `invocationRef`、订阅 `callTree()`，不感知服务端 taskId，也不解析 A2A JSON-RPC/SSE。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| L2 | `docs\develop\03-architecture\L2-Low-Level-Design\agent-client\Feat-Func-026-multi-hop-agent-stream-parsing.md`，读取日期 2026-08-20 |
| Feature | L2 §10 追踪关系声明 feature 文档仅作效果追踪、authority: non-authoritative；本仓 `develop/02-features` 下无 FEAT-026 独立 feature 文档，范围与 Oracle 以 L2 §2/§4/§9 为准 |
| agent-solution 仓 | `main@1cba268bbfad2627e84249229688b1eb1c7e38d3`，读取日期 2026-08-20 |
| acceptance 仓 | `main@cd1c1f1c9bbb2511184941a7b624c6f457c4dfe1`，读取日期 2026-08-20；本文为新增设计 |
| 测试 Agent | `com.openjiuwen.example:agent-deep-research:0.1.0`（root，:18090）+ `agent-search:0.1.0`（:18091）+ `agent-verify:0.1.0`（:18093），由 `SutStack` 以外部 JAR 拉起；COMPARISON 查询触发 root 在同一 turn 批量并行调用 search/verify |
| agent-client SDK | `com.openjiuwen:agent-client-sdk-for-jvm:0.1.0`（`agent-solution/common/agent-client/agent-client-sdk-for-jvm/`），读取日期 2026-08-21。SDK 已完整实现 `callTree()` 公共 API、`CallTreeSnapshot`/`CallTreeNode`/`CallTreeReducer`/`GatewayTransportProvider`/`RuntimeTransportProvider`，且已作为 acceptance 项目 test-scope Maven 依赖（pom.xml line 178-183）。`InvocationMode.BLOCKING`/`ASYNC` 为预留枚举（传入 `invoke()` 以 `UNSUPPORTED_MODE` 拒绝）。 |

L2 明确本特性只对 `STREAMING` 构树，`BLOCKING/ASYNC` 不发布调用树；多个子特性处于 ⚠️ 部分实现 / ⬜ 计划中（§2.1）。本设计按 L2 当前交付能力划分 runtime-artifact-gated 与 contract 用例，不生成空测试。已查阅 `agent-solution/common/agent-client/agent-client-sdk-for-jvm` 产品源码（2026-08-21），确认 SDK 层 `callTree()` Publisher、`CallTreeReducer` 归并器、`CallTreeSnapshot` 不可变快照均已实现。当前门禁为 Runtime 侧 `artifact.metadata.agentEvent`（delegation/output/status）的发射状态：SDK 的 `A2aJsonCodec.parseArtifact()` 从 A2A artifact 的 `metadata.agentEvent` 字段提取 `AgentEvent(type, source, target, state)`，若 Runtime 未在 artifact metadata 中写入 `agentEvent`，则 `CallTreeReducer` 无法建树，`callTree()` Publisher 不发布快照。

### 1.2 范围

本方案只验证 L2 交付给业务应用的 `agent-client` 调用树黑盒行为与协议契约：`STREAMING invoke` 后的 `InvocationCall.callTree()` 发布、delegation 建树、并发兄弟 output 交织、Artifact append/lastChunk、根 agentId 延迟补全、根 outputText 不污染、子 INPUT_REQUIRED 边界、SSE 断线恢复 PARTIAL、Publisher 背压与资源降级、畸形输入诊断、BLOCKING/ASYNC 无树。测试不直接调用 SDK 内部 `CallTreeReducer`、orphan buffer 或节点映射表，也不检查 runtime TaskStore、agentEvent 生产实现或 Gateway 路由。

FEAT-006 负责 STREAMING 帧取得与恢复快照、根级 INPUT_REQUIRED 等待点与 pending toolCallId 解析；本方案只在"FEAT-006 已把归一化 agentEvent 喂给 reducer"后验证树对外效果，不重复断言 FEAT-006 的恢复/续传契约。Runtime 侧 delegation 事件生成属 FEAT-004，本方案只以其真实产出作为输入证据。

当前 `agent-runtime-acceptance` 的 `com.huawei.ascend.sit.client` 下 helper（`A2aServiceClient` / `InteractionFlow` / `A2aEventCollector`）及 `org.a2aproject.sdk` 是验收辅助类，不是 FEAT-026 产品 SDK。但正式 `agent-client` SDK（`com.openjiuwen:agent-client-sdk-for-jvm:0.1.0`）**已交付**且已作为 acceptance Maven 依赖，其 `InvocationCall.callTree()` 返回 `Flow.Publisher<CallTreeSnapshot>`，`CallTreeReducer` 从 `artifact.metadata.agentEvent` 提取 delegation/output/status 事件建树。因此以下用例的当前门禁不再是"SDK 未交付"，而是 **Runtime 侧 artifact metadata.agentEvent 发射状态**：若 Runtime（`agent-service-app-0.1.1`）未在 A2A artifact 的 metadata 中写入 `agentEvent` 字段，SDK 的 `CallTreeReducer` 将无法建树，`callTree()` Publisher 不发布快照。acceptance helper 消费的真实 wire 序列仍只作为 §9.7 Runtime 生产者 fixture 证据，不替代 `callTree()` 快照断言。

## 2. 前置条件与证据

- 由 `SutStack` 按 verify -> search -> deep-research 顺序拉起 `multi-deep-research-demo` 三个外部 JAR（参照 `ParallelSearchComparisonTest` 的 `.downstreams(SEARCH, VERIFY)` + `deep-research-auto` remote-agents-prefix 约定）；使用有效 LLM（DeepSeek `DEEPSEEK_API_KEY`，search 可走 stub fixture 以确定性）。
- 正式 agent-client 只配置 Gateway 地址与测试凭证，业务测试代码不得配置 runtime endpoint、routeHandle、broker、topic、taskId 或 agentEvent 字段路径。
- 每个测试生成唯一 `conversationId` 与 COMPARISON 查询文本（fixture 覆盖 DeepSeek / Qwen / 豆包三条 route，使 root 真正批量 fan-out 而非走兜底）；服务端响应需包含该轮业务语义与 ≥2 vendor 标记。
- 主要证据为产品 `callTree()` Publisher 发布的 `CallTreeSnapshot`（root / activeSpeakers / lastObservedSpeaker / completeness / speakingPhase / diagnostics / revision）与 `InvocationSnapshot.callTree`；`RemoteInvocationProbe` 的 fan-out 计数只用于证明 root 真实并行委托，不作为树归并断言。
- 断流通过 acceptance 现有 `FaultLink.resetPeer()/restore()` 在 client 与 Gateway 之间制造；恢复用例需保证断点落在非终态、且断前后均能经 `getInvocation` 取到权威快照。畸形 agentEvent 与超大 Artifact 用 `MockRemoteAgentServer`（JDK 内置 `com.sun.net.httpserver.HttpServer`）顶替下游 search/verify 产出确定性 fixture 帧，其他帧仍来自真实 Agent，禁止用 fake Agent 替代整条链路。
- 根 `taskId` 由 runtime 生成、不稳定；树结构断言用 `NodeKey(agentId, taskId)` 复合字段并按 agentId 索引，不硬编码 taskId 字面值。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-026.streaming.two-hop-tree` | L2 §4.1/§4.2/§4.5/§9.1/§9.2 | blackbox | runtime-artifact-gated, P0 | sdk-available | delegation 建树、并发兄弟 output 交织、lastObservedSpeaker 随帧变化不清除兄弟、根输出不被下游污染 | `callTree()` 快照 root.children、activeSpeakers、speakingPhase | 多活跃 speaker 集合为 L2 ⬜ 目标，本例以 lastObservedSpeaker 等价占位 |
| `FEAT-026.streaming.multi-hop-and-distinct-taskids` | L2 §4.1.2/§4.3.1/§9.1 | blackbox | runtime-artifact-gated, P1 | design-only | 多跳链、同 agentId 不同 taskId 产生独立节点、根 agentId 延迟补全且不产生第二根 | NodeKey 复合字段、root.key.agentId | 五层乱序为 fixture 扩展点，demo 现深 2 层 |
| `FEAT-026.streaming.artifact-merge` | L2 §4.3.2/§4.8.2/§9.1 | blackbox | runtime-artifact-gated, P1 | design-only | append=true 追加、lastChunk=true 标记 complete、artifactId 跨节点冲突降级、DataPart 深不可变 | ArtifactSnapshot.parts/complete、diagnostics | 深不可变为 L2 §2.3.2 目标，当前未深复制，标 expected-red |
| `FEAT-026.streaming.recovery-partial` | L2 §4.7/§9.3 | blackbox | runtime-artifact-gated, P0 | design-only | 断线即 PARTIAL、重复帧幂等合并、不回升 LIVE、不消费普通 history、恢复后继续扩展原树 | completeness=PARTIAL、revision 不回退 | SSE 中断分类属 FEAT-006，本例只断树完整度 |
| `FEAT-026.streaming.child-input-boundary` | L2 §4.4/§4.6/§9.4 | blackbox | runtime-artifact-gated, P1 | design-only | 子 input_required 只更新子节点、不结算根 Call、根 INPUT_REQUIRED 前发布最终子节点树、不伪造 pending | 子节点 state、root 未终态、最终快照 | pending toolCallId 解析属 FEAT-006 |
| `FEAT-026.streaming.publisher-resource` | L2 §4.8/§9.5 | blackbox | runtime-artifact-gated, P1 | design-only | 晚订阅得最新快照、revision 单调、慢订阅不阻塞 SSE、节点/字节超限 DEGRADED、多 invocation 隔离 | revision 序列、completeness=DEGRADED、独立 publisher | 性能基准与定时 debounce 不在范围 |
| `FEAT-026.streaming.malformed-input` | L2 §4.2/§4.5/§7/§9.1/§9.6 | contract | runtime-artifact-gated, P2 | design-only | 环、多父、缺字段、未知 type/status 只诊断不中断调用、超大内容截断降级、父子同时 output 记录层级违规诊断 | diagnostics、根调用仍 COMPLETED | 未知 type 代理不替代真实 Agent 语义；SPEAKING_HIERARCHY_VIOLATION 扩展点 |
| `FEAT-026.streaming.controller-output` | L2 §4.3.3/§4.5/§9.1/§9.2 | blackbox | runtime-artifact-gated, P1 | design-only | controller_output 清空下游活跃集合并切 ROOT_SPEAKING、控制文本不污染根 outputText、不修改具体父子边 | speakingPhase、currentSpeaker、root outputText | controller_output 无 source/target，不伪造边返回 |
| `FEAT-026.streaming.orphan-buffer-merge` | L2 §4.2/§4.3.1/§9.1 | blackbox | runtime-artifact-gated, P1 | design-only | output/status 早于 delegation 进入 orphan buffer、父边到达后归并到正确节点、orphan 超限降级 | diagnostics(UNRESOLVED_ORPHAN)、completeness=DEGRADED、节点正确挂载 | orphan buffer 容量为实现细节，只断归并结果 |
| `FEAT-026.streaming.artifact-replace` | L2 §4.3.2/§9.1 | blackbox | runtime-artifact-gated, P1 | design-only | append=false 替换当前 Parts、UTF-8 多字节字节预算、深层 Map/List/Iterable/数组递归复制计入预算 | ArtifactSnapshot.parts 被替换非追加、completeness=DEGRADED | 与 artifact-merge 互补覆盖 replace vs append |
| `FEAT-026.streaming.publisher-edge-cases` | L2 §4.8.1/§9.5 | blackbox | runtime-artifact-gated, P1 | design-only | terminal/close 后尾帧不丢、dispatch 过载只取消对应订阅者、onNext 抛异常不阻塞 SSE 线程、orphan 和总字节超限 DEGRADED | revision 序列完整性、completeness=DEGRADED、异常隔离 | 与 publisher-resource 互补覆盖边界场景 |
| `FEAT-026.contract.mode-exclusion` | L2 §2.3.3/§2.2/§9.1 | contract | deferred | design-only | BLOCKING/ASYNC `callTree()` 不发布快照、`InvocationSnapshot.callTree` 为 null、不生成占位树 | 无快照完成 Publisher | L2 明确移除占位树/PARTIAL best-effort，待代码路径落地 |
| `FEAT-026.contract.provider-fixture` | L2 §9.7 | contract | runtime-artifact-gated, P1 | automated | 捕获 multi-deep-research 真实 delegation/output/status wire 序列作为 Runtime 生产者 fixture 证据 | `A2aEventCollector` raw 帧解析 | 只作协议材料，不证明 client 侧归并 |
| `FEAT-026.contract.api-compatibility` | L2 §9.7 | contract | deferred, P2 | design-only | `callTree()` default method 不破坏第三方实现、`InvocationSnapshot` 旧构造器兼容、旧 Transport 仍可运行 | Revapi/japicmp 等价检查或运行时验证 | 待 SDK 引入二进制兼容性检查工具后落地 |

### 当前交付能力追踪

| L2 当前交付能力（§2.1） | 覆盖用例 |
|---|---|
| delegation 建树、output 按 source 归并、根 agentId 延迟补全 | `two-hop-tree` + `multi-hop-and-distinct-taskids` |
| 并发兄弟发言（currentSpeaker 单值→activeSpeakers 集合迁移） | `two-hop-tree`（目标 ⬜，lastObservedSpeaker 等价占位） |
| Artifact replace/append/lastChunk、artifactId 冲突降级 | `artifact-merge` |
| 子节点 status、input_required 不结算根 | `child-input-boundary` |
| STREAMING 恢复合并 PARTIAL、不消费 history | `recovery-partial` |
| 调用树 Publisher 最新值/背压/晚订阅/资源降级 | `publisher-resource` |
| 环/多父/缺字段/未知 type/status/超限/层级违规只诊断不中断 | `malformed-input` |
| controller_output 清空下游并恢复根阶段、不污染根输出、不修改具体边 | `controller-output` |
| output/status 早于 delegation 的 orphan buffer 归并与超限降级 | `orphan-buffer-merge` |
| Artifact replace(append=false) vs append、UTF-8 字节预算、深层递归复制 | `artifact-replace` |
| Publisher 尾帧不丢/过载取消/onNext 异常隔离/orphan 和总字节超限 | `publisher-edge-cases` |
| BLOCKING/ASYNC 无树（移除占位/UNAVAILABLE_FOR_MODE） | `mode-exclusion`（deferred） |
| Provider fixture 与兼容性（callTree default method、旧构造器兼容） | `provider-fixture` |
| API 二进制/源码兼容性检查（Revapi/japicmp 等价） | `api-compatibility`（deferred） |

## 4. 详细用例

### FEAT-026.streaming.two-hop-tree - 两跳调用树与并发兄弟发言

- **状态/优先级**：runtime-artifact-gated, P0；**自动化状态**：sdk-available。
- **Story/来源**：L2 §4.1、§4.2、§4.5、§9.1、§9.2。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的两层 taskId 标识、delegation reducer、活跃发言者模型与根输出过滤语义。
- **G**：`SutStack` 拉起 deep-research + search + verify（search 走 stub fixture 保证 ≥2 vendor 命中）；业务应用创建正式 client；准备唯一 conversation 与 COMPARISON 查询文本；基线不暴露端侧工具。
- **W**：以 `STREAMING` 发起 COMPARISON 调用并订阅 `callTree()`；让 root 在同一 turn 批量并行调用 search 与 verify，二者 output 交织到达；记录每次 revision 的快照。
- **T**：
  - 首快照 root 为 `(null, rootTaskId)`；首个 source.taskId==rootTaskId 的 output 后 root.key.agentId 补全为 `deep-research-agent`，且不产生第二个根节点；
  - root.children 含 search-agent 与 verify-agent 两个子节点，children 顺序按 delegation 首次观察顺序，不表示业务先后；
  - search output 后 speakingPhase=DESCENDANT_SPEAKING；verify output 后 lastObservedSpeaker 切到 verify，但 search 仍处于活跃集合（目标 activeSpeakers 同时含两者；当前单值实现以 lastObservedSpeaker 不被解释为排他发言者为等价证据）；
  - delegation/search/verify 的 Artifact 不进入 root 业务 Artifact 列表，根 outputText 只含无下游 agentEvent 的根业务文本；
  - 全程未提交 taskId，`callTree()` 在终态前发布最终快照。
- **不应断言**：固定 taskId 字面值、固定 vendor 自然语言文本、client 内部 NodeKey 映射结构、currentSpeaker 单值字段名（目标迁移为 activeSpeakers）。
- **失败归类**：树结构/active 语义/根污染不符为 Failure；正式 client/Gateway/Agent 制品或密钥缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat026TwoHopTreeBuildsConcurrentSiblingsAndKeepsRootClean()`。
- **标签**：类级 `@Feature("FEAT-026: 多跳智能体调用的流式数据解析")`、`@Tag("feat-026")`、`@Tag("integration")`、`@Tag("deepagent")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-026.streaming.two-hop-tree: 两跳调用树与并发兄弟发言")`、`@Tag("story-feat-026-streaming-two-hop-tree")`。
- **DisplayName**：`Feat-026 两跳调用树归并并发兄弟发言且保持根输出洁净`。

### FEAT-026.streaming.multi-hop-and-distinct-taskids - 多跳链与同 agentId 不同 taskId

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.1.2、§4.3.1、§9.1。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的 NodeKey 复合标识、根延迟补全与单 Agent 无 agentEvent 时根 agentId 可空语义。
- **G**：同一栈；准备一条触发 search-agent 被调用两次（不同 taskId）的 COMPARISON 查询；五层乱序场景由 mock 在 search 下游挂一层 stub 子代理产出 child-edge 早于 parent-edge 的 fixture（demo 现深 2 层，五层为 fixture 扩展点）。
- **W**：发起调用并订阅 `callTree()`；对同一 `search-agent` agentId 的两次不同 taskId 调用断言独立节点；对 mock 五层乱序序列断言 orphan buffer 归并后无环、revision 单调。
- **T**：
  - 两个 `(search-agent, task-X)` 与 `(search-agent, task-Y)` NodeKey 同时存在且不互相覆盖；
  - 五层链（root→search→refine→extract→cite）建树后无环、每层 children 正确、revision 严格单调递增；
  - 单 Agent 无 agentEvent 的退化调用中 root agentId 可保持空、taskId 仍可稳定标识实例。
- **不应断言**：固定层级深度必须由真实 Agent 产生、client 内部 orphan buffer 容量实现。
- **失败归类**：节点合并/环/revision 回退为 Failure；mock 扩展点未拉起为 Skipped；夹具异常为 Error。
- **方法**：`feat026MultiHopChainAndDistinctTaskIdsKeepAcyclicMonotonicRevision()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.multi-hop-and-distinct-taskids: 多跳链与同 agentId 不同 taskId")`、`@Tag("story-feat-026-streaming-multi-hop-and-distinct-taskids")`。
- **DisplayName**：`Feat-026 多跳链保持无环且同 agentId 不同 taskId 独立成节点`。

### FEAT-026.streaming.artifact-merge - Artifact 追加完成与深不可变

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.3.2、§4.8.2、§9.1。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的 Artifact 合并语义、artifactId 全局唯一契约与 DataPart 深不可变复制承诺。
- **G**：同一栈；mock verify-agent 产出分块 output（append=true 两块、第二块 lastChunk=true）、跨节点复用同一 artifactId 的冲突帧、含可变 Map 的 DataPart 帧。
- **W**：订阅 `callTree()`；消费分块、冲突与 DataPart 序列；对快照中 DataPart 持有引用后修改原可变对象。
- **T**：
  - 分块 append 后该 Artifact parts 含两个 TextPart、complete=true；
  - artifactId 跨节点冲突时保留首个归属、diagnostics 含 ARTIFACT_OWNER_CONFLICT 等价诊断、completeness=DEGRADED 且根调用仍 COMPLETED；
  - DataPart 深不可变：修改原 Map/List 不影响快照中 data（L2 §2.3.2 目标，当前未深复制，本断言 expected-red，作为 TDD 驱动）。
- **不应断言**：固定诊断 code 字面串集合（实现可用统一 RESOURCE_LIMIT）、client 内部字节预算实现。
- **失败归类**：合并/冲突/深不可变不符为 Failure（深不可变预期红）；mock 未产出 fixture 为 Skipped；夹具异常为 Error。
- **方法**：`feat026ArtifactMergeAppendLastChunkAndDeepImmutableDataPart()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.artifact-merge: Artifact 追加完成与深不可变")`、`@Tag("story-feat-026-streaming-artifact-merge")`。
- **DisplayName**：`Feat-026 Artifact 追加/完成/冲突降级与 DataPart 深不可变`。

### FEAT-026.streaming.recovery-partial - 断线恢复 PARTIAL 与幂等合并

- **状态/优先级**：runtime-artifact-gated, P0；**自动化状态**：design-only。
- **Story/来源**：L2 §4.7、§9.3。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的恢复约束：不消费 history、不重放 reducer 日志、当前 artifacts 与后续 live 帧重复幂等、一旦恢复不再回升 LIVE、不使用 RECOVERED_REPLAYED。
- **G**：真实 Agent 流式调用可在非终态中断；在 client-Gateway 间用 `FaultLink.resetPeer()` 制造断点；断点前已建 LIVE 树（root→search output）。
- **W**：在非终态断流触发恢复；恢复后再投递与断前重复的 search output，随后投递新的 verify output；并额外断言一条含普通 Task.history 的恢复响应不参与构树。
- **T**：
  - 断线即 completeness 降为 PARTIAL，后续不再回升 LIVE；
  - 重复的 search output 幂等合并，不重复追加 parts/建边、revision 不因重复输入回退或暴增；
  - 新 delegation 在 PARTIAL 树上继续扩展原树而非重建；
  - 含普通 history 的响应不导致树节点凭空增加。
- **不应断言**：client 内部 reducer 操作日志结构、固定恢复重试次数。
- **失败归类**：PARTIAL/幂等/history 误用不符为 Failure；正式依赖/FaultLink 缺失为 Skipped；故障代理异常为 Error。
- **方法**：`feat026RecoveryMarksPartialAndMergesCurrentArtifactsIdempotently()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.recovery-partial: 断线恢复 PARTIAL 与幂等合并")`、`@Tag("story-feat-026-streaming-recovery-partial")`。
- **DisplayName**：`Feat-026 断线恢复标记 PARTIAL 且重复帧幂等合并不回升 LIVE`。

### FEAT-026.streaming.child-input-boundary - 子 INPUT_REQUIRED 不结算根

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.4、§4.6、§9.4。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的状态单调性与并行 INPUT_REQUIRED 边界：子 input 只更新子节点并移出活跃集合、不结算根 Call；根 INPUT_REQUIRED 前发布最终子节点状态；缺少根等待点时不伪造 pending/toolCallId。
- **G**：同一栈；用 search-agent 的多轮追问 fixture（参照 `MultiTurnSearchFollowup` 的 INPUT_REQUIRED 路径）使 search 进入 input_required，同时 verify 仍可 output。
- **W**：订阅 `callTree()`；让 search status(input_required) 到达、verify 继续 output、再让 root 进入 INPUT_REQUIRED；观察最终树快照与根 Call 结算时机。
- **T**：
  - search 节点 state=input_required，verify 仍活跃，root state 未进入终态；
  - 根 INPUT_REQUIRED 到达前 `callTree()` 发布包含所有子节点最新状态的最终 revision；
  - 缺少根标准 INPUT_REQUIRED 等待点时，快照不伪造 pending toolCallId（pending 解析属 FEAT-006，本例只断不伪造）；
  - 迟到的非终态 status 在已见终态后被忽略并记录诊断。
- **不应断言**：固定追问文案、pending toolCallId 解析算法、FEAT-006 批量答案流程细节。
- **失败归类**：子 input 误结算根/最终树缺失/伪造 pending 为 Failure；确定性 LLM/正式制品缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat026ChildInputRequiredUpdatesChildWithoutSettlingRoot()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.child-input-boundary: 子 INPUT_REQUIRED 不结算根")`、`@Tag("story-feat-026-streaming-child-input-boundary")`。
- **DisplayName**：`Feat-026 子节点 input_required 只更新子节点不结算根调用`。

### FEAT-026.streaming.publisher-resource - Publisher 背压与资源降级

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.8、§9.5。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的发布规则与固定资源基线：实际变化才增 revision、晚订阅得最新值、慢订阅者只保留最新不阻塞 SSE 线程、超限 DEGRADED、多 invocation 隔离。
- **G**：同一栈；准备两个并发 invocation（不同 conversation）；准备一个阻塞型慢订阅者与一个晚订阅者；mock 产出超大 TextPart（>2MiB）与超 256 节点序列。
- **W**：并发发起两调用；对调用 A 先订阅后让慢订阅者阻塞 onNext、再让调用 B 晚订阅；投递超大与超节点 fixture；观察 revision、completeness 与两调用隔离。
- **T**：
  - 晚订阅者立即获得当前最新快照；
  - 慢订阅者阻塞不占用 SSE 读取线程、不影响另一 invocation 或其他订阅者；
  - 重复输入不增加 revision、revision 单调；
  - 超大 Artifact 截断且 completeness=DEGRADED、超节点保留已有拓扑并降级、根调用不因此 FAILED；
  - 两 invocation 的 speaker/diagnostics/buffer 不串。
- **不应断言**：固定 drain 任务数实现、定时 debounce 是否引入（L2 明确本版本不引入）。
- **失败归类**：背压/隔离/降级不符为 Failure；慢订阅阻塞型 helper 缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat026PublisherBackpressureResourceDegradationAndInvocationIsolation()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.publisher-resource: Publisher 背压与资源降级")`、`@Tag("story-feat-026-streaming-publisher-resource")`。
- **DisplayName**：`Feat-026 调用树 Publisher 背压隔离与超限降级不失败根调用`。

### FEAT-026.streaming.malformed-input - 畸形输入与诊断不中断

- **状态/优先级**：runtime-artifact-gated, P2；**自动化状态**：design-only。
- **Story/来源**：L2 §4.2、§7、§9.1、§9.6。
- **测试类型**：contract。
- **Oracle 来源**：L2 的诊断表与不变量：环/多父/缺字段/未知 type/status 只诊断并 DEGRADED、不把成功根调用改成 FAILED。
- **G**：`MockRemoteAgentServer` 顶替下游 search/verify，产出环边（target 反指 root）、多父边、缺 source 的 delegation、未知 agentEvent.type、未知 status 值与超大内容帧；其余帧来自真实 Agent。
- **W**：订阅 `callTree()` 与事件流；消费完整畸形序列至终态。
- **T**：
  - 环边与多父边被忽略、diagnostics 增加且 completeness=DEGRADED；
  - 缺字段与未知 type 的 agentEvent 被忽略并诊断、不中断调用；
  - 未知 status 保留原字符串、已见终态后迟到非终态不回退；
  - 根调用最终仍 COMPLETED，不因树解析错误变 FAILED。
- **不应断言**：真实 runtime 会主动产生未知 type（代理只改写 fixture，不证明真实 Agent 语义）。
- **失败归类**：诊断/降级/根误失败不符为 Failure；mock 未产出畸形 fixture 为 Skipped；代理异常为 Error。
- **方法**：参数化 `feat026MalformedInputDiagnosedWithoutFailingRoot()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-026.streaming.malformed-input: 畸形输入与诊断不中断")`、`@Tag("story-feat-026-streaming-malformed-input")`。
- **DisplayName**：`Feat-026 环多父缺字段未知类型只诊断不中断根调用`。

### FEAT-026.contract.mode-exclusion - BLOCKING/ASYNC 无调用树

- **状态/优先级**：deferred, P2；**自动化状态**：design-only。
- **Story/来源**：L2 §2.3.3、§2.2、§9.1。
- **测试类型**：contract。
- **Oracle 来源**：L2 明确 BLOCKING/ASYNC 不创建 reducer、根占位树或树 Publisher；`callTree()` 返回无快照的完成 Publisher、`InvocationSnapshot.callTree` 为 null；不使用 UNAVAILABLE_FOR_MODE 生成占位树。
- **G**：同一栈；业务应用分别以 BLOCKING 与 ASYNC 模式发起调用。
- **W**：订阅 `callTree()` 并取最终 `InvocationSnapshot`。
- **T**：
  - BLOCKING/ASYNC 的 `callTree()` Publisher 立即完成、不发布任何 CallTreeSnapshot；
  - `InvocationSnapshot.callTree` 为 null；
  - 不出现 UNAVAILABLE_FOR_MODE 占位树或 PARTIAL best-effort 树。
- **不应断言**：BLOCKING/ASYNC 的帧取得与恢复实现（属 FEAT-006）。
- **失败归类**：出现占位树/非 null callTree 为 Failure；L2 代码路径未落地为 deferred（不报 Skipped）。
- **方法**：参数化 `feat026NonStreamingModesPublishNoCallTree()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-026.contract.mode-exclusion: BLOCKING/ASYNC 无调用树")`、`@Tag("story-feat-026-contract-mode-exclusion")`。
- **DisplayName**：`Feat-026 BLOCKING/ASYNC 不发布调用树快照且 snapshot.callTree 为 null`。

### FEAT-026.contract.provider-fixture - Runtime 生产者 delegation/output/status fixture

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：automated。
- **Story/来源**：L2 §9.7。
- **测试类型**：contract。
- **Oracle 来源**：L2 §9.7 要求 Client 与 Runtime 共同维护 delegation/output/status、controller_output、根输出、append/lastChunk、artifactId 全局唯一与子/根 INPUT_REQUIRED 时序 fixture，作为双方 L2 的协议验证材料。
- **G**：真实 deep-research + search + verify 栈；`A2aEventCollector` 以 A2A SDK 直连消费 root 的 SSE 流（不经正式 agent-client，作为独立 fixture 捕获通道）。
- **W**：发起 COMPARISION 调用；收集完整事件流并经 `InboundEvent.raw`（ClientEvent）解析 artifact.metadata.agentEvent 的 type/source/target/state 与 controller_output 标识；固化为可复用 fixture。
- **T**：
  - 捕获到真实 delegation（source=root,target=search/verify）、output（source=search/verify）、status 序列，source.taskId 与外层 rootTaskId 区分正确；
  - fixture 可作为 Runtime 生产者 L2 的协议验证材料，供 client 侧 reducer 单测复用；
  - `RemoteInvocationProbe.hasFanOut` 证明 root 真实批量并行委托（前置证据，非树归并断言）。
- **不应断言**：该 fixture 证明 client 侧 CallTreeReducer 归并正确（归并由正式 agent-client 用例覆盖）。
- **失败归类**：wire 序列缺失关键字段为 Failure；真实 Agent/密钥缺失为 Skipped；采集异常为 Error。
- **方法**：`feat026CaptureRuntimeProducerDelegationOutputStatusFixture()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-026.contract.provider-fixture: Runtime 生产者 fixture 捕获")`、`@Tag("story-feat-026-contract-provider-fixture")`。
- **DisplayName**：`Feat-026 捕获 multi-deep-research 真实 delegation/output/status 作为生产者 fixture`。

### FEAT-026.streaming.controller-output - controller_output 语义与根阶段切换

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.3.3、§4.5、§9.1、§9.2。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的 controller_output 语义：通过 `parts[].data.type == "controller_output"` 识别，清空下游活跃集合、将根加入 activeSpeakers、SpeakingPhase 切为 ROOT_SPEAKING；`all_tasks_processed` 等控制文本不进入根 outputText；controller_output 没有 source/target，不得伪造某条具体父子边的控制权返回。
- **G**：同一栈；`MockRemoteAgentServer` 产出 delegation（root→search）、search output（使下游活跃）、controller_output（`data.type=controller_output`，`all_tasks_processed`）、root 最终 output 与终态 fixture 序列。
- **W**：订阅 `callTree()`；消费 delegation + search output + controller_output + root output 序列；断言 controller_output 到达前后 speakingPhase、currentSpeaker 和根 outputText 的变化。
- **T**：
  - search output 后 speakingPhase=DESCENDANT_SPEAKING（下游活跃）；
  - controller_output 到达后 speakingPhase 切为 ROOT_SPEAKING、currentSpeaker 切回根节点；
  - controller_output 的 `all_tasks_processed` 控制文本不出现在根 outputText 中；
  - controller_output 不导致 root.children 新增节点或修改已有父子边（不伪造边返回）；
  - 根调用最终 COMPLETED。
- **不应断言**：controller_output 的 data 字段内部 schema、activeSpeakers 集合字段名（目标 ⬜，以 currentSpeaker 等价占位）。
- **失败归类**：speakingPhase 不切换/根输出被污染/伪造边返回为 Failure；mock 未产出 controller_output fixture 为 Skipped；夹具异常为 Error。
- **方法**：`feat026ControllerOutputClearsDownstreamAndRestoresRootPhaseWithoutPollutingOutput()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.controller-output: controller_output 语义与根阶段切换")`、`@Tag("story-feat-026-streaming-controller-output")`。
- **DisplayName**：`Feat-026 controller_output 清空下游恢复根阶段且不污染根输出`。

### FEAT-026.streaming.orphan-buffer-merge - output/status 早于 delegation 的 orphan 归并

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.2、§4.3.1、§9.1。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的 orphan buffer 语义：output/status 早于 delegation 时进入有界 orphan-output buffer，父边到达后归并到正确节点；无法在资源上限内归属时记录诊断并将树标记 DEGRADED。
- **G**：同一栈；`MockRemoteAgentServer` 先投递 search output（source=search-agent, taskId=searchTask，此时 delegation 尚未到达），后投递 delegation（root→search），再投递 root output 和终态。
- **W**：订阅 `callTree()`；消费先 output 后 delegation 的乱序序列；断言 orphan buffer 归并后 search 节点正确挂载到 root.children 下且 artifacts 归属正确。
- **T**：
  - 先到的 search output 在 delegation 到达后被归并到正确节点（search-agent 子节点含该 output artifact）；
  - 最终快照 root.children 含 search-agent 子节点且 artifacts 列表非空；
  - revision 单调递增（orphan 归并增加 revision）；
  - 若再投递超 512 条 orphan output（超出 orphan buffer 上限），新增 orphan 被丢弃、diagnostics 含 UNRESOLVED_ORPHAN 等价诊断、completeness=DEGRADED。
- **不应断言**：client 内部 orphan buffer 容量实现、orphan buffer 内部数据结构。
- **失败归类**：orphan 未归并/节点未挂载/artifacts 丢失为 Failure；mock 未产出乱序 fixture 为 Skipped；夹具异常为 Error。
- **方法**：`feat026OrphanBufferMergeReconcilesEarlyOutputAfterDelegationArrives()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.orphan-buffer-merge: output/status 早于 delegation 的 orphan 归并")`、`@Tag("story-feat-026-streaming-orphan-buffer-merge")`。
- **DisplayName**：`Feat-026 output 早于 delegation 时 orphan buffer 归并后正确挂载`。

### FEAT-026.streaming.artifact-replace - Artifact 替换语义与 UTF-8 字节预算

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.3.2、§4.8.2、§9.1、§9.6。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的 Artifact 合并语义：append=false 替换该 Artifact 当前 Parts（非追加）；字节上限按 UTF-8 编码后的实际内容近似计量；DataPart 中 Map、Iterable 和数组必须递归复制并计入预算。
- **G**：同一栈；`MockRemoteAgentServer` 产出同一 artifactId 的先 append=true（追加 chunk1）再 append=false（替换为 chunk2）序列；另产出含多字节 UTF-8 字符的 TextPart 和含深层嵌套 Map/List 的 DataPart fixture。
- **W**：订阅 `callTree()`；消费 append/replace 序列；断言 Parts 被替换而非追加。
- **T**：
  - append=false 到达后该 Artifact parts 只含替换后的内容（chunk2），不含被替换的 chunk1；
  - UTF-8 多字节字符的 TextPart 字节长度被正确计入预算（超 2MiB 时截断并 DEGRADED）；
  - 含深层嵌套 Map/List/Iterable/数组的 DataPart 被递归复制计入字节预算。
- **不应断言**：client 内部字节计量实现细节、DataPart freeze 方法内部实现。
- **失败归类**：replace 未生效/Parts 仍含旧内容为 Failure；mock 未产出 replace fixture 为 Skipped；夹具异常为 Error。
- **方法**：`feat026ArtifactReplaceReplacesPartsAndUtf8ByteBudgetCountsDeepStructures()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.artifact-replace: Artifact 替换语义与 UTF-8 字节预算")`、`@Tag("story-feat-026-streaming-artifact-replace")`。
- **DisplayName**：`Feat-026 append=false 替换 Parts 且 UTF-8 字节预算计入深层结构`。

### FEAT-026.streaming.publisher-edge-cases - Publisher 尾帧/过载/异常隔离

- **状态/优先级**：runtime-artifact-gated, P1；**自动化状态**：design-only。
- **Story/来源**：L2 §4.8.1、§9.5。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 的发布规则与背压边界：terminal/根 INPUT_REQUIRED/close 前强制发布最终快照、dispatch 队列过载时只取消过载订阅者并报告明确错误、onNext 阻塞或回调异常不得占用 SSE 读取线程或影响其他订阅者、orphan 和总字节超限正确 DEGRADED。
- **G**：同一栈；`MockRemoteAgentServer` 产出含终态的完整 fixture 序列；准备一个 onNext 中抛异常的异常订阅者和一个正常晚订阅者；另准备超 512 orphan edge 和超 8 MiB 总内容的 fixture。
- **W**：发起调用；先订阅异常订阅者（onNext 抛 RuntimeException），再让晚订阅者订阅；投递超限 orphan edge 和超限总内容 fixture；断言异常隔离、尾帧不丢和降级行为。
- **T**：
  - 异常订阅者的 onNext 抛出异常后，不阻塞 SSE 读取线程、不影响其他订阅者接收快照；
  - terminal/close 前发布的最终快照被晚订阅者收到（尾帧不丢）；
  - dispatch 过载时只取消对应过载订阅者（其他订阅者继续正常工作）；
  - orphan edge 超 512 上限时新增 orphan 被丢弃、completeness=DEGRADED；
  - 总内容超 8 MiB 时截断内容保留拓扑、completeness=DEGRADED。
- **不应断言**：固定 drain 任务数实现、dispatch 线程池结构。
- **失败归类**：异常不隔离/尾帧丢失/过载取消错误订阅者/超限未降级为 Failure；异常型 helper 缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat026PublisherEdgeCasesTerminalFlushOverloadCancellationAndExceptionIsolation()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-026.streaming.publisher-edge-cases: Publisher 尾帧/过载/异常隔离")`、`@Tag("story-feat-026-streaming-publisher-edge-cases")`。
- **DisplayName**：`Feat-026 Publisher 尾帧不丢且过载异常隔离降级正确`。

### FEAT-026.contract.api-compatibility - API 二进制兼容性检查

- **状态/优先级**：deferred, P2；**自动化状态**：design-only。
- **Story/来源**：L2 §9.7。
- **测试类型**：contract。
- **Oracle 来源**：L2 §9.7 要求 `InvocationCall.callTree()` default method、`InvocationSnapshot` 旧构造器和旧自定义 Transport 必须通过 Revapi/japicmp 或等价检查，并用旧版编译产物做运行兼容测试。
- **G**：SDK 当前版本 jar 与旧版编译产物（如有）；兼容性检查工具（Revapi/japicmp）。
- **W**：运行兼容性检查工具对比 SDK 公共 API 表面；用旧版编译产物做运行兼容测试。
- **T**：
  - `callTree()` 作为 default method 不破坏已有第三方 `InvocationCall` 实现；
  - `InvocationSnapshot` 旧构造器仍可用（新增 `callTree` 参数时保留旧构造器并默认 null）；
  - 旧自定义 Transport 实现仍可运行。
- **不应断言**：内部实现类签名、非 public API 表面。
- **失败归类**：兼容性破坏为 Failure；兼容性检查工具未引入为 deferred（不报 Skipped）。
- **方法**：`feat026ApiCompatibilityPreservesDefaultMethodLegacyConstructorsAndTransport()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-026.contract.api-compatibility: API 二进制兼容性检查")`、`@Tag("story-feat-026-contract-api-compatibility")`。
- **DisplayName**：`Feat-026 callTree default method 和旧构造器保持二进制兼容`。

## 5. 文件、执行与退出标准

计划新增一个黑盒测试文件与一个 fixture 捕获类：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/
  MultiHopCallTreeBlackboxTest.java
  RuntimeProducerCallTreeFixture.java
```

复用既有 `BaseManagedStackTest` + `SutStack`（`.downstreams(SEARCH, VERIFY)` + `deep-research-auto` remote-agents-prefix）与 `InteractionFlow`/`A2aEventCollector`/`MockRemoteAgentServer`/`RemoteInvocationProbe`；不新增自有 mock 框架。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；Maven 本地仓库默认 `~/.m2/repository`。deep-research/search/verify JAR 坐标见 §1.1。正式 `agent-client` SDK（`com.openjiuwen:agent-client-sdk-for-jvm:0.1.0`）已交付且已作为 acceptance Maven 依赖；`callTree()` 公共 API（`InvocationCall.callTree()` → `Flow.Publisher<CallTreeSnapshot>`）、`GatewayTransportProvider`、`RuntimeTransportProvider` 均已实现。`application-openjiuwen.yml` 中 Gateway 制品（`com.openjiuwen:agent-gateway:0.1.0`）已配置（line 67-78）。当前门禁为 Runtime 侧 `artifact.metadata.agentEvent` 发射状态：SDK 的 `CallTreeReducer` 从 A2A artifact 的 `metadata.agentEvent` 字段提取 delegation/output/status 事件建树，若 Runtime 未在 artifact metadata 中写入 `agentEvent`，则 `callTree()` Publisher 不发布快照。不得把 acceptance helper 或 `org.a2aproject.sdk` 解析的 wire 序列放入正式 client `callTree()` 的位置宣称归并通过。

除 `DEEPSEEK_API_KEY`/`LLM_API_BASE`/`LLM_MODEL` 等标准密钥外，COMPARISION 查询文本、vendor 标记、mock fixture 帧与唯一 canary 由测试资源自动准备。测试结束必须关闭 client、Agent 进程与 mock、恢复 `FaultLink`、删除临时目录并确认占用端口释放。落地后执行：

```bash
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-026 test
# Story 示例
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-026-streaming-two-hop-tree test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-026-streaming-recovery-partial test
```

退出标准：当前 L2 可交付用例通过或具有明确门禁；所有 Feature MUST（§9.1–9.7）已直接覆盖或标为 deferred；无 helper/fake 核心链路通过、无固定 LLM 文本 Oracle、无 wire 字面串冒充 `callTree()` 快照、无敏感信息与进程/端口泄漏。L2 ⬜ 目标（多活跃 speaker 集合、DataPart 深复制、BLOCKING/ASYNC 占位树移除、API 兼容性检查工具引入）以 expected-red/deferred 形式留作实现驱动，不计为本迭代通过门禁。补充用例覆盖 L2 §9 遗漏项：controller_output 语义（§9.1/§9.2）、orphan buffer 归并（§9.1）、append=false replace（§9.1）、Publisher 尾帧/过载/异常隔离（§9.5）、SPEAKING_HIERARCHY_VIOLATION（§9.2 扩展至 malformed-input）、UTF-8 字节预算与深层递归复制（§9.6）、API 兼容性检查（§9.7）。
