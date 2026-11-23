---
level: L2-LLD
module: agents/deepanalyze-java
feature_type: functional
feature_id: FEAT-112
status: draft
updated: 2026-11-11
dependency:
  - ../../../02-features/FEAT-112-task-degradation-gate.md
  - ../../../02-features/FEAT-109-dangerous-operation-guardrail-and-hooks.md
  - ../../../02-features/FEAT-110-task-checkpoint-resume.md
  - ../../../02-features/FEAT-111-trajectory-reflection-and-cost-tracking.md
  - ./Feat-Func-054-deepanalyze-host-agent.md
---

# 任务异常率降级门详细设计

本文描述 deepanalyze-java 双新任务入口的实例级降级门：共享结构化终态分类、滑动窗口异常率、真实任务半开探针、故障代际和健康诊断。version-scope 定义外部目标，本文按合入后的真实代码说明实现与差距。

源码行号基准：`agent-solution@origin/common`，提交 `4e952c23465989bc7925b87b345c69b1603e7668`。实现状态使用“已有 / 部分已有 / 不承诺 / 未验证”；测试类在场不等同本文编写阶段重新实跑通过。

## 1. 设计目标与边界

### 1.1 特性范围

本特性承接：

- 在 agent-runtime-ext-java 提供单实例任务异常率窗口、CLOSED/OPEN/HALF_OPEN 状态机和唯一真实探针。
- 在 DA `/run`、`/run-stream` 共用参数校验后的准入决定，拒收时不创建任务或 SSE 假流。
- DA 任务记录恰好一次产生结构化终态事实，降级门和反思调度器共享同一分类结果。
- 半开探针认领、taskId 绑定、终态/待答/无结论落定、故障代际防迟到恢复。
- `/v1/health/detail` 暴露实例级状态和计数，不改变 `/health`，不向 `/ready` 增加门状态 down 分量。

明确不承接：

| 不承接项 | 边界与替代 |
|----------|------------|
| 其他 runtime 入口 | `/v1/query`、WebFlux、标准 A2A、Agent Bus、custom-rest 未接入。 |
| 集群级降级 | 不共享窗口、探针或状态；请求和诊断按 instanceId 解释。 |
| 分桶降级 | 不按租户、会话、模型或 Agent 分桶。 |
| 自动 CLOSED 与内部假探针 | 静默期只到 HALF_OPEN；恢复必须来自真实任务健康结果。 |
| 请求级 fallback | 不改写真实任务、不切换备用模型、不免费重试。 |
| 持久化恢复和强制门控端点 | 进程重启是 CLOSED 空窗口初始化，不是旧实例恢复；不提供人工强制开关门。 |
| 前端提示 | D4 负责展示，本仓只提供 503 和诊断事实。 |

### 1.2 能力对齐矩阵

| 能力 | version-scope 要求 | 当前实现状态 | L2 设计响应 |
|------|--------------------|--------------|-------------|
| 异常率窗口 | MUST | 已有 | 单调水位剪枝、窗口内乱序插入、最小样本和异常率双阈值。 |
| 结构化终态分类 | MUST | 部分已有 | 九值 `TerminalClass` 集中分子/分母属性，DA 稳定信号只分类一次； denied 证据在未知失败上被当作护栏终态，不能证明最终因果，见 §1.3。 |
| 每逻辑任务一次 | MUST | 已有（依赖任务记录 CAS） | 回答和续跑复用原记录，终态认领后才发事实；分发器不自行去重。 |
| 传输不独立记账 | MUST | 已有 | SSE 重连只读缓冲，断流后仍按真实终态分类。 |
| 双入口统一拒收 | MUST | 已有 | `prepareExecution` 在 taskId、注册、start 和提交之前认领或拒绝。 |
| 校验先于准入 | MUST | 已有 | 参数、媒体、team 与 runtimeConfig 校验先完成，无效请求不占探针。 |
| 只拦新增负担 | MUST | 已有 | 状态、列表、重连、取消、注入、回答和恢复不接入门。 |
| 三态与真实半开 | MUST | 已有 | 时间到只半开；唯一真实任务探针；无流量不会自动 CLOSED。 |
| 故障代际 | MUST | 已有 | 未过期系统故障递增代际并回 OPEN，旧探针成功不能覆盖；version-scope §5.1.4 明确过期事实豁免，当前探针过期落定只释放名额。 |
| 探针闭环 | MUST | 已有并有扩展 | 待答健康不记样本；取消/拒收实际落定后释放；四类非系统异常有效终态均可健康关门。 |
| 实例诊断 | MUST | 已有 | 健康块含全部计数、状态、探针和实例标识；他实例事实忽略。 |
| 探活与认证 | MUST | 部分已有 | 三态健康详情均 200；认证过滤器具备能力，但交付默认关闭，部署须显式开启。 |
| 配置与非法值 | MUST | 已有 | ext 默认 false，DA 装配默认 true；`validate()` 校验窗口、最小样本、阈值和静默期四项约束，enabled 由布尔绑定和 DA 默认值处理。 |
| 集群、分桶、持久化、fallback | OUT | 不承诺 | 不新增共享状态、备用执行链或恢复端点。 |

