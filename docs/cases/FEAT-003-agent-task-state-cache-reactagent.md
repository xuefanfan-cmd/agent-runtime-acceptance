---
feature_id: FEAT-003
feature_title: 智能体任务状态缓存
sut: travel-openjiuwen（mainplan → trip → hotel）
scope: agent-runtime Redis endpoint、TaskStore、checkpoint、配置切换、日志脱敏、生命周期及统一 Redis SPI 合同
status: designed
owner: TBD
priority: P0
tags: [integration, openjiuwen, feat-003]
depends_on:
  - travel-openjiuwen 三个可执行 jar 已按 application-openjiuwen.yml 坐标安装到 WSL Maven 本地仓库
  - agent-runtime-java 被测版本的公开 Maven artifact 已安装，版本与 Agent jar 内 runtime 一致
  - Docker/Testcontainers 可用
  - LLM 可用，mainplan → trip → hotel 链路互通
  - Redis Cluster fixture 已验证 WSL 宿主可达的 announce/MOVED 路由，或已配置 external-env 集群
related_docs:
  - FEAT-003-agent-task-state-cache-reactagent.md
  - Feat-Func-003-agent-task-state-cache.md
  - FEAT-003-blackbox-test-design-reviewed-v1.6.md
  - OJ-06-openjiuwen-redis-checkpointer-multi-turn.md
  - OJ-07-openjiuwen-checkpointer-config-switch.md
---

# FEAT-003 — 智能体任务状态缓存测试用例设计

> **一句话**：由 acceptance 拉起 travel-openjiuwen 外部 jar 和 Redis，验证 standalone/cluster、A2A Task、checkpoint、TTL、切换、错误诊断、日志脱敏及生命周期；没有外部触发路径的 Redis SPI 方法在同一 acceptance 测试类内以 `contract` nested tests 验证。

> **仓库边界**：所有新增测试代码只写入 `agent-runtime-acceptance`；`agent-runtime-java` 为只读被测对象，不提交任何修改；不为测试增加 Agent HTTP/SPI 代理端点。

## 1. 状态定义

- **runnable**：当前 main 能力和本地容器可直接实现。
- **env-gated**：实现明确，但依赖有效密码材料、真实 cluster 或外部环境。
- **contract**：代码在 acceptance；通过与 Agent 一致版本的 test-scope runtime artifact 验证公开 SPI，不属于 jar 黑盒。
- **customer-site**：依赖不可外传的客户真实 JAR，只能在现场执行。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 |
|---|---|---|---|
| standalone + 默认 TTL + 策略日志 | `FEAT-003.config.standalone-default` | runnable | jar 黑盒 + Redis 数据面 |
| 旧配置缺省 type/ref | `FEAT-003.config.legacy-defaults` | runnable | jar 黑盒 |
| in_memory 不选择 Redis | `FEAT-003.config.in-memory` | runnable | jar 黑盒 + 增量日志 |
| 错误配置诊断 | `FEAT-003.config.invalid` | runnable | 外部进程启动失败 |
| 密码成功与日志脱敏 | `FEAT-003.security.auth-success` | env-gated | requirepass Redis + 增量日志 |
| 错误密码/不可达不降级 | `FEAT-003.security.no-fallback` | env-gated | 首次真实操作失败 |
| A2A Task + TTL/过期 | `FEAT-003.standalone.task-ttl` | runnable | getTask + Redis TTL |
| 命名 ref + DB2 + 两使用方复用 | `FEAT-003.standalone.named-ref` | runnable | DB0/DB2 差异 + 日志 |
| Task/checkpoint 跨 JVM | `FEAT-003.standalone.restart-recovery` | runnable | stop/start + getTask + context 恢复 |
| reset conversation | `FEAT-003.standalone.reset` | runnable | 真实 reset API + 后续行为 |
| 并发隔离 | `FEAT-003.standalone.isolation` | runnable | 双 context 并发 |
| 生命周期 | `FEAT-003.standalone.lifecycle` | runnable | 三次 stop/start + CLIENT LIST |
| 真实 cluster 等价 | `FEAT-003.cluster.equivalence` | env-gated | 三 Agent 全链 + cluster-aware 数据面 |
| cluster nodes 优先 | `FEAT-003.cluster.nodes-priority` | env-gated | 无效 host/port + 有效 nodes |
| cluster database ignored | `FEAT-003.cluster.database-ignored` | env-gated | 业务成功 + databaseIgnored 日志 |
| standalone ↔ cluster | `FEAT-003.cluster.switch` | env-gated | 同一组三 jar/同一业务 helper |
| cluster 单节点故障 | `FEAT-003.cluster.node-failure` | env-gated/P1 | 节点故障后的行为与诊断 |
| 统一 Redis SPI standalone 合同 | `FEAT-003.contract.standalone` | contract | acceptance 内直接验证公开 SPI |
| 统一 Redis SPI cluster 合同 | `FEAT-003.contract.cluster` | contract | 跨 slot/多节点合同 |
| fake 客户 Adapter 装配 | `FEAT-003.contract.custom-adapter` | contract | Bean back-off + TaskStore/checkpoint |
| 真实客户组件 | `FEAT-003.customer-adapter` | customer-site | 现场统一 journey + 客户监控 |

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 使用 `-Dtest.env=openjiuwen`，复用 `application-openjiuwen.yml`。
- mainplan、trip、hotel 三个 Agent 的完整链路用例全部 `.profile("redis")`。
- 三 Agent 注入同一组 `openjiuwen.service.middleware.checkpointer.*` 与 `redis.<ref>.*`。
- 不使用不存在的 `main-plan-agent.redis-url` 转换入口。
- standalone 使用 `BackingServices` + `TestContainerFactory`。
- cluster 使用 `RedisClusterAndSwitchTest` 类内 private custom `ContainerFactory`。

