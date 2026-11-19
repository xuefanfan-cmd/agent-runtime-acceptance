---
title: FEAT-105 测试设计初稿（skill + knowledge）
feature_id: FEAT-105
document_type: test-design
status: draft
automation_status: design-only
feature_source: FEAT-105 @ docs main:11369710, sha256 21b99abc…9da68；FRS-105 sha256 d24dfc4d…9280
l2_source: Feat-Func-105-skill-optimizer.md (9ef922aa…394c) + Feat-Func-105-knowledge-optimizer.md (f1f5d0e8…eb641) + Prompt L2 公共底座章节（待补读，见 LQ-3）
requirement_preview: FEAT-105-需求与测试预览.md
change_scope: 20261117-feat-105-skill-knowledge-变更包范围卡.md
input_maturity: merged
---

# FEAT-105（Skill + Knowledge）测试设计初稿

> 逐用例判定规则、证据要求和自动化落点。**本文件承载于 acceptance 仓 docs/cases/（分支 sit-feat105@34cd87b，github.com/xuefanfan-cmd/agent-runtime-acceptance）**，对应正式 TestPlan 见 docs 仓 develop/04-testplan/（PR !554）；工作区批次版为活动版，三处经 manifest SHA-256 绑定同一输入快照。覆盖台账 manifest：127 行（planned 115 / out 12）。

## 0. 用户必读：需求与测试方案

- 一句话说明：评估器发现 Skill/知识库问题后，本服务承接优化——上传材料、异步优化、产出可审核候选，全程可查询可恢复，绝不自动发布。
- 关键链路：能力发现 → 提交（multipart+幂等键）→ Worker 异步执行（skill：分组分析→聚合→四类 patch→打包；knowledge：萃取→试写→召回→质检→清理）→ 查询阶段与产物 → 人工审核 →（knowledge）显式 import/cleanup/reconcile action。
- 本期测试：公共底座 17 场景 + Skill 8 场景 + Knowledge 14 场景 + 负向裁决落地 1 场景（N02）+ 前端原型联调 1 场景（U01）= **41 条**（2026-11-18 裁决后删除原 N01/N03/N04），证明受理契约、工作流语义、状态机、恢复语义、安全边界与业务结论分离。
- 本期不测：提示词优化（用户裁决）、评估器/自动发布/IAM（设计 OUT）、通知投递/action hide/集合互斥/环境标签拒绝/独立冲突诊断产物（实现不存在，口径待裁决，见偏差清单）。
- 未决影响：8 条设计问题（DQ-1~8）+ 4 条 L2 澄清（LQ-2/5/7/8）；对应场景标 partial/blocked，其余照常推进。
- 详细预览与范围卡（FEAT-105-需求与测试预览.md、20261117-feat-105-skill-knowledge-变更包范围卡.md）存于测试工作区批次目录；对应正式 TestPlan 见 docs 仓 develop/04-testplan/（PR !554）。
- 输入成熟度：merged（可设计/实现/执行，正式收口绑定实际制品指纹）。
- 用户理解确认：`confirmed`（2026-11-17 范围/方向确认 + 2026-11-18 12 项裁决落地；细档随 PR !554 发布为审视快照）。

## 1. 依据、当前口径与范围

