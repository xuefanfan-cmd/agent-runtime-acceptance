---
feature_id: FEAT-051-RT
test_type: component
scope: request-level-model-selection
deployable_units: [agent-runtime-java]
sut: JiuwenCoreAgentHandler（注入 LlmModelCatalog 的 ReActAgent + DeepAgent）
features: [FEAT-051]
updated: 2026-10-10
owner: TBD
priority: P0
status: draft
tags: [component, dynamic-model, feat-051]
depends_on:
  - agent-runtime-java 已实现请求级模型选择（FEAT-051-RT）
  - agent-core-java 0.1.17 已发布至 Maven 仓库
  - LLM 可用（支持至少 2 个不同 mock 端点）
  - apiconfig.json 配置文件（根默认 model-a + 列表 model-b）
  - 测试 profile：application-openjiuwen.yml（-Dtest.env=openjiuwen）
---

# FEAT-051-RT — 请求级模型选择验收方案

> **一句话**：验证 agent-runtime 的请求级模型选择能力——通过 `apiconfig.json` 配置不可变模型目录、
> 启动注册验证、Query/A2A 请求级 `model_name` 公开别名解析、缺省回根默认、未知别名拒绝、
> 所选模型调用失败可见、请求间隔离、内部续接保持选择，以及旧配置向后兼容。
>
> **关键定位**：FEAT-051-RT 的**黑盒验收**——覆盖 agent-runtime 请求级模型选择的全部 MUST 级要求。
> 场景编号与 L2 设计文档 `Feat-Func-051-request-level-model-selection.md` §7 验收标准一一对应。

---

## 1. 测试目标

以 `JiuwenCoreAgentHandler`（注入 `LlmModelCatalog` 的 ReActAgent/DeepAgent）为黑盒 SUT，通过 HTTP/A2A 请求和 `apiconfig.json` 配置，验证请求级模型选择的全部外部可观测行为：

1. **配置与启动校验**：`apiconfig.json` 根对象=默认模型 + MODELS 列表追加其他模型；启动时解析、逐项解密、校验并注册到 Core Runner。
2. **请求参数边界**：`model_name` 类型非法/空白 → Query 400 / A2A -32602；非正式位置不触发选模。
3. **请求级路由（成功）**：Query/A2A 同步/流式按 `model_name` 公开别名路由到对应 mock 端点、凭据和供应商模型。
4. **所选模型调用失败**：401/5xx/连接失败/超时 + 部分输出后失败 → 客户端收到失败，不自动换模型，错误信息不泄露凭据。
5. **响应完整性**：不仅断言响应标识，还检查 mock 收到的真实路径、供应商请求体 model、认证头和公共参数。
6. **注册隔离与生命周期**：唯一 scope 隔离、重复 start 幂等、冲突不覆盖、失败仅回滚己方条目。
7. **内部续接**：同一请求 batch resume/snapshot 恢复保留公开别名；旧快照默认；失效别名拒绝。
8. **兼容性**：旧配置（开关关闭）行为不变；无目录不选模保持旧路径；无目录却显式选模拒绝。
9. **请求间隔离（Runtime 全链路）**：B→缺省→A 隔离、同会话 A→B→缺省 A、ReAct 不同会话并发互不污染。
10. **已知限制记录**：Core 回退、辅助滞后、共享 DeepAgent 并发缺陷、特殊流式失败缺口——记录为已知限制，不作为通过结论。

**本方案不覆盖**（由 FEAT-050 补充验证）：

- SDK 直连 CRUD（addModel/removeModel/updateModel/... 面向"无 Runtime 客户"场景）（→ FEAT-050 SC-CORE-23~32）
- Core 模型解析优先级链内部回退逻辑（SDK 直连验证 ❶→❷→❸ 三级回退内部行为）（→ FEAT-050 SC-CORE-35~38）
- Context 压缩器跟随时序（→ Core 专项，§1.3 已知限制）
- 共享 DeepAgent 并发结果隔离（→ §1.3 已知缺陷，暂不作为通过结论）

> **注意**：Core 通过 Runtime 端到端验证的行为（启动注册、请求级路由、模型调用失败处理、请求间隔离）**是**本版验收的一部分，
> 由 FEAT-050 SC-CORE-01~22 与本方案互补覆盖。此处"不覆盖"仅指 SDK 直连和 Core 内部回退逻辑。

---

## 2. 范围与非范围

### 范围

- `apiconfig.json` 配置文件解析：根对象=默认模型 + MODELS 追加其他模型。
- `openjiuwen.service.llm.model-selection-enabled` opt-in 开关。
- 启动注册验证：文件读取、类型校验、逐项解密、重复 ID 检查、根不污染列表、列表不继承根连接。
- Query `/v1/query`、`/query` body.model_name 参数解析（MVC + WebFlux 入口）。
- A2A `SendMessage` / `SendStreamingMessage` 请求 params.metadata.model_name 解析。
- 缺省/null → 根默认；类型错误/空白 → 400/-32602；合法未知别名 → Runner 前拒绝。
- 所选模型调用失败（401/5xx/连接失败/超时/部分输出后失败）→ 执行失败通道，不自动换模型。
- 响应完整性：mock 收到的路径、供应商 model、认证头、公共参数（temperature/topP）。
- 注册隔离与生命周期：唯一 scope、幂等 start、冲突不覆盖、失败回滚。
- 内部续接：batch resume / snapshot 恢复保留公开别名。
- 向后兼容：开关关闭、无目录不选模、无目录却显式选模拒绝。
- 请求间隔离：B→缺省→A、同会话 A→B→缺省 A、ReAct 并发互不污染。
- 同 JVM 两实例使用相同公开别名、不同配置时互不覆盖（组件测试）。
- 旧流式错误兼容：无目录时原 data 的 code/retryable 等保留。

### 非范围

- SDK 直连 CRUD（addModel/removeModel/updateModel/listModelIds/hasModel/setDefaultModelId/getDefaultModelId）（→ FEAT-050 SC-CORE-23~32）。注：Core 通过 Runtime 端到端验证的行为（启动注册、请求级路由、模型调用失败处理、请求间隔离）**是**本版验收的一部分，由 FEAT-050 SC-CORE-01~22 互补覆盖。
- Core 模型解析优先级链内部回退逻辑（❶→❷→❸ 三级回退的内部行为）（→ FEAT-050 SC-CORE-35~38）。注：Runtime 通过 SC-ROUTE-05/06/07 验证优先级链的可观测效果（B→缺省→A、缺省走根默认），仅不验证内部回退逻辑。
- Context 压缩器跟随时序强制验证（§1.3 已知限制，Core 专项后续负责）。
- 共享 DeepAgent 并发结果隔离（§1.3 已知缺陷，暂缓修复）。
- DeepAgent 特殊最终失败的流式缺口（§1.3 已知缺陷）。
- REST API 模型管理接口（特性不提供）。
- Redis 集群同步（特性不引入）。
- 管理台 UI（特性不提供）。
- 运行时模型管理 / 热更新（特性明确不支持，改配置文件 + 重启）。
- 自定义 Handler / ext 接入（后续版本负责）。
- 任意自定义 Agent 仅被包装进 Handler（不等于已支持，集成方负责）。

