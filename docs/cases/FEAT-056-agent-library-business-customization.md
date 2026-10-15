---
title: FEAT-056 业务 Agent（Lib JAR）二次开发扩展点验收测试设计
feature_id: FEAT-056
document_type: test-design
status: final
automation_status: implemented
docs_baseline: docs PR 312 head 930035fa4c46624ee7109596c4781aafd20d8e5e + base 1122b6009895d67172c2a880ea23fa7a4d4a23dd；preview merge aca19b264f3ca52057dbe17b6ca9bd48d7c08590
feature_source: 02-Code/_worktrees/docs-pr312-930035fa/develop/02-features/FEAT-056-agent-library-business-customization.md @ aca19b264f3ca52057dbe17b6ca9bd48d7c08590 sha256 B0643D32533970EC441CA848BCE20332E20820C523E23E65564E488514E16B97
frs_source: 02-Code/_worktrees/docs-pr312-930035fa/develop/01-scenarios/FRS-056-agent-library-business-customization.md @ aca19b264f3ca52057dbe17b6ca9bd48d7c08590 sha256 365752868B4717FCA6F6E99FA73F9190620D7186370B7A1DC6F35C2E30593EC2
l2_source: 02-Code/_worktrees/docs-pr312-930035fa/develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-056-agent-lib-jar-secondary-development.md @ aca19b264f3ca52057dbe17b6ca9bd48d7c08590 sha256 6438099E26E501BEC73A4FA14ED1720F02926CEA9410CCE6399ADC37B59D7757
requirement_preview: FEAT-056-需求与测试预览.md
change_scope: 20261012-FEAT-056-变更包范围卡.md
input_maturity: frozen-pr312-preview
---

# FEAT-056 业务 Agent（Lib JAR）二次开发扩展点验收测试设计

> 本文保存逐用例判定规则、证据要求和自动化落点；执行状态与覆盖结论以 `FEAT-056-覆盖与状态.md` 和 `FEAT-056-review-deliverables.json` 为准。

## 0. 用户必读：需求与测试方案

- 一句话说明：让二开方在不动原 Agent Library JAR 的情况下，为自己的应用增加 Rail/Tool，并确保每个真正执行的 Agent 对象只装配一次、失败时不带病运行。
- 关键链路：Library JAR 交付 → 二开应用加入自定义器 → Runtime 在对象执行前回调 → 二开方调用 Core API 注册能力 → 原 A2A/REST 入口执行 → 原所有者释放对象。
- 本期测试：先做打包/真实 A2A 主路径，再做组件和集成故障矩阵，覆盖对象身份、单次初始化、per-task 清理、Hosted 定向、Rail/Tool、护栏和日志。
- 本期不测：旁挂/热加载、托管非默认 Agent 按任务新建、同对象并发执行、异构框架、统一 Tool API、通用副作用回滚。
- 执行结果：47 条权威用例全部 PASS，0 skipped；27 条范围内需求全部 `covered`，无 open/degraded 缺口，`coverage_blocking=0`。通用实例扩展能力是主验收对象；MOA `0.1.1` 作为真实使用方旁证执行。
- 详细预览：`FEAT-056-需求与测试预览.md`
- 本期范围：`20261012-FEAT-056-变更包范围卡.md`
- 输入成熟度：`frozen-pr312-preview`；设计、实现、执行和覆盖均已收口。
- 用户理解确认：`confirmed`

## 1. 依据、当前口径与范围

- 设计真相源：Feature `FEAT-056-agent-library-business-customization.md`、FRS-056。
- L2 实现说明：`Feat-Func-056-agent-lib-jar-secondary-development.md`；PR312 已统一 Feature/FRS 链接。
- 已确认裁决：FRS DEC-01～11；FRS §3.1 五格部署范围。
- 范围内：FR-01～16 及 AC-01～16 子项；Rail/Tool；非托管 singleton/per-task、托管复用、托管默认 Agent 显式 per-task；启动、任务、护栏失败；释放和日志。
- 范围外：托管非默认 Agent 按任务、热加载、旧 SPI、异构框架、统一 Tool API、同对象并发、通用回滚。
- 原确认项：DQ-056-01～03、L2Q-056-01～02 已由 PR292/PR312 和本轮 C22 执行关闭。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| `deepagent-a-library + customer-agent-runtime` 示例 | 代表性二开应用 | 原库不改、Rail、Tool、per-task、真实模型、A2A、释放 | 不用于证明 MOA 业务编排语义 | 作为 AC-01/02/06/07/08/09a/13 的主代表 SUT |
| 测试专用 Library + 宿主变体 | Fixture 应用 | 同版库两个不同扩展、可控错误和回调账本 | 不用于证明真实 MOA 业务语义 | 已实现并执行；用于 AC-02/03/04/05/11/14a/16 |
| 通用 Hosted 测试宿主 | Fixture 应用 | 单成员/双成员、默认 Agent per-task、相同 conversation 隔离、启动回滚 | 非默认 Agent per-task OUT | 用于 AC-04/09b/10/14b/16 |
| Core DeepAgent/BaseAgent 组件夹具 | 组件/集成载体 | 精确制造类型错误、destroy 失败、Rail priority、Tool 资源和释放条件 | 单独不能证明真实入口/打包 | 用于 AC-07/11/12/15/16 的故障面，必须与非夹具链路分开记账 |
| `agent-solution` MOA `0.1.1` | 真实使用方旁证 | 在环境与版本兼容时验证通用定制器能否接入真实 MOA DeepAgent | 官方当前是应用交付，不是官方 Library JAR；不作为 FEAT-056 通用能力门禁 | 用于兼容性 smoke；失败先核对版本/装配，不直接判产品 FAIL |

## 3. 黑盒边界、拓扑与前置条件

黑盒边界以“应用启动 + 配置装配 + A2A/REST/公开 Handler 入口 + 可观察日志/事件/协议响应”为主。组件夹具只用于制造外部不容易触发的失败条件，不能替代真实二开应用主旅程。

前置：

- 使用 Windows 构建、WSL 执行测试；共享 Maven 仓库由 Windows 只写。
- 真实模型凭据仅由受控运行脚本注入，不读取、不打印、不落文件。
- 正式执行前冻结 docs、agent-solution、agent-core-java、agent-runtime-java、acceptance 仓与 fixture SHA-256。
- 本轮使用独立 acceptance worktree 和 solution fixture 分支，未修改产品代码。
- `Library JAR` 必须是普通依赖 JAR，不含可独立启动的 Spring Boot 入口。

### 3.1 Oracle 锚与反自证门禁

FEAT-031 复盘的直接教训是：前置检查、配置存在、测试侧自造期望都可能形成“账面 PASS”。FEAT-056 采用以下门禁：

- 每条 case 必须声明 `oracle_kind`：`external`、`independent`、`self` 或 `control`。
- 行为、生命周期、错误面和跨边界证据必须是 `external`；结构合同可使用 `independent`。
- `self` 只能证明已装配/已连通，不能单独计入能力覆盖或支撑 `closed`。
- `control` 用于验证主断言对故障敏感，不计入产品能力覆盖。
- 外部锚包括：真实 A2A/SSE 响应、真实模型链路、生产类实际产物哈希、进程退出/日志、真实 MOA `0.1.1` 执行事件。
- 每条 P0 用例必须能回答：“若目标行为完全不生效，这条用例会因哪个主断言失败？”

## 4. 需求覆盖矩阵

