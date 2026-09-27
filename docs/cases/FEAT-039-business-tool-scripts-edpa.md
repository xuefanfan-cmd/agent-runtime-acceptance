---
用例编号: FEAT-039-business-tool-scripts-edpa
测试标题: 业务扩展工具话术（EDPA）——三件套装配、存量三工具回归、description_keywords 细分、双发为零与 SPI 生命周期
story: S1
优先级: P0
自动化状态: IMPLEMENTED
适用环境: openjiuwen
作者: TBD
创建日期: 2026-09-20
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, tscript, feat-039]
---

# FEAT-039-business-tool-scripts-edpa — 业务扩展工具话术（EDPA）

> **一句话总结**：EDPA 默认接入通用话术能力，让新工具按「SPI 声明 + `actrule.allowed_tools` + 卡片话术」
> 三件套即可发射话术；存量三个工具（`call_mcp` / `call_versatile` / `call_subagent`）的话术链路行为保持不变，
> 同一句话术不被新旧两条链路重复发送，存量卡片误配话术时装配期强校验失败。
>
> **本轮范围**：FEAT-039 §2 全部 MUST、§3 入口与 §3.1 配置格式、§4 场景表、§5 行为语义与非功能、
> §7 验收要求，共 35 条用例（C01~C35）。其中 C14/C15 因「黑盒不可构造」记 `blocked`
> （守卫覆盖责任交回产品侧确定性单测），C19 维持 `deferred`（无深度定制入口 fixture）。
>
> **机制一句话**：EDPA 启动装配时按 SPI 发现 Provider、按 `allowed_tools` 决定注册集合，
> 注册后的工具由通用话术能力按卡片配置发射话术；存量三工具仍由存量链路发射，
> 两条链路工具集合互不相交，因此不存在重复发射。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-core-ext-react-rails** | 机制提供方 | 通用话术解析与发射（与 FEAT-038 同一能力，本文不重复断言其内部语义） |
| **载体层 · agent-solution（edp-agent-java）** | 机制触发载体 | EDPA 装配（Provider 发现、`allowed_tools` 注册、强校验 fail-fast）、存量话术链路（`scriptconfig` 配置解析与关键字细分） |
| **测试数据层** | 探针与场景 | `tscript_probe` 与多类 SPI 变体 JAR、场景配置变体（`default`、`coverage-probe`、`legacy-downstream`、`missing-*`、`invalid-keyword*`、`builtin-collision`、`allowed-deduplicate`、`skill-isolation` 等；原 `wealth-keywords` 因含客户侧资产已于 2026-09-20 整场景移出，见 C28 说明） |

## 关联特性

- **FEAT-038**：通用话术能力自身语义（优先级链路、事件字段、变量替换、容错）由 FEAT-038 记账，本文只引用。
- **存量链路上下文**：`call_mcp` / `call_versatile` / `call_subagent` 的既有话术机制（设计文档中的存量条目），
  本文只断言引入本特性后行为不退化。

## 关联架构约束 / FEAT-039 事实要求

- §2：存量事件链路只接管三个固定工具；EDPA 默认启用通用能力、客户无需重复启用；
  三件套缺一即「不注册」或「注册但静默」；存量卡片误配 `tool_scripts` / `ui_notice` 时装配期强校验阻止启动；
  新旧链路分治、同一调用零重复发射；话术发射不引入额外大模型调用。
- §3.1：`description_keywords` 为 intent 域内关键字细分话术，声明顺序首个命中生效；
  关键字条目可只配 `tool_start` 或 `tool_end`，缺省侧回落该 intent 默认；关键字仅在本 intent 域内生效。
- §4：SPI 加载异常跳过不扩散；Provider 无效变体在迭代仍可推进时逐个跳过；无法前进时有界终止并诊断。
- §5.1：`tool_end` 优先级为 `ui_notice` > `description_keywords` > intent 默认 > `general_scripts`；
  §5.3：多工具并发独立发射。