- 设计真相源：**FEAT-105 + 三份 L2（docs main@961a6a45）= 裁决后验收依据**；FRS-105 为待对齐材料（!559 进行中），不作冲突裁决依据（docs#55 2026-11-18 裁决）。
- L2 实现说明：与代码 `common@2b3a9b4e` 一致性已核（六点抽查 + 否定主张最新 HEAD 重核）；公共底座契约已补读 Prompt L2（哈希入 manifest）。**DEV-9**：L2-skill 修复循环条款未实现（docs#55 LQ-7），相关断言移除，承接方=开发。
- 已确认裁决：**12 项全部落地（2026-11-18）**，全文见《FEAT-105-稳定裁决记录.md》（docs#55 答复 + PR !554 人工 review）。
- 范围内：范围卡 §2 八块（场景 42 条；N01/N03/N04 已按裁决删除、N02 转负向真执行）。
- 范围外：范围卡 §4 + 裁决出范围 3 行（AC-15/16/29）+ 修复预算断言（LQ-7）+ action hide 子项（DQ-5）。
- 未决问题：**无**（原 12 项全部裁决；blocked 仅环境项 KBENV）。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| agent-ops API 进程（Python/uvicorn） | 被测主体-合同面 | 能力发现、受理、校验、幂等、查询、操作、action 创建、probe | 异步执行 | 全部场景经此入口，黑盒 HTTP |
| agent-ops Worker 进程 | 被测主体-执行面 | 工作流、检查点、清理、action 执行、租约 | 受理校验 | 经任务状态/产物/副作用间接观测 |
| PostgreSQL | 被测依赖（唯一中间件） | 持久化/队列/密文存储 | 向量检索 | 独立实例；断言走 API 面，PG 仅作取证辅助（不直连断言业务结果） |
| 知识库目标（Qdrant，选型 2026-11-17 用户裁决） | 外部副作用面 | 试写/召回/导入/清理核对 | — | **独立取证面**：目标库侧查询不计入 SUT 断言，作 side-effect 证据 |
| 模型服务（火山网关，~/.llmrc） | 真实上游 | 萃取/分析/判定 | — | 不 Mock LLM；LLM 内容差异不影响结构断言（见「确定性桩与真实上游」） |
| 前端原型 web/*.html | UI 联调入口 | U01（本期纳入，按 FEAT §6.2） | — | 不构成契约（FRS DEC-09）；联调旅程独立留痕 |

## 3. 黑盒边界、拓扑与前置条件

- 拓扑：测试侧（HTTP client）→ API（:8091 形态，实际端口以部署为准）→ PG（独立实例）；Worker → PG 轮询；Worker → 模型网关 / 知识库目标（受 `AGENT_OPS_OUTBOUND_RULES` 管控）。
- 前置条件：① Python 3.12 + uv（缺口，需装）；② 测试独立 PG 实例（命名隔离，不动 AKDI 栈 15432/ENV-1.6）；③ schema 迁移单独执行；④ OPTIMIZATION_* 配置组（.env.local）；⑤ 加密密钥（CREDENTIAL_KEYS/KEY_VERSION/FINGERPRINT_KEY）测试部署自生成；⑥ static principal；⑦ 出站规则放行模型网关；⑧ 知识库目标实例（Q-S2 定型前 KB 真执行 blocked）。
- 黑盒红线：不 Mock LLM；不直连 PG/目标库做业务断言（仅取证）；不执行被测仓自带 tests/（开发侧资产）。

## 4. 需求覆盖矩阵

稳定条目目录与唯一覆盖台账：`（待确认后生成 review-deliverables manifest，路径回填）`。

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| 105-P01 | 能力发现：三类回显 | execution |
| 105-P02 | 能力发现：依赖缺失标识不可用 | execution |
| 105-P03 | skill 受理成功 | execution |
| 105-P04 | knowledge 受理成功 | execution |
| 105-P05 | 类型路由与未知类型拒绝 | execution |
| 105-P06 | skill 输入校验负向族 | execution |
| 105-P07 | knowledge 输入校验负向族 | execution |
| 105-P08 | 幂等同键重放与冲突 | execution |
| 105-P09 | 幂等并发重放与隐藏不释放 | execution |
| 105-P10 | 七态生命周期与查询真实性 | execution |
| 105-P11 | 取消族 | execution |
| 105-P12 | 人工重试检查点续做 | execution |
| 105-P13 | 中断 interrupted | execution |
| 105-P14 | 超时 failed | execution |
| 105-P15 | 终态隐藏 | execution |
| 105-P16 | 凭证不外泄 | execution |
| 105-P17 | 跨类型隔离 404 | execution |
| 105-K01 | skill 正常改稿 changed | execution |
| 105-K02 | skill 无有据修改 unchanged | execution |
| 105-K03 | skill 部分冲突保留 | execution |
| 105-K04 | skill description 白名单 | execution |
| 105-K05 | skill patch 校验失败族 | execution |
| 105-K06 | skill 包完整性 | execution |
| 105-K07 | skill 同名隔离 | execution |
| 105-K08 | review append-only（partial） | execution |
| 105-B01 | target-probe 无副作用 | execution |
| 105-B02 | knowledge 正常闭环（业务结论由数据决定） | execution |
| 105-B03 | 召回口径 | execution |
| 105-B04 | target_recall 边界 | execution |
| 105-B05 | 质检阻断 quality_blocked | execution |
| 105-B06 | 零候选 zero_candidates | execution |
| 105-B07 | 临时清理精确性与 CLEANUP_INCOMPLETE | execution |
| 105-B08 | unknown→reconcile | execution |
| 105-B09 | ownership_conflict | execution |
| 105-B10 | 并发同集合无锁不串读 | execution |
| 105-B11 | import action | execution |
| 105-B12 | cleanup action | execution |
| 105-B13 | reconcile action | execution |
| 105-B14 | action 语义 | execution |
| 105-N02 | 环境标签不拒绝（负向，裁决落地） | execution |
| 105-U01 | 前端原型联调全旅程 | execution |

<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目

覆盖台账缺口视图：状态页 §6。**2026-11-18 裁决落地后无 partial 挂起场景**（GAP-105-DQ1~8/LQ2/5/7/8/UIMETHOD 全部 closed，口径见《稳定裁决记录》）；blocked 仅 GAP-105-KBENV（Qdrant 容器部署完成即解除，当前唯一可注册 Adapter=qdrant，B.2 LQ-5）。验收前逐项解除或显式延期。

## 5. 端到端场景

主旅程（knowledge，代表形态）：probe 预检 → 提交（QA 6 条、目标指向测试集合、幂等键 K1）→ 202 queued → 轮询 stages 观察六阶段 → succeeded+target_met → 下载 recall/quality/cleanup 报告 → 目标库独立查询确认临时记录已清 → review 记录结论 → import action（二次确认目标摘要）→ 新 ID 组落库 → 目标库独立查询正式记录 → cleanup action 摘要核对。skill 主旅程：提交 ZIP+cases → 分组/聚合/patch 校验 → changed → 下载候选 ZIP 本地重放比对。

## 6. 详细用例

> 形态缩写：验证形态=execution（真执行）；层次=c（contract）/e（e2e）。每条含主断言三态；增强证据标 non-gating。Oracle 来源列 = 条款锚点（FEAT=FEAT-105、SKL=skill L2、KNW=knowledge L2）。

### 公共底座族

**105-P01 能力发现三类回显**
- 可执行性 runnable；形态 execution；层次 c
- Oracle：FR-01/AC-01、FEAT §3 入口表、SKL §6.4、KNW §6.4
- Given 服务已部署且配置就绪。When `GET /api/v1/optimizers`。
- Then：三类能力返回可用性+输入+阶段+产物+限额+任务时限+取消宽限期+可用操作；prompt 存在但本期仅断言其存在性与结构（不深测）。
- PASS：必返字段齐全且值与实际配置一致（限额对照 .env.local）。FAIL：缺字段/固定假值/无时限字段。INCONCLUSIVE：接口不可达或响应 schema 与文档不符超 3 处（登记契约偏差）。
- 不应断言：prompt 的内部行为。落点：contract 测试类。

**105-P02 依赖缺失标识不可用**
- 可执行性 runnable；形态 execution；层次 c
- Oracle：FR-01「依赖未落实时必须标识不可用」、SKL §4.4、KNW §6.4
- Given 构造依赖缺失（如取消模型 profile → 能力不可用；或指明 KB 目标未配 → knowledge unavailable）。
- Then：对应能力 available=false 且返回不可用原因；其余能力不受拖垮。
- PASS：不可用标识+原因+其它能力可用。FAIL：固定成功响应伪装可用。
- 证据：两组响应对照。落点：contract。

**105-P03 skill 受理成功** — Oracle FR-02/03、SKL §2.2；Given 合法 ZIP+cases+config+幂等键；When POST `…/skill/jobs`；Then 202 `{job_id,optimizer_type:"skill",status:"queued",replayed:false,links}`；FAIL：非法 202/无幂等映射；INCONCLUSIVE：响应丢失（转 P08 原键确认）。落点 contract。

**105-P04 knowledge 受理成功** — Oracle FR-03、KNW §2.2 受理前流程；Given 合法 QA+target+options+凭证；Then 202 queued，task detail auth=`configured:true,redacted:true`；probe 结果冻结快照；FAIL：probe 未执行就受理/凭证明文入库（取证：PG 审计脱敏核对，non-gating）。落点 e2e。

**105-P05 类型路由与跨类型隔离** — Oracle FR-02、FEAT §5.1.2；When optimizer_type=foo 提交 / 用 skill job_id 打 knowledge 语义端点；Then 受理前 422 定位类型错误；跨类型 404（不得读写/操作）。PASS/FAIL 同构。落点 contract。

**105-P06 skill 输入校验负向族** — Oracle SKL §2.2 异常表+§3.1；构造：多 ZIP/缺 SKILL.md/目录与 name 不一致 →422 INVALID_SKILL_PACKAGE；ZIP Slip/符号链接/重复成员/加密 →422 UNSAFE_SKILL_ARCHIVE；压缩炸弹超限 →413；frontmatter 非法/重复 YAML key →422 INVALID_SKILL_METADATA 定位行号；cases 缺字段/重复 ID →422 INVALID_SKILL_CASE 附索引。PASS：全部受理前拒绝+错误定位到字段/路径/索引；不静默截断。FAIL：任一场景静默成功或吞错。落点 contract（真实构造文件族）。

**105-P07 knowledge 输入校验负向族** — Oracle KNW §2.2/§2.6 异常表；构造：缺字段/不支持协议 →422 INVALID_KNOWLEDGE_TARGET；loopback/云 metadata/管理端点 →422 TARGET_NOT_ALLOWED；probe 认证失败 →422 TARGET_AUTH_FAILED 且不建 job、secret 不回显；probe 超时 →503；Adapter 能力缺失 →422 UNSAFE_ADAPTER_CAPABILITY；CREDENTIAL_KEYS 未配 →503 CREDENTIAL_ENCRYPTION_UNAVAILABLE；**容量/队列上限注入（OPTIMIZATION_MAX_QUEUED_JOBS=0 等）→429 受理前拒绝（GAP-105-queuefull，对账新增）**。PASS：错误码+定位一致、无半建对象。落点 contract。

**105-P08 幂等重放** — Oracle FR-04/AC-04、FEAT §5.1.1、SKL §2.3、KNW §3.6；skill 同键重放须 ZIP/cases **字节级**相同（重压缩≠相同 →409）；knowledge 指纹含 QA 字节哈希+规范化 config+凭证 HMAC。Then 同键同内容 200 返回原 job（replayed），异内容 409 IDEMPOTENCY_CONFLICT。PASS/FAIL 同构；INCONCLUSIVE：响应丢失后原键查询确认（分别依赖故障 vs 未受理）。落点 contract。

**105-P09 并发重放与隐藏不释放** — Oracle FEAT §5.1.1/FR-09；并发 N 路同键同内容 → 恰一个 job；hide 后同键重放 → 仍返回原 job（不释放幂等），不新建。落点 contract。

**105-P10 七态与查询真实性** — Oracle FR-05/06、AC-05/06、FEAT §2 任务持久化行、FEAT §5.1.5/5.1.7；构造成功/失败/中断/取消四轮任务，轮询详情+stages+artifacts。Then：状态只取七值；succeeded≠业务提升（business_outcome 单独表达）；未生成结果不补零/不拼凑；部分结果不冒充完整；**API 进程重启后 job/材料/产物仍可查（GAP-105-restart，对账新增）**。FAIL：第五种状态值/虚假百分比/结果拼凑/重启后 404。落点 c+e。

**105-P11 取消族** — Oracle FR-07/AC-07、FEAT §5.1.6、SKL §3.5；①queued 取消→cancelled 直达；②running 取消→cancelling→cancelled（宽限期回显对照 P01）；③取消意图先持久化 vs 迟到模型结果→只允许一个终态，迟到结果只补记录；④重复取消 cancelling/cancelled→返回既有事实；⑤已取消任务 retry→拒绝。落点 e。

**105-P12 人工重试检查点续做** — Oracle FR-08/AC-08/AC-21、SKL §3.5 断点表、KNW §3.6；构造 skill 分组中途模型故障 → failed（已完成组持久化）；retry → 新 job 只重算未完成组（对照组：全量任务比对完成组数与内容一致）；原任务终态不重开不覆盖；retry 复用原输入/配置/模型版本（改参须新建）。落点 e。

**105-P13 中断 interrupted** — Oracle FEAT §5.1.7、SKL §3.5「打包中断：重新打包/校验，无外部写入副作用」、KNW §3.6；kill Worker（进程注入）→ 服务恢复后任务 interrupted（非自动重跑）；retry 走 P12；**skill 打包中断变体：retry 完成打包且无 Skill Hub/资产写入（GAP-105-pack，对账新增）**。落点 e（docker/进程操作）。

**105-P14 超时 failed** — Oracle AC-26、KNW §3.6「deadline 到期进入 failed 并尝试清理」；注入长耗时模型/目标 → 超时 failed，失败原因=超时，检查点+已完成范围保留；若先持久化取消则 cancelled 优先。落点 e。

**105-P15 终态隐藏** — Oracle FR-09/AC-09、FEAT §5.1.6；活动态 hide→拒绝；终态 hide→VisibilityResult，数据可查（列表默认不显、按 id 可查）；隐藏不触发任何业务动作。落点 contract。

**105-P16 凭证不外泄** — Oracle FR-18/AC-18、KNW §3.1/§5.1；knowledge 任务带 auth + skill 任务带模型 api_key（profile env 注入法）；核对任务详情/artifact 下载/日志/事件全部脱敏（结构化断言：脱敏标记存在+原值 0 命中，取值只在受控进程内比对）。落点 e+静态。

**105-P17 跨类型隔离** — 同 P05 后半，独立记账（FEAT §5.1.2 表尾行）。落点 contract。

### Skill 族

**105-K01 正常改稿 changed** — Oracle FR-12/AC-12、SKL §3.2–3.4/§7.2；Given 合法 ZIP（含 scripts/references/assets）+cases（建议支持四类 op 的样例）；Then succeeded+candidate_status=changed；artifacts：candidate_zip/full_text/patch/report/validation 齐全；patch 四类 op 各至少一次，按序重放结果与 candidate_full_text 逐字符一致；expected_result_sha256 吻合。主断言：patch 重放=候选全文。FAIL：patch 应用结果≠全文/缺产物/编造提升。证据：本地确定性重放脚本比对（测试侧工具，非被测件）+ **测试侧模型代理录制请求上下文：模型输入只含 SKILL.md 与 cases，无 scripts/references 内容（GAP-105-skillctx，对账新增；non-gating 增强证据）**。落点 e。

**105-K02 无有据修改 unchanged** — Oracle SKL §3.3/§7.2；Given 建议无法支持具体改动的 cases；Then succeeded+unchanged、原包、空 patch、明确原因；不得编造提升。落点 e。

**105-K03 部分冲突保留** — Oracle SKL §3.3/§7.2；Given 同段落互斥建议；Then 冲突部分原文保留+报告含逐来源建议/证据/不采纳理由；无冲突部分正常改；不等待人工（异步完成）。落点 e。

**105-K04 description 白名单与 metadata 保持** — Oracle SKL §3.4-3、§7.1「修改边界」；Given 带证据的 description 改动+多字段 frontmatter（version/author/自定义）；Then metadata diff 允许路径集合=仅 description；name 与目录名一致且原文片段逐字符保持；正文可重组；YAML 非 dump 式重排（注释/格式保持）。主断言：字节级 frontmatter 原文片段比对。**2026-11-18 裁决（DQ-4）：保护范围字段不存在，固定保护=完整门禁判据（原 N04 并入本场景）。**落点 e。

**105-K05 patch 校验失败族** — Oracle SKL §7.3；构造（经模型诱导或语料设计）：定位不唯一/重叠范围/move 到自身/全文与 patch 不一致 → failed+诊断 artifact，无 approved 候选；有界修复 1 次后仍失败即 failed（repair_attempts 对照）。落点 e。

**105-K06 包完整性** — Oracle SKL §3.4-6/§7.1；Then 候选 ZIP 非文档成员路径/内容哈希/属性集合与原包逐一相等（仅 SKILL.md 允许变）；ZIP 重开验证；候选容器压缩形态不要求与原包一致（语义保持即可）。落点 e。

**105-K07 同名隔离** — Oracle SKL §2.3/§7.2；同名 Skill 不同 cases 并发创建 → 多 job 独立、互不覆盖、各自可查。落点 e。

**105-K08 review** — Oracle KNW/SKL §2.4「review 只记结论」+ Prompt L2 §2.5；**2026-11-18 裁决解锁（DQ-7：review API 属合同，计 PASS 门禁）**。Then append-only（多次 PUT 追加不覆盖、If-Match 乐观并发冲突 412 REVIEW_VERSION_CONFLICT）、同键重放幂等、review 后模型调用数/外部写入数=0（增量断言）、批准不完整候选 → 409 ARTIFACT_NOT_REVIEWABLE。落点 contract。

### Knowledge 族（真执行 blocked until Q-S2）

**105-B01 target-probe** — Oracle KNW §2.6；Then 无 job/凭证记录/候选产生（服务端计数+目标库无写入）；返回 reachable/usable/target_fingerprint/capabilities/probed_at，auth 不回显；probe 结果不是通行证（提交时重新校验）。落点 contract。

**105-B02 正常闭环（业务结论由数据决定）** — Oracle FR-14、KNW §3.2–3.4/§7.2；Given 小规模 QA+测试集合+target_recall=0（L2 §3.3：视为立即满足目标，报告须含无效果阈值说明）；Then 六阶段完整、succeeded+business_outcome=target_met（确定性分支）；候选含来源 case IDs（LQ-2 收敛形态）；逐 ID manifest planned→confirmed；临时清理后目标库独立查询=0 残留。主断言：business_outcome 与分母/命中数自洽 + 目标库零残留。非零 target_recall 的真实召回分支归 B04（below_target 边界已覆盖）。落点 e。

**105-B03 召回口径** — Oracle KNW §3.3；构造：①分类标签带全角空格/NFKC 差异 → 归一后完全相等才算命中；②一条 QA top_k 多条命中只计一次；③检索异常记 error 不算 miss、该轮 incomplete 不缩分母；④分母=归入该分类的有效 QA 数、跨轮固定。主断言：recall=命中/固定分母 可由报告逐行复算。落点 e。

**105-B04 target_recall 边界** — Oracle KNW §3.3/§6.2；①recall=0 显式提交 → 立即达标但报告注明无效果阈值；②全部类别达标 → 停止（迭代数<max）；③budget 耗尽 → below_target 正常完成（非失败）。落点 e。

**105-B05 质检阻断** — Oracle KNW §3.4；构造质检阻断项 → business_outcome=quality_blocked 且任务本身 succeeded（业务结论≠技术失败，AC-30 双列对照）；不代表推荐导入。落点 e。

**105-B06 零候选** — Oracle KNW §3.3；模型产出空候选 → zero_candidates、recall=not_evaluated_no_candidates、score=null、分母保留、不执行无范围检索。落点 e。

**105-B07 临时清理精确性与 CLEANUP_INCOMPLETE** — Oracle KNW §3.4/§7.1；①正常清理：仅本 job/run 已确认 ID 被删（目标库逐 ID 核对）；②注入单条删除超时 → 该 ID unknown、其余 deleted，任务 failed/interrupted + cleanup_status=incomplete + error.code=CLEANUP_INCOMPLETE；cancelled+清理不完整也如实显示（不解释为已清理）。主断言：已知范围/待核对范围如实呈现。落点 e。

**105-B08 unknown 与 reconcile** — Oracle KNW §3.2-5/§2.3；注入 upsert 响应丢失 → ID=unknown；不自动重试/不生成新 ID 重写；reconcile action 只读逐 ID 报 found/missing/owner；核对完成前该 ID 的修改被阻止、其余 ID 照常。落点 e（故障注入）。

**105-B09 ownership_conflict** — Oracle KNW §3.2-5/§7.2；构造外部写入者抢占同 ID（异 owner）→ get_exact owner 不匹配 → 不覆盖不删、标 ownership_conflict 需人工处理。落点 e。

**105-B10 并发同集合** — Oracle KNW §3.3/§7.1；**2026-11-18 裁决全量解锁（DQ-6 无锁语义 + LQ-8 判据收敛）**。两 job 同集合并发写+检索 → 检索请求带 filter+limit=top_k（pre-filter 形态，Qdrant PRE_FILTER）；各自只召回自身 run 记录（对方候选不出现在己方命中）、返回数量≤top_k、逐记录归属当前 run 可核对；无互删。**不把"完整 top_k 完整性"作判据**（如需验证完整性另行约定数据集与证据标准，LQ-8）。落点 e。

**105-B11 import action** — Oracle FR-17/AC-17、KNW §2.3/§3.5；Given succeeded job 且候选完整+质检无阻断+临时清理完整；When 显式 import（artifact_id+target_fingerprint+confirmed_record_count）；Then 新 import_record_id 组（不复用临时 ID）、逐条 upsert 结果可查、job/review 终态不变；目标库独立查询=新 ID 在目标集合。FAIL：自动导入/review 触发写入。落点 e。

**105-B12 cleanup action** — Oracle KNW §2.3/§3.5；Then 仅对请求 ID 子集逐项 owner/version 原子条件 exact delete；返回 deleted/missing/不属于/unknown 分类清单；无 collection clear/arbitrary filter 面（负向断言）。落点 e。

**105-B13 reconcile action** — Oracle KNW §2.3；只读、需幂等键、原 action/job 终态不变、最新核对事实以追加事件呈现；未 settled 操作仍返回 unknown。落点 e。

**105-B14 action 语义** — Oracle KNW §2.3/§2.4；**2026-11-18 裁决（DQ-5）拆分**：job hide 部分计门禁（P15 语义同样适用于 job）；action hide **无接口为 FEAT/L2 明示现状**——负向存在性断言（action 无 hide 路由）保留，不作为能力缺失缺陷。同键重放 200 不重复执行、异内容 409；cancel：queued 直取/running 单请求安全边界停/重复取消返回事实/其它终态 409；跨 scope 404；同 ID 相斥操作 409 RECORD_OPERATION_CONFLICT（逐 ID 不锁集合）。落点 c+e。

### 裁决落地族（2026-11-18 docs#55，口径见《稳定裁决记录》）

原裁决联动场景 **N01/N03/N04 删除**（通知无实现 / 无独立诊断产物 / 无保护范围字段——豁免依据=裁决记录，scope_decision 锚点）；**105-N02 转真执行**：负向断言——目标名含 production/test 字样不被拒绝，仍受协议/SSRF/Adapter 能力校验（Oracle=L2-knowledge §1.1 目标规则 + FEAT §2，断言 A10；落点 contract）。B02 冲突断言按 LQ-2 收敛：候选级来源字段 `source_case_ids` + 质检项结构 `rule/severity/message/candidate_id` 可验；不承诺逐条冲突对应 case ID（DQ-3）。受裁决影响的既有场景修订：K04（固定保护=完整门禁判据）、K08（解锁计门禁）、B10（全量解锁+判据收敛）、B14（job hide 保留/action hide 出范围）、P12（checkpoint 恢复保留/修复预算断言移除，DEV-9）——详见各场景块的裁决注记。

**105-U01 前端原型联调**（已裁决纳入；取证手段 2026-11-17 定为方案 A）
- 可执行性 runnable（环境就绪后）；形态 execution；层次 UI 联调（独立记账，不替代 contract/e2e）
- Oracle 来源：FEAT §6.2 联调门禁、Prompt L2 §2.6（mock 残留必须替换为 API 事实）
- Given：SUT（API+Worker+PG）预起、模型网关可用、浏览器打开模块自带 `web/` 三页（已核实：optimization/ 子模块 prompt.js/skill.js/knowledge.js + api.js 直连真实 API，非静态 mock）
- When：按 FEAT §6.2 八步旅程人工操作——能力发现 → 选优化器 → 上传材料 → 提交 → 查询进度 → 查看产物 → 人工审核 →（知识库）发起 import/cleanup
- Then（主断言）：①八步全部走通且不依赖 mock 响应；②页面展示与 API 返回一致（详情页阶段/进度/产物链接抽查）；③**无 mock 残留**（假进度/假 gain/未接线按钮即缺陷）
- 取证三件套：逐步截图 + devtools 全程 HAR 导出（页面 fetch 全量请求/响应留痕）+ 双路对照（UI 提交的任务用 HTTP 层用例 P10/K01/B02 查询断言，页面字段与 API 返回逐项核对）
- PASS：旅程完整 + 三件套证据齐 + 无 mock 残留。FAIL：旅程断点或 mock 残留（登记前端缺口走澄清/ISSUE，对应步骤 partial——卡住本身是联调产出）。INCONCLUSIVE：SUT 未起或页面无法加载（环境缺陷）
- 不应断言：UI 视觉样式/交互细节（原型不构成契约，DEC-09）
- 归因记录：前端缺陷与 SUT 缺陷分开归因（HAR 区分"页面没发请求"vs"服务返回错"）
- 落点：人工走查，浏览器自动化 B 方案按回归需求后补（单独授权）

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| Python 3.12+uv 安装 | SUT 运行前置（README） | 本机装 uv（用户级），uv 管 Python | 不动系统 Python | 待用户授权 |
| 测试独立 PG 实例 | SUT 持久化 | docker 起命名隔离实例（非 15432） | 无 | 待用户授权 |
| 知识库目标实例 | KB 族真执行 | **Qdrant**（选型 2026-11-17 用户裁决）：镜像站拉取+容器起服务 | 无 | 待用户授权部署 |
| SUT 部署脚本 | 拉起 API+Worker | 批次内脚本（.tmp 纪律外落批次目录） | 无 | 待用户授权 |
| LLM 凭据 | 真实模型 | ~/.llmrc 已配（ENV-4.1）；变量映射待核实（agent-ops 读 OPTIMIZATION_MODEL_* 还是透传） | 执行排期 | 测试侧自行核实（LQ-3 同族） |
| skill 测试 ZIP/cases 语料族 | P06/K01–K07 | 测试侧构造（含恶意构造：Slip/炸弹/加密包） | 无 | 测试侧自建 |
| 故障注入手段 | P13/P14/B07/B08 | Worker kill/模型延迟代理/目标库超时注入（测试侧代理，不 Mock 业务） | 无 | 待实现授权时确认 |

## 8. 自动化落点与精确执行规划

- **自动化落点（2026-11-17 用户裁决：方案 B2）**：Java 用例写进 `agent-runtime-acceptance` 测试仓（新开批次 worktree，按 ENV-5.8 隔离纪律 `git worktree add -b sit-feat105`），**SUT（Python API+Worker）由批次部署脚本预起**，测试仓**零改造**：
  - agent-ops 在测试 yml 中以 **remote 模式**声明（SutStack.java:38-41：`url` ⇒ remote，YAML 决定，不拉进程）；
  - 用例形态参照 agent_memory 线先例（Feat102/103/104 三个 Blackbox 直连类 + `HttpClient`），断言经 resolved base-url + `java.net.http.HttpClient`；
  - 证据：Surefire XML + Allure 报告全套现成（符合工作区证据门禁字面要求，无需等价性声明）；
  - patch 重放比对/ZIP 哈希比对等工具方法以 Java 测试工具类落在测试仓本批包内；
  - UI 联调（U01）取证手段（人工走查+截图 / 浏览器自动化）实现期定，证据并入 Allure。
  - 实现授权与 worktree 创建属后续独立授权事项，本设计不预授权。
- 精确执行规划在 TestPlan 阶段按模板展开（run 矩阵、部署脚本入口、remote yml 配置段）。
- 执行形态纪律：本细档全部场景计划形态=execution；无 static-only 条目（安全静态核对仅作 P16 增强证据）。

## 9. 状态汇总与退出标准

- 四态状态唯一数据源：覆盖台账 manifest（确认后建立，本文件只链接不手工维护）。
- 退出标准：范围内全部条目获得 execution 形态实际证据；**12 项裁决已落地（2026-11-18），无 partial 挂起**；blocked（B 族）在 Qdrant 部署就绪后执行（GAP-105-KBENV）；U01 取证=方案 A（人工走查+HAR+截图）。任何未解除项 → 特性收口至多 partial-open。TestPlan 冻结状态：本轮修订经评审确认前不冻结 PASS 门禁（其 §1）。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| （无——本批尚无执行期负结论） | — | — | — | — | — |

## 最小可执行例子

**机制 1：HTTP 合同执行体（P01–P09/P15/P17/K08/B01/B14）**
输入声明：合法 skill ZIP（policy_search 结构）+ cases JSON 6 条 + config（group_size=2、两模型 profile）；拟发请求：POST /api/v1/optimizers/skill/jobs；入口：API 进程；必要资源：PG+模型网关+出站规则；可观察输出：202 JobAccepted；证据落点：批次 evidence/<run>/http-*.log。

**机制 2：Worker 异步工作流（K01–K07/P10–P14/B02–B09）**
同上提交后：轮询 stages + 下载 artifacts；证据：阶段序列快照、patch 重放比对脚本输出、目标库侧独立查询导出（KB）。

**机制 3：故障注入（P11/P13/P14/B07–B10/B12–B14）**
手段：Worker 进程信号、测试侧模型延迟代理、目标库网络延迟注入；证据：注入前后状态序列+注入点日志。

- 代表性：语料取自真实 Skill 结构（含 scripts/references/assets）与真实 QA 格式（KNW §2.2 示例 schema），保留多字段 frontmatter 与多分类样本这两处真实组合；与客户环境的差异（无真实生产库、模型为网关共享额度）记为假设，不影响结构断言。
- 故障与主断言：若实现"patch 应用结果≠候选全文"（K01 目标失效），主断言"逐字符重放比对"必失败；若实现"清理静默丢 ID"（B07 目标失效），主断言"目标库逐 ID 独立查询=0 残留"必失败。
