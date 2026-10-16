---
feature_id: FEAT-050
test_type: component
scope: core-model-management
deployable_units: [agent-runtime-java, agent-core-java]
sut: JiuwenCoreAgentHandler（Runtime 端到端）+ Runner.resourceMgr（SDK 直连）
features: [FEAT-050]
updated: 2026-10-10
owner: TBD
priority: P0
status: designed
tags: [component, dynamic-model, feat-050]
depends_on:
  - agent-core-java 0.1.17 已实现模型管理 CRUD + 模型解析
  - agent-runtime-java 已实现请求级模型选择（FEAT-051-RT）
  - LLM 可用（支持至少 2 个不同 mock 端点）
  - apiconfig.json 配置文件（根默认 + 列表追加）
  - 测试 profile：application-openjiuwen.yml（-Dtest.env=openjiuwen）
---

# FEAT-050 — agent-core 模型管理验收方案

> **定位**：本方案验证 agent-core 的模型管理能力和模型解析行为。
> Runtime 端到端测试（FEAT-051-RT）自然覆盖了大部分 Core 行为（启动注册、请求解析、隔离、失败处理）；
> 本方案补充 Core 专有的验收点：SDK 直连 CRUD（面向"无 Runtime 客户"）、
> 模型解析优先级链内部行为、模型列表即时切换、并发安全。
>
> **与 FEAT-051-RT 的关系**：
> - FEAT-051-RT 通过 HTTP/A2A 端到端验证 Runtime → Core 的完整链路（配置→启动→请求→路由→调用→响应/失败）。
> - FEAT-050 补充验证 Core 专有行为：SDK 直连 CRUD、优先级链内部回退、即时切换、并发安全。
> - 两者互补，共同覆盖 FRS-050 的全部验收标准。
>
> **已知限制**（L2 §1.3）：
> - Core 模型解析阶段可能回退默认（#1）——用户已接受，记录为已知限制
> - 辅助模型可能滞后（#2）——用户已接受时序边界
> - 共享 DeepAgent 并发结果隔离缺陷（#3）——暂缓修复，保留 ReAct 并发测试
> - DeepAgent 特殊最终失败流式缺口（#4）——后续与 Core 协调

---

## 1. 测试目标

以 `JiuwenCoreAgentHandler`（Runtime 端到端）和 `Runner.resourceMgr`（SDK 直连）为测试对象，验证 agent-core 动态模型配置的全部外部可观测行为：

1. **启动注册**：Handler 启动时调 core addModel 注册 typed Model + getModel 验证。（与 FEAT-051-RT 互补，Core 侧验证注册 Result 和 getModel 一致性）
2. **请求级模型解析**：resolveModel 按优先级链 ❶请求级→❷默认→❸全局默认 三级回退。
3. **请求间隔离**：B→缺省→A 隔离、不改共享默认、不提升伪造 Core 字段。（与 FEAT-051-RT SC-CONC-01~03 互补）
4. **模型列表即时切换**：增删后新请求即时使用新列表，在途请求自然完成。（SDK 直连场景）
5. **并发安全**：模型管理操作在多线程下正确无冲突。
6. **SDK 直连 CRUD**：新增/更新/删除/查询/存在性检查/设置默认/查询默认，含校验与异常。（面向"无 Runtime 客户"）
7. **模型调用失败处理**：所选模型调用失败后 Core 重试与最终失败暴露。（与 FEAT-051-RT SC-FAIL-01~10 互补，Core 侧重重试行为）

**本方案不覆盖**（由 FEAT-051-RT 验收方案负责）：

- 配置文件加载（apiconfig.json 解析、MODELS 列表、逐项解密）→ FEAT-051-RT SC-CFG-01~10
- 请求参数解析与边界（body.model_name / A2A metadata.model_name 类型校验）→ FEAT-051-RT SC-PARAM-01~13
- 协议错误表面（HTTP 400/500、A2A -32602/failed、SSE 终止）→ FEAT-051-RT SC-FAIL-01~10
- 内部续接（batch resume / snapshot 恢复）→ FEAT-051-RT SC-RESUME-01~04
- 兼容性（开关关闭、无目录不选模）→ FEAT-051-RT SC-COMPAT-01~04

---

## 2. 范围与非范围

### 范围

- **Core 启动注册行为**：addModel 的 Result 验证、getModel 身份验证、注册冲突不覆盖、失败回滚。（与 FEAT-051-RT 互补）
- **Core 模型解析优先级链**：❶请求级 → ❷DeepAgentConfig 默认 → ❸全局默认，三级回退内部行为。
- **Core 请求间隔离**：B→缺省→A、不改共享默认、不提升伪造 Core 控制字段。（与 FEAT-051-RT 互补）
- **Core 模型调用失败处理**：所选模型 401/5xx/连接失败/超时后 Core 重试与最终失败暴露。（与 FEAT-051-RT 互补）
- **SDK 直连 CRUD**：addModel / updateModel / removeModel / listModelIds / hasModel / setDefaultModelId / getDefaultModelId，含校验与异常。
- **模型列表即时切换**：增删后新请求即时生效，在途请求自然完成。（SDK 直连）
- **并发安全**：并发新增模型线程安全。（SDK 直连）

