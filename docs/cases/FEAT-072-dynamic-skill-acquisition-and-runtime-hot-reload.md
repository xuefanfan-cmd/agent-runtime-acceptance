---
feature_id: FEAT-072
feature_title: 动态 Skill 获取与运行时热加载
scope: agent-solution common 分支（回归基线 common@3512c0aaa，含 PR #950/#958/#961/#964）
deployable_units: [skillhub-runtime-demo]
sut: skillhub-runtime-demo 宿主 Agent（managed；真实制品 com.openjiuwen.example:skillhub-runtime-demo:0.1.2，agent-solution common/example）
status: executed-manual-plus-automated
features: [FEAT-072]
updated: 2026-10-29
---

# FEAT-072 验收：动态 Skill 获取与运行时热加载

> 本档是 `docs/cases/` 的特性级用例设计（方案级），由正式 TestPlan
> （`FEAT-072-dynamic-skill-acquisition-and-hot-reload-testplan.md`，工作区
> `agent-solution-docs/docs/develop/04-testplan/`）转化而来：场景 ID、判定点（Oracle）
> 与优先级继承 TestPlan，本档补"自动化落点"（acceptance 仓测试类#方法）与执行证据指针。
> 两者的分工见 §9（与 TestPlan 的区别与联系）。

## 1. 测试目标

以真实部署的 skillhub-runtime-demo 宿主为 SUT，验证其从 Skill Hub 动态获取技能并运行时热加载的**外部可观察行为**：启动/手动/周期三种触发源下的全量同步与增量替换、版本规则（PIN/LATEST）语义、删除守卫与删除复查、手动触发的异步受理契约（202/report/409/429）、未就绪与崩溃恢复、存量布局迁移、多副本收敛、开关矩阵与关闭态零耦合，以及提示词层的端到端生效（技能升级后回答内容切换、路径不漂移）。

SIT 侧是裸协议调用方：经 SUT 的管理端点（`/manage/skillhub/sync|report`）、业务端点（`/v1/query`）、进程日志与本地磁盘（`{localDir}/.skillhub/` 树 + `manifest.json`）四条断言面驱动与判定；不 import 被测类、不反射读运行时常量。Hub 侧使用进程内替身（`SkillHubSyncMockServer`，契约取自真实 Hub 五件套实测抓包），消除真实 Hub 的五件套部署依赖与账号状态漂移。

## 2. 范围与非范围

范围：

- 同步链路三触发源：STARTUP / MANUAL / POLL，以及启动失败恢复链路（30s 起指数退避）。
- 版本语义：默认 latest 跟随、PIN 锁定、PIN 未知单项失败、规则文件每轮热读、同版本仅元数据变化（metadataOnly）。
- 管理契约：202+syncId 异步受理、report 查询（SUCCESS/PARTIAL/FAILED + committed）、409 单飞、429 统一冷却、管理令牌 401。
- 删除：下线删除、删除数守卫（max-removals-per-sync=10，超限拦截本轮删除、新增照常提交）、删除复查（转私有复查仍可解析→取消删除）。
- 稳定性：未就绪可观察（不把空技能当成功）、进程死亡恢复（完整旧态/新态，不服务半状态）、提交后重启幂等。
- 迁移与共存：存量（FEAT-005 直铺）布局非破坏清点、skillhub 关闭态旧链路零耦合可用。
- 多副本：双实例指纹收敛、手动触发仅命中目标实例。
- 热加载端到端：技能升级后提示词层 marker 切换、目录路径不漂移。
- 配置守卫：非法规则/可见清单文件的 CONFIG 阶段失败定位（BUG-4 修复回归）。

非范围：

- Skill Hub 服务端功能（本期零改动，归 Hub 侧测试）。
- core 注册管线内部实现（FEAT-005 承接）。
- 只读版本查询接口（L2 §2.3 明文不实现，需求 v0.18 已取消 → SC-VER-09 N/A）。
- 同步结果回执上报 Hub（L2 §1.1 明文 OUT）。
- 组群授权子集经默认链路（Hub 本期无授权子集，本地可见清单过滤替代——观察项，见 §5.1）。
- 沙箱共写/受管子树整替（SBX 组，环境 BLOCKED 9 项，见 §5.1）。
- 三方适配器交付（OUT）。

