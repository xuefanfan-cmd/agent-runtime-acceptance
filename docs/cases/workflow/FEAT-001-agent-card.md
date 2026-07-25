---
用例编号: FEAT-001-agent-card
测试标题: Agent Card 发布机制——agent-runtime-java 标准化服务入口发现能力（近端 workflow / 远端 versatile 双载体）
story: 横向 + S3
优先级: P0
自动化状态: PARTIAL（远端 versatile edpa-adapter ✅ READY；近端 workflow expense-review-workflow ⬜ PENDING 待补钉类）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [component, smoke, workflowagent, feat-001]
---

# FEAT-001-agent-card — Agent Card 发布机制

> **机制一句话**：Agent Card 发布是 **agent-runtime-java 提供的标准化服务入口发现能力**——
> `AgentCardController` 在每个 agent 启动时发布 `/.well-known/agent.json`（及别名
> `/.well-known/agent-card.json`），声明 identity / capabilities / skills / 接口契约。
> 本用例验证的是**这套发布机制**：近端 workflow agent 与远端 versatile 两种 agent-solution 实现都经
> 同一机制暴露 card，差异仅在 agent 自身的 identity 与主 skill。报销审核、银行代理等业务只是各载体
> agent 的实现逻辑（测试数据），不进入本用例的断言焦点。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | `AgentCardController` 发布 card：双入口等价、capabilities 反映真实能力、skills 声明、JSONRPC 接口绑定 `/a2a` |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow agent（`expense-review-workflow`，直连）/ 远端 versatile（`edpa-adapter`，经 gateway） |
| **测试数据层** | 载体 agent 的实现逻辑 | 各 agent 的 identity（name/version）与主 skill（`review_expense` / `versatile-bank-proxy`） |

## 关联特性

- **FEAT-001（标准化智能体服务入口）**：§2「A2A Agent Card 发现 / capabilities / skills」+ §3「`/.well-known/agent-card.json` 与 `/.well-known/agent.json` 双入口」+ §5.1.1「capabilities 必须反映当前版本对外承诺，不得夸大未激活能力」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §2 / §3：Agent Card 双入口发现、capabilities、skills 声明真实性（机制能力）。
- FEAT-001 §5.1.1：capabilities 不得夸大未激活能力（story 3 webhook 未落地 ⇒ `pushNotifications` 必须为 false——这是机制层的诚实性约束）。
- FEAT-001 §1 覆盖矩阵：`FEAT-001.wf.agent-card` / `wf.agent-card-capabilities` / `wf.agent-card-skills` / `wf.webhook-not-advertised` 四行合并至本用例。

## 前置条件

1. 被测 jar 就绪：`edpa-adapter`（✅ 已构建，远端 versatile 载体）、`expense-review-workflow:0.2.0-SNAPSHOT`（⬜ 近端 workflow 载体，补钉类落地后纳入）。
2. `-Dtest.env=openjiuwen`（解析 `SAA_*` 占位符；card 发现本身不消耗 LLM）。
3. edpa-adapter 栈：envexplorer 容器由 YAML service-bindings 自动拉起（**card 发现不依赖其可达**）。
4. 测试执行机 Docker 可用（envexplorer 容器）。
5. 无跨用例依赖：read-only 探测，不依赖任何其他 TC 的执行结果。

## 测试数据

- 契约数据（dotted-path → 期望值，钉死机制输出）：[edpa-adapter-card-assertions.json](../../../src/test/resources/testdata/component/workflow_agent/edpa-adapter-card-assertions.json)；近端 workflow 补钉类落地后新增 `expense-review-workflow-card-assertions.json`（同结构，换 name/skills）。
- SDK required 字段集（跨载体复用，机制级）：[a02-agent-card-required-fields.json](../../../src/test/resources/testdata/component/protocol/a02-agent-card-required-fields.json)。
- 无业务场景文本；card 是机制静态发布物，输入仅「对一个就绪 agent 发 GET」。

## 机制载体表（同一发布机制，两类 agent 实现）

> 按「发布机制步骤 90% 一致、仅载体 agent 不同」原则合并；下表是唯一差异面，机制断言（A–G）对所有行同构成立。