### 非范围

- 配置文件加载与解析（→ FEAT-051-RT）
- 请求参数解析与边界（→ FEAT-051-RT）
- 协议错误表面（HTTP/A2A/SSE）（→ FEAT-051-RT）
- 内部续接（→ FEAT-051-RT）
- 兼容性：开关关闭、无目录不选模（→ FEAT-051-RT）
- Context 压缩器跟随时序（L2 §1.3 #2，用户已接受时序边界，Core 专项后续负责）
- 共享 DeepAgent 并发结果隔离（L2 §1.3 #3，暂缓修复）
- REST API 模型管理接口（特性不提供）
- Redis 集群同步（特性不引入）

---

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-051-request-level-model-selection.md`（status: draft, updated 2026-10-09） | L2 设计文档，§1.3 Core 已知差距、§3.1 启动注册、§3.2 指定模型及缺省请求、§3.4 最终失败与流终止、§4.3 Handler 与装配、§7.1 能力矩阵验收。 |
| `develop/01-scenarios/FRS-050-dynamic-model-config.md`（status: 评审中, v1.1） | 特性需求说明书，Core 相关验收标准（AC-01~07/11/14~21/26/27/30），功能要求 FR-01~08/11~15/20~22。 |

> **注意**：当 FRS-050 与 L2 设计文档冲突时，以 L2 设计文档为准。FRS-050 中关于 Runtime 配置方式的部分已被 L2 的 apiconfig.json 方案取代。

---

## 4. 部署拓扑

### 4.1 Runtime 端到端拓扑（与 FEAT-051-RT 共用，验证 Core 行为）

```text
JUnit 黑盒驱动
  +-> mock LLM 端点 A（http://localhost:4000/v1，返回 "MODEL_A_RESPONSE"）
  +-> mock LLM 端点 B（http://localhost:4001/v1，返回 "MODEL_B_RESPONSE"）
  `-> agent-runtime（单 JVM / 随机端口）
        +-> apiconfig.json（根默认=alias-a，MODELS=[alias-b]）
        +-> JiuwenCoreAgentHandler.start()
        |     `-> Runner.resourceMgr.addModel() + getModel() 验证
        `-> 请求处理链 → ServeRequest.modelName → 目录查询 → Core model_id 注入
              `-> Runner.runAgent(inputs) → Core 解析 Model → LLM 调用 → 结果/失败
```

### 4.2 SDK 直连拓扑（Core 专有场景）

```text
JUnit 测试线程
  +-> 直接持有 agent-core Java API（Runner.resourceMgr）
  +-> mock LLM 端点（返回可识别标识）
  `-> 不经过 agent-runtime 请求处理链
        +-> addModel / updateModel / removeModel / listModelIds / hasModel
        +-> setDefaultModelId / getDefaultModelId
        +-> resolveModel
```

> **注意**：
> 1. `resourceMgr().hasModel` 等 API 签名需核实当前 ResourceMgr 实际接口，不能假定旧 API。
> 2. 重复注册、删除等操作需检查实际 Result 类型，不能统一假定抛某种异常。
> 3. 共享注册表测试只清理自有资源，避免"清空所有模型"污染其他测试。
> 4. 公开别名（MODEL_ID）与供应商模型名（MODEL_NAME）必须不同，不能全写相同字符串。

---

## 5. 测试场景矩阵

### 5.1 Core 启动注册行为（Runtime 端到端，与 FEAT-051-RT 互补）

> 以下场景通过 Runtime 端到端验证 Core 的启动注册行为，侧重 Core 侧的注册 Result 和 getModel 一致性。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-01 | 启动注册验证 | `apiconfig.json` 配置根默认 alias-a + 列表 alias-b；`model-selection-enabled: true` | 启动服务 → 发请求验证 alias-a 和 alias-b 均可用 | 启动成功；Handler.start() 调 core addModel 注册 typed Model，检查 Result 成功；getModel 返回同一对象；两个请求均正常路由 | L2 §3.1, §7.1 配置及兼容 | P0 |
| SC-CORE-02 | 注册冲突不覆盖 | 同一 Runner 已有其他 scope 注册的 alias-a | Handler start 时尝试注册同名的自身 alias-a | 注册冲突不覆盖他人资源；诊断日志记录冲突但不覆盖；不提供半份目录 | L2 §3.1, §7.2 多实例同名别名 | P1 |
| SC-CORE-03 | 注册失败回滚 | 目录有 N 个条目，第 N 个冲突 | 启动 | 仅回滚本次成功新增的 ID；不删除外部冲突资源；启动不成功；诊断日志含操作/ID/异常类及 Core code，不含原 message/cause 中的密钥 | L2 §3.1, §7.2 注册途中失败 | P0 |
| SC-CORE-04 | 重复 start 幂等 | 同一 Handler 已成功 start | 再次调用 start() | 幂等，不重复注册，不报错 | L2 §3.1 | P1 |
| SC-CORE-05 | 启动注册不请求远端 LLM | `apiconfig.json` 配置有效但 API_BASE 指向不可达地址 | 启动服务 | 服务正常启动（启动验证本地注册和可取性，不请求远端 LLM） | L2 §3.1 | P1 |