### 3.2 main 能力复用

- jar/进程/端口/日志：`ProcessLauncher`、`SutStack`、`ManagedSutInstance`。
- 服务：`BackingServices`、`TestContainerFactory`。
- 配置：`AgentBuilder.property/env/profile`。
- 重启：`SutStack.stop/start/isRunning`。
- A2A：`A2aServiceClient`、`InteractionFlow`、`TaskTextExtractor`。
- 轮询：`WaitUtils` 或 Awaitility。

禁止测试自行实现 ProcessBuilder、端口探测、jar 路径解析和进程销毁。

### 3.3 数据与日志

- 每个方法生成唯一 `runId`、contextId 和语义标志串。
- 日志是 append 文件：启动前记录 offset，只读取本次增量。
- 脱敏失败只报告命中类别和日志路径，不回显 canary。
- TTL 用范围和轮询；不等待默认 7 天。
- 黑盒不写死 Task/checkpoint key；使用新 Redis、差集、taskId 特征、reset 和跨 JVM行为。
- cluster 禁止 `KEYS *`，数据检查必须支持 slot/MOVED。

## 4. 配置与诊断子用例

框架落点：`RedisConfigurationAndDiagnosticsTest.java`。

### FEAT-003.config.standalone-default — standalone 默认值与启动日志

- **状态**：runnable，P0。
- **追溯**：TC-CONFIG-01、TC-CONFIG-NEW-01、TC-SEC-03。
- **G**：全新 standalone Redis；三 Agent 启用 redis profile；ref=default、type=standalone，不配置 ttl-seconds。
- **W**：启动 hotel→trip→mainplan，执行 OJ-06 两轮全链 journey。
- **T**：
  - 三 Agent 就绪，Turn2 COMPLETED；
  - 日志包含 `redis-ref=default`、`endpoint-type=standalone`、`JedisPooledRuntimeRedisClient`、`ttl-seconds=604800`；
  - 安全摘要包含 host/port/database/timeoutMs/passwordConfigured；
  - 新增 Redis 数据 TTL 位于 `(0,604800]`。
- **方法**：`feat003StandaloneUsesDefaultTtlAndEmitsDiagnostics()`。
- **Story/DisplayName**：`standalone 默认配置` / `Feat-003 standalone 默认 TTL 与策略日志正确`。

### FEAT-003.config.legacy-defaults — 旧配置缺省 type/ref