- L2（已合并，frontmatter 仍为 `draft`）§2.2 每个装配批次重新发现并产生独立的 Provider/Tool 实例，
  已装配 Agent 不因后续发现集合变化自动更新；§4.2 ServiceLoader 无法前进时终止且有诊断；
  §6.1 `allowed_tools` 保序去重并跳过未知项；§6.2 非法 `description_keywords` fail-fast；
  §4.5 typed 配置按 intent 整体合并，Skill 扁平话术不参与 typed 合并。

## 前置条件

1. `02_ForkCode/agent-solution` 的 `common/example/tool-scripts-sit-demo` 已构建并 install 至共享 Maven 仓库；
   SPI 变体（重名、构建失败、空名称、迭代不可推进等）随该 fixture 提供。
2. `src/test/resources/application-openjiuwen.yml` 已声明 `tool-scripts-edpa` 与
   `tool-scripts-edpa-malformed-provider` 坐标，并以 `-Dtest.env=openjiuwen` 选择 profile。
3. 场景切换方式：`EDP_AGENT_SCENARIO_HOME`（场景目录）、`TSCRIPT_PROVIDER_MODE`（Provider 变体模式）；
   破坏性装配用例（fail-fast 类）必须用独立装配实例，避免污染常驻 SUT。
4. 存量下游可控：场景 `legacy-downstream` 以 `call_mcp` 的 `script_command` 指向 `stubs/legacy_downstream_stub.sh`，
   其标准输出即工具结果，从而在不修改产品代码的前提下控制返回体。
5. 跨模块流式用例（C29）需要运行时/网关/客户端可达链路：`registry-center` +
   `gateway.path-mode=direct` 的网关拓扑，且网关创建请求必须显式携带 `params.metadata.agentId`。
6. 真实模型可达；每条用例使用唯一租户/任务/会话标识与 canary。

---

## 一、新工具主链路与三件套（C01~C04）

**测试数据**：`tscript_probe` 完成 SPI 声明、`allowed_tools` 声明与卡片话术；`missing-spi` / `missing-allowed` /
无卡片话术三类缺项场景。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C01 | PASS / 高 | 三件套齐备（SPI + `allowed_tools` + 卡片意图段/默认段） → 以 `biz_type=财报` 调用 → 前后话术按意图段发射、字段齐全、走 `OutputSchema("custom")` 包装；失败原因若在通用能力解析则交 FEAT-038 记账 | 未注册、未发射或字段/包装不符 | `ToolScriptEdpaAssemblyTest#completeRegistrationTrioEnablesNewToolScripts`（ts039.newtool-emit） |
| TS039-C02 | PASS / 高 | 场景声明了工具名与卡片话术但**缺 SPI 构建器** → 启动并调用 → 工具未注册、对模型不可见、无话术事件、启动无异常 | 工具仍被注册或启动异常 | `ToolScriptMissingSpiTest#allowedNameWithoutProviderRemainsUnavailable`（ts039.trio-missing-spi） |
| TS039-C03 | PASS / 高 | SPI 已声明但场景 `allowed_tools` **未列入**该工具 → 启动并调用 → 不注册、不可见、不发射、无异常 | 工具仍可见或发射话术 | `ToolScriptMissingAllowedTest#providerExcludedByAllowedToolsRemainsUnavailable`（ts039.trio-missing-allowedtools） |
| TS039-C04 | PASS / 高 | SPI 与 `allowed_tools` 齐备但卡片**无 `tool_scripts`** → 调用 → 工具注册且可调用，通用能力静默不发射（等价 FEAT-038-C11 语义） | 发射话术或工具不可调用 | `ToolScriptEdpaAssemblyTest#registeredToolWithoutScriptsRemainsSilent`（ts039.trio-missing-scripts） |

## 二、存量三工具话术回归（C05~C08）

