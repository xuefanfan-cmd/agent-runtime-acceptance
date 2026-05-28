# spring-ai-ascend 平台 SIT（系统集成测试）设计文档

> 版本：v1.0  
> 基于：ARCHITECTURE.md（freeze_id: W1-russell-2026-05-14）  
> 适用范围：L0→L1 架构落地验证，覆盖 W0 已交付能力 + W1/W2 设计契约预验证  
> 文档性质：架构一致性守门文档，供架构组、测试组、工程实施组三方协同使用

---

## 1. 目标愿景与受众分析

### 1.1 目标愿景

本 SIT 测试设计文档的核心愿景是：**以测试作为架构落地的"探针"和"约束器"，在 L0→L3 的每一层建立"设计-实现-验证"的闭环，让集成测试成为架构一致性的守门人，而非仅仅是编码完成后的检查活动。**

具体而言，SIT 测试承担以下三重使命：

1. **子链路穿透**：验证核心业务流程的局部链路中，数据流完整性、交互正确性、异常降级路径是否符合架构设计。
2. **端到端场景验证**：验证完整业务场景下，架构目标是否达成，包括 C/S Hydration、三轨隔离、Swarm 执行等关键能力。
3. **性能竞争力验证**：验证平台在 OS 级亲和、异构计算穿透、内存穿透等关键竞争力维度上的能力边界，确保架构设计中的性能承诺可被度量、可被复现。

### 1.2 受众分析

| 受众 | 角色 | 关注点 | 本文档价值 |
|-----|------|-------|-----------|
| **Audience A — 平台内部贡献者** | 架构师、SPI 设计者、Gate Rule 维护者 | SPI 纯度、依赖方向、§4 约束遵守情况 | 提供模块间验证的精确路径，确保 ADR-0068 Layered 4+1 落地 |
| **Audience B — 外部 Spring 开发者** | 集成平台的 Spring Boot 开发者 | `agent-client` SDK、`ChatClient` / `VectorStore` 适配、quickstart 可用性 | 验证 W2/W3 对外暴露面的功能正确性与开发者体验 |
| **Audience C — 自托管运维方** | regulated-industry 自托管运维 | 部署拓扑、FIPS 构建、可观测性、可靠性、SLA | 验证三轨通道隔离、Posture 模型、Telemetry Vertical 等运维关键能力 |

---

## 2. 模块关系分析与 SIT 组合策略

### 2.1 模块拓扑全景

基于 ARCHITECTURE.md §2 的 8 模块状态（6 个实质模块 + BoM + graphmemory starter），模块间的依赖与调用关系如下：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Edge Plane                                      │
│  agent-client ──────► agent-bus.spi.ingress (IngressGateway)               │
│  (SDK skeleton, W3+)          [C2S 跨平面入口]                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Bus & State Hub Plane                              │
│  agent-bus ──────► bus.spi.ingress (C2S)                                   │
│            ──────► bus.spi.s2c (S2C CallbackTransport)                     │
│            ──────► WorkflowIntermediary + Three-Track Bus                  │
│            [Track 1: Control | Track 2: Data P2P | Track 3: Rhythm]        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│   Compute Control    │  │   Compute Control    │  │   Compute Control    │
│   agent-service      │  │ agent-execution-engine│  │  agent-middleware    │
│  ├─ platform (HTTP)  │  │  ├─ engine.spi       │  │  ├─ HookDispatcher   │
│  ├─ runtime (kernel) │  │  ├─ orchestration.spi│  │  ├─ HookPoint (enum) │
│  ├─ runs/idempotency │  │  │   (RunMode/Check-  │  │  └─ RuntimeMiddleware│
│  └─ memory.spi       │  │  │   pointer/RunCtx/  │  │                      │
│                      │  │  │   SuspendSignal...)│  │                      │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
         │                         │                         │
         └─────────────────────────┼─────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Evolution Plane (W3+)                               │
│  agent-evolve ◄───── agent-bus (S2C 轨迹事件)                              │
│  (Python ML pipeline; Java adapter skeleton)                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

**关键依赖方向**（§4 #1，由 ArchUnit 强制）：
- `agent-service` → `agent-execution-engine`, `agent-bus`, `agent-middleware`
- `agent-execution-engine` → `agent-middleware`, `agent-bus` (`bus.spi.s2c`)
- `agent-middleware` → externals only（纯 Java SPI，无内部 peer 依赖）
- `agent-bus` → no inner peer（仅 `java.*` + 最小外部依赖）
- `agent-client` → `agent-bus.spi.ingress`（**禁止**直接连 compute_control）
- `spring-ai-ascend-graphmemory-starter` → `agent-service` SPI surfaces

### 2.2 组合策略：三层验证体系