### 5.2 Core 请求级模型解析（Runtime 端到端，与 FEAT-051-RT 互补）

> 以下场景通过 Runtime 端到端验证 Core 的模型解析行为，侧重 Core 侧的优先级链回退和 resolveModel 内部行为。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-06 | 请求级路由命中 | 目录 {alias-a→端点 A, alias-b→端点 B}，根默认=alias-a | 发请求指定 model_name="alias-b" | Core 解析 ❶请求级 alias-b → 命中；请求使用 B，响应含 "MODEL_B_RESPONSE" | L2 §3.2, FR-05 | P0 |
| SC-CORE-07 | 未指定走默认 | 同上 | 发请求不传 model_name | Core 解析 ❶null → ❷根默认 alias-a → 命中；请求使用 A，响应含 "MODEL_A_RESPONSE" | L2 §3.2, FR-06 | P0 |
| SC-CORE-08 | 缺省显式注入根默认 ID | 目录 {alias-a, alias-b}，根默认=alias-a，`model-selection-enabled: true` | 发请求不传 model_name → 检查 mock 端点 A 收到的请求 | mock 端点 A 收到请求，证明缺省时显式注入根默认内部 ID（非 null，非走旧路径） | L2 §1.2, §3.2 | P0 |
| SC-CORE-09 | B 后新请求缺省回 A | 目录 {alias-a, alias-b} | Step1: 发请求指定 model_name="alias-b"；Step2: 新请求不传 model_name（不同 conversationId） | Step1: "MODEL_B"；Step2: "MODEL_A"（不沿用 B，Core 请求级隔离） | L2 §3.2, §7.2 新请求缺省 | P0 |
| SC-CORE-10 | 同会话 A→B→缺省 A | 目录 {alias-a, alias-b}，同一 conversationId | Step1: 指定 alias-a；Step2: 同会话指定 alias-b；Step3: 同会话不传 model_name | Step1: "MODEL_A"；Step2: "MODEL_B"；Step3: "MODEL_A"（B 后缺省回 A） | L2 §3.2, FR-12 | P1 |
| SC-CORE-11 | 不改共享默认 | 目录 {alias-a, alias-b} | 发请求指定 alias-b → 新请求不传 → 检查共享 Agent 的"当前模型" | 请求1 使用 B；请求2 使用 A；请求不改变共享 Agent 的"当前模型"或 conversation 永久默认 | L2 §3.2, §7.1 | P0 |
| SC-CORE-12 | 不提升伪造 Core 字段 | 目录 {alias-a, alias-b} | POST /v1/query，body 含 dynamic_model_id="internal-id"（非 model_name） | 不提升客户端任意 metadata 的 dynamic_model_id/target_model_id 到顶层；使用根默认 | L2 §3.2 顶层 model_id | P1 |

### 5.3 Core 模型调用失败处理（Runtime 端到端，与 FEAT-051-RT 互补）

> 以下场景通过 Runtime 端到端验证 Core 的模型调用失败处理行为，侧重 Core 侧重试行为和最终失败暴露。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-13 | 401 最终失败暴露 | 目录 {alias-a, alias-b}；mock 端点 B 返回 401 | 发请求指定 model_name="alias-b" | Core 重试耗尽后最终失败暴露给 Handler → 客户端收到执行失败；不自动调用 alias-a | L2 §2.4, §3.4 | P0 |
| SC-CORE-14 | 5xx 最终失败暴露 | 目录 {alias-a, alias-b}；mock 端点 B 返回 500 | 发请求指定 model_name="alias-b" | 执行失败通道；不自动换模型 | L2 §3.4 | P0 |
| SC-CORE-15 | 连接失败最终暴露 | 目录 {alias-a, alias-b}；alias-b 的 API_BASE 不可达 | 发请求指定 model_name="alias-b" | 执行失败通道；不自动换模型 | L2 §3.4 | P0 |
| SC-CORE-16 | 超时最终暴露 | 目录 {alias-a, alias-b}；mock 端点 B 30s 延迟 | 发请求指定 model_name="alias-b" | 执行失败通道（超时）；不自动换模型 | L2 §3.4 | P0 |
| SC-CORE-17 | Core 重试最终成功 | 目录 {alias-a, alias-b}；mock 端点 B 第一次 500 但重试成功 | 发请求指定 model_name="alias-b" | 请求成功（Core 同模型重试最终成功）；响应含 "MODEL_B_RESPONSE" | L2 §3.4 | P1 |
| SC-CORE-18 | 错误信息不泄露凭据 | 目录 {alias-a, alias-b}；mock 端点 B 返回 401 | 发请求指定 model_name="alias-b" → 检查错误响应和日志 | 错误响应和日志不含完整 api-key；使用脱敏失败摘要 | L2 §3.4, §4.4 | P0 |
| SC-CORE-19 | SSE 部分输出后失败终止 | 目录 {alias-a, alias-b}；mock 端点 B 先返回部分内容后 500 | 发流式请求指定 model_name="alias-b" | SSE 已输出部分文本 → 随后实际 data 变为错误并终止；无成功完成 | L2 §3.4, §7.2 流中最终失败 | P0 |
| SC-CORE-20 | 辅助异常被 Core 捕获后成功 | 目录 {alias-a, alias-b}；辅助调用失败但主推理成功 | 发请求指定 model_name="alias-b" | 请求成功（辅助异常被 Core 捕获后主推理成功时，请求允许成功） | L2 §3.4, §1.3 #2 | P1 |

