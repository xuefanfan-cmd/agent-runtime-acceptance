# FEAT-112 testplan 修改点逐条复核记录（2026-11-12）

方法：以特性档 `05af9cc3` 为锚点，逐条对照 DA L2（`05af9cc3`）、分类规格（PR 463，涉及分类时）、代码（solution `ec105dfb9`，相关类自 `0f5d17265` 起未变）、testplan 原文（`a1db9d86`）与修订稿、测试代码与实跑（`final-20261112`）。凡与特性档不一致的都列出。结论定稿后统一修改 testplan。

## 待提交清单（2026-11-20，用户选择"先不动"，未提交）

当前在 main 分支，工作区改动均未提交。提交前先切分支。**硬约束：`feat112/.src-cache/`（794M，git 远程含 token）绝不提交；`feat112/logs/`、`target/`、`*.tar.gz` 已被 .gitignore 忽略或应排除。**

A 必须提交（测试代码 + CI 运行时直接依赖）：
- 新增：`src/main/java/com/huawei/ascend/sit/judge/`（结果判定层）；`src/test/java/com/huawei/ascend/sit/cases/integration/task_degradation_gate/`、`.../fixtures/task_degradation_gate/`、`.../judge/`（SIT 用例、fixtures、判据自测）
- 改动：`src/main/.../lifecycle/{AgentConfig,ProcessLauncher,SutStack}.java`（workingDirectory）；`src/test/resources/application-openjiuwen.yml`；`src/test/resources/deepanalyze/da-dataplane-baseline.sql`（plugins.version）；`pom.xml`；`scripts/sut/sut-sources.yml`
- feat112 运行时依赖（测试代码直接读取）：`feat112/tools/`、`feat112/testplan-revision-20261112.revised.md`、`feat112/FEAT-112-task-degradation-gate-testplan.md`、`feat112/expectation-fingerprints.txt`、`feat112/test-revision.txt`

B 建议提交（文档记录）：`docs/cases/FEAT-112-task-degradation-gate-deepagent.md`；`feat112/` 的 README、runbook、test-architecture.{md,mmd,html}、server-run-guide-20261118.md、linux-deploy-guide.md、testplan-item-review-20261112.md、testplan-revision-20261112.md

C 可选（过程材料）：`docs/issues/`（8 份草稿，均已提单 #852–#856）；`feat112/` 各 pr*/review* 协作草稿、issues-*、baseline-05af9cc3

D 绝不提交：`feat112/.src-cache/`、`feat112/logs/`、`target/`、`*.tar.gz`

本轮 FEAT-112 闭环状态：84 场景实跑（77 PASS/4 FAIL/3 未承接）；8 项问题核查后 4 真+1 增强已提单 agent-solution #852–#856（正文去内部编号与链接、证据内嵌），2 项关闭；测试仓改动待提交。

## 进展（2026-11-20，开发八项核查答复的核实）

- 开发在 docsReview 回了 `feat112-eight-issue-review-20261120.md`（提交 `e4e4ec9`）：1/2/6/8 真 Issue（带措辞修正）、3/4 降为增强、5/7 判误报关闭。
- 我方代码级核实（solution `96c23d4ea`）：① topP 修复属实——`DaWorkflowRunner` 已是 `Double topPFallback`，修复提交 `6502e58d6`/`5abda1013`/`1728a2f8d` 均为 `96c23d4ea` 祖先；linux-rest-1 的 E16 失败详情是 stub 服务计数（成员已运行），证实 NPE 已不存在，草稿 7 确系陈旧——我方错误：只查了 core 侧 diff，没查 solution 消费端整改。② `classify` 对非空 admissionReason 一律 NOT_ADMITTED 属实（prepare_failed 分类差距成立，且 L2 已定目标，无需裁决）。③ `DaHealthDetailController` 与 `DaHealthController` 并存，detail 端点在前者，草稿 1 类名需改。④ `DegradationGateTest#probeCancellation_releasesSlotOnlyAtSettlement` 存在，取消顺序（落定→发事实→释放）与我读的 `TerminalClosure` 一致。未独立核验的只有“L2 已把实际落定锚在终态事实链”一句（引 PR 538 版 L2）。
- 结论：开发答复整体采纳。保留两点注记：#5 我方原稿本就是“不可观测+裁决请求”而非缺陷断言，“误报”定性偏重，但裁决答案（落定=控制面终态事实链）接受，E7 维持调用级粒度；#2 的 HALF_OPEN 瞬时占探针由推断转为开发证实（自释放）。
- 派生待办：E16 激活逻辑按新载体成员调用模式重写（测试侧新问题，stub served 计数不符）；cases §8.0 与覆盖登记中 E16 的原因“core topP NPE”需更正；草稿 1/2/6/8 按开发修正重写为提单版；3+4 合并为增强建议；5/7 写关闭记录。均待用户确认后动。

## 进展（2026-11-20，正式提单）
- 按用户要求改写 5 条 issue 正文（#852–#856）：去掉开头的 SIT/评审来源与所有链接，证据（匿名 200 响应、门关/开的 400↔503 成对交换、locale 启动栈、分类函数坐标）全部以文字/代码块内嵌；去掉所有特性编号（FEAT-112）与测试点/场景编号（G5-missing、F4、E16 等）及测试类/方法名，改为描述；标题同去编号。保留的只有产品自身的契约条款引用（§x.y、验收对应项）与 solution/core commit。远端回读 5 条：无 docsReview/外链、无内部编号（#852 仅复现命令里的 `http://127.0.0.1` localhost，非引用）。docsReview（私有仓）里的索引/草稿保留内部编号，供 SIT 侧留档。

- 经用户确认（5 项全提 agent-solution、正文内嵌完整草稿），在 openJiuwen/agent-solution 建 issue：#852 匿名诊断面(G5-missing)、#853 F4 租户校验、#854 prepare_failed 分类、#855 locale 启动、#856 可观测性增强(3+4 合并)。core 不提（#7 已修关闭），#5 关闭。token 为用户本人账号 gcw_KTJ9vvED。
- 正文为 r2 草稿全文，相对链接转为 docsReview 绝对 URL，证据包路径说明指向 `evidence/results-all-20261120.tar.gz`；回读 5 条 title/body 正常。索引（`issues-submission-20261120.md` 与 docsReview README）加提单编号横幅。
- 剩：测试仓本地改动（E16 修复、cases/进展更正、6 处 Linux 适配、部署夹具等）仍未 commit。

## 进展（2026-11-20，并入开发核查答复、E16 修复）

- 开发八项核查答复逐条代码级复核并采纳（见上一条）。据此改稿并推送 docsReview（提交 `b8069b2`，先 rebase 到开发的 `e4e4ec9`）：
  - 真 Issue 1/2/6/8 改 r2（类名、F4 收窄、prepare_failed 拆 A 实现差距+B 测试承接、locale 触发条件精确化）；
  - 3+4 合并为增强建议稿（本地留档，已提单 #856）；
  - 5/7 写入关闭记录（本地留档）；README 改为复核后处置索引。
- 第 7 项动态证据补全：从结果包 E16 证据 `FEAT-112-E16-734993d7-…/002.json` 读出新载体上 workflow completed、成员正常运行、无 NPE，与 11-16 旧载体 NPE 证据成对照——确认 solution `96c23d4ea` 已修（我方原漏查消费端整改，已改口）。
- E16 测试侧修复：新载体上成员被拒 2 次后做第 3 次合成调用、拿 stub 默认应答并 completed（非“Max iterations 耗尽”），served=5≠旧硬编码 4。改 `Feat112MemberGuardrailTest` 激活：服务数用 `>=MEMBER_ITERATIONS+2`、去掉“Max iterations”末态要求、改断言“每次 read_file 均被拒、无成功输出”。本机 E16 复跑 PASS（3 findings：instance/samples=1/failures=0）。cases §8.0 的 E16 原因已由“topP NPE”更正。
- 仍未提交：Issue 未正式提交到 core/solution issue 区（docsReview 为评审草稿）；测试仓本地改动（含本次 E16 修复、cases/进展、6+1 处修复）未 commit。

## 进展（2026-11-20，Issue 草稿上传 docsReview）

- 按用户要求把索引、8 份 Issue 草稿、2 份底稿与证据打包推送到 `gcw_KTJ9vvED/docsReview` 仓 `feat112-task-degradation-gate/`（提交 `c4bd341`，main）。README 为索引；`evidence/` 含完整结果包与抽出的关键证据（F4 成对交换、G5 匿名 200、topP NPE SSE、locale 启动失败日志、三轮 summary）；草稿内证据路径已改为目录内相对引用。远端回读核对：根目录 13 项、evidence 13 项，齐全；原有的 PR 463 差异对账清单保留并在 README 注明。
- Issue 仍未正式提交到各接收方（core/solution 的 issue 区）；docsReview 上的是评审草稿。

## 进展（2026-11-20，提交清单起草）
- 用户指出清单过简，要求按 `docs/review-checklist.md` 第 3 章 Issue 规范重写。已把 8 项各写成完整草稿（I-M7 约定；草稿本地留档，Issue 以 gitcode 提单为准）：每份含结论摘要/场景/影响面/实测证据/代码层定位/判据/Pre-flight 三问/历史关系/复现条件/预答反问/修复建议与验收转绿的测试。`issues-submission-20261120.md` 改为索引。观察与推断分开标注（I-M2）：F4 的 HALF_OPEN 占探针、G5 的装配定位、prepare_failed 的不可构造均注明为走读推断。

