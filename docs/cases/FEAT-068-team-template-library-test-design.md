---
title: FEAT-068 团队模板库 测试设计初稿
feature_id: FEAT-068
document_type: test-design
status: draft
automation_status: design-only
feature_source: docs/develop/02-features/FEAT-068-team-template-library.md @ 1f996bc1（a72865fd），sha256 94bddd7f…
l2_source: docs/develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-068-team-template-library.md @ 1f996bc1（2d67dd7b），sha256 b3dfd40e…
requirement_preview: 02-需求与测试预览-FEAT-068.md
change_scope: 01-变更包范围卡-FEAT-068.md
input_maturity: mixed
---

# FEAT-068 团队模板库 · 测试设计初稿

## 0. 用户必读：需求与测试方案

- 一句话说明：把"怎么组织一个多智能体团队"的经验固化成随包预置模板，用户报模板名即可发起确定性编排，不用手写图纸 YAML。
- 关键链路：用户 → 会话工具（067 暴露 template 入参）→ 名字白名单校验 → 随仓 YAML 资产解析 → 装载器（065 契约）→ 照图执行 → 逐步留痕 + 收口产物。
- 本期测试：① 组件层确定性结构判据（八套装载/映射口径/council 角色/动态生成契约/解析安全/清单对账）——证明"模板即判据"可重放；② 真机端到端（council + 并行深度检索点名全链路）——证明真实使用形态成立；③ 对抗探针（跑完即删）——证明安全行为。
- 本期不测：执行语义（065）、交叉验证编译语义（066）、装配门控与出参契约（067）、运营面/参数化/动态会话入口（OUT）、085 重构态（范围裁决划出）、模板产物内容质量（设计 §5.2 不承诺）。
- 未决影响：无（必须澄清 0 项，见设计问题清单）。
- 详细预览：[02-需求与测试预览-FEAT-068.md](02-需求与测试预览-FEAT-068.md)
- 本期范围：[01-变更包范围卡-FEAT-068.md](01-变更包范围卡-FEAT-068.md)
- 输入成熟度：mixed——docs FEAT-068 两文档逐字未变（draft 态）；被测钉子 **`ce9019d86`（post-085 形态）** 为用户第二次裁决基线（2026-10-19 改测新形态，见 `06-增量审视与基线切换记录-20261019.md`），结论绑定该钉子。
- 用户理解确认：`confirmed`，2026-10-19（范围卡+预览 AskUserQuestion 确认；不授权 Java 实现）。

## 1. 依据、当前口径与范围

- 设计真相源：Feature @ `1f996bc1`（sha256 94bddd7f…，冻结于范围卡 §1）
- L2 实现说明：L2 @ `1f996bc1`（sha256 b3dfd40e…，同上）
- 已确认裁决：① 被测代码钉子 **`ce9019d86`**（2026-10-19 二次裁决改测 post-085 新形态；初裁 `6cdf72a6b` 留痕于范围卡 §4.5）；② 执行体形态两种都要（确定性结构断言 + 真实模型端到端）；③ 动态生成器纯函数直调不属 Mock LLM（生成逻辑不涉模型调用，见预览 §5）；④ 085 重构的**通道语义验收**归 085 转测批次，本批按新形态验模板面自身
- 范围内：范围卡 C1–C6（八套资产 / 映射口径 / 动态两形态 / 命名解析 / 清单对账 / 模板即判据）
- 范围外：范围卡 §4.1（OUT 条款 5 项）+ §4.2（067 承接 3 例）+ §4.3（085/069/同批无关改动）
- 未决问题：0 项必须澄清（设计问题清单 0 + L2 问题清单 0；留档 7 项不阻塞）

> 责任人门禁核对：本批无对外提问；所有候选问题已在设计/L2 问题清单中经代码实读闭合或留档。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| deepanalyze-java 宿主（engine 模块） | 被测 SUT（唯一） | C1–C6 全部：模板资产、解析接缝、动态生成器、清单 | — | Feature §1 module: deepanalyze-java；L2 §1.1 |
| 编排引擎（agent-core-ext-orchestration） | 依赖件（065 线被测） | 装载校验被模板资产消费 | 其自身语义不在本期断言 | 范围卡 §3 责任边界关 |
| 会话工具 workflow_run | 依赖件（067 线被测） | 解析失败的错误面经其暴露 | 装配门控/出参契约 | L2 §2.4；067 承接（范围卡 §4.2） |