| 验证层级 | 模块叠加数 | 测试对象 | 验证目标 | 是否必须 |
|---------|-----------|---------|---------|---------|
| **子链路层** | 3~4 模块 | 核心业务流程的局部链路 | **数据流完整性** + **交互正确性** — 跨模块状态一致性、异常降级 | ✅ 必须 |
| **端到端层** | 5~6 模块 | 完整业务场景（含 evolve 反馈环） | **端到端功能** + **架构目标达成** — C/S Hydration、三轨隔离、Swarm 执行 | ✅ 必须 |
| **性能竞争力层** | 全栈 + OS/硬件 | 平台在关键竞争力维度上的能力边界 | **异构穿透**、**内存穿透**、**OS 级亲和**、**延迟/吞吐/能效** | ✅ 必须 |

**不做的事**：
- 不将 `agent-middleware` 作为独立节点串链（横切模块采用"切面注入"模式验证）。
- 性能层不做功能正确性验证（功能正确性由子链路和端到端层覆盖），仅做性能边界与竞争力度量。

---

## 3. 各组合测试展开方法

### 3.1 第一层：子链路层 — 数据流与交互正确性

**原则**：从最上游真实入口注入，穿透中间模块，不拆开单独调内部接口。

| 编号 | 子链路 | 涉及模块 | 注入点 | 测试场景 | 验证目标 |
|-----|-------|---------|-------|---------|---------|
| **SIT-SC-01** | **C2S 请求入链** | `agent-client` → `agent-bus` → `agent-service` | `agent-client` HTTP 入口 或直接向 `IngressGateway` 发 `IngressEnvelope` | 用户发起对话/任务 → Bus 路由 → Service 接收并创建 `Run` | C/S Dynamic Hydration Protocol：TaskCursor / BusinessRuleSubset / SkillPoolLimit 是否正确解析并传入 `RunContext`（§4 #47 / ADR-0049） |
| **SIT-SC-02** | **编排执行子链路** | `agent-service` → `agent-middleware` → `agent-execution-engine` | `agent-service` 的 `SyncOrchestrator.executeLoop()` | 单轮 Agent 执行：规划 → Hook 链 → 引擎执行 → 结果返回 | Hook 链顺序（10 位置全触发）、`SuspendSignal` 中断传播、`SkillResourceMatrix` 限额预检（§4 #12 / #27 / ADR-0030） |
| **SIT-SC-03** | **执行反馈子链路** | `agent-execution-engine` → `agent-bus` → `agent-evolve` | 在 engine 中构造执行轨迹，触发 S2C 回调 | 工具执行结果 → Bus 传输 → Evolve 消费并更新策略 | S2C 轨迹数据完整性、tenant 隔离、`BusinessFactEvent` / `OntologyUpdateCandidate` 语义正确性（§4 #49 / ADR-0051） |
| **SIT-SC-04** | **记忆存取子链路** | `agent-service` → `spring-ai-ascend-graphmemory-starter` | `agent-service` 的 `GraphMemoryRepository` SPI 调用点 | 多轮对话中的 M4 Graph 记忆读写 | tenant-scoped 图节点/边存取、`MemoryMetadata` schema 一致性、retentionExpiry 生效（§4 #31 / ADR-0034） |
| **SIT-SC-05** | **跨平面任务分发** | `agent-bus` → `agent-service` → `agent-execution-engine` | `WorkflowIntermediary` 的 `AdmissionDecision` 入口 | Bus 接收任务 → Service 准入控制 → Engine 执行 | `AdmissionDecision`（Accepted/Delayed/Rejected/Yielded）、BackpressureSignal 传播、三轨隔离不混流（§4 #48 / ADR-0050） |

**横切模块 `agent-middleware` 的验证方式**：
在 SIT-SC-02 和 SIT-SC-05 执行过程中，从旁路输出观测 middleware 行为：
- 断言 `TraceContext` 是否正确携带 `trace_id` / `span_id` / `tenant_id`。
- 断言 `HookDispatcher` 是否向监控指标上报 `springai_ascend_*`。
- 注入超限请求，断言是否返回 429 / 触发熔断（`ResilienceContract` 路由）。
- 断言 `PiiRedactionHook` 是否在 research/prod posture 下生效（§4 #58）。

**测试效果**：
- 验证跨 3~4 个模块的数据状态一致性（如 `Run` 状态从 `PENDING` → `RUNNING` → `SUSPENDED` 的 DFA 合法转换）。
- 验证局部异常降级（如 engine 超时后 service 是否触发 `SuspendSignal` 而非崩溃）。
- 验证横切关注点的正确介入（日志、监控、限流、PII 脱敏）。

---

### 3.2 第二层：端到端层 — 完整业务场景与架构目标

**原则**：唯一入口（`agent-client` 或等效 C2S 入口），唯一出口（最终返回或 `agent-evolve` 状态更新），中间全黑盒，仅通过 Trace/日志/指标观测。