### 1.3 当前实现差距与约束

1. **全部状态仅在进程内。** 窗口、探针、故障代际、任务记录 CAS 和事实分发都不持久化；instanceId 每次支持对象创建都会变化。重启只能记为新进程初始化。
2. **OPEN→HALF_OPEN 是惰性迁移。** 由准入或健康快照推进，没有后台定时器；诊断查询能让状态进入 HALF_OPEN，但不能让状态进入 CLOSED。
3. **残窗可因健康样本过期而开门。** CLOSED 在准入和快照修剪后重新评估；样本总数和异常率同时达标即开门，并非只有新故障到达才评估。
4. **过期事实豁免有明确边界。** 到达时终态时刻已越过单调窗口下界的事实不记账、不改变代际、最近故障时间或门状态；若属于当前探针，仍只释放名额并保持 HALF_OPEN，避免永久占用。该行为由 version-scope §5.1.4 正式定义，只适用于窗口外迟到事实，不得扩展为忽略窗口内新故障或把过期落定记作健康恢复。测试证据：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/test/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGateTest.java:592-602`。
5. **健康终态范围宽于需求列举。** 当前 `COMPLETED`、`BUSINESS_FAILURE`、`INPUT_REJECTED`、`GUARDRAIL_DENIED` 四类都可让当前探针关门；awaiting 单独健康关门但不记样本。
6. **恰好一次由生产者负责。** `DaTaskRecord` CAS 保证当前 DA 每任务最多产生一次；共享 `TerminalFactDispatcher` 和 `DegradationGate` 不按 taskId 去重。新生产者必须继承同一产生约束。
7. **事实分发无持久重试。** CAS 在 sink 调用前置位，分发器同步调用且抛错不自动重放，不形成持久事实日志。
8. **健康详情默认不是强制认证面。** `deepanalyze.auth.enabled=false` 是交付默认；只有显式开启后，缺失或错误 `X-Engine-Secret` 才由过滤器拒绝。
9. **业务失败只认专用结构化终态，denied 不是最终因果证明。** 普通工具错误、回答文本或展示文案不会生成 `BUSINESS_FAILURE`。无护栏 denied 证据时，未知失败保守计为 INFRASTRUCTURE_FAILURE；存在 denied 且没有其他结构化系统异常证据时，当前实现归为 GUARDRAIL_DENIED。该规则只能证明任务中出现过拒绝，不能证明最终失败确由护栏或用户拒绝导致，与 version-scope 的因果要求存在差距。
10. **探针身份不向调用方暴露。** 探针是正常用户任务，仍按真实任务成功、失败、待答或取消契约返回。
11. **真实故障演练边界。** 现有 HTTP 测试主要使用脚本化执行链，多实例测试是同 JVM 独立对象；不能据此宣称真实模型故障、真实多进程部署和整仓流水线已验证。

## 2. 外部服务面设计

### 2.1 新任务准入端点

覆盖 `POST /api/agents/run` 与 `POST /api/agents/run-stream`。共同顺序：

```text
服务间认证（启用时）
  -> 参数、媒体、team、runtimeConfig 校验
  -> degradationSupport.claimProbeSlot()
  -> taskId / 注册 / 属主绑定 / start / 执行提交
```

OPEN 或 HALF_OPEN 探针已占用时返回 HTTP 503 JSON：

```json
{
  "error": "Agent is temporarily not accepting new tasks",
  "code": "ENGINE_DEGRADED",
  "degraded": true,
  "retryable": true,
  "state": "OPEN",
  "instanceId": "deepanalyze-agent-a1b2c3",
  "retryAfterSeconds": 87
}
```

| 字段/行为 | 契约 |
|-----------|------|
| `code/degraded/retryable` | 固定为 `ENGINE_DEGRADED/true/true`，不复用 `ENGINE_BUSY`。 |
| `state/instanceId` | 实际拒收时的 OPEN/HALF_OPEN 和本实例标识。 |
| `retryAfterSeconds` | 仅 OPEN 出现，与 `Retry-After` 使用相同向上取整秒数；HALF_OPEN 省略。 |
| run-stream | 在创建订阅、start 事件和 SSE 前返回 JSON，不形成 200 假任务流。 |
| 副作用 | 不分配 taskId、不注册任务、不绑定属主、不提交执行、不进入窗口。 |

实现证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaCompatController.java:1353-1370,1408-1465`。

