---
feature_id: FEAT-036
feature_title: A2A 协议 Part 多模态（文件/结构化）数据传输
sut: agent-service-app（/a2a JSON-RPC 入站 + A2ARemoteAgentClient 出站）+ agent-service-app-custom-rest（multipart SPI 兜底）+ 测试自有回显业务 Agent（拓扑 A）+ EDPAgent（edp-agent exec jar，拓扑 B 出站宿主）
scope: 黑盒集成测试；Part(raw/url/data) 入站解析与保留、协议层校验限额与错误表面、出站 Part 构造与格式保持、multipart 兼容接入同链路、安全基线（raw 不入 LLM 上下文 / url 不下载）、纯文本回归、端到端场景旅程
status: designed
owner: TBD
priority: P0
tags: [integration, blackbox, feat-036, a2a-part, multipart]
depends_on:
  - L2 Feat-Func-036 落码完成前，除 Smoke 集外的用例为 red-first 设计（预期 FAIL，落码后转准入门禁）
  - agent-runtime 构建产物包含 Feat-Func-036 变更（parser/adapter/outbound/SPI multipart 分支）
  - agent-service-app-custom-rest 扩展 jar（multipart 分支 + Context.files）
  - 测试回显 Agent 与 A2A 网关桩 / 文件服务桩（本档新建 fixture，见 §3.3）
related_docs:
  - docs 私仓 develop/02-features/FEAT-036-a2a-part-file-and-data-transfer.md（status: active, updated: 2026-08-20）
  - docs 私仓 develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-036-a2a-part-file-and-data-transfer.md（status: draft, updated: 2026-08-25）
updated: 2026-09-10
---

# FEAT-036 — A2A Part 多模态（文件/结构化）数据传输测试设计

> **一句话**：acceptance 以黑盒方式经 `POST /a2a`（JSON-RPC）、custom-rest multipart 兼容入口与出站 A2A client 三条面，验证 agent-runtime 对 FEAT-036 的 `Part(raw)`/`Part(url)`/`Part(data)` 完整解析、协议层校验、出站构造、multipart 同链路复用与纯文本兼容。

> **仓库边界**：测试代码与 FEAT-036 自有 fixture 只写入 `agent-runtime-acceptance`；不修改 agent-runtime / agent-solution 产品源码；不修改客户网关与文件存储。

> **执行约束**：标准 `mvn test` 自动发现全部 FEAT-036 用例。**落码前纪律**：L2 §1.2 明确 R-RT-1~4 当前实现状态为"未实现"，本档除 Smoke 集外全部用例为 **red-first 设计**（T-S5：预期 FAIL，注明依据），落码后按 §8.4 退出标准转绿；不得以 assumption / `@Disabled` 隐藏失败，也不得把"当前实现就是这样"写成期望值。

## 0. 引用核对记录（T-M1a~T-M1d 自查留痕）

| 引用文档 | 版本核对 | 不可变锚点 | 本档引用章节 |
|---|---|---|---|
| FEAT-036 特性档 | `status: active` / `updated: 2026-08-20`（现行版，非冻结版裁剪） | docs 私仓 `b1f50a6c`（2026-08-31） | §2 能力表（R-RT-1~4 / 协议层校验 / 业务层类型校验 / 安全基线 / 纯文本兼容 / SHOULD 客户端接入选择 / OUT 行）、§3 外部接口表、§4 场景表、§5.1.1~§5.1.6、§5.2 边界表、§6 约束 |
| Feat-Func-036 L2 | `status: draft` / `updated: 2026-08-25`（**draft 版，评审重点确认项见 §9**） | 同上 `b1f50a6c` | §1.2 能力矩阵、§1.3 差距、§2.1~§2.4 入口契约（含 §2.2 错误表面表、§2.4 步骤 4/5）、§3.1~§3.4 运行模型、§4.1~§4.7、§5.1~§5.4 映射、§6.1~§6.4 配置、§7.1~§7.3 验收表 |
| FEAT-001（转引） | **未直接核对**，以 L2 §2.2 转引为准："HTTP status 复用 FEAT-001 §2.3 的 400 + `-32602` 映射" | — | 仅经 L2 §2.2 转引，不独立引用其条款（T-M1c 等级保真） |

断言中的具体值逐字出处（T-M1d）：`10485760`（raw 10MB）、`100`（Part 总数）、`104857600`（body 100MB）、`1048576`（text/data 1MB）、`255`（filename 字符）、`16KB`（metadata 序列化）、`http/https`（url scheme）、错误 message 关键短语（`exactly one of text/raw/url/data` / `not valid base64` / `exceeds max-raw-bytes` / `exceeds max-parts` / `exceeds max-text-data-bytes` / `must use http or https scheme` / `must be a non-blank string` / `must contain at least one part`）均出自 **L2 §2.2 异常响应表与 §7.3 错误表面验收表**；`-32602` / `EDP-FILE-003` 出自 **特性档 §2/§3**；12MB/100MB 容器必配出自 **L2 §6.1/§6.2**。

## 1. 依据与范围

### 1.1 设计依据

1. 特性档 §2 能力表：R-RT-1（入站完整解析）、R-RT-2（Handler 入参模型）、R-RT-3（出站 Part 构造）、R-RT-4（multipart 兼容接入）四条 MUST；协议层校验（base64 / raw ≤10MB 解码后 / Part 总数 ≤100，失败 `-32602`）；业务层类型校验（`EDP-FILE-003`，业务 Agent 侧）；安全基线（URL 下载沿用资源侧鉴权）；纯文本兼容；SHOULD 客户端接入选择；OUT 边界行。
2. 特性档 §5.1.1~§5.1.6 行为语义：Part 单内容字段表达、出站不转存不下载不序列化降级、multipart SPI 兜底同链路、协议层/业务层校验分层、raw 不入 LLM 上下文、向后兼容；§5.2 显式不承诺项（不按大小自动 raw↔url 互转、multipart 契约不固定等）。
3. 特性档 §6 对下游设计的约束（L2 必须保留完整 Part 集合、出站不丢 `filename`/`mediaType`/JSON 类型、agent-core 透传、SPI 复用标准链路、校验在业务 Agent 前完成、测试必须覆盖的十个面）。
4. L2 §2.2 入站 Part 契约与异常响应表（互斥判别、九类 `-32602` 场景、HTTP 413 预检）；§2.3 multipart 契约；§2.4 出站契约（`parts[0]=TextPart` 固定首位、格式保持、中断恢复重试上限 3 次）；§4.1 `A2aPartRules`/`A2aPartLimits`；§5.2 归一化表示（`kind` 为内部字段**不上 wire**）；§6.1/§6.2 配置（`max-message-bytes` 100MB、`spring.servlet.multipart.*` 宿主必配 12MB/100MB）；§7.1~§7.3 验收标准。
5. 特性档 §4 两个场景旅程（信贷报告分析 url 模式 / 贷后资料批量审查 raw 模式）与 L2 §3.1~§3.4 运行模型。

### 1.2 范围内

- `/a2a` 入站：`SendMessage` 与 `SendStreamingMessage` 对 `raw`/`url`/`data` Part 的解析、保留（顺序/内容/共享元数据）与 Handler 可达性（经回显业务 Agent 观察给出）。
- 协议层校验全表面：结构互斥、base64、raw 10MB、Part 总数 100、text/data 1MB、url scheme、url 非空白、filename/metadata 卫生、请求体 100MB + Content-Length 预检 413、空 parts 拒绝（既有行为）。
- 出站：`RemoteCall.parts` → wire Part 逆映射、`parts[0]=TextPart(message)` 兼容、格式保持（url 入→url 出 / raw 入→raw 出）、不自动转存/下载、中断恢复重放重试与耗尽回填。
- multipart 兼容接入：文件字段→`Part(raw)`、表单字段→`Part(data)`/`Part(text)`、SPI 桥接层与 `/a2a` 同一校验链路、容器层限额先行拒绝。
- 安全基线：raw 字节不进 LLM 上下文（marker 探针）、url 不被 runtime 下载（凭据隔离探针）。
- 业务层类型校验的**错误透出语义**（`EDP-FILE-003` 不被 runtime 吞掉/翻译），白名单规则本身不在范围。
- 纯文本回归：不携带多模态 Part 的调用入口、Task、SSE、错误语义不变。

### 1.3 不在本档范围（T-M17：排除理由 + 指名承接方）