| 编号 | 全链路场景 | 6 模块参与方式 | 注入方式 | 观测出口 | 验证目标 |
|-----|-----------|--------------|---------|---------|---------|
| **SIT-E2E-01** | **标准 Agent 生命周期** | client → bus → service → middleware → execution-engine → bus → client | `POST /v1/runs`（或 W3+ 的 SDK 等效调用） | 同步 `Object` 返回 或 `YieldResponse` | 端到端功能：创建 Run → 执行 → 完成/挂起 → 返回结果；总耗时 < 3s（W2 SLA） |
| **SIT-E2E-02** | **带自学习的多轮对话** | 上述链路 + evolve 接收轨迹并更新 | 同 E2E-01，第二轮携带 sessionId | `agent-evolve` 的策略更新状态 + 第二轮回答质量提升 | 端到端功能：`agent-evolve` 的反馈环生效；`PlaceholderPreservationPolicy` 占位符在 LLM 全链路中不被解析（§4 #49 / ADR-0051） |
| **SIT-E2E-03** | **Swarm 多 Agent 协作** | client → bus → service → execution-engine → bus → service（另一实例）→ ... → evolve | 父 Run 发起 `SpawnEnvelope` | 子 Run 完成 + 父 Run `JoinPolicy`（ALL/ANY/N_OF）聚合 | 端到端功能：15 维 `SpawnEnvelope` 传播完整性、child-tenant 相等性、ancestor 链无环（§4 #51 / ADR-0053 / ADR-0107） |
| **SIT-E2E-04** | **故障降级端到端** | 全链路 + 注入 engine 故障（Stub LLM 超时/工具失败） | 同 E2E-01 | 返回 `BusinessDegradationRequest`（YieldResponse）或友好错误 | 端到端功能：S-side 降级权威红线 — 可替换工具/模型，但不可篡改 C-side 目标；`GoalMutationProhibition` 生效（§4 #47 / ADR-0049） |
| **SIT-E2E-05** | **长连接资源 containment** | 全链路 + 并发多 client 请求 | 并发 `POST /v1/runs` | 系统无连接泄漏、fd 不耗尽、in-flight Run 数可控 | 架构目标：`LogicalCallHandle` 与物理连接解耦、`SuspendInsteadOfHold` 生效、三轨通道不混流（§4 #52 / ADR-0054） |
| **SIT-E2E-06** | **三轨通道隔离验证** | 全链路 + 大 Payload 数据传输 + 控制命令并发 | 同时发送大数据 P2P 传输 + 大量 PAUSE/KILL 控制命令 | 控制命令不被数据流阻塞、心跳/Rhythm track 独立存活 | 架构目标：Track 1 Control / Track 2 Data P2P / Track 3 Rhythm 物理隔离，不重现 whitepaper §5.2 的网络拥塞故障（§4 #46 / #48 / ADR-0048 / ADR-0050） |

**测试效果**：
- 验证端到端业务目标达成（C/S Hydration、Swarm 执行、Memory Ownership Boundary）。
- 验证架构级质量属性（SLA、并发安全、可靠性、可用性）。
- 验证 W0→W4 分阶段交付的契约一致性（如 W0 的 `RunStateMachine` DFA 在全链路中不被破坏）。

---

### 3.3 第三层：性能竞争力层 — OS 级亲和与异构穿透

**原则**：性能层不做功能正确性验证（由子链路和端到端层覆盖），仅度量平台在关键竞争力维度上的能力边界。测试环境必须贴近生产硬件拓扑（Ascend/Kunpeng 或等效异构环境）。

#### 3.3.1 竞争力维度定义

| 竞争力维度 | 架构定义 | 测试目标 | 关联 ADR/约束 |
|-----------|---------|---------|--------------|
| **异构计算穿透** | 平台支持异构硬件（Ascend NPU / Kunpeng CPU / GPU）的透明调度，Agent 执行引擎可自动选择最优计算单元 | 验证 LLM 推理、向量计算、工具执行在不同硬件上的调度延迟与吞吐差异 | ADR-0117（硬件协同）、§4 #30（部署位置词汇） |
| **内存穿透** | 支持跨 JVM 边界的大内存直接访问（如通过 shared memory / mmap / RDMA），避免数据在 JVM 堆与 native 内存间反复拷贝 | 验证大 Payload（>16 KiB checkpoint、LLM 上下文、向量数据）的内存访问延迟与零拷贝能力 | §4 #13（Payload 寻址）、§4 #25（CausalPayloadEnvelope） |
| **OS 级亲和** | 支持 CPU 核心绑定（CPU affinity）、NUMA 感知调度、大页内存（hugepages）、内核旁路（如 io_uring / DPDK） | 验证高并发场景下 OS 调度策略对延迟 P99 和吞吐的影响 | §4 #52（长连接 Containment）、§4 #46（微服务架构承诺） |
| **能效比** | 单位请求的能量消耗（Watts per request / Tokens per Watt） | 验证异构硬件上的能效优势，确保平台在边缘部署场景的可行性 | ADR-0117（垂直无关 + 硬件协同） |
| **冷启动与弹性** | 从 0 实例到首请求响应的时间、水平扩容的收敛时间 | 验证 serverless-friendly SPI（§4 #46）在弹性场景下的实际表现 | §4 #46（SPI 原语 serverless-friendly） |