- 对外问题合并为一份提交清单 `issues-submission-20261120.md`：8 项按接收方分组（solution/DA 产品问题 2：G5-missing、F4；不可观测 3：门日志、代际值、取消窗口；特性设计裁决 1：prepare_failed；core 1：topP NPE；DA 参考 1：POSIX locale）。全部在新载体（core `f777aa244` + solution `96c23d4ea`）的 Linux 实跑中核对仍成立；每项带契约依据、证据路径（结果包 `feat112/logs/results-all-20261120.tar.gz`）与复现方式。待用户确认后对外提交。

## 进展（2026-11-20，Linux 全量实跑收口）

- fix5（locale）后 A2/J4 仍败于收尾竞态：Linux 上 `process.destroy()` 先关管道、SIGTERM 后进程仍存活数秒，读流线程抛 `Stream closed` 被误判为错。fix6（`feat112-fix6-20261120.tar.gz`）：`closing` 标志，主动终止期读流异常不算错。`linux-a2j4-2`：A2、J4 PASS。
- 最终合并（linux-rest-1 + linux-env-2 + linux-clock-3 + linux-a2j4-2）：84 场景 = **77 PASS、4 FAIL（G5-missing、F4 must_violation；E3、E16 conditional_not_triggered）、3 未承接（A1、E17、E18）**。已写入 cases §8.0（原 §8.0–§8.2 顺延为 §8.1–§8.3，交叉引用同步改）。
- 待办：F4 提 Issue（产品）；DA POSIX locale 启动失败作参考项提给开发；6 个测试侧修复未提交（与此前所有测试仓改动一起）。

## 进展（2026-11-19，Linux 服务器实跑）

- 部署：服务器 `/home/openjiuwen/feat112`（华为云 EulerOS 2.0，JDK 21 在 `/usr/local/jdk-21.0.12.1+1`，Maven 用 `./mvnw` 已下载的 3.9.16），离线依赖库 + 空 Maven settings（服务器 settings 配了镜像源，离线解析版本范围时找不到 `maven-metadata-central.xml`，已本机复现），libfaketime 0.9.10 现场编译，镜像用服务器已有的 `registry:8501/postgres:16`、`registry:8501/redis:7.4.9`。环境变量在服务器 `/home/openjiuwen/feat112/env.sh`。
- `linux-env-1`（8 个方法）：B6 PASS；E15 测试侧错误（Redis 停掉后 `describe()` 再取映射端口，Testcontainers 拒绝）；数据面类 DA 启动失败（新载体 solution `875b65d5b` 起 DA 读 `plugins.version`，测试侧基线无此列）；B7×2、E9 在打开门或提交旧任务时就报 “model marker not observed”，发生在拨钟之前，原因待诊断包。
- 修复（测试侧）：`RedisCheckpointStore` 启动时记录 host/port；`da-dataplane-baseline.sql` 的 `plugins` 补 `version TEXT`（列形状取自 solution `DaPluginControllerTest`）。以 `feat112-fix-20261118.tar.gz` 传服务器覆盖。
- `linux-env-2`（4 个方法）：E15、E11、F3 PASS；**F4 FAIL / must_violation**（产品问题）：保留租户头 `*`，CLOSED 返回 400 `ENGINE_VALIDATION`（`tenant id '*' is reserved`），OPEN 返回 503 `ENGINE_DEGRADED`；与代码走读预判一致（保留租户校验在认领之后）。证据 `target/feat112-evidence/FEAT-112-F4-72eda9f8-…/022.json`（服务器）。
- 单方法选择器须带参数类型：`类#方法(com.huawei.ascend.sit.judge.Scenario)`。
- B7/E9 根因（诊断包 `feat112/logs/diag-20261118.tar.gz`）：libfaketime 下 DA 整体变慢约 9 倍——4 个时钟用例 DA 启动 38–41s，其余 DA 4.5s；提交任务到首次模型调用约 13.9s，超过关联替身固定的 15s 等待，测试先放弃。不是产品问题。同时发现隐患：过期余量只有 30s，DA 变慢后旧任务终态可能落在余量之外而被计入，误判 must_violation。
- 修复（测试侧，`feat112-fix2-20261119.tar.gz`）：关联替身等待 15s→60s；`OrderedControlRun` 等待上限放宽（终态 55→120s、状态轮询 40→90s、等 HALF_OPEN 30→90s）；时钟用例静默期 10s→30s、过期余量 30s→120s（拨回 180s）；libfaketime 加 `NO_FAKE_STAT=1`。本机回归 20/20（含 7 个关联替身用例）。
- `linux-clock-2`（fix2 后）：B6、E9 PASS；B7 两个方法因测试客户端单次请求 60s 超时（旧任务的 run 请求在被扣住期间要等开门、等 HALF_OPEN 等步骤，DA 变慢后超过 60s）记 infra_failure。fix3（`feat112-fix3-20261119.tar.gz`）：`DaEvidenceClient` 请求超时上限 60s→600s，只是上限、不影响判定。
- `linux-b7-2`（fix3 后）：B7（OPEN）PASS；B7（HALF_OPEN）报 must_violation “probeStillClosesGate: expected CLOSED, actual HALF_OPEN”。诊断包 `feat112/logs/diag-b7-2.tar.gz` 时间线：探针运行请求 01:54:48.47 返回，0.06s 后读门仍为 probe=true、样本 2——按代码，探针事实若有效应关门、若过期应释放名额，两者都没发生，说明门还没处理到该事实（探针 DA 时间 01:51:48 比窗口下界晚约 11s，不是过期）。DA 收口顺序（`TerminalClosure`）是先 `markFinished` 再 `emitTerminalFact`，任务显示 completed 早于门记账；DA 变慢后测试读门过早。同一方法中“旧任务过期后门不变”那次读门只晚 4ms，也是同类竞态，原 PASS 不可信。不判产品问题。
- fix4（`feat112-fix4-20261119.tar.gz`）：时钟用例开启终态事实 DEBUG 日志（DA 在门处理完事实后才写 `terminal fact taskId=`），`OrderedControlRun.finished` 等到该任务的日志行出现再读门；60s 内未出现记激活不成立（conditional_not_triggered）。本机回归 18/18。
- `linux-clock-3`（fix4 后，2026-11-20）：4 个时钟方法全 PASS（B6、B7×2、E9）。至此 Linux 专属 8 个方法全部有确定结论：B6、B7、E9、E15、E11、F3 PASS；F4 FAIL / must_violation（产品问题，待提 Issue）。
- `linux-rest-1`（14 个类，2026-11-20）：55 个方法 50 过；G5-missing、E3、E16 为已知结论；**A2、J4 新失败（infra_failure）**：隔离交付默认 DA 启动死于 `DaBuiltinSkillInstaller` 解析中文技能目录名——`cleanEnvironment` 把 LANG/LC_* 也剥掉，Linux JVM 无 locale 按 ASCII 解析路径抛 `InvalidPathException`（Windows GBK 不暴露）。测试侧 fix5（`feat112-fix5-20261120.tar.gz`）：保留 lang/lc_all/lc_ctype，自测补断言；本机 17/17。另记：DA 在 POSIX locale 下因内置技能中文目录名启动失败，属 DA 范围的部署健壮性问题，不在 FEAT-112 条款内，可单独提给开发参考。

## 进展（2026-11-18，准备 Linux 实跑）

