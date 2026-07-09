---
id: OJ-09
title: openjiuwen — 沙箱代码执行
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P0
feature: hotel DecoratedSandboxToolRegistrar + jiuwenbox（executeCode）
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel（单 agent，managed）+ jiuwenbox 沙箱服务
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-03：hotel `sandbox` profile + `application-sandbox.yml`
  - jiuwenbox 兼容沙箱在 `SANDBOX_HOST:SANDBOX_PORT` 可达（默认 127.0.0.1:8321）
  - LLM 可用（须能调用沙箱 executeCode / executeCmd）
---

# OJ-09 — openjiuwen 沙箱代码执行

> **一句话**：仅拉起 hotel（8093，`sandbox` profile），在 jiuwenbox 已就绪前提下，
> 经 A2A **流式** `message/stream` 发送「在沙箱用 python 执行 print('ok') 并返回输出」，
> 验证终态 `COMPLETED` 且
> 回复含执行结果 **`ok`**（或等价 stdout）。
>
> **关键定位**：openjiuwen 第三步 **沙箱主测点**；参考 runtime `SandboxDemoApplication` 与
> core `SandboxExample`，SUT 为 travel-openjiuwen hotel + `DecoratedSandboxToolRegistrar`。

---

## 1. 场景目标

验证 hotel agent 经 jiuwenbox 沙箱执行 Python 代码并将 stdout 回传给用户：

1. 前置探针：`GET http://<SANDBOX_HOST>:<SANDBOX_PORT>/health` 返回 ok（测试环境 Testcontainers、固定地址或 CI 预部署）；
2. hotel 以 `--spring.profiles.active=sandbox` 启动，注入 `SANDBOX_HOST`/`SANDBOX_PORT`；
3. 单轮 A2A `message/stream`，用户消息要求沙箱内 Python 打印 `ok`；
4. 终态 `COMPLETED`；回复文本包含 `ok`（允许前后有说明性文字）。

**本用例不覆盖**：

- 沙箱超时 / 非法命令（→ OJ-10，P2 可选）
- skill profile 的 LOCAL `executeCode`（→ OJ-11 使用 LOCAL sysop，与本用例 sandbox 后端不同）
- 全链 mainplan 委派 hotel 沙箱（→ 可扩展 OJ-09b）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-openjiuwen-travel-hotel:0.1.0` 在 ~/.m2 | **FAIL** |
| 2 | jiuwenbox `/health` 可达 | 探针失败 → **FAIL**（不 skip） |
| 3 | hotel `profile=sandbox`；`SANDBOX_HOST`/`SANDBOX_PORT` 正确 | 沙箱工具注册失败 → **FAIL** |
| 4 | LLM 可用 | tool call 失败 → **FAIL** / INCONCLUSIVE |
| 5 | 栈 `streaming(true)`，客户端 `message/stream` | — |
| 6 | 启动日志含 `Registered 3 sandbox tool(s) on hotel agent`（readFile / executeCmd / executeCode） | 可选 meta 观测 |
| 7 | **无需** mainplan / trip / MCP / Redis | — |

> jiuwenbox 通常 **不由** acceptance Testcontainers 自动拉起（镜像/部署较重）；执行前由测试人员或 CI 准备，测试类做 health 探针 FAIL-fast。

---

## 3. 场景步骤

| # | 动作 | 协议 / 配置 | 预期 |
|---|------|------------|------|
| 1 | `@BeforeAll`：jiuwenbox health 探针 | HTTP GET `/health` | status ok |
| 2 | 拉起 hotel：`profile=sandbox`，注入沙箱地址 env | SutStack | hotel 就绪 |
| 3 | SendStreamingMessage：见 testdata `inputText` | message/stream | 终态 COMPLETED |
| 4 | 断言 OJ-09.A / B | — | 见 §4 |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### OJ-09.0 — jiuwenbox 健康门禁

- **When**：hotel 启动前 GET `/health`。
- **Then**：HTTP 200 且 body 含 `ok` 或等价健康标识。
- **PASS** / **FAIL**：沙箱不可用。

### OJ-09.A — 终态 COMPLETED

- **Then**：`TaskState == COMPLETED`。
- **PASS** / **FAIL** / **INCONCLUSIVE**：同 OJ-08.A。

### OJ-09.B — 执行输出含 ok

- **Given**：OJ-09.A PASS。
- **When**：`textOf(task)`。
- **Then**：包含子串 `ok`（忽略大小写可选）；**不得** 仅返回「已执行」而无 stdout。
- **PASS**：含 `ok`。**FAIL**：无执行结果或明显错误栈。

### OJ-09.C — sandbox profile 门禁（meta）

- **Then**：栈 meta 含 `spring.profiles.active=sandbox`。

---

## 5. 测试数据

`src/test/resources/testdata/component/singleagent/oj-09-sandbox-python-ok.json`

```json
{
  "inputText": "在沙箱用 python 执行 print('ok') 并返回输出",
  "expectedTerminalState": "COMPLETED",
  "expectedResponseSubstrings": ["ok"],
  "sandboxHealthUrl": "http://127.0.0.1:8321/health",
  "timeoutMs": 180000
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/singleagent/OpenjiuwenSandboxCodeExecutionTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；建议 `@Tag("nightly")` |
| 栈 | `.streaming(true).agent("hotel", a -> a.profile("sandbox").env("SANDBOX_PORT", "8321"))` |
| 客户端 | `A2aEventCollector` + `TaskTextExtractor.textOf`；参考 A-04 |
| 探针 | `HttpClient` GET health；失败 Assumption 或 AssertJ fail |
| 参考 | `agent-core-java/examples/sandbox/SandboxExample.java`；hotel `DecoratedSandboxToolRegistrar.java` |

---

## 7. 运行方式

```bash
# 前置：jiuwenbox 已运行
curl -s http://127.0.0.1:8321/health

cd agent-runtime-acceptance
export LLM_API_KEY=... LLM_API_BASE=... LLM_MODEL=...
# 若沙箱非默认端口：export SANDBOX_PORT=18090

./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSandboxCodeExecutionTest test
```

---

## 8. 覆盖特性追溯

| 能力（0.2.0 七项） | 子断言 | 覆盖 |
|-------------------|--------|------|
| 沙箱 | OJ-09.0 / A / B / C | ✅ |

---

## 9. 风险与备注

- **外部依赖**：jiuwenbox 是第三步主要环境风险；CI 需预装或文档化 WSL/Docker 启动步骤（见 `agent-core-java/examples/sandbox/README.md`）。
- **LLM 选工具**：可能调用 `executeCode` 或 `executeCmd`；断言只看 stdout 含 `ok`，不绑定具体工具名。
- **与 skill profile 区分**：OJ-11 使用 LOCAL sysop；OJ-09 必须 **单独** `sandbox` profile，避免与 `skill` 同时启用导致工具重复注册（见 travel-openjiuwen README）。