## 3. 事实来源

| 来源 | 路径 | 版本 |
|---|---|---|
| 正式 TestPlan（判定基准·能力范围与场景判定点） | 工作区 `agent-solution-docs/docs/develop/04-testplan/FEAT-072-dynamic-skill-acquisition-and-hot-reload-testplan.md` | 2026-10-28 |
| 权威 L2（判定基准·实现细节） | `Feat-Func-072-dynamic-skill-sync-and-hot-reload.md`（特性开发文档） | 2026-10-26 |
| 执行记录（手工，ECS 139） | `/root/feat072/` 各批次脚本与报告（批次 1–25），执行日期 2026-10-27/28 | 2026-10-28 |
| 回归记录（PR 基线，ECS 139） | `/root/feat072/stage0/p0-reg1.sh / p0-reg2.sh` 输出（基线 common@3512c0aaa + PR #950/#958/#961/#964） | 2026-10-29 |
| 缺陷台账（已闭环） | agent-solution issues #506（BUG-2）/ #521（BUG-4 等）/ #523（BUG-11 等）/ #527（流式挂起，转 runtime 仓） | 已全部 close |
| 文档差异 | agent-solution docs#42（TestPlan↔开发文档差异三） | 已提交 |

基线声明：自动化测试的 SUT 制品 `com.openjiuwen.example:skillhub-runtime-demo:0.1.2` 由
139 `/root/feat072/wt-reg` 工作树（common 分支，回归基线 3512c0aaa）构建并安装到
`~/.m2`；该 jar 含 PR #950（删除复查+AUTH_FAILED 留存 opt-in）、#958（配置守卫
CONFIG_INVALID_*）、#961（fail-fast 诊断）、#964（关闭态零连接）四组修复。

## 4. 部署拓扑

- **SUT**：单/双实例 skillhub-runtime-demo（`SutStack` managed 拉起，随机端口，`ready-mode: tcp`——
  demo 不暴露 actuator，就绪由用例的 report 轮询自证）。场景配置（endpoint / local-dir /
  encrypted-token / 规则文件 / poll 参数）由用例以 Spring 程序参数逐项覆盖注入。
- **Hub**：进程内替身 `SkillHubSyncMockServer`（127.0.0.1 随机端口），复刻真实 Hub 契约：
  `GET /api/v1/plugins?plugin_type=skill&page=N&page_size=200`（分页目录，私有资产不出列表）、
  `GET /api/v1/artifacts/{id}?version=V`（元数据+checksum）、presigned 下载、`/identity/user`
  身份校验。支持可见性切换、三端点独立故障注入、目录挂起（崩溃注入窗口）与审计日志。
- **LLM**：提示词探针类用例（SC-REPL-01/SC-REG-01）需要真实模型——`LLM_API_KEY`/
  `LLM_API_BASE`/`LLM_MODEL` 运行时从环境读取，key 以最高优先级 `--` 参数注入（§8）。
  其余用例不依赖 LLM（同步链路断言面为报告/磁盘/日志）。

## 5. 测试场景矩阵

场景 ID 与 TestPlan §6 一一对应；"主断言"列是该场景的 Oracle 摘要（完整判定点见
TestPlan）。"自动化落点"为本仓 `cases/integration/skillhub/` 的类#方法；状态为
"已执行（手工）+已转化"的行，手工证据在 139 执行记录，自动化证据待 139 执行回填。