- **状态**：runnable，P0。
- **追溯**：TC-CONFIG-03、TC-FUNC-08。
- **G**：不配置 endpoint.type 和 checkpointer.redis-ref，保留 host/port/database。
- **W**：启动并执行同一两轮 journey。
- **T**：业务成功；最终日志为 ref=default/type=standalone；不要求额外 fallback 文案。
- **方法**：`feat003LegacyConfigurationDefaultsToStandalone()`。

### FEAT-003.config.in-memory — in_memory 不选择 Redis

- **状态**：runnable，P1。
- **追溯**：TC-CONFIG-08。
- **G**：checkpointer.type=in_memory，不绑定 Redis service。
- **W**：启动 mainplan 并执行两轮交互。
- **T**：业务成功；本次增量日志不包含精确策略行 `Runtime Redis datasource selected`。不泛化为“日志不得出现 Redis”。
- **方法**：`feat003InMemoryDoesNotSelectRedisDatasource()`。

### FEAT-003.config.invalid — 参数化错误配置

每个场景使用独立外部进程和独立日志目录；通过 main launcher 抛出的异常及 log tail 断言。

| 方法 | 输入 | 预期 |
|---|---|---|
| `feat003ClusterWithoutNodesFails()` | cluster 无 nodes | 明确 nodes required；不强制实际 ref |
| `feat003UnsupportedEndpointTypeFails()` | type=invalid_type | 包含非法值和 standalone/cluster |
| `feat003MissingRedisReferenceFails()` | redis-ref=missing | 包含 `redis.missing is required` 等稳定语义 |
| `feat003ZeroTtlFails()` | ttl-seconds=0 | must be greater than 0 |
| `feat003NegativeTtlFails()` | ttl-seconds=-1 | must be greater than 0 |
| `feat003UnsupportedCheckpointerTypeFails()` | checkpointer.type=invalid | 包含 in_memory/redis 支持范围 |
| `feat003MalformedClusterNodeFails()` | nodes[0]=not-host-port | 明确 host:port 格式 |
| `feat003StandaloneWithoutHostFails()` | standalone 缺/空 host | 按 L2 应失败；当前若回落 localhost，记录产品缺陷 |

所有方法：**Story**=`错误配置诊断`，DisplayName 以 `Feat-003` 开头。

### FEAT-003.security.auth-success — requirepass 成功与脱敏

- **状态**：env-gated，P0。
- **追溯**：TC-SEC-01、TC-SEC-03。
- **G**：本类 private authenticated Redis factory；唯一 canary 同时作为 requirepass 与 encrypted-password。
- **W**：启动 Agent并完成真实业务操作。
- **T**：业务成功；`passwordConfigured=true`；所有本次增量 stdout/stderr/file log 不含 canary。
- **方法**：`feat003AuthenticatedRedisSucceedsWithoutLeakingPassword()`。

### FEAT-003.security.no-fallback — 错误密码/不可达不降级

- **状态**：env-gated，P0。
- **追溯**：TC-SEC-02、SUP-FAULT-01。
- **G**：错误 password 或不可达 endpoint。
- **W**：若 jar 能启动，必须继续发送真实 A2A 请求触发 Jedis 懒连接。
- **T**：启动或操作明确失败；不得以内存模式完成；日志不含 canary。
- **方法**：
  - `feat003WrongPasswordFailsWithoutInMemoryFallback()`；
  - `feat003UnreachableRedisFailsWithoutInMemoryFallback()`。

### FEAT-003.contract.custom-adapter — fake 客户 Adapter

- **状态**：contract，P0。
- **追溯**：TC-CONFIG-07、TC-FUNC-07、TC-SWITCH-03。
- **G**：acceptance test-scope runtime artifacts；本类 inner fake/spying RuntimeRedisClient；ApplicationContextRunner。
- **W**：注册 fake Bean，加载 Redis auto-configuration、TaskStore/checkpointer 相关配置。
- **T**：默认 Bean back-off；诊断显示 fake 类型；TaskStore/checkpointer 调用落到 spy；切回无 fake context 后默认实现生效。
- **方法**：`feat003CustomAdapterBacksOffDefaultAndServesConsumers()`，置于 `@Nested @Tag("contract")`。
- **说明**：不等同客户现场真实 JAR 通过。

