# FEAT-112 testplan 补充复核（2026-11-17，只做 SIT 口径）

状态：2026-11-17 已按本文修改 testplan、cases 与用例（PR 依赖项除外），结果见逐条复核记录“进展（2026-11-17，实施）”；同日补齐表二所需的三类部署夹具，相关用例已实现，待在 Linux + Docker 上实跑。

## 背景与口径

- 用户 2026-11-16 要求：测试仓只做 SIT，不写组件级用例。对外契约观测不到时依次从日志、持久化数据（Redis/DB）观测；都不可观测则提“特性不可观测”Issue。
- E5.5、E5.6 不动，等 PR 463、PR 538 合入后再定。
- 对照基线：特性档 `05af9cc3`；DA L2 = PR 538 新提交 `3b1283ef`（11-16 11:25，晚于我方评论 200956520，§1.4 改为“目标语义与当前差距”）；EXT L2 = PR 463 `8039074e`（未更新，护栏因果仍为旧口径）；代码 solution `origin/common`（`b4995983b`，比 `1127a9ece7b` 新 5 个提交，未动 compat/workflow/security/adapters）、core `e24bcb74d`。
- 代码事实由只读核对给出，关键几条已抽查：交付 yml 显式 `enabled: true`（`application.yml:66`）；执行池在 `submit()` 拒绝，位于认领之后；`enrichFromDataPlane` 只捕获 PG 访问异常，租户 `*` 的校验异常会在认领后抛出；`da_reflection_result` 只对六类失败事实输出；控制面取消先发事实、后提交清理续跑。

## 影响全局的代码事实

- 门的内部事件几乎不打日志：状态迁移、探针认领与释放、过期事实都没有日志；逐任务分类只有 DEBUG `terminal fact taskId= class= code=`，不带终态时刻。
- 门状态、窗口、终态事实全在内存；PG `workflows` 的 status 是流级别，不等于终态分类；Redis 只存 core checkpoint。
- 终态时刻取 `Instant.now()`，窗口水位是单调最大值；时钟回拨可造出窗口内乱序和过期事实。
- `done` 先下发、后记账；诊断查询会推进水位并触发惰性迁移。

## 表一：全局约定（§4、§6、§9）

| 位置 | 要补充或修改的内容 | 依据 |
|---|---|---|
| §4.4、§4.5 ④、§6 `protocol_unobservable`、§9 执行策略、B6 注 | 删除“组件级”验收层，Fixture 只标 `[SIT]`；写明观测顺序：契约 → 日志 → Redis/DB → 不可观测 Issue | 用户要求 |
| §4 日志观测通道 | DEBUG 部署形态（`logging.level.com.openjiuwen.da.compat.DaDegradationSupport=DEBUG`）看每任务分类；`da_reflection_result`（INFO，只对六类失败输出）；启动日志 `da degradation gate wired enabled= instanceId= (delivery default applied=)` | 代码 |
| §4 判定时机 | 先收到 `done` 后记账，判定前轮询诊断直到样本数变化 | 代码 |
| §4 诊断有副作用 | 诊断查询推进水位、可触发 CLOSED→OPEN / OPEN→HALF_OPEN；B5、C7 关键时段不插入查询 | 代码；DA L2 新版 §1.3 第 2 条 |

## 表二：组件级的 9 行改为 SIT 或 Issue