#### 3.3.2 性能竞争力测试场景

| 编号 | 测试场景 | 涉及模块 | 测试方法 | 度量指标 | 目标基线 |
|-----|---------|---------|---------|---------|---------|
| **SIT-PERF-01** | **异构 LLM 推理调度** | `agent-execution-engine` + 异构运行时（Ascend NPU / GPU） | 在同等模型（如 Qwen-7B）和同等输入下，分别调度到 Ascend NPU、Kunpeng CPU、GPU 执行推理 | 首 Token 延迟（TTFT）、每 Token 延迟（TBT）、总吞吐（tokens/s） | Ascend NPU TTFT < 500ms @ batch=1；TBT < 20ms/token |
| **SIT-PERF-02** | **大 Payload 内存穿透** | `agent-service` → `agent-execution-engine` → `Checkpointer` | 构造 1MB / 10MB / 100MB 的 checkpoint payload，验证 JVM 堆外内存（DirectBuffer / Unsafe / Panama）与 native shared memory 的穿透效率 | 序列化延迟、反序列化延迟、内存拷贝次数（通过 perf/ebpf 观测） | 100MB payload 序列化 < 100ms；零拷贝路径占比 > 90% |
| **SIT-PERF-03** | **NUMA 感知调度** | `agent-service` + `agent-execution-engine` + OS 调度器 | 在双路 Kunpeng 服务器上，绑定不同 NUMA 节点的 CPU 核心，执行并发 Run 创建与推理 | 跨 NUMA 访问延迟 vs 本地 NUMA 延迟比值、P99 端到端延迟 | 跨 NUMA P99 延迟 < 1.5x 本地 NUMA 延迟 |
| **SIT-PERF-04** | **CPU 核心亲和与隔离** | `agent-service` + `agent-execution-engine` + OS cgroup/scheduler | 使用 taskset/cgroup 将 Agent Service 实例绑定到特定 CPU 核心集，验证在核心抢占/隔离场景下的延迟稳定性 | 延迟标准差（σ）、P99/P50 比值、CPU 利用率波动 | P99/P50 < 2.0；σ < 50ms @ 1000 QPS |
| **SIT-PERF-05** | **三轨通道吞吐隔离** | `agent-bus` + `agent-service`（多实例） | 在 Track 2 Data P2P 上压测大文件传输（1GB），同时 Track 1 Control 发送高频控制命令（1000 TPS），观测 Track 3 Rhythm 心跳稳定性 | Track 2 吞吐（MB/s）、Track 1 命令延迟（ms）、Track 3 心跳丢失率 | Track 2 > 500MB/s；Track 1 < 10ms P99；Track 3 丢失率 = 0% |
| **SIT-PERF-06** | **能效基准** | 全栈 + 功耗采集 | 在固定负载（1000 QPS，平均 token 长度 512）下，采集整机功耗（IPMI / BMC / 智能 PDU） | 每请求能耗（J/request）、每 token 能耗（J/token）、每瓦特吞吐（requests/W） | 目标：每请求能耗 < 0.5J @ Ascend NPU；对比 GPU 基准提升 > 30% |
| **SIT-PERF-07** | **冷启动与弹性收敛** | `agent-service` + K8s HPA / 自定义弹性控制器 | 从 0 实例开始，突发注入 1000 QPS，观测首请求响应时间和实例扩容收敛时间 | 冷启动延迟（0→1 实例）、扩容收敛时间（1→N 实例）、弹性过程中的错误率 | 冷启动 < 30s（W2 目标）；扩容收敛 < 60s；弹性过程错误率 < 0.1% |
| **SIT-PERF-08** | **向量检索内存穿透** | `spring-ai-ascend-graphmemory-starter` + pgvector / 自研向量引擎 | 在 100M 向量（768 维）数据集上，验证 HNSW 索引的内存映射（mmap） vs 全内存加载的查询延迟与内存占用 | 查询延迟 P99（ms）、内存占用（GB）、索引加载时间（s） | mmap 模式 P99 < 50ms；内存占用 < 20GB（对比全内存 80GB） |

#### 3.3.3 性能测试环境要求