**测试数据**：场景 `default` 的 `scriptconfig.yaml` 存量配置（intent 默认话术、`general_scripts` 兜底链），
存量三工具由产品内置提供；未配置关键字的 intent 作为对照。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C05 | PASS / 高 | 存量 `call_mcp` 配置 intent 默认话术、未配关键字 → 经存量链路调用 → 话术内容与配置一致，且由存量链路发射（通用能力对该卡片零命中） | 话术内容与配置不符、缺事件或出现双发 | `ToolScriptLegacyKeywordTest#callMcpRetainsConfiguredLegacyScripts`（ts039.legacy-mcp） |
| TS039-C06 | PASS / 高 | 同 C05，工具为 `call_versatile` → 话术与配置一致、无新增链路参与 | 同上 | `ToolScriptLegacyKeywordTest#callVersatileRetainsConfiguredLegacyScripts`（ts039.legacy-versatile） |
| TS039-C07 | PASS / 高 | 同 C05，工具为 `call_subagent` → 话术与配置一致、无新增链路参与 | 同上 | `ToolScriptLegacyKeywordTest#callSubagentRetainsConfiguredLegacyScripts`（ts039.legacy-subagent） |
| TS039-C08 | PASS / 高 | intent 未配置 `description_keywords` → 调用 → 行为与本特性引入前的默认链路一致（intent 默认 → `general_scripts` 兜底），无关键字参与痕迹 | 关键字机制意外介入或兜底链缺失 | `ToolScriptLegacyKeywordTest#intentWithoutKeywordsUsesDefaults`（ts039.legacy-nokeyword-parity） |

## 三、`description_keywords` 细分话术与配置边界（C09~C13、C31~C34）

**测试数据**：`scriptconfig.yaml` 关键字变体（命中/未命中/缺省字段/跨 intent/场景级覆盖/非法配置）；
`legacy-downstream` 场景与可控存量下游 stub；`skill-isolation` 场景。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C09 | PASS / 高 | intent 配两个均可命中的关键字条目（声明顺序 k1、k2），`query_description` 同时含两者 → 取声明顺序首个（k1）的话术；`tool_end` 仍低于 `ui_notice` 优先 | 命中后一个、两条叠加或次序不确定 | `ToolScriptLegacyKeywordTest#firstMatchingKeywordWins`（ts039.keyword-hit-order） |
| TS039-C10 | PASS / 高 | 配置了关键字但 `query_description` 均不含 → 回落该 intent 默认话术 | 命中错误条目或不发射 | `ToolScriptLegacyKeywordTest#keywordMissFallsBackToIntentDefaults`（ts039.keyword-miss-fallback） |
| TS039-C11 | PASS / 中 | intent 完全没有关键字字段 → 走默认链路且无配置错误表面 | 启动或调用期出现配置错误 | `ToolScriptLegacyKeywordTest#absentKeywordListHasNoErrorSurface`（ts039.keyword-absent-ok） |
| TS039-C12 | PASS / 高 | 框架级对 intent A 配关键字集 `{k1}`、场景级对同一 intent 配 `{k2}` 且未提及 intent B → A 只按场景级 `{k2}` 匹配（非字段级叠加，`k1` 不生效），B 保留框架级行为 | 字段级合并、`k1` 仍生效或 B 丢失 | `ToolScriptScenarioOverrideTest#scenarioIntentReplacementDoesNotFieldMerge`（ts039.keyword-scene-override） |
| TS039-C13 | PASS / 高 | `query_description` 含唯一 canary 标记串 → 任何话术事件 `content` 均不含该标记串，前端可见话术只来自配置文案 | 任一事件含 canary 原文 | `ToolScriptLegacyKeywordTest#rawQueryDescriptionNeverBecomesScriptContent`（ts039.keyword-no-raw-leak） |
| TS039-C31 | PASS / 中 | intent 默认齐全；关键字条目只配 `keyword` 与 `tool_start`（缺 `tool_end`），且描述命中该 keyword → `tool_start` 取条目值，`tool_end` 回落 intent 默认 | 缺省字段被置空、取错条目或抛异常 | `ToolScriptLegacyKeywordTest#missingKeywordEndFallsBackToIntentEnd`（ts039.keyword-partial-field-fallback） |
| TS039-C32 | PASS / 中 | intent A 配 `kA`、intent B 配 `kB`；实际调用 `query_intent=A` 且描述只含 `kB` → 不得命中 B 的关键字，走 A 的默认话术 | 跨 intent 命中 `kB` | `ToolScriptLegacyKeywordTest#keywordCannotMatchAcrossIntents`（ts039.keyword-intent-scope） |
| TS039-C33 | PASS / 高 | intent 默认与关键字条目齐备，工具返回 `ui_notice{event:tool_end,key:legacy_dynamic}` 且描述同时命中关键字 → `tool_start` 取关键字条目话术，`tool_end` 取 `ui_notice` 话术并覆盖关键字条目话术 | 关键字或 intent 文案覆盖 `ui_notice` | `ToolScriptLegacyDownstreamTest#legacyUiNoticeOverridesKeywordScript`（ts039.uinotice-over-keyword） |
| TS039-C34 | PASS / 中 | `call_versatile` 的 description 由 Rail 动态注入（含关键字与 canary），intent 配对应关键字条目 → `tool_end` 逐字等于关键字条目文案（证明生效描述参与了匹配），且 `query_description` 原文不外泄；描述未注入时会回落 intent 默认并使断言失败 | 命中不到关键字条目或注入原文出现在事件内容中 | `ToolScriptLegacyDownstreamTest#railInjectedDescriptionNeverReachesContent`（ts039.dynamic-description-keyword） |

