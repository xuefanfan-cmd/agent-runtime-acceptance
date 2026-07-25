---
kind: appendix
title: FEAT-003（智能体任务状态缓存）扩展覆盖建议清单
feature: FEAT-003
适用环境: openjiuwen
创建日期: 2026-07-22
tags: [workflowagent, redis, feat-003, appendix]
---

# FEAT-003 扩展覆盖建议（附录）

> **性质**：本文件**不是 TC 用例**，是 FEAT-003 各机制 TC 在「正常逻辑范围内」的可选扩展建议清单，
> 以及明确**不建用例的边界**（无 workflow 业务面处置 / 规格不承诺项豁免 / 组织原则排除项），供后续按
> 优先级落地。核心机制覆盖以 [middleware-activation](FEAT-003-middleware-activation.md) /
> [state-cache-persistence](FEAT-003-state-cache-persistence.md) /
> [state-cache-semantics](FEAT-003-state-cache-semantics.md) /
> [config-variant](FEAT-003-config-variant.md) /
> [cluster-config-semantics](FEAT-003-cluster-config-semantics.md) /
> [customer-adapter-spi](FEAT-003-customer-adapter-spi.md) 六个 TC 为准。

## 1. state-cache-persistence 扩展：进程重启后快照可查（跨 JVM 持久化）

- **归属机制 TC**：[state-cache-persistence](FEAT-003-state-cache-persistence.md)（§4.1 恢复边界）。
- **扩展内容**：当前子断言 B 为**同进程续轮**恢复；可补齐**跨 JVM 恢复**——完成一笔任务后重启 agent 进程，getTask 仍返一致快照（Redis 持久化语义）。
- **落点**：`ExpenseReviewRedisStateCacheTest` 追加方法（或 [state-cache-semantics](FEAT-003-state-cache-semantics.md) 扩展），经 `SutStack` 重启 agent。
- **解锁条件**：`SutStack` 支持同栈重启（stop→start）；getTask 在重启后可达。
- **优先级**：P2（增强项；同进程续轮已证恢复机制正确，跨 JVM 是持久化语义的增强证据）。

## 2. middleware-activation 扩展：cluster + 认证组合

- **归属机制 TC**：[middleware-activation](FEAT-003-middleware-activation.md)（§2 集群接入 MUST / 密码脱敏 MUST）。
- **扩展内容**：当前激活载体矩阵为 near-standalone(+认证) / near-cluster(无认证) / far-standalone；可补齐 **cluster + 认证**组合，验证集群拓扑下密码脱敏仍成立。
- **落点**：`ExpenseReviewRedisClusterAcceptanceTest` 追加 `--requirepass` fixture（或新建组合叶子）。
- **解锁条件**：grokzen cluster fixture 支持密码配置（或换支持 AUTH 的 cluster 镜像）。
- **优先级**：P3（脱敏已由 standalone 载体证明，cluster+认证是组合覆盖广度）。

## 3. state-cache-semantics 扩展：远端 versatile 侧的 TTL/隔离/故障语义

- **归属机制 TC**：[state-cache-semantics](FEAT-003-state-cache-semantics.md)（TTL/故障/隔离）。
- **扩展内容**：当前三条独立语义在**近端 workflow** 载体验证；远端 versatile（plan-agent checkpointer + adapter TaskStore）是否需补齐同型 TTL/隔离/故障语义。
- **落点**：若需，归入 [FEAT-002 versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) 统一设计（远端控制器的 Redis 状态语义与其故障语义同源）。
- **解锁条件**：远端 versatile 侧暴露 TTL 配置入口 + 键空间可探；与 FEAT-002 档协调避免重复。
- **优先级**：P3（同一 runtime 承接，近端已证机制正确；远端补齐是载体覆盖广度）。
- **设计档指引**：FEAT-003 §2.2 明示「若后续 Versatile 侧需要挂起/恢复边界的 Redis 语义验证，归入 FEAT-002 档的 Versatile 设计统一考虑」。

## 4. 无 workflow 业务面的规格点 — 处置结论（不进扩展清单）