| 组件 | 要求 | 备注 |
|-----|------|------|
| **硬件** | Ascend 910B NPU / Kunpeng 920 CPU / 等效 GPU | 与生产硬件拓扑一致，禁止用 x86+GPU 替代做性能基线 |
| **OS** | openEuler 22.03 LTS SP3 / 等效 Linux | 内核版本 ≥ 5.10，启用 NUMA、 hugepages、io_uring |
| **JVM** | OpenJDK 21 + Shenandoah GC / ZGC | 验证 GC 暂停对 P99 延迟的影响 |
| **监控** | eBPF（bcc/bpftrace）、perf、Intel PCM / 华为 iBMC | 内核级性能事件采集，非侵入式 |
| **负载生成** | Locust / Gatling / 自研压测工具 | 支持自定义协议（HTTP/gRPC/MQ）、支持流量模型（恒定、突发、波浪） |

#### 3.3.4 性能测试与功能测试的边界

| 维度 | 子链路/端到端层 | 性能竞争力层 |
|-----|----------------|-------------|
| **验证目标** | 功能正确性、数据一致性、架构目标达成 | 能力边界、竞争力基线、OS/硬件亲和效率 |
| **通过标准** | 断言通过/失败 | 度量值 vs 基线阈值（可接受区间） |
| **环境要求** | 类生产环境（容器化、Testcontainers） | 真实生产硬件（Ascend/Kunpeng） |
| **Mock 策略** | 外部依赖 Mock（LLM、第三方 API） | 尽可能真实（真实 NPU 推理、真实内存访问） |
| **并发模型** | 功能并发（验证正确性） | 压力并发（验证极限与稳定性） |
| **失败处理** | 失败即阻塞发布 | 低于基线即触发架构优化或基线调整评审 |

---

## 4. 测试触发方式与协议原生复用

SIT 测试的触发方式**必须复用生产协议**，不能为测试创造私有通道。

| 模块形态 | 生产暴露协议 | SIT 触发方式 | 示例 |
|---------|-------------|-------------|------|
| HTTP/gRPC 入口（client/service） | `POST /v1/runs`, `GET /v1/health` | 直接 HTTP/GRPC 调用 | `curl -X POST http://client:8080/api/task` |
| MQ/事件总线（bus） | Kafka/NATS Topic / Exchange | 向 Topic 发消息 / 调 Publisher API | `kafka-console-producer` 或 Bus SDK 发 `IngressEnvelope` |
| Java SPI（engine/middleware） | `Orchestrator.execute()` / `HookDispatcher.dispatch()` | 直接 Java 方法调用（同 JVM 内） | `orchestrator.orchestrate(runContext, definition)` |
| 异步任务队列（engine） | 内部任务队列 / `RunDispatcher` SPI | 向队列投递任务 / 直接 RPC | `runDispatcher.enqueue(intent)` |
| 横切 Sidecar（middleware） | 无独立入口，嵌入调用链 | **不单独触发**，在链路中观测旁路输出 | 断言 MDC `trace_id` / Prometheus 指标 / 日志字段 |
| 纯消费者（evolve） | 消费 Bus Topic / 定时任务 | 向 evolve 的消费 Topic 发轨迹事件 | `kafka-console-producer` 发执行轨迹 JSON |
| 性能层（OS/硬件） | OS syscall / 设备驱动 / 内核接口 | eBPF 探针、perf 事件、/proc 读取 | `bpftrace -e 'kprobe:copy_user_enhanced_fast_string { @count = count(); }'` |

---

## 5. 两层一致性验证机制（架构一致性守门）

### 5.1 第一层：实现层（代码）↔ 设计层（MD 文档）

**目标**：暴露"代码没做到"或"文档没考虑到"的不一致，减少 AI 幻觉。

| 不一致类型 | 检测手段 | 暴露方式 | 负责方 |
|-----------|---------|---------|-------|
| **接口签名不一致** | 静态代码分析（AST）扫描代码接口 vs MD 文档中的接口定义 | 《接口偏差报告》 | 测试组暴露，架构组+开发组决策 |
| **依赖关系不一致** | 代码依赖分析（import 图、Maven `pom.xml`）vs 架构依赖矩阵（§4 #1） | 《依赖偏离图》 | 测试组暴露，架构组+开发组决策 |
| **数据流不一致** | 运行时 Trace（OpenTelemetry）vs 架构数据流图 | 《数据流偏差日志》 | 测试组暴露，架构组+开发组决策 |
| **异常处理不一致** | 代码异常捕获点扫描 vs 文档异常策略矩阵（§4 #19/#20） | 《异常盲区报告》 | 测试组暴露，架构组+开发组决策 |
| **配置项不一致** | 配置文件/代码注解 vs 文档配置清单 | 《配置漂移表》 | 测试组暴露，架构组+开发组决策 |
| **Posture 行为不一致** | 运行时行为观测 vs `docs/cross-cutting/posture-model.md` | 《Posture 偏离报告》 | 测试组暴露，架构组+开发组决策 |
| **性能基线偏离** | 性能层测试结果 vs 架构承诺的竞争力基线 | 《性能竞争力偏差报告》 | 测试组暴露，架构委员会决策 |