## 5. standalone 行为子用例

框架落点：`RedisStandaloneBehaviorTest.java`。

### FEAT-003.standalone.task-ttl — Task get、TTL 与过期

- **状态**：runnable，P0。
- **追溯**：TC-FUNC-01/02、TC-CONFIG-NEW-02、TC-EXTRA-04 黑盒部分。
- **G**：ttl-seconds=小的测试值；全新 Redis。
- **W**：发送消息得到 taskId；立即 getTask；读取本次新增数据 TTL；轮询到过期。
- **T**：立即 getTask 内容/状态一致；TTL 在配置窗口；过期后 getTask 为 not-found/空语义，不固定内部 key。
- **方法**：`feat003TaskIsReadableAndExpiresWithConfiguredTtl()`。

### FEAT-003.standalone.named-ref — 命名 ref、DB2 与使用方复用

- **状态**：runnable，P0。
- **追溯**：SUP-CONFIG-01、A2A Task/checkpoint 复用 SHOULD。
- **G**：default→DB0、secondary→DB2；checkpointer.redis-ref=secondary；新 Redis。
- **W**：执行 Task + 多轮 checkpoint journey。
- **T**：DB0 无新增，DB2 有 Task 和 checkpoint 行为证据；日志 `redis-ref=secondary`。
- **方法**：`feat003NamedReferenceSelectsDatabaseForTaskAndCheckpoint()`。

### FEAT-003.standalone.restart-recovery — Task/checkpoint 跨 JVM

- **状态**：runnable，P0。
- **追溯**：SUP-PERSIST-01、TC-FUNC-01。
- **G**：Redis 保持运行；Turn1 保存唯一语义标志，记录 taskId/contextId。
- **W**：`stack.stop(mainplan)` → 等待停止 → `stack.start(mainplan)`；重新创建 client；getTask(taskId)；以同 contextId 继续。
- **T**：旧 Task 可读；新轮次召回重启前信息；PID 变化、端口复用。
- **方法**：`feat003TaskAndCheckpointSurviveAgentRestart()`。

### FEAT-003.standalone.reset — reset conversation

- **状态**：runnable，P0。
- **追溯**：TC-FUNC-03、TC-EXTRA-03。
- **G**：同一 context 已产生多轮历史和 Task。
- **W**：按当前 controller 契约调用 `POST /v1/reset_conversation`，随后用同 contextId 再发消息并查询旧 Task。
- **T**：旧历史不再召回；相关 Task 不再可读；runtime contract 断言补充 scanIter/del 精确映射。
- **方法**：`feat003ResetConversationRemovesTaskAndCheckpointState()`。

### FEAT-003.standalone.isolation — 并发隔离

- **状态**：runnable，P0。
- **追溯**：TC-FUNC-10。
- **W**：两个唯一 context 并发发送不同城市/日期，再分别追问。
- **T**：两条响应只包含各自上下文信息，后续恢复不串扰。
- **方法**：`feat003ConcurrentContextsRemainIsolated()`。

### FEAT-003.standalone.lifecycle — 生命周期

- **状态**：runnable，P1。
- **追溯**：TC-LIFE-01/02。
- **G**：记录 Redis client count 基线。
- **W**：三轮“启动→真实 Redis 操作→stop→start”；最后 close stack。
- **T**：每轮 PID 变化/端口复用/业务成功；连接数在轮询窗口回落且不单调增长；不要求为 0。
- **方法**：`feat003RepeatedRestartReleasesRedisConnections()`。

### FEAT-003.contract.standalone — standalone SPI 合同

- **状态**：contract，P0。
- **追溯**：TC-FUNC-04/05、TC-EXTRA-01/02/04 及完整 17 方法。
- **G**：acceptance test-scope 被测 runtime artifact；本类 standalone Redis。
- **W/T**：分组测试文本/二进制 get-set-setex、setnx、del、exists、expire、mget 有序结果、scanIter；`refresh_on_read=false`，只验证重新保存进入新 TTL 窗口。
- **方法组**：
  - `feat003StandaloneTextAndBinaryOperationsFollowContract()`；
  - `feat003StandaloneTtlExistsAndDeleteFollowContract()`；
  - `feat003StandaloneSetnxFollowsContract()`；
  - `feat003StandaloneMgetAndScanFollowContract()`。
