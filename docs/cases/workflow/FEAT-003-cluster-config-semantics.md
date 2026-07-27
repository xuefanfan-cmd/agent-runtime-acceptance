---
用例编号: FEAT-003-cluster-config-semantics
测试标题: Redis 集群配置语义机制——agent-runtime-java 的单机↔集群切换与 cluster 忽略 database
story: S1
优先级: P1
自动化状态: TOBUILD（`ExpenseReviewRedisClusterStateCacheTest` 2 方法待新建；复用 grokzen fixture 与 bridge-IP 注入模式）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, cluster, feat-003]
---

# FEAT-003-cluster-config-semantics — Redis 集群配置语义机制

> **机制一句话**：Redis 集群配置语义是 **agent-runtime-java 的拓扑适配机制**（FEAT-003 §2 MUST / §3 /
> §5.1.1）——在 [middleware-activation](FEAT-003-middleware-activation.md) 的 cluster 激活（近端 cluster
> 载体②）之上，验证两条配置语义：**单机↔集群切换只改配置**（同一 agent 仅改 endpoint type+nodes 重启切
> cluster，业务代码不变，§2 MUST）；**集群忽略 database**（cluster + database=N 启动不失败，日志含
> `databaseIgnored=N` 非敏感提示，§3/§5.1.1）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 单机↔集群配置切换不改业务（§2 MUST）+ cluster 忽略 standalone database 并发诊断提示（§3/§5.1.1） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review`，方法级自管栈 + 类内 grokzen cluster fixture） |
| **测试数据层** | 载体 agent 的实现逻辑 | 同一 agent 两阶段跑业务（切换用例）/ cluster + database=3 启动（忽略用例）——驱动拓扑适配的载体夹具 |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§2 集群接入 MUST / 配置切换 MUST；§4.1 同一接口；§3/§5.1.1 database ignored。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §2 MUST：原生集群接入、同一接口；单机↔集群配置切换只改配置。
- FEAT-003 §3 / §5.1.1：集群忽略 standalone database + 非敏感 ignored 提示。

## 前置条件

1. 被测 jar 就绪（`expense-review`）。
2. `-Dtest.env=openjiuwen`（切换用例两阶段业务需 LLM；忽略用例 hermetic 启动门禁无需 LLM）。
3. Docker（Testcontainers 单机 + `grokzen/redis-cluster:6.2.14`）。

## 测试数据

- 切换用例：同一 agent jar——阶段一 Testcontainers 单机，阶段二仅改 endpoint type+nodes 指向 Testcontainers cluster。
- 忽略用例：Testcontainers cluster + `database=3`。

## 集群配置参数表（同一载体，两类集群配置语义）

| 方法 | 配置语义 | 注入/动作 | 预期 |
|---|---|---|---|
| `standaloneToClusterSwitchWithoutBusinessChange` | 单机↔集群切换 | 两阶段仅改 type+nodes 重启 | 两阶段业务均成功，策略日志随配置切换 |
| `clusterIgnoresStandaloneDatabaseWithDiagnostic` | cluster 忽略 database | cluster + database=3 启动 | 不失败，日志含 `databaseIgnored=3`，读写可达 |

## 测试步骤

> 两类集群配置语义合并为一个测试类（`ExpenseReviewRedisClusterStateCacheTest`）的两个 `@Test` 方法；复用 grokzen fixture 与 bridge-IP 注入模式。

| # | 动作 | 预期 |
|---|------|------|
| 1 | **切换**：阶段一 Testcontainers 单机跑一笔业务 → 仅改 endpoint type+nodes 重启切 cluster → 阶段二再跑一笔 | 两阶段业务均成功；策略日志随配置切换（standalone→cluster）；测试/业务代码不变；不要求迁移旧数据 |
| 2 | **忽略**：Testcontainers cluster + `database=3` 启动 agent 并就绪 | 不因 database 启动失败；增量日志精确含 `databaseIgnored=3`；Redis 读写可达 |

## 预期结果（机制断言）

### A — 单机↔集群配置切换不改业务（§2 MUST）
- **Given**：同一 agent jar，阶段一单机、阶段二仅改 endpoint type+nodes 指向 cluster。
- **When**：阶段一跑一笔业务 → 重启切 cluster → 阶段二再跑一笔。
- **Then**：两阶段业务均成功；策略日志随配置切换（standalone→cluster 诊断行变化）；测试/业务代码不变；不要求迁移旧数据。
- **PASS**：切换只改配置业务不变。**FAIL**：切换需改业务代码 / 阶段失败（切换机制失效）。

### B — 集群忽略 standalone database + 诊断提示（§3/§5.1.1）
- **Given**：Testcontainers cluster + `database=3`。
- **When**：启动 agent 并就绪。
- **Then**：不因 database 启动失败；增量日志精确含 `databaseIgnored=3`（非敏感提示）；Redis 读写可达。
- **PASS**：cluster 忽略 database 且可诊断。**FAIL**：因 database 启动失败 / 无忽略提示（拓扑适配机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `ExpenseReviewRedisClusterStateCacheTest`（新建，落 `cases/integration/workflow_call`，方法级自管栈） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-003")` + stories `wf.config-switch` / `wf.cluster-db-ignored`（待注册） |
| 基类 | 方法级 `SutStack` + 类内 `grokzen/redis-cluster` fixture；cluster-aware `RedisProbe`（bridge-IP / scan 容忍 MOVED） |
| 同型先例 | `react_travel/Feat003RedisClusterAndSwitchTest`（cluster 切换合同同型实现参考，归 component/contract 层） |

## 运行方式

```bash
# ⬜ 待新建；切换用例需 LLM，忽略用例 hermetic
mvn test -Dtest=ExpenseReviewRedisClusterStateCacheTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2 MUST 单机↔集群配置切换 | A | ⬜ 待新建 |
| §3/§5.1.1 集群忽略 database + 诊断 | B | ⬜ 待新建 |

## 清理策略

- 方法级 `SutStack` try-with-resources 自动收尾；grokzen cluster 容器随方法结束销毁。

## 风险与备注

- **cluster 可达性 = bridge IP 直连**：grokzen 无 `cluster-announce-ip`，节点公告 bridge IP，Linux 宿主可路由；JedisCluster seed bootstrap 后跟随公告重定向（[middleware-activation](FEAT-003-middleware-activation.md) 载体②已验证）。新环境首跑前先做 fixture smoke（设计档风险 4）。
- **切换用例不要求迁移旧数据**：两阶段独立跑业务，断「切换不改业务代码 + 两阶段均成功」，不断旧数据可读（数据迁移属部署责任，§5.2 豁免）。
- **与 component 层边界**：cluster 切换的合同级断言（SPI 跨 slot、scanIter 等）归 `react_travel/Feat003RedisClusterAndSwitchTest`（component/contract 层先例）；本 SIT 用例只断业务面切换语义。
- **与 config-variant 边界**：[config-variant](FEAT-003-config-variant.md) 断缺省/命名引用（standalone）；本用例断集群切换/忽略 database（cluster）——standalone vs cluster 两个配置维度互补。