### 5.4 Core 请求间隔离与并发（Runtime 端到端，与 FEAT-051-RT 互补）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-21 | 不同会话并发各自路由 | 目录 {alias-a, alias-b}；mock 端点 A 和 B 设置 barrier（等两条请求都到达后再放行） | 并发发送请求1（conv-1, alias-a）和请求2（conv-2, alias-b） | 请求1 响应含 "MODEL_A"，请求2 响应含 "MODEL_B"；互不污染 | L2 §7.1 输入隔离, §7.4 | P0 |
| SC-CORE-22 | 唯一 scope 隔离 | 同 JVM 两实例 X(scope=x) 和 Y(scope=y)，各自配置 alias-a/alias-b 指向不同端点 | 分别向 X 和 Y 发请求指定 model_name="alias-b" | X 请求到达 X 的端点 B；Y 请求到达 Y 的端点 D；Core 内部 ID 互不冲突 | L2 §3.1, §7.2 | P0 |

### 5.5 SDK 直连 CRUD（面向"无 Runtime 客户"）

> 以下场景为 SDK 直连测试，面向"不使用 agent-runtime 的客户直接持有 core Java API"的场景。
> 当前版本 Runtime 不走运行期 CRUD（部署时固定目录 + 重启），但这些 API 对于 SDK 客户仍有验证价值。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-23 | 新增模型 | 模型列表 {A, B}（SDK 直连，已注册） | addModel("model-c", supplierC) → listModelIds() | 注册成功；listModelIds() 含 "model-c" | AC-11, FR-01 | P1 |
| SC-CORE-24 | 更新模型 | 模型 A 已注册，指向端点 P1（SDK 直连） | 发请求验证打到 P1 → updateModel("A", newSupplier→P2) → 再次发请求 | 更新前打到 P1；更新后打到 P2 | AC-02, FR-02 | P1 |
| SC-CORE-25 | 删除非默认模型 | 模型列表 {A, B}，默认 A（SDK 直连） | removeModel("model-b") → hasModel("model-b") → listModelIds() → getDefaultModelId() | hasModel=false；listModelIds=["A"]；默认未变 | AC-03, FR-03 | P1 |
| SC-CORE-26 | 删除默认模型自动切换 | 模型列表 {A, B}，默认 A（SDK 直连） | removeModel("model-a") → hasModel("model-a") → getDefaultModelId() | hasModel=false；默认切换为剩余模型 | AC-07, FR-08 | P1 |
| SC-CORE-27 | 查询模型列表 | 模型列表 {A, B}（SDK 直连） | listModelIds() | 返回 ["model-a", "model-b"]，不含 apiKey | AC-11, FR-04 | P1 |
| SC-CORE-28 | 模型存在性检查 | 模型列表 {A, B}（SDK 直连） | hasModel("model-a") + hasModel("model-c") | hasModel("model-a")=true；hasModel("model-c")=false | AC-19, FR-14 | P1 |
| SC-CORE-29 | 设置与查询默认 | 模型列表 {A, B}，默认 A（SDK 直连） | setDefaultModelId("model-b") → getDefaultModelId() | 设置成功；getDefaultModelId()="model-b" | AC-18, FR-08 | P1 |
| SC-CORE-30 | 新增已存在模型 | 模型列表 {A, B}（SDK 直连） | addModel("model-a", supplierA) | 拒绝（检查实际 Result/异常类型） | AC-15, FR-01 | P0 |
| SC-CORE-31 | modelId 为空校验 | 模型列表 {A, B}（SDK 直连） | addModel(null) / updateModel(null) / removeModel(null) / setDefaultModelId(null) / hasModel(null) | 各操作均拒绝（检查实际 Result/异常类型） | FEAT-050 §3.3 | P2 |
| SC-CORE-32 | setDefaultModelId 不存在 | 模型列表 {A, B}（SDK 直连） | setDefaultModelId("model-x") | 拒绝（检查实际 Result/异常类型） | FEAT-050 §3.3 | P2 |

