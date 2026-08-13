---
feature: FEAT-017
title: 运行时订阅消费总线事件消息
status: dependency-gated
sut: FEAT-017-enabled multi-react-travel-demo + agent-bus
---

# FEAT-017 - 运行时订阅消费总线事件消息测试设计

> 从正式 Agent Bus 公开事件边界投递请求并观察 Runtime 响应事件和真实 travel Agent Task，验证嵌入式消费、标准 A2A 语义、投影、幂等与流边界。

## 1. 设计依据与测试范围

- 依据：`FEAT-017-bus-event-subscription-consumption.md`（2026-07-21，SHA-256 `1E202595...A63E4E4`）；`Feat-Func-017-bus-event-subscription-consumption.md`（SHA-256 `745D4DCB...C39E50D`）；读取于 2026-08-12。
- 裁决：Feature 要求客户端/A2A 的创建、查询、取消、流订阅共八种入站事件；L2 §3.2/§11 与当前公开实现说明只交付六种并明确不处理 CancelTask。取消两族保留为产品 known-gap，不能从验收范围删除，也不以空 `@Disabled` 计为通过。L2 当前只解析 inline payload，Feature 的一般 payload/payloadRef 要求按当前 L2 收窄为 payloadRef-only 确定失败。
- 范围内：Runtime 边界内订阅消费；两类事件族进入标准 A2A Task 控制面；accepted/rejected/failed/response/input-required/stream-ready/terminal；query/cancel/subscribe；幂等、tenant/target/schema/deadline/payload 校验；ack 不等待终态；流引用且 token/SSE/物理地址不进 BUS。
- 范围外：Gateway 路由（FEAT-011/012）、Event Bus 转发/物理 topic/outbox/inbox（FEAT-013/014）、RDC（FEAT-016）、调用方 response 回灌、broker 内部 offset/group、内部 store/bridge/线程/bean。
- 源码查阅：未查阅 FEAT-017 产品 Java 源码。公开 L2、模块 README、集成指南和 POM 足以确定交付边界与装配缺口。

## 2. 黑盒拓扑、前置条件与证据

```text
acceptance event client -> formal agent-bus relay/broker -> runtime embedded consumer
  -> standard Task control plane -> real travel-mainplan -> runtime response events
acceptance SSE client <- taskId + streamRef <- runtime public SubscribeToTask
```

