---
feature_id: DFX-001
feature_title: OTel 轨迹可观测性（Trajectory Observability）
sut: agent-service-adapters-otel 模块 + 模拟 OTLP Collector
status: tested
tags: [blackbox, contract, integration, otel, otlp, dfx-001]
---

# DFX-001 — OTel 轨迹可观测性测试设计

> 验证 OpenTelemetry 轨迹上报的数据契约合规性、Bug 复现、深度边界场景和真实 OTLP gRPC 端到端上报；覆盖 §12 全部 10 条验收准则、§8.3 hex 格式、§9.C 业务 trace ID、§5.1.4 故障隔离、§5.1.5 CoT 不暴露、§2 采样控制、§3 环境变量配置等。

---

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| 需求特性文档 | `DFX-001-trajectory-observability.md` |
| 详细设计文档 | `Feat-DFX-001-trajectory-observability.md` |
| 开发自测报告 | `DFX-001-otel-trajectory-test-guide.md` |
| 数据需求说明 | `Java版EDPAgent_OTel轨迹上报_数据需求说明.md` |
| Bug 报告 | `DFX-001-P2-BugReport-toJson-fallback.md` |
| 被测模块 | `agent-solution_new/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-otel` |
| 测试 Demo 模块 | `agent-solution_new/common/example/otel-trajectory-demo` |
| JDK | 21 (Temurin) |
| Maven | 3.9.16 |
| Python | 3.11 |
| 操作系统 | Windows |
| OTel SDK 版本 | 1.64.0 (由 ext-java parent POM 管理) |
| 测试框架 | JUnit 5 + AssertJ + InMemorySpanExporter |
| 端到端 relay | `grpcio` + 手写 protobuf wire-format 解析器 (零 `opentelemetry-proto` 依赖) |

### 1.2 测试范围

**在范围内（In Scope）**：

1. **数据契约验证**（§12.1~§12.10）— 验证 exported span 的字段完整性和语义正确性：
   - §12.1：Span 通用字段（trace_id, span_id, name, kind, start_time, end_time, status_code, attributes, resource_attributes）
   - §12.2：`session.id` 全覆盖（含 tool span，每个 span 自包含）
   - §12.3：Resource 属性（service.name, service.instance.id, scope.name）
   - §12.4：JSON 合法性（`openjiuwen.agent.inputs/outputs`, `gen_ai.prompt/completion` 均为合法 JSON，无 `toString()` 伪影）
   - §12.5：时间格式一致性（ISO 8601 带时区）
   - §12.6：Token 命名（`gen_ai.usage.input_tokens/output_tokens` 新命名）
   - §12.7：Invoke 树自洽（child `parent_invoke_id` = parent `invoke_id`，chain `child_invoke_ids` 双向一致）
   - §12.8：Span 树结构（`http.request`(SERVER, root) → `chain`(INTERNAL) → `llm`(CLIENT) + `tool`(INTERNAL)，恰一个 SERVER 根）
   - §12.9：Chain 响应摘要（`openjiuwen.agent.outputs` 为合法 JSON）
   - §12.10：工具调用覆盖（每次工具调用产生 `tool.*` span，含 `gen_ai.tool.name` 和 `execute_tool`）

2. **附加契约验证**：
   - §8.3：trace_id (32 位小写 hex) / span_id (16 位小写 hex) 格式
   - §9.C：业务 trace ID (`openjiuwen.trace.id`) 在同一 OTel trace 内一致
   - §7.2：LLM span 的 `openjiuwen.llm.finish_reason`
   - §5.1.5：Chain-of-Thought 不暴露（无 `reasoning_content`/`chain_of_thought`/`raw_cot` 属性 key）
   - §5.1.4：故障隔离（`toJson()` 序列化失败时返回 `null`，非 `toString()` 伪影）
   - §7.6：子 agent dispatch span 属性（`openjiuwen.subagent.entity_id/entity_name/query/status`）
   - §7.4：HTTP request body（`openjiuwen.http.request_body` 为合法 JSON）
   - §8.5：Cost 语义（`openjiuwen.cost.total` 不混入 token 数或时间字符串）

3. **Bug DFX-001-P2 修复验证** — `OtelJsonAgentHandler.toJson()` 在序列化失败时正确返回 `null`（属性省略），而非传播 `StackOverflowError` 或写入 `toString()` 伪影。循环引用对象也能安全降级。

4. **采样控制**（§2）— 采样率 0.0 不产生 span，1.0 全量产生；默认关闭 `redaction=false`, `truncation=off`。

5. **环境变量配置**（§3）— 协议（`grpc`/`http`）、端点、headers（含 URL 解码和 `=` 号处理）、采样率、不支持变量告警。

6. **端到端 OTLP gRPC 上报** — 真实 gRPC 上报到模拟 Collector，解析为 JSONL 后执行 18 项数据契约校验。

7. **深度边界测试** — 4 个产品代码问题的复现与对照：
   - BUG-1：`getTimeout()` 不兼容 OTEL 标准毫秒格式
   - BUG-2：`toTracerConfig()` 未调用 `getTimeout()`
   - BUG-3：`stream()` 在迭代器未消费时就 `end()` span
   - 问题-4：`toZonedIso()` 依赖 `ZoneId.systemDefault()`

**不在范围内（Out of Scope）**：

- OTel SDK 内部 span 存储实现细节
- protobuf 序列化字节级一致性
- Collector 侧处理逻辑（如 Jaeger/Zipkin 后端存储）
- 网络层 gRPC 连接池管理
- LLM 模型本身的响应质量
- 非 Java 版 EDPAgent 的轨迹上报

### 1.3 测试策略

本测试方案采用**分层验证**策略，从单元级到端到端逐层递进：

| 层级 | 工具 | 验证内容 | 证据形式 |
|------|------|----------|----------|
| L1: 单元契约 | JUnit 5 + InMemorySpanExporter | span 属性、类型、树结构、invoke 树 | AssertJ 断言 |
| L2: 故障隔离 | JUnit 5 + 不可序列化对象 | toJson fallback、循环引用 | 断言属性不存在 |
| L3: 采样控制 | JUnit 5 + parentBased sampler | 0.0/1.0 采样率 | exported span 计数 |
| L4: 端到端 | otlp_relay.py + OtlpRelayCheckIT | 真实 gRPC OTLP 上报 | JSONL 文件 |
| L5: 数据契约 | span_validator.py | 18 项契约检查 | 测试报告 |
| L6: 深度边界 | JUnit 5 + mock RemoteClient | 4 个产品代码问题 | 断言确认 Bug 存在 |

---

## 2. 前置条件与证据

### 2.1 环境前置条件

1. **JDK 17+**（已验证 JDK 21 Temurin），`JAVA_HOME` 已设置。
2. **Maven 3.9+**（已验证 3.9.16），`MAVEN_HOME` 在 PATH 中或可通过 wrapper 检测。
3. **Python 3.10+**（已验证 3.11），已安装 `grpcio`（端到端测试）。
4. `agent-service-adapters-otel` 模块已 `mvn install` 到本地仓库（首次运行 `-am` 会自动构建依赖模块）。
5. 测试 Demo 模块 `otel-trajectory-demo` 作为 `agent-runtime-ext-java` 子模块，通过 `<parent>` + `<relativePath>` 关联。
6. 端口 4317 可用（OTLP gRPC 标准端口，端到端测试使用）。
7. Windows PowerShell 5 环境（Python 编排脚本跨平台，但路径分隔符在 Windows 下验证）。

### 2.2 证据收集方式

| 证据类型 | 收集方式 | 存储位置 |
|----------|----------|----------|
| exported span 数据 | `InMemorySpanExporter.getFinishedSpanItems()` | JUnit 断言内联 |
| OTLP JSONL span 数据 | `otlp_relay.py` 解析 protobuf → 写入 JSONL | `reports/otlp_spans.jsonl` |
| 数据契约校验报告 | `span_validator.py --report` | `reports/DFX-001-test-report.txt` |
| 综合测试报告 | `run_dfx001_tests.py` 生成 | `reports/DFX-001-summary-report.txt` |
| 测试报告（人工） | 测试团队编写 | `reports/DFX-001-测试报告.md` |
| Maven 测试输出 | `mvn test` 控制台输出 | 控制台 / CI 日志 |

### 2.3 测试 Oracle 来源

| Oracle | 来源文档 | 适用用例 |
|--------|----------|----------|
| Span 通用字段定义 | §12.1 | TC-01, criterion01 |
| session.id 全覆盖要求 | §12.2, §9.D | TC-02, criterion02 |
| Resource & scope 属性 | §12.3, §4, §5 | TC-03, criterion03 |
| JSON 合法性要求 | §12.4, §8.1 | TC-04, criterion04 |
| 时间格式 ISO 8601 | §12.5, §8.2 | TC-05 |
| Token 新命名 | §12.6, §7.2 | TC-06, criterion06 |
| invoke 树自洽规则 | §12.7, §9.B | TC-07, criterion07 |
| Span 树结构定义 | §12.8, §6 | TC-08, criterion08 |
| Chain 响应摘要 | §12.9, §9.E | TC-09, criterion09 |
| 工具调用覆盖 | §12.10, §7.3 | TC-10, criterion10 |
| hex 格式规范 | §8.3 | TC-11, criterion11 |
| 业务 trace ID 一致性 | §9.C | TC-12, criterion12 |
| LLM finish_reason | §7.2 | TC-13, criterion13 |
| CoT 不暴露 | §5.1.5 | TC-14, criterion14 |
| 故障隔离规则 | §5.1.4, §8.1 | TC-15, FaultIsolation |
| 子 agent dispatch | §7.6 | TC-16 |
| HTTP request body | §7.4, §9.E | TC-17 |
| Cost 语义 | §8.5 | TC-18 |
| 采样控制 | §2 | Sampling 组 |
| 默认关闭 | §2 | defaultConfig |
| 环境变量配置 | §3 | EnvVarEdgeCases 组 |
| OTEL 标准超时格式 | OTEL 官方规范 | BUG-1 |
| Bug DFX-001-P2 修复 | DFX-001-P2-BugReport | FaultIsolation |

