---
title: FEAT-111 测试设计初稿
feature_id: FEAT-111
document_type: test-design
status: draft
automation_status: design-only
feature_source: docs develop/02-features/FEAT-111-trajectory-reflection-and-cost-tracking.md @fe5ef25f（sha256 9688541e…）
l2_source: docs develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-111-trajectory-reflection-and-cost-tracking.md @66068d0b（sha256 88d17788…）
requirement_preview: ./FEAT-109-110-111-需求与测试预览.md
change_scope: ./FEAT-109-110-111-变更包范围卡.md
input_maturity: mixed
updated: 2026-11-16
---

# FEAT-111 轨迹反思与成本追踪测试设计初稿

## 0. 用户必读：需求与测试方案

- 一句话说明：任务失败后系统异步做一次受控"复盘"（模型只能从 9 个预定义教训编码里选，写入固定文本），同时按真实用量和价目表把 token/成本输出成标准观测指标。
- 关键链路：任务最终 failed → TerminalFact 分类（六类失败）→ 反思调度（反思仅受宿主开关 `reflection.enabled` 控制；记忆写入另受「显式单租户声明 + default-user 身份」双门）→ 模型选编码 → 固定文本写 agent_memory → da_reflection_result 唯一处置记录；并行地，全执行体 usage → 判定（缺失/非法/未知价/计价）→ OTel 指标。
- 本期测试：①触发面精确性（六类失败 vs 成功/取消/恢复成功/待答）；②至多一次；③lessonCode 受控；④记忆写入双门矩阵；⑤有界与主链隔离；⑥usage 四态判定与缓存公式；⑦指标容量/回收/溢出；⑧金丝雀。
- 本期不测：成功任务反思、持久化队列、多币种账单、缓存写入分项、DFX-001 策略修改（OUT）。
- 未决影响（2026-11-17 复审裁决落定，PR !514）：**OTLP 最终增量 L1 不降级**——正常 Collector 可用时回收前最后一笔有效增量不得静默丢失；F111-16 增加受控接收端硬断言（构造回收前最后一次增量、确认接收端实际收到后方允许序列消失），实现/设计未具备前该子面标 **blocked**（不写成 runnable 不在断言）。**F111-11/13 阻塞原因更正**：core !508 已合 develop（响应 model 与流式 cached-input 源码供给落地）——等待项改为「含修复的制品及 DA 联调验证」，blocked 不变。DV-111-07 撤销（!1181 后三路径统一 request_fallback——r3 已实证成员序列带 fallback 标签，TeamAttributionIT 判据已同步）。原 DV-111-01/02/03 撤销（settings 门随 !1181 移除、L1 `fe5ef25f` 明文）；DV-111-04/05 维持；DV-111-06 改口径「上游字段级供给未就绪分区」。
- 详细预览：[需求预览](./FEAT-109-110-111-需求与测试预览.md) §1C；范围卡 §5。
- 输入成熟度：mixed；正式收口以正式制品为准。
- 用户理解确认：`confirmed`（2026-11-16 用户确认评审修订版；此前 pending）

## 1. 依据、当前口径与范围

- 设计真相源：L1 优先；L2 为实现合同。**门控矩阵按 L1 双门设计**（DV-111-01/02 已撤销：settings 门随 !1181 移除、L1 明文双门；skipped/disabled 分派按 L1 现行）。
- 已确认裁决：暂无。
- 范围内：L1 §2 能力表 21 行（MUST 16 + OUT 5）+ 出口 16 项 + §3 接口（agent_memory 写面、da_reflection_result、OTel 六指标、价目公式）。
- 范围外：范围卡 §4。
- 未决问题：偏差清单 §2.3；L2-04/05。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| deepanalyze（DA 宿主） | 被测 SUT | 反思调度/处置记录/agent_memory 写/usage 采样/指标导出 | 多租户记忆改造（OUT） | L1 module=deepanalyze-java |
| 工具平台 /v1/tools/agent_memory | 协作被调方 | 接收固定文本写入 | — | 既有端点（FEAT-069 写面）；SIT 环境由 SutStack/栈提供 |
| 观测后端（受控 OTLP 接收端） | 测试装置 | 接收六指标 | — | L2 §3.7 OTLP；先例 DaCostOtlpExportChannelTest |
| 反思模型 | 真模型 | 从允许列表选择编码 | 固定选择断言（L1 明文不断言） | L1 页首注记 |
| 计价线执行体 | 待定（Q2） | 确定性执行体（结构/数值断言）候选 | — | 待用户确认双轨 |

## 3. 黑盒边界、拓扑与前置条件

