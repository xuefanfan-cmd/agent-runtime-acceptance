---
feature_id: FEAT-089
feature_title: 会话上下文持久化与长时间暂停恢复 — EDPA2agent 视角
sut: moa-agent-demo（com.moaagent:moa-agent-demo:0.1.3，l1/l2/mock 三进程）+ agent-runtime-java（含 PR#252）+ agent-solution common（含 PR#1051）
scope: Redis/openGauss 双承载 checkpointer、KVStore/TaskStore、长暂停恢复、隔离、故障、并发与部署边界
status: designed
status_note: 用例设计已落地，测试代码与环境供给尚未实现；当前仓库无 FEAT-089 用例
owner: TBD
priority: P0
tags: [integration, e2e, concurrency, edpa2agent, feat-089, opengauss]
depends_on:
  - openGauss 7.0 LTS 实例可用，已按 L2 §5.6 预建 moa_kv / moa_a2a_task 两张表及 (agent_id, context_id) 索引
  - 运行账号具备两张表 SELECT/INSERT/UPDATE/DELETE 及冲突更新权限；启动检查探针可回滚
  - openGauss JDBC 驱动外置于 LOADER_PATH（默认 exec 包不携带驱动）
  - agent-runtime-java 使用含 PR#252 的 develop 构建（本地 pr-247 分支不含 #252）
  - agent-solution common 含 PR#1051（本仓已验证 origin/common 6fd13012b）
  - agent-core-java 依赖版本包含 L2 §1.4 要求的 #287 修复（当前 origin/develop 与 930 均未合入，见 §9）
  - moa-agent-demo 0.1.3 已构建并安装到本地 Maven 仓库（依赖 moa-agent lib 先 install）
  - 7 步长任务 Test Agent / TTL controller / openGauss deploy fixture 已就绪（当前均为缺口）
  - LLM_* 环境变量由 shell 注入，本地跑前 unset 代理
related_docs:
  - FEAT-089-session-context-persistence-long-pause-recovery.md（需求，2026-11-04）
  - develop/04-testplan/FEAT-089-session-context-persistence-long-pause-recovery-testplan.md（测试设计，2026-11-06）
  - Feat-Func-089-session-context-persistence-long-pause-recovery.md（L2，PR docs#445，2026-11-05）
  - Feat-Func-003-agent-task-state-cache.md（checkpoint 隔离基线）
  - FEAT-037-single-process-multi-agent-hosting-deepagent.md（Hosted 共享表基线）
  - ../write-testcase.md（框架用例落地五步法）
---

# FEAT-089 — 会话上下文持久化与长时间暂停恢复测试用例设计（EDPA2agent）

> **一句话**：以 `moa-agent-demo`（EDPA2.0 Runtime 宿主）为 SUT，把 FEAT-089 的「openGauss 无 TTL 持久化 → 超 TTL 长暂停恢复 → 隔离 0 泄露 → 保存/恢复失败可见」映射为本仓可执行的黑盒验收用例，同时用 JDBC 探针做确定性断言，避免只看最终话术造成假阳性。

> **仓库边界**：新增测试代码只写入 `agent-runtime-acceptance`；`moa-agent`/`moa-agent-demo`、`agent-runtime-java`、`agent-core-java` 均为只读被测对象。不加载产品私有类、不反射 checkpointer 内部状态、不新增 Agent HTTP/SPI 测试端点。

> **与产品侧 UT/IT 的分工**：L2 §7.4 的 `OpenGaussKVStoreIT`、`OpenGaussTaskStoreIT`、`NativeContextRecoveryIT` 等属于产品仓白盒集成测试；本仓只做**外部可观测**的验收与契约复核，白盒 Store API 用例单独标记为 `white-box / dependency-gated`，不把 mock 通过计为验收通过。

## 1. 设计依据与输入快照

### 1.1 输入快照