不因存在多种 Agent 架构（deepagent/reactagent 等）扩大 SUT——模板库仅存在于 deepanalyze 宿主。

## 3. 黑盒边界、拓扑与前置条件

- 拓扑：SutStack 声明式拉起 deepanalyze 宿主（exec JAR `0.1.3-feat068-r2`，叶子优先、自动端口）；Redis 走远端模式 `127.0.0.1:6379`（ENV-1.9/1.15，`sit-redis-feat065067` 容器存活，执行预检复核）；组件层用例另经 engine plain JAR（`com.openjiuwen.da.workflow.*`）与 orchestration jar（`com.openjiuwen.agents.orchestration.DynamicTeamTemplates`，085 移入形态）import 公开类与 classpath 资产。
- 静态合同用例沿用上批 `*.source.root` 机制（run-28 先例）：对照源码 = 钉子 worktree `agent-solution-6cdf72a6b`…**已随基线切换更新为 ce9019d86 worktree**（`02-Code/_worktrees/agent-solution-6cdf72a6b` 需重建为 ce9019d86 或改传新路径）。
- 真机 e2e 前置：`~/.llmrc` 已配置（ENV-4.1，2026-10-19 复核）；LLM_API_KEY 由 runner 受控加载，Agent 不读值。
- 制品前置（实现阶段，需授权）：core 0.1.17（9966e03d）与 runtime 0.1.4 已在 m2repo（ENV-3.6/3.10，无需重编）；仅重建 orchestration + engine @ `0.1.3-feat068`；acceptance 仓属性块切版本。
- 黑盒红线：不 Mock LLM（真机用例走真实模型）；不直连 fixture 数据库断言；白名单/解析语义优先经外部行为断言，源码级静态断言只用于"代码形状"合同（清单常量、白名单正则），与上批静态合同用例同型。

## 4. 需求覆盖矩阵

稳定条目目录与唯一覆盖台账：`review-deliverables-feat-068.json`（TestPlan 阶段建立，含铁律 A 横切分母审计）；实际结果与缺口不在本文件手工重复维护。

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| 068-S01 | 八套逐套装载与结构判据 | execution |
| 068-S02 | 映射口径判据 | execution |
| 068-S03 | council 角色与步骤结构判据 | execution |
| 068-S04 | 动态跨库对比生成契约 | execution |
| 068-S05 | 动态零幻觉契约（单库退化=MUST 硬断言，2026-11-18 评审升格） | execution |
| 068-S06 | 动态空列表拒绝 | execution |
| 068-S08 | 双入参全文优先 | execution |
| 068-S10 | 真机 e2e：council 点名全链路 | execution |
| 068-S11 | 真机 e2e：parallel-deep-retrieval 点名全链路 | execution |
| 068-S12 | 会话侧动态通道：@cross-kb-comparison + kbScope 生成/空范围受控拒绝 | execution |
| 068-S13 | 原版中文团队名映射解析并编排 | execution |

<!-- coverage:scenarios:end -->

说明：与模板相关的既有承接有**三层**——① 开发侧 UT：`WorkflowRunToolTest` 模板 3 例（钉子态 :119/:132/:142）；② **SIT 级：067 线 `067-S09`（白名单/穿越/未知名/错误面）与 `067-S10`/`067-S21`（资产↔清单对账），covered**（acceptance 仓 `component/orchestration/`，067 台账 REQ-014/015/016/034 引用，run-35）；③ 开发侧 `TeamTemplateLibraryTest` 6 例。⇒ **068‑S07/S09 撤并**（实现阶段实读发现，2026-10-19 二次修正，见范围卡 §4.2）：白名单/错误面/对账语义由 067 线承接，本期重复建造违反「同一断言不得双重归属」；本期独立承接=资产结构判据、动态生成契约、双入参、坏图纸负例、无专用分支 static、真机旅程。

### 4.1 未承接与降级条目

无（范围卡 §4.4 未承接 0 项；设计期无降级）。台账建立后链接缺口视图。

## 5. 端到端场景

