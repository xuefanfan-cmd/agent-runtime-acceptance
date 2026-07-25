---
用例编号: FEAT-003-customer-adapter-spi
测试标题: 客户封装 Redis 适配扩展点机制——agent-runtime-java 的 Redis SPI 可接入模拟客户组件并显式处理差异/故障
story: S1
优先级: P1
自动化状态: TOBUILD（`ExpenseReviewRedisCustomAdapterTest` 3 方法待新建；依赖 agent 进程类路径可追加，落地时确认 `SutStack`/`ProcessLauncher` 支持）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, spi, feat-003]
---

# FEAT-003-customer-adapter-spi — 客户封装 Redis 适配扩展点机制

> **机制一句话**：客户封装 Redis 适配扩展点是 **agent-runtime-java 的 SPI 机制**（FEAT-003 §2 MUST）——
> runtime 暴露统一 Redis 操作接口（`RuntimeRedisClient` SPI），允许客户自封装 Redis 组件接入。真实客户 JAR
> 因合规不可外传（customer-site），SIT 以**模拟客户适配组件**（实现 SPI、包装真实 Jedis、带诊断标识与故障
> 开关）覆盖：**正常承接**（业务链路经客户组件承接）、**命令差异显式报错**（§5.1.2 语义对齐或显式报错）、
> **装配/连接故障不静默回退**（§5.1.1 不静默使用与配置不一致的数据源）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | Redis SPI 扩展点（`RuntimeRedisClient`）+ 命令差异显式报错（§5.1.2）+ 装配失败不静默回退（§5.1.1） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review`）+ **模拟客户适配组件**（SPI 实现 jar，经类路径注入 agent 进程） |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销→INPUT_REQUIRED→approved→COMPLETED（正常承接）/ 触发指定命令的业务操作（命令差异）/ 指向不可达后端（装配故障） |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§2「客户封装 Redis 适配扩展点」MUST；§4.1「接入客户封装 Redis 组件」场景；§5.1.2 命令差异；§5.1.1 装配失败不静默回退。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §2 MUST：客户封装 Redis 适配扩展点——产品保证 SPI 具备接入能力。
- FEAT-003 §5.1.2：客户组件与原生命令差异由适配实现语义对齐或显式报错。
- FEAT-003 §5.1.1：配置指定的适配实现无法装配时明确失败，不静默使用与配置不一致的数据源。

## 前置条件

1. 被测 jar 就绪（`expense-review`）。
2. **模拟客户适配组件**已构建为独立 jar（实现 `RuntimeRedisClient` SPI、包装真实 Jedis、带诊断标识 + 可配置故障开关），并经类路径追加注入 agent 进程。
3. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
4. Docker（Testcontainers Redis）。

## 测试数据

- 模拟组件：测试侧实现的 `RuntimeRedisClient` SPI 适配，内置**调用计数**与**故障开关**（拒答指定命令 / 指向不可达后端 / 必失败装配），由系统属性控制——属业务合理可达的注入点。
- 正常承接用例：超标报销 → INPUT_REQUIRED → approved → COMPLETED。
- 命令差异用例：触发涉及「拒答命令」的业务操作。
- 装配故障用例：模拟组件指向不可达后端（或装配为必失败实现）。

## SPI 参数表（同一 SPI 机制，三类客户侧场景）

| 方法 | 客户侧场景 | 模拟组件开关 | 预期 |
|---|---|---|---|
| `customAdapterServesWorkflowStateCache` | 正常承接 | 默认（承接） | 业务全流程成功，诊断选中模拟组件，调用计数证明承接 |
| `customAdapterCommandMismatchFailsExplicitly` | 命令差异 | 拒答指定命令 | 适配层显式报错，错误可诊断 |
| `customAdapterFailureFailsWithoutSilentFallback` | 装配/连接故障 | 指向不可达后端/必失败 | 明确失败，不静默回退原生实现 |

## 测试步骤

> 三类客户侧场景合并为一个测试类（`ExpenseReviewRedisCustomAdapterTest`）的三个 `@Test` 方法；方法级自管 `SutStack`，模拟组件源码置于 `src/test/java` 或独立 test-support 模块。

| # | 动作 | 预期 |
|---|------|------|
| 1 | **正常承接**：模拟适配 jar 上 agent 类路径 + Testcontainers Redis → 启动 agent → 跑超标报销至 INPUT_REQUIRED 后续 approved 至 COMPLETED | 业务全流程成功；诊断日志显示模拟客户实现标识；模拟组件调用计数证明 Redis 操作经其承接；状态键正常落盘 |
| 2 | **命令差异**：模拟组件开启「拒答指定命令」开关 → 触发涉及该命令的业务操作 | 适配层显式报错，错误语义可诊断；不静默返回错误结果；日志脱敏 |
| 3 | **装配故障**：模拟组件指向不可达后端（或装配为必失败实现）→ 启动 agent 并发起真实业务操作 | 启动或首次真实操作明确失败；**不**静默回退默认原生实现；日志不含敏感值 |

## 预期结果（机制断言）

### A — 客户适配组件正常承接 workflow 状态缓存（§2 MUST）
- **Given**：模拟客户适配 jar 上 agent 类路径，Testcontainers Redis 就绪。
- **When**：启动 agent → 跑超标报销至 INPUT_REQUIRED 后续 approved 至 COMPLETED。
- **Then**：业务全流程成功；诊断日志显示模拟客户实现标识（默认原生实现 back-off）；模拟组件调用计数证明 Redis 操作经其承接；状态键正常落盘。
- **PASS**：SPI 接入承接正常。**FAIL**：模拟组件未被选中 / 业务失败（SPI 接入机制失效）。

### B — 命令差异显式报错（§5.1.2）
- **Given**：模拟组件开启「拒答指定命令」开关。
- **When**：触发涉及该命令的业务操作。
- **Then**：适配层显式报错，错误语义可诊断；不静默返回错误结果；日志脱敏。
- **PASS**：命令差异显式报错。**FAIL**：静默返回错误结果 / 错误不可诊断（差异处理机制失效）。

### C — 装配/连接故障不静默回退（§5.1.1）
- **Given**：模拟组件指向不可达后端（或装配为必失败实现）。
- **When**：启动 agent 并发起真实业务操作。
- **Then**：启动或首次真实操作明确失败；**不**静默回退默认原生实现；日志不含敏感值。
- **PASS**：明确失败不回退。**FAIL**：静默回退原生实现（配置一致性机制失效，§5.1.1 违反）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `ExpenseReviewRedisCustomAdapterTest`（新建，落 `cases/integration/workflow_call`，方法级自管栈） |
| 模拟组件源码 | `src/test/java` 侧或独立 test-support 模块（打包后由栈注入类路径） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-003")` + stories `wf.customer-adapter-serve` / `wf.customer-adapter-mismatch` / `wf.customer-adapter-failure`（待注册） |
| 基类 | 方法级自管 `SutStack`；agent 进程类路径追加机制（`loader.path` 或 `-cp`） |
| 模拟组件 | 内置调用计数 + 故障开关（系统属性控制）；实现 `RuntimeRedisClient` SPI |