探针认领之后仍可能发生既有资源拒绝：待答任务触顶在 taskId 前抛 `ENGINE_BUSY`，try-with-resources 直接释放；执行池饱和形成 `NOT_ADMITTED(pool_saturated)` 事实并由事实流释放。证据：同文件 `1443-1465,1615-1628`。

以下入口不走降级准入：`/stream`、`/status`、`/task`、`/tasks`、`/cancel`、`/inject`、`/answer`、`/message`、`/resume`。

### 2.2 健康诊断端点

`GET /v1/health/detail` 三种状态均返回 HTTP 200：

```json
{
  "status": "degraded",
  "degradationGate": {
    "enabled": true,
    "state": "HALF_OPEN",
    "instanceId": "deepanalyze-agent-a1b2c3",
    "sampleCount": 20,
    "systemFailureCount": 11,
    "systemFailureRate": 0.55,
    "probeInFlight": true
  }
}
```

| 状态 | status | 计数与恢复时间 |
|------|--------|----------------|
| CLOSED | healthy | 当前窗口计数；空窗口和恢复清窗均为 0，异常率禁止 NaN；无 retryAfterSeconds。 |
| OPEN | degraded | 当前窗口计数、probeInFlight 和 retryAfterSeconds。 |
| HALF_OPEN | degraded | 当前窗口计数和 probeInFlight；无 retryAfterSeconds。 |
| 禁用但已装配 | healthy | enabled=false、CLOSED、零计数、probeInFlight=false。 |
| 支持对象缺席 | healthy | 省略 degradationGate 块，不伪造禁用快照。 |

证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaHealthDetailController.java:117-150`。认证启用时缺失或错误 `X-Engine-Secret` 返回 401 `{error:"unauthorized"}`：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaServiceAuthFilter.java:63-92`。

`/health` 不因门状态变化；`/ready` 不新增门状态 down 分量，但其原有组件检查仍可独立返回 503：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaHealthController.java:103-117`。

## 3. 核心运行模型

### 3.1 单调滑动窗口

```text
terminalAt / admission now / snapshot now
  -> windowAnchor = 历史最大观测时刻（永不倒退）
  -> 删除 terminalAt <= windowAnchor-window 的样本
  -> 窗口内乱序事实按 terminalAt 有序插入
  -> sampleCount = 有效终态样本数
  -> systemFailureRate = 系统异常数 / sampleCount，空窗口为 0
  -> sampleCount >= minSampleSize 且 rate >= threshold 才 CLOSED->OPEN
```

终态、准入和诊断共享 `windowAnchor`，防止较新的当前时间之后到达的过期故障重新污染窗口。窗口内乱序系统故障仍递增代际，但 `lastSystemFailureAt` 只向前推进。证据：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGate.java:131-185,316-374`。

### 3.2 状态机与故障代际

```text
CLOSED
  | 修剪后残窗同时满足最小样本和异常率阈值
  v
OPEN
  | 距最近有效系统故障达到 open-quiet-period；准入或诊断惰性推进
  v
HALF_OPEN
  | 原子认领唯一真实任务，记录 failureGeneration 和 claimId
  +-- 任意窗口内已受理任务系统异常 -> OPEN、代际+1、清探针、重算静默期
  +-- 当前探针四类有效健康终态且代际未变 -> CLOSED、清窗口、释放名额
  +-- 当前探针 awaiting 且代际未变 -------> CLOSED、清窗口、不记样本
  +-- 当前探针取消/NOT_ADMITTED 实际落定 -> 保持 HALF_OPEN、只释放名额
  +-- 当前探针事实已过期 ----------------> 保持 HALF_OPEN、只释放名额
```

认领、绑定、故障代际比较、健康关门和名额释放都在同一把门锁内完成。新故障清掉原探针资格后，迟到成功只能作为普通样本，不能凭旧 taskId 关门。上述“新故障”仅指未过期事实；过期事实不递增代际是 version-scope §5.1.4 的正式豁免，当前探针只释放名额，不产生健康恢复结论。证据：同文件 `131-185,198-282,379-393`。

### 3.3 探针认领所有权

```text
DaProbeSlotHandle.create(gate, admission)
  -> CLOSED 普通放行或拒收：无动作句柄
  -> HALF_OPEN 放行：持有 claimId
       taskId 生成后 bindProbeTask
       任务注册并进入可产生事实的记录：markCommittedToFactFlow
       close：未 committed 才 releaseProbeClaim
  -> 已 committed：由 awaiting 或最终终态事实收口
```

