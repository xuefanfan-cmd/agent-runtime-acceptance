---
id: A-02
title: Agent Card 字段完整性
module: A — A2A 协议与通讯模型
owner: ALL
priority: P0
feature: 特性4 / 4-1 Agent Card
status: implemented（已合并入 A-01 测试类）
sut: main-plan-agent
stack: mainplan（单 agent，与 A-01 共用）
tags: [component]
depends_on: [A-01（同一发现链路）]
---

# A-02 — Agent Card 字段完整性

> **一句话**：验证 A-01 发现的 Card **字段完整**——携带 a2a-sdk 规范所要求的全部必填字段。
>
> **关键结论**：A-02 与 A-01 **实质相同**（同一被测对象、同一发现机制、同一栈），
> 仅在 A-01 基础上增加 `description` / `skills` 的在场断言与"全字段完整性"扫描。
> 故**已合并入 A-01 的测试类** `AgentCardDiscoveryTest`（共用 per-class mainplan 栈，
> 避免二次拉起），不再单列测试类。

---

## 1. 与 A-01 的关系（合并判定）

| 维度 | A-01 发现 | A-02 字段完整性 |
|------|-----------|-----------------|
| 被测对象 / 栈 | mainplan 单 agent | **同一** |
| 触发机制 | `client("mainplan").getAgentCard()` + 原始 GET | **同一**（"解析 A-01 返回的 Card"） |
| 断言范围 | 可达性、`name`、`capabilities`、transport、modes、`url` | `name`、`description`、`url`、`skills`、`supportedInterfaces` + 全字段完整性 |
| 与对方重叠 | — | ≈85% 已被 A-01 覆盖；**新增 `description`、`skills` 在场 + 完整性扫描** |

- **是否相似**：是。同一 Card、同一栈、同一客户端路径。
- **能否合并**：能，且应当合并——否则 per-class 栈会把 mainplan 拉起两遍。
- **是否已被 A-01 完全覆盖**：否（`description`、`skills` 未断言），但合并后即覆盖。

## 2. 规范基线（a2a-sdk 契约，非 SUT 自述）

权威来源：`org.a2aproject.sdk.spec.AgentCard$Builder#build()` 中由
`Assert.checkNotNullParam` **强制非空**的字段（反编译确认，共 8 个）：

```
name, description, version, capabilities,
defaultInputModes, defaultOutputModes, skills, supportedInterfaces
```

可选（`build()` 不强制，可为 null）：`provider`、`url`、`documentationUrl`、
`securitySchemes`、`securityRequirements`、`iconUrl`、`signatures`、`additionalInterfaces`。
`preferredTransport` 为 null 时 `build()` 默认为 `"JSONRPC"`。

> 该基线外置在 `src/test/resources/testdata/component/protocol/a02-agent-card-required-fields.json`，
> 测试以**数据驱动**方式逐字段断言其在场，便于规范演进时只改数据、不改代码。

## 3. 规范校验结论（对真实 mainplan card 实测）

被测 card（`A2A.getAgentCard()` 成功解析 ⇒ 8 个必填字段必然在场）全部满足：

| 字段 | 实测 | 结论 |
|------|------|------|
| name | `"main-plan-agent"` | ✅ |
| description | 非空中文描述 | ✅（且 `isNotBlank`） |
| version | `"0.1.0"` | ✅ |
| capabilities | `{streaming,pushNotifications,extendedAgentCard}` | ✅ |
| defaultInputModes | `["text"]`（非空） | ✅ |
| defaultOutputModes | `["text","artifact"]`（非空） | ✅ |
| skills | `[]`（在场，空列表） | ✅（规范允许空） |
| supportedInterfaces | 1 个 JSONRPC 绑定（非空） | ✅ |

**完整性维度：8 个必填字段全部在场，无违规。** 但顶层 `url`/`preferredTransport`、mode 取值、`provider.url` 等存在 SUT 偏差，见 §4（E/F/G 经实测确属偏差，已 `@Disabled`）。

## 4. 可疑点 / 争议点测试（保留描述以供与开发对齐）

按"不默认 SUT 正确"的原则，三处可疑点各落地为一个测试方法（A-02.E/F/G）。
**规范正确、被测实现有问题的**临时 `@Disabled`，保持 `mvn test` 全绿；`@Disabled` 原因内嵌
完整描述（实测值 + 规范依据 + 对齐问题），便于直接与开发对齐。