稳定条目目录与唯一覆盖台账：`FEAT-056-review-deliverables.json`；实际结果与缺口只在该 manifest 的状态页维护。

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| FEAT-056-C01 | Library JAR 交付边界 | execution |
| FEAT-056-C02 | 两应用使用同版原库且原 JAR 不变 | execution |
| FEAT-056-C03 | 零自定义器兼容 | execution |
| FEAT-056-C04 | 单/多自定义器顺序 | execution |
| FEAT-056-C05 | 自定义器失败停止后续调用 | execution |
| FEAT-056-C06 | 非托管 ID 与请求 metadata 隔离 | execution |
| FEAT-056-C07 | 单成员 Hosted ID | execution |
| FEAT-056-C08 | 双成员 Hosted 同 conversation 定向 | execution |
| FEAT-056-C09 | singleton 每对象一次并持续生效 | execution |
| FEAT-056-C10 | 非托管 per-task 新对象覆盖 | execution |
| FEAT-056-C11 | Hosted 默认 Agent 显式 per-task | execution |
| FEAT-056-C12 | Rail 共存与工具事件筛选 | execution |
| FEAT-056-C13 | 私有 Tool 资源隔离 | execution |
| FEAT-056-C14 | 共享 Tool 所有权释放 | execution |
| FEAT-056-C15 | 对象释放互不影响 | execution |
| FEAT-056-C16 | 不支持对象类型先失败 | execution |
| FEAT-056-C17 | 非托管启动期失败 | execution |
| FEAT-056-C18 | Hosted 启动失败与回滚 | execution |
| FEAT-056-C19 | per-task 初始化失败清理 | execution |
| FEAT-056-C20 | per-task 清理失败不掩盖原错误 | execution |
| FEAT-056-C21 | 护栏 priority 显式检查与共存 | execution |
| FEAT-056-C22 | Hosted 启停日志字段完整、回滚可识别且不泄露敏感信息 | execution |
| FEAT-056-C23 | 重建生效且无旁挂/热加载 | execution |
| FEAT-056-C32 | 不同 conversation 任务对象并发初始化（不额外串行化） | execution |
| FEAT-056-C25 | 断言敏感性反证 | execution |
| FEAT-056-C26 | 动态能力 Rail 组合位置 | execution |
| FEAT-056-C27 | 部分注册失败后的回滚 | execution |
| FEAT-056-C28 | 对象级 Rail 与请求级 Rail 共存 | execution |
| FEAT-056-C29 | 同 conversation 并发首建 | execution |
| FEAT-056-C30 | 真实 MOA 0.1.1 外部锚 | execution |
| FEAT-056-C31 | 官方示例来源保真 | execution |
| FEAT-056-C33 | init-fail-fast=false 非托管初始化失败不退出进程 | execution |
| FEAT-056-C34 | init-fail-fast=true 非托管初始化失败使进程退出 | execution |
| FEAT-056-C35 | 按任务初始化失败且 destroy 也失败 | execution |
| FEAT-056-C36 | 零自定义器真实链路且不要求业务 ID | execution |
| FEAT-056-C37 | 真实链路不支持对象类型先失败 | execution |
| FEAT-056-C38 | 真实链路按任务对象各自扩展与释放隔离 | execution |
| FEAT-056-C39 | 托管默认 Agent 按任务扩展（真实链路） | execution |
| FEAT-056-C40 | 反证：撤掉自定义器注册后调用判据必须失败 | execution |
| FEAT-056-C41 | 反证：撤掉 Rail 注册后 canary 判据必须失败 | execution |
| FEAT-056-C42 | 反证：撤掉 Tool 注册后解析判据必须失败 | execution |
| FEAT-056-C43 | 反证：托管注册 ID 串用时各自注册名判据必须失败 | execution |
| FEAT-056-C44 | 反证：撤掉销毁调用后清理判据必须失败 | execution |

<!-- coverage:scenarios:end -->

### 4.1 执行状态摘要

当前无范围内 `unassigned/degraded` 场景。C01～C44（C24 退休，由 C32 替代）均已实际执行或按范围排除；C18/C22/C24 的原未决口径已分别由 Hosted 回滚、PR312 日志契约和并发初始化决策关闭。

### 4.2 历史经验承接

来源 manifest：`FEAT-056-review-deliverables.json`。

| lesson ID | 历史经验/漏检 | 本期风险 | 用例族 | case ID | 证据要求 |
|---|---|---|---|---|---|
| LESSON-056-00 | 未检到 FEAT-056 专项历史记录 | 把“Bean 存在、配置注入或静态文件”误当成扩展生效 | 全部 | C01～C31 | 每项必须有对象级调用计数、实际事件、协议响应或失败原文 |
| LESSON-031-01 | 判定权与被判定方同源，测试侧自造期望自证 | FEAT-056 只证明自定义器和 Rail 类存在，未证明真实入口执行 | 外部锚 | C02、C12、C13、C26、C30 | 真实 A2A/SSE、真实 MOA 执行、外部工具返回值或产物哈希 |
| LESSON-031-02 | 前置检查、装配检查被计入覆盖并报 PASS | 薄 JAR 构建成功、Bean 数正确被误当扩展生效 | 执行链 | C01、C02、C03、C04、C25 | 必须给出首次执行前后调用增量，并做断言敏感性反证 |
| LESSON-031-03 | 真实文件只证明来源，不代表真实使用和组合位置 | 样例有 Rail 但不在目标执行链；动态 Tool/请求 Rail 未组合 | 组合/Nesting | C10、C11、C12、C14、C26、C28 | 组合位置、对象身份、动态安装后事件和请求前后增量 |
| LESSON-031-04 | 中间输出可满足弱断言，目标终点未证明 | 自定义器执行过一次就报 PASS，但新增 Rail 未触发 | 结果归属 | C12、C13、C15、C25 | 新 Rail 唯一 canary 必须来自目标 callback，且撤掉注册后主断言失败 |
| LESSON-031-05 | blocked/degraded 静默成为正常收口 | C18/C22/C24 曾因未决 Oracle 保留欠账 | 已闭环 | C18、C22、C24 | C18/C22 已执行 PASS，C24 退休并由 C32 替代；覆盖台账无 degraded |
| LESSON-031-06 | 真实来源未保证真实使用 | 只用测试专用 Library 和自写宿主证明平台能力 | 代表性 | C02、C30、C31 | 至少一个官方合入示例和一个真实 MOA `0.1.1` 外部锚，保留源 revision |
| LESSON-031-07 | 过早归因、单一解释延迟发现其他问题 | 一次并发/启动失败被归到环境，不再换观测面 | 失败归因 | C18、C24、C25、C29 | 候选解释逐条列排除依据；换观测面后仍保留原结论状态 |

## 5. 端到端场景

### E2E-1 二开主旅程

先单独构建 `deepagent-a-library`，记录普通 JAR SHA-256；再只依赖已安装的原库构建 `customer-agent-runtime`，记录可执行 JAR。启动后通过 A2A 发送真实模型请求，触发原 Tool、新增 Tool 和客户 Rail。请求完成后检查原 Rail/新增 Rail 的 `init/uninit` 和工厂 `destroy`。

### E2E-2 两个二开方

用同一原库产物构建两个宿主变体，分别注册不同 Rail/Tool。两个应用都要启动成功；原库 SHA-256 必须完全相同；两个行为结果必须不同。

### E2E-3 Hosted 多 Agent

通用 Hosted 宿主注册 `order-agent` 与 `support-agent`，两者使用相同 conversation ID 分别调用。回调账本必须出现各自的注册名，只匹配 `order-agent` 的扩展在 `support-agent` 上调用次数为 0。

## 6. 详细用例

### FEAT-056-C01：Library JAR 交付边界

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：测试专用 Library
- 测试层次：package/component
- Oracle 来源：FRS FR-01/AC-01
- Given：Library 工程已构建为普通 JAR，依赖中包含 Handler/Rail/Tool 但无启动类。
- When：直接尝试按可执行 JAR 启动；另将 JAR 作为依赖装配到最小宿主。
- Then：直接启动不能提供 Agent 服务；最小宿主可载入库内配置并启动。
- PASS：两个条件都成立，产物结构与启动结果一致。
- FAIL：Library 自带可用启动入口，或最小宿主无法装配库内 Handler/配置。
- INCONCLUSIVE：构建成功但未实际启动宿主，或缺依赖导致无法区分产品与构建问题。
- 配置生效/下游触达证据：JAR 内容清单、jar manifest、宿主启动日志、Handler 就绪状态。
- 调用增量与唯一数据：使用本轮唯一 application name 与端口。
- 不应断言：不以 Maven 模块类型名代替实际可启动性。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerPackagingContractTest#libraryJarIsPlainLibraryWithoutBootLoader`。