这覆盖“认领后、绑定前”和“绑定后、注册前”两段异常窗口，同时避免已提交事实流的任务被句柄提前释放。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaProbeSlotHandle.java:87-131`。

### 3.4 终态事实生产与消费

```text
TerminalClosure
  -> DaTaskRecord 终态 CAS（每逻辑任务最多一次）
  -> DaDegradationSupport.onTerminal
       收集稳定错误码、ext 类目、core error 结果位、结构化业务类型、护栏证据
       classify -> TerminalClass
       TaskTerminalFact(instanceId, taskId, terminalAt, ...)
  -> TerminalFactDispatcher 同步分发
       -> DegradationGate.onTerminalFact
       -> 可选反思监听器
```

 awaiting 在终态认领前返回，不产生事实，只调用探针待答处理。回答、恢复、inject、内部重试、工具和子 Agent 不各自生成任务终态事实。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaDegradationSupport.java:110-117,191-223`、`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaTaskRecord.java:728-729,758-768`。

### 3.5 唯一分类顺序

| 顺序 | 结构化条件 | TerminalClass | 系统异常分子 | 有效样本分母 |
|---:|------------|---------------|:---:|:---:|
| 1 | terminalStatus=cancelled | USER_CANCELLED | 否 | 否 |
| 2 | 非 failed 的有效终态 | COMPLETED | 否 | 是 |
| 3 | 显式 admissionReason | NOT_ADMITTED | 否 | 否 |
| 4 | ENGINE_CONTEXT_TOO_LONG | INPUT_REJECTED | 否 | 是 |
| 5 | ENGINE_BUSY 或 CONVERSATION_BUSY | NOT_ADMITTED | 否 | 否 |
| 6 | ENGINE_QUOTA_EXCEEDED / ENGINE_AUTH_FAILED | CREDENTIAL_OR_QUOTA_FAILURE | 是 | 是 |
| 7 | ENGINE_MODEL_ERROR / ENGINE_TIMEOUT / ENGINE_RATE_LIMITED | MODEL_SERVICE_FAILURE | 是 | 是 |
| 8 | 其他非空 failureCategory | INFRASTRUCTURE_FAILURE | 是 | 是 |
| 9 | core result_type=error 结果轮通道 | MODEL_SERVICE_FAILURE | 是 | 是 |
| 10 | 显式 result_type=business_failure | BUSINESS_FAILURE | 否 | 是 |
| 11 | failed 且全程有护栏 denied 证据 | GUARDRAIL_DENIED | 否 | 是 |
| 12 | 其余 failed | INFRASTRUCTURE_FAILURE | 是 | 是 |

已识别的稳定错误码、ext 失败类目、core error-result 和容量/准入证据先于业务或护栏推断，因此这些系统异常不会被历史 denied 改写。若只有 denied 而没有其他结构化系统异常证据，当前实现会在最终保守缺省之前归为 GUARDRAIL_DENIED；这无法证明最终失败确由护栏造成，是§1.3 的因果差距。普通工具业务错误只有在真实任务最终成功时随 `COMPLETED` 计健康样本，不能仅凭工具文本伪造业务终态。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaDegradationSupport.java:248-287`、`common/agents/deepanalyze-java/engine/src/test/java/com/openjiuwen/da/compat/DaTerminalClassificationTest.java:46-52`。

专用业务终态由 `DaBusinessOutcomeRail` 对 `business_failure` 工具产生，翻译器识别直接或 interrupt 信封内的结构化结果；Rail 缺席时工具返回执行错误，不伪造业务事实。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/tools/DaBusinessOutcomeRail.java:30-42`、`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/tools/DaBusinessOutcomeTools.java:53`、`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaEventTranslator.java:432,470,543`。

## 4. 代码结构与类级设计

### 4.1 包结构

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/
└── src/main/java/com/openjiuwen/service/adapters/agentcore/ext/
    ├── degradation/
    │   ├── DegradationGate.java              [已有] 窗口、状态机、探针、快照
    │   ├── DegradationGateProperties.java    [已有] 配置与启动校验
    │   ├── DegradationGateAdmission.java     [已有] 准入结果与认领令牌
    │   ├── DegradationGateSnapshot.java      [已有] 诊断快照
    │   └── DegradationGates.java             [已有] 显式构造入口
    └── terminal/
        ├── TaskTerminalFact.java             [已有] 共享终态事实
        ├── TerminalClass.java                [已有] 九值分类及记账属性
        └── TerminalFactDispatcher.java       [已有] 同步多监听器分发

common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/
├── compat/
│   ├── DaDegradationSupport.java             [已有] DA 分类、装配、事实桥
│   ├── DaProbeSlotHandle.java                [已有] 探针认领所有权
│   ├── DaCompatController.java               [已有] 双入口准入与 503
│   ├── DaHealthDetailController.java         [已有] 实例诊断投影
│   ├── DaTaskRecord.java                     [已有] 终态恰好一次产生点
│   └── TerminalClosure.java                  [已有] 待答/终态统一收口
└── tools/
    ├── DaBusinessOutcomeTools.java           [已有] 专用业务失败工具
    └── DaBusinessOutcomeRail.java            [已有] 结构化业务终态生产点