> 注：E 的初版误把"顶层 `$.url` 在场"当作正确（实际按 A2A 1.0 应**无值**），已更正为"断言字段缺失"；
> 同时 A-01.D 与 A-01 值契约（`a01-agent-card-assertions.json`）原断言的 `preferredTransport=="JSONRPC"` /
> 顶层 `$.url` 一并移除，避免把 SUT 偏差固化进常态断言。

| # | 争议点 | 测试方法 | 规范依据 | 被测实测 | 判定 | 对齐问题 / 建议 |
|---|--------|----------|----------|----------|------|------------------|
| **E** | 按 A2A 1.0 顶层 `$.url` 与 `preferredTransport` 应不输出 | `topLevelUrlAndPreferredTransportAreAbsent` | A2A 1.0：端点信息在 `supportedInterfaces[]`，顶层 `url`/`preferredTransport` 已废弃、应无值 | SUT 仍输出 `$.url=http://localhost:<port>/a2a`、`preferredTransport=JSONRPC`（`hasNonNull` 实测为 true；临时启用时用例确 FAIL，已证有效） | ⏸ **@Disabled**（SUT 偏差） | 开发按 1.0 移除这两个顶层字段；端点已在 `supportedInterfaces[0].url` |
| **F** | `defaultOutputModes` 含 `"artifact"` | `everyModeIsValidA2aMode` | A2A：mode 应为 MIME 类型（`text` 简写可接受） | `defaultOutputModes = [text, artifact]`；`"artifact"` 非 `text`、非 MIME | ⏸ **@Disabled** | `"artifact"` 是自定义合法 token 还是应改 MIME（如 `text`）？建议开发明确语义 |
| **G** | `provider.url` 硬编码 `localhost:8080` | `providerUrlIsNotLoopbackPlaceholder` | A2A：`provider.url` 为组织/公司 URL | `provider.url = http://localhost:8080`（默认端口，未按实际端口改写） | ⏸ **@Disabled** | 应填真实组织 URL，还是维持现状？`provider` 为可选字段，语义待明确 |

> **复测方式**：与开发对齐后，移除对应方法的 `@Disabled` 即可激活。
> E 的规则为"`$.url` 与 `preferredTransport` 均 `hasNonNull==false`"（经临时启用验证：确能 FAIL，证明用例有效）；
> F 的规则为"等于 `text` 或匹配 `type/subtype` MIME"；G 的规则为"`provider.url` 不含 `localhost:8080` 占位"。

### 4.1 SDK 行为注记（E 的背景）
- 按 A2A 1.0，顶层 `url` 与 `preferredTransport` **应不输出**；端点信息由 `supportedInterfaces[]`
  （`protocolBinding`+`url`）承载。`a2a-java-sdk` 解析出的 `AgentCard.url()` 恰好返回 null——这与 1.0 规范
  **一致**（字段本就不该有）。
- 因此判定 SUT 是否违规，**必须读原始 JSON**（`hasNonNull("url")` / `hasNonNull("preferredTransport")`）：
  实测两者均被 SUT 输出（非空）→ 即为偏差。**勿**用 SDK 的 `card.url()`（恒为 null，无法区分"规范无此字段"与"SUT 漏输出"）。
- `preferredTransport` 另有陷阱：SDK `build()` 在该字段为 null 时**默认填 `"JSONRPC"`**，故 SDK 访问器恒为
  `"JSONRPC"`，同样不能用 SDK 判定。

## 5. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentCardDiscoveryTest.java`（**与 A-01 共用**） |
| A-02 方法 | 完整性：`cardCarriesAllSdkRequiredFields()`（数据驱动扫描）、`descriptionIsNonBlank()`；争议点：`topLevelUrlAndPreferredTransportAreAbsent()`(E, @Disabled)、`everyModeIsValidA2aMode()`(F, @Disabled)、`providerUrlIsNotLoopbackPlaceholder()`(G, @Disabled) |
| 标签 | `@Tag("component") @Tag("smoke")`（继承自类） |
| 数据 | `testdata/component/protocol/a02-agent-card-required-fields.json`（必填字段清单） |
| 栈 | `BaseManagedStackTest` per-class，仅 mainplan（与 A-01 同一进程） |

## 6. 运行方式

```bash
./mvnw -Dtest=AgentCardDiscoveryTest test   # 含 A-01 + A-02，共用一次 mainplan 拉起
# 实测：Tests run: 10, Failures: 0, Errors: 0, Skipped: 3（E/F/G 临时 @Disabled，详见 §4）
```

## 7. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 4-1 Agent Card — 发现 | A-01 | ✅ |
| 特性 4-1 Agent Card — 字段完整性 | A-02（完整性扫描 + description/skills） | ✅ |