## 四、双发为零、字段兼容与并发（C16、C17、C20）

**测试数据**：新工具与存量三工具同批执行的场景；并发同名/不同名工具调用。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C16 | PASS / 高 | 新工具与存量三工具同批执行，断言按用例执行前后增量 → 存量调用的话术全部符合存量链路语义，通用能力对存量卡片零命中，新工具话术全部来自卡片配置；任一工具都不同时出现两条链路的话术事件 | 出现重复发射或链路归属错乱 | `ToolScriptEdpaAssemblyTest#cardDrivenToolDoesNotEmitThroughBothScriptPaths`（ts039.no-dual-emit） |
| TS039-C17 | PASS / 高 | 存量工具与新工具各发一次话术 → 两组事件都含 `event/tool/content/timestamp/conversation_id` 同一组字段、都是 `OutputSchema("custom")` 包装、存量消费形态可解析 | 字段组不一致或包装不可解析 | `ToolScriptEdpaAssemblyTest#newAndLegacyEventsRetainRequiredFields`（ts039.field-parity，contract 层） |
| TS039-C20 | PASS / 高 | 同名与不同名带话术工具并发执行 → 新工具事件按 `tool_call_id` 配对，存量事件仍兼容无该字段，各调用无串话术、无丢失 | 事件串写、丢失或 ID 错配 | `ToolScriptEdpaAssemblyTest#concurrentDistinctToolsDoNotMixScripts`（ts039.concurrency-isolation） |

## 五、SPI 生命周期、重名与 `allowed_tools` 处理（C18、C21~C25、C35）

