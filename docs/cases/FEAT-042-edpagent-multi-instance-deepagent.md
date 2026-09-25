---
feature_id: FEAT-042
test_type: deepagent
scope: v0917; agent-core-java=930, agent-runtime-java=develop, agent-solution=common
deployable_units: [edp-agent-engine]
sut: edp-agent-engine multi profile 单 JVM 承载多个命名 EDPA 实例
status: designed-partially-runnable
features: [FEAT-042]
updated: 2026-09-18
---

# edp-agent-engine 验收：EDPA 多实例配置装配

## 1. 测试目标

以正式 `com.openjiuwen:edp-agent-engine:0.1.1:exec` Spring Boot JAR 为真实 SUT，经公开 A2A 目录、实例 Card、实例路由、同步/流式/任务接口和进程 stdout，验证 EDPA 在单 JVM 中的声明式多实例配置装配、继承覆盖、深度生效、隔离、启动失败原子性及单实例兼容。验收驱动不编译或调用 EDPA 产品类，不读取内部对象、私有存储 key 或工作空间实现。

## 2. 范围与非范围

范围：

- `deep-agent.instances` 的双实例、数字前导非法边界、三个实例、声明顺序与首实例默认语义。
- 五类实例配置项的继承/覆盖、启动日志可观测、场景人设、模型端点和模型名的执行层生效。
- 空名、非法名、重复声明和后置实例装配失败的启动期 fail-fast 与无部分发布。
- 显式/缺省工作空间、凭据响应/日志/错误隔离、实例 Card 技能真实性。
- 同一会话标识在同步、流式、异步任务路径下的 Redis 存储实例隔离。
- `instances` 为空或缺省时的单实例 Card、同步和流式兼容。

非范围：

- runtime 注册表、寻址算法、EDPA 内部 `DeepAgent`/Workspace、线程、缓存、数据库表和 Redis/KV key 形态。
- 运行期动态增删、禁用/启用、旧数据迁移、agent-bus、RDC 与 gateway。
- A2A 应答不注入自定义 metadata；实例身份按 Feature/L2 统一口径由实例 Card `name`、实例 URL 路径和启动日志承载。没有公开工作空间访问入口时，不以内部路径替代跨实例拒绝测试。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-042-edpagent-multi-instance.md`（2026-09-18） | Feature MUST、五字段 schema、915 验收、错误、隔离和兼容合同；实例身份不注入自定义 metadata。 |
| `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-Func-042-edpagent-multi-instance.md`（2026-09-17） | EDPA/runtime 边界、配置字段、公开路径、Card、错误码与当前交付说明。 |

## 4. 部署拓扑

```text
JUnit deepagent driver
  +-> classifier-aware process fixture
  |     `-> edp-agent-engine:0.1.1:exec --spring.profiles.active=multi
  |           -> agent-runtime hosted A2A ingress
  +-> deterministic OpenAI-compatible endpoints (input + request audit)
  `-> Redis container (only F042-20..22)
  <- /a2a/agents, per-instance Card/A2A JSON-RPC/SSE, task query, stdout
