---
id: OJ-07
title: openjiuwen — checkpoint-redis 配置切换（B-04 语义，P1）
module: OJ — openjiuwen travel 集成测试（第三步）
owner: TBD
priority: P1
feature: openjiuwen Checkpointer 通过 Spring profile 切换 in_memory ↔ redis
status: designed
sut: agent-openjiuwen-travel-mainplan
stack: mainplan（单 agent）+ Testcontainers Redis（仅 Phase2）
tags: [component, openjiuwen, nightly]
depends_on:
  - 第二步 S-01 已合并
  - OJ-06 语义 helper / testdata 可复用
  - Docker 可用（Phase2）
  - LLM 可用
---

# OJ-07 — openjiuwen Checkpointer 配置切换（InMemory → Redis）

> **一句话**：在 **单个测试方法** 内先后启动两套 **仅 mainplan** 托管栈——Phase1 默认
> in_memory（无 profile）→ Phase2 **仅改** `spring.profiles.active=redis` + Redis 地址——
> 各跑一遍 OJ-06 同款两轮对话，**各自独立** 满足语义断言，验证 **切换不改代码**（B-04 语义）。
>
> **关键定位**：OJ-06 验 Redis 模式下多轮连续；OJ-07 验 **profile alone 可切换**
> checkpointer 后端且行为仍达标。相对 B-04：SUT 为 openjiuwen，切换方式为 **`redis`
> profile** 而非 `main-plan-agent.checkpointer` 属性。

---

## 1. 场景目标

1. Phase1：mainplan **无** redis profile（默认 in_memory），两轮对话 → 语义 PASS；
2. tearDown Phase1 栈；
3. Phase2：新 mainplan 栈，`profile=redis` + Testcontainers `REDIS_HOST`/`REDIS_PORT` → 同一对话再跑 → 语义 PASS；
4. Meta 断言：两阶段除 profile / Redis env 外栈参数一致（§4.OJ-07.0c）。

**本用例不覆盖**：

- 三 agent 全链 Redis（→ OJ-06）
- Redis → in_memory 反向切换
- Phase 间 Redis 数据继承（Phase2 为新容器 + 新进程，不读 Phase1 键）

---

## 2. 前置条件

| # | 条件 |
|---|------|
| 1 | mainplan jar 在 ~/.m2 |
| 2 | Phase2 需 Docker + Testcontainers Redis |
| 3 | LLM 可用 |
| 4 | 复用 `oj-06-redis-multi-turn.json` 与 B-03/OJ-06 流式两轮 runner |
| 5 | 流式 `streaming(true)`，两轮均为 `message/stream`（与 OJ-06 / B-04 对齐） |

---

## 3. 场景步骤（单测两阶段）

| # | 阶段 | 动作 | 预期 |
|---|------|------|------|
| 1 | Phase1 | 起栈：仅 mainplan，**无** redis profile | 就绪 |
| 2 | Phase1 | Turn1「我要出差」→ Turn2「去北京」（流式 SSE） | OJ-07.P1.* PASS |
| 3 | Phase1 | `stack.close()` | 进程退出 |
| 4 | Phase2 | Testcontainers Redis 就绪；起新栈：`profile=redis` + Redis env | 就绪 |
| 5 | Phase2 | **同一 testdata** 再跑 Turn1→Turn2 | OJ-07.P2.* PASS |
| 6 | — | OJ-07.0c 配置差 meta 校验 | 仅 profile + Redis env 不同 |

> Phase1 **FAIL** 则不进入 Phase2。

---

## 4. 可观测子断言

### OJ-07.0 — Docker / Redis 门禁（Phase2）

- **When**：进入 Phase2 前启动 Testcontainers Redis。
- **Then**：`REDIS_HOST`/`REDIS_PORT` 非空且注入 mainplan。
- **PASS** / **FAIL**：Redis 不可用 → Phase2 无法代表 redis 切换。

### OJ-07.0c — 「仅改配置」硬门禁

- **Then**：
  - **允许不同**：Phase2 含 `spring.profiles.active=redis` + `REDIS_HOST`/`REDIS_PORT`；Phase1 无上述项；
  - **必须相同**：fat jar 坐标、`streaming(true)`、其余 property/env（不含 LLM 密钥差分）。
- **PASS** / **FAIL**：存在未声明的额外配置差。

### OJ-07.P1 — Phase1（InMemory）对话断言

等同 OJ-06.A / B / C（或 OJ-03 子断言），前缀 P1。

Phase1 额外硬门禁：**不得** 设置 `spring.profiles.active=redis`。

### OJ-07.P2 — Phase2（Redis）对话断言

与 P1 同结构、同 testdata；**独立判定**，不与 P1 Turn2 文本做相等比较。

Phase2 额外硬门禁：`profile=redis` + Redis env 已注入。

---

## 5. 测试数据

**复用** OJ-06 文件：

`src/test/resources/testdata/integration/react_travel/oj-06-redis-multi-turn.json`

---

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/singleagent/OpenjiuwenCheckpointerConfigSwitchTest.java` |
| 标签 | `@Tag("integration") @Tag("openjiuwen")`；建议 `@Tag("nightly")` |
| 生命周期 | 单 `@Test` 内 Phase1/Phase2 各 `try (SutStack stack = ...)`，**不用** 默认 `@BeforeAll` 单栈 |
| Phase1 | `SutStack.builder(config).streaming(true).agent("mainplan")` |
| Phase2 | 同上 + `.profile("redis")` + Redis env |
| 参考 | [B-04-checkpointer-config-switch.md](./B-04-checkpointer-config-switch.md) 编排结构 |

---

## 7. 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=OpenjiuwenCheckpointerConfigSwitchTest test
```

---

## 8. 覆盖特性追溯

| 能力 | 子断言 | 覆盖 |
|------|--------|------|
| checkpoint-redis（切换叙事） | OJ-07.0c / P2 | ✅ |
| 短期记忆 in_memory | P1 | 🟡 对照 |
| A2A 流式 | P1 / P2 | ⚠️ 传输前置 |
| checkpoint-redis 专项连续 | — | ❌（OJ-06 负责） |

---

## 9. 风险与备注

- **P1 加分项**：0.2.0 最低验收标准为 OJ-06 PASS；OJ-07 为配置切换加分。
- **与 B-04 分工**：B-04 单 agent + spring-ai-ascend 属性键；OJ-07 单 agent + openjiuwen profile。
- **实现顺序**：建议 OJ-06 helper 落地后再写 OJ-07 编排层。