- Runtime 宿主必须是用户指定的 `multi-react-travel-demo`，并正式依赖 `com.openjiuwen:agent-service-bus-consumer:0.1.0`；不能用专用 echo demo 替代并宣称 travel 集成通过。
- 当前 travel POM 只依赖 `agent-service-app` 与 `agent-service-adapters-agentcore-ext`，没有 consumer 模块和 bus 配置；因此所有 FEAT-017 真实用例目前 `dependency-gated`。允许后续增加 acceptance 专用 repack/profile，但不能改产品 example 源码或用测试内 consumer 冒充正式能力。
- 证据只来自正式事件 API/捕获、Runtime 公共 A2A 查询/订阅和真实 Agent 结果。唯一 tenant、messageId、idempotencyKey、correlation、trace、task canary 全链路关联。
- Docker CLI 当前不可用；即使装配补齐，正式 broker/relay 运行仍属 env-gated，不能计为通过。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-017.control.two-families` | Feature §2-5；L2 §3、§5 | blackbox | dependency-gated, P0 | design-only | client/A2A 创建、查询、取消、订阅；accepted/input/response/terminal；标准入口等价 | 正式事件 + A2A Task | 两族/四操作参数化；取消当前 known-gap |
| `FEAT-017.delivery.idempotency-boundary` | Feature §5；L2 §5-7 | blackbox | dependency-gated, P0 | design-only | message 去重、业务幂等、tenant/target/信封/payload、ack、投影补发 | taskId/事件/时序 | 负向表驱动合并 |
| `FEAT-017.stream.control-data-separation` | Feature §2/5.1.6；L2 §3.4/§5.2 | blackbox | dependency-gated, P0 | design-only | stream-ready/ref、点对点订阅、token/endpoint 不进 BUS、重订阅 | 控制事件 + SSE | 两事件族参数化 |

## 4. 详细用例

### FEAT-017.control.two-families - 两类事件族复用 Task 控制面

- **G**：正式 broker/relay 与已启用 consumer 的真实 mainplan；客户端族和 A2A 族各有独立身份/correlation。
- **W**：参数化发布创建（完成与 INPUT_REQUIRED）、GetTask query、CancelTask、SubscribeToTask；操作始终使用 Runtime 返回的真实 taskId。
- **T**：创建先产生匹配族的 `*_ACCEPTED` 且 taskId 可从标准 HTTP GetTask 查询；响应族、tenant、source/target、correlation、trace 与原信封可关联但不泄露内部 route；完成产生 A2A 兼容 response+唯一 terminal，等待产生显式 `*_INPUT_REQUIRED`；query 不创建 Task；cancel 进入同一 Task owner并形成 canceled terminal；subscribe 不创建 Task并产生 stream-ready或明确失败；业务 Agent 结果与 HTTP 入口的 Task 状态和 A2A payload 语义一致。
- **失败归类**：任何合同漂移为 Failure；当前 cancel 未交付为 known-gap；travel 未装 consumer 为 dependency-gated；broker 不可运行是 env-gated。
- **不应断言**：内部 HTTP loopback 与否、bridge/bean/store、topic/offset。
- **方法/标签**：`feat017ConsumesClientAndA2aControlEvents()`；Story/tag `FEAT-017.control.two-families` / `story-feat-017-control-two-families`；DisplayName `Feat-017 两类 BUS 请求进入同一标准 Task 控制面`。

### FEAT-017.delivery.idempotency-boundary - 受理、幂等、隔离与 ACK 边界

- **G**：可捕获响应事件和 broker delivery disposition；真实 mainplan 的长任务由外部 LLM peer 可控阻塞。
- **W**：重投同 messageId；以不同 messageId+同 idempotencyKey/同正文重试；再用同 key/不同正文；表驱动发送跨 tenant、错 target、过期、非法 schema/信封、payloadRef-only、超过 65,536 bytes 的 inline payload 和超过 16,384 bytes 的 metadata；在 accepted 后暂时阻塞 Agent/响应发布并恢复。每个负向载荷带唯一敏感 canary。
- **T**：重复投递/业务重试只对应一个 taskId和一次业务副作用；key 冲突产生带稳定错误码与 retryable 语义的 rejected 且不建新 Task；非法范围不跨 tenant fallback；payloadRef-only、超限或不可解析 payload 产生可编程 failed/rejected 且不把引用当 JSON；超限载荷在进入 Agent 前拒绝；响应事件与公开诊断不回显敏感正文、物理 route 或凭据；ack 在可靠受理边界完成而不等待 Agent 终态；投影发布短暂失败不回滚 Task，并补发相同语义而不重复终态。
- **失败归类/不应断言**：违反外部语义为 Failure；不读取内部 admission/projection store，不断言 broker 专属 ack 枚举之外的实现。
- **方法/标签**：`feat017EnforcesDeliveryAndIdempotencyBoundaries()`；Story/tag `FEAT-017.delivery.idempotency-boundary` / `story-feat-017-delivery-idempotency-boundary`；DisplayName `Feat-017 重投、重试和非法信封不重复或越权创建 Task`。

### FEAT-017.stream.control-data-separation - BUS 控制面与 SSE 数据面分离

- **G**：两事件族分别启动真实流式 travel Task，事件探针只观察公开 broker 消息。
- **W**：接收 accepted/stream-ready，使用 taskId+streamRef 访问标准 SubscribeToTask；断开后重订阅；扫描该 correlation 的全部 BUS 投影。
- **T**：accepted 与 stream-ready 各自有明确语义；streamRef 非空、不含 URL/topic/token/SSE frame，不能替代 taskId；真实 token/progress 只在点对点 SSE 出现；错误 taskId/ref 不隐式建 Task且不泄露其他租户；终态由 terminal/Task 查询恢复。
- **方法/标签**：`feat017KeepsRealtimeStreamOffBus()`；Story/tag `FEAT-017.stream.control-data-separation` / `story-feat-017-stream-control-data-separation`；DisplayName `Feat-017 BUS 只承载流控制而实时数据保持点对点`。

## 5. 文件与执行

- 计划测试文件：`Feat017RuntimeBusConsumerBlackboxTest.java`；三条旅程共享 BUS fixture，事件族与负向输入参数化。
- Feature/标签：`@Feature("FEAT-017: 运行时订阅消费总线事件消息")`，`feat-017,integration,blackbox,dependency-gated`。
- 解锁后执行：`.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-017 test`；Story 使用对应 `story-feat-017-*`。
- 必需制品：travel fat JAR、`agent-service-bus-consumer:0.1.0`、正式 agent-bus SDK/relay/broker adapter。JDK 21；Maven 实际本地仓库 `D:\repository`；当前机器缺 Docker CLI。

## 6. 风险、阻塞与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| travel demo 未依赖/配置 bus consumer | 无法以指定 Agent 验收 FEAT-017 | dependency-gated | 提供正式 BUS-enabled travel classifier/profile/assembly |
| Feature 八种事件 vs L2/交付六种 | cancel MUST 无法通过 | known-gap | 交付两类 CancelTask 映射并更新公开能力说明 |
| Docker CLI 缺失 | broker/relay 无法本机运行 | env-gated | 提供可达正式环境或安装 Docker |
| 当前进程内存可靠性 | 不覆盖跨重启 | 按 L2 deferred | 后续 DFX 设计/持久化实现 |

## 7. 退出标准

正式 BUS-enabled travel Agent 上三例通过，八种入站事件与必需响应投影均有证据；取消 known-gap 未关闭前 FEAT-017 不判整体通过；没有以 broker 内部断言或专用 demo 替代指定 SUT。