---

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-051-request-level-model-selection.md`（status: draft, updated 2026-10-09） | L2 设计文档，请求级模型选择的完整设计：配置 schema（§6）、外部服务面（§2）、核心运行模型（§3）、错误表面（§2.4）、验收标准（§7）、已知差距（§1.3）。 |
| `develop/01-scenarios/FRS-050-dynamic-model-config.md`（status: 评审中, v1.1） | 特性需求说明书，提供验收标准和端到端旅程的高层要求。注意：FRS-050 的配置方式（YAML model-management.models）已被 L2 设计文档的 apiconfig.json 方案取代。 |

> **注意**：当 FRS-050 与 L2 设计文档冲突时，以 L2 设计文档为准。L2 反映 2026-10-09 当前本地实现及已执行验证。

---

## 4. 部署拓扑

### 4.1 正常启动拓扑

```text
JUnit 黑盒驱动
  +-> mock LLM 端点 A（http://localhost:4000/v1，返回 "MODEL_A_RESPONSE"）
  +-> mock LLM 端点 B（http://localhost:4001/v1，返回 "MODEL_B_RESPONSE"）
  `-> agent-runtime（单 JVM / 随机端口）
        +-> apiconfig.json（根默认=alias-a，MODELS=[alias-b]）
        +-> openjiuwen.service.llm.model-selection-enabled: true
        +-> LlmConfigResolver 解析 → LlmModelCatalog（不可变）
        `-> JiuwenCoreAgentHandler.start() → 注册 typed Model 到 Runner + getModel 验证
```

### 4.2 配置加载失败拓扑

```text
JUnit 黑盒驱动
  +-> 正常实例（multi-model profile，端口 8095）
  `-> 错误实例（bad-config profile，端口 8099）
        `-> apiconfig.json 格式错误/字段缺失 → 启动失败 → fail-fast → 不影响正常实例
```

### 4.3 多实例隔离拓扑

```text
JUnit 黑盒驱动
  `-> agent-runtime（单 JVM）
        +-> 实例 X（scope=x，alias-a → 端点 A，alias-b → 端点 B）
        `-> 实例 Y（scope=y，alias-a → 端点 C，alias-b → 端点 D）
        验证：同 JVM 两实例使用相同公开别名、不同配置时互不覆盖