| 载体 agent | 载体类型 | name | 主 skill（skills[].id） | 落点测试类 | 状态 |
|---|---|---|---|---|---|
| 远端 versatile — `edpa-adapter` | agent-solution · 经 gateway | `versatile-adapter` | `versatile-bank-proxy` | [EdpaAdapterCardDiscoveryTest](../../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java) | ✅ READY |
| 近端 workflow — `expense-review-workflow` | agent-solution · 直连 | `expense-review-workflow`（实跑校准后钉死） | `review_expense` | ⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（待新建） | ⬜ PENDING |

## 测试步骤

> 核心步骤只写一遍，对「机制载体表」每一行各跑一次。

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | `SutStack.builder(config).agent(<载体agent>)`，类级 `@BeforeAll` 构栈 | `SutStack` | 单 agent 栈就绪（远端载体的 envexplorer 由 service-bindings 自动拉起） |
| 2 | `GET /.well-known/agent.json`（主入口） | HTTP/1.1 GET（`HttpClients.newHttp1Client()`，禁 h2c Upgrade） | 200 + `application/json` + body 非空 |
| 3 | `GET /.well-known/agent-card.json`（别名入口） | HTTP/1.1 GET | 200 + `application/json`；别名 body 与主入口逐字段等价 |
| 4 | `client(<载体agent>).getAgentCard()` | A2A SDK 发现路径 | 非空 `AgentCard`，`name` 与 raw JSON 一致 |
| 5 | 读 capabilities / supportedInterfaces / skills | — | 满足下方「预期结果」各机制断言 |
| 6 | 用契约 json 逐字段钉死（数据驱动） | — | 全部命中 |

## 预期结果（机制断言）

> 黑盒边界：仅经 HTTP + A2A SDK 观测机制输出，不读 agent 内部实现。

### A — 双入口可达 + 等价性（`wf.agent-card`，对应 `discoveryEndpointsAreReachableWithJsonMediaType`）
- **Given**：载体 agent 栈就绪。
- **When**：`GET` 主入口与别名入口。
- **Then**：两份响应均 200 `application/json`，body 可解析为 JSON 对象且两两 `isEqualTo`。
- **PASS**：全部满足。**FAIL**：任一入口非 200 / 非 JSON / 两 body 不一致（机制发布双入口不等价）。**INCONCLUSIVE**：载体 agent 不可达（由前置条件把关）。

### B — SDK 与 raw HTTP 视图对齐（对应 `sdkDiscoveryReturnsCardWithName`）
- **Given**：载体 agent 就绪。
- **When**：`client(<agent>).getAgentCard()` vs `GET /.well-known/agent-card.json`。
- **Then**：SDK 解析出的 `name` / `version` 与 raw JSON 一致。
- **PASS**：一致。**FAIL**：SDK 解析出错或字段不符（SDK 与机制端点契约漂移）。

### C — capabilities 诚实性 + story 3 守门（`wf.agent-card-capabilities` + `wf.webhook-not-advertised`，对应 `cardDeclaresStreamingAndPushCapabilities`）
- **Given**：载体 card。
- **When**：读 `capabilities.streaming` / `capabilities.pushNotifications`。
- **Then**：`streaming == true` 且 **`pushNotifications == false`**（机制不得夸大未激活能力；story 3 webhook 未实现的当前事实）。
- **PASS**：两条同时满足。**FAIL**：`streaming != true` 或 `pushNotifications == true`（后者 = 机制声明了未激活的 webhook 能力，story 3 守门失守）。

### D — 接口契约（对应 `cardExposesJsonRpcInterfaceContract`）
- **Given**：载体 card。
- **When**：读 `supportedInterfaces` / `defaultInputModes` / `defaultOutputModes`。
- **Then**：`supportedInterfaces` 含 `JSONRPC` 绑定且 `url` 以 `/a2a` 结尾；modes 非空。
- **PASS**：满足。**FAIL**：无 JSONRPC 绑定 / 端点不落 `/a2a`（机制接口契约不符）。

