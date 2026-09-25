---
feature_id: FEAT-028-FEAT-042
test_type: end-to-end
scope: v0815-composed-routing
deployable_units: [edp-agent-engine-single, edp-agent-engine-multi, wiremock-gateway]
sut: EDPAgent -> optional WireMock gateway -> one Runtime hosting two EDPA instances
features: [FEAT-028, FEAT-042]
updated: 2026-09-16
---

# EDPAgent 到同 Runtime 双 Agent 实例路由验收：上下游端到端测试设计

## 1. 测试目标

从单实例 EDPAgent 的公开 A2A 入口发起业务请求，验证请求可直达、或经 WireMock 网关代理后到达同一 Runtime 进程中的 `code-assistant` 与 `data-assistant` 两个 Agent 实例，并由调用链返回完成结果。本设计只证明两种指定拓扑的端到端可达性和实例寻址，不验收 FEAT-028、FEAT-042 的完整特性语义。

## 2. 范围与非范围

范围：

- 单实例 EDPAgent 通过 Runtime 的两个实例 A2A 路径分别完成一次委托。
- 单实例 EDPAgent 通过 WireMock 网关的两个代理路径分别完成一次委托，WireMock 记录请求并把 SSE 响应从真实 Runtime 原样返回。
- 两个被调用实例始终属于同一个 Runtime 进程；每条拓扑内上游 EDPAgent 只启动一个进程。

非范围：

- FEAT-028 的同轮并行、批量中断、`toolCallId`、all-settled、单次恢复推理和耗时收益。
- FEAT-042 的配置继承、工作空间/会话/凭据隔离、默认实例、失败回滚、非法配置和单实例兼容。
- 网关鉴权正确性、故障注入、重试、超时、SSE 分块边界和响应重写语义。
- 性能、容量和两个委托是否并行。本测试使用两个独立请求，避免把并行语义作为链路通过条件。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-028-EDPA规划工作流&子智能体并行执行.md`（`v0815`，2026-09-08） | 确认 EDPAgent 通过 A2A 委托其他 Agent 的公开边界。 |
| `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-Func-028-edpa-planning-workflow-and-agent-parallel-execution.md`（2026-09-15 读取） | 确认直连与 A2A 网关出站路径的配置及公开观察面。 |
| `develop/02-features/FEAT-042-edpagent-multi-instance.md`（2026-09-15 读取） | 确认单进程托管多个 EDPA 实例及按实例寻址的目标拓扑。 |
| `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-Func-042-edpagent-multi-instance.md`（2026-09-15 读取） | 确认 Runtime 多实例端点与同进程托管边界。 |

## 4. 部署拓扑

```text
E1  direct
test client -> EDPAgent(single)
                 -> Runtime(multi, one PID)/a2a/agents/code-assistant
                 -> Runtime(multi, same PID)/a2a/agents/data-assistant

E2  gateway
test client -> EDPAgent(single) -> WireMock 3.9.1
                                      -> Runtime(multi, one PID)/a2a/agents/code-assistant
                                      -> Runtime(multi, same PID)/a2a/agents/data-assistant