### 5.6 SDK 直连模型列表即时切换与并发（面向"无 Runtime 客户"）

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-33 | 在途请求自然完成 | 模型列表 {A,B}，默认 A；会话 S1 已发请求指定 B 且未完成（mock 端点 B 延迟 5s） | Step1: 发起 S1 请求指定 B（不等完成）；Step2: removeModel("B") + addModel("C")；Step3: 等待 S1 完成；Step4: S1 后续请求不传 model_name | S1 在途请求正常完成（仍用 B）；S1 后续请求使用默认 A；listModelIds=["A","C"] | AC-01, FR-07 | P1 |
| SC-CORE-34 | 并发新增模型 | 模型列表 {A, B}（SDK 直连）；mock 端点 A 和 B 设置 barrier | 线程T1: addModel("model-c")；线程T2: addModel("model-d")；使用 barrier 确保同时执行 → listModelIds() | 最终 listModelIds() = ["A","B","C","D"]，无线程安全异常 | AC-27, FR-22 | P1 |

### 5.7 SDK 直连模型解析优先级链（Core 内部行为）

> 以下场景为 SDK 直连测试，验证 Core 模型解析优先级链的内部回退行为。

| 场景 ID | 场景名称 | 前置条件 | 步骤 | 期望结果 | 对应验收 | 优先级 |
|---|---|---|---|---|---|---|
| SC-CORE-35 | 请求级路由命中（SDK 直连） | 模型列表 {A, B}（SDK 直连） | 设置 ctx 指定 model="B" → 发请求 | 请求使用 B，响应含 "MODEL_B" | AC-30, FR-05 | P1 |
| SC-CORE-36 | 未指定走默认（SDK 直连） | 模型列表 {A, B}，默认 A（SDK 直连） | 不指定 model → resolveModel | 请求使用 A，响应含 "MODEL_A" | AC-06, FR-06 | P1 |
| SC-CORE-37 | 指定不存在回退默认（SDK 直连） | 模型列表 {A}，默认 A（SDK 直连） | 指定 model="model-x" → resolveModel | 回退默认 A，记录 WARN | AC-05, FR-05 | P1 |
| SC-CORE-38 | 无可用模型（SDK 直连） | 模型列表为空，默认未设置（SDK 直连） | 不指定 model → resolveModel | 抛异常（"无可用模型"） | AC-21, FR-11 | P1 |

### 5.8 已知限制记录（Informational）

> 以下场景记录 Core 已知限制，**不作为本版通过结论**。测试执行时记录实际行为，标注为 KNOWN_LIMITATION。

| 场景 ID | 场景名称 | 来源 | 说明 | 优先级 |
|---|---|---|---|---|
| SC-CORE-LIMIT-01 | Core 解析阶段可能回退默认 | L2 §1.3 #1 | ReActAgent.getLlm(ctx) 和 ModelMgr 的部分资源解析路径存在 fallback；用户已接受本版采用固定目录、启动时 add/get 验证并记录该限制。不等于 Runtime 在 B 的 HTTP 调用失败后自动切换 A | Info |
| SC-CORE-LIMIT-02 | 辅助模型可能滞后 | L2 §1.3 #2 | 添加消息可能早于 BEFORE_MODEL_CALL 中的处理器刷新；该阶段压缩可能仍用 A，主推理再用 B。用户已确认接受该时序边界。另有共享 Rail 状态风险尚未强制触发验证 | Info |
| SC-CORE-LIMIT-03 | 共享 DeepAgent 并发结果隔离缺陷 | L2 §1.3 #3 | 先前真实 Runner 测试两次复现：A/B 实际调用分别正确，但会话 A 得到 B 的最终结果。本轮按用户要求暂不修 Core，已移除对应 DeepAgent 并发测试分支，保留 ReAct 并发测试 | Info |
| SC-CORE-LIMIT-04 | DeepAgent 特殊最终失败流式缺口 | L2 §1.3 #4 | `final_result.error=completion_timeout` 且缺少 result_type 的标准 DeepAgent 流式包装仍可能转发普通 chunk 并 onComplete。与已支持的 `answer.payload.result_type=error` 不同，应后续与 Core 协调 | Info |

---

## 6. 可观测子断言

> 黑盒边界：仅经 HTTP/A2A 请求、mock 端点请求记录、进程日志、Java API 调用观测，不读 SUT 内部类。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### SC-CORE-01.A — 启动注册验证