```

### 4.2 降级门机制

```java
public final class DegradationGate implements TerminalFactListener {
    public void onTerminalFact(TaskTerminalFact fact);
    public void bindProbeTask(long probeClaimId, String taskId);
    public void releaseProbeClaim(long probeClaimId);
    public void onProbeAwaitingAnswer(long probeClaimId);
    public DegradationGateAdmission tryAdmit(Instant now);
    public DegradationGateSnapshot snapshot(Instant now);
}
```

机制不自行读取系统时钟，时间由宿主注入；禁用时不记账并恒放行；他实例事实直接忽略。证据：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGate.java:131-148,198-305`。

`DegradationGates` 只提供显式构造，不自行自动装配：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGates.java:12-15,34-36`。

### 4.3 DA 装配与宿主桥

```java
public class DaDegradationSupport {
    public static DaDegradationSupport create(
        DegradationGateProperties properties, String applicationName,
        TerminalFactListener extraListener);
    public DaProbeSlotHandle claimProbeSlot();
    public DegradationGateSnapshot snapshot();
    public void onTerminal(DaTaskRecord record, DaEventTranslator translator, String admissionReason);
    public void onAwaitingAnswer(DaTaskRecord record);
}
```

instanceId 由应用名和三个随机字节的十六进制后缀组成；门监听器排在可选反思监听器之前。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaDegradationSupport.java:110-117,177-223,327-330`。

`DaCompatAutoConfiguration` 启用属性绑定并创建支持对象；DA 配置键完全缺席时装配层也补为 true，显式 false 优先：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/autoconfigure/DaCompatAutoConfiguration.java:68-72,231-245`。

### 4.4 分类和终态数据对象

`TerminalClass` 将九种分类的 `countsAsSample/countsAsSystemFailure` 属性集中定义，门不读取错误文本：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/terminal/TerminalClass.java:19-85`。

`TaskTerminalFact` 字段为 `taskId/sessionId/terminalClass/terminalAt/engineErrorCode/errorCategory/guardrailDeniedEvidence/admissionReason/continuationCount/instanceId`，不包含原始异常、用户输入、工具参数或密钥：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/terminal/TaskTerminalFact.java:32-47`。

`TerminalFactDispatcher` 同步遍历监听器，不去重、不持久化：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/terminal/TerminalFactDispatcher.java:45-52`。

## 5. 关键数据对象与映射设计

### 5.1 对象职责

| 对象 | 字段 | 语义 |
|------|------|------|
| `WindowSample` | `terminalAt, systemFailure` | 门内部有效样本；只保留分类结果，不保留错误正文。证据：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGate.java:106-113`。 |
| `DegradationGateAdmission` | `admitted,state,instanceId,probeClaimId,retryAfterSeconds` | 普通放行、探针放行或拒收的统一结果。证据：同目录 `DegradationGateAdmission.java:23-28`。 |
| `DegradationGateSnapshot` | `enabled,state,instanceId,sampleCount,systemFailureCount,systemFailureRate,probeInFlight,retryAfterSeconds` | 健康面唯一机制数据源。证据：同目录 `DegradationGateSnapshot.java:24-32`。 |
| `TerminalSignal` | `terminalStatus,engineErrorCode,failureCategory,coreModelErrorResult,guardrailDeniedEvidence,admissionReason,terminalResultType` | DA 分类输入；保留六参数兼容构造。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaDegradationSupport.java:289-309`。 |
| `DaProbeSlotHandle` | `gate, admission, committedToFactFlow, closed` | 区分未提交异常释放和已提交事实流收口；claimId 从 admission 获取，taskId 仅作为绑定参数传给门和任务记录，不是句柄字段。证据：`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/compat/DaProbeSlotHandle.java:35-47,87-131`。 |

### 5.2 终态到窗口的映射