**测试数据**：多类 SPI 变体 JAR（构建失败、与内置同名、两个外部同名、无效变体、malformed descriptor）、
`allowed-deduplicate` 场景、单例与 per-task 两个装配入口。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C18 | PASS / 高 | 一个 Provider 构建失败（异常矩阵含 `IllegalStateException` 与非白名单运行时异常），其后仍有正常工具 → 失败工具被跳过（不可见、不发话术）、告警可见，其它工具正常注册 | 失败扩散导致后续工具不可用 | `ToolScriptProviderLifecycleTest#providerBuildFailureDoesNotHideLaterProvider`（ts039.spi-load-failure） |
| TS039-C21 | PASS / 高 | 外部 Provider 与内置工具同名，另有唯一名称的正常 Provider → 重名外部项被跳过并告警，内置工具正常构建，正常外部工具不受影响 | 外部覆盖内置、整体加载中断或出现双实例 | `ToolScriptProviderLifecycleTest#providerCannotReplaceBuiltinTool`（ts039.spi-builtin-collision） |
| TS039-C22 | PASS / 高 | 两个外部 Provider 的 trim 后工具名相同 → 注册表首次加载确定性失败，错误含 `EDPA-DUPLICATE-TOOL-PROVIDER`，不依赖发现顺序任取其一 | 静默覆盖、任取一个或错误标识缺失 | `ToolScriptProviderLifecycleTest#duplicateExternalProvidersFailStartup`（ts039.spi-external-duplicate） |
| TS039-C23 | PASS / 中 | 逐个构造无效变体（`next()` 抛错、空名称、工具名异常、构建异常/返回 `null`、空卡片、卡片名不一致，含自定义运行时异常），其后保留合法 Provider → 只要迭代仍能推进，每个坏项被隔离并告警，坏项零注册、合法尾项仍可用 | 合法尾项被可恢复错误阻断或异常扩散 | `ToolScriptProviderLifecycleTest#invalidProviderVariantsDoNotHideTailProvider`（ts039.spi-invalid-isolation） |
| TS039-C24 | PASS / 中 | `allowed_tools` 含重复新工具、未知名称、宿主原生工具与自动工具 → 新工具按首次出现只构建/注册一次，未知项跳过并告警，原生/自动工具仍由原机制管理，启动成功 | 重复构建/注册或未知项导致启动失败 | `ToolScriptProviderLifecycleTest#allowedToolsAreDeduplicatedBeforeBuild`（ts039.allowed-deduplicate） |
| TS039-C25 | PASS / 高 | 同一配置分别装配单例 Agent 与 per-task Agent → 两个入口都默认注册话术能力与业务工具、发出相同语义话术；每个装配批次重新发现并实例化 Provider（得到独立工具实例），已装配 Agent 不因后续发现集合变化自动更新 | 任一入口缺能力、跨批次复用实例或旧 Agent 热更新 | `ToolScriptEdpaAssemblyTest#everyTaskReceivesFreshScriptedToolInstances`（ts039.singleton-pertask-parity） |
| TS039-C35 | PASS / 高 | malformed service descriptor 使迭代器 `hasNext()` 抛 `ServiceConfigurationError`，错误前已有一个可发现 Provider、错误后另有尾项 → 记录稳定诊断后终止本批次发现，已发现项保持、进程不挂死、不无界重试 | 无限循环、异常无诊断外抛或已发现项被错误清空 | `ToolScriptProviderLifecycleTest#malformedProviderDiscoveryTerminatesWithDiagnostics`（ts039.spi-iterator-stop） |

## 六、装配期校验与 fail-fast（C14、C15、C26、C27、C30）