- 拓扑同前；新增：受控 OTLP HTTP 接收端（记录指标名/值/属性）。~~settings 存储可达~~ 已移除（settings 门随 !1181 从实现移除，DV-111-01 撤销）。
- SUT 配置（L2 §6 实际键）：reflection.enabled / single-tenant-declared / timeout / max-concurrency / queue-capacity / max-diagnostic-input-chars / max-lesson-chars；cost.metric-* 四参数 + prices。~~self-evolution-enabled~~ 已随 settings 门移除。
- 触发构造（六类失败，L2 §7.1 分类）：BUSINESS_FAILURE（工具/业务失败终态）、MODEL_SERVICE_FAILURE、CREDENTIAL_OR_QUOTA_FAILURE、INFRASTRUCTURE_FAILURE、INPUT_REJECTED、GUARDRAIL_DENIED——各配最小构造；非失败对照：成功/用户取消/待答/恢复成功/准入拒绝。
- 观察面：da_reflection_result 日志（载体待 B-05/L2-04 核实：logger 名+格式）、agent_memory 行（HTTP 应答）、OTLP 指标（接收端 dump）、SUT 启动日志（配置失败负向）。
- LLM：反思线真模型（设计明文）；计价线受控 usage 待 Q2 确认（建议：确定性执行体承接出口 10 精确数值 + 真实上游承接 usage 真实性）。

## 4. 需求覆盖矩阵