| 场景 | 改法 | 观测 | 可行性与依赖 |
|---|---|---|---|
| A1 | 仓内除 DA 外无宿主装配降级门，ext 单独装配无法 SIT 构造；改为 DA 宿主去掉 `enabled` 键，期望按 ext 缺省关闭（当前强制 true，预计 FAIL） | 诊断；启动日志 `delivery default applied=true` | 依赖 PR 538；ext 单独装配是否还验，提特性确认 |
| B6 | libfaketime 让 DA 时钟小幅回拨（小于窗口），后到终态早于已有样本仍在窗口内，期望照常计入 | 诊断 | 需 Linux，先验证 libfaketime 对 JVM 生效；不通则 Issue |
| B7 | 大幅回拨（不小于窗口），OPEN 与 HALF_OPEN 下旧任务失败过期，样本与状态不变；代际经“探针随后成功仍能关门”间接验证 | 诊断 | 同上；静默期按原始时钟，需设“只回拨不送事实”对照 |
| C4 | 期望改为外部效果：回 OPEN、`retryAfterSeconds` 接近完整静默期；代际经 C5、C8 验证 | 诊断 | 代际值本身见 Issue 3 |
| E5.3 | 模型先 `write_file` 写工作区外被拒，再在下一次模型调用前停 Redis（交付配置 redis checkpointer），任务无码无类目失败，期望计分子 | HTTP；诊断；DEBUG `class=INFRASTRUCTURE_FAILURE` | 中等把握，需实测 |
| E7 | 取消瞬间窗口在一次同步 `/cancel` 内完成，外部不可观测 → Issue；可补观察：`/cancel` 返回后被取消任务的模型调用是否仍在进行而名额已释放 | stub 在途数、诊断 | “实际落定”口径需特性设计确认 |
| E8 | `max-concurrent-tasks=1`；CLOSED 时让任务停在 awaiting；HALF_OPEN 下经 `/answer` 续跑占满执行池；新任务 → 503 `ENGINE_BUSY`，名额释放，保持 HALF_OPEN | HTTP、诊断；DEBUG `class=NOT_ADMITTED` | 需验证续跑是否占同一执行池；该路径无日志 |
| E9 | HALF_OPEN 探针在途时大幅回拨，再释放探针（成功、失败各一），期望名额释放、保持 HALF_OPEN、样本不变、下一任务可成为探针 | 诊断 | 同 B6 |
| E11 | PG 部署形态下 HALF_OPEN 带 `X-Da-Tenant-Id: *`：认领后抛出校验异常 → 400，名额由 try-with-resources 释放，下一任务可成为探针 | HTTP、诊断 | 代码已核实；需 PG |

## 表三：新增或修改的场景

| 场景 | 内容 | 观测 | 依据 | 依赖 |
|---|---|---|---|---|
| A2 | 交付配置启动 `enabled=true`；核对交付 yml 显式写键；启动日志 `delivery default applied=false` | 诊断、配置、日志 | 特性档；DA L2 新版 §1.4 | PR 538 |
| F2 新变体 | PG 部署下 `X-Da-Tenant-Id: *`：CLOSED 返回 400；OPEN 下代码先返回 503 `ENGINE_DEGRADED`（校验在门之后），按出口 18 预计违规 | HTTP 对照 | 验收出口 18 | 需 PG；租户头是否算“基本参数”在结论中注明 |
| F3 | “team 不存在”分支用同一 PG 部署形态 | HTTP 对照 | 出口 18 | 需 PG |
| E4 探针变体 | HALF_OPEN 探针以 181012 结束：名额释放、保持 HALF_OPEN、只计分母（当前代码关门清窗，预计 FAIL） | 诊断 | 特性档 §5.1.6 第 278 行；DA L2 新版 §1.4 | PR 538 |
| E4 文案变体 | 保留 `context_length_exceeded` 码、改提示文案，仍只计分母 | 诊断；DEBUG class | 特性档 §5.1.3 第 258 行；DA L2 新版 §7.4 | 中等把握 |
| E3 | 维持“出厂走不到”；!1131 合入后前置改为交付配置 | — | DA L2 新版 §1.4 | !1131 |
| I1 | 启动记录再核对 `enabled`、`delivery default applied` | 日志 | DA L2 新版 §1.4 | — |
| J4 | 前置写明 `answer-gate.stream-buffering-enabled=false` 及原因 | — | 本轮实跑 | — |

随第 13 条（分类矩阵）处理：最大迭代次数 → 计分子（关 loop-guard、调低 `deepanalyze.model.max-iterations`、stub 持续工具调用）；存储基础设施异常 → 计分子（停 Redis checkpointer）。

## 表四：特性不可观测 Issue

| # | 内容 | 影响 |
|---|---|---|
| 1 | 门状态迁移、探针认领与释放、过期事实无日志；终态事实日志仅 DEBUG 且不带终态时刻 | B6、B7、E9 激活证明；部署监控 |
| 2 | `prepare_failed` 外部不可构造（仅 Error/OOM 可达）；DA L2 新版目标为计基础设施异常 | 新场景（依赖 PR 538） |
| 3 | 故障代际不在诊断面与日志（可选，效果已经 C5、C8 可验） | C4 |
| 4 | 取消请求到达瞬间的窗口外部不可观测 | E7 |

## 非 testplan 事项

- 撤掉组件级用例类 `Feat112MechanismTest`、`Feat112TerminalClassificationTest`，pom `feat112-product` profile 中为组件级加入的依赖随之撤掉；cases §1.2 分层说明同步修改。
- E5.4 卡在 core `e24bcb74d` 与 solution `DaWorkflowRunner.memberRequestConfig` 的 `topP` 空指针，单独提给开发（`issue-core-topp-npe-20261116.md`）。