```

> **模型路由验证策略**：
> 1. 通过 mock 端点返回可识别内容（MODEL_A_RESPONSE 等）从响应反推实际使用的模型。
> 2. 通过 mock 端点收到的请求记录验证：真实路径、供应商请求体 model、认证头、公共参数。
> 3. 认证断言需脱敏，不输出完整 api-key。
> 4. 使用 HTTP mock 不代表可以随意声明 `provider: mock`，仍需受支持的 provider 值。

---

## 5. 测试场景矩阵

### 5.1 配置与启动校验（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CFG-01 | 正常配置注册 | `apiconfig.json` 配置根默认 `alias-a`（MODEL_ID=alias-a, MODEL_NAME=provider-chat-a, API_BASE=http://localhost:4000/v1）+ MODELS 列表追加 `alias-b`（MODEL_ID=alias-b, MODEL_NAME=provider-chat-b, API_BASE=http://localhost:4001/v1）；`model-selection-enabled: true` | 启动服务 → 发请求指定 model_name="alias-b" → 发请求不传 model_name | 启动成功；alias-b 请求路由到端点 B，响应含 "MODEL_B_RESPONSE"；缺省请求路由到端点 A，响应含 "MODEL_A_RESPONSE" | L2 §7.1 配置及兼容, §7.2 启用目录 | P0 |
| SC-CFG-02 | 公开别名与供应商模型名区分 | `apiconfig.json` 中 MODEL_ID="alias-b" 但 MODEL_NAME="provider-chat-b"（两者不同） | 启动服务 → 发请求指定 model_name="alias-b" → 检查 mock 端点 B 收到的请求体 | mock 端点 B 收到的供应商请求体 model 字段为 "provider-chat-b"（不是 "alias-b"）；响应含 "MODEL_B_RESPONSE" | L2 §5.3 映射, §1.2 | P0 |
| SC-CFG-03 | opt-in 开关关闭时不校验追加项 | `model-selection-enabled: false`；`apiconfig.json` 存在根默认 + MODELS 列表（含重复 ID 等无效配置） | 启动服务 → 发请求不传 model_name | 服务正常启动；请求使用旧单模型配置完成；不主动校验 MODELS 或解密追加项 | L2 §6.2, §6.4 | P0 |
| SC-CFG-04 | MODELS 类型错误 | `apiconfig.json` 中 MODELS 为字符串而非数组 | 启动服务 | 解析失败，启动中断（fail-fast）；进程在 ready 前退出 | L2 §6.2 | P1 |
| SC-CFG-05 | 重复 MODEL_ID | `apiconfig.json` 中 MODELS 两个条目 MODEL_ID 均为 "alias-b" | 启动服务 | 解析失败（冲突拒绝），启动中断 | L2 §6.2 MODELS[].MODEL_ID 唯一 | P1 |
| SC-CFG-06 | 空白 MODEL_ID | `apiconfig.json` 中某 MODELS 条目 MODEL_ID 为空白字符串 | 启动服务 | 解析失败（trim 后非空校验），启动中断 | L2 §6.2 | P1 |
| SC-CFG-07 | MODELS 条目不继承根连接 | `apiconfig.json` 根默认 MODEL_PROVIDER="OpenAI"，MODELS[0] 不设 MODEL_PROVIDER（默认 OpenAI）；MODELS[0] 的 API_KEY/API_BASE/MODEL_NAME 独立完整 | 启动服务 → 分别发请求指定 alias-a 和 alias-b → 检查 mock 端点收到的认证头和路径 | alias-a 请求到达端点 A 且认证头为 key-a；alias-b 请求到达端点 B 且认证头为 key-b；两请求使用各自独立配置 | L2 §6.2 MODELS[].MODEL_PROVIDER 不继承根 | P0 |
| SC-CFG-08 | 根不污染列表 | `apiconfig.json` 根 MODEL_SSL_VERIFY=false，MODELS[0] LLM_SSL_VERIFY=true | 启动服务 → 分别发请求 → 检查 mock 端点收到的 SSL 配置 | 根默认项使用 SSL_VERIFY=false；列表项使用各自的 SSL_VERIFY=true，不被根覆盖 | L2 §6.2 根不污染列表 | P1 |
| SC-CFG-09 | 注册失败回滚 | `apiconfig.json` 中前 N 个条目有效，第 N+1 个条目 MODEL_ID 与前冲突 | 启动服务 | 启动失败；已注册的己方条目回滚；不删除外部冲突资源；诊断日志含操作/ID/异常类及 Core code，不含原 message/cause 中的密钥 | L2 §7.2 注册途中失败, §4.4 | P1 |
| SC-CFG-10 | 启动注册不请求远端 LLM | `apiconfig.json` 配置有效但 API_BASE 指向不可达地址 | 启动服务 | 服务正常启动（启动验证本地注册和可取性，不请求远端 LLM）；启动后发请求时才连接失败 | L2 §3.1 | P1 |
| SC-CFG-11 | 文件大小限制 | `apiconfig.json` 文件大小超过 1 MiB | 启动服务 | 解析失败（文件大小超限），启动中断 | L2 §6.2 文件不超过 1 MiB | P2 |
| SC-CFG-12 | auto-discover 自动发现 | `model-selection-enabled: true` 但未指定 config-file；工作目录下有 `apiconfig.json` | 启动服务 | 从工作目录向上最多六级查找 `apiconfig.json`，找到则正常加载 | L2 §6.2 auto-discover | P2 |
| SC-CFG-13 | 解密后空密钥拒绝 | `apiconfig.json` 中某条目 API_KEY 加密，解密后为空字符串 | 启动服务 | 解析失败（目录不接受解密后空密钥），启动中断 | L2 §6.2 解密后空密钥 | P1 |
| SC-CFG-14 | 无文件 + Spring 完整默认 | 无 `apiconfig.json` 文件；Spring 配置提供完整默认连接（model-name/provider/api-base/api-key） | 启动服务 → 发请求不传 model_name | 服务正常启动；生成仅默认项目录；请求使用默认模型完成 | L2 §6.2 无文件且 Spring 提供完整默认 | P1 |
| SC-CFG-15 | SSL 接受字符串值 | `apiconfig.json` 中 LLM_SSL_VERIFY="false"（字符串而非布尔） | 启动服务 → 发请求 → 检查 mock 端点收到的 SSL 配置 | 正常解析（SSL 接受布尔及 "true"/"false" 字符串）；请求正常完成 | L2 §6.2 SSL 接受布尔及 true/false 字符串 | P2 |

### 5.2 请求参数边界（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-PARAM-01 | model_name 缺失 | 目录已注册 {alias-a, alias-b}，根默认=alias-a | POST /v1/query，body 不含 model_name | 正常执行，使用根默认 alias-a，响应含 "MODEL_A_RESPONSE" | L2 §2.2, §2.4 | P0 |
| SC-PARAM-02 | model_name 为 null | 同上 | POST /v1/query，body 含 model_name=null | 正常执行，使用根默认 alias-a | L2 §2.2 | P0 |
| SC-PARAM-03 | model_name 为空白 | 同上 | POST /v1/query，body 含 model_name="  " | Query HTTP 400；不调用 Runner；流式在提交 SSE 前拒绝 | L2 §2.4, §7.3 | P0 |
| SC-PARAM-04 | model_name 为数字 | 同上 | POST /v1/query，body 含 model_name=123 | Query HTTP 400（类型非法）；不调用 Runner | L2 §2.2, §7.3 | P0 |
| SC-PARAM-05 | model_name 为布尔 | 同上 | POST /v1/query，body 含 model_name=true | Query HTTP 400 | L2 §7.3 | P1 |
| SC-PARAM-06 | model_name 为数组 | 同上 | POST /v1/query，body 含 model_name=["a","b"] | Query HTTP 400 | L2 §7.3 | P1 |
| SC-PARAM-07 | A2A metadata.model_name 格式错误 | 同上 | 发送 A2A SendMessage，params.metadata.model_name=123 | JSON-RPC -32602；不创建任务 | L2 §2.3, §7.3 | P0 |
| SC-PARAM-08 | 未知别名 | 目录已注册 {alias-a, alias-b} | POST /v1/query，body 含 model_name="alias-x" | Query 同步 HTTP 500 / AGENT_EXECUTION_FAILED；SSE 错误终止；A2A failed；Handler 在 Runner 前拒绝，不发送 LLM 请求 | L2 §2.4, §7.3 | P0 |
| SC-PARAM-09 | 无目录却显式选模 | `model-selection-enabled: false`，无目录 | POST /v1/query，body 含 model_name="alias-b" | 执行失败通道（500/AGENT_EXECUTION_FAILED）；不静默忽略 | L2 §2.4, §6.4 | P0 |
| SC-PARAM-10 | 非正式位置不触发选模 | 目录已注册 {alias-a, alias-b} | POST /v1/query，body.model_id="alias-b"（非 model_name）；同时检查 header、URL 参数、message.metadata | 请求使用根默认 alias-a（非正式位置不能触发选模）；响应含 "MODEL_A_RESPONSE" | L2 §2.2 出参 | P1 |
| SC-PARAM-11 | A2A message.metadata 不触发选模 | 目录已注册 {alias-a, alias-b} | 发送 A2A SendMessage，params.message.metadata.model_name="alias-b"（而非 params.metadata.model_name） | 请求使用根默认 alias-a | L2 §2.3 不是 params.message.metadata | P1 |
| SC-PARAM-12 | WebFlux 入口参数校验 | 目录已注册 {alias-a, alias-b} | POST /v1/query/reactive，body 含 model_name=123 | HTTP 400（reactive 使用相同 DTO 校验） | L2 §2.1, §2.2 | P1 |
| SC-PARAM-13 | trim 后大小写敏感 | 目录已注册 {alias-a, alias-b} | POST /v1/query，body 含 model_name=" alias-b " | trim 后为 "alias-b"，路由到端点 B，响应含 "MODEL_B_RESPONSE"；另测 model_name="ALIAS-B" 不命中（大小写敏感） | L2 §2.2, §5.3 | P1 |

### 5.3 请求级路由 — 成功（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-ROUTE-01 | Query 同步选 B | 目录 {alias-a→端点 A, alias-b→端点 B}，根默认=alias-a | POST /v1/query，body 含 model_name="alias-b" | 请求路由到端点 B；mock B 收到的路径=4001/v1，供应商 model=provider-chat-b，认证头=key-b；响应含 "MODEL_B_RESPONSE" | L2 §7.2 启用目录 | P0 |
| SC-ROUTE-02 | Query 流式选 B | 同上 | POST /v1/query，stream=true，body 含 model_name="alias-b" | SSE 流式返回；Runtime 输出自己的事件数据；最终响应含 "MODEL_B_RESPONSE"；检查实际 SSE data 内容而非仅 HTTP 200 | L2 §2.2, §3.4 | P0 |
| SC-ROUTE-03 | A2A 同步选 B | 同上 | 发送 A2A SendMessage，params.metadata.model_name="alias-b"（method="SendMessage"，完整 JSON 见 §7.2） | 请求路由到端点 B；Task 状态为 completed；响应含 "MODEL_B_RESPONSE" | L2 §2.3, §7.2 | P0 |
| SC-ROUTE-04 | A2A 流式选 B | 同上 | 发送 A2A SendStreamingMessage，params.metadata.model_name="alias-b" | SSE 事件流；最终内容含 "MODEL_B_RESPONSE" | L2 §2.1, §2.3 | P1 |
| SC-ROUTE-05 | B 后新请求缺省回 A | 目录 {alias-a, alias-b} | Step1: 发请求指定 model_name="alias-b"；Step2: 新请求不传 model_name（不同 conversationId） | Step1: 响应含 "MODEL_B_RESPONSE"；Step2: 响应含 "MODEL_A_RESPONSE"（不沿用 B，显式注入根默认 ID） | L2 §7.2 新请求缺省 | P0 |
| SC-ROUTE-06 | 同会话 A→B→缺省 A | 目录 {alias-a, alias-b}，同一 conversationId | Step1: 发请求指定 model_name="alias-a"；Step2: 同会话发请求指定 model_name="alias-b"；Step3: 同会话发请求不传 model_name | Step1: "MODEL_A"；Step2: "MODEL_B"；Step3: "MODEL_A"（B 后缺省回 A，不沿用 B） | L2 §3.2, §7.2 新请求缺省 | P0 |
| SC-ROUTE-07 | 缺省显式注入根默认 ID | 目录 {alias-a, alias-b}，根默认=alias-a | 发请求不传 model_name → 检查 mock 端点 A 收到的请求 | mock 端点 A 收到请求，证明缺省时显式注入根默认内部 ID（非 null，非走旧路径） | L2 §1.2, §3.2 | P0 |

### 5.4 所选模型调用失败（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-FAIL-01 | 401 认证失败 | 目录 {alias-a, alias-b}；mock 端点 B 返回 401 | 发请求指定 model_name="alias-b" | Core 重试耗尽后最终失败 → 客户端收到执行失败；不自动调用 alias-a；错误信息不泄露 api-key | L2 §2.4, §7.3 | P0 |
| SC-FAIL-02 | 5xx 服务端错误 | 目录 {alias-a, alias-b}；mock 端点 B 返回 500 | 发请求指定 model_name="alias-b" | 执行失败通道；不自动换模型 | L2 §7.3 | P0 |
| SC-FAIL-03 | 连接失败 | 目录 {alias-a, alias-b}；alias-b 的 API_BASE 指向不可达地址 | 发请求指定 model_name="alias-b" | 执行失败通道；不自动换模型 | L2 §7.3 | P0 |
| SC-FAIL-04 | 超时 | 目录 {alias-a, alias-b}；mock 端点 B 设置 30s 延迟（超过 timeout） | 发请求指定 model_name="alias-b" | 执行失败通道（超时）；不自动换模型 | L2 §7.3 | P0 |
| SC-FAIL-05 | SSE 部分输出后失败 | 目录 {alias-a, alias-b}；mock 端点 B 先返回部分内容后返回 500 | 发流式请求指定 model_name="alias-b"（stream=true） | SSE 已输出部分文本 → 随后标准错误 → 实际 data 为错误并终止，无成功完成；HTTP 可能仍为 200，客户端须检查实际 data 和终止状态 | L2 §3.4, §7.3 流中最终失败 | P0 |
| SC-FAIL-06 | A2A failed 后不 completed | 目录 {alias-a, alias-b}；mock 端点 B 返回 500 | 发送 A2A SendMessage 指定 model_name="alias-b" | Task 状态为 failed；failed 后不转为 completed | L2 §2.3, §7.3 | P0 |
| SC-FAIL-07 | Core 重试最终成功仍成功 | 目录 {alias-a, alias-b}；mock 端点 B 第一次 500 但重试后成功 | 发请求指定 model_name="alias-b" | 请求成功（Core 重试最终成功）；响应含 "MODEL_B_RESPONSE" | L2 §3.4 Core 同模型重试 | P1 |
| SC-FAIL-08 | 错误信息不泄露凭据 | 目录 {alias-a, alias-b}；mock 端点 B 返回 401 | 发请求指定 model_name="alias-b" → 检查错误响应和日志 | 错误响应和日志不含完整 api-key；使用脱敏失败摘要 | L2 §3.4, §4.4 | P0 |
| SC-FAIL-09 | 旧无目录流式错误兼容 | `model-selection-enabled: false`，无目录；mock 端点 A 返回流式外层 type=error | 发流式请求（不传 model_name） | 原 data 的 code/retryable 等字段保留，不被新逻辑重写；错误终止 | L2 §3.5, §7.3 旧无目录外层错误 | P1 |
| SC-FAIL-10 | 正常业务数据含 error 不误判 | 目录 {alias-a, alias-b}；响应正常但包含 "error" 文本字段 | 发请求指定 model_name="alias-b" | 不通过递归扫描误判为失败；请求正常完成 | L2 §7.3 正常业务数据 | P1 |

### 5.5 响应完整性验证（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-VERIFY-01 | mock 收到的完整配置 | 目录 {alias-a→4000(key-a, provider-chat-a), alias-b→4001(key-b, provider-chat-b)}；temperature=0.6, topP=0.8, timeout=60s | 发请求指定 model_name="alias-b" → 检查 mock 端点 B 收到的请求记录 | 路径=http://localhost:4001/v1；供应商请求体 model=provider-chat-b；认证头=Bearer key-b（脱敏断言）；公共参数 temperature=0.6, top_p=0.8 | L2 §1.2, §7.2 | P0 |
| SC-VERIFY-02 | 同端点不同供应商模型名 | 目录配置：alias-a→端点 X(provider-chat-a), alias-b→端点 X(provider-chat-b)（同一 mock 端点，不同 MODEL_NAME） | 分别发请求指定 alias-a 和 alias-b → 检查 mock 端点 X 收到的两次请求 | 两次请求到达同一端点但供应商请求体 model 分别为 provider-chat-a 和 provider-chat-b | L2 §5.3 | P1 |

### 5.6 注册隔离与生命周期（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-ISO-01 | 唯一 scope 隔离 | 同 JVM 两实例 X(scope=x) 和 Y(scope=y)，各自配置 alias-a/alias-b 指向不同端点 | 分别向 X 和 Y 发请求指定 model_name="alias-b" | X 的请求到达 X 的端点 B；Y 的请求到达 Y 的端点 D；Core 内部 ID 互不冲突 | L2 §3.1, §7.2 多实例同名别名 | P0 |
| SC-ISO-02 | 重复 start 幂等 | 同一 Handler 已成功 start | 再次调用 start() | 幂等，不重复注册，不报错 | L2 §3.1, §7.1 | P1 |
| SC-ISO-03 | 冲突不覆盖 | 同一 Runner 已有其他 scope 注册的 alias-a | Handler start 时尝试注册同名的自身 alias-a | 注册冲突不覆盖他人资源；诊断日志记录冲突但不覆盖 | L2 §3.1 | P1 |
| SC-ISO-04 | 失败仅回滚己方条目 | 目录有 N 个条目，第 N 个冲突 | 启动 | 仅回滚本次成功新增的 ID；不删除外部冲突资源；启动不成功 | L2 §3.1, §7.2 注册途中失败 | P0 |
| SC-ISO-05 | 普通 stop 不删除部署模型 | 目录已注册，服务运行中 | 停止 hosted Handler（stop） | 不删除部署模型；非 hosted stop 沿用 Runner 生命周期并重置自身注册标记 | L2 §3.1 | P1 |

### 5.7 内部续接（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-RESUME-01 | batch resume 保留选择 | 已接受请求选 alias-b | 触发 batch resume | 仍选择 alias-b；快照无密钥和 Core ID | L2 §3.3, §7.2 内部续接 | P1 |
| SC-RESUME-02 | snapshot 恢复保留选择 | 同上 | 触发 snapshot 恢复 | 仍选择 alias-b；continuationRequest 校验恢复 | L2 §3.3 | P1 |
| SC-RESUME-03 | 旧快照无字段默认 | 旧快照没有 modelName 字段 | 恢复旧快照请求 | 按默认处理（使用根默认 alias-a） | L2 §3.3 旧快照没有字段 | P2 |
| SC-RESUME-04 | 失效别名拒绝 | 重启后 alias-b 已从新目录删除；旧快照有 modelName="alias-b" | 恢复旧快照请求 | 拒绝（执行失败通道） | L2 §3.3 重启后别名已删除 | P1 |

### 5.8 兼容性（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-COMPAT-01 | 旧配置开关关闭 | `model-selection-enabled: false`；`apiconfig.json` 仅根默认 | 启动 → 发请求不传 model_name | 服务正常启动；请求使用旧单模型配置完成；原输入和错误表面不变；新增列表不被主动消费 | L2 §3.5, §7.2 老版本配置 | P0 |
| SC-COMPAT-02 | 无目录不选模保持旧路径 | 无目录（不创建 LlmModelCatalog Bean） | 发请求不传 model_name | 保持原输入，不注入 model_id；行为与升级前一致 | L2 §3.5, §6.4 | P0 |
| SC-COMPAT-03 | 模型变更生效 | Phase1: 目录 {alias-a, alias-b}；Phase2: 修改 apiconfig.json（删除 alias-b，新增 alias-c）→ 重启 | Phase1: 验证 alias-b 可用；Phase2: 验证 alias-c 可用 → 验证 alias-b 被拒绝（非回退默认） | Phase2 重启后 alias-c 可用；alias-b 请求 → Runner 前拒绝（执行失败，不回退默认） | L2 §6.4 变更采用重启 | P1 |
| SC-COMPAT-04 | 根配置重排不改变默认 | `apiconfig.json` 根默认=alias-a，MODELS=[alias-b, alias-c]；重排为 MODELS=[alias-c, alias-b] | 启动 → 发请求不传 model_name | 根默认仍为 alias-a（列表顺序不改变根默认） | L2 §6.2 列表顺序不改变根默认 | P1 |

### 5.9 请求间隔离与并发（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CONC-01 | 不同会话并发各自路由 | 目录 {alias-a, alias-b}；mock 端点 A 和 B 设置 barrier（等两条请求都到达后再放行） | 并发发送请求1（conv-1, model_name="alias-a"）和请求2（conv-2, model_name="alias-b"） | 请求1 响应含 "MODEL_A"，请求2 响应含 "MODEL_B"；互不污染 | L2 §7.1 输入隔离, §7.4 ReAct 并发 | P0 |
| SC-CONC-02 | 不改共享默认 | 目录 {alias-a, alias-b} | 发请求指定 model_name="alias-b" → 新请求不传 model_name → 检查共享 Agent 的"当前模型" | 请求1 使用 B；请求2 使用 A；请求不改变共享 Agent 的"当前模型"或 conversation 永久默认 | L2 §3.2, §7.1 | P0 |
| SC-CONC-03 | 不提升伪造 Core 字段 | 目录 {alias-a, alias-b} | POST /v1/query，body 含 dynamic_model_id="internal-id"（非 model_name） | 不提升客户端任意 metadata 的 dynamic_model_id/target_model_id 到顶层；使用根默认 | L2 §3.2 顶层 model_id | P1 |

### 5.10 配置加载失败隔离（Component）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-ISOLATE-01 | 错误配置不影响正常实例 | Step1: 启动正常实例（multi-model profile，端口 8095）；Step2: 启动错误实例（bad-config profile，端口 8099，apiconfig.json 格式错误） | Step1 → Step2 | Step1: 正常实例启动成功，可服务；Step2: 错误实例启动失败（fail-fast）；正常实例仍可服务（不受影响）；错误根因为配置校验（非缺 jar/端口占用） | L2 §1.3, §7.3 | P0 |

### 5.11 已知限制记录（Informational）

> 以下场景记录 Core 已知限制，**不作为本版通过结论**。测试执行时记录实际行为，标注为 KNOWN_LIMITATION。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 来源 | 优先级 |
|---|---|---|---|---|---|---|
| SC-LIMIT-01 | Core 模型解析阶段可能回退默认 | 目录 {alias-a, alias-b}；Core 内部 fallback 路径 | 发请求指定 model_name="alias-b" → 模拟 Core 解析 fallback | 记录实际行为：Core 可能在解析阶段回退默认（§1.3 #1）；不等于 Runtime 在 B 的 HTTP 调用失败后自动切换 A | L2 §1.3 #1 | Info |
| SC-LIMIT-02 | 辅助调用可能滞后 | 目录 {alias-a, alias-b} | 发请求指定 model_name="alias-b" → 检查辅助调用（压缩/摘要）使用的模型 | 记录实际行为：辅助调用可能仍用 alias-a，主推理用 alias-b（§1.3 #2）；用户已接受该时序边界 | L2 §1.3 #2 | Info |
| SC-LIMIT-03 | 共享 DeepAgent 并发结果隔离缺陷 | 目录 {alias-a, alias-b}；使用共享 DeepAgent task loop | 并发请求1(conv-1, alias-a) 和请求2(conv-2, alias-b) | 记录实际行为：A/B 实际调用分别正确，但会话 A 可能得到 B 的最终结果（§1.3 #3）；本版暂不修复，保留 ReAct 并发测试 | L2 §1.3 #3 | Info |
| SC-LIMIT-04 | DeepAgent 特殊最终失败流式缺口 | 目录 {alias-a, alias-b}；DeepAgent 返回 `final_result.error=completion_timeout` 但缺少 result_type | 发流式请求 → 检查 SSE 终止行为 | 记录实际行为：当前仍可能转发普通 chunk 并 onComplete（§1.3 #4）；与已支持的 `answer.payload.result_type=error` 不同 | L2 §1.3 #4 | Info |

---

## 6. 可观测子断言

> 黑盒边界：仅经 HTTP/A2A 请求、mock 端点请求记录、进程日志、启动行为观测，不读 SUT 内部类。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### SC-CFG-01.A — 配置注册的模型可用

- **Given**：`apiconfig.json` 配置根默认 alias-a + 列表 alias-b；`model-selection-enabled: true`。
- **When**：启动服务，分别发请求指定 model_name="alias-b" 和不传 model_name。
- **Then**：alias-b 请求路由到端点 B；缺省请求路由到端点 A。
- **PASS**：两个响应标识正确。**FAIL**：任一响应标识错误或请求失败。

### SC-CFG-02.A — 公开别名与供应商模型名区分

- **Given**：MODEL_ID="alias-b"，MODEL_NAME="provider-chat-b"（两者不同）。
- **When**：发请求指定 model_name="alias-b" → 检查 mock 端点 B 收到的请求体。
- **Then**：供应商请求体 model 字段为 "provider-chat-b"。
- **PASS**：model 字段正确。**FAIL**：model 字段为 "alias-b"（公开别名泄露到供应商请求）。

### SC-ROUTE-05.A — B 后新请求缺省回 A

- **Given**：目录 {alias-a, alias-b}，根默认=alias-a。
- **When**：Step1: 发请求指定 model_name="alias-b"；Step2: 新请求不传 model_name。
- **Then**：Step1 响应含 "MODEL_B_RESPONSE"；Step2 响应含 "MODEL_A_RESPONSE"。
- **PASS**：两步标识正确。**FAIL**：Step2 路由到 B（未隔离选择）。

### SC-ROUTE-07.A — 缺省显式注入根默认 ID

- **Given**：目录 {alias-a, alias-b}，根默认=alias-a，`model-selection-enabled: true`。
- **When**：发请求不传 model_name → 检查 mock 端点 A 收到的请求。
- **Then**：mock 端点 A 收到请求（证明缺省时显式注入根默认内部 ID，而非走旧路径）。
- **PASS**：端点 A 收到请求。**FAIL**：端点 A 未收到请求（可能走了旧路径或未注入）。

### SC-FAIL-01.A — 401 不自动换模型

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 返回 401。
- **When**：发请求指定 model_name="alias-b"。
- **Then**：客户端收到执行失败；不自动调用 alias-a（检查 mock 端点 A 未收到请求）。
- **PASS**：执行失败 + 端点 A 未收到请求。**FAIL**：自动调用 A 或返回成功。

### SC-FAIL-05.A — SSE 部分输出后失败终止

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 先返回部分内容后返回 500。
- **When**：发流式请求指定 model_name="alias-b"。
- **Then**：SSE 已输出部分文本 → 随后实际 data 变为错误并终止；无成功完成。
- **PASS**：实际 data 为错误 + 终止。**FAIL**：正常完成（onComplete）或错误 data 未改变。
- **注意**：HTTP 可能仍为 200，客户端须检查实际 data 和终止状态。

### SC-FAIL-06.A — A2A failed 后不 completed

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 返回 500。
- **When**：发送 A2A SendMessage 指定 model_name="alias-b"。
- **Then**：Task 状态为 failed；failed 后不转为 completed。
- **PASS**：failed 终态。**FAIL**：completed 或 failed 后转 completed。

### SC-FAIL-08.A — 错误信息不泄露凭据

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 返回 401。
- **When**：发请求指定 model_name="alias-b" → 检查错误响应和进程日志。
- **Then**：错误响应和日志不含完整 api-key；使用脱敏失败摘要。
- **PASS**：无完整 api-key。**FAIL**：响应或日志含完整 api-key。

### SC-VERIFY-01.A — mock 收到的完整配置

- **Given**：目录 {alias-a→4000(key-a, provider-chat-a), alias-b→4001(key-b, provider-chat-b)}；temperature=0.6, topP=0.8。
- **When**：发请求指定 model_name="alias-b" → 检查 mock 端点 B 收到的请求记录。
- **Then**：路径=http://localhost:4001/v1；供应商请求体 model=provider-chat-b；认证头含 key-b（脱敏断言）；公共参数 temperature=0.6, top_p=0.8。
- **PASS**：全部字段正确。**FAIL**：任一字段错误。

### SC-ISO-01.A — 唯一 scope 隔离

- **Given**：同 JVM 两实例 X(scope=x) 和 Y(scope=y)，各自配置 alias-a/alias-b 指向不同端点。
- **When**：分别向 X 和 Y 发请求指定 model_name="alias-b"。
- **Then**：X 的请求到达 X 的端点 B；Y 的请求到达 Y 的端点 D。
- **PASS**：各自路由正确。**FAIL**：路由到对方的端点（scope 未隔离）。

### SC-CONC-01.A — 并发各自路由（执行重叠保证）

- **Given**：目录 {alias-a, alias-b}；mock 端点 A 和 B 设置 barrier（等两条请求都到达后再放行）。
- **When**：并发发送请求1（conv-1, alias-a）和请求2（conv-2, alias-b）。
- **Then**：请求1 响应含 "MODEL_A"，请求2 响应含 "MODEL_B"。
- **PASS**：两个响应标识正确。**FAIL**：任一响应标识错误。
- **注意**：使用 barrier（而非仅 CompletableFuture/CountDownLatch）确保两条请求都到达 mock 后再放行，保证真正的执行重叠。

### SC-ISOLATE-01.A — 错误配置不影响正常实例

- **Given**：正常实例（端口 8095）和错误实例（端口 8099，apiconfig.json 格式错误）。
- **When**：Step1: 启动正常实例；Step2: 启动错误实例；Step3: 向正常实例发请求。
- **Then**：Step1 正常实例启动成功；Step2 错误实例启动失败（fail-fast）；Step3 正常实例仍可服务。
- **PASS**：三步均满足。**FAIL**：正常实例受影响或错误实例启动成功。
- **注意**：必须先启动正常实例再启动错误实例，且检查正常实例仍可服务。不能捕获任意异常作为"配置校验通过"。

### SC-LIMIT-03.A — 共享 DeepAgent 并发结果隔离缺陷

- **Given**：目录 {alias-a, alias-b}；使用共享 DeepAgent task loop。
- **When**：并发请求1(conv-1, alias-a) 和请求2(conv-2, alias-b)。
- **Then**：记录实际行为——A/B 实际调用分别正确，但会话 A 可能得到 B 的最终结果。
- **标注**：KNOWN_LIMITATION（§1.3 #3），不作为通过结论。本版暂不修复 Core，保留 ReAct 并发测试（SC-CONC-01）。

---

## 7. 测试数据

### 7.1 apiconfig.json 配置模板

#### 7.1.1 正常配置（根默认 alias-a + 列表 alias-b）

```json
{
  "MODEL_ID": "alias-a",
  "MODEL_PROVIDER": "openai",
  "API_KEY": "mock-key-a",
  "API_BASE": "http://localhost:4000/v1",
  "MODEL_NAME": "provider-chat-a",
  "LLM_SSL_VERIFY": true,
  "MODELS": [
    {
      "MODEL_ID": "alias-b",
      "MODEL_PROVIDER": "openai",
      "API_KEY": "mock-key-b",
      "API_BASE": "http://localhost:4001/v1",
      "MODEL_NAME": "provider-chat-b",
      "LLM_SSL_VERIFY": true
    }
  ]
}
```

> **注意**：MODEL_ID 是公开别名，MODEL_NAME 是供应商模型名，两者必须不同。provider 必须是受支持的值（如 "openai"），不能随意写 "mock"。

#### 7.1.2 开关关闭配置

```yaml
openjiuwen:
  service:
    llm:
      model-selection-enabled: false
      config-file: ./apiconfig.json
      temperature: 0.6
      top-p: 0.8
      timeout: 60s