| Given | When | Then | 承接用例 |
|---|---|---|---|
| 编排工具已装配、宿主存活 | 会话点名 multi-perspective-council + 分析问题 | 逐步留痕可见三视角→互审→审计顺序；收口产物非空；blueprint 类型 cross_validation 生效 | 068-S10 |
| 同上 | 会话点名 parallel-deep-retrieval | 双路（语义/精确）检索标记汇聚到汇总步 | 068-S11 |
| 同上 | 点名 no-such-template | 工具级 `unknown template` 错误，会话不炸、未静默换模板 | 067-S09（067 线，covered） |
| 同上 | template=`../secrets`、`a/b`、空串 | 同上可读错误；无文件系统访问迹象 | 067-S09（067 线，covered） |
| 同上 | blueprint 全文 + template 同给 | 按 blueprint 执行（结果与 template 名无关） | 068-S08 |

## 6. 详细用例

### 068-S01：八套逐套装载与结构判据

- 可执行性/优先级：runnable / P0
- 计划验证形态：execution；适用 SUT：deepanalyze engine；测试层次：component
- Oracle 来源：Feature §2 行1 + L2 §2.1 结构表（逐套）
- Given：engine plain JAR 在 classpath，八套资产可经 `/workflow-templates/<name>.yaml` 读取
- When：逐套读取 YAML 全文 → BlueprintLoader.load
- Then：装载通过（权威违规全集 12 项，035a2afd 后口径，无模板豁免通过即合法）；type 与 §2.1 表一致；members 非空；steps/depends_on 闭合
- PASS：八套全绿且断言逐套独立；FAIL：任一套装载失败或结构不符；INCONCLUSIVE：制品非钉子构建（指纹不符）
- 配置生效/下游触达证据：不适用（无配置项，L2 §6）
- 调用增量与唯一数据：纯读资产，无共享状态
- 不应断言：模板指令文案质量（DES-3/§5.2）
- 自动化：`@Feature("FEAT-068: 团队模板库")` `@Story("068-S01: 八套逐套装载判据")` `@Tag("feat-068")`

### 068-S02：映射口径判据

- runnable / P0；execution；component；Oracle = Feature §5.1.3 表 + L2 §2.2
- Given/When：同 S1 装载产物
- Then：pipeline 族→PIPELINE（research-pipeline）；链式→PIPELINE（comprehensive-analysis）；图族→DAG（4 套）；委员会+互审→CROSS_VALIDATION（council）；并行族不在静态资产（动态承接）
- PASS/FAIL/INCONCLUSIVE 同 S1 模式；其余字段同 S1（下同，不重复）

### 068-S03：council 结构判据

- runnable / P0；execution；component；Oracle = Feature §2 行2/§4 委员会行 + L2 §2.2
- Then：steps 恰含 p_risk/p_efficiency/p_feasibility/r_risk/r_efficiency/r_feasibility/audit；members 含 risk/efficiency/feasibility/review_risk/review_efficiency/review_feasibility/chief-analyst；review 开启标志为 true
- 不应断言：互审的方向配对细节（归 066 编译语义）

### 068-S04：动态跨库对比契约

- runnable / P0；execution；component；Oracle = Feature §2 行3/§4 + L2 §2.3/§5
- Given：kbIds=["kb-α","kb-β"]（刻意含非 ASCII，验证"只进指令"）
- When：`DynamicTeamTemplates.crossKbComparison(kbIds)`
- Then：members= 2 检索员+comparator，id 恰为 searcher-1/searcher-2/comparator；steps= search-1/search-2/compare；compare.dependsOn = {search-1,search-2}；kbId 原文（含 α β）出现在对应成员指令文本、不出现在任何 id
- 主断言失效拦截：若生成器把 kbId 拼进 id（撞库/非法字符风险），containsExactly(id 集合) 即红

### 068-S05：动态零幻觉契约与单库退化（硬断言）

- runnable / P0；execution；component；Oracle = Feature §2 行3 + L2 §2.3/§5；**单库退化按现行 Feature §2 场景表升格为 MUST 硬断言（2026-11-18 评审）**
- Then：validate.dependsOn = 全部检索步；report.dependsOn 恰为 [validate]；members 含 cross-validator/citation-reporter、不含 comparator
- **单库退化（MUST 硬断言，原观察项升格）**：知识库 id 列表仅 1 项时两种形态均照常生成单路形态、不报错——跨库对比=一路检索+对比汇总；零幻觉变体=一路检索+交叉验证+引用报告
- 成员已具备按 caller 范围收敛的只读工具（FEAT-067 现行 MUST）——检索行为可断言发生；产物质量仍不承诺