### FEAT-056-C02：两应用使用同版原库且原 JAR 不变

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：两个宿主变体 + 同版 Library
- 测试层次：E2E/package
- Oracle 来源：FRS FR-02/AC-02
- Given：原 Library 只构建一次并记录 SHA-256。
- When：两个宿主分别依赖该 JAR，配置不同自定义器和行为，再分别构建启动。
- Then：两应用都启动并表现不同；两个可执行包内的原 JAR SHA-256 等于最初独立构建值。
- PASS：行为差异与哈希一致同时成立。
- FAIL：任一应用要求改原库，或包内原 JAR 改变，或两个扩展行为无差异。
- INCONCLUSIVE：只构建未启动、只比较文件名未比较字节。
- 配置生效/下游触达证据：两次构建日志、JAR SHA-256、两个 A2A/REST 行为响应。
- 调用增量与唯一数据：两个应用使用不同端口与测试数据。
- 不应断言：不要求整个可执行 JAR 字节相同。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerPackagingContractTest#bothAppsEmbedTheSameUnchangedLibraryJar`。

### FEAT-056-C03：零自定义器兼容

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：测试宿主原库路径
- 测试层次：integration
- Oracle 来源：FRS FR-03/AC-03
- Given：应用不注册 `AgentInstanceCustomizer`。
- When：启动应用并执行同步、流式原能力。
- Then：不要求业务 ID，不提前因自定义器初始化 Agent；原行为和原响应保持。
- PASS：请求成功且不存在自定义器调用记录。
- FAIL：缺 ID 导致启动失败，或无 Bean 时仍进入扩展初始化。
- INCONCLUSIVE：只检查 Bean 数，没有执行原能力。
- 配置生效/下游触达证据：启动上下文、请求响应、无调用账本。
- 调用增量与唯一数据：同一 conversation 顺序请求两次。
- 不应断言：不能把“没有编译错误”当兼容。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#zeroCustomizersDoNotRequireBusinessAgentId`。

### FEAT-056-C04：单/多自定义器顺序

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：测试 Library + 宿主
- 测试层次：integration
- Oracle 来源：FRS FR-03/FR-05/AC-03/AC-05
- Given：分别配置一个、两个带 `@Order` 的自定义器，记录调用顺序和对象身份。
- When：启动 singleton 或创建任务对象。
- Then：一个 Bean 正常调用；多个 Bean 按 Spring 顺序调用，回调收到相同 ID 和同一实际对象。
- PASS：顺序、次数、对象身份均符合合同。
- FAIL：顺序错误、对象串用或多调/漏调。
- INCONCLUSIVE：只用 Mockito 验证 Bean 定义，未过真实装配。
- 配置生效/下游触达证据：有序回调账本、对象 identity/hash、Rail 注册结果。
- 调用增量与唯一数据：使用独立宿主 application context。
- 不应断言：自定义器顺序不等于 Rail priority。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#orderedCustomizersRunInOrderAndInitializeTheSingletonOnce`。

### FEAT-056-C05：自定义器失败停止后续调用

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：故障宿主
- 测试层次：integration failure
- Oracle 来源：FRS FR-05/AC-05；L2 §2.2
- Given：第一个自定义器抛指定异常，第二个自定义器有调用计数。
- When：初始化目标对象。
- Then：第二个调用次数为 0，对象不可执行，原异常成为主错误。
- PASS：调用链截断、执行被拒、异常身份匹配。
- FAIL：后续仍被调用，或失败对象完成请求。
- INCONCLUSIVE：只看到表观失败，无法确认失败回调与对象归属。
- 配置生效/下游触达证据：异常原文、两个回调计数、入口拒绝记录。
- 调用增量与唯一数据：失败标记使用唯一 token。
- 不应断言：不验证外部副作用已回滚。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#laterCustomizerIsNotCalledWhenEarlierCustomizerFails`。

### FEAT-056-C06：非托管 ID 与请求 metadata 隔离

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管宿主
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-04/AC-04
- Given：配置 `openjiuwen.service.agent-id=business-a`；请求 metadata 放入伪造的 `agentId=other`。
- When：触发初始化和请求。
- Then：回调始终收到 `business-a`，伪造 metadata 不改变目标或扩展次数。
- PASS：真实回调账本和业务行为都只命中 `business-a`。
- FAIL：目标切换、双调或 metadata 覆盖配置。
- INCONCLUSIVE：只检查请求 JSON，未观察回调。
- 配置生效/下游触达证据：配置键存在性、请求原文、回调账本。
- 调用增量与唯一数据：伪造值使用唯一 canary。
- 不应断言：不把 Core card ID 等于业务 ID 当合同。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#requestMetadataCannotOverrideBusinessAgentId`。

### FEAT-056-C07：单成员 Hosted ID

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：单成员 Hosted 宿主
- 测试层次：integration
- Oracle 来源：FRS FR-04/AC-04；Feature §5.1.1
- Given：Hosted definitions 仅注册 `solo-agent`，全局 `openjiuwen.service.agent-id` 设为不同值。
- When：启动并调用。
- Then：回调 ID 为 `solo-agent`，不回落到全局 ID。
- PASS：注册名与回调 ID 一致，目标 Rail 生效。
- FAIL：回调使用全局值或不触发。
- INCONCLUSIVE：只读 definitions 清单未实际调用。
- 配置生效/下游触达证据：Hosted 清单、全局配置、回调账本、响应。
- 调用增量与唯一数据：solo application 独立端口。
- 不应断言：不扩展到多进程寻址。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerHostedContractTest#singleHostedMemberUsesEntryIdInsteadOfGlobalServiceId`。

### FEAT-056-C08：双成员 Hosted 同 conversation 定向

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：双成员 Hosted 宿主
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-04/FR-10/AC-04/AC-10
- Given：`order-agent` 和 `support-agent` 各有一个复用对象，只给 order 注册可识别 Rail。
- When：两者使用相同 conversation ID 分别调用。
- Then：回调 ID 与各自注册名一致；support 上 order Rail 事件为 0；各自 Manager 独立。
- PASS：ID、对象、扩展事件和 Manager 归属全部不串用。
- FAIL：任一事件串用、共享 Manager 或错误 ID。
- INCONCLUSIVE：只调用一个 Agent。
- 配置生效/下游触达证据：两个响应、两份回调账本、Manager identity、路由日志。
- 调用增量与唯一数据：同一 conversation 是刻意复用，请求 message ID 唯一。
- 不应断言：不要求不同 Agent 的 conversation state 合并。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerHostedContractTest#dualHostedMembersReceiveTheirOwnEntryIds`。

### FEAT-056-C09：singleton 每对象一次并持续生效

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 singleton + 示例应用
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-08/AC-08；L2 §3.1
- Given：一个 singleton 对象、带计数的自定义器和 Rail。
- When：重复 start，随后执行多次不重叠的同步/流式请求。
- Then：自定义器只一次，Rail 注册一次，扩展在所有请求中持续生效。
- PASS：调用计数为 1/1 且每次请求均可观察到 Rail 行为。
- FAIL：重复注册、第二次请求丢失 Rail 或重复 start 触发二次回调。
- INCONCLUSIVE：只发一个请求或只检查启动日志。
- 配置生效/下游触达证据：start 次数、回调计数、Rail init/事件计数、多次响应。
- 调用增量与唯一数据：不同请求 message ID，同一对象身份。
- 不应断言：不验收同对象并发执行。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#externalRailIsRegisteredOnceAndFiresOnRepeatedRequests`。

