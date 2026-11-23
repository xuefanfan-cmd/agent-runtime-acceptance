# FEAT-112：降级门可观测性增强建议（合并原第 3、4 项；非缺陷、不关联验收放行）

日期：2026-11-20
性质：可观测性增强建议，接收方 solution/DA 开发（ext + DA）。
本稿按开发核查答复 `feat112-eight-issue-review-20261120.md` §3.3/§3.4 与 §4.2 的建议，由原两份草稿（`draft-feat112-gate-observability-logs-20261120.md`、`draft-feat112-failure-generation-observability-20261120.md`）合并改写：撤回“特性不可观测”“告警无从挂接”“MUST 验收阻塞”等表述。原两稿保留作底稿，内容以本稿为准。

## 增强请求（按开发认可的范围收敛）

1. **状态迁移事件**：CLOSED→OPEN、OPEN→HALF_OPEN、HALF_OPEN→CLOSED 打低频 INFO（instanceId、前后状态、触发原因、触发事实的 taskId/terminalAt）。频率为每实例每降级周期个位数条，非热点路径。
2. **过期事实豁免**：被 §5.1.4 豁免的事实给出明确处理结果事件（当前完全无输出，测试只能经时钟推算间接证明豁免发生）。
3. **终态事实 DEBUG 行按需补字段**：`terminal fact taskId= class= code=` 增补 `terminalAt`、当前代际、是否探针——不要求无差别升 INFO（采纳开发意见）。
4. **代际值**（可选、低优先级）：在上述迁移事件或诊断面给出 `failureGeneration`，便于 C4 直接断言与排障；其行为效果已由 C5、C8 经外部行为验证，本项纯增强。

## 价值论证（排障与审计，不是契约缺口）

- 产线：当前只能轮询 `/v1/health/detail` 拿即时快照（特性档 §3.2 的告警挂接点，够用但无历史）；一次降级事件的全过程（何时开门、因哪几条失败、探针几次、有无过期豁免）事后无法从日志还原、不可审计。
- 测试：B7 判定竞态（2026-11-19）后，测试已不得不把 DEBUG 行 `terminal fact taskId=` 当同步点（测试仓 `OrderedControlRun.awaitFact`）——观测点缺失正在向测试实现渗透成本；第 2、3 条落地后，B6/B7/E9 的激活证明可从“时钟推算 + DEBUG 行”改为消费正式事件。
- 字段若落地，请在 DA L2 声明为可依赖的观测契约（测试据此把日志判据挂到声明字段上，避免实现微调即误判）。

## 已确认不主张的内容（与开发核查对齐）

- 不主张违反 FEAT-112 契约：特性档要求的外部可观察量（状态、样本量、异常数、异常率、剩余等待、探针占用、实例标识）诊断面已全部提供；告警挂接点特性档明确为 health 字段。
- 不主张门状态/事实入库：契约明确重启不延续旧窗口与探针状态。
- 不与 FEAT-112 验收放行关联；C4 的“部分承接”是测试覆盖登记口径，不反向定义产品需求。

## 代码参考

`DegradationGate.java`（ext，`96c23d4ea`）：迁移处 `onTerminalFact`/`tryAdmit` 无日志，`failureGeneration` 为私有字段、`snapshot()` 不输出；`DaDegradationSupport.java:208` 的 DEBUG 行无 `terminalAt`。
