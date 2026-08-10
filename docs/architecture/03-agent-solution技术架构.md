---
title: agent-solution 技术架构
description: 扩展方案七个模块：core-ext、runtime-ext、agent-bus、agent-client、agent-evolve、agents、example
audience: both
---

# agent-solution：扩展方案与场景实现

> 本文档为 [OpenJiuwen 三仓技术架构总览](00-OpenJiuwen技术架构总览.md) 的子文档，详细分析 `agent-solution` 仓库的架构设计。

---

## 一、工程定位

`agent-solution` 是 OpenJiuwen 的扩展方案库，`common/` 下有七个并列顶层模块。仓根提供只负责聚合构建的 `agent-solution-build` POM（不是各模块的继承 parent，也不发布）；各叶子模块仍通过各自 parent 与 Maven 坐标独立交付。它不重新实现运行时能力，也不重新实现执行核心，而是在两者之上做场景化扩展。

---

## 二、模块总览

| 模块 | 职责 | 与底座关系 |
|------|------|-----------|
| `agent-core-ext-java` | ReActAgent 认知能力扩展 | Maven 依赖 + Rail 机制扩展 agent-core-java |
| `agent-runtime-ext-java` | 协议/框架/入站扩展 | Maven 依赖 + AgentHandler SPI 扩展 agent-runtime-java |
| `agent-bus` | Agent 总线与入口平面 | HTTP/SSE 协议调用 runtime，独立部署 |
| `agent-client` | 端侧访问 SDK | A2A 协议调用 runtime，独立交付 |
| `agent-evolve` | 自演进引擎 | Python 项目，依赖 Python 版 agent-core |
| `agents` | 具体 Agent 实现 | Maven 依赖 agent-core-java / agent-runtime-java |
| `example` | 示例拓扑 | 串联各扩展模块 |

> ⚠️ 文档消费范围：本页为架构全景描述。其中 `agent-bus` / `agent-client` /
> `agent-evolve` / `agents` / `example`（及 runtime-ext 的 agentscope /
> bus-consumer）**不在当前文档消费范围内**——本仓 how-to 与接口文档只覆盖
> core-ext（react-rails）与 runtime-ext 的 versatile / agentcore-ext / SkillHub /
> Custom REST；`agents` / `example` 的源码未完全开放，此处只保留模块级架构事实，
> 不展开其内部实现与示例细节。

---

## 三、agent-core-ext-java：认知能力扩展

纯 Java SDK，不依赖 Spring 或 runtime-ext，直接依赖 `agent-core-java`。当前聚合子模块 `agent-core-ext-react-rails`，给 ReActAgent 补三条认知 Rail，填补 ReActAgent 缺少验证环节的能力空缺：

| Rail | 钩子点 | 职责 |
|------|--------|------|
| 条件验证 Rail | 模型调用后 | 验证最终答案是否满足成功标准；通过则正常结束，失败则降级结束 |
| 重规划 Rail | 模型调用后 | 检测重规划意图，计数限制防止循环发散；超限时降级结束 |
| 根因诊断 Rail | 工具异常 + 模型调用后 | 设备故障时诚实降级终止，不无限重试 |

**扩展机制**：三条 Rail 都通过 `forceFinish` gate 在模型调用后钩子里终止 ReActAgent 循环，复用 `agent-core-java` 的 Rail 注册接口和回调上下文。此外还包含验证器接口与实现、检查清单、验证-重规划桥接、停滞检测、历史压缩、工具调用强制、Rail 事件可观测性等辅助能力。

---

## 四、agent-runtime-ext-java：协议与框架扩展

聚合五个默认子模块和一个 profile 激活子模块，扩展 `agent-runtime-java` 的协议适配、框架兼容和入站能力：

### agent-service-adapters-agentcore-ext

继承基础 AgentCore Handler，在执行前通过 `installBeforeRun` 生命周期安装扩展能力：

