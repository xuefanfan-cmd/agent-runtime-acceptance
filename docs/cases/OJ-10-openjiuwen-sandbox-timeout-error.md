---
id: OJ-10
title: openjiuwen — 沙箱超时 / 错误（P2，可选）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P2
feature: hotel 沙箱异常路径 — 超时或非法命令时的错误可见性
status: designed
sut: agent-openjiuwen-travel-hotel
stack: hotel（单 agent，managed）+ jiuwenbox
tags: [integration, openjiuwen]
depends_on:
  - 第二步 S-03 已合并
  - OJ-09 同类 jiuwenbox 前置
  - LLM 可用
---

# OJ-10 — openjiuwen 沙箱超时 / 错误（可选）

> **一句话**：在 hotel `sandbox` profile 下，故意触发 **沙箱超时** 或 **非法命令**，
> 验证 agent **不崩溃**、终态为可观测的非成功态或回复含 **清晰错误信息**；进程在
> `@AfterAll` 仍健康。
>
> **关键定位**：OJ-09 验 happy path；OJ-10 验 **失败可见性**（P2 加分，不纳入 0.2.0
> 最低验收矩阵）。

---

## 1. 场景目标

| 子场景 | 输入意图 | 期望 |
|--------|---------|------|
| OJ-10a 超时 | 沙箱 Python `sleep(120)` 或等价长耗时 | 终态非 COMPLETED **或** 回复含 timeout / 超时 / 失败类关键词；hotel 进程存活 |
| OJ-10b 非法命令 | 执行不存在命令或语法错误脚本 | 回复含 error / 失败 / 无法执行；hotel 进程存活 |

**本用例不覆盖**：

- 沙箱服务本身崩溃恢复
- 熔断器精确阈值（仅黑盒观测用户可见错误）

---

## 2. 前置条件

同 [OJ-09-openjiuwen-sandbox-code-execution.md](./OJ-09-openjiuwen-sandbox-code-execution.md) §2。

额外：

- testdata 中 `sandboxTimeoutMs` 应 **小于** jiuwenbox / agent 配置的上限，以便可控触发超时（默认 hotel `timeout-ms: 30000`）。

---

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | jiuwenbox health 探针 | PASS |
| 2 | 拉起 hotel `sandbox` profile | 就绪 |
| 3a | SendStreamingMessage：触发超时（见 testdata `timeoutPrompt`） | OJ-10.T.* |
| 3b | （可选第二 `@Test`）SendStreamingMessage：非法命令（`errorPrompt`） | OJ-10.E.* |
| 4 | `@AfterAll` 前：`GET /.well-known/agent.json` 或轻量流式 send 探针 | hotel 仍 200 / 可响应 |

---

## 4. 可观测子断言

### OJ-10.T — 超时路径

- **Then**（满足其一即 PASS）：
  - 终态 ∈ {`FAILED`, `INPUT_REQUIRED`}；**或**
  - 终态 `COMPLETED` 但文本含 `timeout` / `超时` / `timed out` / `失败`（testdata 配置）；
- **且**：后续 agent card 探针 PASS（进程未崩溃）。

### OJ-10.E — 错误路径

- **Then**：回复文本含 error / 错误 / 无法执行 / 失败 等（testdata `errorKeywords` 任命中）；
- **且**：agent 探针 PASS。

### OJ-10.P — 进程存活（共用）

- **When**：异常用例结束后。
- **Then**：hotel HTTP 探针 200。
- **FAIL**：连接拒绝 / 进程退出。

---

## 5. 测试数据

`src/test/resources/testdata/openjiuwen/integration/oj-10-sandbox-error.json`

```json
{
  "timeoutPrompt": "在沙箱用 python 执行 import time; time.sleep(120); print('done')",
  "errorPrompt": "在沙箱执行命令：/nonexistent/command_xyz",
  "errorKeywords": ["error", "错误", "失败", "无法", "cannot", "No such"],
  "timeoutKeywords": ["timeout", "超时", "timed out", "失败"],
  "postProbeInputText": "你好",
  "timeoutMs": 180000
}
```

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/openjiuwen/integration/OpenjiuwenSandboxErrorTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；**不** 纳入 smoke |
| 结构 | `@Test timeoutScenario()` + `@Test illegalCommandScenario()` 或 `@ParameterizedTest` |
| 栈 | `.streaming(true)`（与 OJ-09 一致） |
| 参考 | OJ-09 栈构建；runtime sandbox demo 异常路径 |

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSandboxErrorTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 覆盖 |
|------|------|
| 沙箱（异常路径） | ⚠️ 加分；0.2.0 最低标准仅要求 OJ-09 |

---

## 9. 风险与备注

- **耗时长**：超时子场景可能接近 2～3 分钟；CI nightly 再跑。
- **LLM 非确定性**：模型可能拒绝执行危险 sleep；失败时标 INCONCLUSIVE 并保留日志。
- **0.2.0 范围**：实施计划标记 P2 可选；文档先沉淀，实现可排在 OJ-09 之后。