| 来源事实 | 分类结果 | 窗口/状态用途 |
|----------|----------|---------------|
| 最终成功，包括内部重试后成功 | COMPLETED | 只记分母；当前探针可健康关门。 |
| 专用业务失败终态 | BUSINESS_FAILURE | 只记分母；当前探针可健康关门。 |
| 结构化上下文过长 | INPUT_REJECTED | 只记分母；当前实现允许健康关门。 |
| 无其他系统异常证据且全程有 denied 的 failed | GUARDRAIL_DENIED | 只记分母；当前实现允许健康关门，但 denied 本身不证明最终因果。 |
| 用户取消 | USER_CANCELLED | 不记账；当前探针实际落定后释放。 |
| 会话忙、执行池满、门自身拒收 | NOT_ADMITTED | 不记账；已认领探针由相应释放链收口。 |
| 具有稳定 `ENGINE_QUOTA_EXCEEDED` / `ENGINE_AUTH_FAILED` 的共享认证/配额失败 | CREDENTIAL_OR_QUOTA_FAILURE | 分子、分母、代际和最近故障时间。 |
| 具有 `ENGINE_MODEL_ERROR` / `ENGINE_TIMEOUT` / `ENGINE_RATE_LIMITED`，或来自 core `result_type=error` 通道的模型/网络/超时/重试耗尽失败 | MODEL_SERVICE_FAILURE | 分子、分母、代际和最近故障时间。 |
| 无 denied 证据的未知或其他基础设施失败 | INFRASTRUCTURE_FAILURE | 保守按系统异常，不从展示文案反推用户问题；仅有 denied 时先落入前表 GUARDRAIL_DENIED 分支，存在因果差距。 |
| awaiting_answer | 不产生终态事实 | 只处理当前探针健康关门，最终终态再唯一记账。 |

### 5.3 诊断字段映射

`DegradationGateSnapshot` 到健康块逐字段同名投影；`retryAfterSeconds=null` 时省略字段。`status` 仅由 state 映射：CLOSED=healthy，OPEN/HALF_OPEN=degraded。禁用快照由机制直接构造零值，不读取窗口列表。

## 6. 关键配置项设计

### 6.1 完整配置示例

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    degradation-gate:
      enabled: true
      window: 5m
      min-sample-size: 20
      failure-rate-threshold: 0.50
      open-quiet-period: 2m

deepanalyze:
  auth:
    enabled: true
    engine-secret: ${DA_ENGINE_SECRET:}
```

这里为了呈现需求要求的认证部署姿态，将 `deepanalyze.auth.enabled` 写为 true；随包默认实际是 false，不能用示例掩盖默认差异。secret 只使用环境占位符，不写真实值。

### 6.2 默认值与约束

| 配置 | ext 默认 | DA 交付值 | 合法约束与语义 |
|------|---------:|----------:|----------------|
| `openjiuwen.service.degradation-gate.enabled` | false | true | DA 键缺席时装配层也补 true；显式 false 优先。 |
| `openjiuwen.service.degradation-gate.window` | 5m | 5m | 非空且大于 0。 |
| `openjiuwen.service.degradation-gate.min-sample-size` | 20 | 20 | 大于等于 1。 |
| `openjiuwen.service.degradation-gate.failure-rate-threshold` | 0.50 | 0.50 | `(0,1]`，NaN 非法。 |
| `openjiuwen.service.degradation-gate.open-quiet-period` | 2m | 2m | 非空且大于等于 0。 |
| 半开探针数 | 固定 1 | 固定 1 | 无配置键。 |
| `deepanalyze.auth.enabled` | false | false | 是否强制服务间认证；健康诊断的认证要求需部署显式开启。 |
| `deepanalyze.auth.engine-secret` | 空 | 空 | 开启认证时必填；缺失启动失败。 |

证据：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/degradation/DegradationGateProperties.java:20-35,135-153`、`common/agents/deepanalyze-java/engine/src/main/resources/application.yml:65-70,244-247`。

### 6.3 装配与配置作用点

| 装配点 | 作用 |
|--------|------|
| `DegradationGateProperties.validate()` | 构造门时校验 window、min-sample-size、failure-rate-threshold、open-quiet-period 四项约束，即使 enabled=false 也校验；异常含完整键名和非法值。enabled 由布尔配置绑定和 DA 默认值处理，不归功于 validate()。 |
| `DegradationGates.create()` | ext 显式构造入口，不自行扫描装配。 |
| `DaCompatAutoConfiguration` | 绑定配置、补 DA 默认 enabled=true、创建支持对象和可选共享监听器。 |
| `DaCompatController.prepareExecution()` | 双入口唯一门控消费点。 |
| `DaHealthDetailController.detail()` | 读取同一支持对象的快照，不另建状态。 |
| Surefire 测试基线 | 普通 DA 测试全局关闭门，专项测试显式开启：`common/agents/deepanalyze-java/engine/pom.xml:168-177`。 |

### 6.4 Gating 原则