- **标签**：`@Nested @Tag("contract")`。

## 6. cluster 与切换子用例

框架落点：`RedisClusterAndSwitchTest.java`。

### FEAT-003.cluster.equivalence — 真实 cluster 等价

- **状态**：env-gated，P0。
- **追溯**：TC-CONFIG-02、TC-FUNC-06、TC-SEC-03。
- **G**：真实多节点 cluster；三个 travel Agent 配置相同 nodes/ref/type=cluster。
- **W**：执行与 standalone 相同的全链 journey、Task get、TTL 和重启恢复。
- **T**：业务语义等价；日志显示 JedisClusterRuntimeRedisClient、nodes 数量、timeoutMs、passwordConfigured；不输出节点地址。
- **方法**：`feat003ClusterMatchesStandaloneBusinessSemantics()`。

### FEAT-003.cluster.nodes-priority — nodes 优先

- **状态**：env-gated，P0。
- **追溯**：SUP-CLUSTER-01。
- **G**：配置有效 nodes，同时把 standalone host/port 指向确定不可达地址。
- **W**：启动并执行业务。
- **T**：业务成功，证明 cluster client 未使用 host/port。
- **方法**：`feat003ClusterUsesNodesInsteadOfStandaloneHostPort()`。

### FEAT-003.cluster.database-ignored — database ignored

- **状态**：env-gated，P0。
- **追溯**：TC-CONFIG-04、TC-FUNC-09、TC-SEC-04。
- **G**：cluster + database=3。
- **W**：启动并执行业务。
- **T**：不因 database 失败；本次增量日志精确包含 `databaseIgnored=3`。
- **方法**：`feat003ClusterIgnoresStandaloneDatabaseSetting()`。

### FEAT-003.cluster.switch — 双向配置切换

- **状态**：env-gated，P0。
- **追溯**：TC-SWITCH-01/02。
- **W**：同一组三个 Maven 坐标 jar、同一业务步骤，依次运行 standalone→cluster→standalone。
- **T**：三阶段均成功；策略日志随配置改变；测试/业务代码不变；不要求迁移旧数据。
- **方法**：`feat003SwitchesStandaloneClusterAndBackWithoutBusinessChanges()`。

### FEAT-003.cluster.node-failure — 单节点故障

- **状态**：env-gated/P1。
- **G**：cluster 正常并完成一次读写。
- **W**：停止一个非唯一关键节点，再执行承诺范围内操作。
- **T**：操作继续或返回明确诊断；日志脱敏；不把 Redis 服务端容灾建设纳入产品验收。
- **方法**：`feat003ClusterNodeFailureHasDefinedObservableBehavior()`。

### FEAT-003.contract.cluster — cluster SPI 合同

- **状态**：contract，P0。
- **G**：本类 cluster fixture + acceptance test-scope runtime artifact。
- **W/T**：复用 standalone 合同断言；mget、批量 del 使用跨 slot keys；scanIter 覆盖多个节点；二进制重载等价；禁止 KEYS。
- **方法组**：
  - `feat003ClusterTextAndBinaryOperationsFollowContract()`；
  - `feat003ClusterCrossSlotMgetAndDeleteFollowContract()`；
  - `feat003ClusterScanCoversAllNodes()`。
- **标签**：`@Nested @Tag("contract")`。

## 7. 框架落点汇总