### FEAT-056-C10：非托管 per-task 新对象覆盖

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：测试 Library + AgentFactory
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-09/AC-09a
- Given：合规工厂能为 A/B 返回两个不同对象，回调记录 ID、对象和 conversation。
- When：创建 A/B 并分别执行任务，结束 A 后继续 B。
- Then：A/B 各扩展一次，Rail/Tool 私有状态不串用，释放 A 不影响 B。
- PASS：两个对象身份不同，各调用次数正确，A destroy 后 B 仍可执行。
- FAIL：复用对象、漏扩展、A 的释放破坏 B。
- INCONCLUSIVE：只创建对象未执行。
- 配置生效/下游触达证据：工厂 create/destroy 计数、对象 identity、回调账本、A/B 响应。
- 调用增量与唯一数据：A/B 使用唯一 conversation。
- 不应断言：本 case 不单独证明不同 conversation 的并发初始化能力；该能力由 C32 覆盖。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#perTaskObjectsAreInitializedBeforePublication`。

### FEAT-056-C11：Hosted 默认 Agent 显式 per-task

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：通用 Hosted 宿主
- 测试层次：integration
- Oracle 来源：FRS FR-09/AC-09b；Feature §5.1.2
- Given：默认 Agent 显式配对独立 AgentFactory；其他成员保持 singleton；EDPA 示例仍 singleton-only。
- When：默认 Agent 执行两个任务，另一成员执行一次。
- Then：默认 Agent 两个新对象分别扩展；另一成员复用且不串用。
- PASS：工厂对象数、回调次数和成员归属正确。
- FAIL：默认 Agent 复用、非默认 Agent 被自动切到 per-task、共享 Manager。
- INCONCLUSIVE：为了测试改用了 EDPA singleton-only 配置。
- 配置生效/下游触达证据：Hosted 配置、两个 create/destroy 记录、回调账本、另一成员请求。
- 调用增量与唯一数据：两个默认任务 ID 唯一，另一成员共享 conversation 不影响结果。
- 不应断言：不验收非默认 Agent per-task OUT。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerHostedContractTest#hostedDefaultAgentUsesItsExplicitTaskFactory`。

### FEAT-056-C12：Rail 共存与工具事件筛选

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：示例或专用 DeepAgent
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-06/AC-06
- Given：原 Agent 已有原 Rail 和至少两个工具；客户自定义器增加只筛选 `search`/`lookup_price` 的 Rail。
- When：显式触发目标工具和非目标工具。
- Then：新增 Rail 只接收目标工具事件；原 Rail 仍接收原规则事件；两者共存。
- PASS：按工具名和 `toolCallId` 配对的事件账本满足筛选条件。
- FAIL：非目标工具也命中、原 Rail 丢失、before/after 对不上。
- INCONCLUSIVE：只验证注册表存在，没有工具执行。
- 配置生效/下游触达证据：工具执行日志、原/新 Rail 事件、toolCallId。
- 调用增量与唯一数据：每次调用唯一 toolCallId。
- 不应断言：不依赖模型“是否选择”目标工具；执行请求必须可确定触发。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerOfficialExampleTest#officialExampleRailCoexistenceAndPrivateToolIsolation`。

### FEAT-056-C13：私有 Tool 资源隔离

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：两个任务实例
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-07/AC-07；L2 §2.3
- Given：同一逻辑 `apply_discount`，两个实例使用不同 `ToolCard.id`，处理器返回不同 canary。
- When：分别显式调用两实例工具。
- Then：每个实例执行自己的处理器；释放一个后另一个仍可调用。
- PASS：canary、资源 ID、对象归属和释放后调用均正确。
- FAIL：同逻辑名命中首实例处理器，或释放一个破坏另一个。
- INCONCLUSIVE：只检查工具卡片存在或未实际调用。
- 配置生效/下游触达证据：返回值 canary、ResourceMgr 日志、Ability 列表、destroy 后调用。
- 调用增量与唯一数据：每个实例唯一资源 ID 和 canary。
- 不应断言：不根据 LLM 选中概率。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerOfficialExampleTest#officialExampleRailCoexistenceAndPrivateToolIsolation`。

### FEAT-056-C14：共享 Tool 所有权释放

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：两个可共享实现的实例
- 测试层次：integration
- Oracle 来源：FRS FR-07/AC-07；L2 §2.3/§3.4
- Given：两实例注册相同 ToolCard ID、等价声明和线程安全共享实现。
- When：两实例被执行/登记后，依次释放其中一个和最后一个。
- Then：释放一个后资源仍被另一个解析；最后一个释放后全局条目移除。
- PASS：所有权计数和解析结果符合 Core 合同。
- FAIL：首个释放移除全局资源，或最后一个释放后仍残留。
- INCONCLUSIVE：只看卡片可见，不检查所有权/解析。
- 配置生效/下游触达证据：ResourceMgr 所有权日志、两次显式调用、最终资源查询。
- 调用增量与唯一数据：共享资源 ID 固定，实例 canary 唯一。
- 不应断言：不假设 Java 函数实现等价由框架自动比较。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerCoreOwnershipTest#privateAndSharedToolsFollowOwnershipAcrossInstanceRelease`。

### FEAT-056-C15：对象释放互不影响

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：A/B per-task 或 Hosted 两对象
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-13/AC-13
- Given：A/B 各注册可记录 `init/uninit` 的 Rail，A 先结束。
- When：按原工厂/所有者契约释放 A，再执行 B。
- Then：A `uninit` 和销毁契约触发，B Rail/Tool 正常；不通过再次运行 A 证明清理。
- PASS：A 清理记录 + B 新 canary 请求成功。
- FAIL：A 残留回调、B 被误清理或 B 请求失败。
- INCONCLUSIVE：只检查 destroy 被调用，不观察回调残留或 B。
- 配置生效/下游触达证据：init/uninit 计数、destroy 次数、B 响应、全局回调残留查询（若公开）。
- 调用增量与唯一数据：B canary 在 A 释放后生成。
- 不应断言：不要求自动删除持久化 checkpoint。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerCoreOwnershipTest#privateAndSharedToolsFollowOwnershipAcrossInstanceRelease`。

### FEAT-056-C16：不支持对象类型先失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：组件夹具
- 测试层次：component
- Oracle 来源：FRS FR-11/AC-11；L2 §2.2
- Given：配置自定义器，singleton/工厂分别返回非 BaseAgent/DeepAgent 对象。
- When：触发初始化。
- Then：任何自定义器调用次数为 0，初始化失败，对象不执行，错误包含业务 ID 和类型。
- PASS：失败顺序、错误字段和执行拒绝均满足。
- FAIL：先调用业务回调再失败，或对象继续执行。
- INCONCLUSIVE：只断言构造器拒绝 String，未覆盖普通 Object。
- 配置生效/下游触达证据：异常原文、回调计数、执行入口结果。
- 调用增量与唯一数据：每类错误独立对象。
- 不应断言：不定义新的网络错误码。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#unsupportedAgentFailsBeforeAnyCustomizerRuns`。

### FEAT-056-C17：非托管启动期失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管故障宿主
- 测试层次：integration startup
- Oracle 来源：FRS FR-14/AC-14a
- Given：singleton 自定义器抛指定异常。
- When：启动 Spring 应用/Handler，再尝试同步和流式入口。
- Then：默认 `init-fail-fast=true` 时应用启动失败，失败对象不可执行，同一对象不重试初始化。
- PASS：启动返回失败、后续入口拒绝、初始化计数为 1。
- FAIL：应用部分可用或失败对象被执行。
- INCONCLUSIVE：只单测 Handler.start 未观察 Spring 生命周期。
- 配置生效/下游触达证据：进程退出码/上下文异常、初始化计数、入口异常。
- 调用增量与唯一数据：独立 application context。
- 不应断言：不要求失败进程仍可提供查询端点。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#singletonFailureRejectsExecutionAndDoesNotRetryInitialization`。