- **Given**：`apiconfig.json` 配置根默认 alias-a + 列表 alias-b；`model-selection-enabled: true`。
- **When**：启动服务 → 发请求验证 alias-a 和 alias-b 均可用。
- **Then**：启动成功；两个请求均正常路由到对应端点。
- **PASS**：启动成功 + 两请求路由正确。**FAIL**：启动失败或任一请求路由错误。

### SC-CORE-08.A — 缺省显式注入根默认 ID

- **Given**：目录 {alias-a, alias-b}，根默认=alias-a，`model-selection-enabled: true`。
- **When**：发请求不传 model_name → 检查 mock 端点 A 收到的请求。
- **Then**：mock 端点 A 收到请求（证明缺省时显式注入根默认内部 ID，而非走旧路径）。
- **PASS**：端点 A 收到请求。**FAIL**：端点 A 未收到请求。

### SC-CORE-09.A — B 后新请求缺省回 A

- **Given**：目录 {alias-a, alias-b}，根默认=alias-a。
- **When**：Step1: 发请求指定 model_name="alias-b"；Step2: 新请求不传 model_name。
- **Then**：Step1 响应含 "MODEL_B_RESPONSE"；Step2 响应含 "MODEL_A_RESPONSE"。
- **PASS**：两步标识正确。**FAIL**：Step2 路由到 B（未隔离选择）。

### SC-CORE-11.A — 不改共享默认

- **Given**：目录 {alias-a, alias-b}。
- **When**：发请求指定 alias-b → 新请求不传 → 检查共享 Agent 的"当前模型"。
- **Then**：请求1 使用 B；请求2 使用 A；请求不改变共享 Agent 的"当前模型"或 conversation 永久默认。
- **PASS**：满足。**FAIL**：共享默认被改变。

### SC-CORE-13.A — 401 不自动换模型

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 返回 401。
- **When**：发请求指定 model_name="alias-b"。
- **Then**：客户端收到执行失败；不自动调用 alias-a（检查 mock 端点 A 未收到请求）。
- **PASS**：执行失败 + 端点 A 未收到请求。**FAIL**：自动调用 A 或返回成功。

### SC-CORE-18.A — 错误信息不泄露凭据

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 返回 401。
- **When**：发请求指定 model_name="alias-b" → 检查错误响应和进程日志。
- **Then**：错误响应和日志不含完整 api-key；使用脱敏失败摘要。
- **PASS**：无完整 api-key。**FAIL**：响应或日志含完整 api-key。

### SC-CORE-19.A — SSE 部分输出后失败终止

- **Given**：目录 {alias-a, alias-b}；mock 端点 B 先返回部分内容后 500。
- **When**：发流式请求指定 model_name="alias-b"。
- **Then**：SSE 已输出部分文本 → 随后实际 data 变为错误并终止；无成功完成。
- **PASS**：实际 data 为错误 + 终止。**FAIL**：正常完成或错误 data 未改变。
- **注意**：HTTP 可能仍为 200，客户端须检查实际 data 和终止状态。

### SC-CORE-21.A — 并发各自路由（执行重叠保证）

- **Given**：目录 {alias-a, alias-b}；mock 端点 A 和 B 设置 barrier（等两条请求都到达后再放行）。
- **When**：并发发送请求1（conv-1, alias-a）和请求2（conv-2, alias-b）。
- **Then**：请求1 响应含 "MODEL_A"，请求2 响应含 "MODEL_B"。
- **PASS**：两个响应标识正确。**FAIL**：任一响应标识错误。
- **注意**：使用 barrier（而非仅 CompletableFuture/CountDownLatch）确保两条请求都到达 mock 后再放行，保证真正的执行重叠。

### SC-CORE-22.A — 唯一 scope 隔离

- **Given**：同 JVM 两实例 X(scope=x) 和 Y(scope=y)，各自配置 alias-a/alias-b 指向不同端点。
- **When**：分别向 X 和 Y 发请求指定 model_name="alias-b"。
- **Then**：X 的请求到达 X 的端点 B；Y 的请求到达 Y 的端点 D。
- **PASS**：各自路由正确。**FAIL**：路由到对方的端点（scope 未隔离）。

### SC-CORE-33.A — 在途请求自然完成

- **Given**：会话 S1 已发请求指定 B，mock 端点 B 设置 5s 延迟（制造在途状态）。
- **When**：removeModel("B") + addModel("C")（在 S1 请求未完成时执行）。
- **Then**：S1 在途请求正常完成（响应含 "MODEL_B"），不中断。
- **PASS**：在途请求 COMPLETED 且响应含 "MODEL_B"。**FAIL**：在途请求报错或中断。

### SC-CORE-LIMIT-03.A — 共享 DeepAgent 并发结果隔离缺陷