| ID | 场景 | 主断言（Oracle） | 自动化落点 | 状态 |
|---|---|---|---|---|
| SC-ADD-01 | 启动期全量同步 | STARTUP 轮 SUCCESS/committed，added=全量，active 树与 manifest 落盘一致 | `SkillHubStartupSyncSitTest#startupSyncInstallsAllVisibleSkills` | 已执行（手工批次1）+已转化 |
| SC-ADD-01b | 私有资产排除 | Hub 私有资产不进目录列表、不参与同步 | `SkillHubStartupSyncSitTest#startupSyncExcludesPrivateAssets` | 已执行（冒烟）+已转化 |
| SC-ADD-05 | 默认最新的事后可见标记 | items 携带 previous/target/appliedVersion + changeReason + validationMode=PACKAGE_SHA256 | `SkillHubStartupSyncSitTest#startupReportCarriesFieldLevelMarkers` | 已执行（批次5）+已转化 |
| SC-VER-02 | 版本演化（默认 latest） | 1.0.1→1.0.2 replaced/CONTENT_CHANGED/generation+1/路径不漂移 | `SkillHubVersionRulesSitTest#defaultLatestFollowsNewVersion` | 已执行（批次4）+已转化 |
| SC-VER-03 | PIN 锁定不跟随 | PIN=1.0.1 时 Hub 发 1.0.2 → unchanged 留旧、不下载 | `SkillHubVersionRulesSitTest#pinnedVersionDoesNotFollowLatest` | 已执行（批次9）+已转化 |
| SC-VER-10 | PIN 未知版本 | failedItems 单项失败、不安装、不静默转 latest | `SkillHubVersionRulesSitTest#pinToUnknownVersionFailsTheItemOnly` | 已执行（批次9）+已转化 |
| SC-VER-08 | 规则文件热读 | 修改 version-rules-file 不重启，下一轮按新规则收敛 | `SkillHubVersionRulesSitTest#externalRulesFileIsHotReadEachRound` | 已执行（批次9）+已转化 |
| SC-REPL-05 | 同版本仅元数据变化 | metadataOnly 记账、不重下载、generation 不推进 | `SkillHubVersionRulesSitTest#sameVersionMetadataOnlyRepublish` | 已执行（批次8）+已转化 |
| SC-MANUAL-01 | 手动异步受理 | 202+syncId+reportPath，report 出 SUCCESS/committed/triggerSource=MANUAL | `SkillHubManualSyncSitTest#manualSyncAcceptedAsyncAndReported` | 已执行（批次6）+已转化 |
| SC-MANUAL-02 | 管理令牌鉴权 | 错误/缺失 X-SkillHub-Admin-Token → 401，正确令牌放行 | `SkillHubManualSyncSitTest#manualSyncRequiresAdminToken` | 已执行（批次6）+已转化 |
| SC-MANUAL-04 | 409 单飞冲突 | 在途轮内再触发 → 409+正在执行的 syncId，不排队 | `SkillHubManualSyncSitTest#manualSyncSingleFlightConflict` | 已执行（批次22）+已转化 |
| SC-MANUAL-05 | 429 统一冷却 | 轮次间冷却窗内触发 → 429/COOLDOWN/retryAfterSeconds>0，冷却后恢复 | `SkillHubManualSyncSitTest#manualSyncUnifiedCooldown` | 已执行（批次22）+已转化 |
| SC-POLL-01 | 周期触发生效 | Hub 变化无手动触发即被周期轮收敛 | `SkillHubPollingSitTest#periodicPollConvergesHubChangeWithoutManualTrigger` | 已执行（批次6 PASS-D）+已转化 |
| SC-POLL-02 | 间隔静态配置 | 相邻周期轮间隔≥所配间隔；修改需重启（限制已记录） | `SkillHubPollingSitTest#periodicRoundsHonorStaticInterval` | 已执行（实测确认）+已转化（部分：重启生效限制为文档性记录） |
| SC-DEL-01 | 删除下线与守卫 | 单删 removed=1 磁盘收敛；12>10 全拦 + removalBlocked + 新增照常 + PARTIAL | `SkillHubRemovalSitTest#removedSkillIsUninstalled`、`#removalGuardBlocksOversizedRemovalBatch` | 已执行（批次7/22）+已转化 |
| SC-DEL-02 | 删除复查 | 待删 id 复查仍可解析（转私有）→ 取消删除保留本地 | `SkillHubRemovalSitTest#removalRecheckCancelsDeleteWhenStillResolvable` | 已执行（PR950 回归）+已转化 |
| SC-READY-01 | 未就绪与恢复退避 | 启动轮 FAILED/CATALOG 不落空技能；恢复轮间隔≥30s；Hub 恢复后收敛 | `SkillHubNotReadySitTest#notReadyThenRecoveryWithBackoff` | 已执行（reg8111）+已转化 |
| SC-CTRL-01 | 开关矩阵 | enabled=false 零 Hub 连接/无 .skillhub 树/管理端点 404；poll=false 无周期刷新 | `SkillHubControlSwitchSitTest#disabledMeansZeroHubContact`、`#disabledDoesNotAssembleManagementEndpoint`、`#enabledWithoutPollDoesNotRefreshPeriodically` | 已执行（R7/PR964）+已转化 |
| SC-MIGR-01 | 存量布局非破坏清点 | 旧直铺目录保留原位内容不变；新技能进 .skillhub/active；重启幂等 | `SkillHubLegacyLayoutSitTest#legacyLayoutIsInventoriedNonDestructively` | 已执行（批次7）+已转化 |
| SC-INST-01 | 多副本收敛 | 双实例 installedFingerprint 一致（不承诺代数相同）；手动触发仅命中目标实例 | `SkillHubMultiInstanceSitTest#twoInstancesConvergeAndManualSyncTargetsOneInstance` | 已执行（批次22）+已转化 |
| SC-REG-01 | 关闭态存量全链路 | 旧链路本地技能进提示词可答，零 Hub 接触、零 .skillhub 介入 | `SkillHubDisabledLegacySitTest#legacySkillPathUsableWhenDisabled` | 已执行（老流程A PASS-REG）+已转化（LLM 门控） |
| SC-REPL-01 | 热加载端到端 | 升级后回答切换为新 marker、目录路径不漂移 | `SkillHubHotReloadPromptSitTest#hotReloadReachesPromptAfterUpgrade` | 已执行（批次5）+已转化（LLM 门控） |
| SC-CRASH-01 | 在途轮崩溃恢复 | 进程死亡重启后收敛完整状态（磁盘=manifest=报告），不服务半状态 | `SkillHubCrashRecoverySitTest#crashDuringRoundRecoversToConsistentState` | 已执行（批次7）+已转化（进程级注入以受管强停近似 kill -9，见 §6） |
| SC-CRASH-02 | 提交后重启幂等 | unchanged、零重下载、generation 不变 | `SkillHubCrashRecoverySitTest#restartAfterCommitIsIdempotent` | 已执行（批次7）+已转化 |
| SC-FAIL-CFG | 配置守卫（BUG-4） | 非法 rules/visible 文件 → FAILED/CONFIG/CONFIG_INVALID_* 定位，不笼统 ROUND_FAILED | `SkillHubConfigGuardSitTest#invalidVersionRulesFileFailsAtConfigStage`、`#invalidVisibleSkillsFileFailsAtConfigStage`、`#emptyRulesFileMeansNoPin` | 已执行（reg8112/8113）+已转化 |