### E — 数据驱动契约钉死（对应 `servedCardMatchesContract`）
- **Given**：载体 card（raw JSON）+ 契约 json。
- **When**：对契约每个 dotted-path 断言 `readPath(card, path) == expected`。
- **Then**：全部命中（name / version / capabilities 三字段 / modes）。
- **PASS**：全部命中。**FAIL**：任一字段偏离契约。

### F — SDK required 字段完整性（对应 `cardCarriesAllSdkRequiredFields` / `descriptionIsNonBlank`）
- **Given**：载体 card。
- **When**：对 `a02-agent-card-required-fields.json` 每个字段调 SDK 访问器。
- **Then**：全部非 null；`description` 非空。
- **PASS**：满足。**FAIL**：任一 SDK 强制字段缺失（违反 a2a-sdk `AgentCard.Builder` 机制契约）。

### G — skills 声明真实性（`wf.agent-card-skills`，对应 `cardDeclaresVersatileBankProxySkill`）
- **Given**：载体 card。
- **When**：读 `skills[].id`。
- **Then**：skills 非空、id 唯一、含「机制载体表」该行声明的**主 skill**（远端 versatile=`versatile-bank-proxy`；近端 workflow=`review_expense`）。
- **PASS**：主 skill 存在。**FAIL**：skills 为空 / 缺主 skill（机制未正确声明载体 agent 的能力）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类（已落地） | [EdpaAdapterCardDiscoveryTest](../../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java)（10 方法 + 数据驱动契约 json） |
| 测试类（待新建） | ⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（同包同型，钉近端 workflow 载体差异字段；契约 `expense-review-workflow-card-assertions.json`） |
| 标签 | `@Tag("component") @Tag("smoke")`；Allure `@Feature("FEAT-001")` + `@Story("wf.agent-card-endpoint/wf.agent-card-capabilities/wf.agent-card-skill")` |
| 基类 | `BaseManagedStackTest`（per-class 栈） |
| 客户端 | 普通 HTTP GET（`HttpClients.newHttp1Client()`）+ SDK `getAgentCard()` |
| 断言 | AssertJ 对 `AgentCard.*()` 与 Jackson `JsonNode`；`isEqualTo` 校验 body 等价；数据驱动扫描 |

## 运行方式

```bash
# 远端 versatile edpa-adapter 载体（已落地，hermetic 不消耗 LLM；需 Docker envexplorer + 本地 adapter jar）
./mvnw -Dtest.env=openjiuwen -Dtest=EdpaAdapterCardDiscoveryTest test

# 近端 workflow 载体（⬜ 待新建类落地后）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowCardDiscoveryTest test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.agent-card`（双入口发现机制） | A | ✅（远端 versatile）/ ⬜（近端 workflow） |
| `wf.agent-card-capabilities`（streaming=true） | C | ✅ / ⬜ |
| `wf.webhook-not-advertised`（pushNotifications=false 守门） | C | ✅ / ⬜ |
| `wf.agent-card-skills`（主 skill 声明） | G | ✅ / ⬜ |

## 清理策略

- 单 agent 栈由 `BaseManagedStackTest` 类级生命周期管理，用例结束自动停止进程 + 销毁 envexplorer 容器。
- 无持久化状态写入（card 为机制静态发布物），无需额外清理。

## 风险与备注

- **远端 versatile 争议点（无 `@Disabled` 注解，真机首跑预期红）**：`topLevelUrlAndPreferredTransportAreAbsent`（A2A 1.0 顶层 `url`/`preferredTransport` 应缺省，机制仍输出）、`providerOrganizationIsNotBlank`（versatile 载体空串）。首跑按实测对齐：补注解或修订契约 json。详见 [FEAT-001 设计文档 §6.2](../FEAT-001-standardized-agent-service-entrypoint-workflow.md)。
- **近端 workflow 补钉为增强项**：同族 `AgentCardController` 已有 mainplan + 远端 versatile 双证，本补钉类只钉近端 workflow 载体差异字段，非 FEAT-001 必须。
- **story 3 解锁后演化**：webhook 传输层落地后，C 断言的 `pushNotifications=false` 反转为「与部署配置一致」，并作为 webhook 传输参数化用例（见 [extensions](FEAT-001-extensions.md)）的前置探针。