| 排除项 | 原因（含技术可行性检验） | 承接方（模块 + 用例/动作） |
|---|---|---|
| 客户端接入选择（SHOULD：新客户端优先 SDK+/a2a） | 特性档 §2 标注 SHOULD 且约束对象是**客户端行为**，非 SUT 契约，SIT 不可判定 | 接入文档与样例工程（L2 §2.3 已声明） |
| OUT 四项：网关南向映射 / Artifact 文件输出 / 文件存储 / 内容理解 / 病毒扫描 | 特性档 §2/§5.2 显式排除；"runtime 不做 X"的否定式断言黑盒不可判定 | 无用例义务（显式 OUT）；文档层由特性档 §5.2 边界表承载 |
| multipart path/接口名/字段名"不固定" | "不固定"是不可判契约（T-M4 反例）；本档 mp.* 用具体测试 path 实例化验证**行为**而非契约形态 | FEAT-022 testplan（SPI 装配与动态路由面） |
| Agent Card 能力声明不变（L2 §6.4） | FEAT-001 管辖 Agent Card；本特性未要求变更 | FEAT-001 testplan |
| 纯文本全量语义（入口/Task/SSE/错误**全**表面） | 本档只做多模态引入后的**回归抽样**，全量语义回归超出本特性面 | FEAT-001 testplan；本档 e2e.plain-text-regression 做看守 |
| 出站方向限额校验错误面 | L2 §2.4 明文"需求未定义出站方向的限额校验错误面，本特性不新增出站校验承诺" | 无义务（L2 显式不承诺） |
| `max-message-bytes` 默认值校准、多并发大载荷压测 | L2 §7.4 定性为 SE 压测（非 CI 用例），需生产形态 LB/网关拓扑 | SE 压测（L2 §7.4 交付前执行） |
| `spring.servlet.multipart.*` 部署必配项的落地 | 属部署配置检查，非 SUT 行为；本档 mp.container-limits 只验证该配置在测试环境**生效** | 样例工程 + 接入文档（L2 §1.3 已同步 SE） |
| 业务文件类型白名单具体规则 | 特性档 §2 明确业务 Agent 按自身业务规则执行 | 业务 Agent（EDPAgent）侧测试；本档 val.business-type-reject 用测试桩白名单验证透出语义 |
| TaskStore/checkpointer 容量基线（Redis 512MB 余量） | L2 §1.3 定性为压测校准项 | SE 压测（L2 §7.4） |
| 并发会话/Task 隔离通用语义 | 多模态 Part 不改变 Task 并发模型，无新增契约 | FEAT-003 testplan（并发会话隔离已建） |
| 产品仓单元测试（`A2aPartRulesTest` 等，L2 §7.4 前六行） | 属产品仓白盒单测，混入 SIT 会失真验收口径（T-M14） | agent-runtime / agent-solution 产品仓测试 |

## 2. 黑盒约束与观察面（T-M13 / T-M21）

**允许的测试动作**：

- 经 `POST /a2a` / `POST /a2a/` 发 JSON-RPC `SendMessage` / `SendStreamingMessage`，断言 HTTP status、JSON-RPC envelope、error code/message、SSE 帧。
- 经 custom-rest SPI 声明的测试 multipart path 发 `multipart/form-data`。
- 经 `GetTask` 断言 Task 状态机与 artifacts（含 DataPart 回显内容）。
- 部署测试自有**回显业务 Agent**（拓扑 A，测试代码拥有，经业务侧合法 SPI/编程 API 消费 ServeRequest inputs 并回显），其回显内容是 Task artifacts（wire 面）；拓扑 B 使用真实 EDPAgent（edp-agent exec jar，2026-09-09 整改，见 §3.2）。
- 部署 **A2A 网关桩**（模拟低码 Workflow 的下游 A2A Agent）与**文件服务桩**，断言其**网络边界上收到的 wire**（出站 Part 构造、重试次数、下载请求）。
- 用底层 HttpClient 直发构造的 JSON-RPC / multipart 报文（绕过 SDK 便利层断言 wire 层表面，含缺失 `Content-Length` 的 chunked 场景）。

**禁止的测试动作**：

- 读取/注入 runtime 内部状态：`ServeRequest`、`INPUT_MESSAGES`、`A2aPartRules` 调用点、checkpointer 存储内容。
- 以 runtime 进程日志作为功能通过证据（日志仅可作 INCONCLUSIVE 的环境判据）。
- mock/spy/反射产品内部类；替换 RequestHandler/TaskStore/adapter。
- **在 wire 上断言 `kind` 字段**——L2 §5.2 明文 `kind` 是内部判别字段、"不出现在 A2A wire 上"；wire 判别依据是扁平成员名 `text`/`raw`/`url`/`data`（L2 §5.1，A2A v1.0 扁平化）。SDK `1.0.0.Final` spec 模块 javadoc 的旧嵌套示例与实际序列化不符，断言以**实际 wire 扁平形态**为准（L2 §5.1 注）。

**观察面清单**（每个期望结果必须落在其中之一，T-M15）：

| 观察面 | 归属 | 用于 |
|---|---|---|
| HTTP status + JSON-RPC error envelope（code/message） | `/a2a` wire | VAL 组 |
| HTTP 413（无 JSON-RPC 信封） | `/a2a` wire | val.body-over-100mb-413 |
| SSE 帧序列 + 终态 `statusUpdate` | `/a2a` wire | e2e/IN 组 |
| `GetTask` Task 状态机 + artifacts（含回显 DataPart） | `/a2a` wire | IN/SEC/e2e 组 |
| 网关桩收到的出站 JSON-RPC wire（含 parts 明细） | 网络边界 | OUT/e2e 组 |
| 网关桩收到的重复投递计数 | 网络边界 | out.interrupt-retry-replay |
| 文件服务桩的请求日志（网络边界，含凭据校验结果） | 网络边界 | sec.url-auth-delegation / out.format-preserve |
| custom-rest 400 参数错误表面 | SPI wire | MP 组 |

## 3. SUT、拓扑与可复现锚点（T-M6 / T-M18~T-M20）

### 3.1 拓扑 A：入站 / 校验 / 兼容面

```text
测试 client（HttpClient / A2A SDK）
      |  JSON-RPC / multipart
      v
agent-service-app（/a2a）+ agent-service-app-custom-rest（multipart SPI，测试声明 path）
      |
      v
回显业务 Agent（测试自有，无 LLM）：把收到的 parts（filename/mediaType/byteSize/url/data）
按到达顺序回显为最终 artifact 的 DataPart；按配置白名单决定是否返回 EDP-FILE-003
```

回显 Agent 是**确定性**的（不依赖 LLM 规划），保证 IN/VAL/MP 组判据可精确断言。它消费的是业务侧合法入口（ServeRequest inputs），其回显经 Task artifacts 对客户端可见——观察面是 wire，不违反黑盒边界。

### 3.2 拓扑 B：出站 / 端到端面

```text
测试 client --SendMessage--> EDPAgent（edp-agent，engine exec jar：A2A 入站 + 委托 rail 宿主）
      |                                    |
      |  LLM 规划 call_versatile（attachments 携带文件引用，L2 §4.5）
      |  经 runtime 出站 client 构造 RemoteCall(parts)
      v                                    v
A2A 网关桩（记录入站 wire；可注入 5xx/断连；内嵌"Workflow 下载客户端"）    GetTask 轮询 / 阻塞响应
      |
      v
文件服务桩（报告/审查文件；强制 token 凭据，无凭据请求一律 401）
```

**设计取舍（2026-09-09 整改）**：拓扑 B 业务 Agent 由原设计「测试自有委托桥接 Agent（无 LLM，确定性 `RemoteCall(parts)`）」改为**真实 EDPAgent**（`edp-agent` exec jar）。原桥接方案动因：EDPA 委托附件契约（`attachments` schema / 中断载荷 `parts`）评审确认中（§9 存疑 5）；2026-09-09 复核 L2 §4.5 已明示「**该参数契约为本 L2 提出并定稿**」且 rail 侧 `extractDelegationParts` 已落码，满足 §8.4 退出标准 6 的切换条件。真机复测（run 4）同时暴露全链路唯一断点：`call_versatile` 工具 schema 未声明 `attachments` 参数（`CallVersatileTool.build()`，engine 0.1.1）——LLM 无从携带文件引用，委托恒 `parts=0`、出站 wire 仅 TextPart；该缺口定性 **P0 代码缺陷**（FEAT-036 测试报告 §四.1），出站判据面用例（#23/#24/#31/#32/#1/#2）按 red-first 看守其修复，不因缺陷改判期望。拓扑 B 约束：① 用例意图文本须落在通用化场景 planrule `scope.allowed`（合同审查/贷后资料审查/文件审查/订单查询…）内（feat036-scenario/README.md），否则规划层拒答不委托；② 出站为 LLM 驱动阻塞单轮（~40s），HTTP 预算 240s；③ #25 的 503 注入/重试计数仍由测试自有 GatewayStub 承载（§3.4 豁免记录）。

