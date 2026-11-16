---
feature_id: FEAT-102
feature_title: agent-memory 策略管理与审计（参数热调与审计回滚）
scope: vNext; docs/develop/02-features/FEAT-102-agent-memory-策略配置管理.md (updated 2026-10-23)
deployable_units: [agent-memory (Python, jiuwen_memory_entry.http_server)]
sut: agent-memory 策略管理面（POST /v1/admin 增强 + policy_history/policy_rollback/policy_snapshot_* 新增端点）
status: executed-real-artifacts
features: [FEAT-102]
updated: 2026-11-09
---

# FEAT-102 — 策略管理与审计（参数热调与审计回滚）测试设计

## 1. 测试目标

以 agent-memory（Python，`jiuwen_memory_entry.http_server`，offline profile，dev auth）为 SUT，
验证 FEAT-102 定义的全局策略 KV 持久化、策略变更记录、变更历史查询、单键回滚、快照创建/对比/回滚、
4 键迁移（`retrieval.min_score` / `retrieval.over_fetch_floor` / `retrieval.rerank_max` /
`evolver.dedup_high_similarity`）、`rerank.enabled` 补消费者与空间级 `pipeline_profiles` 覆盖的外部可观察行为。

观察 surface 限定为 HTTP wire 契约：`POST /v1/admin_set` / `/v1/admin_get` / `/v1/admin_all`
（MemoryAPI 同名方法即 HTTP verb）、
特性文档 §4.5 声明的新端点 `POST /v1/policy_history`、`/v1/policy_rollback`、
`/v1/policy_snapshot_create`、`/v1/policy_snapshot_diff`、`/v1/policy_snapshot_rollback`，
以及既有 `POST /v1/search`、`POST /v1/get_space_policy`。本设计不编译/导入 SUT 内部类、不读取
SUT 内部状态或私有 SPI，全部断言落在 HTTP 请求/响应上。

## 2. 范围与非范围

### 范围

- 策略运行时热调：4 个迁移键经 `admin_set` 可写可读（FR-01；含 `evolver.dedup_high_similarity`，F102-15）。
- 策略持久化：`admin_set` 后重启 SUT 进程，值不回退装配期默认（FR-02 / AC-02）；保留活键
  （`lifecycle.expired_active.target`、`lifecycle.superseded.target`、`scope.require_space`、
  `hierarchy.enabled`）同口径验证（F102-02 / F102-23）；本期新增键
  （`lifecycle.forgotten_retention_days`、`lifecycle.purge_forgotten.enabled`、
  `access_tracker.flush_interval`）的持久化与审计同口径验证（F102-16）。
- 策略变更历史：`policy_history` 按时间倒序返回含旧值/新值/时间戳/操作者标识（actor_org/actor_user）
  的记录，支持 limit 截断，支持 space 作用域查询（FR-05 / AC-01；F102-03/13/18）。
- 单键回滚：`policy_rollback` 空 change_id 回最近一条旧值、非空回指定记录旧值，回滚本身落新记录；
  支持 space 作用域（FR-06 / AC-03；F102-04/05/19）。
- 快照三件套：`policy_snapshot_create` 返回快照标识（含 space 空间快照）；`policy_snapshot_diff`
  返回 added/removed/changed 完整三态（removed 经 `set_space_policy` 清空构造）；
  `policy_snapshot_rollback` 逐键写回、各落一条记录、返回受影响键列表（FR-07 / A2 / AC-04；
  F102-06/07/20/21）。
- `rerank.enabled` 键可读写且设置后 `/v1/search` 链路正常（FR-04 最小黑盒口径）。
- 空间级覆盖：`admin_set` 带 space 参数写空间级 `pipeline_profiles` 全部子键 + `retention` 映射
  （FR-08 / DEC-04；F102-09/22）；`set_policy` 后空间策略缓存失效立即可见（§3.6 策略注入查找链
  的管理面可观察口径，F102-18）。