---

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `DFX-001.acceptance.span-fields` | §12.1/§12.2/§12.3 | contract | passed, P0 | automated | Span 通用字段、session.id 全覆盖、Resource & scope 属性 | exported span 属性断言 | — |
| `DFX-001.acceptance.json-validity` | §12.4/§12.9 | contract | passed, P0 | automated | JSON 合法性（agent.inputs/outputs, gen_ai.prompt/completion）、Chain 响应摘要 | JSON 解析断言 | — |
| `DFX-001.acceptance.token-naming` | §12.6 | contract | passed, P0 | automated | Token 命名（input_tokens/output_tokens 新命名） | 属性 key 断言 | — |
| `DFX-001.acceptance.invoke-tree` | §12.7 | contract | passed, P0 | automated | invoke 树自洽（parent_invoke_id ↔ child_invoke_ids 双向一致） | 属性关联断言 | — |
| `DFX-001.acceptance.span-tree` | §12.8 | contract | passed, P0 | automated | Span 树结构（http.request(SERVER,root) → chain(INTERNAL) → llm(CLIENT) + tool(INTERNAL)，恰一个 SERVER 根） | parent_span_id + kind 断言 | — |
| `DFX-001.acceptance.tool-coverage` | §12.10 | contract | passed, P0 | automated | 每次工具调用产生 tool.* span，含 gen_ai.tool.name 和 execute_tool | span name + 属性断言 | — |
| `DFX-001.acceptance.fault-isolation` | §5.1.4 | blackbox | passed, P0 | automated | toJson fallback 返回 null（非 toString），Bug DFX-001-P2 修复验证 | 属性存在性断言 | — |
| `DFX-001.acceptance.cot-redaction` | §5.1.5 | contract | passed, P1 | automated | Chain-of-Thought 不暴露 | 属性 key 不存在断言 | — |
| `DFX-001.acceptance.sampling` | §2 | blackbox | passed, P0 | automated | 采样率 0.0 不产生 span，1.0 全量产生 | exported span 计数 | — |
| `DFX-001.acceptance.defaults` | §2 | contract | passed, P1 | automated | 默认关闭 redaction=false, truncation=off | 配置对象断言 | — |
| `DFX-001.acceptance.error-scenario` | §5.1.4 | blackbox | passed, P1 | automated | 工具异常时 span 仍被创建 | exported span 非空 | — |
| `DFX-001.acceptance.hex-format` | §8.3 | contract | passed, P0 | automated | trace_id 32 位 hex, span_id 16 位 hex | 正则断言 | — |
| `DFX-001.acceptance.business-trace-id` | §9.C | contract | passed, P0 | automated | openjiuwen.trace.id 一致性 | 属性值断言 | — |
| `DFX-001.acceptance.finish-reason` | §7.2 | contract | passed, P1 | automated | LLM finish_reason 存在 | 属性存在断言 | — |
| `DFX-001.otlp.e2e` | §12 全部 + §8.3 + §9.C | integration | passed, P0 | automated | 真实 OTLP gRPC 上报 + JSONL 数据契约校验（18 项检查） | otlp_relay.py + span_validator.py | — |
| `DFX-001.bug-repro.timeout-format` | 深度边界测试 | contract | passed, P2 | automated | BUG-1: getTimeout() 不兼容 OTEL 标准毫秒格式 | DateTimeParseException 断言 | — |
| `DFX-001.bug-repro.timeout-not-applied` | 深度边界测试 | contract | passed, P2 | automated | BUG-2: toTracerConfig() 未调用 getTimeout() | config timeout 值断言 | — |
| `DFX-001.bug-repro.stream-premature-end` | 深度边界测试 | blackbox | passed, P3 | automated | BUG-3: stream() 在迭代器未消费时就 end() span | exported span 时序断言 | — |
| `DFX-001.bug-repro.tz-dependent-iso` | 深度边界测试 | contract | passed, P4 | automated | 问题-4: toZonedIso() 依赖 ZoneId.systemDefault() | 时间格式断言 | — |
| `DFX-001.bug-repro.env-config-boundary` | §3 | contract | passed, P1 | automated | 环境变量边界（空白超时/headers解析/协议校验） | 配置对象断言 | — |

### 当前交付能力追踪

| 需求交付能力 | 覆盖用例 |
|---|---|
| Span 通用字段、session.id、Resource & scope 属性 | `DFX-001.acceptance.span-fields` |
| JSON 合法性、Chain 响应摘要 | `DFX-001.acceptance.json-validity` |
| Token 命名（新命名） | `DFX-001.acceptance.token-naming` |
| invoke 树自洽 | `DFX-001.acceptance.invoke-tree` |
| Span 树结构 | `DFX-001.acceptance.span-tree` |
| 工具调用覆盖 | `DFX-001.acceptance.tool-coverage` |
| 故障隔离 + Bug DFX-001-P2 修复 | `DFX-001.acceptance.fault-isolation` |
| CoT 不暴露 | `DFX-001.acceptance.cot-redaction` |
| 采样控制 | `DFX-001.acceptance.sampling` |
| 默认关闭 | `DFX-001.acceptance.defaults` |
| 工具异常产生 ERROR span | `DFX-001.acceptance.error-scenario` |
| hex 格式（trace_id/span_id） | `DFX-001.acceptance.hex-format` |
| 业务 trace ID 一致性 | `DFX-001.acceptance.business-trace-id` |
| LLM finish_reason | `DFX-001.acceptance.finish-reason` |
| 端到端 OTLP gRPC 上报 | `DFX-001.otlp.e2e` |
| BUG-1: 超时值格式解析 | `DFX-001.bug-repro.timeout-format` |
| BUG-2: 超时值未应用到 config | `DFX-001.bug-repro.timeout-not-applied` |
| BUG-3: stream() span 过早结束 | `DFX-001.bug-repro.stream-premature-end` |
| 问题-4: 时区依赖 | `DFX-001.bug-repro.tz-dependent-iso` |
| 环境变量配置边界 | `DFX-001.bug-repro.env-config-boundary` |

---

## 4. 详细用例

### 4.1 DFX-001.acceptance.span-fields — Span 通用字段完整且 session.id 全覆盖

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.1、§12.2、§12.3。
- **测试类型**：contract。
- **Oracle 来源**：需求特性文档 §12.1（Span 通用字段完整）、§12.2（session.id 全覆盖含 tool span）、§12.3（Resource & scope 属性）。
- **G（Given）**：
  - 使用 `OpenTelemetrySdk` + `InMemorySpanExporter` 构造测试 SDK。
  - 通过 `OtelSdkFactory.createProvider(config, exporter, "inst-accept")` 创建 `SdkTracerProvider`。
  - 构造 `OtelJsonAgentHandler`，设置 `sessionOf("conv-01")` 到 `SessionContextHolder`。
  - 构造 `TraceAgentSpan` 对象：chain (traceId="trace-01", invokeId="inv-1")、llm (invokeId="inv-2", parentInvokeId="inv-1")、tool (invokeId="inv-3", parentInvokeId="inv-1")。
  - 手动创建 `http.request` (SpanKind.SERVER) 作为根 span，设置 `http.request.method`、`http.route`、`openjiuwen.http.request_body`、`session.id` 属性。
  - 在 http root scope 下调用 `handler.onChainStart` → `onLlmStart` → `onLlmEnd` → `onPluginStart` → `onPluginEnd` → `onChainEnd`。
  - 关闭 http root scope，设置 `http.response.status_code=200`，调用 `httpRoot.end()`。
  - `provider.forceFlush().join(5, TimeUnit.SECONDS)` 后从 exporter 获取全部 span。
- **W（When）**：
  - 从 `exporter.getFinishedSpanItems()` 获取全部 exported span（至少 4 个：http.request + chain + llm + tool）。
  - 遍历每个 span 检查通用字段和 session.id。
- **T（Then）**：
  - 每个 span 包含非空 `trace_id`（32 位 hex）、`span_id`（16 位 hex）、`name`（非空）、`kind`（非 null）、`start_time`（>0）、`end_time`（>0）、`attributes`（非 null）。
  - 每个 span（**含 tool span**）的 attributes 中包含 `session.id`，值为 `"conv-01"`。
  - Resource attributes 包含 `service.name`（值为 `"edp-agent"`）和 `service.instance.id`（非空）。
  - scope name 为 `"openjiuwen.tracer.otel"`（http.request span 除外，可能使用自定义 tracer）。
- **不应断言**：固定 trace_id 值（OTel SDK 随机生成）、固定时间戳、OTel SDK 内部 span 存储结构、span 数量（取决于工具调用次数，但至少 4 个）。
- **失败归类**：字段缺失或格式不符为 Failure；SDK 初始化失败或 handler 调用异常为 Error。
- **方法**：
  - `criterion01_spanCommonFields()` — 验证 §12.1。
  - `criterion02_sessionIdCoverage()` — 验证 §12.2。
  - `criterion03_resourceAttributes()` — 验证 §12.3。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.span-fields")`。