### 5.1 显式未承接项（不在本轮 PASS 口径内）

| ID | 未承接原因 | 责任方 / 恢复条件 |
|---|---|---|
| SC-CRASH-03 | trash 清理失败的精确注入点（commit 后目录替换位置）黑盒不可控 | 开发侧 PR-佐证：agent-solution `SkillManifestStoreTest`（FaultInjector 注入，PR #958 全绿） |
| SC-MIGR-02/03 | 扫描根切换时序与回退恢复的内部状态面黑盒不可观察 | 开发侧 PR-佐证：`LegacyLayoutMigratorTest`（PR #958，64 tests 全绿） |
| SC-NAME-01/02 | 命名安全（保留名/截断重名/大小写）需要恶意资产构造的精确性 | 开发侧 PR-佐证：`SkillPathSafetyTest`（PR #958）；真实 Hub 侧攻击面归 Hub 测试 |
| SC-OBS-01 | 指标 `skillhub.sync.*` 需暴露端点/抓取面，demo 无 actuator | 观察项：139 手工核对存在；产品化暴露后补自动化 |
| SC-SYSOP-01/02 | sysop 兜底通道需 SANDBOX 拓扑 | 手工冒烟通过；环境 BLOCKED 项解除后补（见 SBX） |
| SBX 组（SC-SBX-02/06/07/08） | 沙箱共写/受管子树整替依赖沙箱拓扑 | 环境阻塞（BLOCKED 9 项，TestPlan §8 已记录） |
| SC-REG-02 | 未就绪窗口错误暴露：阻塞 500 吞码（BUG-2，#523→runtime 仓）+流式永久挂起（#527→runtime 仓） | 已挂账闭环（issue close，修复责任在 runtime 仓）；runtime 侧修复后回归 |
| SC-VER-09 | 只读版本查询接口本期不实现（L2 §2.3，需求 v0.18 取消） | N/A |
| SC-AUTH-01 授权子集 | Hub 本期无授权子集（L2 §3.2.1），本地可见清单过滤替代 | 观察项；Hub 侧授权子集交付后激活 |
| 回执上报 Hub | L2 §1.1 明文 OUT | N/A |