- **Given**：目录 {alias-a, alias-b}；使用共享 DeepAgent task loop。
- **When**：并发请求1(conv-1, alias-a) 和请求2(conv-2, alias-b)。
- **Then**：记录实际行为——A/B 实际调用分别正确，但会话 A 可能得到 B 的最终结果。
- **标注**：KNOWN_LIMITATION（§1.3 #3），不作为通过结论。本版暂不修复 Core，保留 ReAct 并发测试（SC-CORE-21）。

---

## 7. 测试数据

### 7.1 apiconfig.json 配置模板

> 与 FEAT-051-RT §7.1 相同，此处省略。Runtime 端到端场景复用 FEAT-051-RT 的配置。

### 7.2 SDK 直连测试数据

| 模型 | API_BASE | API_KEY | MODEL_NAME | 行为 |
|---|---|---|---|---|
| model-a | http://localhost:4000/v1 | mock-key-a | provider-chat-a | 返回 "MODEL_A_RESPONSE" |
| model-b | http://localhost:4001/v1 | mock-key-b | provider-chat-b | 返回 "MODEL_B_RESPONSE"（可配置延迟/错误） |
| model-c | http://localhost:4002/v1 | mock-key-c | provider-chat-c | 返回 "MODEL_C_RESPONSE" |
| model-d | http://localhost:4003/v1 | mock-key-d | provider-chat-d | 返回 "MODEL_D_RESPONSE" |

> **注意**：SDK 直连测试中使用 model-a/model-b 等标识，与 Runtime 端到端的 alias-a/alias-b 公开别名区分。MODEL_ID 与 MODEL_NAME 必须不同。

---

## 8. 框架落点

| 项 | 值 |
|---|---|
| 测试类（Runtime 端到端，与 FEAT-051-RT 共用） | `RequestModelSelectionTest`（真实 Runner + HTTP mock）、`JiuwenCoreAgentHandlerTest` |
| 测试类（SDK 直连 CRUD） | `DynamicModelCrudTest`（待创建） |
| 测试类（SDK 直连优先级链） | `DynamicModelPriorityChainTest`（待创建） |
| 测试类（装配与生命周期） | `CoreRunnerLifecycleCoordinatorTest`、`AgentCoreAdaptersAutoConfigurationTest` |
| 标签 | `@Tag("component")` `@Tag("dynamic-model")` `@Tag("feat-050")` |
| Allure | `@Feature("FEAT-050")` `@Story("core-model-management")` |

### Mock 端点配置

| 端点 | 端口 | MODEL_ID | MODEL_NAME | API_KEY | 行为 |
|---|---|---|---|---|---|
| A | 4000 | alias-a / model-a | provider-chat-a | mock-key-a | 返回 "MODEL_A_RESPONSE" |
| B | 4001 | alias-b / model-b | provider-chat-b | mock-key-b | 返回 "MODEL_B_RESPONSE"（可配置延迟/错误） |
| C | 4002 | model-c | provider-chat-c | mock-key-c | 返回 "MODEL_C_RESPONSE" |
| D | 4003 | model-d | provider-chat-d | mock-key-d | 返回 "MODEL_D_RESPONSE" |

---

## 9. 运行方式

```bash
# Runtime 端到端测试（与 FEAT-051-RT 共用）
mvn -B -pl service/agent-service-adapters/agent-service-adapters-agentcore -am test \
  '-Dtest=RequestModelSelectionTest,JiuwenCoreAgentHandlerTest,CoreRunnerLifecycleCoordinatorTest,AgentCoreAdaptersAutoConfigurationTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false'

# SDK 直连 CRUD 测试（待创建）
mvn -B -pl service/agent-service-adapters/agent-service-adapters-agentcore -am test \
  '-Dtest=DynamicModelCrudTest,DynamicModelPriorityChainTest'
```

---

## 10. 覆盖追溯

### 10.1 FRS-050 验收标准覆盖

| 验收标准 | 场景 ID | 来源 | 状态 |
|---|---|---|---|
| AC-01 模型新增后即时可用 | SC-CORE-23, SC-CORE-33 | FR-01/07 | ✅ |
| AC-02 模型更新 | SC-CORE-24 | FR-02 | ✅ |
| AC-03 模型删除 | SC-CORE-25 | FR-03 | ✅ |
| AC-05 指定模型路由/不存在回退 | SC-CORE-06/07/37 | FR-05 | ✅ |
| AC-06 未传模型回退 | SC-CORE-07/36 | FR-06 | ✅ |
| AC-07 删除默认自动切换 | SC-CORE-26 | FR-08 | ✅ |
| AC-11 查询模型列表 | SC-CORE-23/27 | FR-04 | ✅ |
| AC-14 同会话模型切换 | SC-CORE-10 | FR-12 | ✅ |
| AC-15 新增已存在拒绝 | SC-CORE-30 | FR-01 | ✅ |
| AC-18 设置/查询默认 | SC-CORE-29 | FR-08 | ✅ |
| AC-19 模型存在性检查 | SC-CORE-28 | FR-14 | ✅ |
| AC-21 无可用模型 | SC-CORE-38 | FR-11 | ✅ |
| AC-27 并发安全 | SC-CORE-34 | FR-22 | ✅ |
| AC-30 SDK 直连路由 | SC-CORE-35 | FR-05 | ✅ |