- 载体按两边最新源码重建：core `origin/develop@f777aa244`、solution `origin/common@96c23d4ea`，载体 sha256 `cdb5c93e…`（上一版 `2ff9090a…` 备份在 `D:/m2-feat112-backup-20261118-084154`）。core 新提交未涉及 topP，E16 预计仍走不到护栏拒绝。
- 本机冒烟（`smoke-20261118`）：结构检查、制品核对、`Feat112StateMachineTest`、`Feat112DiagnosticsTest` 共 15 个，14 通过；唯一 FAIL 为已知的 G5-missing / `must_violation`。C3、C4、E2、G1–G3、G9、G10 PASS。
- 覆盖登记中 C4、E7 说明原写“已提特性不可观测 Issue”，与事实不符（`issues-unobservable-20261117.md` 未提交），改为“待提”。只影响运行报告，PR 549 testplan 不含这句。
- 指南 `server-run-guide-20261118.md`：E5.3 → E15；主流程改为 17 个类全量回归；补已知非 PASS 结论、新载体信息、传输与 sha256 核对。两个包已打好在 `D:\`（`feat112-sit-repo-20261118.tar.gz`、`feat112-m2-openjiuwen-20261118.tar.gz`），等用户在服务器实跑后回传 `feat112-linux-full-1-results.tar.gz`。

## 进展（2026-11-17，D1 按实践修订为 v4.1）

- 用户决定按实践改 D1：D1 v4.0 §9b 的“观察不到先转下一验收层（组件级）”与“所有层都观察不到 = NOT_OBSERVED(protocol_unobservable) 合法不判定”被 FEAT-112 实践推翻。产物 `insight/D1-契约基线治理-章节稿v4.1.pptx`（脚本 `insight/insight-deck-D1章节稿-v1.1.js`），只改第 6 页 L3 行、第 8 页 Fixture 列说明、第 19 页六态与 reason_code；结构校验通过，三页经 PowerPoint 导出图检查排版正常。判断与理由记在 `insight-refresh-assessment-20261111.md` 第 4 条“2026-11-17 更正”。
- PR 549 第四个提交 `2ef8a739`（已推送，PR 说明同步）：§6、frontmatter、附录 F 的 D1 引用改为 v4.1，NOT_OBSERVED 只保留 `trace_missing`；修正了 `e0565dd7` 里“内容按新口径、出处仍标 D1 v4.0 §9b”的不一致。
- PR 548 汇总评论已发（评论 201479224，2026-11-17 18:48，发前确认 PR 仍为 `88266eff`，回读与本地留档草稿一致）：8 处，含对评论 201285073 中 L2 定位、类型标签两处说法的更正。等作者答复。
- 待同步：测试仓通用结果层 `EvidenceChecks` 仍允许 NOT_OBSERVED/`PROTOCOL_UNOBSERVABLE`。

## 进展（2026-11-17，PR 549 对齐模板 r3）

- 第三个提交 `e0565dd7` 已推送，PR 说明加“6. 对齐 testplan 模板 r3”一节，回读一致；PR 仍只改 testplan 一个文件。场景的前置、步骤、期望未变，84 个 ID 不变。
- 改了 12 处：§1/§3 来源改为“路径 @ commit 链接”（特性档、L2 `05af9cc3`；FEAT-054/111/064 `9623fd0b`；FEAT-109/110 `0aa2f57b`；代码 solution `1127a9ec`），去掉 PR 498 与“代码 PR #1030、#1065”；L2 行写明只给观测形式、不增加或放宽行为；补被依赖 testplan（001/008/064 已合入，054/109/110/111 未合入），删除“上游均未就绪”，§3.1 补 FEAT-064 行；已撤销 ID 表改四列；E3/E17/E18 去掉 PR/MR 编号，未承接原因改“待特性设计确认”；删除 A3–A6、B6、E10 的历史恢复注释；§6 三面都观测不到的 MUST 登记为覆盖缺口并挂 Issue，不用 `protocol_unobservable`；§4.2/§4.3 标题与附录 A–C 按模板骨架，原映射附录顺延为 D–F。
- 测试仓同步：`Feat112Coverage` 的 UNCOVERED/PARTIAL 说明去掉 PR 编号（Fixture 列由它回填）；指纹重新登记，变化的只有 E3、E17 两行；离线自测 69/69 通过。
- 仍待：原因码命名等 PR 548 答复（暂保持 D1 原名）；PR 548 剩余 8 处合成一条评论，起草后给用户看再发。

## 进展（2026-11-17，PR 548 r3 复核）

- PR 548 在我方两条评论后推了 r3（`49c78911`、`d430a6a7`、`88266eff`，头 `88266eff`），未回复评论。复核结论（评审记录本地留档，不入仓）：判定态与原因码、事实来源分类与版本锚、观测顺序与兜底、独立成章、编号规则（含两条更正）均已采纳；仍需修改 8 处——PR 说明与最终内容不一致（L2 定位、旧正则、存量不改名）；L2 作为“协同期望来源”需写明只能细化不能扩充特性档；§5.1 禁 PR 链接与“PR!NNN pending”例外矛盾；原因码改了 D1 原名；“观测不到”在报告里显示成什么未写死；§5.4 示例用了 PR 549 已撤销的 `FEAT-112-A5.1`；类型标签不宜放 level 列；`docs/review/` 惯例在测试仓并不存在。
- PR 549 待模板定稿后需跟进：已撤销 ID 表改四列、类型标签位置、§1/§3 去 PR 链接并补版本锚、§6 原因码、E3/E17/E18 行内 PR 编号。（除原因码外已在 `e0565dd7` 完成，见上。）

## 进展（2026-11-17，PR 549 统一编号）

- 按用户要求在 PR 549 按编号规则改名（提交 `5791d21c`，已推送；PR 说明同步加“ID 编号规则”一节与新旧对照表，回读一致）。映射：A5.1–A5.3 → A5-zero/gt1/nan，D1a/b → D1-run/stream，E4.1 → E4-formats，E5.1–E5.6 → E13–E18，F-reg → F5，G5a/b → G5-missing/invalid，G5c → G11，K-rep1–3 → K18–K20；B1.x、E1.x 不变。
- 测试仓同步：13 个源文件的 `@Covers`/`@DisplayName`/判定 ID 改名；`Feat112Coverage` 行正则改为新格式并在“已撤销 ID”登记前停止解析；指纹重新登记（键全部换新，文本变化的仅 A5-gt1、A5-nan、D1-stream、E14、E17 五行，均为行内引用 ID）；自测 69/69，报告 84 个新 ID；cases §1–§7、§8.0 改名，§8.1、§8.2 历史登记保留旧写法并加说明。未重跑 DA 场景（只改 ID 字符串，双向对账已通过）。
- 注意：PR 548 评论第五节第 8 条写的是“存量 ID 不改名”，与这次在 PR 549 改名不一致；评论中的格式正则把测试点放在变体之前（`(\.\d+)?(-…)?`），与 D1 §6c 及本次 testplan 写法（变体在前、测试点在后）相反（我写评论时的笔误）。已按用户修改后的口径在 PR 548 发更正：评论 ID 201300121（2026-11-17 11:29，内容与本地留档草稿一致（回读核对））——正则改为变体在前；第 8 条改为“已有 ID 原则上不改，编号错误时允许修改，原 ID 登记且不复用，引用同次更新”。

## 进展（2026-11-17，PR 548 评审）

- 用户要求看 [docs PR 548](https://gitcode.com/Technical-AF/docs/pull/548)（testplan 模板 r2，头提交 `7af5d5ca`，净 +76/−5）是否合理。结论（评审记录本地留档，不入仓）：方向合理（SIT scope、可观测三面、观测不到提 issue、frontmatter 禁入评审噪声），需改完再合——判定态 UNSUPPORTED/WARN/EXCLUDED 的定义与 D1 §9b 及 FEAT-112 testplan 不一致；事实来源未写“预期只来自特性档”；新增小节 4.1–4.3 与 testplan 骨架撞号；`docs/review/` 在 docs 仓不存在；“demo/UT”按关键词禁用过宽；PR 说明仍写“三态、删 required_capabilities”，与最终内容不符。
- 用户指出 testplan 缺测试点编号规则：确认模板只引用 D1 §6c 格式、未规定各段含义与新增/拆分/撤销规则；FEAT-112 已出现 `D1a`、`A5.1`（变体）、`E5.1`（独立场景）、`B1.1`（测试点）、`K-rep1`、`F-reg` 等不一致写法。评审文档第三节给出编号规则草案（场景/测试点/变体三级、序号不重排不复用、已撤销 ID 登记、类型不进 ID、存量 ID 不改名登记为历史写法）。
- 若 PR 548 按现文合入，PR 549 需跟着调整：E3、E5.5、E5.6 行内的 PR/MR 编号，正文的 `demo` 与“曾移交开发侧单元测试”注释，§6 判定态定义。均未改，待模板定稿后再改 PR 549。
- 评审意见已按用户要求发到 PR 548：评论 ID 201285073（2026-11-17 10:58，回读与本地留档草稿逐字一致）。

## 进展（2026-11-17，提交 testplan）

- testplan 三轮修订（§5 共 84 行）已提交为 [docs PR 549](https://gitcode.com/Technical-AF/docs/merge_requests/549)：分支 `docs/feat-112-testplan-revision`，提交 `e798933f`，基于 main `11369710`（该文件自 `a1db9d86` 未变，提交前已核对）；只改该 testplan 一个文件；说明见 `pr-testplan-revision-20261117.md`。
- 提交前检查 docs 仓未合入 PR：无其他 PR 改该文件；PR 514（FEAT-111 testplan，zhangyaojun2，未合入）与第 13、14 条相关，待用户通知后再对齐；PR 548 为 testplan 模板 r2，只改模板。

## 进展（2026-11-17，部署夹具）

- 测试侧补齐三类部署夹具，被测对象不改：`RedisCheckpointStore`（Testcontainers 起 Redis，作 core checkpointer，可中途停掉）、`PostgresDataPlane`（Testcontainers 起 PostgreSQL，回放 `da-dataplane-baseline.sql`，接 `deepanalyze.data.pg.*`）、`ClockControl`（libfaketime，运行中改偏移文件回拨 DA 时钟，以 DA 自报时间核对）。`DeploymentForm` 可声明这三类环境，`GateDeployment` 统一启停并在前置证据中记录。
- 新增用例：`Feat112ClockTest`（B6、B7 的 OPEN 与 HALF_OPEN 两种前置、E9）、`Feat112CheckpointStoreTest`（E5.3）、`Feat112DataPlaneTest`（F4、F3 的 team 不存在、E11）；夹具自测 `DeploymentEnvironmentTest`。未承接从 9 个降为 3 个（A1、E5.5、E5.6）。自测 69/69。
- E5.3 构造调整：旧用例 `DaRedisRecoveryE2EIT` 已记录“任务中途停 Redis 结局不确定”（checkpointer 只在运行开始、中断、结束时访问存储），改为先让任务被拒、再停在 awaiting_answer（检查点入 Redis），停 Redis 后 `/answer` 续跑，续跑开始时恢复检查点失败；以 DEBUG 终态日志 `class=INFRASTRUCTURE_FAILURE code=`（空）作激活证据。
- 本机（Windows，无 Docker、无 WSL 发行版）无法实跑：`env-20261117` 中 B6、E5.3 记 infra_failure，`Feat112DataPlaneTest` 部署阶段失败、整类未执行。须在装有 Docker 与 faketime 的 Linux 上实跑（`linux-deploy-guide.md` §0、§3 已写明）；E5.3 的失败路径、libfaketime 对 JVM 是否生效需在那里确认，走不通的按口径提不可观测 Issue。

## 进展（2026-11-17，实施）

按 [testplan-gap-review-20261117.md](testplan-gap-review-20261117.md) 修改了 testplan（第三轮，§5 共 84 行）、cases（draft-4）与用例：撤掉组件级用例类 `Feat112MechanismTest`、`Feat112TerminalClassificationTest` 与 pom `feat112-product` profile（备份 `target/feat112-refactor/removed-component-20261117/`）；`Feat112ArchitectureTest` 改为禁止组件级验收；新增 SIT 用例 E8、E4.1，E7 补“下一真实任务成为探针”，I1 补启动记录字段；Fixture 回填 75 个有承接、9 个未承接；指纹重新登记（变化的正好是改动的 15 行）。自测 65/65；实跑 E8、E4.1、E7、I1、K-rep3 通过（E4.1、E7 首跑各有一处测试侧错误，已改）。不可观测 Issue 4 条见 `issues-unobservable-20261117.md`（未提交）。

与 20 条复核点逐条对照（“现状”指今天修改后）：

| 条目 | 当时结论要点 | 现状 |
|---|---|---|
| 1 配置 | A10 MUST 带键名；A3–A6 恢复公开面；A2 键缺席提特性确认 | A10、A3–A6 PASS；A2 部分承接，DA L2（PR 538）目标为装配层尊重缺省 false，合入后定稿；A1 原组件级撤回，改为未承接（无 ext 独立宿主，需特性确认） |
| 2 窗口 | B5 新任务到达开门；诊断开门提特性确认；B6 改测试仓验收 | B5 PASS；诊断开门：DA L2 新版认定属窗口水位推进，testplan §4.4 写明诊断查询的副作用；B6 原组件级撤回，改为时钟回拨 SIT（未承接，夹具待实现） |
| 3 状态机 | C7 三段；C3/E2 补 run-stream；C4 改验收判据；新增 C8、C9 | 均 PASS；C4 的代际值不可观测（Issue 3），效果由 C5、C8 验证 |
| 4 E2/E3/E6 | E3 前置写明出厂不可达；E3 期望改 CLOSED 清窗；恢复 E10 | E10 PASS；E3 仍 conditional_not_triggered，前置补“!1131 合入后改交付配置” |
| 5 E4 | 只验记账；探针门状态待裁决；结构化码入差异清单 | E4 PASS；新增 E4.1 PASS；INPUT_REJECTED 探针变体待 PR 538 合入（DA L2 新版目标：释放名额、保持 HALF_OPEN）后补 |
| 6 E5 与 E 组 | 拆 E5.1–E5.6；E5.3 组件级；E8 尝试公开面；E11 改对外行为 | E5.1、E5.2 PASS；E5.3 原组件级撤回，改停 Redis 的 SIT（未承接）；E5.4 卡在 topP 空指针；E5.5、E5.6 等 PR 合入；E8 改为 SIT 已 PASS；E11 改为 PostgreSQL + 保留租户头的 SIT（未承接） |
| 7 F1 | 保留；补“认领后并发拒绝释放名额” | F1 PASS；E12 PASS |
| 8 F2 | 缺 sessionId 等三种参数 | PASS；另新增 F4（租户头，需 PostgreSQL，未承接） |
| 9 F3 | 对照判法；team 不存在需 PostgreSQL | 部分承接，PostgreSQL 部署形态待实现 |
| 10 F-reg | MUST；9 个入口；与 CLOSED 对照 | PASS（11-16 补 CLOSED 基线后） |
| 11 G5a–G5c | 按特性档改；G5a 修复方向待选 | G5b、G5c PASS；G5a must_violation 不变（DA L2 新版目标：交付配置默认开启服务间认证） |
| 12 J3 | DA 重连路由；补任务结束后重连 | PASS |
| 13、14 K 组 | 挂起 | 仍挂起；新增两项待并入：最大迭代次数、存储基础设施异常均计分子 |
| 15 能力列 | 全部“—” | 已落实 |
| 16 Fixture 与 §4/§7 | 由 `@Covers` 回填；§4.4 改写 | Fixture 只标 `[SIT]`；§4.4 改为观测顺序 |
| 17、18、20 | 被测物、stub、§8 与附录 | 已落实；§4.3 增加时钟控制与数据面部署形态 |
| 19 §6 六态 | protocol_unobservable 改为所有层都观测不到 | 改为契约、日志、数据三级都观测不到才成立并提 Issue |
| 补核 D | D3 值核对；D5 归属 | PASS |
| 补核 G | G4 补全；G8 重做；监控字段写明 | PASS |
| 补核 H | 新增 H4 | PASS |
| 补核 I | I1 验启动记录；告警属部署 | PASS，补验 `enabled`、`delivery default applied` 字段 |
| 补核 J | J2 断开原始流；J4 不改 | PASS；J4 前置写明关闭内容缓冲 |

## 进展（2026-11-17）

- 用户新口径（2026-11-16）：测试仓只做 SIT，不写组件级用例；契约观测不到依次用日志、Redis/DB 数据观测，都不行提“特性不可观测”Issue（已记入长期记忆）。E5.5、E5.6 不动，等 PR 463、PR 538 合入。
- PR 状态：PR 463 未更新（`8039074e`）；PR 538 在我方评论后有新提交 `3b1283ef`，§1.4 改为“目标语义与当前差距”，给出 `prepare_failed`、INPUT_REJECTED 探针、GUARDRAIL_DENIED 探针、DA enabled、诊断面认证、`business_failure` 审批、启动记录七项目标语义；“成员失败 → 任务失败”一环仍未落地，§3.5 自述“不表示任意拒绝都直接终止任务”。
- 按新口径复核 testplan，结论见 [testplan-gap-review-20261117.md](testplan-gap-review-20261117.md)：全局约定 4 处、组件级 9 行（A1、B6、B7、C4、E5.3、E7、E8、E9、E11）改为 SIT 或 Issue、新增或修改场景 8 项、不可观测 Issue 4 条。未修改 testplan、cases、代码，待用户决定先改不依赖 PR 的部分还是全部等合入。
- 上一节“E5.3 已补组件级用例”作废：按新口径需撤掉组件级用例，改为 SIT（被拒后停 Redis）。

## 进展（2026-11-16，按复核结论实施）

- testplan 修订稿 §5 由 65 行增至 82 行，落实第 1–12、15–20 条与 D、G、H、I、J 补核结论（清单见 `testplan-revision-20261112.md`“第二轮”）；Fixture 列由 `@Covers` 回填（80 个有承接，E5.5、E5.6 未承接）。第 13、14 条仍挂起。
- 逐条回查（用户追问“20 条都包含了吗”）后补上：第 6 条 E5.3 原定组件级，曾误登记为“未承接”——已补 `Feat112TerminalClassificationTest`（经 DA 公开的任务记录与事件翻译器构造终态信号，进入真实 `DaDegradationSupport`），PASS；第 16 条遗漏的 §9“长运行”一节（仍标 `@Tag("manual")` 并引 L2 §7.4 开发侧清单）已改写为实际执行方式；第 19 条/I 组遗漏的 §6 `trace_missing` 例子（仍用 I1 监控关联）已更换。仍未做（中低优先级）：第 6 条 E8 公开面尝试、第 9 条 F3“team 不存在”（需 PostgreSQL 数据面）。第 11 条“交付默认匿名响应内容越界”未单独结论，实际并入 G5a。
- 期望文本指纹：`feat112/expectation-fingerprints.txt` + `Feat112ArchitectureTest#testsWereReviewedAgainstTheCurrentTestplanText`，改 E12 期望即报 E12 变化（变红已验证）。
- 代码复核（solution `1127a9ece7b`、core `e24bcb74d`）：分类判定序只换了护栏输入（成员因果证据），准入顺序、每任务一次终态事实、启动日志不变；成员失败在公开路径上不会使任务失败，E5.5/E5.6 维持未承接。
- 新发现（非 FEAT-112）：core `e24bcb74d` 把 `ModelRequestConfig.topP` 改为可空，solution `DaWorkflowRunner.memberRequestConfig` 拆箱默认值 NPE，两边最新代码组合下所有工作流成员无法构建；E5.4 因此无法激活。记录见 `issue-core-topp-npe-20261116.md`，未对外。
- 实跑 `full-20261116`（新载体，64 个测试方法 57 个通过）：新增与改写场景全部 PASS；G5a（待特性设计选方案）、E3（出厂 ask）、E5.4（上条 NPE）为已知；另有三处测试侧错误已改并复跑通过（`rerun-20261116b` 4/4）：E5.2/G8 工具调用 + 256 个失败超出 stub 256 条上限（infra_failure）；F-reg 对 inject、cancel、answer、message、resume 写死期望值而未按约定与 CLOSED 对照，`/resume` 既有契约为 202，误判 must_violation，已撤回并补 CLOSED 基线；J4 新代码默认开启内容缓冲，整段回复结束前不下发增量，慢消费条件不成立，部署改为关闭内容缓冲。