| 输入 | 锁定版本/状态 |
|---|---|
| 需求 | `develop/02-features/FEAT-089-session-context-persistence-long-pause-recovery.md`（status draft，updated 2026-11-04） |
| 测试设计 | `develop/04-testplan/FEAT-089-session-context-persistence-long-pause-recovery-testplan.md`（updated 2026-11-06） |
| L2 设计 | `develop/03-architecture/L2-Low-Level-Design/moa-agent/Feat-Func-089-session-context-persistence-long-pause-recovery.md`（PR docs#445，已合入 docs main `1db2f121`） |
| Runtime 代码 | PR [agent-runtime-java#252](https://gitcode.com/openJiuwen/agent-runtime-java/pull/252)（`e99b714b`，已合入 `origin/develop`） |
| Solution 代码 | PR [agent-solution#1051](https://gitcode.com/openJiuwen/agent-solution/pull/1051)（已合入 `origin/common`，本仓 `6fd13012b`） |
| Core 前置 | `agent-core-java#287`（修复未合入 `origin/develop` / `origin/930`，见 §9 阻塞项） |
| 框架 | `agent-runtime-acceptance` main `3cce246`（2026-11-02） |
| 现状 | 本仓无 FEAT-089 用例、无 openGauss fixture、`application-openjiuwen.yml` 无 EDPA2.0 agent 声明 |

### 1.2 范围

覆盖需求 §2/§6 与 L2 §7 的验收面：

- openGauss 承载下会话上下文无 TTL 保存、覆盖式更新、超 TTL 长暂停恢复、进程重启恢复；
- Redis 承载回归（正 TTL、到期行为不变），openGauss 与 Redis 行为等价；
- 隔离沿用现状（跨用户/agent_id/命名空间 0 泄露，Hosted 共享表与独立应用隔离）；
- 保存失败、取回失败、未命中、损坏/半记录、不支持操作等错误表面可见且可区分；
- 并发恢复、连接池耗尽、网络断连、SQL 超时、慢保存等并发与故障面；
- openGauss 专项：启动检查、驱动外置/缺失、密码解密、Provider 缺失/重复、连接引用不存在、关闭顺序与资源回收；
- 与 FEAT-088 的联动场景（打断后超 TTL 恢复）。

### 1.3 非范围

- 打断入口/接管/自续的独立验收 → FEAT-088 TestPlan；
- 跨会话召回、恢复确认交互、跨承载迁移、数据清理/归档 → OUT 负面用例，仅记录行为；
- 建表 DDL 语法、驱动打包 layout、连接池参数调优 → 部署实施细节，不作验收阻断项；
- 产品仓白盒单测/集成测试本身（`OpenGaussKVStoreIT` 等）→ 产品仓交付，本仓只做验收复核。

## 2. 测试文档与代码落点

按 [write-testcase.md](../write-testcase.md) Step 1 的约定，测试设计文档统一放在 **`docs/cases/`**，文档编号与 `@Feature` 标签一一对应：

| 产物 | 落点 |
|---|---|
| 本文（用例设计） | `docs/cases/FEAT-089-session-context-persistence-long-pause-recovery-edpa2agent.md` |
| 黑盒恢复用例 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa2_session_persistence/` |
| 隔离/边界用例 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa2_session_persistence/` |
| openGauss 启动/驱动/配置用例 | `src/test/java/com/huawei/ascend/sit/cases/e2e/edpa2_session_persistence/` |
| Store 契约（白盒，dependency-gated） | `src/test/java/com/huawei/ascend/sit/cases/component/moa_opengauss/` |
| 并发用例 | `src/test/java/com/huawei/ascend/sit/cases/concurrency/edpa2_session_persistence/` |
| 环境声明 | `src/test/resources/application-openjiuwen.yml` 新增 EDPA2.0 agent 与 openGauss 外部环境块 |
| 构建供给 | `scripts/sut/sut-sources.yml` 新增 `common/agents/moa-agent`、`common/agents/moa-agent-demo` 构建步骤 |
| 新增探针 | `src/main/java/com/huawei/ascend/sit/utils/OpenGaussProbe.java`（只读 JDBC：表结构/记录/计数/前缀查询） |

标签约定：

- 类级 `@Feature("FEAT-089: 会话上下文持久化与长时间暂停恢复")`；
- 方法级 `@Story("<用例 ID 的 slug>: <一句话>")`、`@DisplayName("<用例 ID>: <场景>")`；
- 分层标签：`component` / `integration` / `e2e` / `concurrency`；
- 环境缺失统一 `Assumptions.abort/assumeTrue` 跳过，不伪绿；故障注入类额外打 `@Tag("manual")`。

## 3. SUT 与部署拓扑

### 3.1 SUT

| 进程 | profile | 端口 | 用途 |
|---|---|---|---|
| `moa-mock` | `mock` | 3001 | 共享 Mock Agent，承接景点/酒店/预订等 Skill 下游调用，无需模型 |
| `moa-l2` | `l2` | 18229 | 二级酒店 EDPA2.0，验证 Hosted/多 Agent 共享同一套表 |
| `moa-l1` | `l1` | 18219 | 一级旅行 EDPA2.0，对外 A2A 入口，验收主对象 |

- 坐标：`com.moaagent:moa-agent-demo:0.1.3`，读取 `~/.m2`；`agent-solution-latest` 下构建产物亦可。
- 启动顺序：mock → l2 → l1；`SutStack` 按依赖顺序拉起，端口以 `application-openjiuwen.yml` 声明为准。
- 默认空跑基线：`--spring.profiles.active=...` 不含任何 checkpointer 自定义 → `checkpointer.type=in_memory`。
- Redis 基线：`checkpointer.type=redis`，`ttl-seconds: 604800`（七天真值）。
- openGauss 目标：`checkpointer.type=opengauss`，`connection-ref: default`，按 §4.2 注入连接定义。

### 3.2 配置键（L2 §6.1/§6.2）

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: opengauss            # in_memory | redis | opengauss
        connection-ref: default    # 扩展后端引用，默认 default
        # ttl-seconds 仅 Redis 消费且必须 > 0；openGauss 不填写、不消费
      opengauss:
        default:
          jdbc-url: ${OPENGAUSS_JDBC_URL}
          username: ${OPENGAUSS_USERNAME}
          encrypted-password: ${OPENGAUSS_ENCRYPTED_PASSWORD}
          schema: moa
          pool:
            maximum-size: 10
            connection-timeout-ms: 3000
```

- 数据表：`moa.moa_kv`（`store_key` 主键）与 `moa.moa_a2a_task`（`(agent_id, task_id)` 主键，`(agent_id, context_id)` 索引）。
- 驱动类 `org.opengauss.Driver`，URL 前缀 `jdbc:opengauss:`；驱动外置 via `loader.properties` / `LOADER_PATH`。
- 表由部署方预建，应用只检查不建表；运行账号需 SELECT/INSERT/UPDATE/DELETE 及冲突更新权限。

## 4. 前置条件与共享约定

### 4.1 环境供给缺口（必须先补齐）

| 缺口 | 说明 | 落地建议 |
|---|---|---|
| openGauss 实例 | 当前框架无 openGauss 容器/fixture；L2 要求真实目标版本，H2/PostgreSQL 通过不能替代 | 以 `sut.external.opengauss` 外部块声明地址；testcontainers 不作为验收基线 |
| 两张表与权限 | 需预建 `moa_kv` / `moa_a2a_task` 及索引，并授予运行账号冲突更新权限 | 建表/授权 SQL 取自 EDPA2.0 部署文档 `common/agents/moa-agent/docs/opengauss-storage.md` |
| JDBC 驱动 | 默认 exec 包不带驱动 | 驱动置于独立目录，启动参数 `-Dloader.path=<dir>` 或 `LOADER_PATH` |
| 7 步长任务 Fixture Agent | 测试设计要求确定性 7 步任务，便于在第 3/5 步暂停并断言进度 | 在 `common/example` 新增 `feat089-seven-step-agent`（脚本工具 + 可控模型），不改 EDPA2.0 产品代码 |
| TTL controller | 构造“超过 TTL”的暂停 | 短期回归用缩短 Redis TTL 的对照；真实 7 天保留用例单独 manual 执行 |
| 故障注入 | 存储不可用/超时/网络断连/SQL 超时 | 复用 `ToxiproxyFaultLink`；DB 侧用连接串断开/账号回收/慢 SQL 包装，不修改产品类 |
| Core 前置修复 | `#287` 未合入 develop/930 | 阻塞错误表面用例（§9） |

### 4.2 环境变量（不落库）

- `OPENGAUSS_JDBC_URL` / `OPENGAUSS_USERNAME` / `OPENGAUSS_ENCRYPTED_PASSWORD`：openGauss 连接；
- `LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER` / `LLM_SSL_VERIFY`：模型；
- 本地跑前 `unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy`，避免内网模型被代理拦截。

### 4.3 共享约定

- 每个用例生成唯一 `contextId`（`ctx-f089-<slug>-<8 位随机>`），隔离场景使用独立身份/租户/agent_id。
- 恢复类断言采用**双通道**：A2A 终态与文本 + JDBC 直查 `moa_kv`/`moa_a2a_task` 记录；只做话术断言不算通过。
- 长暂停必须清除进程内缓存/重建 Agent 实例/重启进程，禁止仅等待后继续（防止内存状态造成假阳性）。
- 断言不依赖具体话术：以 turn1 独有 token（进度、关键事实）在无提示的 turn2 输入中被承接为准。
- 只读探针禁止执行 DDL/DML；故障注入用可回滚探针记录，结束后清理。
- Redis 键空间不硬编码 schema，只用 glob + 存在性/缺席性判断。

## 5. 覆盖矩阵总览

| 测试类 | 分层 / Tag | 用例数 | 对应设计 ID | 状态 |
|---|---|---|---|---|
| `SessionRecoveryAcceptanceTest` | integration | 7 | 5.1 全部 | env-gated |
| `BackendParityAcceptanceTest` | integration | 2（另复用 1 条等价性执行） | redis-regression、ttl-threshold；opengauss-equivalence 与 6.1 同 ID 对比执行 | env-gated |
| `RecoveryFailureAcceptanceTest` | integration | 7 | 5.2 全部 + l2.legal-empty-map-and-miss、l2.unknown-task-id | env-gated / manual |
| `IsolationAcceptanceTest` | integration | 8 | 5.5 隔离/OUT + l2.hosted-shared-table、l2.independent-app-isolation、l2.same-agent-context-isolation、l2.multi-host-replica | env-gated / e2e |
| `OpenGaussStartupAcceptanceTest` | e2e | 10 | 5.6 启动/驱动/密码/Provider/引用/关闭 + l2.core-prerequisite-fix | env-gated / manual |
| `OpenGaussStoreContractTest` | component | 8 | 5.6 TTL 契约 + 5.5 标识/载荷/空 key + l2.data-type-fidelity、l2.mget-pipeline-consistency、l2.pipeline-rollback | white-box / dependency-gated |
| `PersistenceConcurrencyAcceptanceTest` | concurrency | 4 | 5.3 全部 | env-gated |
| `PersistenceFaultAcceptanceTest` | integration | 14 | 5.4 全部 + l2.connection-pool-exhaust | env-gated / manual |
| `PersistenceLifecycleAcceptanceTest` | integration | 4 | l2.process-restart-recovery、l2.checkpoint-replace-reuse、l2.serialization-compat、l2.recovery-reset | env-gated / manual |
| `Feat088LinkageAcceptanceTest` | integration | 3 | 5.8 全部 | blocked（依赖 FEAT-088） |
| `OutOfScopeBoundaryAcceptanceTest` | integration | 4 | 恢复确认交互、跨承载迁移、数据清理、跨会话召回 | env-gated / P2 |

合计 71 条，与测试设计文档 §5 的 7 组场景一一对应；每条用例的 ID 沿用测试设计文档的 `FEAT-089.*` 命名，方法名取 ID 去点驼峰化（如 `FEAT-089.normal.longpause-recovery` → `feat089NormalLongpauseRecovery()`）。

## 6. 用例详细设计

### 6.1 `SessionRecoveryAcceptanceTest`（integration，env-gated）

SUT：`moa-l1` + 7 步 Fixture Agent；承载：openGauss（除特别说明）。

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.normal.longpause-recovery | P0 | 7 步任务执行到 3/7 后暂停，上下文已落 openGauss；暂停时长超原 Redis TTL | 重建 Agent 实例/清缓存，原会话（同 contextId）发“继续” | JDBC 可读到最近一次 `moa_kv` 记录；续写承接前 3 步主题与进度，不按新会话处理 |
| FEAT-089.normal.override-save | P0 | 3/7 暂停保存 → 恢复到 5/7 → 再次长时间暂停覆盖保存 | 原会话发“继续” | 以 5/7 记录恢复，从第 6 步续写；`moa_kv` 中该会话无历史版本堆积 |
| FEAT-089.normal.three-stop-times | P1 | 同一任务分别构造暂停、被打断、正常收尾三类停止时机 | 分别执行停止操作 | 三类时机均触发保存；保存事件在日志/指标可观测 |
| FEAT-089.normal.normal-completion-save | P1 | 任务正常完成（非暂停/非打断） | 完成任务后再次长暂停并恢复 | 正常收尾也写了上下文；后续恢复有上下文可用 |
| FEAT-089.normal.opengauss-equivalence | P1 | 两套干净后端；Redis 记录在 TTL 内；同一模型与输入 | 分别以 `type=redis` 与 `type=opengauss` 跑同一会话（长任务→暂停→恢复） | 应答、上下文承接、Task 行为等价；仅 openGauss 验证超原 TTL 恢复 |
| FEAT-089.normal.longpause-recovery-newcmd | P0 | 3/7 暂停并保存；超 TTL | 清缓存后原会话发新指令 B（非“继续”） | 先恢复已保存上下文，再基于上下文执行 B；模型输入含恢复上下文 + B |
| FEAT-089.normal.recovery-then-complete | P1 | 3/7 暂停并保存；超 TTL | 原会话“继续” → 续写至任务完成 | 续写承接进度，任务最终 `TASK_STATE_COMPLETED`，结果汇总正常返回 |

关键断言补充：

- 恢复后 turn2 用户输入不含 turn1 独有 token，避免机械回显；token 命中即证明来自持久化上下文。
- 暂停后必须用 `SutStack` 重启或新建实例，确保 `in_memory` 状态不残留。
- JDBC 断言：`SELECT store_key, value_type, payload, updated_at FROM moa.moa_kv WHERE store_key LIKE '%<contextId>%'`，且 `moa_kv` 无过期列。

### 6.2 `BackendParityAcceptanceTest`（integration，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.normal.opengauss-equivalence（与 6.1 同 ID，承载对比执行） | P1 | 同 6.1（本类只放承载对比维度，避免重复执行） | 同一剧本分别对 Redis/openGauss 各跑一次，比较终态文本、A2A 事件序列、Task 状态 | 两条承载的对外行为一致；差异仅限非语义字段 |
| FEAT-089.l2.redis-regression | P1 | 引入 openGauss 后 Redis 配置不变 | 验证原内存/Redis YAML 启动；正 TTL 写入与到期；Core/Task/索引行为 | Redis 与原行为完全一致；到期后查询未命中 |
| FEAT-089.boundary.ttl-threshold | P1 | openGauss 与 Redis 各自保存同一会话 | 分别用略小于 TTL、等于 TTL、略大于 TTL 触发恢复（Redis TTL 用缩短值作短期对照） | 略大于 Redis TTL 仍可恢复（openGauss 无 TTL），仅 openGauss 通过；临界点行为符合预期；真实 7 天保留单独 manual |

### 6.3 `RecoveryFailureAcceptanceTest`（integration，env-gated / manual）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.error.corrupted-context | P1 | 已保存上下文中一个 key 被改写为非法内容（JDBC 注入） | 原会话发“继续” | 反馈恢复错误并记录；不当作未命中，不伪装正常恢复，进程不崩溃 |
| FEAT-089.error.session-not-exist | P1 | 使用不存在/已清理的会话标识 | 发起恢复 | 按既有新会话行为处理；不伪装恢复成功 |
| FEAT-089.error.waiting-input-recovery | P1 | Task 处于 waiting（原生中断保存完成） | 重启进程后提交合法等待应答 | 原等待关联与会话状态恢复；A2A 状态语义不变 |
| FEAT-089.error.half-record-illegal-struct | P1 | 检查点两条 key 只保留一条，或结构非法（JDBC 注入） | 尝试恢复 | 反馈恢复错误，保留 Session state 与原数据；不崩溃 |
| FEAT-089.error.unknown-java-rejected | P2 | KV 值含白名单外 Java 对象 | 写入/读取该记录 | 显式拒绝，不静默接受；错误可定位到类型不兼容 |
| FEAT-089.l2.legal-empty-map-and-miss | P1 | 两条 key 均不存在；合法空 Map；实际未命中 | 分别恢复 | 两条 key 均缺失→按新会话（非降级/非错误）；合法空 Map 正常恢复；未命中与读取失败可区分 |
| FEAT-089.l2.unknown-task-id | P1 | Core 会话状态存在，A2A Task 不存在 | 显式按旧 taskId 续接 | 保持 SDK 任务不存在语义，不伪造历史 Task |

### 6.4 `IsolationAcceptanceTest`（integration / e2e，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.boundary.isolation-cross-domain | P0 | 身份 A、B 各有已保存暂停会话 | B 构造指向 A 会话标识的请求；随后 B 回自己会话恢复 | B 任何请求形式都取不到 A 的上下文；A 内容 0 泄露；B 自己正常续写 |
| FEAT-089.boundary.isolation-multi-dimension | P1 | 覆盖 agent_id 或命名空间维度 | 构造跨维度访问 | 隔离整体生效，不止单一维度碰巧通过 |
| FEAT-089.boundary.cross-session-recall | P2 | 原会话之外另有一个会话 | 在别的会话尝试召回原会话上下文 | 取不到、不泄露（OUT 负面，仅记录） |
| FEAT-089.l2.hosted-shared-table | P1 | 同一应用托管 A、B 逻辑 Agent，共用两张表，绑定不同 agent_id | 写 A，再从 B 查同 taskId；A 读回自己 | B 不读到 A；A 可读回；不为 A/B 另建表；`moa_a2a_task` 按 `(agent_id, task_id)` 隔离 |
| FEAT-089.l2.independent-app-isolation | P1 | 两个独立应用用不同数据库/schema，各建两张表，允许相同 ID | 分别读写相同标识记录 | 互不读取/覆盖；隔离边界为数据库/schema 级 |
| FEAT-089.l2.same-agent-context-isolation | P1 | 同一 Agent 的 context-A/B，context-B 的 Task JSON 损坏 | 查 context-A 的恢复 | context-A 正常恢复；SQL 查询按 `agent_id + context_id` 限定，不读取该 Agent 全量任务再反序列化过滤 |
| FEAT-089.l2.multi-host-replica | P1 | 同一应用两个 Host 副本共用两张表，相同逻辑 Agent 使用相同 Agent ID | 另一副本/重启后恢复 | 读回同一份会话与 Task；不按 Host 建表，不使用主机信息划分数据 |
| FEAT-089.l2.normal-hosted-recovery | P1 | openGauss 配置就绪 | 普通模式与 Hosted 模式各执行长任务→暂停→恢复 | 两种模式恢复均正常；普通模式用应用名+目标 Agent ID，Hosted 用注册项稳定 Agent ID |

### 6.5 `OpenGaussStartupAcceptanceTest`（e2e，env-gated / manual）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.opengauss.startup-check | P1 | 表结构/索引/权限就绪；探针 key 可回滚 | 启动 EDPA2agent，观察启动检查 | 校验表结构/主键/列类型/容量 + 运行账号读写与冲突更新权限；探针使用专用 key 且回滚/清理；不执行 CREATE/ALTER |
| FEAT-089.opengauss.startup-check-fail | P1 | 表结构/索引/版本不符或权限不足 | 启动 | 初始化失败、不发布就绪；失败信息可定位到具体问题；不静默通过 |
| FEAT-089.opengauss.driver-missing | P2 | exec 包默认不带驱动 | 不外置驱动直接启动 openGauss 承载 | 启动失败并提示驱动缺失；不降级到其他承载 |
| FEAT-089.opengauss.driver-external | P2 | 驱动外置于 LOADER_PATH | `java -jar` 启动 | 驱动经 JDBC SPI 正确加载；包内依赖先于驱动目录；进入连接阶段 |
| FEAT-089.opengauss.password-decrypt-fail | P1 | `encrypted-password` 损坏或密钥缺失 | 启动/连接 | 明确解密失败；不静默用空密码/明文；错误可定位到解密环节 |
| FEAT-089.opengauss.provider-missing-duplicate | P1 | 配置 type 指定的 Provider 缺失或有多个匹配 | 启动 | 启动失败，指出 type 与组件类型；不泄露凭据 |
| FEAT-089.opengauss.connection-ref-not-exist | P1 | `connection-ref` 指向不存在的数据源 | 启动 | 启动失败，指出配置路径与引用名 |
| FEAT-089.opengauss.startup-fail-resource-reclaim | P2 | 初始化中部分组件已创建后失败 | 启动失败 | 只回收本组件拥有且创建成功的资源；借用 DataSource 不关闭 |
| FEAT-089.opengauss.shutdown-order | P2 | openGauss 承载运行中 | 正常关闭 EDPA2agent | 停收新请求→收束运行→停止 sweeper/共享 Runner→关闭自有连接池；借用 DataSource 不关闭 |
| FEAT-089.l2.core-prerequisite-fix | P1 | 检查实际依赖是否含 Core #287 修复 | 跑非法检查点恢复 | 依赖含修复，恢复错误语义符合 L2 §2.3；当前为阻塞项（§9） |

### 6.6 `OpenGaussStoreContractTest`（component，white-box / dependency-gated）

> 需要 `moa-agent` lib 作为测试依赖直调 Store；仅做契约复核，不替代产品仓 `OpenGaussKVStoreIT`。无 openGauss 时整类 `Assumptions.abort`。

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.opengauss.explicit-ttl-rejected | P1 | openGauss Store 就绪 | 普通 key 写入；调用 `exclusiveSet` 带 expiry；pipeline set 带非空 TTL | 普通 key 无到期信息；`exclusiveSet` 明确失败且未执行 SQL；pipeline 在执行任何 SQL 前拒绝整批 |
| FEAT-089.l2.unsupported-ops-rejected | P1 | 同上 | `exclusiveSet`；pipeline 带 TTL | 两者均明确失败且未执行任何 SQL，不静默接受/降级 |
| FEAT-089.l2.data-type-fidelity | P1 | Store 就绪 | 写读 String、byte[]、null、空串、零字节、中文、二进制；Core 序列化载荷含 Map/List/标量 | 原始 KV 层各类型无损；Core 载荷层反序列化无损；两层分开验证 |
| FEAT-089.boundary.large-payload | P1 | Store 就绪 | 写入大于 4KB 的上下文载荷并读回 | 大 payload 无损，无截断/编码损坏 |
| FEAT-089.l2.mget-pipeline-consistency | P1 | 多条记录已写 | mget（含重复/缺失 key）；pipeline set/get/exists 混合；含 get/exists 批次；含非空 TTL 的 set | mget 逐项复用 get、保留顺序/重复/缺失占位；pipeline 按入队顺序返回；含 get/exists 用 REPEATABLE_READ 一致快照；非空 TTL 整批拒绝 |
| FEAT-089.l2.pipeline-rollback | P1 | 两个状态组成行在同事务 | 第二条 SQL 注入异常 | 全部回滚，旧状态一致可恢复 |
| FEAT-089.boundary.key-length-exceed | P1 | 构造超长 store_key/agent_id/task_id | 保存/恢复 | 显式失败，不截断、不改主键，事务无部分提交 |
| FEAT-089.boundary.empty-key-rejected | P2 | 空 key | 写入 | 拒绝写入，显式失败 |

### 6.7 `PersistenceConcurrencyAcceptanceTest`（concurrency，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.concurrent.same-session-recovery-single | P1 | 同一 Agent 实例下同一会话标识 | 两个请求几乎同时恢复 | 单实例准入正确，无上下文分裂或重复恢复异常 |
| FEAT-089.concurrent.same-session-recovery-cross-replica | P2 | 两个 Host 副本共用同一套表 | 两个副本同时恢复同一会话 | 事务保证单次批量操作原子性；可能后写覆盖但无数据损坏，最终一致 |
| FEAT-089.concurrent.multi-session | P1 | 同一用户多个会话同时暂停 | 各自独立恢复 | 会话间不串扰，各取各自最近一次保存内容 |
| FEAT-089.concurrent.high-freq-save | P2 | 会话短时间反复暂停/收尾 | 触发高频覆盖写 | 单实例内最后提交为准；无版本堆积；保存点等待写入；事务原子 |

### 6.8 `PersistenceFaultAcceptanceTest`（integration，env-gated / manual）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.fault.save-unavailable | P0 | 上下文存储不可用（断网/停库/账号回收） | 执行暂停/收尾 | 保存点等待写入；失败沿用既有异常处理并记录，不伪装成功；部分步骤保存失败后继续符合 L2 §1.4 |
| FEAT-089.fault.retrieval-fail-error | P0 | 长暂停后取回失败或内容损坏/版本不兼容 | 发起恢复 | 反馈恢复错误并记录，不当作未命中，不伪装正常恢复；调用方可观察失败 |
| FEAT-089.fault.retrieval-miss-new-session | P0 | 实际无已保存内容 | 发起恢复 | 按既有新会话处理，不伪装恢复成功；与读取失败区分 |
| FEAT-089.fault.save-timeout | P1 | 存储未完全不可用，单次写超时 | 执行暂停 | 失败沿用既有异常处理并记录；下个写点可重新保存 |
| FEAT-089.fault.core-serialization-fail | P1 | 会话上下文含不可序列化对象 | 执行暂停 | Core 侧失败沿用现有异常处理；两侧无联合事务，不保证同时成功 |
| FEAT-089.fault.a2a-task-id-validation-fail | P1 | Task 写入缺少 contextId（标识校验先于 JSON 序列化） | 执行暂停 | A2A Task 侧记录 `TaskPersistenceException`（非 `TaskSerializationException`） |
| FEAT-089.fault.a2a-task-encode-fail | P1 | Task 编解码触发非法状态/NaN 元数据 | 执行暂停 | 记录 `TaskSerializationException` 并保留原始原因 |
| FEAT-089.fault.recovery-after-failure | P2 | 故障期间保存失败，故障恢复后 | 再次暂停/恢复 | 新停止时机正常保存；历史失败有记录；下个写点可重试 |
| FEAT-089.fault.mid-retrieval-failure | P1 | 恢复过程中存储中途故障 | 取回中 | 反馈恢复错误，不返回半截上下文；失败可查 |
| FEAT-089.fault.slow-save-waiting | P1 | 注入存储延迟（慢但成功） | 执行暂停/正常对话 | 保存点等待写入完成，耗时计入该次调用；最终成功且可取回 |
| FEAT-089.fault.network-disconnect | P1 | openGauss 运行中 | 断开网络连接（区别于连接池耗尽） | 有限等待后返回存储失败；不变为空上下文/内存 Store；按既有错误链反馈 |
| FEAT-089.fault.sql-timeout | P1 | openGauss 运行中 | 注入单条 SQL 超时（区别于连接池耗尽） | 适配器返回存储失败；不变为空上下文/内存 Store；失败可观测 |
| FEAT-089.fault.retry-not-fix-corrupted | P1 | 恢复因数据损坏失败，原数据保留 | 再次恢复（重试） | 重试不修复损坏记录；Store/sweeper 不自动清空会话；需显式重置/修复 |
| FEAT-089.l2.connection-pool-exhaust | P1 | 连接池达到 `maximum-size` | 并发请求耗尽连接池 | 有限等待（`connection-timeout-ms`）后返回存储失败；不变为空上下文/内存 Store |

### 6.9 `PersistenceLifecycleAcceptanceTest`（integration，env-gated / manual）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.l2.process-restart-recovery | P0 | openGauss 多轮会话已提交，保存确认成功 | 停止实例/重启进程，清缓存，同身份同 Agent 同会话发“继续” | Core 原生恢复状态与上下文；openGauss 记录不判到期；JDBC 可观测读取；真实 7 天保留单独 manual |
| FEAT-089.l2.checkpoint-replace-reuse | P2 | 应用持有同一 KVStore 与共享 DataSource | 重新安装默认检查点或停止后重启 Runner | Store 仍可读写；仅应用销毁释放自有连接池，借用资源不关闭 |
| FEAT-089.l2.serialization-compat | P1 | 旧版本真实 Session/context、等待输入状态及 Task JSON 已保存 | 升级依赖后读回 | 旧数据可读回；不兼容时保留原数据，经受控迁移/回退 |
| FEAT-089.l2.recovery-reset | P2 | 恢复失败，原数据保留 | 通过授权入口 `POST /v1/reset_conversation` 重置 | 清理范围符合 L2 §5.5.1（按租户前缀清理 Core + 关联 A2A Task）；执行前停止并发写入；失败须反馈 |

### 6.10 `Feat088LinkageAcceptanceTest`（integration，blocked）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.cross.interrupt-then-longpause | P1 | 任务被打断生效（FEAT-088），现场已保存 | 暂停超 TTL 后原会话“继续” | 走 openGauss 无 TTL 路径恢复；续写承接前文 |
| FEAT-089.cross.interrupt-save-failure | P1 | 打断生效同时现场保存失败 | 执行打断 | 打断不回滚；保存失败记录；按实际持久化结果恢复；不把本次失败误判为历史全无 |
| FEAT-089.cross.interrupt-then-longpause-newcmd | P1 | 打断生效、现场已保存 | 超 TTL 后发新指令 B（非“继续”） | 先恢复上下文再执行 B；模型输入含恢复上下文 + B |

> 阻塞原因：FEAT-088 的打断入口/接管能力在本仓尚无对应用例与 fixture，待 FEAT-088 用例落地后解除。

### 6.11 `OutOfScopeBoundaryAcceptanceTest`（integration，env-gated / P2）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-089.boundary.no-confirm-interaction | P2 | 长暂停后恢复 | 观察是否有用户确认步骤 | 恢复自动生效，无确认交互 |
| FEAT-089.boundary.cross-storage-migration | P2 | 从 Redis 切换承载前已有数据 | 切换后尝试恢复旧数据 | 不承诺自动迁移；旧数据不出现在新承载 |
| FEAT-089.boundary.data-cleanup | P2 | 已保存上下文 | 观察清理/保留/归档 | 不验证清理逻辑，仅确认无 TTL |
| FEAT-089.boundary.redis-ttl-invalid | P2 | Redis 承载配置 `ttl-seconds<=0` | 启动 | 沿用原配置校验失败；openGauss 不要求会话 TTL 配置 |

## 7. 执行策略与门禁

### 7.1 分级

- **Smoke**（每次改动必跑）：`longpause-recovery`、`override-save`。
- **P0**：`longpause-recovery`、`override-save`、`l2.process-restart-recovery`、`fault.save-unavailable`、`fault.retrieval-fail-error`、`fault.retrieval-miss-new-session`、`boundary.isolation-cross-domain`。
- **P1**：其余主体用例，含 openGauss 专项、隔离多维、并发、故障、生命周期。
- **P2**：OUT 负面、驱动缺失/外置、重置、跨副本并发、高频保存等。
- **manual**：真实 7 天保留、驱动缺失/外置、SQL 超时、旧版本载荷读回、进程 kill/restart 类。

### 7.2 命令

```bash
# 单类
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=SessionRecoveryAcceptanceTest

# 全量 FEAT-089
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest='*089*'
```

- `openGauss` 外部环境未就绪时，类级 `Assumptions.abort` 整类 SKIPPED，不计 PASS。
- 失败必须区分：产品缺陷（红）/ 环境缺失（跳过）/ 用例缺陷（修正）；不允许把跳过当通过写入验收结论。
- 每个环境组合（openGauss 版本、兼容模式、驱动坐标）在报告中单独记录。

### 7.3 断言门禁

- 恢复类：A2A 终态 + JDBC 记录双通道，缺一不通过。
- 隔离类：A 上下文 token 在 B 的任何请求/日志中不出现。
- 失败类：错误可见 + 数据保留 + 不变为空上下文/内存 Store，三者同时满足。
- 保存类：保存等待语义成立（耗时计入调用），失败不伪装成功。

## 8. 可观测性与断言约定

| 观测面 | 工具/方法 | 用途 |
|---|---|---|
| KV 记录 | `OpenGaussProbe.kv(storeKey)` / `kvByPrefix` / `countKv` | 恢复来源、覆盖式更新、无 TTL、载荷无损 |
| Task 记录 | `OpenGaussProbe.task(agentId, taskId)` / `tasksByContext` | A2A Task 写入、agent_id 隔离、坏 JSON 不影响其他 context |
| 表结构 | `OpenGaussProbe.columns/indexes` | 启动检查、无过期列、主键与索引 |
| Redis | `RedisProbe.keys/keysAny/dbsize` | Redis 对照基线、TTL、到期未命中 |
| 日志/指标 | `ProbeLogTailer` + SUT stdout | 保存/恢复/失败事件可观测 |
| A2A | `InteractionFlow` / `A2aEventCollector` / `TaskTextExtractor` | 终态、文本承接、事件序列 |
| 故障 | `ToxiproxyFaultLink` 或 DB 侧断连/慢 SQL | 网络断连、超时、恢复中途故障 |

探针约束：只读、参数绑定、禁止 `SELECT *` 全表扫描大表；前缀查询走 `store_key LIKE 'prefix%'` 时先确认执行计划；不执行 DDL/DML。

## 9. 风险、阻塞与后续

| 项 | 影响 | 处置 |
|---|---|---|
| Core #287 修复未合入（`origin/develop`、`origin/930` 均不含） | 错误表面用例无法判定为产品通过 | 阻塞 `l2.core-prerequisite-fix`、`error.half-record-illegal-struct`；需先确认依赖版本 |
| 本地 agent-runtime-java 在 pr-247，不含 PR#252 | SUT 无 openGauss Provider 接线 | 测试供给切到含 #252 的 develop 构建；先安装 `agent-runtime-java` 相关模块 |
| 仓库无 openGauss fixture / EDPA2.0 agent 环境声明 | 全部用例当前 SKIPPED | 按 §4.1 补齐 `sut.external.opengauss`、EDPA2.0 agent 坐标、sut-sources 构建步骤 |
| 7 步 Fixture Agent 未实现 | 进度断言无法落地 | 新增 example fixture，保持产品代码零改动 |
| 真实 7 天保留无法在 CI 完成 | 核心要求只做短期对照会失真 | 单独 manual 环境执行，报告注明“缩短 TTL 对照不等价于真实 7 天保留” |
| 跨副本/独立应用需多堆栈 | 资源开销大 | e2e + manual 分层执行，不并入默认回归 |
| 存储故障注入可能污染共享库 | 影响其他用例 | 独立 schema/独立实例 + 专用探针 key，结束清理 |

## 10. See also

- FEAT-089 需求：`D:\docs\docs\develop\02-features\FEAT-089-session-context-persistence-long-pause-recovery.md`
- FEAT-089 测试设计：`D:\docs\docs\develop\04-testplan\FEAT-089-session-context-persistence-long-pause-recovery-testplan.md`
- L2 设计（PR docs#445）：`D:\docs\docs\develop\03-architecture\L2-Low-Level-Design\moa-agent\Feat-Func-089-session-context-persistence-long-pause-recovery.md`
- [FEAT-003 agent task state cache](FEAT-003-agent-task-state-cache-deepagent.md)（checkpoint 隔离基线）
- [FEAT-037 single-process multi-agent hosting](FEAT-037-single-process-multi-agent-hosting-deepagent.md)（Hosted 共享基线）
- [write-testcase.md](../write-testcase.md)（用例落地五步法）
- EDPA2.0 openGauss 部署说明：`agent-solution/common/agents/moa-agent/docs/opengauss-storage.md`
