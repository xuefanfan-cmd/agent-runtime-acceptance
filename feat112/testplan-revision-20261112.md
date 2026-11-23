# FEAT-112 testplan 修订记录（2026-11-12 首轮、2026-11-16 第二轮、2026-11-17 第三轮，已提 PR 549）

## 状态

- **2026-11-17 已提交**：[Technical-AF/docs PR 549](https://gitcode.com/Technical-AF/docs/merge_requests/549)，分支 `docs/feat-112-testplan-revision`（从 main `11369710` 开出，提交 `e798933f` 内容修订、`5791d21c` 统一编号，只改该 testplan 一个文件，内容与[修订后全文](testplan-revision-20261112.revised.md)逐字节一致）；PR 说明见 [pr-testplan-revision-20261117.md](pr-testplan-revision-20261117.md)。合入并回读后同步本地镜像与 README 中的 commit、校验值。
- PR 483 已合入 docs main（合入提交 `93ee260d`），合入内容即 `a1db9d86`；合入后 main 上该文件无人再改，特性档与 L2 自 `05af9cc3` 起未变。
- 以下修订以 `a1db9d86` 为基点，**未提交、未推送**，只在 `.src-cache/docs-repo` 工作区；PR 483 已关闭，需从 `origin/main` 新开分支另提 PR。
- 留存：[补丁](testplan-revision-20261112.patch)（`git diff` 相对 main `11369710`，220 增 / 182 删）、[修订后全文](testplan-revision-20261112.revised.md)（SHA-256 `16fac84e898ea977553d91e2aa2ae087b8e9d0ef5b72602218e9bd6f506164ff`，§5 共 84 行，行尾统一为 LF）。第二轮的逐条依据见 [逐条复核记录](testplan-item-review-20261112.md)；改写脚本 `target/feat112-refactor/revise_testplan_20261116.py`，改前全文备份 `target/feat112-refactor/testplan-before-20261116.md`；第三轮脚本 `target/feat112-refactor/revise_testplan_20261117.py`，改前备份 `target/feat112-refactor/testplan-before-20261117.md`。
- 本地镜像 [FEAT-112-task-degradation-gate-testplan.md](FEAT-112-task-degradation-gate-testplan.md) 仍是 `a1db9d86` 原文；新 PR 合入并回读后再同步。首轮修订未改场景 ID；第二轮新增 18 个 ID、撤下 E5（净增 17，见下文“第二轮”），第三轮新增 E4.1、F4；代码与 cases 已按 84 行承接。
- 原则：testplan 偏离特性设计是测试侧错误，按特性档直接改；认为特性设计不对的，单独向特性设计提出。

## 一、场景预期或级别（13 行）

| ID | 修订 | 依据（特性档 `05af9cc3`） |
|---|---|---|
| G5a | 原“认证启用时无 secret 返 401 `{error:"unauthorized"}`”→ 交付默认配置与已配置认证的部署下，匿名访问都必须被服务间认证拒绝、不返回诊断内容；已配置认证时拒绝方式与其它受保护端点一致 | §3.2“纳入既有服务间认证，不像 /ready 一样豁免”；验收出口 16 |
| G5b | 错误凭据被拒，拒绝方式与其它受保护端点一致，不写死错误体 | 同上 |
| G5c | 正确凭据 200 且诊断完整；前置为“已配置服务间认证” | 同上 |
| E4 | 原“探针 181012 健康关门”→ 181012 上下文过长只计分母、不计分子；探针以此结束后的门状态不作期望 | §5.1.6、§5.1.3；验收出口 5 |
| E5 | 原“探针护栏拦截健康关门”→ 护栏正常拦截只计分母；Fixture 标“未承接”（出厂配置无确定可达路径） | 同上 |
| F1 | 期望改为与门 CLOSED 时同一请求的既有认证错误相同，非 ENGINE_DEGRADED、不占探针；前置补 HALF_OPEN | §3.1、§5.1.1 |
| F2 | 步骤“缺 `method` 字段”（A2A 写法）→ 缺 `sessionId`；期望改为同一对照判法 | §5.1.9；验收出口 18 |
| F3 | 删“四项均为 4xx”（团队通道不可用的既有错误是 503）；改为对照判法，补 HALF_OPEN | 同上 |
| A10 | SHOULD → MUST；期望写明错误指出键名 `openjiuwen.service.degradation-gate.enabled` | §3.3；能力表“配置与默认值” MUST |
| F-reg | SHOULD → MUST | 能力表“只拦新增负担” MUST；验收出口 8 |
| C7 | 原“state=OPEN 保持”→ 不自动 CLOSED：样本过期期间保持 OPEN，静默期满可进入 HALF_OPEN；前置 window 短于 quiet | 验收出口 4；§5.1.7 |
| B5 | 触发由“诊断推进”改为“新任务到达”，期望为残窗达标即开门并拒收（503） | §5.1.2；§4 场景表“异常率超标” |
| J3 | 步骤中 A2A `SubscribeToTask` → DA 实际的 `/api/agents/stream/{taskId}` 重连 | DA 实际接口 |

## 二、全部 65 行的两列

- `required_capability`：原 23 行把配置键当能力、其余写“（内部）”→ 统一为 `—`。FEAT-112 全为 MUST，无契约可选能力；配置键是前置。
- Fixture：原为开发仓测试类名或实现代码位置（如 `DaServiceAuthFilter:63-92`、`DaHealthController:103-117`）→ 测试仓承接类并标验收层（`[SIT] Feat112XxxTest` / `[组件] Feat112MechanismTest`），由代码 `@Covers` 回填；64 个有承接，E5 标“未承接”。

## 三、场景表外

- frontmatter：`required_capabilities` 清空并注明原因（原要求 demo 对 7 个配置键逐个表态，会消掉 A2 的“键缺席时交付默认”）；`sut` 中“真实 LLM”改为“模型由 stub 替身驱动”；`updated` 改为 2026-11-12；头部注释同步。
- §4.5 stub 规格：删“全场景只需 1 个 stub”（C5/C6/F-reg 需按请求关联的模型替身，J4 需持续流替身）。
- §6 六态：UNSUPPORTED → 不适用；conditional_not_triggered 处置由 WARN 改为“记 FAIL、结论无效、修前置后重跑”；protocol_unobservable 改为“所有验收层都观察不到才成立”。
- §7 SUT 描述同上修正；§8 “§8c 两道能力反查”→“前置与激活证据”。
- 附录 C 第 4、8、10 条按 D1 v4.0 口径同步。

## 第二轮（2026-11-16，按逐条复核结论）

- 恢复为测试仓场景（原“移交开发 UT”）：A3、A4、A5.1、A5.2、A5.3、A6（非法配置启动失败，期望写明完整键名）、B6（窗口内乱序终态照常计入）、E10（待答续跑与 inject 只记一次）。
- E5 拆为 E5.1–E5.6（主链被拒后完成/系统异常/原因不明失败，成员被拒耗尽后主任务完成/无关失败，护栏拦截导致任务失败）；E5.3 由组件级经 DA 终态分类入口承接，E5.5、E5.6 标“未承接”并写明原因。
- 新增：C8（探针取消后旧任务故障回 OPEN，验收出口 15）、C9（OPEN 中旧故障重算静默期，§5.1.2）、E12（认领后待答触顶按无结论释放，验收出口 18）、H4（两实例半开名额互不影响，验收出口 17）。
- 改写：A2 注明“键缺席”待特性设计确认；C7 分三段；E3 写明出厂形态走不到；E11 改为“名额不被永久占用、下一真实任务可成为探针”；F2 三种非法参数；F3 补 HALF_OPEN 与 PostgreSQL 前置；F-reg 扩到 §3.1 的 9 个入口并以 CLOSED 响应为对照；G4 补全零计数；G8 改为哨兵取证；H3、I1（启动记录供监控关联，告警保留属部署验收）、J2、J3 按特性档补全。
- 场景表外：frontmatter `compatibility` 改为“源码构建”、`updated` 2026-11-16；§1.1/§3.1 删除 UT 移交行；§4.1–§4.5 重写（三种 stub、`base_url` 带 `/v1`、错误体格式要求、夹具表）；§6 补 WARN/EXCLUDED/model_variance/条件示例/NOT_EXECUTED；§7 改为“开发仓用例只作参考”；§9 与附录映射同步；§9“长运行”一节去掉 `@Tag("manual")` 与 L2 §7.4 的开发侧清单，改写为实际执行方式；§6 `trace_missing` 例子不再用 I1 监控关联（告警关联属部署验收）。
- Fixture 列由 `target/feat112-refactor/fill_fixture.py` 按代码 `@Covers` 回填（80 个有承接，E5.5、E5.6 未承接），不手写。
- 期望文本指纹：`feat112/expectation-fingerprints.txt` 记录各行场景/前置/步骤/期望的哈希，`Feat112ArchitectureTest` 比对；改 testplan 后须复核承接用例再执行 `feat112/tools/run-feat112.sh fingerprints`。

## 第三轮（2026-11-17，只做 SIT）

依据：用户口径“测试仓只做 SIT，契约观测不到依次取日志、持久化数据，都不行提特性不可观测 Issue”；逐项分析见 [testplan-gap-review-20261117.md](testplan-gap-review-20261117.md)。

- 全局：§4.4 改写为观测顺序（契约 → 日志 → Redis/数据库 → 不可观测 Issue），列出可用日志、门状态不持久化、`done` 先于记账、诊断查询有副作用；§4.3 增加时钟控制（libfaketime）与数据面部署形态（PostgreSQL、Redis）；§4.5 ④ Fixture 只标 `[SIT]`；§6 `protocol_unobservable` 改为三级都观测不到才成立并提 Issue；§9 执行策略与附录 A 同步。
- 原组件级的行改为 SIT 构造：B6、B7、E9（时钟回拨）、E5.3（停 Redis）、E8（经 `/answer` 续跑占满执行池）、E11（PostgreSQL + 保留租户头）；C4 的代际与 E7 的取消瞬间注明不可观测；A1 注明 SIT 无法构造。
- 新增：E4.1（不同提供方格式的上下文超长）、F4（租户头校验先于降级门，需 PostgreSQL）。
- 改写：E3（!1131 后改交付配置）、F3（team 不存在需 PostgreSQL 部署形态）、I1（启动记录字段）、J4（声明关闭内容缓冲）。
- Fixture 列回填后：75 个有 SIT 承接，A1、B6、B7、E5.3、E9、E11、F4、E5.5、E5.6 未承接。
- 同日补齐部署夹具（Redis checkpointer、PostgreSQL 数据面、libfaketime 时钟控制）后：B6、B7、E9、E5.3、E11、F4 有了 SIT 用例（尚未在 Linux/Docker 上实跑），未承接只剩 A1、E5.5、E5.6；E5.3 行按实际构造改为“停在 awaiting 后停 Redis、续跑恢复检查点失败”，B7 行改为与事实到达前比较、不比较 `retryAfterSeconds`；§4.3 两类夹具标为已实现、尚未实跑（脚本 `target/feat112-refactor/revise_testplan_20261117b.py`）。

## 编号（2026-11-17，PR 549 第二个提交 `5791d21c`）

按已发到 PR 548 的编号规则统一改名：场景 `{域}{序号}`、变体 `{场景}-{变体名}`、测试点 `{场景}.{n}`，序号按新增顺序、不重排不复用，类型不进 ID。A5.1–A5.3 → A5-zero、A5-gt1、A5-nan；D1a、D1b → D1-run、D1-stream；E4.1 → E4-formats；E5.1–E5.6 → E13–E18；F-reg → F5；G5a、G5b → G5-missing、G5-invalid；G5c → G11；K-rep1–K-rep3 → K18–K20（K1–K17 已撤销不复用）。B1.1–B1.3、E1.1–E1.2 符合测试点定义未改。§5 末尾新增“已撤销 ID 与历史写法”（含 E5、K1–K17、F6–F12、空号 D2/G6/G7）。场景内容未变，只有行内引用其他 ID 的文字（A5-gt1、A5-nan、D1-stream、E14、E17）随之更新。测试代码、覆盖登记、指纹、cases 同步改名（脚本 `target/feat112-refactor/renumber_*_20261117.py`）。

## 对齐模板 r3（2026-11-17，PR 549 第三个提交 `e0565dd7`）

按 PR 548 r3 的正文硬约束与章节骨架调整，不改场景内容：来源写“路径 @ commit 链接”、去 PR 链接；补被依赖 testplan 与 FEAT-064；已撤销 ID 表改四列；E3/E17/E18 去 PR/MR 编号；删历史恢复注释；§6 三面都观测不到登记为覆盖缺口；§4.2/§4.3 标题与附录 A–C 对齐骨架（原附录顺延 D–F）。原因码命名待 PR 548 定稿。

## 提交时的步骤

1. `git fetch`，从 `origin/main` 新建分支，确认该文件仍等于 `a1db9d86`（否则先合并他人改动）。
2. 应用补丁，提交、推送、提 PR（需用户确认后执行）。
3. 合入并回读后，同步本地镜像、[README](README.md) 的 commit 与校验值、[cases](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md) §1。