## 进展（2026-11-13）

- 已完成：原定 20 条中除第 13、14 条外全部复核；补核 D、G（G5 外）、H、I、J（J3 外）五组。各条结论见下文。
- 挂起：第 13 条（分类记账矩阵）、第 14 条（K-rep 前置与分类设计出处），待其他测试人员把 FEAT-111 testplan 上传到 Technical-AF/docs 后，由用户通知再继续。
- 未结项：J4 在 128MB 输出压力下 DA 于终态前退出，原因未查（用户决定暂不复现）。
- 已对外：对 PR 463 分类规格与特性档/DA L2 的差异对账清单共 17 条（本地留档），已推送到 docsReview（最新提交 `08d9dc4`）；cases §8 中 G5a 定性已修正。
- PR 463 评审评论（200442164，2026-11-13）：已评估（评估记录本地留档）；按用户要求未在 PR 回复，用户已把 docsReview 差异清单转给该评审员复析，等其反馈。
- PR 463 评审评论更新（200480678，2026-11-13 发布并编辑，取代 200442164）：已评估（评估记录本地留档）。结论：可以照改；上次的改写与补充意见均已采纳，差异清单 17 条覆盖 16 条；遗漏三处（规格 §2/§3 与 DA L2 §5.2 仍把建档前返回写成 NOT_ADMITTED、评审流程与 §8、core error 通道覆盖范围），另有三条顺序建议。对测试侧：A2 本轮 PASS 不能当作满足特性档，报告需注明依赖待裁决项。
- 2026-11-16 曾在 PR 463 追加补充评论（ID 200901772），已被用户删除：其中“规格若先于代码合入，应写成目标 + 现状差距”一条错误——规格是锚点，先于代码合入，写目标行为。按用户口径：DA L2 的修改由评审员另开 PR 跟进；ext L2 不拆 PR，统一在 PR 463 跟进。修正后的补充评论已于 2026-11-16 09:06 发出（ID 200902799，内容与本地留档草稿一致（回读核对））。
- docsReview 暂不推送（用户 2026-11-16）：差异清单本地已有未推送的更正（第八条第 2 问改为“规格写目标来源”），等 PR 463 及 DA L2 跟进 PR 等相关 PR 合入后再统一处理。
- PR 538（DA L2 对齐，2026-11-16）：已评审（评审记录本地留档）。整体可合入；实质问题：成员因果证据（docs #49 裁决，solution `56e618ba5`）只证明成员因拒绝耗尽失败，未建立“成员失败 → 任务最终失败”的关联，护栏分支在任务层仍是共现；另有四处小问题。代码基线已到 solution `1127a9ece7b`，载体需在下一轮实跑前重建，E5.3 预期需按新代码重估。评审意见已于 2026-11-16 11:16 发到 PR 538（评论 ID 200956520，内容与本地留档草稿一致（回读核对））；按用户口径只谈 DA L2 刷新点，§1.4 按“当前实现与特性档的差距”理解。
- 载体重建（2026-11-13，用户要求 core 用 develop 最新、solution 用本机 common 最新）：`feat112/tools/build-carrier.sh` 从干净 worktree 构建 core `0db55c68e`（develop）与 solution `6bf40476c`（不带本地 48 个他人改动），demo 未纳入版本控制、记录内容哈希；载体 sha256 `41d7d5da…`，校验其中打包的 core 即 develop 构建；清单 `D:/m2-feat112/feat112-build-manifest.json`；旧件备份在 `D:/m2-feat112-backup-20261113-112524`。
- 来源与版本记录（架构优化第一步）：结果 JSON 新增 `provenance`（测试仓提交、特性档基线、testplan 修订、构建清单）；`summary.md` 新增报告头（口径、来源、被测物，记录不一致时告警）；载体身份改从构建清单取来源，载体与清单不符即拒绝。自测 54/54 通过。
- 按新代码复核：solution `6bf40476c`、core `0db55c68e` 上“代码”维度结论 17 项全部不变；差异清单已更新代码基线与行号（docsReview `0723111`）。新代码中影响执行的变化：SSE 内容缓冲默认开启（J4 可能不再溢出）、回答门 rail 可能多发起最多 2 轮模型调用、/answer 与 /resume 新增长度与队列上限——重跑时留意。
- 未改：按用户要求，testplan 修订稿、cases 文档（除 §1、§8 两处已改的说明）与测试代码暂不按复核结论修改。待改内容按三块组织：①testplan 修订稿（恢复 A3–A6、E10，拆分 E5，新增 C 组两条、H4，改写 E3、G8、I1，重写 §4.2/§4.3/§7，清理残留，修正 §6）；②测试代码（C7 部署形态、G8 重做、J2 断开原始流、F-reg 四个入口、F2 两种参数、D3 值核对、新增用例等）；③改后重跑并更新 cases §8。