<!-- coverage:scenarios:start -->
| 用例 ID | 场景（对齐出口） | 计划验证方式 | Oracle 口径 |
|---|---|---|---|
| F111-01 | 出口1 失败反思成功：双门满足（reflection.enabled 开 + 显式单租户声明 + default-user 身份）→ 主任务先原 failed 终态；唯一 succeeded 处置带 taskId/sessionId/memoryId；memoryId 对应非空 lesson_learned 固定文本；自由文本未入库 | e2e | L1 出口1/§3.1；L2 §7.2 |
| F111-02 | 出口2 触发排除面：成功/待答/工具中断后恢复成功/用户取消/准入拒绝 → 反思模型零调用、记忆行数不增、无处置记录（或按 L2：非六类失败"不动作"） | e2e | L1 出口2/§5.1.1；L2 §3.1 六类失败白名单 |
| F111-03 | 出口3 至多一次：按 provider 请求计数≤1；反思失败不重试不递归；唯一 outcome=failed 处置含身份字段 | e2e | L1 出口3/§5.1.4；L2 §1.2（CAS）；maxRetries=0 |
| F111-04 | 出口4 有界与隔离：反思模型/agent_memory 挂起 → 排队+调用+写入在 timeout 内释放并留唯一 timeout 处置；主任务原状态/错误码不变 | e2e | L1 出口4/§5.1.6；L2 §3.2（~~DV-111-03 settings 挂起面~~ 已随 settings 门移除撤销） |
| F111-05 | 出口5 资源边界：并发失败超队列 → 全部主任务正常收尾+超限唯一 dropped；enabled=false → 唯一 disabled 零 IO；输入超 max-diagnostic-input-chars → 不发请求不写记忆唯一 failed；输出超 max-lesson-chars 同 failed；非法配置启动失败指出键名 | e2e+启动验证 | L1 出口5/§5.1.9；L2 §6.2（~~DV-111-01/02 disabled 细分两 reason~~ 已随 settings 门移除撤销，唯一 disabled 态） |
| F111-06 | 出口6 金丝雀：原文/工具结果/配置埋敏感语料 → 反思输入只含结构化字段（instruction/lessonCodes/errorFacts 五字段）；新增记忆仅允许列表固定文本；处置记录与指标无金丝雀（taskId/sessionId 除外） | e2e+日志取证 | L1 出口6/§5.1.3；L2 §3.2 诊断输入字段清单 |
| F111-07 | 出口7 同码两任务复用 memoryId，各 taskId 独立 succeeded 记录；不用全局 list 前 50/物理行数替代 | e2e | L1 出口7/§5.1.3 |
| F111-08 | 出口8 记忆写入双门三态：未声明+default-user / 缺失空白身份无声明 / 非 default-user → 唯一 skipped 不读写全局记忆；reflection.enabled 控制反思本体；声明+default-user 才写（L1 §2.5.2 现行） | e2e | L1 出口8/§5.1.2 双门矩阵（DV-111-02 撤销） |
| F111-09 | 出口9 全执行体归属：主/成员/团队/并行委托/技能子 Agent/反思 usage 全归原 mainTaskId/sessionId+purpose 正确；主任务 done 后迟到委托不改挂下一任务；按任务求和=各执行体之和 | e2e | L1 出口9/§5.1.5；L2 §3.4 归属矩阵；**DV-111-04**：无锚静默不采样（无 missing/overflow 信号）——负向面按"无指标"断言 |
| F111-10 | 出口10 缓存公式精确值：input=1000/cached=200/output=500 × 价目 1/0.25/2 → 0.00185 USD；两任务/两会话聚合 0.00370/0.00740；token 分项精确、total 不重复计数 | integration（受控 usage） | L1 出口10/§3.3；**Q2 待确认**：执行体形态（确定性执行体骨架保留）；**Q4 待前置**：cached_input 流式来源未闭环（L2 §1.3.5）——该分栏标待前置 |
| F111-11 | 出口11 多模型计价：三路径统一按请求模型计价并标 `request_fallback`；真实响应模型归因待 core #277 上游供给（blocked 不冒充）；FEAT-113 切换不串价（快照解析，L2 §3.3） | integration | L1 出口11；DV-111-07 撤销（!1181 统一 request_fallback） |
| F111-12 | 出口12 判定顺序（按来源拆分）：主路径受控注入验 missing/invalid/unknown_price 四态不混淆、字段在场的真实 0 不误报 missing；成员/反思字段级供给未就绪分区单独记录（压 0 只记录不作通过依据） | integration | L1 出口12/§3.3；L2 §3.5；DV-111-06 改口径（上游字段级供给未就绪，待上游后四态开跑） |
| F111-13 | 出口13 流式缓存计价 → **blocked·待前置**（#277 流式 cached-input 未交付；L2 §7.2 明示不得把 cache=0 当通过） | — | L1 出口13；恢复条件=#277 |
| F111-14 | 出口14 十进制与容差：声明范围内微额与累计按 max(1e-12, abs·1e-9)；不出现单调用舍入为 0；范围外不承诺 | integration | L1 出口14/§3.4；L2 §5.4（BigDecimal、profile=SHA-256 前 16hex） |
| F111-15 | 出口15 采样率故障面：trace sample rate=0 及各档配置 → 成本指标按配置采样、任务不被拖垮 | e2e | L1 出口15/§5.1.5；L2 §3.7（两层调度） |
| F111-15b | 评审补充：Collector 故障面——OTLP 接收端关闭/不可达 → 采集失败不影响业务、任务照常完成 | e2e | L1 出口15；两故障面独立断言（评审要求，防互相遮蔽） |
| F111-16 | 出口16 指标基数有界：大量不同 taskId/sessionId → 序列/内存保持上界；容量满丢新增量、有界 overflow 计数不混任务；过期回收（idle 10m）生效；回收后同键迟到重开 WARN | integration（受控高频写入） | L1 出口16/§5.1.5/§5.1.7；L2 §3.6；**DV-111-05**：overflow 实例级 Resource 身份未建立 → D3 归因半句标 blocked；**meter 最终增量送达确认单列待决设计项**（评审三），不在本期断言 |
| F111-18 | DV-111-08/09/10 留档面：currency 不参与计算、reconcile_diff 不生成（书面说明）、指标 unit 不设 | 观察 | 留档 non-gating |
| F111-19 | L1 §5.1.4 负向钉：进程退出前未执行的反思允许丢弃、不补跑（重启后无补跑、无跨重启 exactly-once） | e2e | L1 §5.1.4/OUT「持久化反思队列」；依赖 SUT 重启基建（与 F110-15 同源，B-04）——对账表缺口 #6 |
| F111-20 | 六类失败正向触发补全：INPUT_REJECTED（准入拒绝失败终态）与 GUARDRAIL_DENIED（护栏拒绝后任务失败终态，经 109 联动构造）各自触发反思且排除面为零调用 | e2e | L1 §2 失败触发行 + L2 §7.1「INPUT_REJECTED/GUARDRAIL_DENIED 调度…需补」——对账表缺口 #5；GUARDRAIL_DENIED 为 109×111 跨特性交界用例 |
<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目

- F111-13 blocked（#277）；F111-09(无锚负向)/12 字段级分区 partial；F111-16 D3 归因半句 blocked + meter 送达确认待决。
- ~~F111-04 settings 挂起面 partial~~ 已撤销（settings 门移除）；~~F111-17~~ 已删除（DV-111-01 撤销）。
- **2026-11-09 裁决落定**：F111-10 双轨批准（确定性执行体验精确数值 + 真实上游验真实性）→ 骨架转 runnable。
- 范围卡 §4.4 唯一未承接项（workflow/team/delegate/skill 的 mainTaskId 传递矩阵细节）→ F111-09 以黑盒"归原任务"断言承接主面，内部传递矩阵不承接（等 L2 补）。

## 5. 端到端场景