### 068-S06：空列表拒绝

- runnable / P0；execution；component；Oracle = Feature §3 + L2 §2.3(:38)
- Then：两形态各传空列表均抛 IllegalArgumentException；null 入参抛 NullPointerException（代码事实 :37，断言按实现，Oracle 注记"L2 未单列 null"）

### 068‑S07（已撤并 → 承接引用）：解析安全与错误面

- **撤并原因（2026-10-19 实现阶段二次修正）**：实现阶段实读 acceptance 仓发现 067 线 `067-S09`（`DaWorkflowTemplateResolutionTest`）已以 9 个参数实例 + 静态序关系证明覆盖同一接缝的同一断言（白名单/穿越/未知名/错误面，**covered**）。
- 承接方：`067-S09` @ `component/orchestration/DaWorkflowTemplateResolutionTest.java`，`@Feature("FEAT-067:…")`，067 台账 REQ-014/015/034 引用。
- REQ-008/REQ-009 的 coverage 行以 handoff 指向 067 台账（case_prefix 067-S09，status covered）。
- 本期不重复建造；若 067 线用例形态变化影响本特性判定，按「新输入改变 Oracle」返回审视。

### 068-S08：双入参全文优先

- runnable / P1；execution；component；Oracle = Feature §4 双入参行 + L2 §2.4
- When：blueprint=最小合法图纸（单成员单步）+ template=research-pipeline 同给
- Then：执行结果对应 blueprint 内容（步骤名可区分），research-pipeline 的 search/verify/write 未出现

### 068‑S09（已撤并 → 承接引用）：清单↔资产精确对账

- **撤并原因（同 S07，2026-10-19）**：067 线 `067-S10`（`DaWorkflowTemplateAssetReconciliationTest`）已断言资产↔工具描述清单**双向完全相等**（硬断言），且 `067-S21` 按 FEAT-068 L2 §1.3 口径对冻结基准做方向分级（缺失=FAIL、新增=WARN）——该方向分级经上一批 §7W.5 处置③ 用户裁决。本用例原设计"多放也红"与该裁决冲突，撤并。
- 承接方：`067-S10`/`067-S21`，067 台账 REQ-016 引用，**covered**。
- REQ-011 的 coverage 行以 handoff 指向 067 台账（case_prefix 067-S10，status covered）。DES-1 的"SIT 侧补位"由此项达成，遗留问题仅剩产品级自动化检测（ISSUE #34 A2）。

### 068-S10：真机 e2e · council 点名全链路

- runnable（凭据已备）/ P0；execution；e2e；Oracle = Feature §4 委员会行
- Given：真实 LLM（ENV-4.1）、Redis（ENV-1.10）、宿主 exec JAR
- When：会话点名 multi-perspective-council + 真实分析问题
- Then：留痕可观测三视角→互审→审计顺序（工具调用事件序列，ENV-3.12 可观测面）；收口产物非空
- 不能证明：产物内容质量（§5.2 OUT）
- 主断言失效拦截：若模板解析/装载/交叉验证调度任一环失效，留痕序列或收口产物断言失败

### 068-S12：会话侧动态通道（@cross-kb-comparison + kbScope）——2026-10-19 二次裁决纳入

- runnable / P1；execution；e2e（真机）；Oracle = Feat-Func-085 :182/:375 + WorkflowRunTool :172-187（post-085）
- Given/When：会话 scope.kbIds=["kb-e2e-a","kb-e2e-b","kb-e2e-c"]，点名 teamName=@cross-kb-comparison
- Then：动态生成照范围执行（任务 completed、收口非空、调用入参含 @ 名）；**无 scope 变体**：空列表受控拒绝（任务达终态、叙述可读，不悬挂）
- 步骤级结构断言（每库一检索员）已随 GAP-068-01 闭环恢复（2026-10-22 r3 起 expectedCrossKbSteps 真执行；r5 复验 634.9s 全绿）

### 068-S13：原版中文团队名映射——2026-10-19 二次裁决纳入