## 6. Test Agent 与 Fixture

SUT 是**真实部署的业务 Agent**（真实制品 jar + 真实模型可选），不使用 Test Agent mock
制造业务状态。场景可控性来自替身 Hub 与故障注入：

| 支撑物 | 用途 | 边界 |
|---|---|---|
| 真实 SUT（skillhub-runtime-demo 0.1.2，managed 进程） | 全部场景的被测对象 | 崩溃注入以受管进程强停近似 kill -9（进程死亡时点可控、JVM 内注入点不可控——精确注入点由开发侧 `SkillManifestStoreTest` 承接，见 §5.1） |
| `SkillHubSyncMockServer`（进程内 Hub 替身） | 目录/元数据/下载/身份四端点契约复刻；版本发布、可见性切换、三端点独立故障注入、目录挂起、审计 | 契约取自真实 Hub 实测抓包（2026-10-27）；Hub 服务端功能不测 |
| `AbstractSkillHubSyncSitTest`（用例基座） | SUT 拉起与配置覆盖注入（endpoint/local-dir/token/规则/LLM）、管理面 HTTP、报告/磁盘/日志断言、429 自动等待重试 | encrypted-token 经环境变量占位符注入（值不落仓）；LLM key 以 `--` 参数注入 |
| LLM 环境绑定 | 提示词探针类用例的真实模型连接 | 未配置 `LLM_API_KEY` 时探针用例跳过（Assumptions，跳过≠通过） |

**Fixture 变更登记**：本特性在 acceptance 仓新增——`application-openjiuwen.yml` 的
`skillhub-demo`/`skillhub-demo-b` agent 段（坐标 + ready-mode: tcp）、`sut-sources.yml`
的 `common/example/skillhub-runtime-demo` 构建步、`cases/integration/skillhub/` 14 个
类（1 替身 + 1 基座 + 12 场景类）。

## 7. 关键链路断言

| 断言组 | 断言 | 来源 |
|---|---|---|
| 同步收敛 | 报告 added/replaced/removed/unchanged/metadataOnly/failedItems/removalBlocked 与磁盘/manifest 三面一致 | L2 §3.3；TestPlan §6 |
| 触发源 | STARTUP/MANUAL/POLL/RECOVERY 四源可区分（triggerSource） | L2 §3.7 |
| 手动契约 | 202+syncId+reportPath；409 单飞返回在途 syncId；429 COOLDOWN+retryAfterSeconds；管理令牌 401 | L2 §3.5；实测样本 |
| 版本语义 | 无规则=latest 跟随；PIN 精确匹配留旧；PIN 未知单项失败不转 latest；规则文件每轮热读 | L2 §3.2；TestPlan SC-VER |
| 守卫语义 | 删除数>10 全拦本轮删除、新增照常、PARTIAL+removalBlocked；复查可解析→取消删除 | L2 §3.3 AC-12；PR #950 |
| 完整性 | 崩溃/重启后磁盘=manifest=报告 installedCount，不服务半状态 | L2 §3.6 AC-14/15 |
| 开关矩阵 | enabled=false 零 Hub 连接、无受管树、管理端点 404 | L2 §6.5；PR #964 |
| 热加载 | 升级后提示词 marker 切换、directoryKey 不漂移 | FEAT 验收出口；TestPlan SC-REPL-01 |

## 8. 执行策略

- **执行历史**：全部场景已于 2026-10-27/28 在 ECS 139（真实 Hub 五件套 + 真实 LLM）
  手工执行并记录（批次 1–25）；2026-10-29 在回归基线 common@3512c0aaa（PR #950/#958/
  #961/#964）上完成回归并闭环缺陷（issues #506/#521/#523/#527 全部 close，回归结论见
  issue 评论）。