- 新任务入口必须先完成入站校验，再调用同一 `claimProbeSlot()`；不得在某一个入口单独复制状态判断。
- 只有 `TerminalClass` 决定分子和分母；Controller、Translator、反思和健康面不得重新解析展示文本。
- 只有 DA 任务记录终态 CAS 后才可发事实；共享门不负责补偿生产者的重复发送。
- 当前探针过期落定只释放名额，不能重新记账或开门；窗口内系统故障则必须递增代际。该过期豁免是 version-scope §5.1.4 的明确边界，既防止窗口外迟到事实污染状态，也防止探针名额永久占用。
- 部署必须按 instanceId 关联 503、诊断和前端提示，并显式开启健康详情所需的服务间认证。

## 7. 特性实现验收标准

### 7.1 能力矩阵验收

| 能力 | 验收标准 | 现有测试资产与边界 |
|------|----------|--------------------|
| 开关和配置 | ext 默认关闭，DA 默认开启；四项时长/数值约束非法值指名失败，合法边界通过。 | `DegradationGatePropertiesTest`、`DaDegradationDeliveryDefaultTest`；enabled 的畸形布尔绑定和 Spring 启动测试未单独覆盖。 |
| 最小样本与阈值 | 19 个样本不拒新，第 20 个达标后拒；25% 不开、50% 开，无异常次数旁路。 | `DegradationGateTest`。 |
| 窗口与乱序 | 过期样本退出；残窗可开门；窗口内乱序记账；过期事实不改状态或代际。 | `DegradationGateTest` 已覆盖乱序、过期事实和最近故障时间；健康样本过期后残窗达到双阈值并 CLOSED→OPEN 的直接用例需补。 |
| 分类矩阵 | 九类分子/分母与 §3.5 一致，稳定系统证据优先；无 denied 的未知失败保守计基础设施。 | `DegradationGateTest`、DA 终态分类测试；仅有 denied 且无其他系统证据时被归为 GUARDRAIL_DENIED，不能证明最终因果，需补反例并修正实现或事实口径。 |
| 双入口拒收 | 503 字段、Retry-After、HALF_OPEN 省略恢复秒数；run-stream 无 SSE 假流、零任务副作用。 | `DaDegradationAdmissionTest`。 |
| 校验顺序 | 参数、媒体、team 错误先于门；无效请求不占探针。 | `DaDegradationAdmissionTest` 覆盖空白输入和 team 通道不可用；媒体不存在、媒体白名单拒绝、team 不存在需补前置拒绝矩阵。 |
| 在途控制面 | OPEN 不阻断状态、重连、取消、inject、answer、resume。 | `DaDegradationAdmissionTest` 直接覆盖状态、列表和重连；OPEN 下取消、inject、answer、resume 需补聚焦验证。 |
| 断流与慢消费者 | 断流和慢订阅不独立记账，最终只按真实终态分类一次。 | `DaDegradationAdmissionTest` 覆盖重连不新增样本；慢消费者下终态分类不变需补聚焦验证。 |
| 半开单探针 | 并发仅一个真实任务准入，其余 503/HALF_OPEN。 | `DegradationGateTest`、`DaDegradationAdmissionTest`。 |
| 故障代际 | 窗口内旧任务故障先记账则回 OPEN，探针迟到成功不得关门；当前探针过期落定只释放名额。 | `DegradationGateTest`；过期豁免按 version-scope §5.1.4 验收，不得覆盖窗口内新故障的否决语义。 |
| 探针健康/待答 | 四类健康终态可关门；awaiting 不记样本；最终终态只记一次。 | `DegradationGateTest` 覆盖 COMPLETED、BUSINESS_FAILURE 和 awaiting；INPUT_REJECTED、GUARDRAIL_DENIED 只有记账属性证据，探针关门需补测试。 |
| 探针无结论 | 取消和 NOT_ADMITTED 实际落定后释放；认领/注册异常窗口不泄漏。 | `DegradationGateTest`、`DaProbeSlotHandleTest`。 |
| 过期当前探针 | 过期落定不记账但释放名额，后续真实任务可重新探测。 | `DegradationGateTest`。 |
| 健康与认证 | 三态 200，计数确定，401 认证错误，/health 和 /ready 不因门新增 down。 | `DaHealthDetailE2ETest`、`DaHealthDetailHalfOpenE2ETest`。 |
| 多实例 | 不同对象独立窗口、探针和 instanceId，他实例事实忽略。 | `DegradationGateTest`；不是真实多进程证明。 |

### 7.2 场景旅程验收