## 运行方式

```bash
# ⬜ 待新建；依赖类路径注入机制确认后实现；业务场景需 LLM
mvn test -Dtest=ExpenseReviewRedisCustomAdapterTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2 MUST 客户封装 Redis 适配扩展点（正常承接） | A | ⬜ 待新建 |
| §5.1.2 客户组件命令差异显式报错 | B | ⬜ 待新建 |
| §5.1.1 装配失败不静默回退 | C | ⬜ 待新建 |

## 清理策略

- 方法级 `SutStack` try-with-resources 自动收尾；模拟适配 jar 随测试类路径销毁；Testcontainers Redis 随方法结束销毁。

## 风险与备注

- **最大落地前置——类路径注入机制**：依赖 agent 进程类路径可追加（`loader.path`/`-cp`）；落地时先验证 `SutStack`/`ProcessLauncher` 支持，不支持则以 test-support 模块预打包「agent + 模拟适配」变体兜底（设计档风险 3）。模拟组件本身不改变被测 runtime 代码，仅作为 SPI 装配候选。
- **真实客户 JAR = customer-site**：真实客户封装组件因合规不可外传（§5.2 豁免）；内部以模拟组件覆盖适配能力与客户侧常见问题，最终现场联调在客户环境完成。
- **SPI 合同最小命令面归 component/contract 层**：SPI 方法面（文本/二进制、setnx、mget、scanIter、跨 slot）无业务触发路径，归 `react_travel` contract 用例（§2.3 处置）；本 SIT 用例只断业务面的接入/差异/故障语义。
- **故障开关属业务合理注入**：模拟组件故障开关由系统属性控制，模拟客户交付形态中的命令差异/连接问题——属 FEAT-003 §4.1「接入客户组件场景的故障面」合理覆盖，非离谱注入。