## 第 1 条：配置与默认值（A 组）

特性档条款：能力表第 76 行“配置与默认值”MUST；§3.3 第 179–201 行；出口 1、出口 19。

| 场景 | 发现 | 结论 |
|---|---|---|
| A10 非法 `enabled` | 特性档 §3.3 末句覆盖 `enabled`（出口 19 未列）。DA L2 未设计该行为（§6.3 只写“由布尔绑定处理”，§7.1 自认未覆盖）。代码由 Spring 绑定 `boolean`，非法值启动失败且带完整键名，一致。testplan 原文 SHOULD 与特性档 MUST 不一致 | 保留修订：MUST，期望写完整键名；补改 §6 第 421 行“WARN 主要承载 A10”为“FEAT-112 场景均为 MUST，WARN 不适用”；DA L2 缺少该设计，补入给开发的差异清单 |
| A3–A6 出口 19 非法数值 | 特性档出口 19 为 MUST。代码 `validate()` 在构造门时校验并带键名，看代码一致，但 DA 启动层面无人验证（DA L2 §7.1 只有单元测试）。testplan 把 A3–A6 移交开发单元测试（§1.1 第 62、140 行，第 288 行注，第 556 行），测试仓无验收 | 新增修改：恢复 A3–A6 为公开面场景（非法值启动 DA → 非零退出 + 完整键名），测试代码补齐 |
| A2 键缺席 | 特性档只写“DA 交付配置显式开启”，未规定键缺席。DA L2 §1.2、§6.3 与代码 `DaCompatAutoConfiguration.java:254-258` 增加“键缺席即开启”（理由：宿主自带 application.yml 会遮蔽引擎 yml）。testplan A2 的预期取自实现 | 提特性设计确认：确认则特性档补写、A2 不变；否则 A2 改为只验交付配置下 `enabled=true` |
| A1、A7–A9 | 三方一致，实跑通过 | 不改 |

## 第 2 条：窗口算法（B 组）

特性档条款：§5.1.2；§4“异常率超标”；出口 2、3、4、16。

| 场景 | 发现 | 结论 |
|---|---|---|
| B5 残窗开门 | 特性档 §4：样本与异常率达标时“新任务到达”转 OPEN 并 503。DA L2 §1.3 第 3 条与代码（`DegradationGate.java:331-334`，准入与诊断快照均调用 `advance`）在诊断查询时也会开门。testplan 原文把“准入或诊断推进”作为触发，场景出处是 L2 §7.1 缺测清单 | 保留修订（新任务到达即开门，依据 §5.1.2、§4）；测试代码在过期后不查诊断、直接发新任务，验证的是特性档路径，实跑 PASS |
| 诊断查询触发 CLOSED→OPEN | 特性档只写诊断推进窗口水位、诊断可使 OPEN→HALF_OPEN；未写诊断能否使 CLOSED→OPEN。代码允许，结果与下一新任务到达等价 | 低优先级，并入特性设计确认清单 |
| B1.1–B1.3、B2、B3、B4、B7、B8 | 与出口 2、3、4、16 及 §5.1.4 一致，实跑 PASS | 不改 |
| §1.1 第 141 行 | 写“原 B6/B7 移交单元测试”，但 B7 已是 §5 场景（测试仓组件级） | 删去 B7 |
| B6 窗口内乱序到达 | 特性档 §5.1.2 要求统计窗口内全部有效终态；已移交开发单元测试；公开面不可造 | 低优先级：改为测试仓组件级验收 |

## 第 3 条：状态机（C 组）

特性档条款：§5.1.2、§5.1.7；出口 4、9、11、12、15。