> 这些规格点**无 workflow 业务触发路径**，归 contract/component 层或既有用例复验，不在本档建 SIT 用例。

| 规格点（feature 出处） | 处置 | 同型先例 |
|---|---|---|
| 统一 Redis 操作接口最小命令面（§2/§3 SPI/§5.1.2：文本/二进制、TTL、setnx、mget、scanIter、跨 slot） | 归 **contract/component 层**：SPI 方法面无业务触发路径，以 test-scope runtime artifact 验证 | `react_travel/Feat003RedisStandaloneBehaviorTest` / `Feat003RedisClusterAndSwitchTest` |
| 配置错误可诊断（§2 SHOULD/§3：缺 nodes / 非法 type / 缺 ref / 非法 TTL） | 归 **component 层**（非法输入/错误配置不进 SIT，组织原则 2） | `react_travel/Feat003RedisConfigurationAndDiagnosticsTest` |
| 连接资源生命周期（§2 SHOULD：初始化/释放不泄漏） | runtime 级语义，无 workflow 业务差异；由既有 runtime 级用例复验 | — |
| reset conversation 清理状态 | ReAct 会话能力，workflow agent 无此业务面，不适用 | — |
| DeepAgent Todolist 快照 / 自治持久化 / 任务级隔离 key / 文件存储排除（§2 新增 4 条 MUST/§4.2） | DeepAgent 专属能力，归 deepagent 侧设计 | — |

## 5. 规格不承诺项 — 验收豁免（feature §5.2，不建 SIT）

| 不承诺项 | 豁免说明 |
|---|---|
| 真实客户 JAR 现场联调 / 客户监控平台 / 私有安全协议 | 🏷 customer-site：真实 JAR 因合规不可外传；内部以**模拟客户适配组件**覆盖（[customer-adapter-spi](FEAT-003-customer-adapter-spi.md)），最终现场联调在客户环境完成 |
| Redis 部署/容灾/备份、全命令暴露、业务数据迁移、key schema 稳定、零重启切换、多租户 namespace、跨 runtime 自动 keyspace 隔离、多数据源路由 | 属部署与系统工程责任，不建 SIT 用例；键空间断言遵守「key schema 非稳定契约」弱形态约定 |
| 运行时故障自动降级（fail-open / fail-close / 内存降级） | 不验证降级本身；以「明确失败/可诊断」反向验收（[state-cache-semantics](FEAT-003-state-cache-semantics.md) 子断言 B） |

## 6. 不建扩展（明示边界 / 组织原则排除项）

- **非法输入 / 错误配置 / SDK 错误码**：缺 nodes、非法 type、缺 ref、非法 TTL 等配置校验，及 SPI 方法面错误码——归 component/contract 层（组织原则 2）。SIT 不建非法输入用例，也不断言具体错误码。
- **SSE wire 帧格式 / webhook**：传输层抽象，不建独立 TC（组织原则 3）。
- **key schema 硬编码断言**：key schema 非外部稳定契约，键空间断言取弱形态（存在性 + taskId 特征），不硬编码前缀（组织原则 3）。
- **数据迁移 / 零重启切换**：属部署责任（§5.2 豁免）；切换用例只断「改配置不改业务」，不断旧数据可读。

## 7. 解锁路线图（按依赖排序）

| 阶段 | 解锁项 | 解锁后可启用 | 优先级 |
|---|---|---|---|
| 1 | `SutStack` 同栈重启（stop→start）支持 | 扩展 1 跨 JVM 快照可查；[state-cache-semantics](FEAT-003-state-cache-semantics.md) 故障用例已用 `stop()` | P2 |
| 2 | agent 进程类路径追加机制（`loader.path`/`-cp`）确认 | [customer-adapter-spi](FEAT-003-customer-adapter-spi.md) 3 方法 ⬜→✅ | P1 |
| 3 | grokzen cluster fixture 支持密码配置 | 扩展 2 cluster+认证组合 | P3 |
| 4 | 远端 versatile TTL/键空间入口 + FEAT-002 档协调 | 扩展 3 远端侧状态语义（归 FEAT-002） | P3 |