- **自动化转化**（本档交付物）：上述场景转化为 acceptance 仓可执行黑盒用例（§5 落点），
  Hub 换为进程内替身，消除真实 Hub 部署依赖；执行前置仅剩 JDK 21 + `~/.m2` 有 0.1.2
  demo 制品 + （可选）真实 LLM 环境变量。
- **LLM key 的供给方式**：仓库全局 `sut.java.system-properties.LLM_API_KEY` 为空串且以
  `-D` 注入会覆盖环境变量；用例基座把 key 以最高优先级 `--LLM_API_KEY=<env>` Spring
  参数注入（值只在运行时从环境读取，不落文件、不落日志）。`LLM_API_BASE`/`LLM_MODEL`
  经环境变量透传。
- **统一冷却**：轮次间存在冷却窗（实测 17–18s 量级），用例基座对 429 自动等待重试；
  冷却契约本身由 `SkillHubManualSyncSitTest#manualSyncUnifiedCooldown` 判定。
- **执行命令**（在 139 或任意具备前置的 Linux 主机）：

  ```bash
  # 前置：~/.m2 安装 demo 制品
  mvn install:install-file -Dfile=skillhub-runtime-demo-0.1.2.jar \
      -DgroupId=com.openjiuwen.example -DartifactId=skillhub-runtime-demo \
      -Dversion=0.1.2 -Dpackaging=jar
  # 全量（提示词探针类需要 LLM_API_KEY/LLM_API_BASE/LLM_MODEL 环境变量）
  mvn test -Dtest.env=openjiuwen -Dtest='SkillHub*SitTest'
  ```

- **退出策略**：手工执行已形成验收结论（见 3 事实来源的执行/回归记录）；自动化转化的
  PASS 作为可回归资产，首次在 139 执行后回填本矩阵"状态"列（区分 手工已执行 / 自动化
  已执行）。

## 9. 与 TestPlan 的区别与联系

| 维度 | TestPlan（工作区正式版） | 本档（docs/cases） |
|---|---|---|
| 定位 | 测试方案：判定基准、全部 SC 场景的完整前置/步骤/期望、分批执行计划、已知限制与偏差清单 | 用例设计档：场景矩阵 + 自动化落点 + 执行证据指针，服务 acceptance 仓回归 |
| 场景集 | 全量 SC-*（含未执行/挂账/N/A/观察项） | §5 只收已执行（跑绿）场景；未承接项压缩进 §5.1 并给出理由与恢复条件 |
| 判定细节 | 逐场景完整 Oracle | Oracle 摘要 + 指回 TestPlan |
| 自动化 | 不维护代码落点 | 逐场景绑定测试类#方法（Allure feature 树据此回溯） |
| 环境 | 真实 Hub 五件套 + 真实 LLM（139 部署拓扑） | 进程内 Hub 替身 + 可选真实 LLM（回归可复现、无环境漂移） |
| 生命周期 | 随需求/设计演进刷新 | 随 acceptance 仓代码演进（本档即 TestPlan 的执行态投影） |

转化规则：场景 ID 与判定点原样继承（未新造编号）；TestPlan 中同一 SC 的多判定点
若落到不同测试方法，在自动化落点列逐一列出；手工执行结论不因转化失效——自动化
首次执行前，矩阵状态列以手工证据为准。

## 附录 A. 差异与待澄清项

- **替身 Hub 与真实 Hub 的差异**：替身固定单页目录（条目数<200）、checksum 为真实 zip
  sha256、无审核流（发布即 latest）；真实 Hub 的发布→待审→放行链路、组群授权、审计
  日志面不在替身语义内（归 Hub 侧测试与观察项）。
- **崩溃注入精度**：黑盒用例以受管进程强停近似 kill -9；JVM 内精确注入点（目录移动/
  manifest 替换位置）由开发侧 `SkillManifestStoreTest`（PR #958）承接——两证据互补。
- **冷却窗口时长**：实测 17–18s（批次 22/24），产品文档未固定数值；用例只断言
  retryAfterSeconds>0 与冷却后恢复，不锁死具体秒数。
- **BUG-10 勘误**：DRAIN_TIMEOUT 代码默认 60s，15s 为 demo yml 覆盖值——已记入 docs#42
  文档差异；本矩阵不设排空超时的独立自动化（在途保护归 SC-CONC，P1 挂账）。