```

边界要求：

- EDPAgent 与 Runtime 都从正式 `com.openjiuwen:edp-agent-engine:0.1.1:exec` 制品启动；Runtime 使用 `multi` profile。
- WireMock 只记录请求并代理到真实 Runtime，不返回固定成功 SSE，也不替换 EDPAgent 或 Runtime。
- 两个目标分别携带唯一 route canary；子实例使用关闭任务循环的确定性探针场景，并通过不同 workspace marker 证明实际命中的实例，结果必须从公开 A2A/SSE 返回。
- LLM 配置只从执行进程环境变量读取，密钥不得进入设计、代码、命令行参数或日志。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| E1 | EDPAgent 直连同一 Runtime 的两个 Agent 实例 | Given 一个 `multi` Runtime 进程已发布 `code-assistant`、`data-assistant` 两个实例端点，且一个单实例 EDPAgent 已把两个端点注册为远端 Agent | When 分别向该 EDPAgent 的公开 A2A 入口发送 code 与 data 路由探针，每次要求调用指定 `call_subagent` 一次 | Then 两次请求均以完成态返回各自 route canary 和目标实例独有的 workspace marker，且不含另一实例 marker；Runtime PID 在两次调用前后不变，且两个实例卡片 URL 均指向该 Runtime 的不同实例路径 | 正式 EDPAgent/Runtime 制品、Redis Testcontainer、上游真实 LLM、确定性 Runtime 探针场景、唯一 context/canary |
| E2 | EDPAgent 经 WireMock 网关访问同一 Runtime 的两个 Agent 实例 | Given 与 E1 相同的 Runtime；一个启用 A2A gateway 的单实例 EDPAgent；WireMock 已为两个实例路径配置到该 Runtime 的透明代理 | When 分别向该 EDPAgent 发送 code 与 data 路由探针 | Then 两次请求均完成并返回各自 route canary 和目标实例 workspace marker；WireMock 分别记录两个目标路径及对应 canary 请求体，响应来自真实 Runtime；Runtime PID 不变 | `org.wiremock:wiremock-standalone:3.9.1`、正式 EDPAgent/Runtime 制品、Redis Testcontainer、上游真实 LLM、确定性 Runtime 探针场景、唯一 context/canary |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| 上游 EDPAgent | 真实 SUT | `edp-agent-engine:0.1.1:exec` 单实例进程；通过公开 A2A 入口接收探针并调用 `call_subagent`。每个场景独立启动一次，以切换直连/网关配置。 |
| 双实例 Runtime | 真实 SUT | `edp-agent-engine:0.1.1:exec` 的 `multi` profile；测试类生命周期内只启动一次，同一 PID 发布两个实例端点。 |
| code-assistant | 真实 SUT 内实例 | 使用独立的 Runtime 探针场景并关闭任务循环，通过 `runtime-probe-code` workspace marker 回显实际实例配置；场景仅注册 Card 声明的 `code_review_skill`，不调用下游 LLM、Todo 或其他工具。 |
| data-assistant | 真实 SUT 内实例 | 使用独立的 Runtime 探针场景并关闭任务循环，通过 `runtime-probe-data` workspace marker 回显实际实例配置；场景仅注册 Card 声明的 `analyze_data_skill`，不调用下游 LLM、Todo 或其他工具。 |
| WireMock gateway | Fixture | 使用用户指定的 3.9.1 standalone 测试依赖；记录 Header/body，并把两个 A2A POST 路径代理到真实 Runtime，不生成业务结果。 |
| Redis 与 route canary | Fixture | Redis 支撑上游 EDPAgent 的正式规划/Todo 状态以及 Runtime 会话/任务状态；每次请求使用独立 context 和 canary，排除历史结果假阳性。 |

## 7. 关键链路断言

- Runtime 是一个存活的受管进程，测试期间 PID 不变；两个 Agent Card 的接口 URL 分别以 `/a2a/agents/code-assistant`、`/a2a/agents/data-assistant` 结尾。
- E1 的两次上游响应分别包含本次 route canary、目标实例 workspace marker 和完成态，且不含另一实例 marker；探针结果由真实 Runtime 实例生成，不接受测试夹具直接合成的成功结果。
- E2 在 E1 断言基础上，WireMock 必须分别捕获两个实例路径；每条请求 body 必须包含对应 route canary。
- E2 的 WireMock stub 使用代理响应；若 Runtime 未收到请求或未返回终态，场景失败，固定 SSE 回放不能满足 Oracle。

## 8. 执行策略

- Smoke：E1、E2。
- Full suite：E1、E2。本轮没有其他场景，也不定义额外优先级。
- 设计状态为 `designed-runnable`；自动化状态为 `verified`。
- E1 已通过 WSL 精确执行：Run ID `20260916-000349`，结果 `PASS`，1 个测试、0 failure、0 error、0 skipped。
- E2 已通过 WSL 精确执行：Run ID `20260916-000800`，结果 `PASS`，1 个测试、0 failure、0 error、0 skipped。
- 两次验证使用 `com.openjiuwen:edp-agent-engine:0.1.1:exec`，制品 SHA-256 为 `25156c4898be5cc5593ffa478dd886115327c8c118fb8c55d2547e31e939107e`。
- 执行门禁：`agent-core-java@930`、`agent-runtime-java@develop`、`agent-solution@common` 构建到共享 Maven 仓；WSL Java 与 Docker 可用；五个 `LLM_*` 环境变量已提供。
- 隔离与证据：每个方法使用独立上游进程、context 与 route canary；共享 Runtime 与 Redis 由类级生命周期关闭；WireMock 由 E2 方法关闭。

```powershell
./.agents/skills/feature-acceptance-testing/scripts/run-wsl-tests.ps1 `
  -TestSelector EdpaMultiInstanceRoutingE2eTest `
  -Environment openjiuwen `
  -OutputSuffix feat028-feat042-routing `
  -RequireLlm
```

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 输入解析脚本只匹配以 `FEAT-028/042` 开头的 L2 文件名，而当前 L2 使用 `Feat-Func-028/042` | 自动输入清单无法由该脚本生成 | 已人工锁定唯一匹配文件和源码分支，不影响测试实现 | 后续单独增强脚本的 L2 文件名兼容性；本轮不修改共享脚本 |
| 真实 LLM 输出存在自然语言波动 | 不可断言逐字回答 | 通过 route canary、实例身份 canary、完成态和 WireMock 请求记录建立稳定 Oracle | 若模型不支持 tool calling，则环境结果记为 ERROR，不放宽链路 Oracle |
