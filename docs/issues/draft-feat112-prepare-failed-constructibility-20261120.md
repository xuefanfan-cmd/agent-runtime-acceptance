# FEAT-112：`prepare_failed` 分类实现差距（归 NOT_ADMITTED 而非 INFRASTRUCTURE_FAILURE）；SIT 构造方式另行跟踪

日期：2026-11-20
性质：实现差距（分类错误，静态确认）+ 测试承接事项（SIT 注入方式），接收方 solution/DA 开发；承接方式三选一请开发与特性设计定。
提单版（2026-11-20 r2，按开发核查答复 §3.6 修订：分类归属不再请裁决——DA L2 `3b1283ef` 第 89 行已定 `prepare_failed` 归 `INFRASTRUCTURE_FAILURE`，现实现与之不符是实现差距；“仅 Error/OOM 可达”表述更正）。开发已判定为真 Issue。

## 结论摘要

两个独立问题，分开跟踪，不得以 B 关闭 A：

**问题 A（实现差距，产品侧修）**：`DaDegradationSupport.classify` 对任意非空 `admissionReason` 一律返回 `NOT_ADMITTED`（开发核查定位 `DaDegradationSupport.java:265-267`，测试侧在 `96c23d4ea` 源码复核一致），未区分正常容量拒绝与 `prepare_failed`；意外 prepare 故障因此不计系统异常分子分母。DA L2 `3b1283ef` 第 89 行已明确：建档后的缓冲、属主存储等意外故障应计系统异常，`prepare_failed` 归 `INFRASTRUCTURE_FAILURE`——分类口径无需裁决，当前实现与 L2 不符。

**问题 B（测试承接）**：prepare 保护区（`DaCompatController.java:1608-1613`）只含内存属主绑定、翻译器与执行体构造，捕获 `RuntimeException | Error`；未发现正常 HTTP 输入、PG、Redis、媒体或执行池状态能确定性落入该保护区（执行池饱和与 shutdown 走独立的 `RejectedExecutionException` 路径）。SIT 承接方式三选一：测试 profile 注入点（同 `cancel-drain-delay-ms` 先例）/ 开发侧组件测试承接并在 L2 登记 / 明确 SIT 豁免并在 testplan 注明。

## 场景说明与影响面

按 L2 目标，任务建档后、执行前的基础设施故障应计入降级分子（它是“系统不健康”的证据）。测试尝试过的构造：停数据面（在建档前即拒绝，走校验路径）、停 Redis（走 E15 的恢复失败路径，分类 INFRASTRUCTURE_FAILURE 但发生在执行中而非 prepare 段）、执行池饱和（E8/E12，归 NOT_ADMITTED/既有 ENGINE_BUSY）。没有一种外部手段落入 prepare 段。影响：问题 A 使意外 prepare 故障被错计为 NOT_ADMITTED（漏计分子分母，降级门对这类系统不健康无感知）；问题 B 使该路径暂无 SIT 承接。

## 三面核查（Pre-flight 三问）

1. 对外契约：无任何入口参数/依赖状态组合可从外部使请求失败于 prepare 段（上列构造全部落入其它路径，各有运行证据）。
2. 日志：全轮 `sit-logs` 无 prepare 段失败日志（路径未被触发）。
3. 数据：无。
主张性质说明（I-M2）：“不可构造”是对尝试集合 + 代码走读的归纳，属推断而非穷尽证明——这正是请开发确认的原因。

## 代码层定位（solution `96c23d4ea`，静态走读）

见上问题 A/B 的坐标。更正旧稿表述：不是“仅 Error/OOM 可达”，而是“保护区捕获 `RuntimeException | Error`，但未发现当前保护区内现实可外部构造的 RuntimeException”。本项为静态确认，未动态复现 `prepare_failed`。

## 判据与历史

特性档 `05af9cc3` §5.1.3 第 254 行；DA L2 §1.4 目标。2026-11-17 登记草稿，本轮新基线复核结论不变。

## 修复与承接请求、验收

1. 问题 A：修正 `classify`，`prepare_failed` 场合归 `INFRASTRUCTURE_FAILURE`（计分子分母）。
2. 问题 B：三选一定承接方式；选注入点则测试仓补场景。
- 验收：问题 A 修复后，经问题 B 选定的方式验证 prepare 段故障计入窗口；在此之前覆盖登记保持“待承接方式确定”。

## 预答可能的反问

“测试没构造出来不等于不存在”——同意，问题 B 的“不可构造”是归纳不是穷尽证明，欢迎指出遗漏的构造路径，有即补测；但问题 A 的分类差距是静态确认的实现事实，与可构造性无关，不以 B 为由关闭 A（开发核查 §4.5 同此口径）。