### FEAT-056-C18：Hosted 启动失败与回滚

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：双成员 Hosted 故障宿主
- 测试层次：integration startup
- Oracle 来源：FRS FR-14/AC-14b；L2 §3.4
- Given：第二个成员启动期自定义器抛错，第一个成员已进入启动流程，共享资源可记录 start/stop。
- When：启动 Hosted 应用。
- Then：进程启动失败，已启动和启动中的资源被反向清理，不开放部分可用集合。
- PASS：启动失败 + 清理顺序/次数满足 FEAT-037 合同。
- FAIL：半集合可用、清理遗漏或先清后启动新资源造成残留。
- INCONCLUSIVE：无法捕获 Hosted 启停事件或资源清理结果，只能观察进程失败。
- 配置生效/下游触达证据：Hosted 启停日志、资源 start/stop、进程结果。
- 调用增量与唯一数据：失败成员 canary 唯一。
- 不应断言：不凭“进程退出”单独断言所有业务副作用均已回滚；验收依据是 Hosted 启停事件、资源清理和进程结果。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#hostedStartupFailureRollsBackAndPublishesNoPartialSet`。

### FEAT-056-C19：per-task 初始化失败清理

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：组件 + per-task 宿主
- 测试层次：component + integration
- Oracle 来源：FRS FR-15/AC-15；L2 §3.2
- Given：A 的自定义器抛错，工厂 destroy 正常；同一 conversation 后续允许重建 B。
- When：创建 A 失败后再次提交合法任务 B。
- Then：A 不执行，destroy(A) 恰好一次，占位释放，B 创建/初始化成功。
- PASS：调用增量、异常、占位释放和 B 成功全部满足。
- FAIL：A 被执行、destroy 0/2 次、占位残留或 B 无法创建。
- INCONCLUSIVE：只捕获异常，没有后续合法任务。
- 配置生效/下游触达证据：工厂 create/destroy 计数、回调账本、异常、B canary。
- 调用增量与唯一数据：A 失败 canary、B 成功 canary。
- 不应断言：不要求复用失败 Task ID。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#failedCustomizationStopsTheChainAndDestroysTheUnpublishedObject`。

### FEAT-056-C20：per-task 清理失败不掩盖原错误

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：组件夹具
- 测试层次：component
- Oracle 来源：FRS FR-15/FR-16/AC-15/AC-16
- Given：初始化异常 E1，destroy 抛 E2。
- When：acquire 失败。
- Then：对外主异常为 E1，E2 作为 suppressed/可诊断信息；占位最终移除。
- PASS：主/次异常身份与占位状态正确。
- FAIL：E2 覆盖 E1，或占位残留。
- INCONCLUSIVE：只检查日志中有两个错误，未验证异常链路。
- 配置生效/下游触达证据：异常对象 identity、suppressed、manager 状态、后续 acquire。
- 调用增量与唯一数据：E1/E2 使用唯一 message 但不输出敏感值。
- 不应断言：不要求具体异常类型之外的统一错误码。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#cleanupFailureDoesNotOverrideThePrimaryInitializationFailure`。

### FEAT-056-C21：护栏 priority 显式检查与合规共存

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：启用 Tool Guardrail 的 DeepAgent 宿主
- 测试层次：component + integration
- Oracle 来源：FRS FR-12/AC-12a/AC-12b
- Given：业务 Rail priority 低于 `RESERVED_FLOOR`，宿主启用工具护栏。
- When：显式读取并检查业务 Rail priority，再执行工具调用。
- Then：合规业务 Rail 与原护栏共同生效，原 Tool 和新增能力未被隐式替换；越界内层 Rail 不被自动拒绝是已确认边界，不写成产品拦截承诺。
- PASS：合规 priority 可检查、请求可治理、无隐式覆盖。
- FAIL：合规 Rail 被误拒、优先级别名或护栏安装成功被当成自动合规证明。
- INCONCLUSIVE：只验证对象启动成功，没有显式 priority 检查或工具触达证据。
- 配置生效/下游触达证据：guardrail enabled 状态、显式 priority、真实工具调用与审计结果。
- 调用增量与唯一数据：合规 Rail 使用唯一 canary。
- 不应断言：不要求覆盖所有内层直接注册的越界 Rail，也不把安装成功当作合规证明。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerGuardrailContractTest#compliantBusinessRailCoexistsAndItsPriorityIsCheckedExplicitly`。

### FEAT-056-C22：Hosted 启停日志字段完整、回滚可识别且不泄露敏感信息

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：Hosted 正常宿主、启动失败宿主、回滚 stop 失败宿主
- 测试层次：component log contract
- Oracle 来源：docs PR 312 FRS FR-16/AC-16；Feature §5.1.6；FEAT-037 L2 §4.6
- Given：两个托管成员；一个自定义器使第二个成员启动失败；一个成员在回滚 stop 时抛错；异常 message 带唯一敏感 canary。
- When：分别执行正常启停、启动失败回滚、回滚 stop 失败，采集 `HostedLifecycleCoordinator` 的事件、级别和异常附件。
- Then：正常启停含 `agentId/operation/result` 且 stop 为 `rollback=false`；start failure 以 ERROR 记录 type；实际执行的 stop 均有 `rollback=true`；stop 失败不阻止其他成员清理；不存在未调用操作的 success 幻影；事件无 Throwable 附件且 message 不含 canary。
- PASS：三类路径的必需字段、级别、回滚标记、继续清理和脱敏同时成立。
- FAIL：缺字段、级别错误、回滚标记错误、stop 失败阻断其他清理、出现未调用 success，或 message/异常附件泄露 canary。
- INCONCLUSIVE：无法捕获目标 Logger 或日志级别未开启。
- 配置生效/下游触达证据：真实 `HostedLifecycleCoordinator`、三组宿主事件、ILoggingEvent 级别/ThrowableProxy 和 canary 搜索结果。
- 调用增量与唯一数据：敏感 canary 仅进入异常 message，不进入允许日志字段。
- 不应断言：不把 `type` 当完整异常链，不要求程序内异常对象丢失 message，不要求未调用操作产生 success。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerHostedContractTest#hostedLifecycleLogsExposeRequiredFieldsAndHideSensitiveMessage`。

### FEAT-056-C23：重建生效且无旁挂/热加载

- 可执行性/优先级：`runnable / P1`
- 验证形态：`execution`
- 适用 SUT：二开应用
- 测试层次：package/E2E
- Oracle 来源：FRS §3.3、Feature §5.2、L2 §6.3
- Given：二开应用包含扩展并正常运行。
- When：修改扩展、重建可执行应用并重启；另尝试只替换部署目录旁的普通 JAR 后重启。
- Then：重建后的新行为生效；原库不需要重发；旁挂 JAR 不生效。
- PASS：两次启动行为与产物内容一致。
- FAIL：不重建也热加载，或重建后仍执行旧扩展，或要求改原库。
- INCONCLUSIVE：只构建未运行。
- 配置生效/下游触达证据：应用构建清单、启动日志、行为 canary。
- 调用增量与唯一数据：新扩展使用 canary。
- 不应断言：不要求运行中热替换。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerPackagingContractTest#rebuildChangesOnlyOuterApplicationJarAndSiblingJarStaysOutside`。

### FEAT-056-C32：不同 conversation 的任务对象并发初始化（替代 C24）

> C24（"业务工厂串行创建、最大并发 1"）在 PR 292 后已不再是需求：新口径要求 Runtime-ext 不额外串行化，不同 conversation 的任务对象可以并发初始化和执行。C24 的断言作为"业务自选串行"的探针保留在组件测试里，但不再计入本需求覆盖。

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 per-task 工厂 + 自定义器
- 测试层次：integration concurrency
- Oracle 来源：FRS AC-09a；L2 §3.2（Runtime-ext 不加跨对象初始化锁）
- Given：无跨对象锁的工厂；两个不同 conversation。
- When：两个线程同时 acquire；两个 `create()` 用共享栅栏对齐。
- Then：两个 create 区间**时间上重叠**（栅栏两侧都到达）；两对象各触发一次自定义器；Rail/Tool 各自可用、不串用。
- PASS：栅栏两侧均到达、各自一次、实例互不相同。
- FAIL：任一方因被串行化而超时未过栅栏；或对象/扩展串用。
- INCONCLUSIVE：无法观测 create 进入/退出时间，或只跑了一侧。
- 不应断言：不把"两个并发请求最后都成功"当并发证明；必须有重叠证据。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerConcurrentInitTest#differentConversationsCreateAndInitializeConcurrently`。