**测试数据**：非法 `description_keywords` 变体场景（类型错误、元素非 Map、keyword 非字符串/trim 后为空/同 intent 重复、
`tool_start`/`tool_end` 非字符串、合法空列表对照）；`skill-isolation` 场景；外部 Provider 重名场景（装配失败触发）。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C14 | blocked / 高 | 存量卡片误配 `tool_scripts` → 期望装配期 fail-fast 且零注册副作用 | **当前不可构造**：同名外部 Provider 被内置静默遮蔽、内置三工具卡片无话术属性写入点、场景配置只进 `SysTemplateConfig` 类不写卡片属性；本场景不改写为 PASS/FAIL，守卫覆盖交回产品侧确定性单测 | 无自动化（不可构造证据已留档）；Story ts039.dualguard-toolscripts |
| TS039-C15 | blocked / 高 | 存量卡片误配 `ui_notice` → 同 C14 | 同 C14 | 无自动化（不可构造证据已留档）；Story ts039.dualguard-uinotice |
| TS039-C26 | PASS / 高 | 参数化覆盖非法 `description_keywords` 各变体与合法空列表 → 非法变体加载失败且错误含 `EDPA-INVALID-DESCRIPTION-KEYWORD`；合法空列表冻结为空并走 intent 默认 | 非法配置进入运行期，或空列表被误判非法 | `ToolScriptProviderLifecycleTest#descriptionKeywordConfigurationIsValidated`（ts039.keyword-invalid-config，参数化） |
| TS039-C27 | PASS / 中 | framework 定义 intent A/B，场景整体替换 A，Skill 层再声明 A 的扁平模板 → typed 快照中 A 只取场景完整对象、B 保留 framework，Skill 不参与 typed 合并且既有扁平能力不退化 | 字段级合并、Skill 覆盖 typed 值或 B 丢失 | `ToolScriptSkillIsolationTest#skillFlatScriptsDoNotOverwriteTypedIntentSnapshots`（ts039.typed-merge-skill-isolation） |
| TS039-C30 | PASS / 高 | 装配期以可达触发（外部 Provider 重名，`TSCRIPT_PROVIDER_MODE=external-duplicate`）失败 → 失败标识可识别（`EDPA-DUPLICATE-TOOL-PROVIDER`）、日志显示 Provider 发现已开始，但没有任何工具被构建（零部分注册） | 出现部分注册/构建，或失败不可识别 | `ToolScriptProviderLifecycleTest#assemblyFailureLeavesNoPartialRegistration`（ts039.dualguard-no-side-effects） |

> C14/C15 的原始验收语义是「存量卡片误配话术 → 装配期 fail-fast 且零注册副作用」。
> 黑盒不可构造后，其 Oracle 本体由 C30 以**可达触发**保留（装配期校验失败后零部分注册），
> 但 C30 的触发条件与设计原文不同，仅 Oracle 语义等价；卡片的守卫覆盖仍由产品侧负责。

## 七、跨模块流式契约（C29）

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C29 | PASS / 高 | 运行时/网关/客户端可达拓扑：向网关 `/a2a` 发一次流式请求（显式携带 `metadata.agentId`） → 流式帧中 `type=custom` 的信封五字段齐全、`content` 等于卡片配置文案且不含 canary，网关按 direct 模式原样透传 `custom` 包装；网关未返回 `200 + text/event-stream` 或宿主未实际执行工具时按 `INCONCLUSIVE` 门禁中止，不产生 PASS/FAIL | 事件在网关链路丢失、被改写或字段缺失 | `cases/e2e/tscript/ToolScriptGatewayStreamingContractIT#scriptEventsSurviveGatewayStreaming`（ts039.downstream-contract，Failsafe e2e 层） |

## 八、交付场景配置运行时生效（C28，裁决：不交付）

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS039-C28 a~d | blocked（不交付）/ 高 | 该用例的 Given 依赖开发交付的真实场景配置，其期望值逐字取自交付文案 → 按「客户侧资产不入仓」裁决，场景与用例均不交付：不重建等价场景，也不提交引用交付文案的测试代码 | 该用例不作为本期能力覆盖，不参与 PASS/FAIL 判定；历史 PASS 只对当时场景成立 | 无自动化（原引用该场景的测试代码已移出仓工作树，未提交） |

> 口径说明：本用例原口径为「存量硬编码迁移前后输出一致」，2026-09-15 依开发交付包的反馈改为
> 「按交付配置生效」，旧硬编码 golden 不再作为等价基准；该口径变更同时影响迁移等价这一旧验收项，
> 权威文档侧的迁移映射与验收行需由设计/开发同步刷新。
>
> 客户侧资产处置（2026-09-20 裁决）：交付包及其在 fixture 中的逐字副本属于客户侧资产，**不得进入任何仓**，
> 已整包移出仓库工作树（现位于工作区非仓目录 `02-Code/customerTest/feat039-scenarios-delivery-20260915/`）；
> 本用例**不重建场景、不交付测试代码**，按 `blocked（客户资产不入仓）` 记账，
> 2026-09-15 的 PASS 仅是该场景存在时的历史结论，不作为当前能力覆盖依据。
> 本用例也不承诺内部工具或下游副作用的 exactly-once、跨进程帧序，以及生产实际部署配置与交付包逐字节一致（后者只能由交付方确认）。