- **DisplayName**：`§12.1: Every span has trace_id, span_id, name, kind, start_time, end_time, status_code, attributes, resource_attributes`。
- **辅助方法**：
  - `sessionOf(String id)` — 构造匿名 `Session` 实现，返回固定 sessionId。
  - `config()` — 构造 `OtelTracerConfig`（exporterType="otlp", endpoint="http://localhost:4317", serviceName="edp-agent", redaction=false, maxAttrLength=-1）。
  - `provider(config)` — 调用 `OtelSdkFactory.createProvider()` 创建带 `InMemorySpanExporter` 的 provider。
  - `buildContractTree(sessionId, traceId)` — 构建完整合同 span 树的辅助方法，返回 `List<SpanData>`。内部流程：
    1. 创建 `OtelJsonAgentHandler`，设置 session 到 `SessionContextHolder`。
    2. 创建 `http.request` (SERVER) 根 span，设置 HTTP 属性和 `session.id`。
    3. 在 http scope 下调用 `onChainStart(chain, inputs, meta)`。
    4. 调用 `onLlmStart(llm, prompt, meta)` → `onLlmEnd(llm, outputs)`。
    5. 调用 `onPluginStart(tool, inputs, meta)` → `onPluginEnd(tool, outputs)`。
    6. 调用 `onChainEnd(chain, outputs)`。
    7. 关闭 scope，end http root span，forceFlush。
    8. 返回 `exporter.getFinishedSpanItems()`。
  - `str(String key)` / `lng(String key)` — `AttributeKey.stringKey()` / `AttributeKey.longKey()` 工厂方法。

---

### 4.2 DFX-001.acceptance.json-validity — JSON 属性合法且无 toString 伪影

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.4、§12.9。
- **测试类型**：contract。
- **Oracle 来源**：§12.4（`openjiuwen.agent.inputs/outputs`、`gen_ai.prompt/completion` 均为合法 JSON）、§12.9（Chain 响应摘要为合法 JSON）。Bug DFX-001-P2 报告（`toString()` 伪影问题）。
- **G（Given）**：
  - 调用 `buildContractTree("conv-04", "trace-04")` 构建完整 span 树。
  - inputs 包含 `Map.of("query", "test query")`，outputs 包含 `Map.of("answer", "ok")`。
  - LLM prompt 包含 `List.of(Map.of("role", "user", "content", "test"))`。
  - LLM completion 包含 `Map.of("role", "assistant", "content", "ok", "usage", Map.of("input_tokens", 10, "output_tokens", 5), "finish_reason", "stop")`。
  - Tool inputs 包含 `Map.of("inputs", Map.of("path", "/tmp/test.txt"))`，outputs 包含 `Map.of("code", 0, "message", "success")`。
- **W（When）**：
  - 从 exported span 中按 name 前缀筛选 chain、tool、llm span。
  - 提取 `openjiuwen.agent.inputs`、`openjiuwen.agent.outputs`、`gen_ai.prompt`、`gen_ai.completion` 属性值。
- **T（Then）**：
  - chain inputs 包含 `"query"` 且不包含 `"="`（排除 `{query=test}` 形式的 toString 伪影）。
  - chain outputs 包含 `"answer"` 且不包含 `"="`。
  - tool inputs 包含 `"path"` 且不包含 `"="`。
  - tool outputs 包含 `"code"` 且不包含 `"="`。
  - llm prompt 包含 `"role"` 和 `"content"`。
  - llm completion 包含 `"assistant"` 和 `"content"`。
  - chain span 的 `openjiuwen.agent.outputs` 不为 null 且包含 `"answer"`。
- **不应断言**：JSON 字段顺序、固定 JSON 内容（只验证 key 存在和 toString 伪影不存在）。
- **失败归类**：JSON 解析失败为 Failure；属性不存在为 Failure；toString 伪影存在为 Failure（Bug DFX-001-P2 回归）。
- **方法**：
  - `criterion04_jsonValidity()` — 验证 §12.4。
  - `criterion09_chainResponse()` — 验证 §12.9。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.json-validity")`。
- **DisplayName**：`§12.4: Structured attributes are valid JSON (no toString/repr artifacts)`。

---

### 4.3 DFX-001.acceptance.token-naming — Token 命名（新命名）

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.6。
- **测试类型**：contract。
- **Oracle 来源**：§12.6（LLM span 必须报告 `gen_ai.usage.input_tokens` 和 `gen_ai.usage.output_tokens` 新命名，旧命名 `prompt_tokens`/`completion_tokens` 可共存但不能单独存在）。
- **G（Given）**：
  - 调用 `buildContractTree("conv-06", "trace-06")` 构建 span 树。
  - LLM 的 `onLlmEnd` 传入 `Map.of("usage", Map.of("input_tokens", 10, "output_tokens", 5))`。
- **W（When）**：
  - 从 exported span 中筛选 name 以 `"llm."` 开头的 span。
  - 读取 `gen_ai.usage.input_tokens`（longKey）和 `gen_ai.usage.output_tokens`（longKey）。
- **T（Then）**：
  - `gen_ai.usage.input_tokens` = 10L。
  - `gen_ai.usage.output_tokens` = 5L。
- **不应断言**：旧命名属性不存在（旧命名可共存）、token 数量的业务正确性（只验证属性 key 和值类型）。
- **失败归类**：新命名属性缺失为 Failure；值类型不符为 Failure。
- **方法**：`criterion06_tokenNaming()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.token-naming")`。
- **DisplayName**：`§12.6: LLM span reports gen_ai.usage.input_tokens and output_tokens (new naming)`。

---

### 4.4 DFX-001.acceptance.invoke-tree — invoke 树 parent_invoke_id 与 child_invoke_ids 双向一致

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.7。
- **测试类型**：contract。
- **Oracle 来源**：§12.7（child span 的 `parent_invoke_id` = parent span 的 `invoke_id`；chain span 的 `child_invoke_ids` 列表包含所有直接子 span 的 `invoke_id`；无孤儿 invoke、无循环引用）。
- **G（Given）**：
  - 调用 `buildContractTree("conv-07", "trace-07")` 构建 span 树。
  - chain span 设置 `invokeId="inv-1"`、`childInvokesId=List.of("inv-2", "inv-3")`。
  - llm span 设置 `invokeId="inv-2"`、`parentInvokeId="inv-1"`。
  - tool span 设置 `invokeId="inv-3"`、`parentInvokeId="inv-1"`。
- **W（When）**：
  - 从 exported span 中筛选 chain、llm、tool span。
  - 读取 `openjiuwen.invoke_id`、`openjiuwen.parent_invoke_id`、`openjiuwen.child_invoke_ids` 属性。
- **T（Then）**：
  - chain 的 `invoke_id` = `"inv-1"`。
  - chain 的 `parent_invoke_id` = `""`（根 invoke）。
  - llm 的 `parent_invoke_id` = chain 的 `invoke_id`（`"inv-1"`）。
  - tool 的 `parent_invoke_id` = chain 的 `invoke_id`（`"inv-1"`）。
  - chain 的 `child_invoke_ids` 包含 `"inv-2"` 和 `"inv-3"`。
- **不应断言**：invoke_id 的生成算法、固定 ID 值（测试中使用固定值是为了可验证性，生产环境 invoke_id 由引擎分配）。
- **失败归类**：关联不一致为 Failure；孤儿 invoke 为 Failure。
- **方法**：`criterion07_invokeTree()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.invoke-tree")`。
- **DisplayName**：`§12.7: Invoke tree — child parent_invoke_id matches parent invoke_id; chain child_invoke_ids exhaustive`。

---

### 4.5 DFX-001.acceptance.span-tree — Span 树 http.request→chain→llm+tool 恰一个 SERVER 根

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.8。
- **测试类型**：contract。
- **Oracle 来源**：§12.8（`http.request`(SERVER, root) → `chain`(INTERNAL) → `llm`(CLIENT) + `tool`(INTERNAL)，恰一个 SERVER 根；所有 span 同一 trace_id；parent-child 关系正确）。
- **G（Given）**：
  - 调用 `buildContractTree("conv-08", "trace-08")` 构建 span 树。
  - http root span 在 `Context.root()` 下创建（无 parent），kind=SERVER。
  - chain span 在 http root scope 下创建，kind=INTERNAL。
  - llm span 在 chain scope 下创建，kind=CLIENT。
  - tool span 在 chain scope 下创建，kind=INTERNAL。
- **W（When）**：
  - 从 exported span 中筛选 http.request、chain、llm、tool span。
  - 检查 trace_id 一致性、parent_span_id 关系、span kind。
  - 统计 SERVER kind 的 span 数量。
- **T（Then）**：
  - chain.traceId = http.traceId = llm.traceId = tool.traceId（同一 trace）。
  - chain.parentSpanId = http.spanId。
  - llm.parentSpanId = chain.spanId。
  - tool.parentSpanId = chain.spanId。
  - http.kind = SERVER。
  - chain.kind = INTERNAL。
  - llm.kind = CLIENT。
  - tool.kind = INTERNAL。
  - http.parentSpanId = `"0000000000000000"`（无效 span ID 表示根）。
  - 恰 1 个 SERVER span。
- **不应断言**：固定 span 数量（取决于工具调用次数）、固定 span name 后缀（如 `chain.EDPAgent` vs `chain.xxx`）。
- **失败归类**：树结构不符为 Failure；parent_span_id 不匹配为 Failure；多个 SERVER 根为 Failure。
- **方法**：`criterion08_spanTreeStructure()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.span-tree")`。
- **DisplayName**：`§12.8: Span tree — http.request(SERVER, root) → chain(INTERNAL) → llm(CLIENT) + tool(INTERNAL)`。

---