| 场景 | 发现 | 结论 |
|---|---|---|
| C7 样本过期不自动关门 | testplan 原文“state=OPEN 保持”未约束窗口与静默期，默认配置下静默期先满应转 HALF_OPEN，与 §5.1.7 不一致。修订稿加“窗口短于静默期”。但测试代码用标准部署（窗口 60s > 静默期 10s），样本在 HALF_OPEN 期间才过期，断言为“OPEN 或 HALF_OPEN”，出口 4 前半句未被严格验证（此前记 PASS 偏乐观，属测试侧疏漏）；后半句（HALF_OPEN 无流量无假探针）被顺带验证 | 保留修订；测试改用窗口短于静默期的部署，分三段断言（OPEN 中样本过期严格 OPEN → 静默期满 HALF_OPEN → 无流量持续 HALF_OPEN、无探针、无模型调用）；§9 出口 4 映射由 E1 改为 C7 |
| C3 / E2 | 出口 12 要求后续双入口正常执行，测试只验 `/api/agents/run` | 测试补 `/run-stream` |
| C4 | 出口 11“该任务按真实失败契约返回”在测试中记为激活证据 | 测试改为验收判据 |
| 出口 15“探针取消后旧任务故障回 OPEN” | 无场景承接；代码一致（`DegradationGate.java:151-163`） | testplan 新增场景，测试补齐 |
| §5.1.2“OPEN 中旧任务未过期故障更新最近故障时间” | 无场景承接；代码一致 | testplan 新增场景（OPEN 后在途旧任务失败 → retryAfterSeconds 回到接近完整静默期，HALF_OPEN 推迟），测试补齐 |
| C1、C2、C5、C6 | 与出口 2、3、9、10、15 一致，实跑 PASS | 不改 |

## 第 4 条：探针健康结果（E2、E3、E6，出口 12、13）

| 场景 | 发现 | 结论 |
|---|---|---|
| E3 交付形态可达性 | 特性档出口 12 要求业务失败可证明健康并关门。ext L2（PR 463）称无产生点；DA L2 与代码有 `business_failure` 工具 + Rail。但出厂 actrule 对 `business_failure` 落入 `"*": ask`，探针先进 awaiting_answer 并按 E6 关门，业务失败关门路径在出厂形态下走不到；场景 actrule 只能取严合并，不能放宽（`DaEngineConfiguration.java:990-1004`）。实跑 conditional_not_triggered | E3 前置写明“`business_failure` 已放行”的部署形态，注明出厂不可达，待开发答复（差异清单第一条） |
| E3 期望与 E2、测试矛盾 | 特性档 §5.1.6/§4“按健康样本记账、计正常样本”与 §5.1.7/出口 12“清空窗口”未说明先后；代码先记后清，关门后样本数 0；测试断言 0；testplan E3 写“只计分母” | E3 期望改为“CLOSED、窗口清空”；步骤改为对外行为（模型调用 `business_failure` 工具）；“探针自身样本清空后是否保留”提特性设计（低优先级） |
| 出口 13 后半句 | 回答续跑、inject、恢复不重复累计无场景承接；原 E10 被剔除，理由三处矛盾（第 122、345、507 行），§9 第 550 行仍映射 E10；代码看一致（`DaHandlerTaskExecutor.java:364-369`、`DaTaskRecord.java:767-778`） | 恢复 E10 为公开面场景（awaiting 关门 → 回答续跑注入系统异常 → 样本与异常各只 +1；加 inject 变体）；修正四处说法 |
| E2、E6 | 特性档、DA L2、代码一致，实跑 PASS；ext L2（PR 463）漏写 E6（差异清单第四条） | 不改 |

## 第 5 条：上下文过长（E4）

| 场景 | 发现 | 结论 |
|---|---|---|
| E4 记账 | 特性档 §5.1.3 第 247、258 行、出口 5：结构化 181012 只计分母。ext L2（PR 463）、DA L2、代码记账一致；测试在 CLOSED 下验样本 +1、异常不变，实跑 PASS | 保留修订 |
| E4 原文“探针健康关门” | 期望取自实现与 DA L2 §7.1 缺测清单；DA L2 §1.3 第 5 条自认该行为宽于需求；特性档 §5.1.6 健康结果不含此类 | 修订必要：探针门状态不作期望；特性设计裁决后（差异清单第三条）补探针变体，若裁定非健康则现代码为缺陷 |
| §7 第 475 行残留 | “需补齐：INPUT_REJECTED / GUARDRAIL_DENIED 探针关门（E4、E5）”与修订后 E4/E5 矛盾 | 改为“E4/E5 只验记账；探针门状态待特性设计裁决” |
| 识别方式 | 特性档要求结构化类别，FEAT-054 第 243 行要求换结构化码源；代码匹配 core 错误文本片段（`DaErrorCodes.java:68,147-164`），core 错误结果到 DA 只剩文本（`DaEventTranslator.java:572-574`） | 沿用差异清单第八条 |
| 非上下文过长的内容拒绝按系统异常 | 出口 5 要求，无场景 | 并入第 13 条 K 组 |

## 第 6 条：护栏拦截（E5）与 E 组其余

core 依据：agent-core-java develop `0db55c68e`（2026-11-12）；载体打包 core 0.1.17。

| 场景 | 发现 | 结论 |
|---|---|---|
| “任务因护栏拦截而失败”是否存在 | FEAT-109 第 327 行：拒绝不等于取消任务；core `PermissionInterruptRail` 拒绝返回 RejectResult，`InterruptRailSupport.java:131-139` 跳过工具、拒绝内容作工具结果、循环继续；DA hook、预检、`/answer` 拒绝同样继续。三方均无“拒绝终止任务”路径。故 GUARDRAIL_DENIED 分支（`DaDegradationSupport.java:282-283`）实际只豁免“拒绝后原因不明的失败”，正是特性档 §6 第 335 行禁止豁免的情形 | 提特性设计：§5.1.3 第 248 行对应的具体场景是什么；若 DA 中不存在，去掉该分支或由实现提供正向因果事实 |
| 反复被拒直到最大迭代 | core 返回 `result_type=error`“Max iterations reached without completion”（`ReActAgent.java:2456-2466`），DA 归 MODEL_SERVICE_FAILURE 计分子，且自 `1ea268090` 起优先于拒绝证据 | 并入上一问题：此类是否算“确由护栏导致” |
| E5 原文“探针健康关门” | 照搬实现行为，与 E4 同 | 修订必要 |
| E5 改写 | 修订稿只验记账、标“未承接” | 拆为 E5.1 拒绝后正常完成（只计分母，公开面）、E5.2 拒绝后系统异常（计分子，公开面）、E5.3 拒绝后原因不明失败（按特性档计分子，组件级，现代码预计 must_violation）；拒绝用 `write_file`（工作区外路径预检拒绝，或工作区内 ask 后 `/answer` 拒绝） |
| 更正 | 之前称 `delete_file: deny` 可构造拒绝，错误：DA 未注册该工具 | 评审文档 T4、差异清单第六条更正 |
| 181012 结构化码 | core 0.1.17 `buildErrorResult`（`ReActAgent.java:4052-4087`）附 `failure`{code, numericCode=181012, retryable}；DA 翻译只保留 result_type 与 output（`DaEventTranslator.java:572-574`）后匹配文本 | 差异清单第八条改写为“core 已提供结构化码，DA 丢弃”，修复方向为读 `failure` |
| E8 | 只有组件级；调小执行池可能公开面触发 | 尝试公开面（中低优先级） |
| E11 | 期望写内部类 `DaProbeSlotHandle` | 改为对外行为：名额不被永久占用、下一真实任务可成为探针（依据 §5.1.4） |
| E1.1、E1.2、E7、E9 | 一致，实跑 PASS | 不改 |

## 第 7 条：认证先于降级门（F1）

| 场景 | 发现 | 结论 |
|---|---|---|
| F1 | 特性档 §3.1、§5.1.1：入站认证先于门，无效请求不占探针（出口 18 未列认证）。DA L2 §2.1“服务间认证（启用时）”，交付默认未启用，F1 需已配置认证的部署。代码认证在过滤器，早于门。testplan 原文只验 OPEN（“不占探针”在 OPEN 下无意义）、只测 `/run`、只发错误凭据、写死 401 | 保留修订（OPEN 与 HALF_OPEN、缺失与错误凭据、双入口、与 CLOSED 对照）；测试已对齐，实跑 PASS |
| 出口 18 末句“认领后并发拒绝释放名额” | 认领后待答任务触顶返回 `ENGINE_BUSY`（`DaCompatController.java:1473-1480`），句柄释放名额；无场景承接（E8 只有执行池满的组件级） | E8 下补公开面变体：`max-awaiting-tasks=1`，先造一个 awaiting 任务，HALF_OPEN 下新任务返回既有 BUSY、名额释放、门保持 HALF_OPEN、下一真实任务可成为探针 |

## 第 8 条：参数先于降级门（F2）

| 场景 | 发现 | 结论 |
|---|---|---|
| F2 | 特性档 §5.1.9、出口 18。DA L2 §2.1 与代码一致：门前四类参数校验（`sessionId` 缺失、`input` 与 `mediaIds` 均空、`runtimeConfig.version` 不支持、`runtimeConfig.hooks` 非法）。testplan 原文“缺 `method` 字段”是 A2A 字段，DA 请求体无此字段，按原文请求合法、OPEN 下应 503，期望 400 会误判；且只验 OPEN、只测 `/run`、写死 400 | 保留修订（缺 `sessionId`、OPEN 与 HALF_OPEN、双入口、对照判法）；测试已对齐（含无副作用、不占探针），实跑 PASS |
| 门前参数校验覆盖 | 测试只构造缺 `sessionId` 一种 | F2 增加“`input` 与媒体均空”“`runtimeConfig.version` 不支持”两种（后者为 FEAT-112 专门前移的校验）（中低优先级） |