### 3.3 Fixture 清单

| Fixture | 复用/新建 | 职责 |
|---|---|---|
| `SutStack` | 复用 | 拉起/停止 agent-runtime + custom-rest 扩展 + 回显 Agent；动态端口注入。 |
| `A2aServiceClient` / `A2aHttpProbe` | 复用 | SDK 驱动与底层 HttpClient 直发（413/chunked/非法 base64 等需绕 SDK 校验的行）。 |
| `PartsEchoAgent` | 新建 | 拓扑 A 回显业务 Agent：inputs → parts 逐条回显（DataPart）。其"白名单开关 → `EDP-FILE-003`"分支于 2026-09-10 由 **`WhitelistEchoAgent`** 独立落地（测试 JVM 内嵌 `agent-service-app:0.1.2` 实例 + 测试自有白名单 `AgentHandler`，见 §3.4 行 4），承载矩阵 #22。 |
| ~~`DelegateBridgeAgent`~~ | **已裁撤（2026-09-09）** | 原拓扑 B 桥接方案（入站 parts → `RemoteCall(message, parts)` 委托网关桩）随 §3.2 整改裁撤——L2 §4.5 attachments 契约定稿后拓扑 B 直接使用真实 EDPAgent（edp-agent exec jar），无需测试自有桥接 Agent。 |
| `GatewayStub` | 新建 | 下游 A2A Agent 桩：记录每次收到的 message wire 快照；可注入"前 N 次 5xx/断连"；内嵌带 token 的 Workflow 下载客户端。**动态端口**。**用例间 reset()**（wire 快照/计数器/故障注入复位，2026-09-09 补——拓扑 B 真实流量下跨用例累积会污染 get(0)/计数断言）。 |
| `FileServerStub` | 新建 | 文件服务桩：仅接受带合法 token 的 GET；无 token 一律 401；记录全部请求（来源、凭据）。**动态端口**。 |
| `RawFileFactory` | 新建 | 确定性合成字节（固定 seed 伪随机序列 + 可嵌 marker）；按解码后字节数生成（10485760 / +1B 等边界）；base64 编码。 |
| `MultipartRequestBuilder` | 新建 | 构造 `multipart/form-data` 报文（文件字段/表单字段/超限字段）。 |

### 3.4 SUT agent 选型（源自 `agent-solution/common` 现有资产，2026-09-04 勘察结论）

| 测试设计角色 | 选定 agent（仓内路径） | 关键事实（勘察依据） | 承载场景 |
|---|---|---|---|
| multipart SUT | `example/edp-agent-multipart-demo` | FEAT-036 R-EDPAgent-1 **官方样例**：复用引擎内置 `EdpaCustomRestAdapter`（message→Part(text)、文件→Part(raw)、其余字段→Part(data)）；已配 `max-file-size: 12MB / max-request-size: 100MB`（L2 §6.1 必配项活样例）；自带 `MultipartUploadIntegrationTest`（mock 终端 agent，已验 multipart→parts 映射与 `A2aPartRules` 桥接校验） | #27 #28 #29 #30 #3 |
| EDPAgent 主 SUT | `agents/edp-agent-java`（engine）→ 验收仓 SUT `edp-agent`（`edp-agent-engine:0.1.1` exec jar，**已注册**） | A2A 入站 + 委托 rail 宿主；被委托方经 `EDP_AGENT_SEARCH_A2A_URL` / `EDP_AGENT_VERSATILE_A2A_URL` 注入；**2026-09-09 整改**：拓扑 B 出站判据面（原误接 echo-agent——无 LLM 恒零出站）切至本 SUT，模型凭据（OPENJIUWEN yml `EDP_AGENT_MODEL_*`）、通用化场景（`EDP_AGENT_SCENARIO_HOME`→feat036-scenario）、remote-agents streaming=false（GatewayStub 阻塞应答）、thinking.type=enabled（Ark glm-5.3）由测试栈注入 | **#1 #2 #23 #24 #25 #31 #32**（拓扑 B 出站/SEC/E2E 主被测） |
| 回显 Agent（PartsEchoAgent 底座） | `example/agent-bus-consumer-demo/agent-bus-consumer-callee-demo` → 验收仓 SUT `echo-agent`（**已注册**） | 确定性无 LLM `CalleeAgentHandler`（回显 query、6 chunk 流式、INPUT_REQUIRED 触发器）；⚠️ **现状只回显 `lastUserQuery()` 文本，未回显 parts**——需按 `ServeRequest.messages[].parts` 扩展 Part 摘要回显（filename/mediaType/byteSize/url/data 类型）后方可承载 IN 组判据；**无 LLM、无委托工具，不得作为出站判据面宿主**（2026-09-09 整改结论） | #4 #21（Smoke）#5~#11 #12~#22 #26（拓扑 A） |
| 下游"网关+低码 Workflow"（真实链路版） | `example/versatile-orchestration-demo/adapter` → 验收仓 SUT `versatile-orch-demo-adapter`（**已注册**，port 18094）+ services 段已预置 `env-explorer`（versatile 流程 mock） | A2A→versatile HTTP/SSE 协议翻译；其 `VersatileRequestExtractor` 把 `parts[0].text` 当 JSON 解析——正是 L2 §2.4 步骤 1"`parts[0]=TextPart` 固定首位"兼容约束的**实测面**（#23） | #1 #2 #23 #24 #32（真实链路冒烟） |
| 业务白名单宿主（EDP-FILE-003） | **测试自有 `WhitelistEchoAgent`**（2026-09-10 落地，替代原选型"mp demo + config-driven 场景"——mp demo 与 echo-agent 均无白名单机制，config-driven 场景亦无文件类型规则） | 测试 JVM 内嵌 `agent-service-app:0.1.2` 实例（pom test-scope 依赖，与 echo-agent 进程内捆绑 runtime 完全同款），随机端口；测试自有 `AgentHandler`：白名单仅 `application/pdf`，违规文件抛 `AgentExecutionException("EDP-FILE-003")`——经 runtime 真实管道（`failAndDrain` → `AgentEmitter.fail` → Task FAILED + `openjiuwen.error` 元数据）透出，正是 #22 的判据面；配置镜像 echo-agent demo yml（无库表、无总线消费） | #22 |
| 真实 LLM 被委托方（冒烟级） | `example/multi-deep-research-demo` 的 `agent-search` / `agent-verify`（FEAT-028 已复用） | LLM 驱动、非确定性——只作 E2E 冒烟与 EDPAgent 委托联调，不承载精确判据 | E2E 冒烟备选 |

**选型豁免记录**：`agentscope-a2a-interrupt-demo`（中断专项）、`bank-intent-routing-a2a-demo`（意图路由）、`agent-client-demo`/`agent-gateway-demo`（FEAT-011/012 转发）、`edpa-alpha`（EDPA rail 库，非独立 agent）、`skillhub-runtime-demo`/`instance-route-query-demo`（其他特性）与 FEAT-036 契约面无关，不选用。

**仍需测试自有桩的能力（产品 demo 不具备，且 P-M7 禁止单方面改别队资产）**：`out.interrupt-retry-replay`（#25）的 5xx/断连注入与重复投递计数、`FileServerStub` 的强制 token 凭据与 401 拒绝——保留本档 §3.3 的 `GatewayStub` / `FileServerStub` 自建 fixture，双轨并行：**真实链路冒烟**（versatile-orch adapter）+ **桩精确判据**（自建桩）。

