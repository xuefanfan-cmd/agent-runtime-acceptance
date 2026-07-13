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
  - Docker 中存在 `jiuwenbox:latest`，支持 privileged；宿主机 8321 空闲
  - LLM 可用
---

# OJ-09 — openjiuwen 沙箱代码执行

> **一句话**：hotel（`sandbox` profile）+ jiuwenbox，流式发送「在沙箱用 python 执行
> print('ok') 并返回输出」，硬等 `COMPLETED`，回复含 `ok`，且 hotel 日志有真实
> `Tool result: code=0 ... ok`（防 LLM 假绿）。

---

## 1. 场景目标

1. `SutStack` 启动 jiuwenbox，完成 policy bootstrap/restart；hotel 启动后 `/health` PASS；
2. `hotel-sandbox`：`streaming(true)` + `profile=sandbox`，service binding 注入完整 URL；
3. `InteractionFlow` 单轮；终态 **硬等** `COMPLETED`；
4. 回复含 `ok`；OJ-09.E 日志门禁。

**不覆盖**：超时/非法命令（→ OJ-10）、skill LOCAL sysop（→ OJ-11）。

---

## 2. 前置条件

hotel jar 在 ~/.m2；Docker 中有 `jiuwenbox:latest` 且可 privileged 启动；宿主机 8321 空闲；`-Dtest.env=openjiuwen`。

---

## 3. 场景步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | inline `containerFactory` 启动 privileged jiuwenbox，bootstrap/restart | managed service ready |
| 2 | `SutStack` 启动 `hotel-sandbox`，binding 注入 `service-url` | OJ-09.C |
| 3 | hotel 启动后轮询 `/health` | OJ-09.0 |
| 4 | `InteractionFlow` → `awaitState(COMPLETED)` | OJ-09.A / B |
| 5 | `assertSandboxExecSucceeded` | OJ-09.E |

---

## 4. 子断言

- **OJ-09.0**：health 200  
- **OJ-09.A**：COMPLETED  
- **OJ-09.B**：回复含 `ok`  
- **OJ-09.C**：日志 `profile is active: "sandbox"` + sandbox tools 注册  
- **OJ-09.E**：日志 `Tool result` `code=0` 且含 `ok`；无 409 / state=error  

---

## 5. 测试数据

外置参考：`testdata/component/singleagent/oj-09-sandbox-python-ok.json`  
文案写在测试常量；**不**使用 `main` ScenarioData。

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `.../singleagent/OpenjiuwenSandboxCodeExecutionTest.java` |
| 栈 | `hotel-sandbox` + YAML service binding + 测试类内联 jiuwenbox `containerFactory` |
| 驱动 | `InteractionFlow`：`awaitState(COMPLETED)` |
| 门禁 | `OpenjiuwenSandboxCodeExecutionTest` 类内健康、日志私有断言 |

> **不**引入 `OpenjiuwenStackSupport` / Runner / `model.openjiuwen.*`。

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenSandboxCodeExecutionTest test
```