### FEAT-056-C25：断言敏感性反证

- 可执行性/优先级：`runnable / P0 control`
- 验证形态：`execution`
- 适用 SUT：全部代表路径的测试控制面
- 测试层次：control
- Oracle 来源：FEAT-031 复盘 LESSON-031-02/04；测试设计 §3.1
- Given：基础用例分别在正确配置下通过。
- When：逐类注入“自定义器不调用、Rail 不注册、目标 Tool 处理器被替换、事件不转发”的故障。
- Then：对应基础用例必须失败；不能仍因原 Tool、中间文本或对象已创建而 PASS。
- PASS：每个 P0 行为族至少有一条 mutation 使主断言 FAIL。
- FAIL：目标行为撤掉后仍 PASS，说明断言测的是前置或中间证据。
- INCONCLUSIVE：mutation 未实际运行，只有口头说明。
- 配置生效/下游触达证据：mutation diff、每族失败断言语义、原始 XML。
- 调用增量与唯一数据：注入故障使用唯一标识，不复用正确路径数据。
- 不应断言：control case 不计入产品能力 covered。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#missingRegistrationCannotSatisfyTheRailCanaryOracle`。

### FEAT-056-C26：动态能力 Rail 组合位置

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：自定义器 + 请求期 Remote Tool/SkillHub 宿主
- 测试层次：integration + E2E
- Oracle 来源：Feature §4/§5.1.1；L2 §3.1；LESSON-031-03
- Given：自定义器初始化时目标工具尚不存在；请求期安装动态 Tool；新增 Rail 按工具名筛选。
- When：通过真实请求触发动态 Tool 调用。
- Then：Rail 虽然早注册，仍能观察到后安装工具的事件；筛选结果和 toolCallId 配对正确。
- PASS：动态 Tool 的实际调用、Rail before/after 和唯一 canary 三方一致。
- FAIL：只在初始化时可见的工具被覆盖，后安装工具完全绕过 Rail。
- INCONCLUSIVE：只调用静态工具，未触发动态安装后事件。
- 配置生效/下游触达证据：安装前后工具清单、真实请求、Rail 事件、toolCallId。
- 调用增量与唯一数据：动态 Tool 使用唯一 canary。
- 不应断言：初始化时动态工具必须存在；设计明确不要求。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerCoreOwnershipTest#railRegisteredBeforeDynamicToolStillObservesLateTool`。

### FEAT-056-C27：部分注册失败后的回滚

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：两自定义器 + 可记录 Rail/Tool 清理的 Agent
- 测试层次：component + integration
- Oracle 来源：L2 §1.3/§3.4；LESSON-031-01/02
- Given：第一个自定义器成功注册 Rail 和 Tool，第二个自定义器抛错。
- When：singleton 启动或 per-task acquire 失败，再创建后续合法对象。
- Then：失败对象不执行；已注册的 Rail/Tool 跟随 destroy/uninit 清理，不污染后续对象。
- PASS：原错误保留，部分注册的 callback/资源归属不残留。
- FAIL：只断言“任务失败”，但自定义器先注册的能力仍影响后续实例。
- INCONCLUSIVE：没有独立计数，无法区分注册成功与真正清理。
- 配置生效/下游触达证据：注册/注销计数、ResourceMgr 归属、后续对象 canary、原异常。
- 调用增量与唯一数据：失败对象和后续对象资源 ID 唯一。
- 不应断言：不要求通用事务回滚外部副作用；只验证受管 callback/资源。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#failedPerTaskCustomizationDestroysObjectAndRemovesPartialRail`。

### FEAT-056-C28：对象级 Rail 与请求级 Rail 共存

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：自定义器宿主 + Runtime 请求 Rail 绑定
- 测试层次：integration + E2E
- Oracle 来源：FRS FR-13；Feature §5.1.5；LESSON-031-03
- Given：自定义器向对象注册持久 Rail；Runtime 每次请求绑定/解绑请求级 Rail。
- When：同一对象顺序执行两次不重叠请求。
- Then：对象级 Rail 在两个请求均生效；请求级 Rail 按原契约只在各请求内生效且无重复。
- PASS：两次请求的对象级 canary 均可观察，请求级回调计数符合请求边界。
- FAIL：对象级 Rail 被请求 finally 清掉，或请求级 Rail 残留到下一请求。
- INCONCLUSIVE：只执行一次请求，无法证明解绑。
- 配置生效/下游触达证据：两轮事件序列、请求 Rail 计数、对象 identity。
- 调用增量与唯一数据：两轮使用独立 request canary 和同一对象身份。
- 不应断言：不要求同对象并发执行。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerCoreOwnershipTest#objectRailSurvivesRequestScopedRailBindingAndClose`。

### FEAT-056-C29：同 conversation 并发首建

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：per-task factory + 两并发请求
- 测试层次：integration concurrency
- Oracle 来源：L2 §3.2/§8.3；LESSON-031-07
- Given：同一 conversation 没有活动对象；一个 acquire 正在进行 create/初始化。
- When：第二个同 conversation acquire 同时到达；另一个 conversation 同时 acquire。
- Then：同 conversation 只一个 create/init，第二个按 busy 语义拒绝；另一个 conversation 可独立进展。
- PASS：创建次数、busy 响应、对象提交和占位释放符合 L2。
- FAIL：重复创建、共享未初始化对象、占位残留或不同 conversation 被无关锁阻塞。
- INCONCLUSIVE：只做顺序调用，没有并发时间线。
- 配置生效/下游触达证据：并发时间线、factory 调用计数、busy code、对象映射。
- 调用增量与唯一数据：conversation ID 唯一，create 阻塞点受控。
- 不应断言：本 case 只验证同 conversation 并发首建准入；不同 conversation 的并发能力由 C32 单独验证。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerContractTest#concurrentFirstAcquireRejectsTheSameConversationAndAllowsAnother`。

### FEAT-056-C30：真实 MOA 0.1.1 外部锚

- 可执行性/优先级：`runnable / P0 external-anchor`（用户 2026-10-12 确认 C30 为门禁；已用官方 MOA 0.1.1 源码 + 外部宿主自定义器跑通，见 `evidence/runs/feat056-c30-20261012-1527/c30-evidence.json`）
- 验证形态：`execution`
- 适用 SUT：官方 `agent-solution origin/0.1.1`、`common/agents/moa-agent`
- 测试层次：integration + E2E
- Oracle 来源：开发/用户确认“通用能力”；MOA 源 revision `b67d8a66`
- Given：从官方 MOA 源构建零业务源码修改的薄 JAR，外部宿主增加 canary customizer/Rail。
- When：通过真实 MOA Agent 的公开入口执行一次；Rail 在 `beforeInvoke` 发出唯一 canary 并完成请求。
- Then：MOA 原源码无业务改动，外部 Rail 在真实 MOA 执行链中触发，公开响应/流事件可见 canary。
- PASS：MOA 源 diff 只有允许的测试构建包装或无修改；canary 来自真实执行链。
- FAIL：只能证明测试自造 DeepAgent，无法在没有改写 MOA 主源码的情况下触发。
- INCONCLUSIVE：MOA 依赖或打包无法在零业务源码修改下接入；记录具体缺口，不让通用示例 PASS 冒充 MOA 锚。
- 配置生效/下游触达证据：源 revision、diff、薄 JAR 哈希、启动日志、协议/i流 canary。
- 调用增量与唯一数据：MOA conversation 与 canary 唯一。
- 不应断言：不验收 MOA 自身业务质量，也不要求官方提供 Library JAR。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerMoaAnchorTest#moaExternalCustomizerRailFiresInRealExecutionChain`。

### FEAT-056-C31：官方示例来源保真