旅程一（失败反思）：双门满足任务 → 构造业务失败终态 → 主任务先返回 failed → da_reflection_result 出现唯一 succeeded+memoryId → agent_memory 新增固定文本 → OTLP 出现 purpose=reflection 的 usage。
旅程二（成本观测）：同一会话多执行体任务 → OTLP 接收端按 task/session 聚合校验（purpose 拆分 main/subagent/reflection）→ 构造一次缺价调用 → unknown_price 计数+1 且无伪造成本。
旅程三（有界）：reflection.timeout 缩短 + 模型挂起（不可达端点）→ deadline 释放 + outcome=timeout + 主任务不受影响。

## 6. 详细用例（关键判定规则）

### F111-01 失败反思成功

- 可执行性/优先级：runnable / P0
- Oracle 来源：L1 出口 1 + §3.1；L2 §2.1/§2.2/§7.2
- Given：reflection.enabled=true、显式单租户声明、身份 default-user（L1 双门满足）；受控失败构造（工具必败路径）
- When：任务执行至最终 failed
- Then：①主任务 SSE 先收到原 failed 终态（不等待反思）；②da_reflection_result 唯一一条 outcome=succeeded、memoryId 非空、duration 合理；③agent_memory 新增行 content=lessonCode 对应固定文本（9 选 1，允许断言"属于允许列表"但不断言具体编码——L1 明文）；④诊断输入金丝雀零命中
- INCONCLUSIVE：模型输出自由文本/未知码 → 宿主应 failed（lesson_uncontrolled）——此时 outcome=failed 属**实现正确行为**，不是产品缺陷也不是 INCONCLUSIVE；判 INCONCLUSIVE 仅当 provider 请求本身未发生且无法归因
- 自动化：`Feat111ReflectionE2EIT` / @Story("reflection.success: 失败后唯一受控复盘")

### F111-10 缓存计价精确值（受控 usage）

- Oracle 来源：L1 出口 10 + §3.3 公式；L2 §3.5/§5.4
- Given：价目配置（1/0.25/2 USD/M）；执行体形态=待 Q2（骨架按确定性执行体设计）
- When：注入 usage（1000/200/500）×2 任务、×2 会话
- Then：OTLP token 计数 input=2000/cached=400/output=1000（按 task/session 聚合）；cost=0.00370/0.00740（容差 max(1e-12, abs·1e-9)）；total 无第三重复增量
- partial：cached_input 流式真实来源（Q4）标待前置——确定性来源下公式断言有效，但"真实流式缓存"能力不因此声明通过
- 自动化：`Feat111CostPricingIT` / @Story("cost.pricing.cache-formula")

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| 受控 OTLP HTTP 接收端 | 六指标取证 | 测试进程内 HTTP server（OTLP/HTTP proto 或 JSON） | 无 | 待用户确认（先例 DaCostOtlpExportChannelTest 形态） |
| 失败构造语料（六类） | 触发面 | 真模型+必败工具/不可达端点等 | 无 | 执行期细化 |
| 受控 usage 注入手段 | F111-10/12/14 | 确定性执行体（**2026-11-09 用户已批准双轨**：装配方提供，不属 Mock LLM）+ 真实上游补跑 | 涉及执行体落位（测试侧装配方提供） | 已授权 |
| 批次制品构建 | 全部 | 源码构建（成本模块 agent-core-ext-llm-cost 在 solution 仓内） | 隔离 m2repo | 执行阶段 |

## 8. 自动化落点与精确执行规划

- 测试仓/worktree/命令形态同前；新类 `cases/e2e/deepanalyze_reflection/`、`cases/integration/deepanalyze_cost/`。
- @Tag("feat-111")；指标断言以 OTLP 接收端 dump 为证据（不依赖 SUT 内部注册表——黑盒）。
- 日志取证：da_reflection_result 的检索方式（logger 名/格式）执行前按 B-05/L2-04 核实并固化到证据脚本。

## 9. 状态汇总与退出标准

- 可执行性：runnable 主体；partial=F111-10(形态待定)/12(上游分区)；blocked=F111-13、F111-16 D3 半句 + meter 送达确认待决。
- 退出标准：21 能力行 + 16 出口逐条承接；settings 分支已移除、门控按 L1 双门；#277 前置项维持待前置不洗白；meter 最终增量送达确认单列待决设计项。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| （设计期无负结论） | — | — | — | — | — |

## 最小可执行例子

- 机制 A（反思链）：输入声明=必败工具任务 → 可观察=da_reflection_result+agent_memory 行+主任务终态 → 证据=SSE dump+日志检索+HTTP 应答。
  故障与主断言：若主任务被反思阻塞（终态延迟）或出现第二条 provider 请求，F111-01/03 主断言应失败。
- 机制 B（计价链）：输入声明=受控 usage+价目表 → 可观察=OTLP 指标名/值/属性 → 证据=接收端 dump。
  故障与主断言：若缺价调用产生 0 USD 而非 unknown_price，F111-12 主断言应失败。