### 4.6 DFX-001.acceptance.tool-coverage — 工具调用覆盖

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12.10。
- **测试类型**：contract。
- **Oracle 来源**：§12.10（每次工具调用产生 `tool.*` span，含 `gen_ai.tool.name` 和 `gen_ai.operation.name="execute_tool"`；invoke_type="plugin"）。
- **G（Given）**：
  - 调用 `buildContractTree("conv-10", "trace-10")` 构建 span 树。
  - Tool span 设置 `invokeType="plugin"`、`name="read_file"`。
  - `onPluginStart` 传入 `Map.of("inputs", Map.of("path", "/tmp/test.txt"))`。
  - `onPluginEnd` 传入 `Map.of("code", 0, "message", "success")`。
- **W（When）**：
  - 从 exported span 中筛选 name 以 `"tool."` 开头的 span。
  - 读取 `gen_ai.tool.name`、`gen_ai.operation.name`、`openjiuwen.agent.invoke_type`。
- **T（Then）**：
  - `gen_ai.tool.name` = `"read_file"`。
  - `gen_ai.operation.name` = `"execute_tool"`。
  - `openjiuwen.agent.invoke_type` = `"plugin"`。
- **不应断言**：tool span 的 inputs/outputs 具体内容（已在 json-validity 用例中验证）。
- **失败归类**：属性缺失为 Failure；值不符为 Failure。
- **方法**：`criterion10_toolSpanCoverage()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.tool-coverage")`。
- **DisplayName**：`§12.10: Tool span has gen_ai.tool.name and execute_tool operation`。

---

### 4.7 DFX-001.acceptance.fault-isolation — toJson fallback 返回 null 非 toString（Bug DFX-001-P2 修复验证）

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§5.1.4、Bug 报告 DFX-001-P2。
- **测试类型**：blackbox。
- **Oracle 来源**：§5.1.4（`toJson()` 序列化失败时返回 `null`，属性省略，非 `toString()` 伪影）；Bug 报告 DFX-001-P2（`Throwable` 捕获 + null 降级，此前 `StackOverflowError` 未被捕获导致进程崩溃）。
- **G（Given）**：
  - 构造 `OtelTracerConfig`（exporterType="console", redaction=false, maxAttrLength=-1）。
  - 创建独立的 `SdkTracerProvider` + `InMemorySpanExporter`（不使用 `OtelSdkFactory`，避免采样干扰）。
  - 构造 `OtelJsonAgentHandler`。
  - **场景 1**：构造匿名 `Object` 子类，重写 `toString()` 返回 `"UnserializableBean@abc123"`（Jackson 无法序列化匿名类）。
  - **场景 2**：构造 `HashMap` 循环引用（`cyclicMap.put("self", cyclicMap)`），Jackson 会抛 `StackOverflowError`。
  - **场景 3（对照）**：构造正常 `Map.of("query", "hello", "count", 42)`。
- **W（When）**：
  - 场景 1：调用 `handler.onChainStart(span, unserializable, null)` 和 `handler.onChainEnd(span, unserializable)`。
  - 场景 2：调用 `handler.onChainStart(span, cyclicMap, null)` 和 `handler.onChainEnd(span, cyclicMap)`。
  - 场景 3：调用 `handler.onChainStart(span, inputs, null)` 和 `handler.onChainEnd(span, null)`，forceFlush 后获取 span。
- **T（Then）**：
  - **场景 1**：span 被成功创建（不崩溃），`openjiuwen.agent.inputs` 为 null 或空（fallback 返回 null），不含 `"@"` 或 `"UnserializableBean"`。
  - **场景 2**：span 被成功创建（`StackOverflowError` 被 `Throwable` 捕获），exporter 中有 1 个 span。
  - **场景 3（对照）**：`openjiuwen.agent.inputs` 包含 `"query":"hello"` 和 `"count":42`（正常 JSON 序列化）。
- **不应断言**：异常堆栈文本、内部序列化器实现、异常类型名称（`StackOverflowError` vs `JsonProcessingException`）。
- **失败归类**：异常未捕获（进程崩溃）为 Error；toString 伪影存在为 Failure（Bug DFX-001-P2 回归）；正常对象序列化失败为 Failure。
- **方法**：
  - `toJson_fallbackReturnsNull_notRawObject()` — 场景 1。
  - `selfReferencingObject_doesNotCrash()` — 场景 2。
  - `normalMap_producesValidJson()` — 场景 3（对照）。
- **标签**：`@Tag("dfx-001")`、`@Tag("blackbox")`、`@Story("DFX-001.acceptance.fault-isolation")`。
- **DisplayName**：`toJson fallback returns null (not raw object) — Bug DFX-001-P2 fix verification`。
- **嵌套类**：`@Nested class FaultIsolation`。

---

### 4.8 DFX-001.acceptance.cot-redaction — Chain-of-Thought 不暴露

- **状态/优先级**：passed, P1。
- **自动化状态**：automated。
- **Story/来源**：§5.1.5。
- **测试类型**：contract。
- **Oracle 来源**：§5.1.5（轨迹中不得暴露原始 Chain-of-Thought，属性 key 不得包含 `chain_of_thought`、`raw_cot`、`reasoning_content`）。
- **G（Given）**：调用 `buildContractTree("conv-14", "trace-14")` 构建 span 树。
- **W（When）**：遍历每个 span 的全部属性 key。
- **T（Then）**：没有任何属性 key（转小写后）包含 `"chain_of_thought"` 或 `"raw_cot"`。
- **不应断言**：属性 value 内容（CoT 可能嵌入在合法的 LLM 响应中，由引擎层负责过滤）。
- **失败归类**：CoT 属性 key 存在为 Failure。
- **方法**：`criterion14_noCotExposure()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.cot-redaction")`。
- **DisplayName**：`§5.1.5: No raw Chain-of-Thought exposure in trajectory`。

---

### 4.9 DFX-001.acceptance.sampling — 采样率 0.0 无 span 1.0 全量 span

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§2。
- **测试类型**：blackbox。
- **Oracle 来源**：§2（采样率 0.0 不产生 span，1.0 全量产生 span；采样器为 parentBased(traceIdRatioBased(ratio))）。
- **G（Given）**：
  - **场景 1**：构造 `OtelTracerConfig`（sampleRate=0.0），通过 `OtelSdkFactory.createProvider()` 创建 provider。
  - **场景 2**：构造 `OtelTracerConfig`（sampleRate=1.0），通过 `OtelSdkFactory.createProvider()` 创建 provider。
  - 两个场景都使用独立的 `InMemorySpanExporter`。
- **W（When）**：
  - **场景 1**：创建 1 个 SERVER span，使用 `.setParent(Context.root())` 确保 parentBased 采样器委托给根采样器（traceIdRatioBased 0.0），调用 `span.end()`，forceFlush。
  - **场景 2**：创建 10 个 SERVER span，同样使用 `.setParent(Context.root())`，逐一 `end()`，forceFlush。
- **T（Then）**：
  - **场景 1**：`exporter.getFinishedSpanItems()` 为空（0 个 span）。
  - **场景 2**：`exporter.getFinishedSpanItems()` 有 10 个 span。
- **不应断言**：采样器的内部决策算法、采样概率分布（只验证 0.0 和 1.0 边界值）。
- **关键技术细节**：
  - 必须使用 `.setParent(Context.root())` 而非依赖 `Context.current()`，因为 parentBased 采样器在有父上下文时会委托给父的采样决策，而非使用根采样器。在测试环境中如果没有 HTTP 请求上下文，`Context.current()` 可能携带意外的父 span，导致采样行为与预期不符。
- **失败归类**：span 数量不符为 Failure；采样器配置失败为 Error。
- **方法**：
  - `sampleRateZero_producesNoSpans()` — 场景 1。
  - `sampleRateOne_producesAllSpans()` — 场景 2。
- **标签**：`@Tag("dfx-001")`、`@Tag("blackbox")`、`@Story("DFX-001.acceptance.sampling")`。
- **DisplayName**：`Sample rate 0.0 produces no spans (root spans dropped)` / `Sample rate 1.0 produces all spans`。
- **嵌套类**：`@Nested class Sampling`。

---

### 4.10 DFX-001.acceptance.defaults — 默认关闭 redaction 和 truncation

- **状态/优先级**：passed, P1。
- **自动化状态**：automated。
- **Story/来源**：§2。
- **测试类型**：contract。
- **Oracle 来源**：§2（默认配置 redaction=false, truncation=off/maxAttrLength<=0）。
- **G（Given）**：调用 `OtelEnvProperties.fromSystemEnv()` 读取系统环境变量（不设置任何 OTEL_ 变量）。
- **W（When）**：调用 `props.toTracerConfig()` 构建配置对象。
- **T（Then）**：
  - `config.isRedactionEnabled()` = false。
  - `config.getMaxAttrLength()` <= 0（表示截断关闭）。
- **不应断言**：具体 maxAttrLength 值（-1 或 0 均表示关闭）。
- **失败归类**：默认值不符为 Failure。
- **方法**：`defaultConfig_redactionOff_noTruncation()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.defaults")`。
- **DisplayName**：`§2: Default config has redaction disabled and truncation off`。

---

### 4.11 DFX-001.acceptance.error-scenario — 工具异常产生 ERROR status span

- **状态/优先级**：passed, P1。
- **自动化状态**：automated。
- **Story/来源**：§5.1.4（故障隔离在错误场景下的表现）。
- **测试类型**：blackbox。
- **Oracle 来源**：§5.1.4（工具调用异常时 span 仍应被创建，不应崩溃）。
- **G（Given）**：
  - 构造 chain span (traceId="trace-err", invokeId="inv-e1") 和 tool span (invokeId="inv-e2", parentInvokeId="inv-e1")。
  - Tool name 设为 `"failing_tool"`。
  - 设置 `SessionContextHolder.setCurrentSession(sessionOf("conv-err"))`。