- runnable / P1；execution；e2e（真机）；Oracle = team-names.properties（原版 teamName→随包模板名）
- Then：teamName=研究管道 经映射解析到 research-pipeline 并完成编排（任务级，步骤级同上——已随 GAP-068-01 闭环恢复）

### 068-S11：真机 e2e · parallel-deep-retrieval 点名全链路

- runnable / P1；execution；e2e；Oracle = L2 §7.1 行8 同型（双路标记汇聚汇总员）
- Then：语义/精确两路检索步留痕均出现且汇聚到 summary 步；收口产物非空
- **成员工具判据（2026-10-27 裁决落定，docs#37 / !379 `cbd1ff5d`；原 degraded 挂载就此解除）**：成员与委托执行体的工具面 = **14 件只读工具**（read_file/grep/glob/list_files/notebook_read/kb_search/doc_grep/expand/web_search/wikipedia/wiki_browse/scholar_search/pdf_read/run_sql，按 caller 会话收敛；run_sql 只读性由平台 SELECT/WITH 限制与只读事务保证；无 caller 会话上下文时不挂工具），且与**写工具**（write_file/edit_file/bash/powershell）及**编排六件**（workflow_run/workflow_status/delegate_task/task_output/send_message/subagent_transcript）**交集为空**。Oracle = FEAT-067 §2 两条 MUST/MUST NOT 行＋L2-067 成员工厂段（!379 同步回写）。观测面：e2e＝成员工具实调记录 ⊆ 白名单（SUT 日志成员工具 outcome 面，r5 实测 glob/grep/list_files/read_file 全部落在白名单内）；静态＝`DaWorkflowRunner.MEMBER_TOOLS` 与 L2-067 清单对账。模型口径 temperature/max_tokens 缺省 0.3/4000 可经宿主配置覆盖——L2 明记"可覆盖"，e2e 不作 gating（可观测面待宿主暴露，静态读源码承载）。

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| 无 fixture 代码改动预估 | S01–S06/S08/S10–S11 纯资产/纯函数/错误面，不涉 agent 装配变化；S10/S11 用既有 deepanalyze SUT | 无 | — | —（如实现中发现必须改 fixture，按门禁另行申请） |
| 制品构建（orchestration+engine @ 0.1.3-feat068-r2，源=ce9019d86） | SUT 制品必须绑定不可变 commit | 按 ENV-3.5/3.6 配方，复用已装 core/runtime | m2repo 新增坐标，不覆盖旧坐标 | ✅ 已构建（evidence/20261019-build-feat068/ 指纹 r2 段） |
| acceptance 仓属性切换（orchestration.version/deepanalyze-engine.version → 0.1.3-feat068-r2） | 测试仓指向本批制品 | pom 属性块精确替换 | ⚠️ 并发会话正把 067 线迁往 post-085（r3 于 16:00 重建为新内容）；切版需与其错峰，落仓前复核仓可编译 | 阻塞中（见批次记录） |
| Redis / ~/.llmrc | e2e 前置 | 无改动（ENV-1.10/4.1 已就绪，执行预检复核） | — | 已具备 |

## 8. 自动化落点与精确执行规划

- 仓库/分支：`02-Code/02_ForkCode/agent-runtime-acceptance-main`（main）
- 组件层新目录：`src/test/java/com/huawei/ascend/sit/cases/component/deepanalyze_template/`（命名对齐 registry_discovery 先例）；e2e 归 `cases/e2e/deepanalyze/`（新增文件带 `@Tag("feat-068")`，不触碰 FEAT-054 的 26 例，ENV-5.3）
- 基类：`BaseManagedStackTest`；选择器 `-Dtest='DeepanalyzeTemplate*Test'`（执行期与矩阵 runner 参数在 run-spec 定稿）
- Allure：类级 `@Feature("FEAT-068: 团队模板库")`；方法级 `@Story("068-S*: …")`
- 精确执行命令（初稿，run-spec 固化后为准）：单轨 Maven（run-28 先例，禁双轨），`-Dmaven.repo.local=<工作区 m2repo>` + 两个 `*.source.root` 透传
- 真机 e2e 排期：凭据已解锁，随实现轮一起安排；不因排期改判据

## 9. 状态汇总与退出标准