### 5.2 第二层：设计层（MD 文档）↔ 业务目的（系统大功能）

**目标**：验证"架构文档本身是否正确"。

| 验证维度 | 测试手段 | 暴露方式 | 负责方 |
|---------|---------|---------|-------|
| **功能完整性** | 端到端场景测试（E2E SIT） | 《功能盲区报告》 | 测试组暴露，架构委员会+产品负责人决策 |
| **架构目标达成度** | 质量属性测试：性能压测、容错注入、扩展性测试 | 《质量属性偏差报告》 | 测试组暴露，架构委员会决策 |
| **业务规则一致性** | 基于业务用例的集成测试 | 《规则断裂报告》 | 测试组暴露，产品负责人+架构委员会决策 |
| **演进一致性** | 架构变更影响分析 | 《架构僵化报告》 | 测试组暴露，架构委员会决策 |
| **竞争力基线达成** | 性能层测试 vs 承诺基线 | 《竞争力缺口报告》 | 测试组暴露，架构委员会+硬件团队决策 |

---

## 6. 测试效果与覆盖维度矩阵

### 6.1 功能测试覆盖

| 功能维度 | 子链路层 | 端到端层 | 性能竞争力层 | 验证手段 |
|---------|---------|---------|-------------|---------|
| **SDK 功能** | client→bus→service | client→...→client | — | SDK 调用链、IngressEnvelope 字段、ResumeEnvelope 解析 |
| **可观测性** | service→middleware (Hook SPI) | 全链路 Trace | — | `TraceContext` 传播、W3C `traceparent`、OTLP 格式 |
| **可维护性** | 全部 5 条子链路 | 全部 6 个场景 | — | ArchUnit 规则运行时验证、模块独立构建 |
| **资料文档一致性** | 全部 5 条 | 全部 6 个场景 | 全部 8 个场景 | 两层一致性验证机制 |
| **SPI 纯度** | middleware↔任何模块 | — | — | `SpiPurityGeneralizedArchTest` |
| **Posture 模型** | service→middleware→engine | 全链路 | — | `AppPostureGate` 行为验证 |

### 6.2 系统测试覆盖

| 系统维度 | 子链路层 | 端到端层 | 性能竞争力层 | 验证手段 |
|---------|---------|---------|-------------|---------|
| **SLA（延迟/吞吐）** | — | ✅ E2E-01/E2E-05 | ✅ PERF-01/PERF-03/PERF-04 | P90 端到端延迟 < 3s；异构推理 TTFT < 500ms |
| **并发安全** | — | ✅ E2E-05 | ✅ PERF-04 | 多 tenant 并发无数据串台；CPU 亲和下延迟 σ < 50ms |
| **安装/部署** | — | ✅ 全链路 | ✅ PERF-07 | 模块独立构建 + Docker Compose + K8s HPA |
| **可靠性** | service→middleware→engine | ✅ E2E-04 | — | 故障降级、熔断、Skill 超时释放资源 |
| **可用性** | service↔bus (health) | ✅ E2E-01 | ✅ PERF-07 | Health 探针、弹性冷启动 < 30s |
| **安全性** | client↔bus (JWT) | client→bus→service | — | JWT 交叉校验、tenant 标签隔离 |
| **容错性** | service→middleware→engine | ✅ E2E-04 | — | `ON_ERROR` hook、checkpoint 原子写、DFA 拦截 |
| **可扩展性** | — | ✅ E2E-06 | ✅ PERF-05 | 三轨通道隔离：数据 P2P 不阻塞控制命令 |
| **异构穿透** | — | — | ✅ PERF-01 | Ascend NPU / Kunpeng / GPU 调度效率对比 |
| **内存穿透** | — | — | ✅ PERF-02/PERF-08 | 零拷贝占比 > 90%；mmap 向量检索内存节省 |
| **OS 级亲和** | — | — | ✅ PERF-03/PERF-04 | NUMA 感知、CPU 核心绑定、大页内存 |
| **能效比** | — | — | ✅ PERF-06 | 每请求能耗 < 0.5J @ Ascend NPU |

---

## 7. 组织协同与信息暴露机制

### 7.1 协同流程

