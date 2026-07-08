---
id: OJ-01
title: openjiuwen — A2A 同步 SendMessage 最小闭环
module: OJ — openjiuwen travel 集成测试（第一步）
owner: TBD
priority: P0
feature: agent-runtime-java A2A 同步（SendMessage）+ agent-core-java ReAct 经 JiuwenCoreAgentHandler 暴露
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent，managed 模式，无需 trip/hotel）
tags: [component, openjiuwen, smoke]
depends_on:
  - travel-openjiuwen 三 fat jar 已 mvn install 至 ~/.m2（本用例仅拉起 mainplan）
  - mainplan 启动时已配置 LLM（LLM_* 环境变量；由测试人员准备，用例代码不门禁）
  - 测试 profile：application-openjiuwen.yml（-Dtest.env=openjiuwen）
---

# OJ-01 — openjiuwen A2A 同步 SendMessage 最小闭环

> **一句话**：在 managed 模式自动拉起的 `agent-openjiuwen-travel-mainplan` 上，经同步
> `message/send`（栈 `streaming(false)`）发送「你好」，验证任务到达终态 `COMPLETED` 且
> 回复文本非空。
>
> **关键定位**：openjiuwen 第一步的**协议最小闭环**——验证 travel-openjiuwen 嵌入
> agent-runtime-java 后，北向 A2A 同步路径可用。参考 spring-ai-ascend 侧 A-03 / A-05
> 的 send 语义，但 SUT 换为 openjiuwen 制品，**不依赖** spring-ai-ascend agent 坐标。

---

## 1. 场景目标

验证 openjiuwen mainplan 的 A2A 同步通讯（特性对应：agent-runtime S2C 同步模式 + A2A SendMessage）：

1. `SutStack` 在 `@BeforeAll` 自动 `java -jar` 拉起 mainplan（无需人工预启动）；
2. 客户端配置 `streaming=false`，走 `message/send` 阻塞至终态；
3. 单次问候对话到达 `COMPLETED`；
4. 终态 artifact / status 文本可抽取且非空。

**本用例不覆盖**（由其他用例负责）：

- `message/stream` 流式路径（→ 第二步 / 第三步或 spring-ai-ascend A-04）
- `tasks/get` 快照一致性（→ 可选扩展 OJ-01b，参考 A-05）
- trip / hotel 远程链路（→ OJ-05 及后续）
- Agent Card 字段完整性（→ 可选扩展，参考 A-01）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `com.huawei.ascend.examples:agent-openjiuwen-travel-mainplan:0.1.0` 可在 `~/.m2` 解析 | ProcessLauncher 启动失败 → **FAIL** |
| 2 | 测试框架 `-Dtest.env=openjiuwen` 加载 `application-openjiuwen.yml` | 坐标错误 → **FAIL** |
| 3 | 栈仅声明 `mainplan` 单 agent（managed，`group/artifact/version`，无 `url`） | 误拉起全链不影响本用例，但违背设计 |
| 4 | 栈 `streaming(false)`，A2A 客户端走同步 `message/send` | 误配 `streaming(true)` → 偏离本用例 |
| 5 | mainplan 进程具备可用 LLM（`LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` 等） | send 超时或 `FAILED` → **FAIL** |
| 6 | **无需** trip / hotel / Redis / MCP | — |

> LLM 为运行前置，不在测试类内做 `@EnabledIf` 跳过；凭据由执行者在跑测前 export。

---

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | `@BeforeAll`：`SutStack.builder(config).streaming(false).agent("mainplan").start()` | managed ProcessLauncher | mainplan 就绪；`GET /.well-known/agent.json` 200 |
| 2 | 构造 A2A 客户端：`stack.client("mainplan")`（SDK `streaming=false`） | A2aServiceClient | 客户端可 send |
| 3 | 发送 `message/send`：用户文本「你好」 | a2a-sdk `Client.sendMessage` | 阻塞至终态 |
| 4 | 收集终态 Task | A2aEventCollector / send 返回值 | `state == COMPLETED` |
| 5 | 抽取回复文本 | artifacts → status.message → history 降级 | 非空字符串 |
| 6 | 断言 OJ-01.A / B | — | 见 §4 |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK `message/send` 观测，不读 mainplan 内部类 / YAML。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### OJ-01.A — 同步 send 到达 COMPLETED

- **Given**：mainplan 已就绪，客户端 `streaming=false`。
- **When**：发送「你好」，等待终态。
- **Then**：`TaskState == COMPLETED`（或 SDK 等价终态枚举）。
- **PASS**：COMPLETED。**FAIL**：FAILED / 超时 / 无终态事件。
- **INCONCLUSIVE**：LLM 对「你好」返回 INPUT_REQUIRED（极少见）——记录实测，不当作 PASS。

### OJ-01.B — 回复文本非空

- **Given**：OJ-01.A 为 COMPLETED。
- **When**：经统一 `textOf(task)` 抽取 artifact 文本。
- **Then**：`length > 0`。
- **PASS**：非空。**FAIL**：空串或无法抽取。
- **INCONCLUSIVE**：OJ-01.A 非 COMPLETED 时不可判。

---

## 5. 测试数据

外置：`src/test/resources/testdata/openjiuwen/component/oj-01-sync-send.json`

```json
{
  "inputText": "你好",
  "expectedTerminalState": "COMPLETED",
  "minResponseLength": 1
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/component/OpenjiuwenSyncSendTest.java` |
| 标签 | `@Tag("component") @Tag("openjiuwen")`；可选 `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest`（per-class 管理栈） |
| 栈描述 | `.streaming(false).agent("mainplan")` |
| 配置 | `-Dtest.env=openjiuwen` → `application-openjiuwen.yml` |
| 客户端 | `stack.client("mainplan")`；`A2aServiceClient` 同步模式 |
| 断言 | AssertJ：`isEqualTo(COMPLETED)`、`isNotBlank(textOf(...))` |

---

## 7. 运行方式

```bash
# WSL / Linux 推荐
cd agent-runtime-acceptance
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSyncSendTest test
```

> 无需提前 `java -jar` 启动 agent；框架在 `@BeforeAll` 自动拉起并在 `@AfterAll` 销毁。

---

## 8. 覆盖特性追溯

| 能力（0.2.0 七项） | 子断言 | 覆盖 |
|-------------------|--------|------|
| A2A 同步 | OJ-01.A / B | ✅ |

---

## 9. 风险与备注

- **与 spring-ai-ascend A-03 的差异**：SUT 为 `agent-openjiuwen-travel-mainplan`，Card `name` 为
  `agent-openjiuwen-travel-mainplan`（非 `main-plan-agent`）；断言勿硬编码 spring-ai-ascend 字段。
- **LLM 非确定性**：「你好」通常 COMPLETED；若偶发 FAILED，记录 trace + 日志（`target/sit-logs/`）。
- **Windows 执行**：遵循 WSL 跑 `./mvnw`，避免 Git Bash 路径问题。
