# spring-ai-ascend 集成验收测试方案

## 1. 方法定位

测试仓采用**架构感知的外部集成验收**方法：规格层保持 SUT 无关，以外部行为作为 PASS/FAIL 的主判据；适配层记录 spring-ai-ascend 的特性—模块协同映射和可观测证据，用于指导覆盖分析和解释测试结果。测试可以知道内部模块职责，但不以私有实现细节作为验收对象。

换句话说，本方案不是“不串联模块”，而是**不把模块调用关系本身写成验收对象**。测试设计阶段需要理解特性背后应协同的模块；测试判定阶段则以外部行为、公开观测面、trace、metric、audit、状态流转等证据作为依据。

## 2. 验收目标

面向 spring-ai-ascend 这类企业级 Agent Runtime，集成验收关注：

1. 外部调用方能否通过稳定契约触发运行时能力。
2. 一个特性背后的多个模块是否形成可验证协同。
3. 关键架构承诺是否体现为外部可观测行为。
4. 系统在异常、重试、租户越权、数据面压力等场景下是否保持安全和可恢复。
5. 验收规格是否避免与任一 SUT 实现相互拟合。

## 3. 分层边界

### 3.1 规格层：SUT 无关

`specs/` 下的 AT 规格必须保持 SUT 无关：

- 不引用 spring-ai-ascend 的模块名、类名、包名。
- 不引用 spring-ai-ascend 的 ADR、Rule、CLAUDE.md、ARCHITECTURE.md。
- 不断言私有方法、内部类、数据库表、内部队列。
- 只描述外部可观测行为、抽象能力、判定准则和报告结构。

### 3.2 适配层：SUT 感知

`sut/adapters/spring-ai-ascend/` 可以记录 SUT 特定信息，包括：

- 抽象能力到具体端点的映射。
- 抽象状态到 SUT 状态的映射。
- 特性到模块协同的覆盖映射。
- 可观测证据到 trace / metric / audit / endpoint 的映射。

适配层可以知道 spring-ai-ascend 的模块职责，但仍不得要求测试导入 SUT 代码或依赖私有实现。

### 3.3 判定层：外部行为为主，公开观测证据辅助

PASS / FAIL 主要依据：

- HTTP 状态码、响应体、响应时延。
- run 状态流转。
- 公开查询接口。
- 公开 metrics。
- 公开 trace/span。
- 公开 audit/event/report。

判定时区分两类结论：

1. **Conformance verdict**：是否满足 AT 规格定义的外部行为，作为 PASS / FAIL / INCONCLUSIVE 的主判据。
2. **Coverage explanation**：该特性背后的 spring-ai-ascend 模块协同是否有足够公开证据支撑，用于解释覆盖质量和定位 evidence gap。

若 AT 规格要求的外部行为无法观测，报告 `INCONCLUSIVE`。若外部行为满足但缺少足够证据证明某个预期模块参与，不应把规格层 PASS 强行改成 FAIL；应在覆盖分析中标记 evidence gap，或在相关 AT 明确把该公开观测面纳入 required evidence 后再进入判定。

## 4. 推荐目录增强

在现有结构基础上，增加：

```text
sut/adapters/spring-ai-ascend/
  adapter.yaml
  feature-coverage.yaml
  observability-map.yaml
```

职责：

| 文件 | 作用 | 是否 SUT 特定 |
|---|---|---|
| `specs/AT-xxx.md` | 通用验收规格 | 否 |
| `sut/sut-contract.md` | 抽象能力契约 | 否 |
| `adapter.yaml` | 抽象能力到 SUT 接口映射 | 是 |
| `feature-coverage.yaml` | 特性到模块协同与证据映射 | 是 |
| `observability-map.yaml` | trace / metric / audit 观测点映射 | 是 |

## 5. 特性驱动的模块协同验收

验收用例不以 `client -> service -> bus -> engine` 这种内部调用链为对象，而以特性为对象。

示例：取消运行。

外部特性：

1. 创建一个长任务。
2. 在任务运行中请求取消。
3. 查询状态变化。
4. 在数据面压力下重复验证取消接口仍可用。

预期协同：

- client/API 接收外部请求。
- middleware 执行认证、租户、策略与 trace 传播。
- service 管理 run 生命周期。
- bus 承载 control 命令。
- execution-engine 响应取消并推进状态。

可接受证据：

- cancel 返回 2xx 或明确的 4xx 非 404。
- poll 最终观察到 `CANCELLED` 或明确终态。
- create / cancel / poll 具有同一 runId / traceId 关联。
- control-plane 相关 metric 或 audit event 可见。

不可接受证据：

- 某个 Java 私有方法被调用。
- 某个内部类被实例化。
- 某张内部表被写入。
- 某个内部包路径出现在调用栈。

## 6. 判定原则

| 判定 | 含义 |
|---|---|
| PASS | AT 规格定义的外部行为满足要求；覆盖分析可进一步说明模块协同证据是否充分。 |
| FAIL | AT 规格定义的外部行为违反要求，或公开证据显示安全边界 / 外部契约被破坏。 |
| INCONCLUSIVE | SUT 未暴露足够外部行为面，无法诚实判断 AT 规格要求。 |
| EVIDENCE_GAP | 覆盖解释标签：外部行为可能已满足，但缺少公开观测证据证明某个预期模块协同。它不是顶层 verdict。 |

`INCONCLUSIVE` 不等于 PASS，也不等于 FAIL。它用于记录验收面不足，避免测试仓为了得到二值结果而侵入 SUT 实现。`EVIDENCE_GAP` 用于覆盖解释，不改变规格层 verdict，除非对应 AT 已经把该观测面定义为必需条件。

## 7. 与开发内部测试的分工

测试仓负责：

- 外部行为验收。
- 特性级跨模块协同验证。
- 可观测证据采集与覆盖解释。
- 多租户、幂等、取消、异步边界、控制面活性等运行时语义验收。

开发仓内部测试负责：

- ArchUnit / module dependency 检查。
- SPI 包、module-metadata、DFX、contract catalog 一致性。
- 私有类、私有方法、内部状态机代码级正确性。
- Gate / enforcer / schema 级规则执行。
- 数据库表结构与内部实现细节。

## 8. 后续扩展方向

在现有 AT-001 至 AT-005 基础上，建议优先补充：

1. Idempotent Submission。
2. Terminal State Absorption。
3. Trace and Audit Correlation。
4. Engine Mode Contract Consistency。
5. Suspend / Resume External Event Flow。
6. Control / Data / Rhythm Separation。
7. Middleware Policy Enforcement。
8. Evolve Feedback Loop。

这些规格应继续保持 specs 层 SUT 无关；spring-ai-ascend 的模块协同解释放入 adapter 层覆盖文件。