## 第 9 条：媒体与 team 先于降级门（F3）

| 场景 | 发现 | 结论 |
|---|---|---|
| F3 | 特性档出口 18、§5.1.9 未规定状态码。代码门前：team 通道未启用 503（`DaTeamUnavailableException`），通道可用但 team 不存在 404，媒体不存在/白名单拒绝为既有校验错误；白名单校验器默认不启用。testplan 原文“四次均为 4xx”与代码不符（误判正确实现），且只验 OPEN；DA L2 §7.3 第 443 行同样写“既有 4xx” | 保留修订（对照判法、OPEN 与 HALF_OPEN、部署配置媒体目录与白名单）；测试判据比对状态码、错误码与错误内容，能区分 team 503 与降级 503，实跑 PASS |
| “team 不存在” | 需团队通道可用（PostgreSQL 数据面）才会检查；现部署只走“通道不可用”分支，未覆盖 | F3 前置注明；补带数据面的部署形态后再验（中低优先级） |
| DA L2 §7.3“4xx” | 与代码不符 | 补入给开发的差异清单（低优先级） |

## 第 10 条：在途控制入口不被阻断（F-reg）

| 场景 | 发现 | 结论 |
|---|---|---|
| F-reg 级别 | 特性档能力表第 68 行“只拦新增负担”MUST、出口 8；testplan 原文 SHOULD，违反只记 WARN | 保留修订（MUST）；DA L2 与代码一致（门只在 `prepareExecution`，`DaCompatController.java:1468`），实跑 PASS |
| 入口覆盖 | 特性档 §3.1 列 9 个不阻断入口；测试覆盖 `/status`、`/stream`、`/inject`、`/cancel`、`/answer`，缺 `/tasks`、`/resume`（能力表点名）与 `/task`、`/message` | 补四个入口的冒烟（OPEN 下调用、与 CLOSED 对照、无 `ENGINE_DEGRADED`）；`/resume` 可复用 awaiting 任务作前置 |
| 判法 | testplan 要求“与非 OPEN 时原本应返的码相同”，测试写死“200/running”等 | 改为与门 CLOSED 时同一请求对照（小问题） |
| 未改动组 | 20 条只涵盖修订过的行 | 20 条后补核 D、G（G5 外）、H、I、J（J3 外）组 |

## 第 11 条：诊断面认证（G5a–G5c）

| 场景 | 发现 | 结论 |
|---|---|---|
| G5b、G5c | testplan 原文前置写死 `deepanalyze.auth.enabled=true`、期望写死 401 `{error:"unauthorized"}`，取自实现（`DaServiceAuthFilter:63-92`）；修订改为与其它受保护端点对照 | 保留修订；实跑 PASS |
| G5a 交付默认 | 特性档能力表第 74 行“而非公开匿名面”、出口 16“要求合法服务间认证”。DA L2 §1.2 自认“部分已有”。代码：认证开关默认关闭（`DaAuthProperties.java:17`），关闭时 DA 全部接口（含 `/run`）都不校验；开启时过滤器同守 `/api/agents/**` 与诊断面（`DaServiceAuthFilter.java:88-92`）。实跑 must_violation | 违反特性档字面；修复方向待特性设计选择：(a) DA 交付默认开启认证（全接口，超出 FEAT-112）；(b) 仅诊断面在未配置认证时拒绝匿名；(c) 澄清出口 16 只要求已配置认证的部署。testplan 暂不改；此前“产品缺陷”的定性过重，差异清单第九条与 cases §8 同步修正 |
| 诊断内容是否越界 | 交付默认匿名响应含护栏台账、hook、轨迹统计等 | 补核 G 组时随 G8/G9 看 |

## 第 12 条：SSE 重连不新增样本（J3）

| 场景 | 发现 | 结论 |
|---|---|---|
| J3 | 特性档能力表第 64 行 MUST、§5.1.5、出口 6；能力表第 77 行“标准 A2A”入口本期 OUT。ext L2 §1/§7、DA L2 §1.2/§7.2、代码一致（`/api/agents/stream/{taskId}` 读缓冲、不产生事实）。testplan 原文步骤“`SubscribeToTask`”是标准 A2A 方法，测的是特性档排除的入口 | 保留修订；测试在执行期间重连 3 次，验无新增模型调用、无新增样本、单任务，实跑 PASS |
| 任务结束后重连 | 出口 6 同样适用，测试未覆盖 | 补变体（低优先级） |

## 第 13 条：分类记账矩阵（K 组）——挂起

状态：待 FEAT-111 testplan 上传到 Technical-AF/docs 后再定（2026-11-13 用户告知由其他测试人员准备中；此前“FEAT-111 testplan 不存在”的说法更正为“尚未上传”）。

已核对的事实（供届时对照）：
- 特性档 §1.2 第 51–52 行：分类由 agent-runtime-ext-java 交付、属本特性，FEAT-111 与本特性消费同一分类；§5.1.3 十行与出口 5 是 FEAT-112 的 MUST 验收。FEAT-111 特性档只验收反思处置，其公开观察面（`da_reflection_result`）对“只计分母的失败”（业务失败、181012、护栏）与系统异常都按 failed 触发反思，区分不了分子与分母。
- 当前承接：正常完成（K-rep2）、模型 5xx 耗尽（K-rep1）、用户取消（K-rep3）、181012（E4）；其余条目（5xx 重试成功、内容拒绝按系统异常、护栏确认处取消、会话忙、429 耗尽、超时、网络中断、401/403/配额、基础设施异常、入站与上游 4xx 配对、F1 窗口检查）无承接。
- 构造注意：stub 快捷错误格式不一定被 core 正确分类（如 stub 把配额映射为 429，core 按限流处理，`RuleBasedModelErrorClassifier.java:28`），须用真实提供方格式。

届时要核对：
1. FEAT-111 testplan 对 §5.1.3 各行验证了什么、经由什么观察面；
2. 凡是 FEAT-111 观察面区分不了分子与分母的行，FEAT-112 仍须自己验记账效果；
3. testplan 第 25、60、76、121–123、136、146、156、399–409、465、471、503、507、515、542 行的归属表述据此修改。

## 第 14 条：K-rep 前置与分类设计出处——随第 13 条挂起

K-rep 前置“SIT 启动 FEAT-111 跑一遍确认分类正确”取决于 FEAT-111 testplan；分类设计出处由 DA L2 §3.5 改引 ext L2（PR 463 合入后）独立且工作量小，届时一并处理。

## 第 15 条：`required_capability` 列与 frontmatter

| 场景 | 发现 | 结论 |
|---|---|---|
| 能力列与 frontmatter | 特性档全部 MUST，无契约可选能力，配置项是部署参数。docs main 模板 `00-testplan-template.md` 第 44 行：`required_capabilities`“仅适用于 A2A 协议 4 项固定字段”。原文 23 行填配置键、42 行填“（内部）”，frontmatter 要求 demo 表态 7 个键；§6 第 429 行未声明即 UNSUPPORTED 合法跳过，可能使 F1、G5a–G5c 等 MUST 场景被跳过，并与 A2（键缺席）矛盾 | 保留修订（全部“—”、frontmatter 清空并注明）；必要性由“建议”升为“必要”；23 行配置均已移入前置列，核对无遗漏 |

## 第 16 条：Fixture 列与 §4.3、§4.4、§7

| 场景 | 发现 | 结论 |
|---|---|---|
| Fixture 列 | 原文 34 行填开发仓测试、2 行填实现代码行、7 行“补齐测试（L2 §7.x）”，与独立验收原则冲突；修订改为测试仓承接类加验收层（由 `@Covers` 回填）。`Feat112ArchitectureTest` 只核对 ID 双向一致，不核对 Fixture 内容 | 保留修订；新增场景实现后同步，考虑由 `Feat112Coverage` 生成（低优先级） |
| §4.3、§7 Fixture 表 | 列 A2A 驱动（`A2aHttpProbe` 等，DA 双入口非 A2A、标准 A2A 为 OUT）、不存在的 `DegradationHealthProbe`、全局固定“窗口 30s/静默期 10s”、`SutStack` 注入 `DA_ENGINE_SECRET`，均与测试仓不符 | 按实际重写（`GateDeployment`/`DeploymentForm`/`IsolatedDefaultProcess`、`DaEvidenceClient`、`GateDriver`、三种 stub） |
| §7“代码级引用（复用）” | 把 8 个开发仓测试写成复用 | 改为“开发侧测试，仅供参考，不计入验收” |
| §7“需补齐测试资产（L2 §7.4）” | 开发自己的缺测清单混入，含过期内容（第 471、475 行） | 删除；testplan 缺口由 Fixture“未承接”与覆盖登记表达 |
| §4.4 第 249 行 | “不读日志”与测试分层不符（激活证据用 stub 计数与进程输出，组件层验产品类） | 改写：验收判定只依据公开观察；前置与激活证据可来自 stub 与进程输出；组件层另述 |
| §4.4 第 252 行“唯一 stub” | 与 §4.5 同类残留 | 并入第 18 条 |

## 第 17、18、20 条：被测物描述、stub 规格、§8 与附录 C