- **W（When）**：
  - 调用 `handler.onChainStart(chain, Map.of("query", "trigger error"), null)`。
  - 调用 `handler.onPluginStart(tool, Map.of("inputs", Map.of("cmd", "fail")), null)`。
  - 调用 `handler.onPluginEnd(tool, null)` — null output 表示失败。
  - 调用 `handler.onChainEnd(chain, Map.of("error", "tool failed"))`。
  - forceFlush 后获取 span。
- **T（Then）**：span 列表不为空（尽管工具调用失败，span 仍被创建和导出）。
- **不应断言**：span 的 status_code（ERROR vs UNSET 取决于 handler 实现）、error message 内容。
- **失败归类**：span 未创建为 Failure；handler 抛异常为 Error。
- **方法**：`toolException_producesErrorSpan()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("blackbox")`、`@Story("DFX-001.acceptance.error-scenario")`。
- **DisplayName**：`Tool exception produces ERROR status span`。
- **嵌套类**：`@Nested class ErrorScenarios`。

---

### 4.12 DFX-001.acceptance.hex-format — trace_id/span_id hex 格式

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§8.3。
- **测试类型**：contract。
- **Oracle 来源**：§8.3（trace_id 为 32 位小写 hex，span_id 为 16 位小写 hex）。
- **G（Given）**：调用 `buildContractTree("conv-11", "trace-11")` 构建 span 树。
- **W（When）**：遍历每个 span 的 trace_id 和 span_id。
- **T（Then）**：
  - `trace_id` 长度为 32，匹配正则 `[0-9a-f]{32}`。
  - `span_id` 长度为 16，匹配正则 `[0-9a-f]{16}`。
- **不应断言**：固定 hex 值（OTel SDK 随机生成）。
- **失败归类**：格式不符为 Failure。
- **方法**：`criterion11_hexFormat()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.hex-format")`。
- **DisplayName**：`§8.3: trace_id is 32-char lowercase hex, span_id is 16-char lowercase hex`。

---

### 4.13 DFX-001.acceptance.business-trace-id — 业务 trace ID 一致性

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§9.C。
- **测试类型**：contract。
- **Oracle 来源**：§9.C（`openjiuwen.trace.id` 在同一 OTel trace 内一致）。
- **G（Given）**：调用 `buildContractTree("conv-12", "trace-12")` 构建 span 树，业务 trace ID 为 `"trace-12"`。
- **W（When）**：从 chain span 读取 `openjiuwen.trace.id` 属性。
- **T（Then）**：`openjiuwen.trace.id` = `"trace-12"`（不为 null）。
- **不应断言**：所有 span 都有此属性（由引擎 handler 写入，http.request span 可能不含）。
- **失败归类**：属性缺失为 Failure；值不一致为 Failure。
- **方法**：`criterion12_businessTraceId()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.business-trace-id")`。
- **DisplayName**：`§9.C: openjiuwen.trace.id is consistent within the same trace`。

---

### 4.14 DFX-001.acceptance.finish-reason — LLM finish_reason

- **状态/优先级**：passed, P1。
- **自动化状态**：automated。
- **Story/来源**：§7.2。
- **测试类型**：contract。
- **Oracle 来源**：§7.2（LLM span 应包含 `openjiuwen.llm.finish_reason` 属性）。
- **G（Given）**：调用 `buildContractTree("conv-13", "trace-13")` 构建 span 树，LLM 的 `onLlmEnd` 传入 `finish_reason="stop"`。
- **W（When）**：从 llm span 读取 `openjiuwen.llm.finish_reason` 属性。
- **T（Then）**：`openjiuwen.llm.finish_reason` = `"stop"`。
- **不应断言**：finish_reason 的业务正确性（只验证属性存在和值类型）。
- **失败归类**：属性缺失为 Failure。
- **方法**：`criterion13_finishReason()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.acceptance.finish-reason")`。
- **DisplayName**：`§7.2: LLM span has openjiuwen.llm.finish_reason`。

---

### 4.15 DFX-001.otlp.e2e — 真实 OTLP gRPC 上报 + JSONL 数据契约 18 项校验

- **状态/优先级**：passed, P0。
- **自动化状态**：automated。
- **Story/来源**：§12 全部 + §8.3 + §9.C + §5.1.5 + §7.2 + §7.4 + §7.6 + §8.5。
- **测试类型**：integration。
- **Oracle 来源**：需求特性文档 §12 全部验收准则 + 数据需求说明中的字段规范。
- **G（Given）**：
  - 启动 `otlp_relay.py` 监听 gRPC :4317：
    ```bash
    python otlp_relay.py serve --jsonl reports/otlp_spans.jsonl --grpc-port 4317
    ```
  - relay 使用手写 protobuf wire-format 解析器（不依赖 `opentelemetry-proto` 或 `grpcio-tools`），通过 `grpc.server()` + `GenericRpcHandler` 接收 OTLP `ExportTraceServiceRequest`。
  - relay 将每个 span 解析为 JSON 对象并写入 JSONL 文件，字段包括：trace_id, span_id, parent_span_id, name, kind, start_time, end_time, status_code, status_message, attributes, resource_attributes, scope_name, scope_version。
  - 运行 `OtlpRelayCheckIT` 测试，配置 `OtelTracerConfig` 指向 relay 端点，发送合同 span 树（http.request → chain → llm + tool，4 个 span）。
- **W（When）**：
  - `OtlpRelayCheckIT` 通过真实 OTLP gRPC exporter 将 span 发送到 relay。
  - relay 接收 protobuf 字节流，解析 wire-format（varint、length-delimited、fixed64），提取 span 字段和属性。
  - relay 将 span 写入 `reports/otlp_spans.jsonl`。
  - 运行 `span_validator.py --jsonl reports/otlp_spans.jsonl --verbose` 对 JSONL 执行 18 项数据契约检查。
- **T（Then）**：
  - JSONL 文件包含 4 个 span，trace_id 一致。
  - **TC-01**：Span 通用字段完整（trace_id, span_id, name, kind, start_time, end_time, attributes, resource_attributes, status_code 均存在且非 null）。
  - **TC-02**：session.id 全覆盖（含 tool span）。
  - **TC-03**：Resource 属性（service.name, service.instance.id 存在；scope.name = `openjiuwen.tracer.otel`，http.request span 除外）。
  - **TC-04**：JSON 合法性（`openjiuwen.agent.inputs/outputs`, `gen_ai.prompt/completion`, `openjiuwen.child_invoke_ids`, `openjiuwen.meta_data`, `openjiuwen.http.request_body`, `openjiuwen.http.response_summary` 均可被 `json.loads()` 解析；无 `toString()` 伪影、无 `ClassName@hex` 模式、无 Python dict repr）。
  - **TC-05**：时间格式一致性（top-level `start_time`/`end_time` 和 attributes `openjiuwen.start_time`/`openjiuwen.end_time` 均匹配 ISO 8601 带时区正则 `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?([+-]\d{2}:\d{2}|Z)$`）。
  - **TC-06**：Token 命名（`gen_ai.usage.input_tokens` 和 `gen_ai.usage.output_tokens` 存在于 llm span）。
  - **TC-07**：invoke 树自洽（child `parent_invoke_id` 在 invoke_map 中找到；parent 的 `child_invoke_ids` JSON 列表包含 child 的 `invoke_id`；chain 的 `child_invoke_ids` 中的每个 ID 都有对应 span）。
  - **TC-08**：Span 树结构（按 trace_id 分组后，每组恰 1 个 SERVER 根；root 的 parent_span_id 为空或 `"0000000000000000"`；chain 的 parent_span_id = http 的 span_id；llm kind=CLIENT；tool kind=INTERNAL；chain kind=INTERNAL；http kind=SERVER）。
  - **TC-09**：Chain 响应摘要（chain span 的 `openjiuwen.agent.outputs` 存在且可被 `json.loads()` 解析）。
  - **TC-10**：工具调用覆盖（tool span 包含 `gen_ai.tool.name` 和 `gen_ai.operation.name="execute_tool"`；inputs/outputs 为合法 JSON）。
  - **TC-11**：trace_id 32 位小写 hex，span_id 16 位小写 hex，parent_span_id 16 位小写 hex（非零时）。
  - **TC-12**：业务 trace ID 一致性（同一 trace_id 下所有 `openjiuwen.trace.id` 值唯一）。
  - **TC-13**：LLM finish_reason（llm span 包含 `openjiuwen.llm.finish_reason`）。
  - **TC-14**：CoT 不暴露（无属性 key 包含 `reasoning_content`/`chain_of_thought`/`cot`，警告级别）。
  - **TC-15**：故障隔离（无 `ClassName@hex` 模式、无 Python dict repr、无 constructor repr）。
  - **TC-16**：子 agent dispatch（`sub_agent.dispatch` span 包含 `openjiuwen.subagent.entity_id/entity_name/query/status`）。
  - **TC-17**：HTTP request body（`http.request` span 包含 `openjiuwen.http.request_body` 为合法 JSON，包含 `http.request.method`）。
  - **TC-18**：Cost 语义（`openjiuwen.cost.total` 不为以 `s` 或 `ms` 结尾的字符串，避免与时间值混淆）。