```
架构组输出 L0/L1 文档（ARCHITECTURE.md + per-module ARCHITECTURE.md + ADR YAML）
    ↓
测试组基于文档生成《SIT 验证规约》+《一致性检查清单》+《性能竞争力基线》
    ↓
开发组按 L1 契约编码 + 自测（单元测试 + 局部 SIT）
    ↓
【第一层验证】CI 自动扫描：代码 vs 文档 → 生成《偏差报告》
    ↓
测试组执行 SIT 分层测试（SC → E2E → PERF）→ 生成《功能/架构/竞争力偏离报告》
    ↓
【评审会】架构组 + 开发组 + 产品负责人 + 硬件团队（性能层），基于测试报告决策
    ↓
决策结果：修正文档 / 修正代码 / 调整基线 / 接受偏差（需记录技术债）
    ↓
【第二层验证】版本发布前，端到端验证文档 vs 业务目的 vs 竞争力承诺
```

### 7.2 测试组输出原则

- **只呈现事实**：代码做了什么、文档写了什么、业务要求什么、硬件测得什么。
- **只做归因**：偏差的可能原因（A/B/C），不做唯一性断言。
- **只给建议**：基于行业最佳实践的优化方向，不做强制要求。
- **不背决策**：所有修正决策由架构组或技术委员会在评审会上做出。

### 7.3 报告模板（示例）

> **不一致项 #A-07**
> - **类型**：接口契约偏离
> - **文档位置**：《L1-agent-service/ARCHITECTURE.md》第 3.2 节
> - **文档定义**：`execute_tool(tool_name, params) -> ToolResult`
> - **代码实现**：`execute_tool(name, args, timeout=30) -> dict | None`
> - **偏差分析**：
>   - 参数名不一致（`params` vs `args`）
>   - 返回类型不一致（`ToolResult` 对象 vs `dict`），导致调用方无法依赖类型安全
>   - 代码新增了 `timeout` 参数，文档未定义该配置透出点
> - **可能原因**：
>   - 原因 A：开发侧自行扩展，未同步文档
>   - 原因 B：架构侧未考虑超时策略的接口透出
> - **优化建议**：
>   - 建议 1：统一参数名为 `params`，并定义 `ToolResult` 结构体
>   - 建议 2：若 `timeout` 为必要参数，文档需补充其语义和默认值
> - **决策方**：架构组 + 开发组长
> - **测试组结论**：仅暴露偏差，不做架构决策

> **竞争力偏差项 #P-03**
> - **竞争力维度**：异构计算穿透 — Ascend NPU 推理延迟
> - **架构承诺**：TTFT < 500ms @ batch=1，Qwen-7B
> - **实测结果**：TTFT = 720ms @ batch=1，Qwen-7B
> - **偏差分析**：
>   - 原因 A：NPU 驱动版本与模型格式不匹配，导致 fallback 到 CPU
>   - 原因 B：模型量化策略（INT8 vs FP16）未在架构中明确，实际使用 FP16
>   - 原因 C：内存穿透路径未启用，数据在 JVM 堆与 NPU 显存间多次拷贝
> - **优化建议**：
>   - 建议 1：明确架构中模型量化策略为默认 INT8，FP16 需显式声明
>   - 建议 2：启用 Panama/Unsafe 零拷贝路径，减少数据搬运
>   - 建议 3：与硬件团队协作优化 NPU 驱动模型加载路径
> - **决策方**：架构委员会 + 硬件团队
> - **测试组结论**：仅暴露偏差与根因，不做技术决策

---

## 8. 环境要求与测试数据

### 8.1 SIT 环境配置

| 组件 | 要求 | 备注 |
|-----|------|------|
| **JDK** | OpenJDK 21 | 与生产一致，Virtual Threads 验证 |
| **Spring Boot** | 4.0.5 | 与生产一致 |
| **Postgres** | 16 + pgvector | W2+ 持久化验证；W0 可用 H2/内存替代 |
| **消息总线** | Kafka / NATS JetStream / Redpanda（Stub 或 Testcontainers） | Track 1/2/3 分离验证 |
| **LLM 网关** | Mock Server（WireMock/MockServer） | 避免测试时调用真实付费接口 |
| **可观测性** | OTLP Collector + Prometheus + Tempo（可选） | 验证 Telemetry Vertical 端到端 |
| **性能测试硬件** | Ascend 910B NPU + Kunpeng 920 CPU | 性能竞争力层必须真实硬件 |
| **OS** | openEuler 22.03 LTS SP3 | 内核 ≥ 5.10，启用 NUMA、hugepages、io_uring |
| **监控工具** | eBPF（bcc/bpftrace）、perf、Intel PCM / iBMC | 内核级性能事件采集 |

### 8.2 测试数据管理

- **Tenant 隔离数据**：准备 3~5 个 tenant UUID，验证 `tenant_id NOT NULL` 和跨 tenant 数据隔离。
- **Run 生命周期数据**：覆盖 `PENDING → RUNNING → SUSPENDED → RUNNING → SUCCEEDED/FAILED/CANCELLED/EXPIRED` 全 DFA 路径。
- **边界输入**：超长文本（>16 KiB，验证 `Checkpointer` 上限）、特殊字符、多语言混合、非法 `traceparent` 头。
- **并发场景**：同一 Run 的 cancel + resume 竞态、多 tenant 并发创建 Run。
- **性能基准数据**：标准化模型（Qwen-7B INT8）、标准化输入（512 tokens prompt / 128 tokens completion）、标准化负载（100/1000/10000 QPS）。

