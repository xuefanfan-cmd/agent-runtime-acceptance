---
feature_id: FEAT-112
feature_title: 任务异常率降级门
since_solution_version: "0.1.4"
case_revision: "draft-4"
status: design
updated: 2026-11-17
tags: [feat-112, degradation-gate, deepanalyze]
depends_on: [FEAT-054, FEAT-109, FEAT-110, FEAT-111, FEAT-001, FEAT-008, FEAT-064]
---

# FEAT-112 cases：如何触发、取证与判定

本文件承接 testplan §5 的全部 **85 个场景 ID**（2026-11-26 修订版，docs !654：新增 G13、撤销 E11，A1/A2/E17/E18 改为可承接）。不是新的需求，不把实现现状变成验收标准。当前为完整设计，代码和实跑结果另在第 8 节登记；写出判据不等于判据自测通过，更不等于真实 demo 验收通过。

## 1. 基线、范围与端到端关系

本次设计基线与 testplan §3 一致；以下设计快照固定于所列提交，不代表可变缓存工作树：
- 特性档（docs 仓 `develop/02-features/FEAT-112-task-degradation-gate.md` @ `a166678c`，与上一基线 `05af9cc3` 相比只有出口 16 凭据口径；核对一律回 docs 仓看该提交）：§2 验收出口、§3.1 准入、§3.2 健康面、§3.3 配置面、§5 状态/窗口机制。用例判据的条款出处统一记为 `feature.md@a166678c`。
- L2 设计（docs 仓 `develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-112-task-degradation-gate.md` @ `5497094b`；终态分类规格 `agent-runtime-ext/FEAT-112-task-terminal-classification-spec.md` @ `93ccf48e`）：观测形式、实现证据及明确列出的需求差距，不用于覆盖需求。
- testplan：测试仓不存副本。同一版本周期内，用例一律对应 docs main 上最新的 testplan：运行前由 `scripts/fetch-expectations.sh` 取到 `target/expectations/`，实际取到的提交记在同目录 `PROVENANCE.txt`，`Feat112Coverage` 读取；docs main 上 testplan 一变而用例未跟进时，`Feat112ArchitectureTest` 的 ID 对账即失败。版本发布后才按版本归档 testplan 提交，复现该版本结论时以 `FEAT112_TESTPLAN_COMMIT` 指定。按特性档修订的三轮 testplan（§5 共 84 行）经 [docs PR 549](https://gitcode.com/Technical-AF/docs/merge_requests/549) 合入；2026-11-25 按 agent-solution #852、#856 裁决的增量（G5-missing 收窄为交付默认、新增 G12、G5-invalid 明确 401、C4 按迁移事件断言代际、E15 激活证据与判定分离）经 [docs !628](https://gitcode.com/Technical-AF/docs/merge_requests/628) 合入，§5 共 85 行；2026-11-26 测试侧按模板收口的修订（基线重定、补条款引用、新增 G13、撤销 E11，A1/A2/E17/E18 改为可承接）经 [docs !654](https://gitcode.com/Technical-AF/docs/merge_requests/654) 合入，§5 仍为 85 行。发布测试报告时记 `PROVENANCE.txt` 中的提交，不以可变文件路径代替版本基线。
- 多维比对（不作预期来源）：DA L2（docs PR 538 刷新中）、ext L2 分类规格（docs PR 463）、代码。与特性档不一致之处逐条登记到差异清单与 PR 评论，不改写本文期望。
- 被测物来源：agent-core-java `develop` 最新（当前 `e24bcb74d`）+ solution `common` 最新（当前 `1127a9ece7b`），由 `scripts/sut/build-carrier.sh` 在干净工作树中构建，构建清单 `feat112-build-manifest.json` 记录两边 commit、制品 sha256 与载体内捆绑 core 的一致性校验；运行时不一致即拒绝。
- demo：solution 仓 `common/example/task-degradation-gate-acceptance-demo/`（测试侧自建，未入库）。2026-11-25 起 demo 不再自带 `application.yml`，载体运行时读 deepanalyze-engine 随包交付的配置（服务间认证默认开启、checkpointer 为 Redis），与 DA 交付形态一致；此前 demo 配置整份遮蔽了 engine 交付配置，载体上认证实为关闭。部署夹具在交付配置之上只叠加部署差异：门参数、模型地址、端口；全部形态注入 `DA_ENGINE_SECRET`、驱动侧携带 `X-Engine-Secret`，不注入认证开关；不托管 Redis 的形态把 checkpointer 设为 `in_memory`。

链路为：**特性要求 → testplan ID → 本文 case/判据 → 驱动真实 demo → 留证 → 独立判定 → 判据自测 → 执行报告 → D1/D2 反馈**。

完整场景 ID 为 `FEAT-112-` 加下表短 ID。2026-11-17 起按 testplan §5 的编号规则（场景 / 变体 / 测试点）改名：A5.1–A5.3 → A5-zero、A5-gt1、A5-nan；D1a、D1b → D1-run、D1-stream；E4.1 → E4-formats；E5.1–E5.6 → E13–E18；F-reg → F5；G5a、G5b → G5-missing、G5-invalid；G5c → G11；K-rep1–K-rep3 → K18–K20。§8.2、§8.3 的历史登记保留当时的写法。沿用 D1-run/D1-stream、B1.1 等已有写法，不重新编号。原先移交开发 UT 的 A3/A4/A5-zero/A5-gt1/A5-nan/A6、B6 与 E10 已恢复为测试仓场景（开发仓用例不能替代测试仓独立验收）；旧 F/K 附录行不重新作为现行 case。原草稿严格白名单 J09 撤回，不复用其含义。

### 1.1 必须先纠正的基线差异

| 项目 | cases 采用的边界 | 对 testplan 的反馈 |
|---|---|---|
| B7/E9 过期事实 | 特性档 §5.1.4 已正式定义过期豁免：不记样本、不改代际/最近故障时间/门状态；当前探针只释放名额，保持 HALF_OPEN | PR 498 已解决契约分歧；本地 testplan 已改引新条款。实现及执行缺口仍独立保留 |
| C2/E1.1 | 观察真实任务受理、探针占用及竞争拒收；不要求 HTTP 返回内部 claimId | 删除公开响应含 claimId 的假设 |
| C7 | 分三段验：样本在 OPEN 期间过期完毕时严格保持 OPEN、样本数 0；静默期满转 HALF_OPEN；之后无流量保持 HALF_OPEN、无探针、无模型调用 | 不把恒 OPEN 写成全部时间下的预期，也不只验“最终不是 CLOSED” |
| E3/E4/E5 | 健康探针关闭门后窗口清空；不能要求关门后仍保留分母增量 | 记账分类与关门清窗口分开验 |
| 依赖就绪 | 001/008/064 的 testplan 文件存在，054/109/110/111 在该目录未找到 | 缺文档、缺用例、产品能力缺失分别登记，不统一写 upstream_not_ready |
| F2/J3 | 以当前 DA 请求 DTO 和控制路由构造请求；不假定 DA /run 使用 A2A method/SubscribeToTask 报文 | 核对入口绑定，不能套错误协议 |
| 覆盖引用 | §5 的现行 ID 为准，附录遗留 E10、未拆 E1、G1–G8 不是新增场景 | 回写引用修正清单，不静默恢复旧 ID |
| E3 前置 | DA 框架权限资产 `defaults.* = ask` 且未放行 `business_failure`，任务先停在待确认；按 E6 语义探针会在待确认时关门 | 出厂配置下 E3 描述的路径不可达，testplan 前置已写明，待开发答复；E3 部分承接。G8 改用“被拒工具参数 + 带哨兵的原始 500 错误”取证，不再依赖 `business_failure` |
| E4 激活 | 须返回真实提供方格式的上下文超长错误（`error.code=context_length_exceeded`），由 Core 分类为 181012；stub 的 `ENGINE_CONTEXT_TOO_LONG` 快捷错误不含提供方关键字，激活不了 | 场景配方写明错误体格式，不能只写“返回 ENGINE_CONTEXT_TOO_LONG” |
| E5 拆分 | 特性档 §6“曾出现 denied 不得改写分类”与 §5.1.3 第 248/258 行，拆为 E13–E18：主链被拒后完成/系统异常/原因不明失败，成员被拒耗尽后主任务完成/无关失败，护栏拦截导致任务失败。主链拒绝用 `write_file` 写工作区外路径（DA 预检拒绝，不执行破坏操作） | E13/E14/E16 公开面承接；E15 公开面造不出“无错误码、无类目、非 core”的失败，改为 SIT：被拒后停掉 Redis checkpointer 使任务失败（待实现）；E17 同样造不出，E18 的确定路径未建立（solution `56e618ba5` 改为成员因果证据后，成员失败到任务失败的因果仍未建立，见 PR 538 评审），两项登记未承接 |
| G5-missing、G5-invalid、G11、G12 认证 | 特性档 §3.2“纳入既有服务间认证，不像 /ready 一样豁免”、验收出口16“要求合法服务间认证”：交付默认配置下诊断面匿名访问返回 401；显式配置认证时 401 且与任务入口的拒绝一致 | testplan 原按实现写成“认证启用时 401 `{error:"unauthorized"}`”、Fixture 引实现代码——测试侧偏离特性设计，已按特性档改写。#852 裁决交付默认开启认证后，docs !628 把 G5-missing 收窄为交付默认形态、显式配置形态拆为 G12 |
| E4/E5 关门 | 特性档 §5.1.6 健康结果只含正常完成、无系统异常的预期业务失败、awaiting_answer；181012 上下文过长与护栏拦截只规定“计分母、不计分子” | testplan 原把 L2 第 5 条“健康终态范围宽于需求列举”的实现行为写成 MUST——改为只验记账，探针门状态不作期望 |
| F1/F2/F3 既有错误 | §5.1.9“返回既有校验错误”：以门 CLOSED 时同一请求的响应为准做对照，OPEN 与 HALF_OPEN 都验（验收出口18），并验不占探针、不建任务、不进窗口、不调模型 | 删去照抄实现的错误文案、“缺 method 字段”与“均为 4xx”限定（团队通道不可用的既有错误是 503） |
| A10/F5 级别 | 特性档能力表“配置与默认值”“只拦新增负担”均为 MUST | SHOULD → MUST |
| required_capability | FEAT-112 全为 MUST，没有契约可选能力；配置键是场景前置，不是能力声明；要求 demo 对配置键显式表态会消掉 A2（键缺席时的交付默认） | 23 行改为 —，frontmatter `required_capabilities` 清空 |
| B5 触发 | §4 场景表“异常率超标…新任务到达→转OPEN”与 §5.1.2 滑动窗口：残窗达标以新任务到达触发 | 不以诊断查询推动开门 |
| UT 移交撤回 | A3–A6 非法配置启动失败、B6 窗口内乱序终态、E10 待答续跑只记一次都是特性档 MUST；开发仓 UT 不能替代测试仓验收 | 恢复为 §5 场景：A3–A6 公开面（独立进程启动失败并指出完整键名），B6 经时钟回拨构造（待实现），E10 公开面 |
| 新增场景 | 验收出口15/17/18 与 §5.1.2 中未被任何场景承接的要求 | 新增 C8（探针取消后旧任务故障回 OPEN）、C9（OPEN 中旧故障重算静默期）、E12（认领后待答触顶按无结论释放）、H4（两实例半开名额互不影响） |
| F5 对照 | §3.1 列出 9 个在途控制面入口；“保持原契约”以门 CLOSED 时同一请求的响应为对照 | 由 5 个入口扩到 9 个，每个入口先在 CLOSED 取基线，OPEN 下比对状态码与错误码，不只验“不含 ENGINE_DEGRADED” |
| 供监控的字段与日志 | 监控规则属部署验收；但提供给监控的字段与日志是被测物输出，SIT 应覆盖 | I1 增验新进程启动记录中的 `instanceId` 与诊断一致；启动记录的正式载体待开发确认（部分承接）。告警保留不在 SIT |
| 组件级验收撤回 | 测试仓只做 SIT：对外契约观测不到时依次取进程日志、持久化数据；都不可观测则提特性不可观测 Issue，不以直接调用产品内部类、测试自造内部事实代替（用户 2026-11-16） | 撤掉 `Feat112MechanismTest`、`Feat112TerminalClassificationTest`。A1、B6、B7、C4、E15、E7、E8、E9、E11 改为 SIT 或登记未承接；不可观测项的简稿本地留档（后随 2026-11-20 处置：3/4 合并为增强提单 openJiuwen/agent-solution#856，5 经开发核查接受关闭） |
| 期望文本漂移 | ID 对账发现不了“ID 不变、期望改了”（C7、E3 都曾如此） | 测试仓不存 testplan 副本，同一版本周期内取 docs main 最新文本；testplan 有修订时随承接用例一起复核，版本发布后按版本归档 testplan 提交 |

### 1.2 测试层级

每个测试方法用 `@Covers(场景 ID, 层级)` 声明承接的场景与验收层；目录按验收层而不是部署方式划分：

- **公开面（PUBLIC_SURFACE）**：`cases/integration/task_degradation_gate/`。测试自己托管真实 DA 进程（模型由 stub 替身驱动，对应仓库 L2 集成层），只经公开请求、公开诊断和进程管理取证；不强制设置门状态，不伪造内部终态。每个类声明一种部署形态（`DeploymentForm`），结论依赖门参数、重启或多进程的场景各自启动部署，不依赖外部环境碰巧的配置。
- **只做 SIT，不设组件级**：对外契约观测不到的要求，依次从进程日志（启动记录、门迁移事件 `event=degradation_gate_transition`、DEBUG 终态分类日志、`da_reflection_result`）、持久化数据（Redis/数据库）取证；构造不出或观测不到的登记为未承接，并提特性不可观测 Issue。需要特殊环境的构造手段写入部署形态：时钟回拨（libfaketime，B6、B7、E9）、PostgreSQL 数据面（F4、F3 的 team 不存在）、Redis checkpointer（E15）。
- **不设 e2e**：仓库 L3 定义禁止 Mock，FEAT-112 的场景都要注入模型故障，没有符合该定义的场景。
- **争议/待能力验证**：有明确 case 设计和解除条件，但不得显示已执行或 PASS；无承接用例的场景登记在 `Feat112Coverage.UNCOVERED`，部分承接登记在 `PARTIAL`。

依赖 FEAT 的分类机制全面验证仍归其所属用例。本 FEAT 的“某种结果能否正确驱动降级门”仍须自己验证，E3/E5/K18–K20 不因上游文档缺失自动禁用，也不能仅因文档存在宣称可以运行。

## 2. 公共执行、证据和判据约定

### 2.1 前置与清理

每个隔离场景记录 demo 构建/依赖版本、测试代码版本、case_revision、文档 commit、实际配置、实例 ID、run ID 与请求/任务关联。solution 0.1.4 是适用基线，不是判据自身版本。受管理进程用于配置变更、多实例和重启；远程连接模式不冒充这些操作已执行。

每组开始用独立实例或已验证的清理方式建立空窗口；只 reset stub 不等于清空降级门。通过合法真实任务、可控模型响应和公开时长配置激活 CLOSED/OPEN/HALF_OPEN。确认重试次数和终态已经落定，不能用“一次模型 500”代替“一次任务系统失败”。

并发/取消场景需要模型调用保持在途且可按请求关联控制；一个未关联的全局 FIFO 不足以精确安排旧任务与探针的完成顺序。计时场景设置余量、记录请求开始/结束及状态采样时间，避免静默期、样本过期和无关并发污染。清理关闭 SSE、终止本测试创建的进程/线程；失败证据先保存，不清理掉原始结果。

### 2.2 证据种类

| 标识 | 证据 | 证明范围与限制 |
|---|---|---|
| H | 请求/响应（方法、路径、状态、头、体、时间、关联 ID） | DA HTTP 与 A2A 分开标协议；A2A 才引用匹配版本官方 schema |
| S | SSE 原始帧、顺序、事件 ID、任务 ID、连接开闭 | 记录 EOF、主动断开、超时及采集不全；不能把超时当正常结束 |
| Q | 同实例健康诊断快照 | `degradationGate` 状态/计数/比例/占用/实例；不含内部故障代际和 claimId |
| T | 公开任务列表、状态及结果 | 关联本场景新请求与在途任务；排除其他运行的任务 |
| M | stub 响应计划、实际调用记录/计数 | 证明模型被怎样驱动；不直接证明所有内部执行副作用 |
| P | 启停记录、PID/启动时刻、实例 ID、配置 | 证明真实进程重启与实例隔离；不伪装成业务恢复 |
| L | 增量日志/告警 | 对外契约观测不到时作为判定证据（观测顺序第二级）；按 DA L2 §3.6 的事件名与键匹配，不匹配整行文案。INFO 可见：启动记录、门迁移 `degradation_gate_transition`（含 `generation=`）、探针相关过期 `degradation_fact_expired`、名额释放 `degradation_probe_released`；DEBUG 须由部署形态声明日志级别。探针认领、句柄释放目前没有日志 |

原始证据必须脱敏认证头、密钥及输入敏感信息，并标记截断/采集失败。派生视图需能指回原始记录。离线判据只消费证据和预定期望，不访问 SUT，不复制降级门实现，也不根据实际响应改变期望。

### 2.3 两个维度描述判据

“检查什么”分为：响应与数据格式、状态与时序、副作用与隔离、运行与诊断行为。“证据在哪里”用 H/S/Q/T/M/P/L 标注。DB、Redis、日志是证据来源，不天然是独立等级的判据；FEAT-112 本轮不为凑分类引入持久化依赖。

公共判据族（均为设计名称，代码落地见第 8 节）：
- **R 拒收**：503；JSON 对象；`code=ENGINE_DEGRADED`；布尔 degraded/retryable；state 与独立前置一致；instanceId 与目标实例一致；error 文案按本版 testplan。OPEN 恢复时间为非负整数、头体同值，HALF_OPEN 占用时头和字段省略。允许合法 charset、JSON 键顺序变化和未被契约禁止的普通扩展字段。
- **W 窗口**：同实例有效样本数、系统失败数及比例；先证明终态/配置/采集有效再比增量，容许约定的数值精度，不容许缺字段默认为 0。
- **X 状态**：已证明前置的前后快照和相关任务终态共同支持迁移；不只验最后一次 state。
- **N 零副作用**：前后 T/Q/M 对照；保持同一窗口时间区间并排除无关任务。无模型调用不能单独证明未创建执行许可。
- **U 唯一探针**：并发请求中一个真实受理且保持在途，其余拒收；由 H/T/M/Q 证明，不读取内部令牌。
- **O 原契约保持**：对明确前置逐入口检查准确响应/错误，不以任意 4xx 或没有 ENGINE_DEGRADED 就替代正确语义。
- **G 诊断/安全**：结构、状态关系、认证与指定敏感测试值的泄露检查；不拿封闭字段白名单代替安全测试。
- **I 隔离/重启**：实例、任务和进程关联；只比较约定的状态，不比较会自然变化的全部响应字节。
- **O 既有契约保持（对照判法）**：同一无效请求分别在门 CLOSED 与 OPEN/HALF_OPEN 下发送，状态码、code、error 必须一致且不是 ENGINE_DEGRADED；期望来自被测物在门之外的既有行为，不抄实现文案。CLOSED 下该请求必须确被校验拒绝，作为激活证据。

## 3. 全量用例设计

每行的依据为“特性档对应契约 + testplan §5 对应同名行”；下文明确列出主要章节/验收出口。实现状态统一是 **设计完成、执行未确认**，不能据表中的“拟落点”推断代码存在。建议类缩写：Config/Window/State/Admission/Probe/Control/Health/Isolation/Transport 分别代表对应 `*DegradationGate*IT`，最终实际类/方法在第 8 节登记。

### A 配置与装配（12）

依据：特性档配置默认与验收出口 19；testplan §5.1。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| A1 | DA 宿主不读随包配置（`--spring.config.location` 只给部署必需项），`enabled` 键缺席、环境未继承覆盖变量；`min-sample-size: 1`，模型地址不可达，连发两个任务 | Q/H → 诊断 `enabled=false`、CLOSED、零计数；两个任务都以系统失败结束，第二个不被 503 `ENGINE_DEGRADED` 拒收 | SIT / `Feat112ProcessConfigurationTest`；按当前实现预计 FAIL：装配层在键缺席时强制开启（`DaCompatAutoConfiguration`，DA L2 §1.4 已列为差距），已提 openJiuwen/agent-solution#1011 请求裁决 |
| A2 | DA 读随包交付配置（engine `application.yml` 显式 `enabled: true`），部署只叠加模型连接、服务间密钥与 in_memory checkpointer，不设 `enabled` | P/Q → enabled=true、CLOSED、零计数 | SIT / `Feat112ProcessConfigurationTest`（`--spring.config.additional-location` 叠加，不替换随包配置） |
| A3 | `window: 0s`，启动独立 DA 进程 | P → 非零退出，输出指出 `openjiuwen.service.degradation-gate.window` | 启动集成 / `Feat112ProcessConfigurationTest`；不静默替换默认值 |
| A4 | `min-sample-size: 0`，启动 | P → 非零退出并指出完整键名 | 同 A3 |
| A5-zero | `failure-rate-threshold: 0` | P → 非零退出并指出完整键名 | 同 A3 |
| A5-gt1 | `failure-rate-threshold: 1.5` | P → 同 A5-zero | 同 A3 |
| A5-nan | `failure-rate-threshold: NaN` | P → 同 A5-zero | 同 A3 |
| A6 | `open-quiet-period: -1s` | P → 非零退出并指出完整键名 | 同 A3 |
| A7 | 空窗口、min-sample=1、阈值满足；一个任务系统失败 | M/T/Q → 终态后计数达标、OPEN | SIT / Config；故障响应覆盖完整重试 |
| A8 | 阈值=1.0；分别全故障及含健康样本的独立运行 | T/Q → 仅全故障且样本数足够时开门 | SIT / Config；混合运行先健康避免提前开门，记录精确分母 |
| A9 | quiet=0，真实任务开门后下一次诊断/准入 | H/Q → 可进入 HALF_OPEN；不要求自动 CLOSED | SIT / Config；不要用上一毫秒快照误断瞬时状态 |
| A10 | enabled=yesnot，启动独立 DA 进程 | P/L → 启动失败、指出配置键 | 启动集成 / Config；不得请求已存活旧实例冒充新进程 |

### B 窗口与分类（10）

依据：特性档 §5.1.2 与窗口验收出口；testplan §5.2。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| B1.1 | 空窗口 min=20，19 个系统失败任务均落定，再发第20条 | M/T/Q/H → 19 后 CLOSED，第20条被受理 | SIT / Window；整条任务不是模型尝试数 |
| B1.2 | 同一隔离序列第20条失败落定 | T/Q → 样本20、失败20、比例1、OPEN | SIT / Window；与 B1.1 可同方法多 ID，但每条独立结果 |
| B1.3 | 已取得 B1.2 达标证据后发第21条 | H/Q → R 拒收且不新增窗口样本 | SIT / Window |
| B2 | min=20，先30个正常再10个失败，均在窗口内 | T/Q → 40/10、比例0.25、CLOSED | SIT / Window；窗口足够长，排除重试成功误归失败 |
| B3 | 先10个正常再10个失败，均在窗口内 | T/Q → 20/10、比例0.5、OPEN | SIT / Window |
| B4 | 已开门且样本在 OPEN 期间全部过期（`window` 短于静默期），期间无新任务 | Q/时间 → 样本数降为 0，状态仍为 OPEN | SIT / `Feat112WindowParametersTest`（与 C7 同一部署、同一时间线） |
| B5 | 先健康样本、后故障样本；初始比例未达标，健康样本先过期 | T/Q/时间 → 残窗同时满足最小样本及阈值时开门 | SIT / Window；明确两批时间区间，窗口内仍须保留足够故障数 |
| B6 | Linux 部署、libfaketime 控制 DA 时钟；CLOSED，先有一条正常样本；回拨 60s（window 5m）后让一个任务系统失败，以该任务 `createdAt` 核对回拨生效 | Q → 样本 2、异常 1、CLOSED（窗口内乱序照常计入） | SIT / `Feat112ClockTest`；已实现，尚未实跑（需 Linux + libfaketime；本机 Windows 记 infra_failure） |
| B7 | 同 B6 的时钟控制，window 60s；OPEN 与 HALF_OPEN（探针在途）各有一个 OPEN 前受理的旧任务在途；回拨 90s（以拒收响应 `Date` 头核对）后立即让旧任务系统失败；HALF_OPEN 组再等实际时间越过窗口下界后让探针正常完成 | Q → 样本、异常与事实到达前相同，状态不迁移；HALF_OPEN 下探针随后正常完成仍能 CLOSED（未递增代际） | SIT / `Feat112ClockTest`（两个方法）；已实现，尚未实跑（需 Linux + libfaketime；本机 Windows 记 infra_failure）。`retryAfterSeconds` 不比较：静默期按原始时钟计算 |
| B8 | 新实例无任务，取诊断 | Q → 计数0、比例为数值0而非 NaN | SIT / Health；缺字段不是0 |

### C 状态机与故障竞争（9）

依据：特性档 §5.1.4、半开及代际契约；testplan §5.3。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| C1 | 独立序列先10健康再10系统失败，min20、threshold0.5 | T/Q → 达标才 CLOSED→OPEN | SIT / State；复用 B3 驱动但保留该 ID 归属 |
| C2 | OPEN 静默期已满、无在途探针；发一条真实新任务并保持在途 | H/T/M/Q → 被受理、探针占用；不得要求响应 claimId | SIT / State；若任务立即完成不能以最终 CLOSED 否认受理 |
| C3 | HALF_OPEN 探针在途、期间无新系统失败；正常完成 | T/Q → CLOSED、清窗口；下一个经 `/run-stream` 的任务被受理并计入新窗口 | SIT / `Feat112StateMachineTest` |
| C4 | HALF_OPEN 探针以真实系统失败结束 | M/T/Q/L/时间 → 回 OPEN、静默期重新开始；拒收响应不暴露探针身份（无 claim/probe 字段）；迁移事件 `event=degradation_gate_transition` 按键匹配：`from=HALF_OPEN to=OPEN reason=system_failure` 那条的 `generation=` 大于其前进入 HALF_OPEN（`reason=quiet_elapsed`）那条 | SIT / `Feat112StateMachineTest`；从场景开始前的日志偏移读起，按 `instanceId` 过滤；迟到探针成功不关门的外部效果由 C5、C8 验证 |
| C5 | CLOSED 期保留旧在途任务，另批任务开门；半开受理新探针；旧任务先失败，新探针后成功 | 关联 M/T/Q → 旧失败后 OPEN，新探针迟到成功不能关门 | SIT / State；需逐任务可控释放，未具备则能力阻塞，不靠全局 sleep 猜顺序 |
| C6 | 半开探针先健康关门，旧任务后失败 | T/Q → 先 CLOSED 清窗，再按新 CLOSED 窗口计故障 | SIT / State；测试 min>1 时单个新故障不应开门，避免混淆 |
| C7 | `window` 短于 `open-quiet-period`；开门后不发任务，经历样本过期、静默期满、之后的空闲期 | Q/M/时间 → 三段：过期期间严格 OPEN 且样本 0；静默期满转 HALF_OPEN；之后保持 HALF_OPEN、无探针、无模型调用、实例不变 | SIT / `Feat112WindowParametersTest`；按特性档验收出口4、§5.1.7 |
| C8 | HALF_OPEN，探针与一个 OPEN 前受理的旧任务都在途；取消探针并等落定，再让旧任务系统失败 | H/T/M/Q → 取消落定后仍 HALF_OPEN；旧任务失败后回 OPEN 并重计静默期 | SIT / `Feat112OrderedControlTest`；验收出口15 |
| C9 | OPEN（静默期 40s），一个 OPEN 前受理的旧任务在途；等待一段后让旧任务系统失败 | Q/时间 → `retryAfterSeconds` 回到接近完整静默期（≥37s），转 HALF_OPEN 相应推迟 | SIT / `Feat112OrderedControlTest`；§5.1.2 |

### D 双入口拒收（6）

依据：特性档 §3.1、验收出口 7/8；testplan §5.4。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| D1-run | 独立 Q 证明 OPEN，提交参数/认证均有效的 /run | H/Q → R 的 OPEN 响应要求 | SIT / Admission；不验证未经约定的额外字段或键顺序 |
| D1-stream | 同样 OPEN，向 /run-stream 请求 SSE | H/S/Q → 先返回503 JSON，不建立200 SSE或伪任务流 | SIT / Admission；不能仅复用 /run 结果 |
| D3 | OPEN 下捕获一次拒收响应，并与紧邻的诊断快照对照 | H/Q → 头 Retry-After 与 body 整数秒数相同，且与快照 `retryAfterSeconds` 相差不超过 1s | SIT / `Feat112AdmissionTest`；精确 87 秒与取整边界由受控时间机制测试 |
| D4 | HALF_OPEN 探针被真实任务占用并保持；另发新任务 | H/T/Q → 503，头和 retryAfterSeconds 字段均不存在 | SIT / Probe；null 不等于省略 |
| D5 | 分别取得 OPEN 与 HALF_OPEN 拒收 | H/Q → state 与前置一致，instanceId 与实际目标实例一致 | SIT / Admission |
| D6 | 独立 OPEN、无并发，在窗口过期前捕获拒收前后证据 | H/T/Q/M → 无新增任务/模型调用/窗口样本；流式路径无 start | SIT / Admission；内部执行许可仍由宿主测试证明，不能由 HTTP503 单独推出 |

### E 探针闭环（19）

依据：特性档半开真实探针、健康/无结论终态；L2 业务终态说明；testplan §5.5。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| E1.1 | HALF_OPEN 无占用，5个合法请求同时发出，获准任务保持在途 | H/T/M/Q → 恰1条受理并有真实执行，probeInFlight=true | SIT / Probe；不依赖 claimId；先完成后受理第二任务不属于并发违规 |
| E1.2 | 同一并发批次 | H/T → 其余4条503 HALF_OPEN，无新增任务 | SIT / Probe；与 E1.1 逐条结果 |
| E2 | HALF_OPEN 正常探针完成 | T/Q → CLOSED、计数清零、名额释放 | SIT / Probe |
| E3 | stub 返回实际注册的 business_failure 工具，arguments 为含 output 字符串的 JSON；真实 rail 生效 | M/T/Q → 专用业务终态、健康关门、清窗 | SIT / `Feat112ProbeTest`；出厂配置下该工具落入默认 ask，探针先停在待确认（部分承接，待开发答复）；空查询结果不等于 business_failure |
| E4 | HALF_OPEN，触发真实结构化上下文超限181012 | T/结构化错误/Q → INPUT_REJECTED 对应健康关门清窗 | SIT / Probe；上游 JSON 写错误名不足以证明映射，须核实 provider/core 路径；激活未证则不报 PASS |
| E4-formats | CLOSED 空窗口；两种提供方格式各一次：`context_length_exceeded` 码配无长度字样的文案、`invalid_parameter_error` 码配输入长度范围文案 | T/Q → 每次样本+1、异常不变，与提供方格式和文案无关 | SIT / `Feat112WindowAccountingTest`；core 按提供方错误码与文案关键字归 181012，DA 按 core 模板识别 |
| E13 | CLOSED 空窗口；模型调用 `write_file` 写工作区外路径被 DA 预检拒绝，随后给出最终回答 | M/T/Q → 任务 completed，样本+1、异常不变；拒绝确已发生（工具结果含 denied、目标文件未写） | SIT / `Feat112WindowAccountingTest` |
| E14 | 同 E13 先被拒，之后的模型调用持续 5xx 直到重试耗尽 | M/T/Q → 任务 failed，样本+1、异常+1；stub 错误响应数增加作激活证据 | SIT / `Feat112WindowAccountingTest` |
| E15 | CLOSED 空窗口，部署使用 Redis checkpointer（测试托管 Redis 容器）；模型先 `write_file` 写工作区外被拒，再 `ask_user` 使任务停在 awaiting_answer（检查点写入 Redis）；停掉 Redis 后 `/answer` 续跑，续跑开始时恢复检查点失败 | T/M/Q → 判定取诊断面：样本+1、异常+1。激活证据与判定独立：公开任务记录的 `error` 表明检查点存储读取失败（`Failed to get key: …agent_state…`）；续跑前后 stub 请求数不变（排除模型调用与 core 错误通道） | SIT / `Feat112CheckpointStoreTest`；不再以 DEBUG 终态行作激活证据（该行不是 `event=` 契约）。不用“任务中途停 Redis”：checkpointer 只在运行开始、中断、结束时访问存储，FEAT-054 用例已观察到中途停 Redis 结局不确定 |
| E16 | 成员工具的确认请求按 fail-closed 拒绝；模型发起成员调用，成员工具尝试全部被拒并以 error 结束；主任务随后给出最终回答 | M/T/Q → 任务 completed，样本+1、异常不变 | SIT / `Feat112MemberGuardrailTest`；交付 actrule 下成员工具全为 allow，部署另加场景级覆盖使 `read_file` 需确认（成员无确认链，按拒绝处理），成员轮次设为 2；激活证据：stub 服务次数、拒绝标记、成员以轮次耗尽结束 |
| E17 | E16 的部署形态加测试托管 Redis checkpointer；模型脚本：主任务 `workflow_run` → 成员两轮 `read_file` 全被拒 → 成员总结轮 → 主任务 `ask_user` 停在待答；停 Redis 后 `/answer` 续跑，恢复检查点失败 | T/M/Q/S → 计分子：样本+1、异常+1。激活证据：成员拒绝标记与轮次耗尽、任务记录 `error` 为检查点读取失败、续跑后 stub 无新请求 | SIT / `Feat112GuardrailOutcomeTest`；需 Docker。若成员因果被任务级合并判为闭合，任务会归 GUARDRAIL_DENIED、只计分母，即违反特性档 §6（终态分类规格 §5 跨链否决作用域待裁） |
| E18 | 部署 `deepanalyze.model.max-iterations: 2`、`deepanalyze.hooks.http-allowed-hosts: 127.0.0.1`；请求 `runtimeConfig.hooks` 带一个 http 型 PreToolUse hook（测试托管 `DenyingHook`，恒返回 `allowed=false`，matcher `write_file`）；模型每轮都要 `write_file` | T/M/Q → 任务 failed；样本+1、异常不变。激活证据：模型轮次 ≤ 上限且每轮的工具调用都到达 hook 被拒（hook 计数＝模型轮次）、目标文件未写出 | SIT / `Feat112GuardrailOutcomeTest`；不用“写工作区外路径”构造：那是权限检查拒绝，主链只在 hook 拒绝处登记因果证据（终态分类规格 §5） |
| E6 | HALF_OPEN 探针触发真实待答 | T/S/Q → CLOSED、名额释放、清窗、不记样本 | SIT / Probe；需要真正待答事件，不是文本说等待 |
| E7 | HALF_OPEN 真实在途探针，发取消，记录请求与最终落定，再发下一任务 | H/T/M/Q → 落定后释放、保持 HALF_OPEN，下一真实任务成为探针 | SIT / `Feat112ProbeTest`；取消请求到达瞬间的窗口在一次同步调用内，不可观测（Issue 4，含“实际落定”口径待确认）（部分承接） |
| E8 | HALF_OPEN、`max-concurrent-tasks=1`；开门前让一个任务停在 awaiting_answer，HALF_OPEN 下经 `/answer` 续跑占住唯一执行线程，再发新任务 | H/Q → 既有 503 `ENGINE_BUSY`（too many running tasks）而非 `ENGINE_DEGRADED`；保持 HALF_OPEN、`probeInFlight=false`、样本不变；续跑结束后下一真实任务成为探针 | SIT / `Feat112OrderedControlTest`；续跑与首轮共用执行池（`submitContinuation`），执行池在认领之后检查 |
| E9 | 同 B6 的时钟控制；HALF_OPEN 当前探针在途，回拨 90s 后让探针正常完成；下一任务成为探针后再以系统异常结束 | Q → 两次都不增样本、保持 HALF_OPEN、释放名额；过期健康结果不关门、过期失败不回 OPEN；中间下一真实任务成为探针 | SIT / `Feat112ClockTest`；已实现，尚未实跑（需 Linux + libfaketime；本机 Windows 记 infra_failure） |
| E10 | HALF_OPEN，探针进入 awaiting_answer 并已关门（E6）；经 `/answer` 回答，续跑中模型持续失败；变体在回答前经 `/inject` 追加消息 | H/T/M/Q → 同一逻辑任务只记一次：样本+1、异常+1；回答与 inject 不各自新增样本，任务列表只有这一个任务 | SIT / `Feat112ProbeTest`；验收出口13 |
| E11 | （已撤销） | — | testplan（docs !654）撤销 E11、替代 E12：入站校验前移到降级门之前（#853）后，“认领后、绑定前”窗口内唯一可稳定构造的拒绝是待答任务数触顶，与 E12 前置、步骤、期望相同。注册之后属主绑定失败的 `prepare_failed` 路径（计分子并回 OPEN）是另一窗口，由开发侧组件测试锁定 |
| E12 | HALF_OPEN；`deepanalyze.compat.max-awaiting-tasks: 1` 且已有一个任务停在 awaiting_answer；发起新任务 | H/Q → 既有 `ENGINE_BUSY` 拒绝而非 `ENGINE_DEGRADED`；保持 HALF_OPEN、`probeInFlight=false`；释放待答名额后下一个真实任务成为探针 | SIT / `Feat112OrderedControlTest`；验收出口18“认领后并发拒绝按无结论释放名额” |

### F 校验顺序与既有控制面（5）

依据：特性档 §3.1 顺序、验收出口18；testplan §5.6。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| F1 | OPEN，认证启用，提交缺失/无效认证的新任务 | H/Q → 原认证错误、不是降级503、不占探针 | SIT / Control；有效token预检与被测无效token分开 |
| F2 | OPEN 与 HALF_OPEN；分别发缺 `sessionId`、`input` 与 `mediaIds` 均空、`runtimeConfig.version` 不受支持的 `/run`、`/run-stream` | H/Q → 与门 CLOSED 时同一请求的既有校验错误相同，不是 ENGINE_DEGRADED；不占探针、不建任务 | SIT / `Feat112ValidationPrecedenceTest`（O 对照判法） |
| F3 | OPEN 与 HALF_OPEN；分别发媒体不存在 / 白名单外 / team 不存在 / team 通道不可用 | H/Q → 每项与 CLOSED 时同一请求的既有错误相同；不占探针、不建任务、不进窗口 | SIT / `Feat112ValidationPrecedenceTest`（无数据面：媒体两项与通道不可用）+ `Feat112DataPlaneTest`（PostgreSQL 数据面：team 不存在，CLOSED 时须为 404）；后者已实现，尚未实跑（需 Docker） |
| F4 | OPEN 与 HALF_OPEN，发带 `X-Da-Tenant-Id: *` 的 `/run`、`/run-stream`；数据面关闭（交付默认）与 PostgreSQL 数据面两种形态各验一次，另验正常租户头不被租户校验拒绝 | H/Q → 保留租户：与 CLOSED 时同一请求的既有校验错误相同（无数据面形态须为 400 `ENGINE_VALIDATION`），不是 `ENGINE_DEGRADED`，不占探针、无副作用；正常租户：CLOSED 下 200 completed 且计 1 个样本，OPEN 下由降级门 503 `ENGINE_DEGRADED` 拒收 | SIT / `Feat112ValidationPrecedenceTest`（数据面关闭）+ `Feat112DataPlaneTest`（PostgreSQL 数据面）；#853 已把租户校验前移到降级门之前 |
| F5 | 先在门 CLOSED 时为 9 个入口取基线，再开门；`/status`、`/task`、`/tasks`、`/stream` 重连、`/cancel`、`/inject`、`/message` 用活跃任务，`/answer`、`/resume` 用 awaiting-input 任务（带 Idempotency-Key） | H/S/T → OPEN 下每个入口的状态码与错误码与 CLOSED 基线相同，且不含 ENGINE_DEGRADED/degraded=true | SIT / `Feat112OrderedControlTest`；控制面自身行为归 FEAT-001/008/110 |

### G 健康诊断（12）

依据：特性档 §3.2、验收出口16；testplan §5.7。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| G1 | CLOSED，带有效服务认证 GET health/detail | H/Q → 200、healthy、CLOSED、无恢复时间 | SIT / Health |
| G2 | 真实 OPEN 后查询 | H/Q → 200、degraded、OPEN、有恢复时间 | SIT / Health |
| G3 | HALF_OPEN 后查询 | H/Q → 200、degraded、HALF_OPEN、无恢复时间 | SIT / Health |
| G4 | enabled=false 独立实例 | H/Q → healthy、enabled=false、CLOSED、样本/异常/比例均为 0、probeInFlight=false | SIT / `Feat112DisabledGateTest`；不代替 A1 默认配置 |
| G5-missing | 交付默认配置：只注入 `DA_ENGINE_SECRET`，不设置 `deepanalyze.auth.enabled`，也不设置或继承 `DEEPANALYZE_AUTH_ENABLED`；诊断请求无 secret | H → 401，不返回诊断内容 | SIT / `Feat112DiagnosticsTest`（standard 形态即交付默认）；前置核对形态未声明认证开关、测试进程环境无该变量 |
| G12 | 显式配置 `deepanalyze.auth.enabled=true` 与密钥；诊断请求无 secret | H → 401，不返回诊断内容；状态码与响应体和 `POST /api/agents/run` 同一匿名请求的拒绝相同 | SIT / `Feat112AuthenticatedTest` |
| G5-invalid | 已配置认证，诊断请求带错误 secret | H → 401，不返回诊断内容；状态码与响应体和 `POST /api/agents/run` 同一错误凭据请求的拒绝相同 | SIT / `Feat112AuthenticatedTest` |
| G13 | 直接启动制品读随包交付配置；不注入 `DA_ENGINE_SECRET`、不设密钥与认证开关，环境未继承覆盖变量；只补模型 key 与 in_memory checkpointer | P → 非零退出，输出指出 `deepanalyze.auth.engine-secret` | SIT / `Feat112ProcessConfigurationTest` |
| G11 | 同实例正确secret | H/Q → 200、完整诊断 | SIT / Health；密钥不落盘 |
| G8 | 模型 API key 为测试替身专用值；用户输入、stub 500 错误体、`write_file`（工作区外路径，被拒）参数各带独立哨兵；持续失败直至 OPEN | H/Q/哨兵 → 整段诊断响应不含任一哨兵、服务 secret 与错误堆栈 | SIT / `Feat112AuthenticatedTest`；不再依赖 business_failure |
| G9 | 同实例其他健康条件固定，比较 CLOSED/OPEN 的 /health | H → 门状态变化不改变约定健康语义 | SIT / Health；不比较动态时间等无关字段 |
| G10 | 同样条件比较 /ready | H → 不因门状态增加down分量 | SIT / Health；其他组件故障另行归因 |

### H 多实例（4）

依据：特性档实例本地性；testplan §5.8。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| H1 | 两个真实进程、独立stub，仅向A发送故障任务 | P/M/Q → A OPEN、B CLOSED且窗口未被污染 | 多进程SIT / Isolation；同JVM两个对象只作机制证据 |
| H2 | A OPEN，向A拒收并查询诊断 | H/Q/P → 两者实例ID一致且属于A | 多进程SIT / Isolation；A是逻辑别名，不强制真实ID字面等于A |
| H3 | B OPEN，推动A恢复CLOSED后重新查B | P/T/Q → B门状态/提示按自身事实维持 | 多进程SIT / Isolation；避开B静默/窗口自然迁移的混淆 |
| H4 | A、B 两个进程均 HALF_OPEN；A 的探针名额被慢任务占住 | P/H/Q → B `probeInFlight=false`，B 的新任务被受理为 B 的探针；A 的新任务 503 `state=HALF_OPEN` | 多进程 SIT / `Feat112InstancesTest`；验收出口17 |

### I 重启（2）

依据：特性档 §5.1.7、验收出口20；testplan §5.9。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| I1 | 旧进程 OPEN，真实停止并启动 | P/Q/L → 新 instanceId、CLOSED、零计数、无探针，记为“新进程初始化”；新进程启动记录（`da degradation gate wired` 行）同一行带 `enabled=true`、`delivery default applied=` 与 `instanceId`，且 `instanceId` 与诊断一致 | 多进程 SIT / `Feat112InstancesTest`；正式载体以 DA L2 合入版本为准（部分承接）；告警保留属部署验收 |
| I2 | 旧实例HALF_OPEN有探针在途时重启 | P/T/Q → 新实例无旧窗口/探针，不恢复旧占用 | 多进程SIT / Isolation；与I1不同独立前置 |

### J 传输与任务记账（4）

依据：特性档任务终态唯一记账和传输不独立记账；testplan §5.10。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| J1 | CLOSED在途任务，收到开始事件即断开观察SSE，任务随后成功 | S/T/Q/M → 同任务后台完成、样本+1失败+0 | SIT / Transport；断开观察不等于取消任务 |
| J2 | 经 `/run-stream` 发起、收到 start 后断开原始流，模型持续失败使任务后台系统失败 | S/T/Q/M → 终态 failed，样本+1、异常+1，只此一个任务 | SIT / `Feat112TransportTest`；不得让重试回默认成功 |
| J3 | 同一在途任务经 `/api/agents/stream/{taskId}` 多次重连并主动断开；任务结束后再重连一次 | S/H/T/Q/M → 无额外样本、无额外模型调用；样本只因任务终态 +1 | SIT / `Feat112TransportTest` |
| J4 | 可控持续事件，客户端缓慢读取，任务正常或失败落定；部署声明 `max-events-per-task=64` 与 `answer-gate.stream-buffering-enabled=false`（交付默认整段回复结束前不下发增量，慢消费条件不成立） | S/T/Q → 分类取决真实终态，慢消费不另记账 | SIT / `Feat112ProcessConfigurationTest`；须证明压力/背压条件真的发生（DA 输出订阅溢出断开记录），不以短流 sleep 冒充慢消费者测试 |

### K 消费分类的代表验证（3）

依据：特性档共享分类与唯一记账点；testplan §5.11。

| ID | 前置与操作 | 证据 → 判据 | 层级 / 拟落点 / 限制 |
|---|---|---|---|
| K18 | CLOSED且足够开门余量；模型5xx持续到重试耗尽 | M/T/Q → 一个任务系统失败、样本+1失败+1 | SIT / Window；分类输入路径已证即可测消费，不要求先运行不存在的上游testplan |
| K19 | CLOSED正常任务完成 | M/T/Q → 样本+1失败+0 | SIT / Window；正常模型响应必须通过实际provider解析 |
| K20 | CLOSED真实任务在途后取消并等落定 | S/T/Q/M → 样本和失败数均不增加 | SIT / Control；不在任务开始前取消造成另一种拒绝分类 |

## 4. 判据自测：测试代码也必须被检查

按规则族构造离线证据：正常样本应通过，单项错误应指出对应规则，缺前置/缺记录不能PASS，合法额外字段/键顺序/charset不误报。状态与计数规则用前后快照、任务结局及声明前置，不只输入一个孤立state。并发规则要有同批次与在途重叠证据。

| 规则族 | 正确证据 | 故意错误 | 合法变化/缺证据 |
|---|---|---|---|
| R | OPEN503与同值头体；HALF_OPEN省略恢复时间 | 错误码、错误类型、缺字段、头体不同、null冒充省略 | 额外requestId/头大小写/charset；缺独立前置不能PASS |
| W/X | 指定终态与前后计数/迁移一致 | 任务重试计多次、负计数、错误比例、健康后未清窗 | 不同合法时间间隔；缺终态/实例不一致不授予通过 |
| N/U | 无新增副作用；真正并发恰一探针 | 拒收创建任务、模型新增调用、多个探针 | 第一探针结束后第二次正常受理不误报；缺重叠证据不能证明唯一性 |
| O/G | 原契约错误、正确认证、诊断字段 | 降级抢先、认证旁路、泄露标记 | 动态无关字段变化不误报 |
| I | 实例隔离、新进程新ID清窗 | 跨实例串样本、重启恢复旧探针 | 无进程证据不能声称已重启 |

此处为自测规格，不表示所有测试已实现。既有 EdpaJudgeSelfTest 的无SUT自测模式可复用思想，不复制其业务规则。

## 5. 结果与失败信息

结果保持 testplan 的六态语义，不另造全仓结果框架。执行阶段另记 `designed / implemented / selftested / executed`，这些不是PASS/FAIL。前置触发失败、采集缺失、产品契约违规和依赖未具备分别保留原因；未触发场景不是自动UNSUPPORTED，缺上游文档也不是自动禁用。

每个失败至少给出：场景/判据ID、需求基线引用、前置证据、期望、实际、证据记录位置、原因分类。初次失败和复跑结果都保留，禁止“重试到绿”覆盖原始失败。判据自测通过只证明判据对样本的行为，不计入真实SUT通过数。

实现（2026-11-12）：用例拿到一个 `Scenario` 记录器，驱动在建立状态时记录前置与激活证据，判据族产出带特性档条款出处的 Finding；用例不直接断言。`AcceptanceExtension` 按六态推导每个场景的结论——前置或激活不成立记 FAIL/conditional_not_triggered（结论无效，修前置），产品违规记 FAIL/must_violation，未记录任何判定或中途异常记 FAIL/infra_failure——并为每次执行写 `target/acceptance-results/<runId>/*.json`（场景结论、全部前置/激活/验收记录、证据路径、被测物坐标与 sha256 及构建来源）。`Feat112Coverage` 把一次运行汇总为逐场景 `summary.md`，没有执行记录的场景显示 NOT_EXECUTED，不会静默缺失。JUnit 状态由结论派生：阻断性结论使测试失败。

## 6. 实施顺序与运行方式

1. 先跑无SUT判据自测、stub协议自检，确认正常/故障/工具调用及流式能被实际provider消费。
2. 打通真实DA启动、Q诊断、合法任务与故障耗尽；落实A/B/C/D/G/K。
3. 增加真实待答、业务失败、权限拒绝、取消、竞争与断流；落实E/F/J。
4. 启两个进程并真实重启；落实H/I，单列监控未覆盖部分。
5. 执行后回查 84 个 ID，按代码与真实证据填第8节；不要用框架骨架填“完成”。

代码目录（2026-11-12 重排）：

- 通用结果层：`src/main/java/.../judge/`（`EvidenceChecks` 六态判据模型、`ScenarioVerdict` 结论推导、`ArtifactIdentity` 被测物身份、`AcceptanceReport` 运行汇总）与 `src/test/java/.../judge/`（`Covers`、`Scenario`、`AcceptanceExtension` 及自测）。
- FEAT-112 夹具 `src/test/java/.../fixtures/task_degradation_gate/`：`Feat112Contract`（唯一的条款出处来源）、`Feat112Coverage`（覆盖登记与报告）、`Feat112ArchitectureTest`（结构规则）；子包 `judge/`（R、G、W/X/N、O 判据族及自测，只消费已采集证据，不依赖驱动与部署）、`deploy/`（部署形态、托管部署、demo stub 进程、隔离进程）、`drive/`（DA 驱动、证据客户端、关联模型替身）。
- 用例：`cases/integration/task_degradation_gate/`（公开面 SIT），见 §1.2；不设组件级目录，`Feat112ArchitectureTest` 校验不存在组件级验收。

`Feat112ArchitectureTest` 把以下规则做成测试：用例不直接断言；每个测试方法声明场景与层级且层级与目录一致；testplan 每个场景要么有承接用例、要么登记缺口；验收规则只能经 `Feat112Contract` 引特性档快照；判据不依赖驱动与部署；夹具不依赖用例；不读外部假定的部署地址。testplan 每行场景/前置/步骤/期望的文本指纹须与 `feat112/expectation-fingerprints.txt` 一致（testplan 改了而承接用例未复核即失败，复核后执行 `run-feat112.sh fingerprints` 重新登记）。规则已做过注入违规的变红验证。

执行沿用既有供给 → 运行 → 报告机制。载体（DA 宿主 + 降级门扩展 + demo stub 的可执行包）由 `scripts/sut/sut-sources.yml` 的 openjiuwen 环境构建并 install，坐标声明在 `application-openjiuwen.yml` 的 `sut.agents.feat112` / `feat112-peer`；用例按部署形态自行启动 demo stub 进程与托管 DA，不需要预先手工部署。测试仓整体编译恢复前，用 `feat112/tools/run-feat112.sh` 定向编译运行：

```bash
M2=<供给后的 Maven 仓库> feat112/tools/run-feat112.sh compile
```

```bash
M2=<同上> SOURCE_REVISION=<载体构建来源> feat112/tools/run-feat112.sh run target/feat112-run/result.txt <类或类#方法>...
```

运行结束自动生成逐场景 `target/acceptance-results/<runId>/summary.md`。

所有范围内场景由测试仓独立验收。开发仓已有测试可帮助定位机制和注入点，但不能替代测试仓用例或销项；公开面不可观测的要求依次从日志、持久化数据取证，都不可观测时提特性不可观测 Issue，并在覆盖登记中记录缺口。

生产算法不为满足cases而修改。demo只补真实配置接线与可控测试依赖；不在solution仓新增验收测试。

## 7. D1/D2 实践反馈

| 维度 | 本轮已核对的事实 | 方法论修正/待实证项 |
|---|---|---|
| D1 | testplan仍有缺号引用、公开/内部字段混用、依赖状态误写 | ID反查必须检查有效引用和测试层级，不止检查编号存在 |
| D1 | B7/E9 原分歧已由 PR 498 正式修订特性档/L2解决 | 以设计提交05af9cc3替换实现作为判据依据；同步testplan/cases，契约关闭不等于测试通过 |
| D1 | 001/008/064文档存在，其他部分缺失 | 特性依赖、执行依赖、能力激活分别表达；FEAT级关联可先工作 |
| D2 | DA拒收是自有HTTP，不是所有响应都为A2A | 按实际协议复用schema，统一取证封装不等于统一业务schema |
| D2 | 内部terminalAt和claimId不可由公开面直接取得 | 可观测性限制的是证明范围，不是判据数据化的能力；分层承接而非伪造证据 |
| D2 | E3需要真实工具+rail，E5需要拒绝与最终失败因果 | stub支持任意body不是场景已激活；需替身自检和实际路径证据 |
| D2 | 拒收响应合格不能独证零副作用 | 协议、状态、外部副作用证据组合，既抓错又避免越界推断 |
| D2 | 当前尚未取得本轮真实运行结果 | 独立判定、离线重评、稳定性等效果先实测，后刷新洞察，不把设计称为实证 |
| D2 | 首轮真实运行 26 条中 20 条“失败”全为载体或部署参数问题（模型地址缺 `/v1`、窗口与重试节奏不兼容） | 前置与验收必须分开记录；注入故障须有激活证明（stub 计数变化），否则错误路径也能“打开门” |
| D1 | Central 已发布 `0.1.3` 不含降级门，验收对象只能从源码构建 | 被测物需分别记录发布基线、构建来源 commit、制品坐标与校验和 |
| D1 | testplan 的 G5、E4/E5、F1–F3 预期取自 L2 或实现代码，与特性档不一致；23 行把配置键当能力 | 特性档是唯一预期来源，测试侧偏离直接按特性档改；能力门只用于契约可选能力（D1 v4.0 §8） |
| D1/D2 | 方法论要求原先只停留在约定，用例可绕开 | 结构规则固化为 `Feat112ArchitectureTest`：前置与判定分离、ID 双向对账、条款出处、判据独立、部署形态声明 |
| D2 | “既有校验错误”这类期望无法从契约写出具体文案 | 以被测物在门之外的既有行为为对照（O 判据族），期望不抄实现 |
| D1 | ID 双向对账全绿时，C7、E3 的期望已与特性档不一致 | ID 绑定只证明“有人认领”，不证明“按当前期望验”；需对期望文本做指纹，变化触发复核 |
| D1 | 7 个特性档 MUST 场景曾以“移交开发 UT”离开测试仓 | 开发仓用例不能替代测试仓独立验收，归属不能移出 |
| D2 | 9 个场景曾用组件级（测试进程内加载产品类、喂手工构造的事实）验收 | 组件级等于测试替产品编造内部事实，不是验收；可观测性按“契约 → 日志 → 持久化数据 → 不可观测 Issue”分级，观测不到的要求要变成对产品的可观测性诉求，而不是降级到组件级 |
| D2 | 监控规则属部署，但监控要消费的字段与日志由被测物输出 | 可观测性要求拆成“产出”（SIT 验）与“消费/告警”（部署验），不整条划出范围 |

PPT本轮不直接重写；将代码/报告证据链接回此表后，再更新D1/D2对应结论。旧长期记忆中的严格7字段白名单、所有依赖缺失、普通工具错误即业务失败等说法不能沿用。

## 8. 实现与运行登记

### 8.00 截至 2026-11-26（Linux 服务器全量实跑，载体按 engine 交付配置运行，testplan !654）

载体 2026-11-25 重建：core `origin/develop@105ce18c1`（0.1.18）+ solution `origin/common@d4d6ebc43`（revision 0.1.4），sha256 `6da81cdd…`；demo 不再自带 `application.yml`，DA 按 engine 交付配置运行（认证默认开启、checkpointer 为 Redis）。测试仓 `db510b3`，testplan 取 docs main `ef1feab1`（!654 合入版）。环境同 §8.0。

85 个场景全部有判定：**81 PASS、4 FAIL**（逐场景判定 91 条，F4 等由两个用例分别承接）。

| 结论 | 场景 | 说明 |
|---|---|---|
| FAIL / must_violation | A1 | `enabled` 键缺席、不读随包配置时诊断 `enabled=true`，第二个系统失败任务被 503 `ENGINE_DEGRADED` 拒收。装配层在键缺席时强制开启，与特性档 §3.3“ext 为 false”不符（DA L2 §1.4 已列为差距）。本机 Windows 同样复现。已提 openJiuwen/agent-solution#1011 请求裁决（实现整改或特性档修订） |
| FAIL / must_violation | E4-formats | `context_length_exceeded` 码配无长度字样的文案时计入分子（`systemFailureCount=1`）。同 §8.0 之后的 core 缺陷（DeepAgent 丢弃结构化 failure），已提 openJiuwen/agent-core-java#322 |
| FAIL / conditional_not_triggered | E4 | 同一 core 缺陷：任务失败未带结构化 181012，激活不成立 |
| FAIL / conditional_not_triggered | E3 | 出厂配置下 `business_failure` 落默认 ask，探针先停待答（待特性设计确认，状态不变） |

本轮转绿或首次通过：G5-missing（载体对齐交付配置后，交付默认匿名访问返回 401）、G12、G5-invalid（均 401 且与 `POST /api/agents/run` 同一请求的拒绝一致）、G13（缺密钥启动失败并指出 `deepanalyze.auth.engine-secret`）、C4（迁移事件 `generation` 由 `quiet_elapsed` 时的值递增到 `system_failure` 时的值）、E15（新激活证据：任务记录 error 为检查点读取失败、续跑后 stub 无新请求）、E17（成员被拒耗尽后无关失败计入分子）、E18（hook 每轮拒绝直到轮次耗尽：任务 failed、只计分母）、A2（随包交付配置 `enabled=true`）、E16。其余场景无退化。

测试侧问题一项（已修，不计产品问题）：交付配置把 SSE 异步超时设为 61 分钟（原 demo 配置下为 Spring 默认 30 秒），任务停在待答时 `/run-stream` 不结束，E15 读响应体被阻塞；改为读到 `ask_user` 事件后由客户端断开（`DaEvidenceClient.postSseUntil`），被测物配置不变。

### 8.0 截至 2026-11-20（Linux 服务器全量实跑，新载体）

载体 2026-11-18 重建：core `origin/develop@f777aa244` + solution `origin/common@96c23d4ea`（sha256 `cdb5c93e…`，运行时按构建清单核对）。环境：华为云 EulerOS ECS、JDK 21、Docker（镜像 `registry:8501/postgres:16`、`registry:8501/redis:7.4.9`）、libfaketime 0.9.10 现场编译；离线依赖库 + 空 Maven settings。轮次：`linux-rest-1`（14 个类 55 方法）、`linux-env-2`（E15、E11、F3、F4）、`linux-clock-3`（B6、B7×2、E9）、`linux-a2j4-2`（A2、J4 复跑）；更早轮次（linux-env-1、linux-b7*、linux-clock-2）被取代。结果包 `feat112/logs/results-all-20261120.tar.gz`（linux-a2j4-2 以回贴为据）。

84 个场景：**77 PASS、4 FAIL、3 未承接**。只能在 Linux 跑的 8 个方法全部有定论。

| 结论 | 场景 | 说明 |
|---|---|---|
| FAIL / must_violation | G5-missing | 同 §8.2：交付默认配置下匿名可读诊断面；修复方向待特性设计定 |
| FAIL / must_violation | **F4（本轮新发现）** | OPEN/HALF_OPEN 下带保留租户头 `*` 的 `/run` 返回 503 `ENGINE_DEGRADED`，CLOSED 下同一请求返回 400 `ENGINE_VALIDATION`：既有租户校验被降级门盖过，违反特性档 §5.1.9、验收出口 18（校验先于降级门）。与代码走读预判一致（保留租户校验在认领名额之后）。待提 Issue |
| FAIL / conditional_not_triggered | E3、E16 | 前置不成立，结论无效。E3：出厂配置下 `business_failure` 落默认 ask。E16（2026-11-20 更正原因）：topP NPE 已在 solution `96c23d4ea` 修复（本轮证据中成员正常运行、workflow completed），现激活败在替身剧本未命中成员请求——测试侧待重写激活逻辑，非产品问题 |
| NOT_EXECUTED | A1、E17、E18 | 覆盖缺口（待特性设计确认），无用例 |

Linux 实跑暴露并修复的测试侧问题六项（均不计产品问题，复核记录本地留档）：数据面基线 `plugins` 补 `version` 列（solution `875b65d5b` 起 DA 启动读取）；Redis 夹具停容器后不再取映射端口；libfaketime 下 DA 约慢 9 倍——各等待上限放宽、过期余量 30s→120s、静默期 10s→30s、加 `NO_FAKE_STAT`；测试客户端单请求超时 60s→600s；DA 先标任务完成后交终态给门，时钟用例改为等 `terminal fact taskId=` 日志行再读门（B7 HALF_OPEN 一度误判 must_violation 即此竞态）；隔离交付默认进程保留 LANG/LC_*（否则 Linux JVM 按 ASCII 解析内置技能中文目录名、DA 启动失败），主动终止期间的读流异常不算错。

另记（DA 范围，非本特性条款）：POSIX locale 部署环境下 DA 因内置技能中文目录名启动失败，建议开发关注。

### 8.1 截至 2026-11-17（只做 SIT，testplan 修订稿 84 行）

84 个场景中 75 个有 SIT 承接用例（公开面 14 个类，无组件级），9 个登记为未承接（`Feat112Coverage.UNCOVERED`）。同日稍后补齐部署夹具后，未承接降为 3 个（A1、E17、E18），见本节末“部署夹具”。原 9 个为：
- A1：仓内除 DA 外没有装配降级门的可运行宿主，ext 单独装配无法在 SIT 构造，需特性确认；
- B6、B7、E9：需 Linux + libfaketime 时钟控制夹具（待实现）；
- E15：需 Redis checkpointer 部署形态（待实现）；
- E11、F4：需 PostgreSQL 数据面部署形态（待实现）；
- E17、E18：等 PR 463、PR 538 合入再定。

2026-11-16 的组件级结论（A1、B6、B7、C4 代际部分、E15、E7 瞬间部分、E8、E9、E11）全部撤回，不计入验收；不可观测 4 条的简稿本地留档（2026-11-20 处置：3/4 合并为增强提单 openJiuwen/agent-solution#856，5 经开发核查接受关闭）。

本轮实跑（载体同 §8.2）：`sit-20261117`、`sit-20261117b`。
- E8（新 SIT：`max-concurrent-tasks=1`，经 `/answer` 续跑占满执行池后新任务认领即被拒）：PASS，既有 503 `ENGINE_BUSY`，名额释放、保持 HALF_OPEN、样本不变，续跑结束后下一任务成为探针。
- E4-formats（新增）：PASS，两种提供方格式均只计分母。
- E7（补“下一真实任务可成为探针”）、I1（补启动记录 `enabled`、`delivery default applied` 字段）、K20：PASS。
- 测试侧错误两处（已改、复跑通过，不计产品问题）：E4-formats 首跑条款引用写成“§5.1.3 第258行”，不符合引用格式，判定前被拒；E7 首跑 must_violation 系误判——原检查要求取消前后样本数相等，未考虑 60s 窗口内旧样本过期（5→4），改为从发取消的时刻比较、只判“不增加”。

其余场景沿用 `full-20261116` 与复跑结论（§8.2）；G5-missing（must_violation）、E3、E16（conditional_not_triggered）状态不变。

部署夹具（2026-11-17 补齐，测试侧，不改被测对象）：部署形态可声明三类测试托管环境——`RedisCheckpointStore`（Redis 容器作 core checkpointer）、`PostgresDataPlane`（PostgreSQL 容器，回放数据面基线 DDL，接 `deepanalyze.data.pg.*`）、`ClockControl`（libfaketime 控制 DA 进程时钟）。新增 `Feat112ClockTest`（B6、B7×2、E9）、`Feat112CheckpointStoreTest`（E15）、`Feat112DataPlaneTest`（F4、F3 的 team 不存在、E11）。本机（Windows、无 Docker、无 WSL 发行版）跑不了：`env-20261117` 中 B6、E15 记 infra_failure（缺 libfaketime、缺 Docker），`Feat112DataPlaneTest` 在部署阶段失败、整类未执行。需在装有 Docker 与 faketime 的 Linux 上实跑；E15 的“恢复检查点失败”路径、libfaketime 对 JVM 是否生效都要在那里确认，走不通的按口径提不可观测 Issue。

### 8.2 截至 2026-11-16

> 本节及 §8.3 为历史登记，场景 ID 保留当时写法（对照见 §1）。

82 个场景中 80 个有承接用例（公开面 14 个类、组件级 2 个类：`Feat112MechanismTest`、`Feat112TerminalClassificationTest`），E5.5、E5.6 登记为未承接（`Feat112Coverage.UNCOVERED`）；`Feat112ArchitectureTest` 校验 ID 双向对账、结构规则与期望文本指纹。被测物：载体 `com.openjiuwen.example:degradation-agent-runtime:0.1.0`，sha256 `2ff9090a15060f3c00ba00c4af3dbfa5bfc453b0b1aec94cfaac49464561b55b`，由 core `origin/develop@e24bcb74d` 与 solution `origin/common@1127a9ece7b`（干净工作树，不含本地他人改动）构建；demo 未纳入版本控制，记内容哈希；构建清单 `feat112-build-manifest.json`，每条结果记录与报告头写明来源。

运行：`full-20261116`（当时的全部 15 个类，64 个测试方法 57 个通过；逐场景 {PASS=72, FAIL=7, NOT_EXECUTED=3}）；其后修正三处测试侧错误并复跑 `rerun-20261116b`（E5.2、G8、F-reg、J4，4/4 通过）；E5.3 组件级 `e53-20261116b` 通过。结果目录 `target/acceptance-results/<runId>/`（不入库）。

| 结论 | 场景 | 说明 |
|---|---|---|
| PASS（77） | 除下列 5 项外的全部场景 | 首次执行即通过的新场景：A3–A6、B6、C8、C9、E5.1、E10、E12、H4；改写后通过：C7/B4（三段）、C3/E2（`/run-stream` 后续任务）、C4、D3、F2、I1（启动记录 `instanceId` 与诊断一致）、J2、J3；复跑通过：E5.2、G8、F-reg、J4；E5.3 为组件级 |
| FAIL / must_violation | G5a | 同 §8.3：交付默认配置下匿名可读诊断面；修复方向待特性设计在三种方案中选择 |
| FAIL / conditional_not_triggered | E3 | 出厂 actrule 下 `business_failure` 落入默认 ask，探针停在待确认；待开发答复 |
| FAIL / conditional_not_triggered | E5.4 | 载体上工作流成员无法构建：core `e24bcb74d` 把 `ModelRequestConfig.topP` 改为可空，solution `DaWorkflowRunner.memberRequestConfig` 拆箱 NPE（非 FEAT-112 缺陷，草稿本地留档；经开发核查指定基线已修复、确认关闭）；结论无效，修复后重跑 |
| 未承接 | E5.5、E5.6 | 公开面造不出“无错误码、无类目、非 core”且由成员拒绝引起的任务失败；“护栏拦截导致任务失败”的确定路径未建立（PR 538 评审） |

部分承接（`Feat112Coverage.PARTIAL`）：A1、A2（键缺席待特性设计确认，结论只说明实现行为）、C4、E3、E7、E8、E11、F3（team 存在分支需 PostgreSQL）、I1（启动记录正式载体待开发确认；告警保留属部署验收）。

本轮测试侧错误（结论已撤回，不计产品问题）：
- E5.2、G8 首跑 infra_failure：工具调用加 256 个失败超过 stub 单次 256 条上限，stub 拒收；失败尾部改为 255。
- F-reg 首跑 must_violation（`/resume` 期望 200、实为 202）：inject、cancel、answer、message、resume 的期望写死，未按 testplan 与门 CLOSED 时同一请求对照；补 CLOSED 基线后通过。`/resume` 的既有契约就是 202。
- J4 首跑 conditional_not_triggered：新代码默认开启 `answer-gate.stream-buffering-enabled`，整段模型回复结束前不下发增量，慢消费者读到 3KB、订阅队列无从溢出；部署声明关闭内容缓冲（回答门仍开）后溢出发生，任务只计一次。

### 8.3 截至 2026-11-12（历史）

截至 2026-11-12（结构改造后）：65 个场景中 64 个有承接用例（公开面 13 个类、组件级 1 个类），E5 登记为未承接；`Feat112ArchitectureTest` 校验 ID 双向对账与结构规则。被测物：载体 `com.openjiuwen.example:degradation-agent-runtime:0.1.0`，sha256 `76777455cd0321bdf8e937a21c749c56f642a140973de31b595cda5a7e26c089`，由 solution `0f5d17265`（工作区有本地改动）于 2026-11-11 源码构建；每条结果记录同样写明该身份。开发仓已有测试不计入下表。

最终运行 `final-20261112`（本机 Windows，约 31 分钟）：124 个测试方法中 121 个通过；逐场景结论见 `target/acceptance-results/final-20261112/summary.md`（不入库）。

| 结论 | 场景 | 说明 |
|---|---|---|
| PASS（61） | 除下列 4 项外的全部场景 | 组件级 A1、B7、C4、E7、E8、E9、E11 针对产品类；其余为托管真实 DA 的公开面验收，含首次执行的 A2、A7–A10、B1–B5、C1、E6、H1–H3、I1、I2、J2、J4 |
| FAIL / must_violation | G5a | 交付默认配置下匿名请求可读诊断面（HTTP 200 并返回门状态），违反特性档能力表“纳入既有服务间认证而非公开匿名面”与验收出口16“要求合法服务间认证”；已配置认证的部署上 G5a/G5b/G5c 均通过。2026-11-13 复核：交付默认下 DA 服务间认证整体关闭（`DaAuthProperties` 默认 false，`/run` 同样匿名），修复方向待特性设计选择——DA 交付默认开启认证、仅诊断面在未配置认证时拒绝匿名、或澄清出口 16 只要求已配置认证的部署（见 docsReview 差异清单第九条）；在此之前不按单纯的 FEAT-112 产品缺陷提交 |
| FAIL / conditional_not_triggered | E3、G8 | 探针/哨兵任务停在 `awaiting_answer`：DA 权限资产默认 `ask`，未放行 `business_failure`，业务结果通道不可达。该次结论无效，不是产品违规；待开发确认默认放行或改写前置 |
| 未承接 | E5 | 出厂配置下没有确定可达的“护栏正常拦截”路径 |

部分承接（不影响上表结论，缺口登记在 `Feat112Coverage.PARTIAL`）：A1 未覆盖 ext 单独装配的 Spring 链；C4 故障代际只经静默期重算推断；E7 取消微窗口、E8 宿主执行池真实拒绝链、E11 宿主认领到绑定窗口只有组件级；F3 团队“可用”分支需 PostgreSQL；I1 未覆盖监控告警关联。

首轮真实执行回写（保留原始失败记录 `full-20261112`、`rerun*-20261112`）：
- I2 首轮 infra_failure：重启时 stub 仍有在途延迟请求，旧驱动清理立即重置被拒（409），清理异常盖过验收。驱动清理改为先等 stub 无在途请求。
- J4 首轮 conditional_not_triggered：进程输出只保留前 1MB，溢出标记在其后；改为全程监听标记。复跑仍未溢出：订阅队列容量等于 `max-events-per-task`（默认 5000），慢读期间未写满；该场景部署改为 64。随后在 128MB 模型输出压力下 DA 在任务终态前退出，当时未留输出尾部，原因未核实，未作为缺陷登记；压力降到 2MB 后溢出确实发生，任务完成且只计一次分母，J4 通过。隔离进程现保留输出尾部，进程中途退出时记录证据。
- 原先因未接入供给链而从未执行的 20 个场景（A2、A7–A10、B1–B5、C1、E6、H1–H3、I1、I2、J2、J4）本轮首次执行；部署形态由测试声明并自行启动，不再依赖手工部署与 `feat112.*` 系统属性。