- **不应断言**：protobuf 序列化字节级一致性、Collector 内部处理逻辑、gRPC 连接池管理。
- **失败归类**：数据契约不符为 Failure；relay 启动失败为 Error；span 未到达 relay 为 Failure；protobuf 解析错误为 Error。
- **方法**：
  - Java: `OtlpRelayCheckIT`（发送 span 到 relay）。
  - Python: `span_validator.py --jsonl ... --verbose`（18 项契约校验）。
- **标签**：`@Tag("dfx-001")`、`@Tag("integration")`、`@Story("DFX-001.otlp.e2e")`。
- **DisplayName**：`DFX-001 真实 OTLP gRPC 上报 + JSONL 数据契约 18 项校验`。
- **relay 技术细节**：
  - 监听 gRPC 方法路径：`/opentelemetry.proto.collector.trace.v1.TraceService/Export`
  - 使用 `grpc.unary_unary_rpc_method_handler` + `request_deserializer=lambda x: x`（传递原始字节）
  - protobuf 解析器手动实现：`read_varint`、`read_tag`、`read_length_delimited`、`read_fixed64`、`skip_field`
  - span 字段映射：field 1=trace_id(bytes→hex), 2=span_id(bytes→hex), 3=trace_state(skip), 4=parent_span_id(bytes→hex), 5=name(string), 6=kind(enum), 7=start_time_unix_nano(fixed64), 8=end_time_unix_nano(fixed64), 9=attributes(repeated KeyValue), 15=status
  - 纳秒时间戳转换为 ISO 8601 UTC：`datetime.fromtimestamp(nano / 1e9, tz=timezone.utc).isoformat()`

---

### 4.16 DFX-001.bug-repro.timeout-format — BUG-1: OTEL 标准毫秒值 '30000' 应可解析但当前抛出异常