```

#### 7.1.3 错误配置（格式错误）

```json
{
  "MODEL_ID": "alias-a",
  "MODEL_PROVIDER": "openai",
  "API_KEY": "mock-key-a",
  "API_BASE": "http://localhost:4000/v1",
  "MODEL_NAME": "provider-chat-a",
  "MODELS": "not-an-array"
}
```

### 7.2 请求模板

#### 7.2.1 Query 同步请求

```json
POST /v1/query
{
  "conversation_id": "conv-001",
  "model_name": "alias-b",
  "message": "请解释这段代码",
  "stream": false
}
```

#### 7.2.2 Query 流式请求

```json
POST /v1/query
{
  "conversation_id": "conv-001",
  "model_name": "alias-b",
  "message": "请解释这段代码",
  "stream": true
}
```

> **注意**：检查实际 SSE data 内容和终止状态，不能仅凭 HTTP 200 判定成功。

#### 7.2.3 A2A SendMessage 请求

```json
POST /a2a/
{
  "jsonrpc": "2.0",
  "id": "request-001",
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "message-001",
      "role": "user",
      "parts": [{"kind": "text", "text": "请解释这段代码"}]
    },
    "metadata": {"model_name": "alias-b"}
  }
}
```

> **注意**：方法名是 `SendMessage`（当前仓库的 A2AMethods），不是 `message/send`。model_name 在 params.metadata 中，不是 params.message.metadata。

#### 7.2.4 A2A SendStreamingMessage 请求

```json
POST /a2a/
{
  "jsonrpc": "2.0",
  "id": "request-002",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "messageId": "message-002",
      "role": "user",
      "parts": [{"kind": "text", "text": "请解释这段代码"}]
    },
    "metadata": {"model_name": "alias-b"}
  }
}
```

### 7.3 参数边界测试数据

| 测试输入 | 期望行为 | 入口 |
|---|---|---|
| model_name 缺失 | 正常执行，使用根默认 | Query / A2A |
| model_name=null | 正常执行，使用根默认 | Query / A2A |
| model_name="" | 400 / -32602 | Query / A2A |
| model_name="  " | 400 / -32602（trim 后为空） | Query / A2A |
| model_name=123 | 400 / -32602（类型非法） | Query MVC / A2A |
| model_name=true | 400 / -32602 | Query MVC / A2A |
| model_name=["a","b"] | 400 / -32602 | Query MVC / A2A |
| model_name={"key":"val"} | 400 / -32602 | Query MVC / A2A |
| model_name="alias-x"（未知） | 500/AGENT_EXECUTION_FAILED | Query / A2A |
| body.model_id="alias-b" | 不触发选模（非正式位置） | Query |
| params.message.metadata.model_name="alias-b" | 不触发选模（非正式位置） | A2A |
| header X-Model="alias-b" | 不触发选模 | Query |

### 7.4 错误响应模板

#### Query 同步执行失败

```json
{
  "type": "error",
  "code": "AGENT_EXECUTION_FAILED",
  "error": "agent execution failed",
  "conversation_id": "conv-001"
}
```

#### A2A failed 终态

```json
{
  "jsonrpc": "2.0",
  "id": "request-001",
  "result": {
    "state": "failed"
  }
}
```

> **注意**：failed 后不转为 completed。

---

## 8. 框架落点

| 项 | 值 |
|---|---|
| 测试类（配置与启动） | `LlmConfigResolverTest`、`LlmAutoConfigurationTest` |
| 测试类（参数边界） | `QueryRequestTest`、`QueryMvcControllerTest`、`QueryWebFluxControllerTest`、`A2aJsonRpcParamsParserTest` |
| 测试类（路由与失败） | `RequestModelSelectionTest`（真实 Runner + HTTP mock） |
| 测试类（Handler 行为） | `JiuwenCoreAgentHandlerTest` |
| 测试类（续接） | `A2AEnabledServeOrchestratorTest`、`RemoteInvocationBatchMapperTest` |
| 测试类（装配） | `CoreRunnerLifecycleCoordinatorTest`、`AgentCoreAdaptersAutoConfigurationTest` |
| 测试类（A2A 终态） | `A2AAgentExecutorTest`、`A2aJsonRpcControllerTest` |
| 标签 | `@Tag("component")` `@Tag("dynamic-model")` `@Tag("feat-051")` |
| Allure | `@Feature("FEAT-051")` `@Story("request-level-model-selection")` |

### Mock 端点配置

| 端点 | 端口 | MODEL_ID | MODEL_NAME | API_KEY | 行为 |
|---|---|---|---|---|---|
| A | 4000 | alias-a | provider-chat-a | mock-key-a | 返回 "MODEL_A_RESPONSE" |
| B | 4001 | alias-b | provider-chat-b | mock-key-b | 返回 "MODEL_B_RESPONSE"（可配置延迟/错误） |
| C | 4002 | — | — | — | SC-ISO-01 实例 Y 的 alias-a 端点 |
| D | 4003 | — | — | — | SC-ISO-01 实例 Y 的 alias-b 端点 |

### 环境配置

```yaml
openjiuwen:
  service:
    llm:
      model-selection-enabled: true
      config-file: ./apiconfig.json
      temperature: 0.6
      top-p: 0.8
      timeout: 60s