| 场景 | Given | When | Then |
|------|-------|------|------|
| 小样本故障 | minSampleSize=20，窗口已有 19 个系统失败。 | 新任务到达。 | 门仍 CLOSED；第 20 个有效失败记账后，后续任务才 OPEN 拒收。 |
| 残窗异常率上升 | 窗口曾有足量健康和失败样本，比例未达标。 | 健康样本随时间过期，准入或诊断推进。 | 残窗若同时满足样本和比例则 OPEN；不得误以为只有新故障才开门。 |
| OPEN 无流量 | 门已 OPEN，旧样本全部过期。 | 等待且不发新任务。 | 不自动 CLOSED；静默期到后只能 HALF_OPEN，不生成内部假探针。 |
| 半开竞争 | 门为 HALF_OPEN。 | 多个有效新任务并发。 | 一个获得 claimId 并真实执行，其余 503 且无恢复秒数。 |
| 旧任务故障竞争 | 半开探针在途，旧任务发生未过期系统失败。 | 旧任务先记账，探针后成功。 | 代际递增并回 OPEN；迟到成功不得关门。 |
| 探针待答 | 当前探针代际未变。 | 任务进入 awaiting_answer。 | CLOSED、清窗口、释放名额、不记样本；续跑最终终态再唯一记账。 |
| 探针取消 | 当前探针在途。 | 用户取消，事实随后落定。 | 请求到达时不提前放第二探针；落定后保持 HALF_OPEN 并释放。 |
| 过期探针落定 | 单调水位已越过该探针 terminalAt 所属窗口。 | 当前探针事实到达。 | 不增加样本或代际，只释放名额，允许下一真实探针。 |
| 断流重连 | 已受理任务持续执行。 | SSE 断开并重连，最终成功或失败。 | 传输不新增样本；只按真实终态分类一次。 |
| 新进程启动 | 旧进程曾 OPEN，部署监控仍保留原故障告警。 | 启动新进程并观察进程启动事件。 | 新 instanceId、CLOSED 空窗口只能记为新进程初始化；监控必须关联启动事件保留旧故障上下文，不得仅凭重启清除原告警或伪造健康探针记录。 |

### 7.3 错误表面验收

| 错误场景 | 验收标准 |
|----------|----------|
| OPEN 新任务 | 503 `ENGINE_DEGRADED`，带 state、instanceId、retryAfterSeconds 和一致 Retry-After。 |
| HALF_OPEN 探针占用 | 503 同形错误，但省略 retryAfterSeconds 和 Retry-After。 |
| 参数/媒体/team 非法 | 既有 4xx 优先，不占探针、不创建任务、不进入窗口。 |
| 执行池饱和 | 503 `ENGINE_BUSY`；已认领探针按 NOT_ADMITTED 或异常所有权路径释放。 |
| 探针计入类系统失败（事实未过期） | 调用方收到真实任务失败，不暴露探针身份；门回 OPEN 并重算静默期。业务/输入/护栏失败可按当前健康规则关门，当前探针的过期事实只释放名额。 |
| 健康详情匿名访问 | 认证开启时 401 `{error:"unauthorized"}`；默认关闭时不得误写为必然 401。 |
| 非法配置 | 构造失败并指出完整键名；window<=0、minSample=0、阈值越界/NaN、quiet<0 均不得静默替换。 |
| 无结构化证据的未知失败 | 无 denied 时为 INFRASTRUCTURE_FAILURE；仅有 denied 时当前为 GUARDRAIL_DENIED，但最终因果未证明，不能写成完整满足分类要求。 |

### 7.4 最小测试套件建议

| 测试分组 | 覆盖重点 |
|----------|----------|
| `DegradationGatePropertiesTest` | 默认值、非法值、合法边界和异常键名。 |
| `DegradationGateTest` | 窗口、阈值、九类记账、三态、探针、代际、乱序、过期落定和实例隔离。 |
| `DaProbeSlotHandleTest` | claim/bind/commit/close、重复关闭和并发关闭。 |
| `DaDegradationAdmissionTest` | 双入口 503、零副作用、校验顺序、控制面、多实例和资源拒绝释放。 |
| `DaDegradationDeliveryDefaultTest` | DA 键缺席默认开、显式 false 优先。 |
| `DaHealthDetailE2ETest`、`DaHealthDetailHalfOpenE2ETest` | 三态诊断、认证、探活不变和真实健康关门。 |
| 业务终态与翻译聚焦测试 | 直接/包装 business_failure、系统错误优先、真实 interrupt 和 late commit error 不错误改分类。 |
| 新增聚焦测试 | 健康样本过期后残窗开门、仅有 denied 的未知最终失败因果反例、OPEN 下取消/inject/answer/resume、完整媒体与 team 前置校验矩阵、慢消费者、INPUT_REJECTED/GUARDRAIL_DENIED 探针关门、畸形 enabled 绑定。 |
| 部署补充验证 | 真实 Spring 非法绑定启动、真实模型故障注入、真实多进程实例亲和与告警联动；重启验收必须核对进程启动事件与原故障告警关联，不得只检查新实例 CLOSED。未执行时标记未验证。 |

本文编写阶段完成的是源码和测试资产核验，未重新执行上述测试，也未执行真实模型故障演练或真实多进程部署验证。