- **状态/优先级**：passed, P2。
- **自动化状态**：automated。
- **Story/来源**：深度边界测试 — [OtelEnvProperties.java:86-92](file:///e:/Project/CodeProject/Fuyingjieneu/DFX-001-需求测试/agent-solution_new/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-otel/src/main/java/com/openjiuwen/service/adapters/otel/OtelEnvProperties.java#L86-L92)。
- **测试类型**：contract。
- **Oracle 来源**：OTEL 官方规范定义 `OTEL_EXPORTER_OTLP_TIMEOUT` 为毫秒数值（如 `"30000"` 表示 30 秒）；当前代码执行 `Duration.parse("PT" + raw)`，对 `"30000"` 构造出非法的 `"PT30000"`，抛出 `DateTimeParseException`。
- **G（Given）**：
  - 构造 `OtelEnvProperties`，环境变量映射为 `Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "30000")`。
  - **场景 1（Bug 复现）**：OTEL 标准值 `"30000"`。
  - **场景 2（对照）**：非标准值 `"30S"`（当前代码期望的格式）。
  - **场景 3（对照）**：无环境变量（默认值）。
- **W（When）**：
  - 三个场景分别调用 `props.getTimeout()`。
- **T（Then）**：
  - **场景 1**：抛出 `java.time.format.DateTimeParseException`（确认 Bug 存在）。
  - **场景 2**：返回 `Duration.ofSeconds(30)`（非标准格式可正常解析）。
  - **场景 3**：返回 `Duration.ofSeconds(10)`（默认超时 10 秒）。
  - **修复后期望（场景 1）**：返回 `Duration.ofMillis(30000)` = 30 秒，不抛异常。
- **不应断言**：异常消息文本。
- **失败归类**：
  - 场景 1：异常未抛出（Bug 已修复）为 Failure（测试需同步更新为验证修复后的正确行为）。
  - 场景 2/3：返回值不符为 Failure。
- **方法**：
  - `bug1_otelStandardTimeoutFormat_throwsException()` — 场景 1。
  - `bug1_nonStandardFormat_works()` — 场景 2。
  - `bug1_defaultTimeout_is10Seconds()` — 场景 3。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.bug-repro.timeout-format")`。
- **DisplayName**：`BUG-1: OTEL标准毫秒值 '30000' 应返回30秒但当前抛出 DateTimeParseException`。
- **修复建议代码**：
  ```java
  public Duration getTimeout() {
      String raw = env.apply(ENV_TIMEOUT);
      if (raw == null || raw.isBlank()) {
          return DEFAULT_TIMEOUT;
      }
      String trimmed = raw.trim();
      // 优先按 OTEL 标准尝试纯数字（毫秒）
      try {
          long millis = Long.parseLong(trimmed);
          return Duration.ofMillis(millis);
      } catch (NumberFormatException e) {
          // 不是纯数字，尝试 ISO-8601 duration 格式（如 "PT30S"）
          return Duration.parse(trimmed.startsWith("PT") ? trimmed : "PT" + trimmed);
      }
  }
  ```

---

### 4.17 DFX-001.bug-repro.timeout-not-applied — BUG-2: 设置 OTEL_EXPORTER_OTLP_TIMEOUT=5S 后 config 仍使用默认值 30000ms

- **状态/优先级**：passed, P2。
- **自动化状态**：automated。
- **Story/来源**：深度边界测试 — [OtelEnvProperties.java:126-138](file:///e:/Project/CodeProject/Fuyingjieneu/DFX-001-需求测试/agent-solution_new/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-otel/src/main/java/com/openjiuwen/service/adapters/otel/OtelEnvProperties.java#L126-L138)。
- **测试类型**：contract。
- **Oracle 来源**：`getTimeout()` 能正确读取和解析 `OTEL_EXPORTER_OTLP_TIMEOUT`，但 `toTracerConfig()` 构建配置时未调用 `getTimeout()`，导致用户设置的超时值被静默忽略，config 始终使用默认 30000ms。
- **G（Given）**：
  - 构造 `OtelEnvProperties`，环境变量映射为 `Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "5S")`。
  - `getTimeout()` 可正确解析 `"5S"` 为 5 秒。
- **W（When）**：
  - 先验证 `props.getTimeout()` 返回 `Duration.ofSeconds(5)`（方法本身正确）。
  - 再调用 `props.toTracerConfig()` 构建 `OtelTracerConfig`。
  - 检查 `config.getExportTimeoutMs()` 的值。
- **T（Then）**：
  - `props.getTimeout()` = `Duration.ofSeconds(5)`（方法正确）。
  - `config.getExportTimeoutMs()` = 30000L（默认值），**不等于** 5000L（确认 Bug 存在）。
  - **修复后期望**：`config.getExportTimeoutMs()` = 5000L。
- **不应断言**：builder 内部字段传递机制。
- **失败归类**：
  - config 返回 5000（Bug 已修复）为 Failure（测试需同步更新）。
  - `getTimeout()` 返回值不符为 Failure。
- **方法**：`bug2_timeoutEnvVar_isSilentlyIgnored()`。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.bug-repro.timeout-not-applied")`。
- **DisplayName**：`BUG-2: 设置 OTEL_EXPORTER_OTLP_TIMEOUT=5S 后 config 仍使用默认值 30000ms`。
- **修复建议代码**：
  ```java
  public OtelTracerConfig toTracerConfig() {
      return OtelTracerConfig.builder()
              .exporterType("otlp")
              .exporterEndpoint(getEndpoint())
              .protocol(getProtocol())
              .headers(getHeaders())
              .serviceName(getServiceName())
              .serviceVersion(getServiceVersion())
              .sampleRate(getSampleRate())
              .isRedactionEnabled(false)
              .maxAttrLength(-1)
              .exportTimeoutMs((int) getTimeout().toMillis())  // ← 新增
              .build();
  }
  ```

---

### 4.18 DFX-001.bug-repro.stream-premature-end — BUG-3: stream() 迭代器未消费时 span 已结束（对比 invoke 正确）

- **状态/优先级**：passed, P3。
- **自动化状态**：automated。
- **Story/来源**：深度边界测试 — [OtelRemoteClientDecoratorFactory.java:92-108](file:///e:/Project/CodeProject/Fuyingjieneu/DFX-001-需求测试/agent-solution_new/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-otel/src/main/java/com/openjiuwen/service/adapters/otel/egress/OtelRemoteClientDecoratorFactory.java#L92-L108)。
- **测试类型**：blackbox。
- **Oracle 来源**：`stream()` 方法在 `finally` 块中调用 `span.end()`，但返回的迭代器尚未被消费；对比 `invoke()` 方法在调用完成后才 `end()` span，行为正确。
- **G（Given）**：
  - 创建 `InMemorySpanExporter` + `SdkTracerProvider`。
  - 构造 `OtelRemoteClientDecoratorFactory`。
  - **场景 1（Bug 复现）**：构造 mock `RemoteClient`，其 `stream()` 返回 `List.of("chunk1", "chunk2", "chunk3").iterator()`，`invoke()` 返回 `"result"`。
  - **场景 2（对照）**：同一 mock，使用 `invoke()` 而非 `stream()`。
  - `RemoteClientConfig` 设置 id="sub-agent-1"/name="sub-agent-1"。
  - 通过 `factory.decorate(rcConfig, mockDelegate, null)` 获取装饰后的 client。
- **W（When）**：
  - **场景 1**：调用 `decorated.stream(Map.of("query", "test"), 10.0)`，获取迭代器但**不消费**。`forceFlush` 后检查 exporter 中是否有已结束的 span。
  - **场景 2**：调用 `decorated.invoke(Map.of("query", "test"), 10.0)`，获取返回值。`forceFlush` 后检查 exporter 中的 span。
- **T（Then）**：
  - **场景 1**：
    - exporter 中已有 1 个 span（span 已 end，但迭代器未消费）— **确认 Bug 存在**。
    - span name = `"sub_agent.dispatch"`。
    - `openjiuwen.subagent.status` = `"streaming"`（但 span 已结束，语义矛盾）。
    - 迭代器仍可用：`hasNext()` = true，`next()` = `"chunk1"`。
  - **场景 2（对照）**：
    - exporter 中有 1 个 span。
    - `openjiuwen.subagent.status` = `"completed"`（正确：invoke 完成后才 end）。
    - 返回值 = `"result"`。
  - **修复后期望（场景 1）**：在迭代器消费完毕前，exporter 中不应有 span。
- **不应断言**：span 的 elapsed_time 精确值。
- **失败归类**：
  - 场景 1：span 未在迭代器未消费时 end（Bug 已修复）为 Failure（测试需同步更新）。
  - 场景 2：invoke 行为不符为 Failure（对照组应始终正确）。
- **方法**：
  - `bug3_streamSpan_endedBeforeIteratorConsumed()` — 场景 1。
  - `bug3_invokeSpan_endedAfterCallCompletes()` — 场景 2。
- **标签**：`@Tag("dfx-001")`、`@Tag("blackbox")`、`@Story("DFX-001.bug-repro.stream-premature-end")`。
- **DisplayName**：`BUG-3: stream() 返回的迭代器尚未消费时 span 已结束` / `BUG-3 对照: invoke() 正确地在调用完成后才结束 span`。
- **修复建议代码**：
  ```java
  @Override
  public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
      Span span = startDispatchSpan(inputs);
      long startNanos = System.nanoTime();
      try {
          Iterator<Object> iterator = delegate.stream(inputs, timeoutSeconds);
          span.setAttribute(statusKey(), "streaming");
          return new Iterator<>() {
              @Override
              public boolean hasNext() {
                  if (iterator.hasNext()) return true;
                  span.setAttribute(elapsedKey(), (System.nanoTime() - startNanos) / 1_000_000L);
                  span.setAttribute(statusKey(), "completed");
                  span.end();
                  return false;
              }
              @Override
              public Object next() {
                  return iterator.next();
              }
          };
      } catch (Exception e) {
          span.setAttribute(statusKey(), "error");
          span.setAttribute("error.message", e.getMessage());
          span.recordException(e);
          span.end();
          throw e;
      }
  }
  ```
  > **要点**：`span.end()` 必须从 `finally` 块移到迭代器的 `hasNext()` 返回 `false` 时（或 `next()` 抛异常时），确保 span 的生命周期与实际数据消费一致。对照 `invoke()` 方法的行为——在调用返回后才 `end()` span——`stream()` 也应在流结束后才 `end()`。

---

### 4.19 DFX-001.bug-repro.tz-dependent-iso — 问题-4: toZonedIso() 时区依赖导致跨时区时间格式不一致

- **状态/优先级**：passed（问题已确认），P4。
- **自动化状态**：automated。
- **Story/来源**：深度边界测试 — [OtelCompatSpanExporter.java:89-98](file:///e:/Project/CodeProject/Fuyingjieneu/DFX-001-需求测试/agent-solution_new/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-otel/src/main/java/com/openjiuwen/service/adapters/otel/OtelCompatSpanExporter.java#L89-L98)（`toZonedIso` 方法）。
- **测试类型**：contract。
- **Oracle 来源**：§12.5 要求时间格式为 ISO 8601 带时区。当前实现使用 `ZoneId.systemDefault()` 作为默认时区，在不同 JVM 时区下产生的 ISO 字符串偏移量不同（如 `+08:00` vs `+00:00`），虽各自合法但跨时区不一致，可能导致下游聚合或排序偏差。
- **G（Given）**：
  - 创建 `InMemorySpanExporter`，包装在 `OtelCompatSpanExporter` 中（`SimpleSpanProcessor.create(new OtelCompatSpanExporter(exporter))`）。
  - 创建 `SdkTracerProvider`，注册上述 processor。
  - **场景 1（已有时区）**：创建 span，设置 `openjiuwen.start_time` = `"2024-01-15T10:30:00+08:00"`，end span。
  - **场景 2（无时区）**：创建 span，设置 `openjiuwen.start_time` = `"2024-01-15T10:30:00"`（无时区后缀），end span。
  - **场景 3（非法时间）**：创建 span，设置 `openjiuwen.start_time` = `"not-a-date"`，end span。
- **W（When）**：
  - 每个场景：`provider.forceFlush()` 后从 `exporter.getFinishedSpanItems()` 获取 span，读取 `openjiuwen.start_time` 属性值。
- **T（Then）**：
  - **场景 1**：`openjiuwen.start_time` = `"2024-01-15T10:30:00+08:00"`（已有时区的值原样透传，行为正确）。
  - **场景 2**：`openjiuwen.start_time` 不为 null，且匹配正则 `.*([+-]\\d{2}:\\d{2}|Z)$`（被添加系统时区偏移量 — **确认问题存在**：偏移量取决于 `ZoneId.systemDefault()`，不同 JVM 时区结果不同）。
  - **场景 3**：`openjiuwen.start_time` 为 null（非法时间值被移除，正确降级）。
  - **修复后期望（场景 2）**：统一使用 UTC（`Z`），使跨环境结果一致。
- **不应断言**：具体的时间戳数值、具体的时区偏移量（取决于 JVM 默认时区设置）。
- **失败归类**：
  - 场景 1：属性值被修改为 Failure（已有时区应原样透传）。
  - 场景 2：属性值为 null 或不匹配时区正则为 Failure。
  - 场景 3：属性值不为 null 为 Failure（非法时间应被移除）。
- **方法**：
  - `bug4_timeWithTimezone_passesThrough()` — 场景 1。
  - `bug4_timeWithoutTimezone_getsSystemTimezone()` — 场景 2。
  - `bug4_invalidTime_isRemoved()` — 场景 3。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.bug-repro.tz-dependent-iso")`。
- **DisplayName**：`问题4-a: 已有时区的时间值直接透传（行为正确）` / `问题4-b: 无时区的时间值被添加系统时区（跨部署可能不一致）` / `问题4-c: 无法解析的时间值被移除（正确降级）`。
- **修复建议代码**：
  ```java
  // 修改前：依赖系统默认时区
  private static String toZonedIso(String value) {
      if (value.matches(".*([+-]\\d{2}:\\d{2}|Z)$")) {
          return value;
      }
      try {
          return LocalDateTime.parse(value)
              .atZone(ZoneId.systemDefault())  // ← 问题：不同 JVM 时区结果不同
              .toOffsetDateTime().toString();
      } catch (DateTimeParseException e) {
          return null;
      }
  }

  // 修改后：统一使用 UTC
  private static String toZonedIso(String value) {
      if (value.matches(".*([+-]\\d{2}:\\d{2}|Z)$")) {
          return value;
      }
      try {
          return LocalDateTime.parse(value)
              .atZone(ZoneOffset.UTC)  // ← 统一 UTC，跨环境一致
              .toOffsetDateTime().toString();
      } catch (DateTimeParseException e) {
          return null;
      }
  }
  ```
  > **要点**：将 `ZoneId.systemDefault()` 改为 `ZoneOffset.UTC`，确保所有环境下 `start_time` / `end_time` 均以 UTC 格式上报。若业务需求要求本地时区，可通过环境变量 `OTEL_EXPORTER_OTLP_HEADERS` 或自定义属性 `openjiuwen.agent.timezone` 补充携带，但 ISO 字符串本身应统一 UTC。

---

### 4.20 DFX-001.bug-repro.env-config-boundary — 环境变量配置边界：空白超时、headers 解析、协议校验

- **状态/优先级**：passed，P1。
- **自动化状态**：automated。
- **Story/来源**：深度边界测试 — §3 环境变量配置表。
- **测试类型**：contract。
- **Oracle 来源**：§3 定义了 `OTEL_EXPORTER_OTLP_TIMEOUT`（接受毫秒数或带单位 Duration 字符串，空白时使用默认值）、`OTEL_EXPORTER_OTLP_HEADERS`（支持 `=` 号分隔、URL 解码）、`OTEL_EXPORTER_OTLP_PROTOCOL`（仅接受小写 `grpc`/`http`）、`OTEL_SERVICE_VERSION`（未设置时为 null）。需验证这些边界场景的解析行为。
- **G（Given）**：
  - **场景 1（空白超时）**：构造 `OtelEnvProperties`，`OTEL_EXPORTER_OTLP_TIMEOUT` = `"   "`（纯空白）。
  - **场景 2（服务版本未设置）**：构造 `OtelEnvProperties`，环境变量映射返回 `null`（未设置 `OTEL_SERVICE_VERSION`）。
  - **场景 3（headers 含 = 号）**：构造 `OtelEnvProperties`，`OTEL_EXPORTER_OTLP_HEADERS` = `"auth=Bearer token=abc123"`。
  - **场景 4（headers URL 解码）**：构造 `OtelEnvProperties`，`OTEL_EXPORTER_OTLP_HEADERS` = `"x-token%20id=Bearer%20abc"`。
  - **场景 5（协议大写）**：构造 `OtelEnvProperties`，`OTEL_EXPORTER_OTLP_PROTOCOL` = `"GRPC"`（大写）。
- **W（When）**：
  - 场景 1：调用 `props.getTimeout()`。
  - 场景 2：调用 `props.getServiceVersion()` 和 `props.toTracerConfig()`。
  - 场景 3：调用 `props.getHeaders()`。
  - 场景 4：调用 `props.getHeaders()`。
  - 场景 5：调用 `props.getProtocol()`。
- **T（Then）**：
  - **场景 1**：`getTimeout()` = `Duration.ofSeconds(10)`（空白值 fallback 到默认 10 秒）。
  - **场景 2**：`getServiceVersion()` = `null`（未设置时返回 null，非 `"unknown"`；`OtelSdkFactory` 中用 `"unknown"` 做兜底）。
  - **场景 3**：headers 包含 entry `"auth"` → `"Bearer token=abc123"`（value 中的 `=` 号被保留，仅按第一个 `=` 分割 key/value）。
  - **场景 4**：headers 包含 entry `"x-token id"` → `"Bearer abc"`（key 和 value 均被 URL 解码，`%20` → 空格）。
  - **场景 5**：抛出 `IllegalArgumentException`，消息包含 `"GRPC"`（仅接受小写 `grpc`/`http`）。
- **不应断言**：`OtelSdkFactory` 中 `"unknown"` 兜底逻辑的具体实现。
- **失败归类**：
  - 场景 1：空白超时未 fallback 到默认值为 Failure。
  - 场景 2：`getServiceVersion()` 返回非 null 为 Failure。
  - 场景 3：headers value 中 `=` 号被截断为 Failure（应仅按第一个 `=` 分割）。
  - 场景 4：headers 未进行 URL 解码为 Failure。
  - 场景 5：大写协议被接受为 Failure（应抛异常）。
- **方法**：
  - `blankTimeout_usesDefault()` — 场景 1。
  - `serviceVersionNotSet_isNull()` — 场景 2。
  - `headersValueWithEquals()` — 场景 3。
  - `headersUrlDecoded()` — 场景 4。
  - `protocolUppercase_notAccepted()` — 场景 5。
- **标签**：`@Tag("dfx-001")`、`@Tag("contract")`、`@Story("DFX-001.bug-repro.env-config-boundary")`。
- **DisplayName**：`空白 OTEL_EXPORTER_OTLP_TIMEOUT 使用默认值` / `OTEL_SERVICE_VERSION 未设置时为 null` / `headers 中 value 包含 '=' 号时正确解析` / `headers 中 key 和 value 都被 URL 解码` / `protocol 大写值不被接受（仅小写 grpc/http）`。

---

## 5. 文件、执行与退出标准

### 5.1 测试文件清单

测试文件统一归档于 `agent-solution_new/common/example/otel-trajectory-demo` Maven 子模块和 `DFX-001-test-scripts` 归档目录中：

| 类别 | 文件路径 | 说明 |
|---|---|---|
| 验收测试 | `src/test/java/.../DFX001AcceptanceTest.java` | §4.1~§4.14，20 个验收用例 |
| OTLP 端到端测试 | `src/test/java/.../OtlpRelayCheckIT.java`（位于 `agent-service-adapters-otel` 模块） | §4.15，1 个集成测试用例 |
| Bug 复现测试 | `src/test/java/.../DFX001BugReproTest.java` | §4.16~§4.19，9 个 Bug 复现用例 |
| 深度边界测试 | `src/test/java/.../DFX001DeepEdgeTest.java` | §4.16~§4.20，14 个深度边界用例（含 bug 深度复测 + 环境变量边界） |
| OTLP Relay | `python/otlp_relay.py` | 手写 protobuf wire-format 解析器，零 `opentelemetry-proto` 依赖 |
| Span 校验器 | `python/span_validator.py` | 18 项数据契约校验逻辑 |
| 一键执行脚本 | `run_dfx001_tests.py` | Python 编排脚本：启动 relay → Maven 测试 → 数据契约校验 → 汇总报告 |
| 测试报告 | `reports/DFX-001-测试报告.md` | 128 用例全通过的详细测试报告 |
| Maven POM | `pom.xml` | 子模块定义，依赖 ext-java parent POM 管理 OTel SDK 版本 |
| README | `README.md` | 模块使用说明 |

### 5.2 执行方式

```bash
# 方式 1：一键执行（推荐）
cd agent-solution_new/common/example/otel-trajectory-demo
python run_dfx001_tests.py

# 方式 2：仅运行 Maven 测试（不含 OTLP 端到端）
cd agent-solution_new/common/example/otel-trajectory-demo
.\mvnw.cmd -pl common/example/otel-trajectory-demo test

# 方式 3：仅运行验收测试
.\mvnw.cmd -pl common/example/otel-trajectory-demo -Dtest=DFX001AcceptanceTest test

# 方式 4：仅运行 Bug 复现测试
.\mvnw.cmd -pl common/example/otel-trajectory-demo -Dtest=DFX001BugReproTest test

# 方式 5：仅运行深度边界测试
.\mvnw.cmd -pl common/example/otel-trajectory-demo -Dtest=DFX001DeepEdgeTest test

# 方式 6：运行全部 DFX-001 测试（标签过滤）
.\mvnw.cmd -pl common/example/otel-trajectory-demo -Dgroups=dfx-001 test
```

### 5.3 环境变量配置

测试可通过以下环境变量控制行为（均有默认值，无需手动设置即可运行）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP gRPC 上报端点（relay 监听地址） |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | `30000` | OTLP 上报超时（毫秒） |
| `OTEL_TRACES_SAMPLER` | `parentBased(traceIdRatioBased)` | 采样器类型 |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | 采样率（0.0~1.0） |
| `OTEL_SERVICE_NAME` | `agent-service` | 服务名 |
| `OTEL_SERVICE_INSTANCE_ID` | 自动生成 UUID | 服务实例 ID |

> **注意**：`run_dfx001_tests.py` 脚本会自动在启动 relay 前设置 `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`，测试结束后恢复原始环境变量。

### 5.4 测试数据准备

- **验收测试 / Bug 复现测试 / 深度边界测试**：全部使用 mock `RemoteClient` 和 `InMemorySpanExporter`，无需真实 LLM 后端或网络服务。
- **OTLP 端到端测试**（§4.15）：使用 `python/otlp_relay.py` 作为本地 mock OTLP Collector，监听 `localhost:4317`。relay 接收 OTLP gRPC 请求后，通过手写 protobuf wire-format 解析器提取 span 数据，生成 JSONL 文件供 `span_validator.py` 校验。
- **不依赖外部密钥**：所有测试数据由测试代码内部构造，不读取 `LLM_API_KEY` 等密钥环境变量。

### 5.5 清理与恢复

- **Maven 测试**：JUnit 5 的 `@AfterEach` / `@AfterAll` 负责关闭 `SdkTracerProvider` 和 `InMemorySpanExporter`，释放资源。
- **OTLP Relay**：`run_dfx001_tests.py` 在测试结束后通过 `atexit` 和 `try/finally` 确保 relay 进程被终止，端口 4317 被释放。
- **环境变量**：脚本在修改环境变量前保存原始值，结束后恢复。
- **临时文件**：relay 生成的 JSONL 文件保存在 `reports/` 目录下（用于事后审计），不自动删除；如需清理可手动删除 `reports/*.jsonl`。
- **端口检查**：脚本启动前检查端口 4317 是否被占用，如被占用则尝试终止占用进程或提示用户手动处理。

### 5.6 退出标准

| 标准 | 当前状态 |
|---|---|
| §12 全部 10 条验收准则均有对应自动化用例且通过 | ✅ 已满足（§4.1~§4.15） |
| §8.3 hex 格式验证通过 | ✅ 已满足（§4.12） |
| §9.C 业务 trace ID 一致性验证通过 | ✅ 已满足（§4.13） |
| §5.1.4 故障隔离（toJson fallback）验证通过 | ✅ 已满足（§4.7） |
| §5.1.5 CoT 不暴露验证通过 | ✅ 已满足（§4.8） |
| §2 采样控制（0.0 无 span / 1.0 全量 span）验证通过 | ✅ 已满足（§4.9） |
| §3 环境变量配置边界验证通过 | ✅ 已满足（§4.20） |
| Bug DFX-001-P2 复现与修复验证 | ✅ 已满足（§4.7） |
| OTLP gRPC 端到端上报 + 18 项数据契约校验通过 | ✅ 已满足（§4.15） |
| 4 个产品代码问题均有复现用例 | ✅ 已满足（§4.16~§4.19） |
| 128 个用例全部通过 | ✅ 已满足 |
| 无敏感信息泄露（API Key 等） | ✅ 已满足（测试不依赖外部密钥） |
| 无进程/端口泄漏 | ✅ 已满足（脚本自动清理） |

### 5.7 已知门禁与遗留项

1. **BUG-1（§4.16）**：`getTimeout()` 方法对 OTEL 标准毫秒值 `'30000'` 抛出异常 — 建议开发团队修复后更新测试期望为 Pass。
2. **BUG-2（§4.17）**：设置 `OTEL_EXPORTER_OTLP_TIMEOUT=5S` 后 config 仍使用默认值 30000ms — 建议开发团队修复后更新测试期望为 Pass。
3. **BUG-3（§4.18）**：`stream()` 方法在迭代器未消费时已 `span.end()` — 建议开发团队修复后更新测试期望为 Pass。
4. **问题-4（§4.19）**：`toZonedIso()` 使用 `ZoneId.systemDefault()` 导致跨时区时间格式不一致 — 建议统一为 UTC。
5. **OTLP Relay 限制**：当前 `otlp_relay.py` 使用手写 protobuf wire-format 解析器，仅支持 OTLP gRPC 的 span 数据解析，不支持 metrics/logs；如需完整 OTLP Collector 能力，建议替换为 OpenTelemetry Collector 官方实现。
6. **采样率越界行为未覆盖**：当前 §4.9 仅验证采样率 0.0 和 1.0 边界值，未覆盖越界值（>1.0 或 <0.0）的处理行为。OTel SDK 对越界采样率的 clamp 或抛异常行为未在需求文档中明确，建议需求方补充定义并增加测试覆盖。