### 10.2 L2 §1.3 已知差距覆盖

| 差距项 | 场景 ID | 测试策略 |
|---|---|---|
| #1 Core 模型解析阶段可能回退默认 | SC-CORE-LIMIT-01 | 记录实际行为，标注 KNOWN_LIMITATION |
| #2 辅助模型可能滞后 | SC-CORE-LIMIT-02, SC-CORE-20 | 记录实际行为，标注 KNOWN_LIMITATION；SC-CORE-20 验证辅助异常被捕获后主推理成功 |
| #3 共享 DeepAgent 并发结果隔离缺陷 | SC-CORE-LIMIT-03 | 保留 ReAct 并发测试（SC-CORE-21），不测共享 DeepAgent 并发 |
| #4 DeepAgent 特殊最终失败流式缺口 | SC-CORE-LIMIT-04 | 记录实际行为，标注 KNOWN_LIMITATION |

### 10.3 与 FEAT-051-RT 的互补关系

| Core 行为 | FEAT-051-RT 场景（Runtime 侧） | FEAT-050 场景（Core 侧） |
|---|---|---|
| 启动注册 | SC-CFG-01（配置注册验证） | SC-CORE-01~05（注册 Result/getModel/冲突/回滚/幂等） |
| 请求级路由 | SC-ROUTE-01~07（HTTP/A2A 路由） | SC-CORE-06~12（优先级链/隔离/不提升伪造字段） |
| 模型调用失败 | SC-FAIL-01~10（协议错误表面） | SC-CORE-13~20（Core 重试/最终失败/辅助异常） |
| 请求间隔离 | SC-CONC-01~03（Runtime 全链路隔离） | SC-CORE-21/22（并发/scope 隔离） |
| 配置加载 | SC-CFG-01~10（apiconfig.json 解析） | —（由 FEAT-051-RT 覆盖） |
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
| API 签名不确定 | `resourceMgr().hasModel` 等可能不符合当前实际 API | 核实当前 ResourceMgr 接口后再编写测试代码 |
| 异常类型不确定 | 重复注册、删除等操作的 Result/异常类型需核实 | 检查实际 Result 类型，不统一假定抛某种异常 |
| 共享注册表污染 | "清空所有模型"可能影响其他测试 | 只清理自有资源 |
| 并发起发≠执行重叠 | CompletableFuture/CountDownLatch 只增加同时发起概率 | 使用 mock barrier 等两条请求都到达后再放行 |
| Core 内部 fallback 不可控 | SC-CORE-LIMIT-01 可能影响路由测试 | 记录为已知限制，不作为通过结论 |
| 辅助调用时序不可控 | SC-CORE-LIMIT-02 可能影响压缩跟随验证 | 用户已接受时序边界，不强制验证辅助调用一致性 |
| 公开别名与供应商模型名混用 | 测试配置全写相同字符串会掩盖路由问题 | MODEL_ID 与 MODEL_NAME 必须不同 |

### 11.2 设计决策说明

1. **保留 P0/P1 级别**：Core 启动注册、请求级解析、隔离、失败处理等通过 Runtime 端到端验证的行为保持 P0/P1，不降级。

2. **SDK 直连 CRUD 保持 P1**：对于"无 Runtime 客户"场景仍有验证价值，不降级。仅 modelId 为空校验和 setDefaultModelId 不存在校验为 P2（边界场景）。

3. **运行期 CRUD 不单独降级**：当前版本 Runtime 不走运行期 CRUD（部署时固定目录 + 重启），但 SDK 直连 CRUD 仍可验证 Core API 正确性，保持 P1。检视意见的核心是"不应成为本版 Runtime 的**全部**验收门槛"，而非"Core CRUD 不测"。

4. **与 FEAT-051-RT 互补**：Runtime 端到端场景（SC-CORE-01~22）与 FEAT-051-RT 共用测试基础设施，Core 侧侧重验证注册 Result、优先级链回退、重试行为等 Core 内部行为。

5. **压缩测试不包含**：L2 §1.3 #2 已接受辅助调用时序边界，不作为绝对一致性承诺。压缩触发方法需 Core 专项后续负责。

6. **共享 DeepAgent 并发不包含**：L2 §1.3 #3 已知缺陷，暂缓修复，不列为通过结论。保留 ReAct 并发测试。

7. **三进程一致性**：属于较低优先级部署回归，由 FEAT-051-RT SC-COMPAT-03 覆盖重启生效场景。

8. **清理策略**：共享注册表测试只清理自有资源，避免"清空所有模型"污染其他测试。
