# FEAT-112：降级门内部事件无日志，终态事实日志缺终态时刻（特性不可观测）

日期：2026-11-20
性质：特性不可观测 Issue，接收方 solution/DA 开发（ext + DA）。
草稿（checklist I-M7）。来源：`feat112/issues-unobservable-20261117.md` Issue 1，本稿按 Issue 模板补齐并在新基线复核。

## 结论摘要

降级门的状态迁移（CLOSED→OPEN→HALF_OPEN→CLOSED）、探针认领/释放、过期事实豁免这些特性档点名的行为，在对外契约、进程日志、持久化数据三面都没有观测点；逐任务的终态事实日志是 DEBUG 且不带终态时刻（`terminalAt`）。按“对外契约 → 日志 → 持久化数据”的 SIT 取证阶梯逐级查完仍不可观测，依口径提本 Issue。

## 场景说明

部署方在产线发现某实例进入降级（503 `ENGINE_DEGRADED`）。要回答“什么时候开的门、因哪几条失败、探针何时认领/落定、有没有事实因过期被豁免”，当前只能拿到 `/v1/health/detail` 的即时快照——没有任何历史轨迹可查。测试侧同理：B6/B7/E9 的激活证明（某条事实确实乱序/过期）只能靠时钟推算，不能从被测对象自己的输出直接判定。

## 影响面

- 测试：B6、B7、E9 只能经时钟回拨间接构造，激活证据是推算而非观测；2026-11-19 的 B7 判定竞态（测试读门早于门处理终态）正是因为唯一可等待的信号 `terminal fact` 是 DEBUG 日志，还不带时刻。
- 部署：无法从日志还原状态迁移序列，降级事件不可审计、告警无从挂接（特性档 §3.2 "部署监控门状态"的落点只有轮询快照）。
- 不阻塞单场景判定（测试已用间接手段承接），但属于特性档点名行为的观测缺失。

## 实测证据与三面核查（Pre-flight 三问即本节）

1. 对外契约全字段扫描：`/v1/health/detail` 响应全文（结果包任一 `*health*` 证据，如 `FEAT-112-F4-…/013.json`）仅含 `status`、`degradationGate{enabled,state,instanceId,sampleCount,systemFailureCount,systemFailureRate,probeInFlight,retryAfterSeconds}`、`modelConfig{…}`、`tasks{…}`、`guardrailLedger{…}`、`hookReaders{…}`——无窗口边界、无终态时刻、无迁移历史、无代际。
2. 日志跨通道核对：全轮 `target/sit-logs/*/stdout.log` 中与门相关的只有启动 INFO（`da degradation gate wired enabled= instanceId=`）与 DEBUG `terminal fact taskId= class= code=`（不带 terminalAt）；状态迁移、探针认领/释放、过期豁免零日志。
3. 持久化数据：门状态与窗口样本只在内存（代码定位见下），Redis/PostgreSQL 形态实跑（E15、E11、F3、F4）期间两库中无任何门相关键/表写入。

## 代码层定位（core/solution 基线 `f777aa244` / `96c23d4ea`，静态走读 + 运行验证）

- `common/agent-runtime-ext-java/.../adapters/agentcore/ext/degradation/DegradationGate.java`：状态与窗口样本全部为内存字段（`windowAnchor`、`samples`、`failureGeneration`），迁移处（`onTerminalFact`、`tryAdmit`）无日志调用，全类仅两条 DEBUG。
- `common/agents/deepanalyze-java/engine/.../da/compat/DaDegradationSupport.java:208`：`LOGGER.debug("terminal fact taskId={} class={} code={}")`——发生在门处理之后，但无 `terminalAt`、无门状态结果，且级别为 DEBUG。

## 判据

特性档 `05af9cc3`：§5.1.2（窗口按终态时刻统计）、§5.1.4（过期事实豁免）、§3.2（部署监控门状态）、验收出口 4、15。上述行为是 MUST 语义的组成部分，而其发生与否当前不可从外部证实。

## 与历史 issue 的关系

2026-11-17 起以草稿登记；本轮（2026-11-19/20 Linux 实跑）在新基线复核仍成立，并新增一条佐证：测试为规避判定竞态，已不得不依赖这条 DEBUG 日志行作同步点（测试仓 `OrderedControlRun.awaitFact`）——观测点缺失已经在向测试实现渗透成本。

## 复现条件

任意部署形态起载体（`cdb5c93e…`），驱动若干失败任务令门迁移，然后：`grep -E "degradation|terminal fact" <进程日志>`——仅见启动行与（DEBUG 级别开启时的）terminal fact 行；对照 `/v1/health/detail` 快照确认迁移确已发生。

## 预答可能的反问

- “DEBUG 日志已经有 terminal fact，不够吗？”——级别（产线默认不开 DEBUG）、内容（无 terminalAt，无法核对 §5.1.2 的按终态时刻统计）、覆盖（只有逐任务事实，没有门自身的迁移/认领/豁免事件）三点都不够。
- “加日志影响性能？”——状态迁移每实例每次降级周期个位数条，探针认领/释放同量级；非热点路径。

## 修复建议与验收

- 建议：迁移打 INFO（instanceId、前后状态、触发原因、触发事实的 taskId/terminalAt）；`terminal fact` 行升 INFO 并补 `terminalAt` 与处理结果；过期豁免单独一条。字段在 DA L2 声明为可依赖的观测契约。
- 验收：B6/B7/E9 的激活证明改为直接消费日志事件（不再时钟推算）；测试仓 `OrderedControlRun.awaitFact` 改读正式事件行。