## 九、深度定制入口（C19，范围决定项）

| 用例 ID | 状态 | 场景 | 处置 |
|---|---|---|---|
| TS039-C19 | deferred / 中 | 经深度定制入口注册带话术卡片的工具，能力默认生效且无需重复启用 | 本期不新增第二宿主 fixture（范围裁决），维持 `deferred`，不作为已覆盖能力 |

## 自动化落点汇总

| 测试类 | 层次 | 覆盖用例 | 最近一轮 testcase 数 |
|---|---|---|---|
| `cases/integration/tscript/ToolScriptEdpaAssemblyTest` | integration | C01、C04、C16、C17、C20、C25 | 6 |
| `cases/integration/tscript/ToolScriptLegacyKeywordTest` | integration | C05~C11、C13、C31、C32 | 10 |
| `cases/integration/tscript/ToolScriptLegacyDownstreamTest` | integration | C33、C34 | 2 |
| `cases/integration/tscript/ToolScriptProviderLifecycleTest` | integration | C18、C21~C24、C26、C30、C35 | 15（含 C26 参数化变体） |
| `cases/integration/tscript/ToolScriptScenarioOverrideTest` | integration | C12 | 1 |
| `cases/integration/tscript/ToolScriptSkillIsolationTest` | integration | C27 | 1 |
| `cases/integration/tscript/ToolScriptMissingSpiTest` | integration | C02 | 1 |
| `cases/integration/tscript/ToolScriptMissingAllowedTest` | integration | C03 | 1 |
| `cases/e2e/tscript/ToolScriptGatewayStreamingContractIT` | e2e（Failsafe） | C29 | 1 |

> C28 无自动化落点：其场景与测试代码因含客户侧资产而不交付（见第八节），不计入上表。

Allure 标注：类级 `@Feature("FEAT-039: 业务扩展工具话术（EDPA）")`；方法级 `@Story("TS039-Cxx …")`；
Surefire/Failsafe 选择标签 `integration` / `e2e`、`tscript`、`feat-039`。

## 执行与结论

- 执行分组：G1 行为组（新工具发射、存量回归、关键字、双发为零、并发）→ G2 独立装配与 fail-fast 组（破坏性用例独立装配实例）
  → G3 外部依赖组（C29 网关流式；C28 已按不交付裁决退出执行范围）。
- 最近一轮全量：Run `feat-038-039-20260917-105053` 覆盖 63 个 testcase（FEAT-039 组 37 个），
  `selected 63 / executed 63 / PASS 63 / FAIL 0 / ERROR 0 / skipped 0`；C29 单独 Run `…-111506` 1/1 PASS。
- 缺口（不视为通过）：TS039-C14/C15 `blocked`（黑盒不可构造，守卫覆盖交回产品侧确定性单测）、
  TS039-C19 `deferred`（无深度定制入口 fixture）、TS039-C28 `blocked`（客户资产不入仓，不重建、不交付）。
- 说明：上述全量结果来自被测制品为**候选/最新代码组合**的轮次；正式发布制品确定后需按受影响范围复跑，
  本文不把候选轮结果升级为发布基线结论。

## 不覆盖

| 内容 | 依据 |
|---|---|
| 存量话术迁移到 `ToolCard.properties` | §2 明示 OUT |
| AG-UI 协议映射、话术国际化 | §5.2 明确不承诺 |
| 存量工具新增意图维度话术（`intent_field` 式） | §5.2 明确不承诺，存量语义冻结 |
| 客户绕过框架装配自建存量卡片 | §5.2 强校验范围外，仅文档约束 |
| 存量工具的业务结果正确性 | 本文只断言话术面 |
| 内部工具或下游副作用的 exactly-once、跨进程帧序 | 无权威承诺，且黑盒无可稳定计数证据 |
| 生产实际部署配置与交付包逐字节一致 | 只能由交付方确认，测试侧不代为承诺 |