- 可执行性/优先级：`runnable / P0 independent`
- 验证形态：`execution + static evidence`
- 适用 SUT：`agent-solution origin/common` 的 `agentcore-instance-secondary-development`
- 测试层次：package + source audit
- Oracle 来源：官方合入 commit `d4044eeb`；LESSON-031-01/06
- Given：执行的是官方示例源码快照，不在测试侧复制或重写核心 Library/Host。
- When：构建并运行主 E2E，记录执行类与源文件对应关系。
- Then：被执行的主 Library、宿主和 Customizer 可追溯到官方 commit；测试只加控制/观测配置。
- PASS：源码路径、编译类、JAR 内容和执行日志一致，未用测试侧替代实现冒充。
- FAIL：测试实际执行的是测试侧自写 library/host，却宣称验证官方示例。
- INCONCLUSIVE：只有源码存在或构建日志，没有执行映射。
- 配置生效/下游触达证据：commit、文件 SHA-256、jar 内容、启动类映射、日志。
- 调用增量与唯一数据：使用本轮唯一 version/build ID。
- 不应断言：不证明官方示例等同生产 MOA。
- 归因记录：候选解释 → 排除依据 → 未排除项。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerPackagingContractTest#officialExampleProvenanceMatchesTheExecutedArtifacts`。

### FEAT-056-C33：`init-fail-fast=false` 下非托管初始化失败不退出进程

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 singleton 宿主（夹具 `VERIFY_DEPLOYMENT=singleton` + `mode=fail`）
- 测试层次：integration + process
- Oracle 来源：FRS FR-14/AC-14a（PR312 冻结口径）
- Given：非托管复用对象自定义器抛错；`openjiuwen.service.lifecycle.init-fail-fast=false`。
- When：启动宿主；进程起来后向入口发一次请求。
- Then：Agent 未就绪；入口明确失败（`TASK_STATE_FAILED` + `AGENT_EXECUTION_FAILED` + `Instance customization is not ready`）；失败对象不被执行；进程**不退出**。
- PASS：进程存活 + 入口明确失败 + 自定义器不再被调用。
- FAIL：进程退出、失败对象被执行、或重复初始化。
- INCONCLUSIVE：只启动未发请求。
- 不应断言：不要求失败进程仍提供查询接口（沿设计：启动失败依靠启停事件与诊断）。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#nonHostedInitFailureKeepsProcessAliveWhenFailFastIsDisabled`。

### FEAT-056-C34：`init-fail-fast=true`（默认）下非托管初始化失败使进程退出

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：同 C33，但不设 `init-fail-fast`（默认 true）
- 测试层次：integration + process
- Oracle 来源：FRS FR-14/AC-14a（PR312 冻结口径）
- Given/When：同上。
- Then：进程非零退出；日志含 `(init-fail-fast=true)`、`Application run failed`，cause 为自定义器异常；后续自定义器未被调用。
- PASS：退出码非零 + 上述日志齐全。
- FAIL：进程仍存活，或失败对象被执行。
- INCONCLUSIVE：只启动未等待退出。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#nonHostedInitFailureKillsTheProcessByDefault`。

### FEAT-056-C35：按任务初始化失败且工厂 `destroy` 也失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 + 显式 per-task 工厂（`VERIFY_AGENT_MANAGER=true`、`VERIFY_FACTORY_DESTROY_THROWS=true`）
- 测试层次：integration
- Oracle 来源：FRS AC-15/AC-16（清理失败不掩盖原错误、可诊断）
- Given：自定义器失败；工厂 `destroy` 也抛错。
- When：同一 conversation 连续两次请求。
- Then：主错误是自定义器异常；清理失败以 suppressed 出现（可诊断）；占位被清除，第二次请求仍能创建新对象。
- PASS：三条件同时成立。
- FAIL：主错误被清理错误替换；或第二次请求得到 conversation busy；或清理失败不可诊断。
- INCONCLUSIVE：日志缺 suppressed 记录。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#perTaskInitAndDestroyFailureKeepsPrimaryErrorFreesPlaceholder`。

### FEAT-056-C36：零自定义器真实链路且不要求业务 ID

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 singleton，`VERIFY_CUSTOMIZER_MODE=none`，不提供 `openjiuwen.service.agent-id`
- 测试层次：integration
- Oracle 来源：FRS FR-03/AC-03
- Given：不注册任何自定义器。
- When：启动并请求一次。
- Then：应用能启动（不要求业务 ID）；请求不因缺 ID/缺自定义器失败；日志无任何自定义器调用记录。
- PASS：启动成功 + 请求未因扩展机制失败 + 无调用记录。
- FAIL：因缺业务 ID 或"实例定制未就绪"失败；出现自定义器调用。
- INCONCLUSIVE：只启动未请求。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#zeroCustomizersKeepsOriginalBehaviourAndRequiresNoBusinessId`。

### FEAT-056-C37：真实链路不支持对象类型先失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 + per-task 工厂，`VERIFY_FACTORY_AGENT_TYPE=plain`
- 测试层次：integration
- Oracle 来源：FRS FR-11/AC-11
- Given：工厂产出非 BaseAgent/DeepAgent 的对象；配有一个自定义器。
- When：请求一次。
- Then：任务明确失败且失败点是类型检查（`requires BaseAgent or DeepAgent`）；自定义器调用次数为 0。
- PASS：任务失败 + 类型错误原文 + 自定义器 0 次。
- FAIL：自定义器先被调用；或对象被交付执行。
- INCONCLUSIVE：只看到失败未取到内层原因。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#unsupportedTaskAgentTypeFailsBeforeAnyCustomizerRuns`。

### FEAT-056-C38：真实链路按任务对象各自扩展与释放隔离

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：非托管 + per-task 工厂
- 测试层次：integration
- Oracle 来源：FRS FR-13/AC-13、FR-09a
- Given：两个不同 conversation。
- When：顺序各发一次请求。
- Then：两个任务对象各自扩展一次、实例互不相同、各自 Rail 触发、各自 destroy 且回调注销。
- PASS：计数与实例身份均符合。
- FAIL：实例复用/串用，或 Rail 未随对象释放注销。
- INCONCLUSIVE：无法区分两份日志导致重复计数（本项已因该原因假失败过一次，判据须只读文件日志）。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#perTaskObjectsGetSeparateExtensionsAndReleaseIsolation`。

### FEAT-056-C39：托管默认 Agent 按任务扩展（真实链路）

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：托管双成员 + 默认实例 `agent-a` + 显式配对 per-task 工厂
- 测试层次：integration
- Oracle 来源：FRS AC-09b（不把全局 Bean 或 EDPA singleton-only 当作已启用）
- Given：只给默认成员显式配对 `AgentInstanceManager`。
- When：向默认入口 `/a2a` 连发两次请求。
- Then：两次各创建新对象并各自扩展（实例互不相同）；非默认成员仍按自身形态启用。
- PASS：两次安装 + 两个不同按任务实例。
- FAIL：复用同一实例、或靠全局 Bean 自动切换模式。
- INCONCLUSIVE：只发一次请求。
- 自动化落点：`com.huawei.ascend.sit.cases.integration.feat056.AgentInstanceCustomizerHostedRealChainTest#hostedDefaultAgentPerTaskCustomizationOnRealChain`。

### FEAT-056-C40：反证——撤掉自定义器注册后调用判据必须失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：AgentInstanceManager + 计数自定义器
- 测试层次：component mutation control
- Oracle 来源：FEAT-031 LESSON-02
- Given：一组注册一个计数自定义器，另一组不注册。
- When：分别 acquire 新任务对象。
- Then：注册组调用次数为 1，撤掉注册后为 0。
- PASS：正向调用成立且 mutant 判据失败。
- FAIL：撤掉注册后仍有调用，说明计数判据不敏感。
- INCONCLUSIVE：两组没有使用同一可观察入口。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerMutationControlTest#customizerCallOracleFailsWhenNoCustomizerIsRegistered`。