```

---

## 9. 运行方式

```bash
# 全部测试
mvn -B -pl service/agent-service-adapters/agent-service-adapters-agentcore -am test \
  '-Dtest=RequestModelSelectionTest,JiuwenCoreAgentHandlerTest,CoreRunnerLifecycleCoordinatorTest,AgentCoreAdaptersAutoConfigurationTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false'

# 仅路由与失败测试
mvn -B -pl service/agent-service-adapters/agent-service-adapters-agentcore -am test \
  '-Dtest=RequestModelSelectionTest'

# 仅 Handler 行为测试
mvn -B -pl service/agent-service-adapters/agent-service-adapters-agentcore -am test \
  '-Dtest=JiuwenCoreAgentHandlerTest'

# App 层参数边界测试
mvn -B -pl service/agent-service-app -am test \
  '-Dtest=QueryRequestTest,QueryMvcControllerTest,QueryWebFluxControllerTest,A2aJsonRpcParamsParserTest,A2AAgentExecutorTest'

# 配置解析测试
mvn -B -pl service/agent-service-app -am test \
  '-Dtest=LlmConfigResolverTest,LlmAutoConfigurationTest'
```

---

## 10. 覆盖追溯

### 10.1 L2 设计文档 §7 验收标准覆盖

| L2 验收项 | 场景 ID | 状态 |
|---|---|---|
| §7.1 配置及兼容 | SC-CFG-01/03/07/08/10~15, SC-COMPAT-01/02/04 | ✅ |
| §7.1 请求级选择 | SC-ROUTE-01~07, SC-VERIFY-01/02 | ✅ |
| §7.1 注册隔离 | SC-ISO-01~05 | ✅ |
| §7.1 输入隔离 | SC-CONC-01~03 | ✅ |
| §7.1 续接 | SC-RESUME-01~04 | ✅ |
| §7.1 失败及兼容 | SC-FAIL-01~10 | ✅ |
| §7.2 启用目录 | SC-CFG-01, SC-ROUTE-01 | ✅ |
| §7.2 新请求缺省 | SC-ROUTE-05/06/07 | ✅ |
| §7.2 原输入早返回 | （由 JiuwenCoreAgentHandlerTest 覆盖） | ✅ |
| §7.2 内部续接 | SC-RESUME-01/02 | ✅ |
| §7.2 老版本配置 | SC-COMPAT-01 | ✅ |
| §7.2 多实例同名别名 | SC-ISO-01 | ✅ |
| §7.2 流中最终失败 | SC-FAIL-05 | ✅ |
| §7.2 注册途中失败 | SC-CFG-09, SC-ISO-04 | ✅ |
| §7.3 类型错误/空白 | SC-PARAM-01~13 | ✅ |
| §7.3 未知别名/无目录却指定 | SC-PARAM-08/09 | ✅ |
| §7.3 最终 401/5xx/连接失败/超时 | SC-FAIL-01~04 | ✅ |
| §7.3 answer.payload.result_type=error | SC-FAIL-05/06 | ✅ |
| §7.3 observer 回调抛错 | （由 JiuwenCoreAgentHandlerTest 覆盖） | ✅ |
| §7.3 旧无目录外层错误 | SC-FAIL-09 | ✅ |
| §7.3 正常业务数据含 error | SC-FAIL-10 | ✅ |
| §7.3 注册失败诊断 | SC-CFG-09, SC-ISO-04 | ✅ |
| §7.3 §1.3 已知缺口 | SC-LIMIT-01~04 | ✅ (Info) |

### 10.2 L2 §1.3 已知差距覆盖

| 差差距项 | 场景 ID | 测试策略 |
|---|---|---|
| #1 Core 模型解析阶段可能回退默认 | SC-LIMIT-01 | 记录实际行为，标注 KNOWN_LIMITATION |
| #2 辅助模型可能滞后 | SC-LIMIT-02 | 记录实际行为，标注 KNOWN_LIMITATION |
| #3 共享 DeepAgent 并发结果隔离缺陷 | SC-LIMIT-03 | 保留 ReAct 并发测试（SC-CONC-01），不测共享 DeepAgent 并发 |
| #4 DeepAgent 特殊最终失败流式缺口 | SC-LIMIT-04 | 记录实际行为，标注 KNOWN_LIMITATION |
| #5 DeepAgent Map 模型配置转换不等价 | — | 不测任意自定义 Map 接入 |
| #6 验证范围有限 | — | 本地测试使用 Maven 缓存依赖，不凭版本名保证源码与 jar 一致 |

### 10.3 与 FEAT-050 的互补关系

| Core 行为 | FEAT-051-RT 场景（Runtime 侧） | FEAT-050 场景（Core 侧） |
|---|---|---|
| 启动注册 | SC-CFG-01/09/10（配置注册验证） | SC-CORE-01~05（注册 Result/getModel/冲突/回滚/幂等） |
| 请求级路由 | SC-ROUTE-01~07（HTTP/A2A 路由） | SC-CORE-06~12（优先级链/隔离/不提升伪造字段） |
| 模型调用失败 | SC-FAIL-01~10（协议错误表面） | SC-CORE-13~20（Core 重试/最终失败/辅助异常） |
| 请求间隔离 | SC-CONC-01~03（Runtime 全链路隔离） | SC-CORE-21/22（并发/scope 隔离） |
| 配置加载 | SC-CFG-01~15（apiconfig.json 解析） | —（由 FEAT-051-RT 覆盖） |
| 参数边界 | SC-PARAM-01~13（类型校验） | —（由 FEAT-051-RT 覆盖） |
| 内部续接 | SC-RESUME-01~04（batch resume） | —（由 FEAT-051-RT 覆盖） |
| 兼容性 | SC-COMPAT-01~04（开关/无目录） | —（由 FEAT-051-RT 覆盖） |
| SDK 直连 CRUD | —（Runtime 不走此路径） | SC-CORE-23~32（SDK 客户场景） |
| SDK 直连优先级链 | —（Runtime 不走此路径） | SC-CORE-35~38（Core 内部回退） |

---

## 11. 风险与备注

### 11.1 关键风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| Core 内部 fallback 不可控 | SC-LIMIT-01 可能影响路由测试 | 记录为已知限制，不作为通过结论 |
| 辅助调用时序不可控 | SC-LIMIT-02 可能影响压缩跟随验证 | 用户已接受时序边界，不强制验证辅助调用一致性 |
| 共享 DeepAgent 并发缺陷 | SC-LIMIT-03 可能影响并发测试 | 保留 ReAct 并发测试，不测共享 DeepAgent 并发 |
| mock 端点 barrier 实现复杂度 | SC-CONC-01 需要真正的执行重叠 | 让 mock 等两条请求都到达后再放行，而非仅 CompletableFuture 起发 |
| HTTP 200 假阳性 | SSE 流式场景 HTTP 200 不代表成功 | 检查实际 SSE data 内容和终止状态 |
| 启动失败隔离假阳性 | SC-ISOLATE-01 可能因缺 jar/端口占用误判 | 先启动正常实例 → 再启动坏实例 → 检查正常实例仍可服务 → 核对配置错误根因 |
| provider 值不能随意声明 | 使用 HTTP mock 不代表 provider 可写 "mock" | 使用受支持的 provider 值（如 "openai"） |
| 公开别名与供应商模型名混用 | 测试配置全写相同字符串会掩盖路由问题 | MODEL_ID 与 MODEL_NAME 必须不同 |

### 11.2 设计决策说明

1. **测试宿主选择**：使用 `JiuwenCoreAgentHandler`（注入 `LlmModelCatalog`），不使用 `travel-demo-mainplan`（它实际使用 `JiuwenCoreAgentExtHandler` 并自行装配模型，属于后续版本）。仅升级 Runtime 依赖不能证明示例已支持选模。

2. **配置方式**：使用 `apiconfig.json`（根对象=默认模型 + MODELS 列表追加），不使用 `openjiuwen.model-management.models` YAML 列表。根默认天然属于目录，不需要"列表漏配后自动补注册并 WARN"。

3. **未知别名处理**：Runtime 在进入 Runner 前拒绝未知公开别名，不调用任何 LLM。Core 直连测试可单独验证其遗留 fallback 行为，但不能扩大为 Runtime 接受未知别名。

4. **A2A 方法名**：使用 `SendMessage` / `SendStreamingMessage`（当前仓库 A2AMethods），不是 `message/send`。

5. **SSE 断言**：Runtime 输出自己的事件数据，不假定供应商流与客户端流逐块对应。检查实际 data 内容和终止状态，不能仅凭 HTTP 200 判定成功。

6. **Core CRUD 互补关系**：Core SDK 直连 CRUD（addModel/removeModel/updateModel/...）面向"无 Runtime 客户"场景，由 FEAT-050 SC-CORE-23~32 覆盖。Core 通过 Runtime 端到端验证的行为（启动注册、请求级路由、模型调用失败处理、请求间隔离）是本版验收的一部分，由 FEAT-050 SC-CORE-01~22 与本方案互补覆盖。

7. **三进程一致性**：降级为较低优先级部署回归（SC-COMPAT-03 覆盖重启生效），不为本版测试新增管理 REST。

8. **并发测试设计**：使用 mock barrier（等两条请求都到达后再放行）确保真正的执行重叠，而非仅 `CompletableFuture`/`CountDownLatch` 起发。