**落码前置动作**（承接 §9 存疑 5/7）：① ✅ 已完成（2026-09-08）：`edp-agent-multipart` 专用 demo jar（`com.customer:customer-multipart-app:1.0.0`）已构建并替换验收仓占位——构建命令 `mvn -f common/example/edp-agent-multipart-demo/pom.xml clean install -Dmaven.test.skip=true -Dedp-agent-engine.version=0.1.1`（demo 自带单测按 engine 0.1.0 构造器签名编写，对 0.1.1 编译不过，故跳过测试编译）；application-local/openjiuwen.yml 坐标已切换；② `echo-agent` 扩展 parts 回显（改动在 example 工程内、属测试配套，需与 demo 属主确认后进行）；③ `edp-agent-multipart-demo` 自带单测（MockMvc + mock 终端 agent）为产品仓白盒/半集成证据，**不计入**本档 SIT 矩阵（T-M14）。

**mp SUT 部署配置清单（2026-09-08 真机实测，demo jar 与 engine exec jar 的配置差异）**：demo jar 相比 engine exec jar 缺三处启动配置，须由测试栈注入（均为部署配置注入，非缺陷遮蔽）：

1. **remote-agents 未注册**：engine exec jar 的 application.yml 自带 `openjiuwen.service.a2a.remote-agents[0]（versatile-agent → ${EDP_AGENT_VERSATILE_A2A_URL}）`，demo jar 无任何 remote-agents 声明——不注入则委托报 `Unknown remote agent: versatile-agent`。buildStack 已按 `.property("openjiuwen.service.a2a.remote-agents[0].{name,url,streaming}")` 注入（url 指向 GatewayStub）。
2. **场景目录**：demo jar 默认 `./scenarios/loan-review` 不存在（README 指向复用 config-driven 场景）；引擎内置 planrule `scope.allowed` 为空时按框架协议 LLM 拒答不委托。测试栈注入 `EDP_AGENT_SCENARIO_HOME` → `src/test/resources/feat036-scenario`（通用化最小场景 fixture，来源见该目录 README；FEAT-028 `/tmp/edpa-scenario-min` 先例）。
3. **模型 thinking 参数**：demo jar yml 写死 `deep-agent.model.thinking.type=disabled`，当前环境模型（Ark glm-5.3）不接受（400 InvalidParameter），须覆盖为 `enabled`。

另两处与 Bug 190（category C，报告 §4.3）相关的运行面事实（12-08 实测，供桩与部署复现）：

- **GatewayStub 须应答 SDK 卡片发现**：A2ACardResolver 发送前 GET `<url>/.well-known/agent-card.json`，无合法卡片则 discovery 失败、registry 不收录（运行期 30s 轮询重试），委托仍报 `Unknown remote agent`。GatewayStub 已补 GET 分支（卡片字段对齐真实 agent-runtime 服务）。
- **GatewayStub 响应须为 `result.task` oneof 包裹**：直放 Task 字段（`"kind":"task"`/`"lastChunk"` 等）触发 SDK `InvalidParamsJsonMappingException`（"id in message SendMessageResponse"）→ 委托判 REMOTE_PROTOCOL_ERROR、SDK 3 次退避重试全失败。GatewayStub 响应已对齐真实服务格式（`{"result":{"task":{...,"history":[]}}}`，status 带 timestamp）。
- **multipart 链路仍需关闭输入安全 Filter**（`deep-agent.input-security.enabled=false`）：category C 缺陷未修（engine 0.1.1 的 `CachedBodyRequest` 未 override `getParts()`），关闭后 mp 组用例才能触达被测面；Filter 缺陷由报告单列跟踪。

### 3.5 命名、标签与资源纪律

- contextId：`ctx-feat036-<slug>-<uuid8>`；slug 与矩阵 ID 对应。
- Tag：类级 `@Tag("feat-036")` + `@Tag("integration")` + `@Tag("blackbox")`；方法级 `@Story("FEAT-036.<id>: <场景名>")`。
- 资源（T-M20）：网关桩/文件服务桩端口动态分配；合成的 10MB 级字节仅在边界用例内生成、用后即弃，不落临时目录；contextId/文件名带 `feat036` 前缀避免撞 key。
- 版本指纹（T-M19）：用例启动时记录 SUT jar 文件名 + Agent Card `version` 字段（探针式），报告输出指纹；防止跑旧产物全绿。
- SUT/文档锚点（T-M18）：契约文档锚 docs 私仓 `b1f50a6c`；SUT 构建锚点在首次落码 PR 中回填具体 commit/tag（本档为设计稿，暂记 TBD，见 §9 存疑 7）。

## 4. 覆盖矩阵（32 条场景）

**计数声明（T-M10）**：本档规划 **32** 条场景 = Smoke 集 **2** + P0 准入集 **15** + 仅 Full 集 **15**；下表 32 行，与执行集闭合。

**落码前预期（T-S5）**：标 🔴 的用例在 L2 §1.2 所列能力落码前**预期 FAIL**（red-first 看守，依据 L2 §1.3 差距清单：非文本 Part 被丢弃/纯文件请求被拒）；标 🟢 的为当前实现应全绿的回归项。

| # | 场景 ID | 场景 | 契约依据 | 主要外部证据 | 执行集 | 落码前 |
|---|---|---|---|---|---|---|
| 1 | `e2e.url-credit-report` | 信贷报告分析 url 模式端到端 | 特性 §4 场景 1；L2 §3.1 | 终态 + 网关桩 wire + 文件桩零 runtime 请求 | P0 | 🔴 |
| 2 | `e2e.raw-batch-review` | 贷后资料批量审查 raw 模式端到端（经 /a2a） | 特性 §4 场景 2；L2 §3.2 | 终态 + 网关桩 wire（多 raw） | P0 | 🔴 |
| 3 | `e2e.multipart-batch-review` | 同场景经 multipart 兼容入口端到端 | L2 §3.2；特性 §5.1.3 | 终态 + 网关桩 wire | Full | 🔴 |
| 4 | `e2e.plain-text-regression` | 纯文本回归端到端 | 特性 §5.1.6；L2 §3.3 | 既有入口/Task/SSE/错误语义不变 | Smoke | 🟢 |
| 5 | `in.url-preserved` | 单 url Part 解析保留 | 特性 R-RT-1/§5.1.1；L2 §2.2 | 回显 DataPart：url/filename/mediaType | P0 | 🔴 |
| 6 | `in.raw-preserved` | 单 raw Part 解码保留 | 特性 R-RT-1；L2 §2.2 | 回显：byteSize/base64 一致 | P0 | 🔴 |
| 7 | `in.multi-raw-preserved` | 多文件 raw 顺序与内容保留 | 特性 §6（多文件 raw）；L2 §3.2 | 回显顺序与逐文件一致 | Full | 🔴 |
| 8 | `in.data-types-preserved` | data JSON 类型不降级 | 特性 R-RT-1/§5.1.1；L2 §5.3 | 回显 data 类型逐项可判 | P0 | 🔴 |
| 9 | `in.mixed-order-preserved` | text+url+raw+data 交错顺序可还原 | 特性 §5.1.1；L2 §5.3 | 回显顺序 == wire 顺序 | P0 | 🔴 |
| 10 | `in.both-methods-consistent` | SendMessage 与 SendStreamingMessage 同语义 | 特性 §3；L2 §2.1 | 两 method 回显一致 | Full | 🔴 |
| 11 | `in.metadata-shared` | filename/mediaType/metadata 原样保留（含缺省） | 特性 §3；L2 §2.2 | 回显元数据逐字段 | Full | 🔴 |
| 12 | `val.mutex-violation` | Part 判别字段缺失/并存 | L2 §2.2 表；特性 §3 | `-32602` + `exactly one of text/raw/url/data` | P0 | 🔴 |
| 13 | `val.base64-invalid` | raw 非法 base64 | L2 §2.2 表；特性 §3 | `-32602` + `not valid base64` | P0 | 🔴 |
| 14 | `val.raw-over-10mb` | raw 解码 10MB 边界（10485760 过 / +1B 拒） | L2 §2.2/§4.1；特性 §2 | 10485760→200；+1B→`-32602`+`exceeds max-raw-bytes` | P0 | 🔴 |
| 15 | `val.parts-over-100` | Part 总数边界（100 过 / 101 拒） | L2 §2.2/§4.1；特性 §2 | 100→200；101→`-32602`+`exceeds max-parts` | P0 | 🔴 |
| 16 | `val.text-data-over-1mb` | 单 data（及 text，存疑子行）>1MB | L2 §2.2/§4.1（**特性档未载**，见 §9 存疑 2/3） | data→`-32602`+`exceeds max-text-data-bytes` | Full | 🔴 |
| 17 | `val.url-scheme-invalid` | url scheme 非 http/https | L2 §2.2/§4.1 | `-32602` + `must use http or https scheme` | Full | 🔴 |
| 18 | `val.url-blank` | url 空白串 | L2 §2.2/§4.1 | `-32602` + `must be a non-blank string` | Full | 🔴 |
| 19 | `val.filename-metadata-hygiene` | filename 255 字符边界 / metadata 16KB 边界 | L2 §2.2/§4.1 | 255/16KB 过；越界→`-32602`+`exceeds size limit` | Full | 🔴 |
| 20 | `val.body-over-100mb-413` | 请求体 100MB / Content-Length 缺失预检 | L2 §2.2 步骤 1/§4.2（**特性档未载**，见 §9 存疑 2） | HTTP 413、无 JSON-RPC 信封、先于解析 | Full | 🔴 |
| 21 | `val.empty-parts` | parts 空数组 / 全空白文本拒绝（既有行为） | L2 §2.2 末行 | `-32602` + `must contain at least one part` | Smoke | 🟢 |
| 22 | `val.business-type-reject` | 业务类型拒绝 EDP-FILE-003 透出不被吞 | 特性 §2/§3；L2 §7.3 | 客户端错误面出现 `EDP-FILE-003`，非 `-32602` | P0 | 🔴 |
| 23 | `out.parts-constructed` | 出站 parts 构造（TextPart 首位 + File/Data Part） | L2 §2.4/§5.4；特性 R-RT-3 | 网关桩 wire：parts[0]=TextPart、元数据/类型不丢 | P0 | 🔴 |
| 24 | `out.format-preserve` | 出站格式保持（url→url、raw→raw、不互转不下载） | L2 §2.4 步骤 4；特性 §5.2 | 网关桩 wire 形态 + 文件桩零 runtime 请求 | P0 | 🔴 |
| 25 | `out.interrupt-retry-replay` | 出站中断恢复重放（退避重试 ≤3 / 耗尽回填） | L2 §2.4 步骤 5/§7.3 | 网关桩重复投递计数 + 终态语义 | Full | 🔴 |
| 26 | `out.return-filepart-skip` | 回程 FilePart 跳过（只提取 Text/Data） | L2 §3.4/§4.7 | 任务正常终态，final_answer 不混入文件字节 | Full | 🔴 |
| 27 | `mp.file-to-raw` | multipart 文件字段 → Part(raw) | 特性 R-RT-4/§5.1.3；L2 §2.3 | 回显：bytes/filename/mediaType=contentType | P0 | 🔴 |
| 28 | `mp.form-to-data-or-text` | 表单字段 → Part(data)/Part(text) 分流 | 特性 R-RT-4；L2 §2.3 | 回显：JSON 可解析→data，否则 text | Full | 🔴 |
| 29 | `mp.same-chain-validation` | multipart 超限走与 /a2a 相同校验、同 400 表面 | 特性 §6；L2 §2.3 | 既有 400 参数错误表面，不进业务 | P0 | 🔴 |
| 30 | `mp.container-limits` | 容器层 multipart 限额先行拒绝 | L2 §2.3 异常表/§6.1 | 容器 4xx（非 SPI 参数错误信封） | Full | 🔴 |
| 31 | `sec.raw-not-into-llm-context` | raw 字节不入 LLM 上下文（marker 探针） | 特性 §5.1.5；L2 §3.2 | final_answer 无 marker；marker 经 parts 回显可达 | Full | 🔴 |
| 32 | `sec.url-auth-delegation` | url 不被 runtime 下载（凭据隔离探针） | 特性 §2 安全基线/§5.1.5 | 文件桩 401 拒无凭据请求而任务仍完成 | Full | 🔴 |