| Java 类 | 黑盒子用例 | contract 子用例 | 类内私有 fixture |
|---|---|---|---|
| `RedisConfigurationAndDiagnosticsTest` | config.*、security.* | custom-adapter | 日志切片、认证 Redis、fake client |
| `RedisStandaloneBehaviorTest` | standalone.* | standalone SPI | Redis 数据检查、reset、生命周期 |
| `RedisClusterAndSwitchTest` | cluster.* | cluster SPI | cluster factory、seed nodes、cluster 检查 |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
```

不新增 TestSupport 文件。若某段逻辑开始跨类复用，先调整子用例归组；确属跨特性通用能力时再进入 main。

## 8. v1.2 35 个 ID 追溯

| v1.2 ID | 本档落点 |
|---|---|
| CONFIG-01/02/03/04/05/06/08/09、CONFIG-NEW-01/02/03 | config/cluster 黑盒子用例 |
| CONFIG-07、FUNC-07、SWITCH-03 | custom-adapter contract + customer-site |
| FUNC-01/02/03/06/08/09/10 | standalone/cluster 黑盒；FUNC-03 走 reset |
| FUNC-04/05 | standalone/cluster contract |
| SWITCH-01/02 | cluster.switch |
| SEC-01/02/03/04 | config/security/cluster 日志子用例 |
| LIFE-01/02 | standalone.lifecycle |
| EXTRA-01/02/04 | standalone/cluster contract |
| EXTRA-03 | standalone.reset + contract scanIter |

新增追溯：SUP-CONFIG-01～04、SUP-FAULT-01、SUP-PERSIST-01、SUP-CLUSTER-01。

## 9. 标签与报告

```java
@Feature("FEAT-003: 智能体任务状态缓存")
@Tag("feat-003")
@Tag("integration")
class RedisStandaloneBehaviorTest {
    @Test
    @Tag("blackbox")
    @Stories({
            @Story("FEAT-003.standalone.restart-recovery: Task 与 checkpoint 跨 JVM 恢复")
    })
    @DisplayName("Feat-003 Agent 重启后 Task 和上下文仍可恢复")
    void feat003TaskAndCheckpointSurviveAgentRestart() { }

    @Nested
    @Tag("contract")
    class RuntimeRedisContract {
        // 每个方法同样带 @Test / @Stories / Feat-003 DisplayName
    }
}
```

Allure 报告必须区分 blackbox、contract、customer-site，禁止把直接 SPI 调用写成 jar 黑盒通过。

## 10. 运行方式

```bash
# 全部 FEAT-003
./mvnw -Dtest.env=openjiuwen -Dgroups=feat-003 test

# 指定类
./mvnw -Dtest.env=openjiuwen \
  -Dtest=RedisStandaloneBehaviorTest test

# 纯黑盒/合同（groups 表达式以当前 Surefire 配置为准）
./mvnw -Dtest.env=openjiuwen -Dgroups=blackbox test
./mvnw -Dtest.env=openjiuwen -Dgroups=contract test
```

## 11. 风险与代码生成约束

1. acceptance test-scope runtime artifact 版本必须与三个 Agent jar 内版本一致；不一致直接失败。
2. 当前 `RedisClusterMiddlewareSpringIT` 不是实际 cluster 读写证据，不能替代本档 cluster 用例。
3. Spring Boot/Jedis 可能懒连接；认证失败必须通过真实操作触发。
4. 日志有 append 污染风险，必须使用 offset slice。
5. cluster announce/MOVED 是 WSL 自动化最大风险，代码生成前先做 fixture smoke。
6. L2 要求 standalone host 必填，而实现可能默认 localhost；测试按规格期望失败并记录缺陷。
7. 客户 fake 只证明扩展点和内部消费者；现场真实 JAR/治理监控另行验收。
8. 后续生成代码时只创建本档列出的三个 Java 文件，不创建 TestSupport，不修改 agent-runtime-java。

## 12. 退出标准

- 三个 Java 类均可由 `feat-003` 标签执行。
- 三个类均具备 `@Feature("FEAT-003: 智能体任务状态缓存")`；所有方法具备 `@Stories`、`@Test` 和 `Feat-003` DisplayName 所要求的归属。
- v1.2 的 35 个 ID 和补充场景均有明确方法或现场证据。
- standalone、cluster、认证、reset、跨 JVM、不降级和生命周期有真实外部证据。
- SPI 17 方法在 acceptance contract tests 中覆盖，cluster 包含跨 slot。
- 所有新增代码只在 agent-runtime-acceptance；agent-runtime-java 工作树无修改。
