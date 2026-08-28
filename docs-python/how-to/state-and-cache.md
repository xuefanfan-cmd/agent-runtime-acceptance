---
title: Task 状态与缓存
description: 选择 TaskStore、SessionStore 与缓存边界——协议可见状态与运行时细节的分层、终态单向推进、多副本恢复的判据
audience: ai-coding
status: verified
snippets: ../snippets/state-task-store-memory.py
---

# Task 状态与缓存

## 适用场景 / 不适用场景

**适用**：需要决定 Task 与会话状态放在哪、活多久、多副本怎么共享，以及重启后哪些能恢复。

**不适用**：

- 只做单进程验收 —— 进程内实现足够，不必引入外置后端。
- 要配 Redis 端点 —— 端点与过期时间的配置面在 [中间件配置](middleware.md)，本页讲的是边界与判据。
- 框架内部的会话上下文 —— 那是 agent-core 的会话，不由本页的 store 承载。

## 最小装配契约

```python
task_store, push_cache, init_hook = build_a2a_stores_with_init(sources)
app = create_a2a_app(handler, name=agent_id, config=config,
                     task_store=task_store,            # 空即退回进程内实现
                     push_callback_cache=push_cache,
                     init_hooks=(init_hook,) if init_hook else ())
```

REST 入口与 A2A 共存时必须传同一个 `task_store` 实例。

## 能力点逐个展开

### 三层状态，边界不同

| 层 | 内容 | 可见性 | 恢复要求 |
|---|---|---|---|
| Task 状态 | 状态机、终态、Artifact | **协议可见**，对外契约 | 多副本共享、重启可读 |
| 会话状态 | 请求上下文、续接关联 | 宿主内部 | 跨副本续接时必须共享 |
| 运行态句柄 | 在途流、取消事件 | 进程内 | **不可外置**，事件对象无法跨副本 |

把三层塞进一个无界字典是最常见的错误：运行态句柄外置不了，Task 状态又必须外置。

### 终态只能前进一次

创建 Task 时生成稳定标识并记录初始状态；流产生期间持续更新；终态写入一次后不再变化。重复提交、过期 Task 与未知标识都要有明确结果，而不是各自回落到「新建一个」。

### 读状态不触发执行

状态查询是只读投影，不得触发新的模型调用。否则一次轮询就会重复计费，且状态永远追不上。

### 取消只在收到请求的副本生效

在途流登记表与取消事件是进程内运行态。多副本部署下，向副本 A 发的取消不会终止副本 B 上的执行——这是当前实现的边界，不是缺陷掩饰，路由层要保证同一会话落到同一副本，或接受这一限制。

## 配置项参考

- **`openjiuwen.service.middleware.checkpointer.ttl_seconds`**：Task 快照过期时间，默认 604800。
- **`openjiuwen.service.middleware.endpoint_type` 与端点段**：外置后端的选择，见 [中间件配置](middleware.md)。
- **`openjiuwen.service.runtime_db.*`**：Task 快照的数据库档，默认关闭。
- **函数参数 `task_store` / `session_store` / `push_callback_cache`**：由宿主注入；不注入即进程内实现。

## 坑位与排错

**注意：进程内实现不能证明多副本恢复能力。** 用它跑通的续接测试，换成两副本部署会失败——验收结论要写清用的是哪一档。

**注意：只检查 HTTP 响应发现不了「响应完成但状态未落盘」。** 验收要同时断言接口返回与 store 内容。

**排错：重启后 Task 丢失** —— 仍在用进程内实现，或配置未传进工厂。

**排错：两入口状态不一致** —— A2A 与 REST 没共享同一个 `task_store` 与编排器。

**排错：终态反复变化** —— 多处写入终态，缺少单向推进保护。

## 端到端校验

```bash
# 提交 -> 读状态 -> 重启进程 -> 再读同一个 task id
curl -fsS -X POST "$BASE_URL" -d @docs/snippets/a2a-send-request.json
curl -fsS "$BASE_URL/tasks/$TASK_ID"
```

判据：重启后仍读到 Task 与终态；外置档下 Redis 键带预期过期时间；多副本档下两副本读到同一份状态。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.ports.state_store` / `ports.session` / `ports.cache` / `ports.json_cache`
- `agent_runtime.bootstrap.task_store_wiring.build_a2a_task_store`
- `agent_runtime.bootstrap.state_store_wiring`
- `agent_runtime.adapters.outbound.state_cached` / `state_db` / `session`
- `agent_runtime.application.active_streams`

## See also

- [中间件配置](middleware.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [生命周期、Task 与外置状态](../architecture/02-agent-runtime-python技术架构.md)