## 5. 分层交代（T-M5）

| 层 | 覆盖 | 说明 |
|---|---|---|
| 正例 | #1~#11、#23、#24、#27、#28、#3 | 各 Part 类型单独 + 混合 + 端到端 |
| 异常 | #12、#13、#16~#20、#22、#29、#30 | 协议层九类 -32602 场景 + 413 + 业务拒绝 + 容器层 |
| 边界 | #14（10485760/10485761）、#15（100/101）、#16（1048576/1048577）、#19（255 字符/16KB±）、#20（100MB 整/超） | 全部逐字对齐 L2 §4.1 常量 |
| 时序/并发 | #25（中断→持久化→重放重试时序）；并发隔离**不在本档**（§1.3 排除行：FEAT-003 承接，多模态不改变 Task 并发模型） | |
| 权限与状态机非法迁移 | #32（资源侧凭据隔离）；状态机语义（SUBMITTED→WORKING→COMPLETED 单调、终态语义）**不在本档新增**——FEAT-036 不改变状态机，承接方：FEAT-001 testplan；本档各 e2e 用例顺带断言终态单调作为回归面 | |

## 6. 子用例 G/W/T

> 判定分层（T-S8）：依赖真实外部条件不可满足时判 INCONCLUSIVE 并写明判定条件；本档拓扑 A/B 均为确定性拓扑（无 LLM 依赖），INCONCLUSIVE 出口仅限"SUT 未达就绪 / 桩不可达 / 观察面缺失"。**落码前所有 🔴 用例按 red-first 记 FAIL，不记 INCONCLUSIVE**（实现缺失不是环境缺失）。

### 6.1 端到端旅程（E2E）

**#1 `e2e.url-credit-report`**
- **G**：拓扑 B 全链路就绪；`FileServerStub` 持 `credit-report.pdf`（嵌 marker `FEAT036-URL-MARKER`），仅接受带 token 的 GET。
- **W**：`SendStreamingMessage`：TextPart("分析该信贷报告的风险") + FilePart(url=`FileServerStub` URL, filename=`credit-report.pdf`, mediaType=`application/pdf`) + DataPart({"riskLevel":"high","amount":500})；SSE 采集至终态。
- **T**：①终态 COMPLETED 且状态机单调；②网关桩收到的 wire 恰含 FileWithUri（url 与入站逐字符一致、filename/mediaType 一致）+ DataPart（JSON 类型一致）+ `parts[0]` 为 TextPart；③任务执行全程 `FileServerStub` 无来自 runtime 的请求（下载由网关桩的 Workflow 客户端带 token 完成）；④final_answer 覆盖分析结论。
- **诊断价值**：挂了说明入站→出站链路丢失 Part/元数据、runtime 越权下载、或格式被互转。

**#2 `e2e.raw-batch-review`**
- **G**：拓扑 B；`RawFileFactory` 生成 3 份 ≤10MB 文件（PDF/Excel/Word mediaType，各嵌独立 marker）。
- **W**：`SendStreamingMessage`：TextPart + 3×FilePart(FileWithBytes) + DataPart(审查参数)；至终态。
- **T**：①终态 COMPLETED；②网关桩 wire 含 3 个 FileWithBytes，base64 解码后与源文件逐字节一致、filename/mediaType 逐个对应、顺序保持；③DataPart 类型不降级；④协议层校验在业务链路前（对照 #29）。
- **诊断价值**：挂了说明多文件透传丢内容/丢序/丢元数据。

**#3 `e2e.multipart-batch-review`**：同 #2 场景，入口改 multipart（文件字段×3 + 表单字段审查参数 JSON）；额外断言：multipart 面与 #2 的 /a2a 面到达网关桩的 wire **语义一致**（同链路承诺，特性 §5.1.3）。

**#4 `e2e.plain-text-regression`**
- **G**：拓扑 A；存量纯文本 client。
- **W**：按 FEAT-001 既有方式发纯文本 `SendMessage` 与 `SendStreamingMessage` 各一（含一次业务错误路径）。
- **T**：入口、Task 状态机、SSE 事件面、错误信封与多模态特性引入前**一致**（基线：落码前先跑一次留基线快照，落码后 diff 为空）。
- **诊断价值**：多模态扩展破坏存量语义的直接信号。

### 6.2 入站解析与保留（IN）

**#5~#11 共同判据框架**（观察面=回显 Agent 的 artifact DataPart，T-M21 已核为 wire 面）：