- **远程 A2A 工具注入**：读取基础 Runtime 维护的远端 Agent Card 注册表，将远端 Agent 增量、幂等地安装为本地 Agent 的模型工具
- **A2A 委派中断**：模型调用注入的远端工具时产生委派中断，由 Runtime 的 A2A Orchestrator 完成实际远端调用
- **客户端工具**：处理请求级客户端工具，产生中断交由客户端执行
- **SkillHub 中间件**：可选的 Skill 下载与注册，通过 SPI 支持多种 Skill 源

### agent-service-adapters-versatile

将 Runtime 请求转换为 Versatile HTTP/SSE 调用，支持意图路由选择端点、SSE 逐行事件解析、Legacy 结果与三字段结果（含 A2A 委派中断）等多种响应模式。自动装配只绑定配置，宿主必须显式注册 Handler Bean。

### agent-service-adapters-agentscope

进程内 AgentScope Agent 适配器，支持 ReAct 和 Harness 两种调用器，映射 runtime 的 query、stream、failure、pause 语义。无自动配置，需宿主显式注册。

### agent-service-app-custom-rest

让宿主暴露自定义 JSON REST 协议入口，框架固定传输、A2A Bridge、任务续接和错误边界，支持同步 JSON 和 SSE 流式响应。

### agent-service-bus-consumer（profile 激活）

Runtime 侧订阅 event-bus 的 deliver 事件，桥接到业务 AgentHandler，投影 Task 状态，发布响应事件。复用基础 Runtime 的 A2A 控制面，不创建独立 Agent。

### agent-service-spec-ext

扩展公共 SPI，当前主要服务 SkillHub 的 Skill 源提供者接口。

---

## 五、agent-bus：Agent 总线与入口平面

三个可独立部署的 Spring Boot 单元，无 root pom，各自独立构建，通过 HTTP/SSE 端口协作：

```
client ──POST /a2a──▶ agent-gateway ──┐
                       │  (RDC 解析路由)
                       ├──DIRECT─▶ runtime /a2a (HTTP/SSE)
                       └──BUS──▶ event-bus-relay ──▶ runtime
                                     ◀── resp ── (两跳回程)
```

### agent-gateway（A2A 入口治理网关）

接收 A2A 请求 → 治理（鉴权/租户/校验/幂等/审计）→ 按路径模式转发。支持两种模式：DIRECT（默认，经 RDC 解析路由后直连 runtime）和 BUS（经 event-bus 两跳转发到 runtime）。RDC 和 runtime 经六边形端口以 HTTP/SSE 触达，无进程内耦合。

### event-bus（转发总线）

gateway/caller 与 runtime 之间的两跳 broker 转发（请求 → 投递 → 响应入 → 响应出），内置治理（去重/租户/correlation/poison 守卫）和可靠投递（JDBC outbox/inbox + Flyway 迁移）。持久化层使用 PostgreSQL + RocketMQ。包含四个子模块：SPI 契约层（零生产依赖）、测试替身、SDK（JDBC + RocketMQ + Spring 自动装配）、Relay（独立治理 relay 进程）。三角色互斥：caller（生产方）、runtime（消费方）、relay（relay 进程）。

### registry-discovery-center（注册发现中心）

Agent 逻辑 Card 注册/发现、运行时实例路由、健康探活。两个平面：逻辑 Agent Card（注册与发现）和运行时实例路由（opaque routeHandle 解析）。基于 PostgreSQL + RLS 多租户隔离，通过 Flyway 管理数据库迁移，内置健康探活调度器。

---

## 六、agent-client：端侧访问 SDK

`agent-client-sdk-for-jvm` 是 JVM 版 SDK 交付物，采用四层包结构，严格依赖单向：

| 层 | 职责 |
|----|------|
| 公共 API/SPI | Agent 调用接口（invoke/getTask/cancelTask/subscribe/continueTask）、本地工具 SPI、状态存储 SPI、传输提供者 SPI |
| Core 编排 | 默认客户端实现、工具注册表与分发器、内存状态存储 |
| Transport 适配器 | A2A HTTP 传输提供者、测试用进程内网关 |

设计原则：