---

## 9. 准入/准出标准

### 9.1 准入条件（Pre-conditions）

- [ ] 各模块单元测试通过率 100%。
- [ ] 各模块 `module-metadata.yaml` 完整（`module` / `kind` / `version` / `semver_compatibility`）。
- [ ] 各模块 `docs/dfx/<module>.yaml` 已发布（§4 #63 / ADR-0067）。
- [ ] ArchUnit 规则全绿（`ApiCompatibilityTest`, `RuntimeMustNotDependOnPlatformTest`, `OrchestrationSpiArchTest`, `SpiPurityGeneralizedArchTest` 等）。
- [ ] 待测模块间的接口契约 MD 文档已冻结（L1 closure）。
- [ ] 性能竞争力层测试环境已就绪（Ascend/Kunpeng 硬件、openEuler OS、eBPF 工具链）。

### 9.2 准出标准（Exit Criteria）

**子链路层：**
- [ ] 全部 5 条 **子链路测试**通过，数据流完整性零断裂。
- [ ] 接口偏差报告零 P0/P1 项。

**端到端层：**
- [ ] 全部 6 个 **端到端场景测试**通过，核心业务场景零阻塞缺陷。
- [ ] 两层一致性验证报告已生成，偏差项已录入评审会决策队列。
- [ ] 性能基线：端到端 P90 延迟 < 3s（W2 目标，W0 可放宽至 < 5s）。
- [ ] 并发基线：100 并发 Run 创建无数据串台、无重复执行、无连接泄漏。

**性能竞争力层：**
- [ ] 全部 8 个 **性能竞争力场景**完成测试，度量数据已采集。
- [ ] 异构穿透：Ascend NPU TTFT < 500ms @ Qwen-7B batch=1（或架构委员会认可的调整基线）。
- [ ] 内存穿透：100MB payload 零拷贝路径占比 > 90%。
- [ ] OS 级亲和：NUMA 跨节点 P99 延迟 < 1.5x 本地节点；CPU 核心绑定下 P99/P50 < 2.0。
- [ ] 能效比：每请求能耗 < 0.5J @ Ascend NPU（或架构委员会认可的调整基线）。
- [ ] 弹性：冷启动 < 30s；扩容收敛 < 60s；弹性过程错误率 < 0.1%。
- [ ] 性能竞争力偏差报告已生成，低于基线项已触发架构优化或基线调整评审。

---

## 10. 附录：与 W0/W1/W2/W3/W4 波次的对齐

| 波次 | SIT 重点 | 新增测试组合 | 备注 |
|-----|---------|-------------|------|
| **W0（已交付）** | 子链路 + 基础端到端 | SIT-SC-02/03/04、SIT-E2E-01 | 验证已 shipped 的 Orchestration SPI、Run DFA、Posture Gate、Idempotency Filter |
| **W1（当前）** | C2S 入口 + Tenant 升级 + 性能层基线建立 | SIT-SC-01、SIT-E2E-01（扩展）、SIT-PERF-01~04（基线采集） | `IngressGateway`、JWT cross-check、性能基线首次度量 |
| **W2（规划）** | 全链路 + Telemetry + 三轨隔离 + 性能优化验证 | SIT-SC-03/05、SIT-E2E-01~06、SIT-PERF-05~08 | Hook SPI 参考实现、OTel SDK、Postgres Checkpointer、Streamed handoff、性能基线达成验证 |
| **W3（规划）** | SDK GA + MCP + Sandbox + 边缘部署性能 | SIT-E2E-02（Evolve 全量）、SIT-PERF-06（边缘能效）、SIT-PERF-07（弹性） | `agent-client` SDK、MCP tool server、UNTRUSTED skill 强制 sandbox、边缘能效验证 |
| **W4（规划）** | Temporal + Eval Harness + 联邦 + 大规模性能 | SIT-E2E-03（Swarm 联邦）、SIT-PERF-05（大规模三轨压测）、SIT-PERF-08（大规模向量检索） | Temporal child workflow、跨实例 ancestor 链重建、100M 向量 mmap 检索 |

---

> **文档控制**
> - 本文档为 L0 SIT 策略文档，遵循 ADR-0068 Layered 4+1 规范。
> - 任何修改必须通过 `docs/logs/reviews/` 提案流程，经架构委员会评审后生效。
> - 关联文档：`ARCHITECTURE.md`（L0）、各 `agent-*/ARCHITECTURE.md`（L1）、`docs/governance/architecture-status.yaml`（能力状态机）。