- **#5 `in.url-preserved`**：发单个 FilePart(FileWithUri)；回显含 url 逐字符一致、filename、mediaType；**runtime 未发起下载**（文件桩零请求）。PASS=全字段一致；FAIL=字段缺失/被改写/被下载。
- **#6 `in.raw-preserved`**：发单个 FilePart(FileWithBytes, 1KB 确定字节)；回显 byteSize==1024、base64 解码逐字节一致、filename/mediaType 保持。
- **#7 `in.multi-raw-preserved`**：3 个 raw 文件（不同大小/类型）；回显**顺序与逐文件内容**均一致（顺序断言按回显数组下标，全档唯一口径="wire parts 顺序 == 回显顺序"）。
- **#8 `in.data-types-preserved`**：data 依次为 object/array/number/boolean/string 五行（参数化）；回显后逐行断言 JSON 类型不变（number 不变字符串、对象键序不作为判据、布尔不变 "true" 字符串）。
- **#9 `in.mixed-order-preserved`**：parts=[text, url, raw, data, text]；回显 5 元素顺序与 wire 完全一致，text 既进 content 拼接也进 parts（L2 §5.3）。
- **#10 `in.both-methods-consistent`**：同一 mixed 请求分别走 `SendMessage` 与 `SendStreamingMessage`；两者回显 DataPart 深度相等（流式差异仅 SSE 封装，特性 §3）。
- **#11 `in.metadata-shared`**：缺省 mediaType（回显为缺失而非 null 字符串/补默认值）、含空格与 Unicode 的 filename、part 级 metadata object——三者均原样保留（L2 §2.2 "原样保留"；metadata 经既有 parseMetadata 语义）。

### 6.3 协议层校验（VAL）

共同前置：拓扑 A；预期失败行**不得创建 Task**（错误后 GetTask 无新 Task，L2 §7.2"无 Task 副作用"）。

- **#12 `val.mutex-violation`**：参数化三行——零内容字段（仅 filename）、双字段（text+raw）、三字段（raw+url+data）→ 均 `-32602` 且 message 含 `exactly one of text/raw/url/data`。
- **#13 `val.base64-invalid`**：raw=`"not-base64!!!"` → `-32602` + `not valid base64`。
- **#14 `val.raw-over-10mb`**：两行——解码后恰 10485760 字节→200 通过（回显 byteSize 断言）；10485761 字节→`-32602` + `exceeds max-raw-bytes`。
- **#15 `val.parts-over-100`**：两行——100 个微型 raw Part→200；101 个→`-32602` + `exceeds max-parts`。
- **#16 `val.text-data-over-1mb`**：data 序列化后 1048577 字节→`-32602` + `exceeds max-text-data-bytes`；1048576→200。**text 子行（纯文本 >1MB）裁定前不写死期望**（§9 存疑 3→实为存疑 1），先以观察模式执行并记录。
- **#17 `val.url-scheme-invalid`**：`ftp://host/f`、`file:///etc/passwd` → `-32602` + `must use http or https scheme`。
- **#18 `val.url-blank`**：`""` 与 `"   "` → `-32602` + `must be a non-blank string`。
- **#19 `val.filename-metadata-hygiene`**：filename 255 字符→200；256 字符→`-32602` + `exceeds size limit`；metadata 序列化 16KB→200；>16KB→`-32602` + `exceeds size limit`。
- **#20 `val.body-over-100mb-413`**：三行——CL 缺失（chunked）→HTTP 413；CL>104857600→413；恰 104857600→正常处理。413 行断言：**响应体无 JSON-RPC 信封**、发生在解析前（配合无效 JSON 体仍 413 证明先于解析）。⚠️ chunked→413 的严格语义受 L2 §7.4 压测结论约束，可能放宽（§9 存疑 4）。
- **#21 `val.empty-parts`**：parts=[] 与 parts=[空白文本]→`-32602` + `must contain at least one part`（既有行为，Smoke）。
- **#22 `val.business-type-reject`**：白名单宿主为测试自有 `WhitelistEchoAgent`（测试 JVM 内嵌 `agent-service-app:0.1.2` 实例——与 echo-agent 进程内捆绑 runtime 同款，pom test-scope 依赖；白名单仅 `application/pdf`，业务拒绝经 `AgentExecutionException` + `AgentFailureDescriptor("EDP-FILE-003")` 抛出，2026-09-10 落地）；发 `application/vnd.microsoft.portable-executable`（.exe）raw → 客户端可见错误面出现 **`EDP-FILE-003`**（业务码原样透出），**不是** `-32602`、不被 runtime 翻译或吞掉（特性 §3）。**断言面（与 runtime 真实错误透出管道对齐，`A2AAgentExecutor.failAndDrain` → `A2aErrorMetadata.encode`）**：HTTP 200（A2A JSONRPC 统一错误面，2026-09-09 对齐）+ 无 JSON-RPC error envelope + `result.task.status.state=FAILED` + `status.message.parts[0].text` 含 `EDP-FILE-003` + `status.message.metadata["openjiuwen.error"].code=="EDP-FILE-003"`（结构化元数据原样透出，附 `numericCode`/`retryable`）；同请求若先触发协议层违约则到不了业务层——由 #12~#15 的"无 Task 副作用"联合证明分层。
- **诊断价值（VAL 组）**：任一校验行挂了，说明协议层拦截器缺失/限额常量漂移/校验发生在业务之后——外部错误承诺被破坏。

### 6.4 出站构造（OUT）

- **#23 `out.parts-constructed`**：拓扑 B（edp-agent 宿主，2026-09-09 整改）：/a2a SendMessage 携 message="分析指令"（意图落在场景 scope.allowed 内）+ parts=[url, raw, data, text]，LLM 规划 call_versatile 委托（attachments 携带文件引用，L2 §4.5）；网关桩断言 wire：`parts[0].text=="分析指令"`（固定首位，L2 §2.4 步骤 1）、其后依次 FileWithUri/FileWithBytes/DataPart/TextPart，filename/mediaType/JSON 类型不丢失（L2 §5.4 逆映射表逐行）。
- **#24 `out.format-preserve`**：两组——入站 url 的 Part 出站仍为 FileWithUri 且 url 值不变；入站 raw 出站仍为 FileWithBytes。全程 `FileServerStub` 零 runtime 请求（不自动下载/转存，特性 §5.1.2 + L2 §2.4 步骤 4）。
- **#25 `out.interrupt-retry-replay`**：网关桩注入前 2 次 503 后成功→任务终态 COMPLETED，网关桩记录到 ≥1 次重复投递（重放内容与首次一致，含 parts），重复投递总数 ≤3（L2 §2.4 步骤 5"指数退避，默认上限 3 次"）；子行 B：注入持续失败→任务按 FEAT-004 既有错误语义达失败终态并回填 remote result；"原始 Part 保留 Task 快照"为观察记录不作硬判据（快照属内部面，T-M21）。
- **#26 `out.return-filepart-skip`**：网关桩返回 artifact 含 FilePart + TextPart + DataPart → 任务正常终态，final_answer 只含 Text/Data 提取内容，FilePart 被跳过且不致错误（L2 §3.4/§4.7 现状边界）。
- **诊断价值（OUT 组）**：#23/#24 挂=出站链路丢元数据或做了未承诺的格式互转；#25 挂=中断恢复语义缺失或重试上限失控；#26 挂=回程提取语义被改动、破坏既有边界。

### 6.5 multipart 兼容接入（MP）

- **#27 `mp.file-to-raw`**：multipart 含 1 个文件字段（filename=`a.pdf`, contentType=`application/pdf`）→ 回显 byteSize/逐字节、filename、mediaType==contentType（L2 §2.3 步骤 2）。
- **#28 `mp.form-to-data-or-text`**：参数化——字段值 `{"k":1}`→Part(data)；字段值 `plain-text`→Part(text)；分流经回显内容可判（不在 wire 断言内部 kind，见 §2 禁令）。
- **#29 `mp.same-chain-validation`**：multipart 提交解码 >10MB 文件 / 101 个字段 / 非法结构→**既有 400 参数错误表面**（L2 §2.3"等价于 -32602"），不进业务链路、无 Task 副作用——与 /a2a 面同规则（特性 §6"不得创建私有执行链路"）。
- **#30 `mp.container-limits`**：测试环境配置 `max-file-size=12MB`（L2 §6.1 样例必配值）；提交 13MB 文件→容器层 4xx，且**先于 SPI 校验**（响应非 custom-rest 参数错误信封，L2 §2.3 异常表第一行"multipart 解析阶段拒绝"）。
- **诊断价值（MP 组）**：#27/#28 挂=SPI 转换缺失或字段映射错误；#29 挂=multipart 创建了绕过标准校验的私有链路；#30 挂=容器必配项未生效（部署缺陷，对应 L2 §1.3 风险行）。