- **单一状态权威**：agent-runtime 是服务端 Task 的唯一 owner,SDK 只保存本地投影
- **公共 API 框架中立**：只依赖 JDK 类型，不暴露 Spring/Reactor/Jackson/A2A SDK
- **transport 与语义解耦**：HTTP/SSE/Webhook 都是 transport adapter
- **三套状态机**：调用状态机、Stream subscription 状态机、本地工具调用状态机

关键约束：公共 API/SPI 禁止 import agent-runtime/agent-core 生产代码；client 与服务端只通过线协议（A2A JSON）通信。

---

## 七、agent-evolve：自演进引擎（Python）

两个子模块均为 Python 项目，不属于 Java 生态。

**evoagent（自进化元 Agent）** — 基于 agent-core Python 包的 ReActAgent 构建，实现「用户指令 → Agent 识别意图 → 编排优化 Pipeline → 输出优化报告」的闭环。优化引擎采用 ReflACT 管线：反思 → 聚合 → 选择 → 应用 → 慢更新 → 元技能，跨 operator 并行受单一 semaphore 控制。提供 CLI 和 FastAPI 双模式入口。

**evoagent-adapter（日志采集适配器）** — 独立部署的轻量服务，增量读取 EDPAgent 日志，解析配对的 LLM 调用开始/结束标签，输出结构化 JSONL 轨迹。提供轨迹查询和 managed-doc 热更新 API。

两者关系：evoagent 是优化引擎（消费者），evoagent-adapter 是数据采集层（生产者）。evoagent 通过 sidecar 通信获取轨迹数据，adapter 的 managed-doc API 支持优化后 skill 文档的热更新。

---

## 八、agents：具体 Agent 实现

四个独立构建的 Agent 实现，代表不同场景的 Agent 落地：

| 子模块 | 职责 | 依赖底座 |
|--------|------|---------|
| **pev** | PEV（Plan→Execute→Verify→Diagnose→Dispatch）模板，自带 verify-loop dispatch | agent-core-java |
| **edp-agent-java** | 生产级 EDPAgent Java 项目，基于 DeepAgent，含大量 Rail、工具、配置 | agent-runtime-java + agent-runtime-ext-java + agent-core-java |
| **edpa-alpha** | EDPA alpha 版，含探索器、MCP 集成、子 Agent 调度、验证 | agent-core-java |
| **versatile-agent-java** | 独立 A2A 进程，代理 A2A 请求到远端 Versatile REST API | agent-service-app + agent-service-adapters-versatile |

其中 `edp-agent-java` 是最重的实现，包含十余条 Rail（覆盖用户确认、取消、熔断、事件、任务规划、执行限制、日志、MCP 中断、沙箱中断、脚本、Versatile 委派等场景）和多个业务工具。

---

## 九、example：示例拓扑

`common/example/` 下当前有十五个示例目录，展示如何串联各扩展模块。关键示例拓扑：

| 示例 | 串联的扩展 | 拓扑 |
|------|-----------|------|
| multi-react-travel-demo | agentcore-ext | 3 个 ReActAgent 通过远程 A2A 工具互相调用（旅行编排） |
| multi-deep-research-demo | runtime-ext + core | DeepAgent root + ReAct sub-agents 多 Agent 深度研究 |
| agentcore-ext-remote-a2a-tool-demo | agentcore-ext + versatile | DeepAgent runtime 通过远端 A2A 工具调用 Versatile runtime |
| versatile-orchestration-demo | versatile + agentcore-ext + custom-rest | Versatile 工作流编排（含 gateway + adapter + plan-agent） |
| agentscope-a2a-interrupt-demo | agentscope adapter | AgentScope A2A 中断（Harness + ReAct 两种 invoker） |
| agent-bus-consumer-demo | app + bus-consumer + event-bus-sdk | Runtime 订阅 agent-bus 事件 |
| agent-gateway-demo | agent-gateway + event-bus-sdk | agent-gateway 的 BUS 模式 launcher |
| agent-client-demo | agent-client-sdk-for-jvm | Agent Client SDK 端侧调用验证 |
| skillhub-runtime-demo | agentcore-ext (SkillHub) | SkillHub skill 下载/注册 |