### FEAT-056-C41：反证——撤掉 Rail 注册后 canary 判据必须失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：真实 Core callback 链 + canary Rail
- 测试层次：component mutation control
- Oracle 来源：FEAT-031 LESSON-04
- Given：一条路径注册唯一 canary Rail，另一条路径不注册。
- When：分别触发 before-invoke 回调。
- Then：注册路径命中为正，撤掉注册后命中为 0。
- PASS：Rail 事件判据对注册缺失敏感。
- FAIL：未注册仍命中，说明 canary 可能来自其他来源。
- INCONCLUSIVE：两条路径不是同一调用入口。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerMutationControlTest#railCanaryOracleFailsWhenRailIsNotRegistered`。

### FEAT-056-C42：反证——撤掉 Tool 注册后解析判据必须失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：DeepAgent 工具注册
- 测试层次：component mutation control
- Oracle 来源：FEAT-031 LESSON-02
- Given：一条路径注册唯一 Tool，另一条不注册。
- When：分别查询可解析工具数量。
- Then：注册路径为 1，撤掉注册后为 0。
- PASS：Tool 存在性判据对注册缺失敏感。
- FAIL：撤掉注册后仍可见。
- INCONCLUSIVE：查询对象不是目标 DeepAgent。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerMutationControlTest#toolRegistrationOracleFailsWhenToolIsNotRegistered`。

### FEAT-056-C43：反证——托管注册 ID 串用时各自注册名判据必须失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：HostedAgentDefinitions
- 测试层次：component mutation control
- Oracle 来源：FEAT-031 LESSON-04
- Given：正常路径注册 `agent-a` / `agent-b`；mutant 用同一 ID 登记两个成员。
- When：分别构建 Hosted definitions。
- Then：正常路径得到两个互异注册名；重复 ID 被产品拒绝。
- PASS：正常 ID 判据成立且串用 mutant 无法产生两个注册名。
- FAIL：重复 ID 被静默覆盖后仍声称两成员有效。
- INCONCLUSIVE：没有检查实际注册名集合。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerMutationControlTest#hostedEntryIdOracleFailsWhenMembersShareOneRegistrationId`。

### FEAT-056-C44：反证——撤掉销毁调用后清理判据必须失败

- 可执行性/优先级：`runnable / P0`
- 验证形态：`execution`
- 适用 SUT：AgentInstanceManager 失败初始化路径
- 测试层次：component mutation control
- Oracle 来源：FEAT-031 LESSON-02
- Given：一条路径在初始化失败后调用 destroy，另一条撤掉 destroy。
- When：分别执行失败初始化。
- Then：正常路径 destroy 次数为 1，撤掉后为 0。
- PASS：清理计数判据对 destroy 缺失敏感。
- FAIL：未调用 destroy 但计数仍为 1。
- INCONCLUSIVE：失败点未实际进入清理分支。
- 自动化落点：`com.huawei.ascend.sit.cases.component.feat056.AgentInstanceCustomizerMutationControlTest#destroyOracleFailsWhenCleanupIsNotInvoked`。

## 7. Fixture、环境与执行状态

| Fixture / 环境 | 用途 | 实现与执行状态 |
|---|---|---|
| 测试专用 Library 与第二宿主 | AC-01/02 的同版原库、不同扩展和原 JAR 哈希 | 已实现；C01/C02/C23/C31 均执行 PASS |
| Hosted 单/双成员测试宿主 | AC-04/09b/10/14b/16 | 已实现于 `agentcore-instance-customizer-verify-demo`；PR #823 |
| 故障注入自定义器 | 类型、顺序、destroy、priority、日志和失败回滚 | 已实现；组件与真实链路均执行 |
| Guardrail 启用夹具 | AC-12a/b 的合规 priority 与护栏共存 | 已实现；内层越界 Rail 按设计不作为自动拒绝承诺 |
| 真实 DeepSeek / MOA 环境 | 官方示例真实模型链路和 MOA `0.1.1` 外部锚 | 已执行；权威运行 0 skipped |

## 8. 自动化落点与执行结果

- 测试仓：`agent-runtime-acceptance`，分支最终以 `main@0f4aca5` 为准。
- Fixture：`agent-solution` 的 `agentcore-instance-customizer-verify-demo`，PR #823 当前待合入。
- `component`：Spring 装配、Hosted 生命周期、类型错误、destroy 双失败、并发初始化和 mutation 反证。
- `integration`：真实 Runtime A2A、托管/非托管启停、Rail/Tool 隔离、打包来源、MOA 外部锚和官方示例。
- 权威命令：`Run-Sit-Wsl-With-Llm.sh -Dtest='com.huawei.ascend.sit.cases.component.feat056.*Test,com.huawei.ascend.sit.cases.integration.feat056.*Test' test`。
- 权威结果：47 PASS、0 failure、0 error、0 skipped；mutation 5 条另在独立 run 复跑 PASS。
- 证据：`evidence/runs/feat056-acceptance-20261015-1033/` 与 `evidence/runs/feat056-acceptance-mutation-20261015-1042/`。

## 9. 状态汇总与退出标准

- 覆盖台账：`FEAT-056-review-deliverables.json`。
- 逐行结论：27 covered + 5 out-of-scope，无 degraded/unassigned。
- 验收门禁：`coverage_blocking=0`，`closure_eligible=True`，特性测试状态 `closed`。
- 退出标准已满足：范围内条目均有本轮真实 XML 与行为证据，原 C18/C22/C24 缺口均已关闭。

## 10. 负结论归因记录

当前无需登记“非产品问题/范围外/不提 ISSUE”的额外负结论；范围外项由 FRS/Feature 明确裁决并已在覆盖台账标记 `out-of-scope`。

## 最小可执行例子

### 机制 A：真实二开应用 A2A

- 输入声明：Library JAR SHA-256 + 二开应用请求 JSON + 真实模型环境。
- 实际请求：A2A `SendStreamingMessage`，要求模型确定触发原 `lookup_price` 和新增 `apply_customer_discount`。
- 入口与必要资源：宿主应用端口、DeepSeek 凭据、可写 workspace。
- 可观察输出：SSE 完成状态、工具日志、Rail 事件账本、原/新 Tool 结果。
- 证据落点：`evidence/<run-id>/` 的请求、SSE、日志、产物哈希、报告。
- 代表性：实现仓 `agentcore-instance-secondary-development` 是真实通用 Library+宿主+Core/Runtime 组合；MOA `0.1.1` 另作真实使用方旁证，其业务编排差异不改写本期通用生命周期 Oracle。
- 故障与主断言：若自定义器未在真实对象执行前注册 Rail/Tool，则协议响应、工具日志和 Rail 事件三者无法同时出现预期 canary，主断言应失败。

### 机制 B：故障注入组件/集成

- 输入声明：测试自定义器、抛错工厂、BaseAgent/DeepAgent/Object 对象、Hosted definitions。
- 实际执行：直接调用公开 Handler/Manager 入口或启动 Spring 测试上下文。
- 入口与必要资源：测试类路径、隔离日志、JUnit 5 参数。
- 可观察输出：异常身份、create/destroy/回调计数、上下文启动结果、日志字段。
- 证据落点：Surefire XML 与独立行为证据。
- 代表性：使用真实 `JiuwenCoreAgentExtHandler` 和 `AgentInstanceManager`，只替换测试对象/故障回调；不能代表真实 LLM 业务结果。
- 故障与主断言：若失败对象仍被执行、destroy 次数错误或原异常被覆盖，相应计数/异常断言应失败。

### 机制 C：不同任务对象并发初始化探针

- 输入声明：两个 conversation、受控工厂、create/initializer 时间线记录。
- 实际执行：两个 conversation 并发 acquire，栅栏证明两个 create 同时进入工厂。
- 入口与必要资源：`AgentInstanceManager` + 合规工厂。
- 可观察输出：最大并发 create 数、对象身份和自定义器调用增量。
- 证据落点：并发时间线截图/XML 与组件行为证据。
- 代表性：验证 PR292/PR312 的最终口径，即 Runtime-ext 不额外串行化不同任务对象。
- 故障与主断言：若不同对象被额外串行化，最大并发数小于 2，C32 必须失败。