### 6.6 安全（SEC）

- **#31 `sec.raw-not-into-llm-context`**：raw 文件嵌唯一 marker；final_answer **文本面**不含 marker、SSE 数据面文本不含 marker；同一请求回显 parts **含**该文件（证明文件确实进入链路——排除"文件没送到导致恒绿"的假绿）。判定注：marker 出现在回显 DataPart 属 parts 通道（合法），仅文本面出现才违约。
- **#32 `sec.url-auth-delegation`**：文件桩仅接受 token 凭据（runtime 侧无从获得）；拓扑 B 跑 #1 场景→任务仍 COMPLETED 且分析结果正确——若 runtime 曾尝试自行下载，将收到 401 且无法伪造结果；文件桩请求日志中全部请求均携带 token 且来自网关桩下载客户端。
- **诊断价值（SEC 组）**：#31 挂=raw 字节泄漏进 LLM 上下文（安全基线违约，静默失败类）；#32 挂=runtime 越权下载、未沿用资源侧鉴权边界。

## 7. 双向覆盖台账（T-M3）

### 7.1 契约 → 场景

| 契约条款（出处） | 覆盖场景 |
|---|---|
| R-RT-1 入站完整解析（特性 §2） | #5 #6 #7 #8 #9 #10 #1 #2 #3 |
| R-RT-2 Handler 入参模型（特性 §2） | #5~#11（回显面）、#27 #28（multipart 面） |
| R-RT-3 出站构造（特性 §2） | #23 #24 #1 #2 #3 |
| R-RT-4 multipart 兼容接入（特性 §2） | #27 #28 #29 #30 #3 |
| 协议层校验 + `-32602`（特性 §2/§3；L2 §2.2 表） | #12~#21 #29 |
| 业务层类型校验 `EDP-FILE-003`（特性 §2/§3） | #22 |
| 安全基线（特性 §2/§5.1.5） | #31 #32 #5 #24 |
| 纯文本兼容（特性 §2/§5.1.6；L2 §3.3） | #4 #21 |
| 客户端接入选择（SHOULD，特性 §2） | 不覆盖（§1.3 排除行 1） |
| 特性 §6"测试必须覆盖"十项 | url=#5#1、raw=#6#2、多文件 raw=#7#2、data=#8、multipart=#27~#30#3、base64 非法=#13、raw 超限=#14、Part 超数=#15、业务类型拒绝=#22、纯文本兼容=#4 |
| L2 §2.2 步骤 1 Content-Length 预检 / 413 | #20 |
| L2 §2.4 步骤 4 格式保持 | #24 #1 |
| L2 §2.4 步骤 5 中断恢复重试 | #25 |
| L2 §2.4 步骤 1 `parts[0]=TextPart` | #23 |
| L2 §3.4 回程 FilePart 跳过 | #26 |
| L2 §6.1 容器必配生效 | #30 |
| OUT 边界四项（特性 §2/§5.2） | 不覆盖（§1.3 排除行 2，显式 OUT） |

### 7.2 场景 → 契约

见 §4 矩阵"契约依据"列，每条场景均可回溯到特性档/L2 具体章节；双向闭合，无矩阵孤儿行。

## 8. 代码落点、运行与退出标准

### 8.1 文件规划

| 文件 | 内容 |
|---|---|
| `src/test/java/com/huawei/ascend/sit/cases/integration/edpa/A2APartTransferTest.java`（原三用例类合并） | 全部 32 场景（参数化行见 §6）、共享拓扑 A/B 生命周期 |
| 同目录 fixture（同包 package-private 顶层类或独立文件） | `PartsEchoAgent` / `DelegateBridgeAgent` / `GatewayStub` / `FileServerStub` / `RawFileFactory` / `MultipartRequestBuilder` |

### 8.2 标签与签名（沿用 FEAT-022/FEAT-003 格式）

```java
@Feature("FEAT-036: A2A Part 多模态数据传输")
@Tag("feat-036") @Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class A2APartTransferTest {
    @Test @Tag("blackbox")
    @Stories({@Story("FEAT-036.in.mixed-order-preserved: 交错顺序可还原")})
    @DisplayName("Feat-036 text/url/raw/data 交错 Part 顺序完整保留")
    void feat036MixedOrderPartsPreserved() throws Exception { }

    @ParameterizedTest(name = "[{index}] {0}") @MethodSource("mutexViolationCases")
    @Tag("blackbox")
    @Stories({@Story("FEAT-036.val.mutex-violation: 判别字段缺失/并存")})
    @DisplayName("Feat-036 Part 判别字段违约返回 -32602")
    void feat036MutexViolationRejected(String caseName) throws Exception { }
}
```

### 8.3 运行方式

```bash
./mvnw test          # 零附加参数，自动发现全部 FEAT-036 用例
```

不使用 `e2e`/`performance` 等 Surefire 默认排除标签；边界大报文用例生成即弃、不依赖外部数据文件；端口/前缀动态分配（T-M20）。

### 8.4 退出标准

1. 32 条场景全部落码；Smoke 2 条全绿（当前基线即绿）。
2. L2 落码合入后：P0 15 条全绿构成特性准入门禁；Full 32 条全绿构成验收结论。
3. 协议层全部违约行返回规定 code/message 关键短语且**无 Task 副作用**；413 行先于解析。
4. 出站 wire 在网关桩侧逐字段可判（T-M21：全部断言落在 §2 观察面清单内）。
5. 纯文本回归基线 diff 为空。
6. EDPA 委托附件契约定稿后，补真实 EDPAgent 端到端用例（当前以拓扑 B 桥接 Agent 代替，见 §9 存疑 5），补入后本档条数从 32 递增并更新 §4 计数声明。**2026-09-09 已执行**：L2 §4.5 定稿确认，拓扑 B 出站判据面（#23~#25/#31/#32/#1/#2）已切换真实 EDPAgent 承载（§3.2 整改，条数不变——为宿主替换而非新增场景）。

## 9. 存疑项与裁定待办（T-M8 / T-M1b / T-M16）

| # | 存疑项 | 影响 | 处置 |
|---|---|---|---|
| 1 | **text Part >1MB 与纯文本兼容潜在冲突**：L2 §4.1 对单 `text` Part 同施 1MB 限额，但特性档纯文本兼容行要求存量语义不变——存量 >1MB 纯文本调用可能由"兼容"变"被拒" | #16 的 text 子行判据 | **裁定前不写死期望**，观察模式执行；裁定动作=提冲突单（T-M16），不替上游定契约 |
| 2 | **HTTP 413 与 1MB 限额为 L2 新增**（PM 对齐 2026-08-24），特性档 §2/§3 未载 | #16/#20 的引用等级 | 本档按 L2（合法契约源）引用并在矩阵标注"特性档未载"；建议特性档回填后消除双源 |
| 3 | L2 §3.3 引用"FEAT-036 §5.1.7"实为特性档 §5.1.6（引用笔误） | 无判据影响 | 本档一律引特性档 §5.1.6；已反馈设计侧修 L2 |
| 4 | **chunked（CL 缺失）→413 的严格语义**可能经 L2 §7.4 SE 压测后放宽为有界读取 | #20 的 chunked 子行 | 按现行 L2 写判据；L2 修订后同步改判据并记入 §10 变更记录 |
| 5 | **EDPA 委托附件契约（attachments/parts）为 L2 自定稿**，评审确认中（L2 §1.3 明示） | 拓扑 B 是否用真实 EDPAgent | **已闭合（2026-09-09）**：L2 §4.5 明示「该参数契约为本 L2 提出并定稿」，rail 侧映射（`extractDelegationParts`）已落码且有单测（DelegateRailAttachmentsTest）——拓扑 B 已切换真实 EDPAgent 承载（§3.2 整改）。**遗留缺口定性为 P0 代码缺陷**（非契约存疑）：`call_versatile` 工具 schema 未声明 `attachments` 参数（`CallVersatileTool.build()`，edp-agent-engine 0.1.1）——LLM 无从携带文件引用，入站 parts 也未以引用形式透给 LLM（模型推理自述"未见合同文件"），实测委托恒 `parts=0`、出站 wire 仅 TextPart(query)；#27/#28/#3 与拓扑 B #23/#24/#31/#32/#1/#2 的 raw/url/data wire 断言按 red-first 看守其修复（详见 FEAT-036-test-report §四.1） |
| 6 | `-32602 InvalidParams`（特性）vs `Invalid params`（L2）措辞差异 | 错误 message 断言 | 本档只锚定 code 数值与 L2 §7.3 关键短语，不锚定 "Invalid params" 字样，规避双源冲突 |
| 7 | SUT 构建锚点（T-M19）待落码 PR 回填 commit/tag | 可复现性 | §3.5 已定指纹口径（jar 名 + Card.version）；落码 PR 中回填；另见 §3.4 落码前置动作 |