- 四种状态（执行/用例结论/ISSUE/收口）+ 四维状态（输入成熟度 mixed / 可执行性 runnable / 执行形态 待回填 / 自动化 design-only）：唯一台账 `review-deliverables-feat-068.json`（TestPlan 阶段建立）。
- 退出标准：C1–C6 逐条有承接用例与实际证据（真执行或设计本就要求的 static）；任何 degraded/blocked/未验证项阻塞收口；S10/S11 代表路径真机绿 + XML 审计通过。

## 10. 负结论归因记录

无（设计期无负结论）。

## 对抗性探针设计（铁律 B；跑完即删、不进台账、不算设计场景）

| 风险面 | 探针 | 安全行为断言（失败即发现） |
|---|---|---|
| ①隔离/②并发 | 两会话/两线程并发点名同一模板 | 双方各自完成、留痕互不串话（无共享可变状态泄漏） |
| ③资源边界 | kbIds 1000 项生成；超长 kbId（10KB）；超长模板名（64KB） | 生成完成或受控拒绝，不 OOM/不死循环；标识仍合法 |
| ④敌意输入 | kbId 含引号/换行/YAML 特殊字符/指令注入文本；模板名 URL 编码/unicode 全角/`%2e%2e%2f`；blueprint 传空白串+template 同给 | 注入文本只出现在指令字段；白名单全拦；空白 blueprint 不被当作"已给全文"绕过双入参优先级 |
| ⑤失败注入 | 不适用——生成器为纯函数无外部依赖；资产读取 IO 失败已在 S7 static 覆盖语义 | — |
| ⑥重启恢复 | 不适用——模板资产只读无状态，重启语义归宿主面（065/067 域） | — |
| ⑦运行期权限 | 不适用——模板面无独立权限（随 `deepanalyze.workflow.enabled` 门控归 067 已测装配门控） | — |

归因纪律：探针发现的候选解释→排除依据→仍未排除项记入证据目录 README；不自行改判产品缺陷、不写成 gating 用例。

## 最小可执行例子

**例子 1（资产判据机制，S1–S3/S9 共用）**
输入声明：classpath `workflow-templates/multi-perspective-council.yaml` → 拟发：`BlueprintLoader.load(YAML全文)` → 入口：组件层 JUnit（engine plain JAR + orchestration artifact）→ 可观察：BlueprintSpec(type/members/steps) → 证据：surefire XML + 断言原文。
- 代表性：材料=随仓真实资产（非自制样例）；保留 council 最复杂形态（cross_validation+review）；与客户环境差异=本机 colima/远端 redis，替代办法=无客户现场需求（范围卡）。
- 故障与主断言：若任一资产被改坏（成员空/类型错/依赖悬空），对应 068-S01/S3 断言应红——拦不住即判据失效。

**例子 2（动态生成机制，S4–S6 共用）**
输入声明：kbIds 列表 → 拟发：`DynamicTeamTemplates.crossKbComparison/zeroHallucination(kbIds)` → 入口：组件层直调公开静态工厂 → 可观察：BlueprintSpec 结构 → 证据：surefire XML。
- 代表性：真实使用形态=代码层调用（Feature §1 Agent 开发者角色）；关键差异=N 取 2 与 1 两档。
- 故障与主断言：若 kbId 泄入标识或依赖闭合被破坏，S4 containsExactly/dependsOn 断言应红。

**例子 3（真机点名机制，S10–S11 共用）**
输入声明：模板名+问题 → 拟发：A2A/compat 会话消息（template 入参经 067 工具）→ 入口：宿主 HTTP 会话面 → 可观察：SSE/状态事件留痕+收口产物 → 证据：run manifest + 事件原文。
- 代表性：真实用户链路（预览 §1）；保留 council 互审编排这一最复杂组合。
- 故障与主断言：若解析/装载/调度任一环失效，留痕序列断言应失败。
- 反向挑战（2026-10-19 自审）：假设"用户总传规范模板名" → 条件变化：会话输入是自由文本，大小写/首尾空白/URL 编码变体合理出现（依据：Feature §3 模板名=契约字符串但入参来自模型自由生成）→ 承接：067-S09 行为面（白名单匹配大写但资产不命中 → 可读 unknown 错误，不静默换模板，covered）+ 探针④（编码/全角绕过构造）。未发现现有方案盲区，无新增缺口。