| 条目 | 发现 | 结论 |
|---|---|---|
| 17 `sut` | 原文“真实 LLM”与实际不符，修订为 stub 驱动 | 保留修订 |
| 17 `compatibility` | `agent-runtime-ext-java: "0.1.2+"` 不成立：Central 0.1.3 不含降级门，尚无包含它的发布版本，被测物由 solution `0f5d17265` 源码构建 | 新增修改：注明无发布版本，以构建来源 commit、制品坐标与 sha256 标识 |
| 17 头注释第 32 行 | 仍写“§8c（L2 能力门 + SIT 反查）” | 同步改（低优先级） |
| 18 §4.2 | “只需要一个”stub 漏改（此前称已删，实际只改了 §4.5 ①）；切换方式写 `DA_MODEL_BASE_URL`，实际为属性 `deepanalyze.model.backend.base_url` 且带 `/v1`（`GateDeployment.java:26,93`）；`setNextError` 称返回 DA 结构化错误码，实为 HTTP 状态加简易体，简易格式不一定被 core 正确分类；E3 示例 `sql_query`、E5 示例 `shell_exec rm -rf /` 均错误 | 按实际重写：三种 stub（`LlmFaultInjectionStub`、`CorrelatedModelStub`、`SustainedModelStream`）的用途、测试接口、切换方式，并要求分类相关场景用真实提供方错误格式 |
| 18 残留 | §4.4 第 252 行、§7 第 451 行“唯一 stub”；§4.5 ④ `[stub]` 前缀与 `[SIT]/[组件]` 不一致；§4.5 第 269 行重复行 | 清理、对齐 |
| 20 §8、附录 C | §8 第 490 行“前置与激活证据”、附录 C 第 4/8/10 条与第 15、19 条及测试代码一致；§8 其余断言与特性档一致（“每任务只记一次”待 E10 恢复后有承接） | 保留修订（措辞类，可选） |

## 第 19 条：§6 六态处置

| 项 | 发现 | 结论 |
|---|---|---|
| UNSUPPORTED | 原文“能力未声明即合法跳过”；测试代码部署形态缺失记 infra_failure，FEAT-112 不产生 UNSUPPORTED | 保留修订（不适用），必要 |
| conditional_not_triggered | 原文处置 WARN；测试代码前置或激活不成立判 FAIL/conditional_not_triggered | 保留修订（记 FAIL、结论无效、修前置重跑），必要 |
| protocol_unobservable | 原文以 G8 与内部状态为例；修订为所有验收层都观察不到才成立，内部机制先由组件级承接 | 保留修订，必要 |
| 过期内容 | WARN“主要承载 A10”；model_variance“K 组可能遇到”（stub 确定性驱动，不适用）；conditional_not_triggered 例子“K8”（已删） | 改为：WARN 不适用；model_variance 本特性不适用；例子换为 E3 停在等待确认、J4 首轮未溢出 |
| NOT_EXECUTED、EXCLUDED、报告聚合 | 报告用 NOT_EXECUTED 表示无承接（非判定态），§6 未说明；§5 无 OUT 场景，不产生 EXCLUDED；“Allure 二级聚合”与实际 `summary.md` 不符 | 补说明；聚合写法按实际改（低优先级） |
| I1 监控关联 | 部分承接却记 PASS，是否应单列 trace_missing | 补核 I 组时看 |

## 补核 D 组：拒收契约与无副作用

| 场景 | 发现 | 结论 |
|---|---|---|
| D1a、D1b、D4、D6 | 与特性档 §3.1、能力表“双入口一致拒收”“不制造假任务”、出口 7、出口 8 前半句一致；DA L2、代码一致；实跑 PASS | 不改 |
| D3 | 只校验 `Retry-After` 与 `retryAfterSeconds` 相等，未核对等于剩余静默时间 | 测试补：与请求前诊断面 `retryAfterSeconds` 相差不超过 1 秒（低优先级） |
| D5 | HALF_OPEN 下已检查 `state`/`instanceId`，结果记在 E1.2 名下 | 结果同时归到 D5（低优先级） |
| “D4”重名 | 特性档“D4”指前端（第 55、90、98、176 行）；testplan 场景 D4 指 HALF_OPEN 省略恢复时间；§8 第 488 行用的是特性档含义 | 不改 ID，testplan 加注明；出口 9 引擎侧义务（HALF_OPEN 期间诊断保持 degraded）在 G 组核对 |

## 补核 G 组（G5 以外）：健康诊断面

| 场景 | 发现 | 结论 |
|---|---|---|
| G1–G3、G9、G10 | 与特性档 §3.2、能力表“状态可见”“不破坏探针与认证”、出口 16 一致；DA L2、代码一致；实跑 PASS | 不改 |
| G4 | 测试按完整字段检查；testplan 期望少写 `status=healthy` 与 `systemFailureCount`/`systemFailureRate`=0 | 期望补全（低优先级） |
| G8 | 特性档要求不泄露原始异常、用户输入、工具参数、供应商密钥；测试哨兵只有用户输入、业务失败输出、服务间认证密钥（不是供应商密钥），缺原始异常与工具参数；且依赖 `business_failure`（出厂需确认）导致 conditional_not_triggered | 重做（中高优先级）：用户输入放 `/run` input；原始异常由 stub 500 错误体携带并借此开门；工具参数由 stub 发起带哨兵的 `write_file`（工作区外路径被拒）；供应商密钥配置为哨兵值；OPEN 下整段诊断响应不得含任一哨兵 |
| 出口 9 引擎侧 | HALF_OPEN 期间诊断 degraded（G3）、非探针新任务 503（E1.2、D4）；§9 只映射 C2、E1 | §9 补 G3，配合“D4”注明 |
| 监控所需的产品输出 | §3.2 第 177 行要求部署方据 `state`、`probeInFlight` 告警；这些字段是产品输出，SIT 已覆盖：G1–G3（各态字段）、C2（探针运行时 true）、E7/E8（释放后 false）、C7（不自动 CLOSED、无流量长期 HALF_OPEN，C7 改造后）。门状态切换不打日志，特性档未要求，监控靠轮询诊断即可 | testplan 写明“监控字段 → G1–G3、C2、E7/E8、C7”；只有告警规则本身写为部署方承接（2026-11-13 修正：此前“整条监控义务不在范围”的说法不对） |

## 补核 H 组：多实例

| 场景 | 发现 | 结论 |
|---|---|---|
| H1–H3 | 与能力表“多实例语义诚实”、§5.1.8、出口 17 一致；测试用两个真实进程（开发测试只用同 JVM 两对象，DA L2 §1.3 第 11 条），实跑 PASS | 不改；H3 标题“D4 提示”按 D 组约定加注明 |
| 出口 17“半开名额互不影响” | 无场景承接；按代码各进程门独立 | 新增 H4（公开面）：两实例均 HALF_OPEN，A 被慢任务占住探针名额时，B 诊断 `probeInFlight=false`、B 新任务被受理为 B 的探针、A 其余新任务仍 503 |

## 补核 I 组：进程重启

| 场景 | 发现 | 结论 |
|---|---|---|
| I1 引擎侧 | 与 §5.1.7、出口 20 一致：新 `instanceId`、CLOSED、零计数、无探针，只记为新进程初始化；实跑 PASS | 保留 |
| I1 进程启动事件 | 出口 20、§5.1.7 第 302 行要求监控结合“进程启动事件”；特性档与 DA L2（§7.2、§7.4 第 462 行）都未定义其载体。代码只有诊断 `instanceId` 每进程不同，及启动 INFO 日志 `da degradation gate wired enabled={} instanceId={} …`（`DaCompatAutoConfiguration.java:261`）；SIT 只验了前者 | SIT 补验：新进程输出该启动日志，且其 `instanceId` 与诊断一致（结论注明“载体待开发确认”）；提开发确认启动日志即约定的启动事件、格式与字段成为契约（补入差异清单） |
| I1 告警关联 | “保留原故障告警、不因重启清除”属部署监控 | 从 I1 期望中拆出，写为部署验收承接；报告不再以 PASS 覆盖 |
| I2 | 与特性档一致，实跑 PASS | 不改 |

## 补核 J 组（J3 以外）：传输事件

| 场景 | 发现 | 结论 |
|---|---|---|
| J1 | 与能力表“传输事件不独立记账”、§5.1.5、出口 6 一致；断开原始 run-stream 后任务完成，样本 1、异常 0，实跑 PASS | 不改；激活证据描述“before the observer disconnected”改为原始流（小问题） |
| J2 | 测试以 `/run` 发起，断开的是旁路观察流 `/api/agents/stream/{taskId}`，未测“原始 run-stream 断开后最终系统失败”，弱于出口 6 | 测试改为与 J1 相同：`/run-stream` 发起、start 后断开原始流、注入故障失败、轮询终态，验样本 1、异常 1；原做法保留为附加证据 |
| J4 | 一致，实跑 PASS；事件队列调为 64 属声明的部署前置 | 不改；128MB 输出压力下 DA 在终态前退出、原因未查，列为未结项（用户决定暂不复现） |

## 汇总：待提给开发与特性设计的事项（合并进差异清单第十二至十七条）

第 1 条 A2、非法 `enabled`；第 2 条诊断开门；第 4 条探针自身样本；第 9 条 DA L2“既有 4xx”；I 组启动事件载体。