- 审计分工：设置失败（未知键拒绝）不落变更记录（§3.6；F102-24）。
- 异常语义：未知键 set 拒绝（PolicyError → HTTP 400）、不存在键 history 返回空、
  不存在 snapshot_id 返回 NotFound（HTTP 404）、`access_tracker.flush_interval` 非法值
  原样存储且 SUT 存活（F102-10/11/12/17）。

### 非范围

- `rerank.enabled` 的精排排序差异断言（AC-06 精排后顺序可观测）：依赖装配期注入真实 Reranker
  与嵌入模型，排序差异不可稳定判定——由 L2/白盒联动验证承接。
- 入口审计（动作级）与变更审计（值级）的 actor 关联（AC-07）：入口审计通道在本环境下经
  `/v1/audit` 可查，但其 actor 字段在 dev auth 下恒为 local/developer，关联性断言退化为同值恒真，
  由 required-auth 的 e2e 环境承接。
- ADMINISTER_SYSTEM 权限校验（403 路径）：dev auth 模式身份固定，无法构造非管理员主体，
  由 required-auth e2e 承接。
- KV 写异常回滚（AC-08）：需进程外故障注入 KV 存储层，offline profile 无注入点；
  由故障注入专项承接。
- `lifecycle.forgotten_retention_days`、`lifecycle.purge_forgotten.enabled`、
  `access_tracker.flush_interval` 的消费者行为：归 FEAT-102 生命周期与容量治理特性文档，
  本设计只验证其作为策略键的持久化与审计。
- 抽取参数/调用级参数/同族非主导参数不迁移项（OUT）：不验证。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `docs/develop/02-features/FEAT-102-agent-memory-策略配置管理.md`（updated 2026-10-23） | 行为口径唯一 Oracle：§3.6 能力要求、§4.5 端点与策略键、§6.5/6.6 语义、§9.2 验收口径 A1/A2 |
| `docs/develop/01-scenarios/FRS-102-agent-memory-策略配置管理.md`（v0.1, 2026-10-23） | FR-01~FR-08 功能要求、AC-01~AC-08 验收标准、§10 输入输出契约（NotFoundError/KV 异常语义）、§8 KV 前缀隔离 |
| `jiuwen_memory_entry/core/handler.py`（_ROUTES/_admin）、`jiuwen_memory_entry/core/error_response.py` | 现有 admin verb wire 契约与错误封套（仅确认请求形态，不作期望值来源） |

## 4. 部署拓扑

```text
JUnit 黑盒驱动（Feat102PolicyConfigBlackboxTest，自管 SUT 进程）
  `-> agent-memory（Python, 从 feature_source 源码目录直接拉起）
        python -m jiuwen_memory_entry.http_server --auth-mode dev --host 127.0.0.1 --port 8237
        offline profile；POST /v1/<verb>；GET /healthz