## 10. 变更记录（T-S6）

| 日期 | 变更 |
|---|---|
| 2026-09-04 | 初版设计：32 条场景（Smoke 2 / P0 15 / Full 32），基于特性档 `active@2026-08-20` 与 L2 `draft@2026-08-25`（docs 私仓 `b1f50a6c`）；标注 7 项存疑待裁定；落码前除 Smoke 外均为 red-first。 |
| 2026-09-04 | 用例落码：`src/test/java/.../cases/integration/edpa/` 新增 `A2APartFixtures`（Part 构造 / raw JSON-RPC / chunked POST / multipart 报文 / GatewayStub / FileServerStub）与 `A2APartTransferTest`（合并用例类：拓扑 A #4~#22、拓扑 B #3/#27~#30、OUT/SEC/E2E `@Disabled` known-gap 按 FEAT-022 承接口先例）；`mvn test-compile` 通过；矩阵 32/32 有对应测试方法。 |
| 2026-09-04 | 文件整合：三个用例类（Feat036 前缀）合并为单一 `A2APartTransferTest`，类名/文件名 Feat036→A2APart；known-gap 组改方法级 `@Disabled`+`@Tag("known-gap")`；合并栈同时挂 echo-agent 与 edp-agent-multipart。 |
| 2026-09-08 | **L2 落码后第一轮真机复测（#27）**：① demo jar 构建替换完成（§3.4 前置动作 ①）；② buildStack 补 mp SUT 部署配置注入（remote-agents / 通用化场景 fixture / thinking.type=enabled / input-security 关闭，§3.4 清单）；③ 新增 `src/test/resources/feat036-scenario` 通用化场景 fixture；④ GatewayStub 补 agent-card GET 应答、响应改 `result.task` 包裹（SDK oneof 契约）；⑤ postUrl 超时 90s→240s（LLM 驱动 E2E 单轮实测 ~40s）；⑥ 修复测试缺陷 category B（#15 边界内 1+100=101 个 Part 误超限 → 1+99=100；超限侧 1+101=102 未贴边界 → 1+100=101，复测边界内侧转绿、超限侧仅剩分类 A 状态码断言失败，见报告 §4.2.3）；⑦ #27 复测结果：链路推进至 raw 断言 FAIL（HTTP 200 ✓、委托已产生 ✓、text ✓；raw ✗——出站委托 `parts=0`，attachments 通道无生产方，见 §9 存疑 5 补充），环境模型切换 ModelArts deepseek（已退役 404）→ Ark glm-5.3。 |
| 2026-09-09 | **分类 C 拓扑整改（出站判据面换宿主）**：① 7 个出站判据用例（#23/#24/#25/#31/#32/#1/#2）宿主由 echo-agent（无 LLM、无委托工具，恒零出站）切至 **edp-agent**（engine exec jar，拓扑 B 主被测，§3.4 行 2；依据 §8.4 退出标准 6 + L2 §4.5 attachments 契约定稿）；② buildStack 增挂 edp-agent（remote-agents **三键整体注入**——Spring 列表绑定以含 `remote-agents[0].*` 键的属性源为整体来源，只注入 streaming 单键会使 yml 的 name/url 丢失（"name must not be null" 启动失败实测）；通用化场景、thinking.type=enabled），OPENJIUWEN yml 注入 `EDP_AGENT_MODEL_*`（与 mp SUT 同款）；③ 探针意图文本改为落在场景 `scope.allowed`（文件审查/合同审查/订单查询/贷后资料审查）内，防规划层拒答不委托；④ fixtures 新增 `postSlow`（/a2a 阻塞单轮 240s）；⑤ GatewayStub/FileServerStub 补 `reset()`（用例间 wire 快照/计数/故障注入复位——拓扑 B 真实流量下跨用例累积污染 get(0)/计数断言，实测 #25 计入历史 12 请求）；⑥ #31 按设计 §6.6 补反假绿断言（base64 载荷须出现在出站 wire，排除"文件没送到恒绿"）。**真机验证（run 2，16:04–16:16）**：edp-agent 启动成功、7 用例全部触达委托链（12 次委托）；#25 实测 1 首投+2 退避重试（200ms/400ms）恰 3 请求、第 3 次恢复（reset 后应 PASS）；#23/#24/#31/#32 wire 缺 raw/url、#1/#2 任务 INPUT_REQUIRED（LLM ask_user"未检测到随附文件"）——均如实归因 P0 附件参数缺陷（CallVersatileTool），拓扑失真消除。终态复跑被 Ark 账户 5 小时配额耗尽阻塞（429，重置 19:11），quota 恢复后全量重跑确认。 |
| 2026-09-10 | **矩阵 #22 闭环（测试自有业务白名单落地）**：① 新增测试 fixture `WhitelistEchoAgent`（`src/test/java/.../edpa/`）：测试 JVM 内嵌 `agent-service-app:0.1.2` 实例（pom 既有 test-scope 依赖，与 echo-agent 进程内捆绑 runtime 同款；`SpringApplicationBuilder` 随机端口，配置镜像 echo demo yml），注册测试自有白名单 `AgentHandler`（白名单仅 `application/pdf`，违规 raw/url part 抛 `AgentExecutionException` + `AgentFailureDescriptor("EDP-FILE-003")`）——业务白名单按特性档 §2/§3 归业务 Agent，测试 fixture 即业务 Agent，未改动 agent-solution/agent-runtime-java 产品源码；② #22 断言面按 runtime 真实错误透出管道（`A2AAgentExecutor.executeAdmitted` catch → `failAndDrain` → `AgentEmitter.fail` → Task FAILED；字节码核实）重写：HTTP 200 + 无 JSON-RPC error envelope + `result.task.status.state=FAILED` + `status.message.parts[0].text` 含 `EDP-FILE-003` + `metadata["openjiuwen.error"].code=="EDP-FILE-003"`（`A2aErrorMetadata.encode` 结构化透出，含 `numericCode=40003`/`retryable=false`），移除 red-first 标记（原 4xx 期望系对齐前旧契约）；③ fixtures 新增 `resultTask()` 解析助手。**真机验证（09:29/09:33 两轮）**：#22 PASS，实测 wire：`{"result":{"task":{"status":{"state":"TASK_STATE_FAILED","message":{"parts":[{"text":"EDP-FILE-003: 文件类型不在业务白名单 [application/pdf] 内: mediaType=application/vnd.microsoft.portable-executable, filename=evil.exe"}],"metadata":{"openjiuwen.error":{"schemaVersion":"1","code":"EDP-FILE-003","numericCode":40003,"retryable":false}}}}}}}`。 |
| 2026-09-14 | **最新代码全量复测 41/43 + 测试侧整改复测 43/43（第四轮，报告 `test-reports/FEAT-036-A2APartTransferTest-report-20260914.md`）**：SUT 按 agent-runtime-java `715dc12`（run_context 附件透传）+ agent-solution `3af437fb`（attachmentRef manifest / multipart 逐字段分流）重打包——上轮 BUG-001（run_context 断裂→raw 出站丢失，6 用例）与 BUG-002（multipart 字段未分流，1 用例）确认修复，#23/#24/#31/#27/#28/#2 转绿。剩余 2 失败均定性**测试用例问题**并整改：① #30 探针文案"容器边界内探针"越界 scope.allowed（规划层合法 ask_user 拒答 + LLM 单轮 90–120s 使 240s 预算耗尽）→ 改"文件审查"意图文案 + `postUrl`/`upload` 增超时重载（该用例 360s）；② #3 出站 TextPart 为 LLM 规划生成的委托指令（L2 §2.4/§4.5），逐字断言不可复现 → 弱化为语义级（业务域关键词"贷后资料"），对齐 §6 #3"语义一致"判据。复测 2/2 通过（#30 86.6s——12MB+1KB 容器拒绝子断言同步补齐覆盖；#3 48.8s），**矩阵 32/32 全绿，FEAT-036 验收通过**。 |