```

边界要求：

- classifier-aware Fixture 只解决正式 fat JAR 的 `exec` classifier 定位、随机端口、就绪探测、日志与 teardown，不替换产品装配或路由行为。
- 可控模型端点只生成输入响应并审计请求路径、model、Authorization；场景选择、配置合并、实例路由与会话隔离仍由真实 SUT 完成。
- 每个启动变体使用独立端口、日志和标识；负向用例必须在目标错误出现后确认进程未就绪，不能以任意启动失败判定通过。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F042-01 | 双实例声明、目录与顺序 | G：multi profile 依次声明 `code-assistant`、`data-assistant`，两套场景可加载 | W：启动并 GET `/a2a/agents` 与两张实例 Card | T：同一 PID 就绪；目录只含两实例且顺序一致；两张 Card 均为 200 | 正式 exec JAR、场景 Fixture、HTTP/Card 探针 |
| F042-02 | 全局继承与实例覆盖日志 | G：全局 max-iterations/model/backend；code 覆盖 max/model，data 覆盖 backend 且省略 max | W：启动并读取 stdout | T：每实例恰有可检索的生效配置记录，显示继承/覆盖后的 scenario、workspace、max-iterations、模型连接与模型名；不含凭据 | stdout 探针 |
| F042-03 | 场景、工作空间与人设差异 | G：code/data 使用带唯一身份提示的不同场景，code 显式 workspace，data 缺省 | W：分别经实例 A2A 同步入口发送唯一 canary，并检查模型请求与响应 | T：每个请求只带目标场景身份；响应只含目标身份；日志中的场景和 workspace 分属目标实例 | 场景 Fixture、模型审计、A2A 探针 |
| F042-04 | 模型连接与模型参数深度生效 | G：code 继承全局端点但覆盖模型名，data 覆盖端点与模型名 | W：分别对话并按请求 canary 查询模型审计 | T：请求到达各自声明端点，model 字段分别等于目标值，响应与 A2A 终态对应目标 canary | 双路径 OpenAI-compatible Fixture |
| F042-05 | 最大迭代轮数深度生效 | G：两个实例配置不同 max-iterations，模型 Fixture 可稳定制造超过较小阈值的工具循环 | W：分别触发相同循环任务并统计公开轨迹/模型请求 | T：两实例分别在各自阈值终止且互不影响 | 迭代 Fixture；当前 blocked，待稳定公开轨迹与工具循环输入 |
| F042-06 | 空实例名 fail-fast | G：附加配置含空字符串 key | W：启动负向变体 | T：ready 前退出；错误定位空实例名配置；没有任何实例目录可访问 | 版本化负向 YAML、进程探针 |
| F042-07 | 非法实例名 fail-fast | G：新增含空格、斜杠或连字符前导的实例名 | W：逐个启动负向变体 | T：均在 ready 前退出且错误含稳定非法名语义，不因其他配置失败 | 参数化进程探针 |
| F042-08 | 重复实例名 fail-fast | G：附加 YAML 在同一 `instances` Map 重复声明 key | W：启动负向变体 | T：配置解析/装配期在 ready 前失败并明确重复 key；不进入运行期 | 版本化重复 key YAML、进程探针 |
| F042-09 | 数字开头实例名非法 | G：新增数字开头实例 `1code` 且其余配置合法 | W：启动负向变体 | T：ready 前退出；错误包含 `EDPA_INSTANCE_NAME_INVALID`、原始实例名与合法规则；不发布目录或 Card | 参数化进程探针 |
| F042-10 | 三个实例无二实例硬上限 | G：声明 code、data、audit 三实例且资源足够 | W：启动并获取目录/三张 Card | T：三实例全部发布且顺序一致，无固定二实例限制 | 三实例配置、HTTP/Card 探针 |
| F042-11 | 后置实例装配失败原子回滚 | G：首实例合法，后置实例 scenario 指向不存在目录 | W：启动并持续探测进程/端口 | T：进程以 `EDPA_INSTANCE_ASSEMBLY_FAILED` 失败；没有目录或首实例 Card 曾进入 ready | 负向进程与端口探针 |
| F042-12 | 工作空间缺省推导 | G：code 显式 workspace，data 未声明 workspace | W：启动并读取逐实例生效配置日志 | T：code 使用显式目录；data 显示 `workspaces/data-assistant` 统一推导值，二者不同 | stdout 探针 |
| F042-13 | 跨实例工作空间拒绝 | G：存在文档化公开工作空间读写入口与稳定错误合同 | W：经 A 寻址上下文访问 B 的路径并检查副作用 | T：明确拒绝且 B 工作空间无变化 | 公开 workspace client；当前 blocked |
| F042-14 | 凭据不进入对端响应和日志 | G：code/data 使用不同测试 canary 凭据 | W：分别正常调用，采集 A2A 响应、SUT stdout 与模型请求头 | T：请求头只带目标凭据；两个凭据均不出现在任何响应或 SUT 日志 | 模型审计、响应/日志脱敏探针 |
| F042-15 | 凭据不进入对端错误 | G：data 端点对专用 canary 返回受控 401，code 凭据不同 | W：调用 data 并取得公开失败结果 | T：错误可归因模型调用失败，错误响应与日志均不含任一凭据，尤其不含 code 凭据 | 受控模型错误 Fixture |
| F042-16 | 实例 Card 技能真实性 | G：两场景分别仅注册 `code_review_skill` 与 `analyze_data_skill`，Card 声明对齐 | W：获取两张实例 Card | T：每张 Card 的 skill id/name/description 只对应本场景，不混入对端技能 | 版本化场景与 Card 探针 |
| F042-20 | Redis 同步会话隔离 | G：真实 Redis、A/B 同 contextId、同步写入 marker | W：停止并以相同 Redis/端口配置重启 SUT，再同步回忆 | T：A/B 各自只恢复本实例历史；不读取 Redis key | Redis、A2A sync |
| F042-21 | Redis 流式会话隔离 | G：真实 Redis、A/B 同 contextId、流式写入 marker | W：重启后分别流式回忆 | T：A/B SSE 结果各自只含本实例历史 | Redis、A2A SSE |
| F042-22 | Redis 异步任务隔离 | G：真实 Redis、A/B 同 contextId 各创建任务 | W：重启后按实例查询原 taskId 并交叉查询 | T：owner 可恢复，peer 为目标局部 TaskNotFound，不读取内部 key | Redis、A2A task 探针 |
| F042-23 | A2A 实例身份承载 | G：两实例 Card `name` 缺省为实例名，分别使用 `/a2a/agents/{agentId}` 路径，启动日志逐实例输出身份 | W：获取两张 Card、分别调用实例路径并检查启动日志 | T：Card `name`、URL 路径与日志均能区分目标实例；不要求 A2A 应答注入自定义 metadata | Card、A2A 路径与 stdout 探针 |
| F042-24 | 首实例为默认实例 | G：code 为首个声明实例，data 为第二个 | W：GET 目录并向根 `/a2a` 发同步请求 | T：`defaultAgent` 为 code；根请求只进入 code 模型路径并返回 code 身份 | 目录、根 A2A、模型审计 |
| F042-25 | 空 `instances` 单实例回退 | G：不激活 multi，附加配置显式声明空 Map，使用合法单场景 | W：启动、GET legacy Card、发送同步/流式请求 | T：进程走单实例公开入口；Card 与响应结构有效；不发布多实例目录 | 空 Map YAML、legacy A2A 探针 |
| F042-26 | 缺省 `instances` 单实例兼容 | G：不激活 multi 且完全不声明 `instances`，使用同一单场景 | W：启动、GET legacy Card、发送同步/流式请求 | T：与 F042-25 的 Card 关键字段、入口、状态和响应结构一致；不发布多实例目录 | legacy 配置、A2A 探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `edp-agent-engine` | 真实 SUT | 正式 `com.openjiuwen:edp-agent-engine:0.1.1:exec` fat JAR；测试不依赖产品实现类。 |
| agent-runtime hosted ingress | 真实依赖 | 随 engine 制品运行，提供目录、Card、A2A 实例路径与任务接口。 |
| code/data 场景 | Fixture | 位于 `src/test/resources/testdata/integration/deepagent_deepresearch/feat042/scenarios`，各含 `governance/`、`skills/` 与唯一人设；只提供业务输入。 |
| 模型端点 | Fixture | OpenAI-compatible HTTP 服务，按 URL 路径返回确定性 canary 并记录 model/header/body；不实现实例路由和配置合并。 |
| classifier-aware launcher | Fixture | 从 Maven 仓解析 `-exec.jar`，负责独立端口、就绪探测、stdout 和 teardown；不修改 acceptance 生命周期代码。 |
| Redis | 真实依赖 | 正式制品当前启动所需；F042-20..22 另通过公开重启恢复结果验收，不读取内部 key。 |

## 7. 关键链路断言

- F042-01/02/06..12/16/24..26 是启动、配置、Card 与兼容主门禁；负向场景必须同时命中目标错误与 ready 前退出。
- F042-03/04/14/15/20..22 只断言稳定的路径、请求字段、身份 canary、协议状态和实例隔离，不逐字匹配模型自然语言。
- Feature 与 L2 已统一为实例名必须字母开头；F042-09 验证数字前导名按 `EDPA_INSTANCE_NAME_INVALID` 在 ready 前失败；重复 key 的阶段性错误码冲突在 F042-08 结果中单独分诊。
- F042-05/13 在门禁解除前保持 SKIPPED/INCONCLUSIVE，不计为 PASS；不得用内部最大迭代对象或 workspace 路径访问猜测补齐。F042-23 按 Card `name`、实例 URL 与日志三层公开身份机制判定，不把自定义 metadata 作为 Oracle。
- 不断言 `HostedAgentDefinitions`、内部 `AgentCard.id`、`TenantContextHolder`、内部 Redis key、线程、缓存、类名或调用顺序。

## 8. 执行策略

- Smoke：F042-01、F042-02、F042-06、F042-07、F042-09、F042-11、F042-16、F042-24、F042-26。
- Full suite：F042-01..16、F042-20..26；F042-05/13 在门禁未解除时为 SKIPPED/INCONCLUSIVE，不计通过。
- P0 必须全绿：F042-01、F042-02、F042-06..12、F042-14..16、F042-20..22、F042-24..26 中当前可执行项。
- 依赖门禁：F042-05 需稳定多轮工具循环与公开轨迹；F042-13 需公开 workspace 接口/错误码；Redis 或 Docker 不可用影响当前正式制品启动及 F042-20..22，分类为环境 ERROR。
- LLM 或异步场景：首选确定性端点；每个请求和轮询均有 180 秒 watchdog。受控输入下超时或协议终态错误为 FAIL，外部 Redis/Docker 不可用为 ERROR/INCONCLUSIVE。
- 标识隔离：每次使用 `FEAT042_<场景>_<UUID>` canary 和唯一 context/task；只使用非敏感测试凭据，真实 `LLM_*` 仅由操作者环境注入，证据不记录值。

```bash
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=Feat042EdpaMultiInstanceBlackboxTest
```

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| workspace 无公开读写面 | F042-13 无法触发 | blocked | 提供公开入口、稳定错误与副作用观察面 |
| max-iterations 无稳定外部轨迹/循环输入 | F042-05 不能排除模型提前结束 | blocked | 提供确定性多轮 Fixture 与公开终止原因/轨迹 |
| 重复 key 在 Map 绑定前已由 YAML 解析 | F042-08 可证明 fail-fast，但 `EDPA_INSTANCE_NAME_DUPLICATE` 分支不可达 | 部分可判定 | 明确配置解析错误是否满足 Feature 错误合同 |
| 正式 fat JAR 使用 `exec` classifier | 默认无 classifier 解析器只能定位 thin JAR | 已由测试级 launcher 解锁 | 产品发布无 classifier 可执行 JAR，或框架正式支持 classifier |