```

边界要求：

- **真实 SUT**：agent-memory 从 `feature_source`（D:/code/test/agent-memory）源码目录以独立进程拉起，
  每次执行前确认工作区为最新源码（等价 provision；Python 无构建产物）。
- **真实依赖（不 mock）**：offline profile 内置 KV/嵌入等本地后端；无外部中间件。
- **观察 surface**：仅 HTTP（/v1/*、/healthz）。不读 SUT 日志/内部对象/数据库。
- **隔离策略**：策略键为全局作用域，测试键值选择避开业务语义键的歧义值；测试类自管
  专用端口 8237（可经 `-Dsut.memory102.port` 覆盖），不与手工拉起的 8137 冲突。
- **故障注入**：本设计无进程内注入；重启语义通过测试类内停/起 SUT 进程实现（进程外手段）。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F102-01 | 迁移键运行时热调 | SUT 已拉起；`retrieval.min_score` 为合法策略键 | `POST /v1/admin_set {key:"retrieval.min_score", value:"0.3"}` → `POST /v1/admin_get {key:"retrieval.min_score"}` → `POST /v1/admin_all {}` | set 响应 HTTP 200；get 响应 `value=="0.3"`；all 响应 `policies["retrieval.min_score"]=="0.3"`（键存在于全局策略集） | 自管 SUT |
| F102-02 | 保留活键热调 + 跨重启持久化 | SUT 已拉起；SUT 配置层声明 `memory_api.kv_store.default={target:sqlite, params:{db_path:<落盘文件>}}`（FR-02 持久化要求 KV 后端跨进程存续；SUT 默认 `kv_store=memory` 为进程内态，且 HTTP 入口 Server.build 只把 `memory_api` 段交给内核装配，覆盖须嵌套于该段下） | `POST /v1/admin_set {key:"lifecycle.expired_active.target", value:"archived"}` → get 确认 → 停止 SUT 进程 → 重新拉起 → `POST /v1/admin_get {key:"lifecycle.expired_active.target"}` | set/get 均 200；重启后 get 响应 `value=="archived"`（≠ 默认 `"forgotten"`） | 自管 SUT（需进程重启） |
| F102-03 | 策略变更历史查询 | F102-01 已产生 ≥1 条变更 | `POST /v1/policy_history {key:"retrieval.min_score", limit:10}` | HTTP 200；响应为记录数组且按时间倒序；每条含非空 `old_value`/`new_value`/`timestamp` 字段 | 自管 SUT |
| F102-04 | 单键回滚（最近一条） | 对 `retrieval.over_fetch_floor` 已连续 set 两次（如 "60"→"80"→"100"） | `POST /v1/policy_rollback {key:"retrieval.over_fetch_floor"}` → get → `POST /v1/policy_history` | get `value=="80"`（最近一条记录的 old_value）；history 新增多一条记录且其 `new_value=="80"`（回滚本身落记录） | 自管 SUT |
| F102-05 | 单键回滚（指定 change_id） | 对 `retrieval.rerank_max` 已连续 set 两次并取得第一次的 change_id | `POST /v1/policy_rollback {key:"retrieval.rerank_max", change_id:<第一条>}` → get | get `value` 等于指定记录的 `old_value`（两次变更前的初值） | 自管 SUT |
| F102-06 | 快照创建与对比 | SUT 已拉起 | `POST /v1/policy_snapshot_create {}` → set `retrieval.min_score` 新值 → `POST /v1/policy_snapshot_diff {snapshot_id}` | create 返回非空快照标识（FRS-102 §10.1 `snapshot_id: str`，HTTP 面对返回值机械转换 → 响应体为非空 JSON 字符串）；diff 响应 `changed` 含 `retrieval.min_score`（键存在且值不同），`added`/`removed` 为空对象或不含该键 | 自管 SUT |
| F102-07 | 快照回滚 | F102-06 的快照存在且 diff 非空 | `POST /v1/policy_snapshot_rollback {snapshot_id}` → get `retrieval.min_score` → `POST /v1/policy_history` | 响应返回受影响键列表且含 `retrieval.min_score`；get 值回到快照值；history 中该键新增恰 1 条回滚记录 | 自管 SUT |
| F102-08 | rerank.enabled 热调可读写 | SUT 已拉起 | `POST /v1/admin_set {key:"rerank.enabled", value:"false"}` → `POST /v1/admin_get {key:"rerank.enabled"}` → set 回 `"true"` → get | set HTTP 200；get `value=="false"`；set 回 `"true"` 后 get `value=="true"`（键可读写、值往返一致） | 自管 SUT |
| F102-09 | 空间级 pipeline_profiles 覆盖（admin_set space 参数） | SUT 已拉起；测试空间已创建 | `POST /v1/admin_set {key:"retrieval.min_score", value:"0.3", space:["local","f102-space"]}`（FRS-102 §10.1 `space: tuple[str, str]` → wire 形态为 JSON 数组 `[org, space]`；键为全局策略键，空间级自动映射 `pipeline_profiles.retrieval_min_score`） → `POST /v1/get_space_policy {org:"local", space:"f102-space"}` | set HTTP 200；get_space_policy 响应 `pipeline_profiles.retrieval_min_score=="0.3"` | 自管 SUT |
| F102-10 | 异常：未知键 set 拒绝 | SUT 已拉起 | `POST /v1/admin_set {key:"not.exist.key", value:"x"}` | HTTP 400 且响应体 `error=="PolicyError"`；随后 admin_get 该键 HTTP 400（键未被创建） | 自管 SUT |
| F102-11 | 异常：history 查无记录 | SUT 已拉起 | `POST /v1/policy_history {key:"scope.require_space"}`（未变更过的键） | HTTP 200 且记录列表为空数组（不报错） | 自管 SUT |
| F102-12 | 异常：快照 id 不存在 | SUT 已拉起 | `POST /v1/policy_snapshot_diff {snapshot_id:"snap_not_exist"}` 与 `POST /v1/policy_snapshot_rollback {snapshot_id:"snap_not_exist"}` | 均 HTTP 404 且 `error=="NotFoundError"` | 自管 SUT |
| F102-13 | 边界：limit 截断 | 对同一键已产生 ≥3 条变更记录 | `POST /v1/policy_history {key:<该键>, limit:2}` | 返回记录数 ≤2 且为最近 2 条（首条时间戳 ≥ 末条时间戳，倒序） | 自管 SUT |
| F102-14 | 时序/并发：并发 set 记录完整 | SUT 已拉起 | 并发发起 5 次 `POST /v1/admin_set`（同键不同值）→ 待全部返回 → `POST /v1/policy_history {key}` | 5 次调用均成功；history 记录数 ≥5；最终 get 值 ∈ 5 个写入值之一 | 自管 SUT |
| F102-15 | 迁移键 `evolver.dedup_high_similarity` 热调 | SUT 已拉起 | `POST /v1/admin_set {key:"evolver.dedup_high_similarity", value:"0.85"}` → `admin_get` → `admin_all` | set/get 均 200；get 响应 `"0.85"`；all 响应含该键且值 `"0.85"` | 自管 SUT |
| F102-16 | 本期新增键持久化与审计（3 键） | SUT 已拉起（SQLite KV） | `admin_set`/`admin_get` `lifecycle.forgotten_retention_days="45"`、`lifecycle.purge_forgotten.enabled="false"`、`access_tracker.flush_interval="120"` → `policy_history`（forgotten_retention_days）→ 重启 SUT → `admin_get` 三键 | 三键 set/get 均 200 且值一致；history 非空含 old_value/new_value/timestamp；重启后三键值恢复（≠ 默认 30/true/60） | 自管 SUT |
| F102-17 | `access_tracker.flush_interval` 非法值语义 | SUT 已拉起 | `admin_set {key:"access_tracker.flush_interval", value:"0"}` → `admin_get`；`value:"abc"` → `admin_get`；`GET /healthz` | 非法值 set 均 200 且 get 原样返回（键值层不拒绝）；SUT 仍健康（消费侧 `max(·,1.0)`/异常回落为 daemon 时序行为，行为级断言 deferred） | 自管 SUT |
| F102-18 | 空间级变更历史查询与缓存失效 | 空间 `f102-hist-space` 已创建 | `admin_set {key:"retrieval.min_score", value:"0.35", space:["local","f102-hist-space"]}` → `policy_history {key, space}` → 二次 set `"0.4"` → `get_space_policy` | history 200 非空；最新记录 `key=="retrieval.min_score"`、`new_value=="0.35"`、`scope.org=="local"`、`scope.space=="f102-hist-space"`、`actor_org`/`actor_user` 非空；二次 set 后 get_space_policy 立即 `"0.4"`（set_policy 缓存失效） | 自管 SUT |
| F102-19 | 空间级单键回滚 | 空间 `f102-rollback-space` 已创建；该空间键已两次变更（"70"→"90"） | `POST /v1/policy_rollback {key:"retrieval.over_fetch_floor", space:["local","f102-rollback-space"]}` → `get_space_policy` → `policy_history {key, space}` | 回滚 200；空间策略值 `"70"`（最近一条记录 old_value）；history 最新一条 `new_value=="70"`（回滚落记录） | 自管 SUT |
| F102-20 | 空间级快照三件套 | 空间 `f102-snap-space` 已创建；基线值已写 | `policy_snapshot_create {space}` → set `"55"` → `policy_snapshot_diff {snapshot_id}` → `policy_snapshot_rollback {snapshot_id}` → `get_space_policy` → `policy_history {key, space}` | create 200 返回非空标识；diff.changed 含 `pipeline_profiles.retrieval_rerank_max`（值 `"55"`）且不在 added；rollback 200 且受影响列表含该键；空间策略回到 `"50"`；history 最新一条 `new_value=="50"` | 自管 SUT |
| F102-21 | 快照差异 added/removed 完整场景 | 空间 `f102-diff-space` 已创建 | 快照 S0（空 profiles）→ set `"55"` → diff S0（added）；快照 S1（含键）→ `set_space_policy` 清空 `pipeline_profiles` → diff S1（removed） | diff S0 `added["pipeline_profiles.retrieval_rerank_max"]=="55"` 且不在 changed；diff S1 `removed["pipeline_profiles.retrieval_rerank_max"]=="55"` 且不在 added | 自管 SUT |
| F102-22 | 空间级键映射补全 | 空间 `f102-profiles-space` 已创建 | `admin_set space` 写 `evolver.dedup_high_similarity="0.8"`、`rerank.enabled="false"`、`lifecycle.forgotten_retention_days="45"` → 各 `get_space_policy` | 分别映射到 `pipeline_profiles.evolver_dedup_high_similarity=="0.8"`、`pipeline_profiles.rerank_enabled=="false"`、`retention.forgotten_retention_days=="45"` | 自管 SUT |
| F102-23 | 其余保留活键跨重启持久化 | SUT 已拉起（SQLite KV） | `admin_set` `scope.require_space="true"`、`hierarchy.enabled="true"`、`lifecycle.superseded.target="archived"` → 重启 SUT → `admin_get` 三键 | 三键 set 200；重启后值恢复 `"true"`/`"true"`/`"archived"`（≠ 默认 false/false/"forgotten"） | 自管 SUT |
| F102-24 | 设置失败不落变更记录 | SUT 已拉起 | `admin_set {key:"not.exist.key", value:"x"}`（400 拒绝）→ `policy_history {key:"not.exist.key"}` | set 400；history 200 且记录数组为空（§3.6 审计分工：设置失败不落变更记录） | 自管 SUT |

### 5.1 显式 deferred / 白盒联动项

| 项 | 承接方 |
|---|---|
| AC-06 精排排序差异可观测（rerank.enabled 消费者行为） | L2 白盒联动 + required-auth e2e（需装配期注入真实 Reranker） |
| AC-07 入口审计与变更审计 actor 关联 | required-auth e2e（dev auth 身份恒定使关联断言退化） |
| AC-08 KV 写异常内存回滚 | 故障注入专项（进程外 KV 注入点） |
| ADMINISTER_SYSTEM 鉴权 403 | required-auth e2e |
| rerank.enabled 设置后检索管线行为（AC-06 的 search leg） | 支持 space 的存储后端 profile——offline InMemoryEngine 拒绝 scope.space（"only supports scope.space == ''"）与「scope.space is required」校验互相矛盾，检索链路在 offline 下不可用 |
| 检索/演进参数热调生效于下次检索/演进的行为级断言（AC-01 全链路） | 同上：依赖可用的检索链路（迁移键热调的读写面由 F102-01/15 承载） |
| `access_tracker.flush_interval` 消费侧行为级断言（daemon 下个 tick 1s 生效、非法值夹紧/回落的时序观测） | 时序专项（消费侧实现 `access_tracker._current_interval`：`max(float(·),1.0)` 夹紧 + 异常回落缺省；键值层面「非法值原样存储 + SUT 存活」已由 F102-17 承载） |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| agent-memory（jiuwen_memory_entry.http_server，8237） | 真实 SUT | 测试类经 ProcessBuilder 从 feature_source 源码目录拉起/停止/重启；offline profile；dev auth；配置层含 `memory_api.kv_store.default` SQLite 落盘后端（F102-02 持久化断言前提，见场景矩阵） |
| python 解释器 | 真实依赖 | PATH 上的 python3（≥3.11），能 import jiuwen_memory |
| 自管进程 Fixture（Feat102SutProcess） | Fixture | 封装 start/stop/restart/healthz 等待；`-Dsut.memory102.port`、`-Dsut.memory102.sourceDir`（或 env `SUT_MEMORY_SOURCE_DIR`）可覆盖默认 |
| LLM | 真实依赖（本特性用例不需要） | 策略管理面与 /v1/search（本地嵌入）均不依赖 LLM_* 凭据 |

## 7. 关键链路断言（主门禁）

以下场景为主门禁，任一失败即判定 FEAT-102 未通过：

1. **F102-01**（4 键迁移热调）：`admin_set`/`admin_get`/`admin_all` 对 `retrieval.min_score`
   返回值精确等于写入值 `"0.3"`——期望值逐字来自特性文档 §4.5 键名与 §3.6 默认语义。
2. **F102-02**（持久化跨重启）：重启后 `lifecycle.expired_active.target` 仍为 `"archived"`
   ——对应 FR-02「重启后从 KV 恢复不回退装配期默认」（默认 `"forgotten"` 为字典策略缺省表取值）。
3. **F102-03**（变更历史）：`policy_history` 存在、可查、倒序、含 old_value/new_value/timestamp
   ——对应 FR-05 / A1。
4. **F102-04/05**（单键回滚）：回滚值精确等于目标记录 old_value，回滚本身落新记录——对应 FR-06 / A1。
5. **F102-06/07**（快照三件套）：snapshot_id 可得、diff 含 changed 键、rollback 逐键写回并返回
   受影响键列表——对应 FR-07 / A2。

失败语义与基线同构：新增端点未实现时表现为 HTTP 404 `error=="UnknownVerb"`（路由表无此 verb），
与 F102-12 的 NotFound（404）路径可区分——前者是端点缺失，后者是资源缺失。

## 8. 执行策略

- **Smoke 集合**（必跑）：F102-01、F102-02、F102-03。
- **Full suite**：F102-01 ~ F102-24 全量。
- **P0 全绿门禁**：F102-01/02/03/04/06/07（主门禁场景）。
- **依赖门禁**：python 可用且 feature_source 目录存在；端口 8237 未被占用；无 LLM 依赖。
- **执行命令**：

```bash
# 全量（按特性 Tag 过滤）
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-102 -Dmaven.test.failure.ignore=true

# 单类
./mvnw test -Dtest.env=openjiuwen -Dtest=Feat102PolicyConfigBlackboxTest

# 覆盖 SUT 源码目录/端口（可选）
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-102 \
  -Dsut.memory102.sourceDir=D:/code/test/agent-memory -Dsut.memory102.port=8237
```

- **推荐执行 agent**：`D:/code/test/agent-solution/common/example/personal-assistant-deepagent-demo`
  （feat_test.yaml recommended_agent；本特性用例不依赖该 agent，仅在策略键持久化影响
  agent 链路时作为附加观察通道，非本设计断言对象）。
