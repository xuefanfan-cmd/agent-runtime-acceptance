---
title: 中间件配置：状态缓存 checkpointer 与 Redis 端点
description: 用 openjiuwen.service.middleware 配置 Task 快照与会话状态的外置缓存——段存在即装配的判据、单机与集群两档端点、过期时间与重试策略
audience: ai-coding
status: verified
snippets: ../snippets/state-task-store-memory.py
---

# 中间件配置：状态缓存 checkpointer 与 Redis 端点

## 适用场景 / 不适用场景

**适用**：需要 Task 快照与推送回调状态跨副本共享或重启后可恢复；部署有可用的 Redis 端点。

**不适用**：

- 单进程本地验收 —— 不配这一段即可，runtime 退回进程内实现，连 Redis 依赖都不会被导入。
- 需要在请求前后插鉴权、限流、观测 —— 那是 Web 框架层的中间件或 [Rail](rails.md)，与本页的配置段同名不同义。
- 跨会话长期记忆 —— 当前 runtime 不承载，属 agent-core 侧能力。

## 最小完整示例

```yaml
openjiuwen:
  service:
    middleware:
      endpoint_type: standalone      # standalone | cluster
      standalone:
        host: redis.internal
        port: 6379
        database: 0
        timeout_ms: 3000
      checkpointer:
        ttl_seconds: 604800
```

宿主把加载后的配置交给组合根，状态存储随之切换到外置实现：

```python
task_store, push_cache, init_hook = build_a2a_stores_with_init(sources)
app = create_a2a_app(handler, name=agent_id, config=config,
                     task_store=task_store, push_callback_cache=push_cache,
                     init_hooks=(init_hook,) if init_hook else ())
```

## 能力点逐个展开

### 判据是「段在不在」，不是布尔开关

装配与否取决于**配置来源里有没有 `middleware` 这一段**。这样设计是因为配置类带默认值，绑定之后「没配」与「配成默认值」长得一模一样，用布尔开关会多出一个可以与实际配置矛盾的字段。

段缺席时：状态存储退回进程内实现，且 Redis 客户端库不会被导入——没装 redis 依赖的部署只要不配这一段就能正常起来。

### 两档端点

- **单机**：`standalone.host` / `port` / `database` / `timeout_ms` / 密码。
- **集群**：`cluster.nodes`（形如 `["host1:6379", "host2:6379"]`，至少一个）/ `timeout_ms` / 密码；集群档忽略 `database`。

`endpoint_type` 未配即按单机处理。

### 一个客户端，两种用法

Task 快照存储与推送回调缓存**共用同一个客户端实例**：它们是同一个 Redis 接入的两种用法，分别建会开出两套连接池，而配置里只描述了一套。

### 过期时间要真的到达存储

`checkpointer.ttl_seconds`（默认 604800，即七天）由装配层取出并传给存储实现。宿主自建 Task 存储时要用同一份取值，否则配置文件里的过期时间落不到任何写入上。

### 数据库档（存量可选）

`runtime_db.runtime_db_enabled` 为真时启用 Task 快照的数据库档，此时装配层会给出一个初始化钩子，宿主**必须**把它挂进组合根的 `init_hooks`——没挂就用会当场报可诊断的错，不会静默退化。默认关闭，既有部署行为不变。

## 配置项参考

- **`middleware.endpoint_type`**：`standalone` 或 `cluster`，默认 `standalone`。
- **`middleware.standalone.host` / `port` / `database` / `timeout_ms`**：单机端点，默认 `localhost:6379`、库 0、超时 3000 毫秒。
- **`middleware.cluster.nodes` / `timeout_ms`**：集群节点列表与超时。
- **`middleware.*.decrypted_password`**：密码，走 secret 目录或凭据解密器，不写进 YAML。
- **`middleware.checkpointer.ttl_seconds`**：快照过期时间，默认 604800。
- **`middleware.retry`**：有界重试与退避策略。
- **`runtime_db.runtime_db_enabled`** 等：Task 快照的数据库档，默认关闭。

## 坑位与排错

**注意：配了这一段就必须装好 Redis 依赖。** 惰性导入只保护「不配」这条路径。

**注意：只配段不传给工厂等于没配。** 装配层读配置产出 store，宿主还要把 store 传进应用工厂。配置消费链的检查方法见 [配置驱动装配](config-driven-agent.md)。

**注意：本页的 middleware 与 Web 中间件不是一回事。** 配置段名沿用上游命名，指的是状态中间件。

**排错：重启后 Task 丢失** —— 段没配，或 store 没传进工厂。

**排错：过期时间不生效** —— 宿主自建了 store 但没有取同一份 `ttl_seconds`。

**排错：集群档连不上** —— `nodes` 为空，或误配了 `database`（集群档忽略它）。

## 端到端校验

```bash
# 1. 段是否被识别
python -c "
from agent_runtime.bootstrap.cache_wiring import cache_section_configured
from agent_runtime.bootstrap.config.loader import ConfigSource, SourceKind
print(cache_section_configured((ConfigSource(SourceKind.FILE, 'resources/application.yml'),)))
"
# 2. 重启恢复：提交 Task -> 重启进程 -> 查同一个 task id
curl -fsS "$BASE_URL/tasks/$TASK_ID"
```

判据是重启后仍能读到 Task 状态，且 Redis 里的键带预期过期时间。多副本场景还要验证两副本读到同一份状态。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.cache_wiring.cache_section_configured` / `build_cache_if_configured` / `build_a2a_stores_with_init` / `build_task_store_ttl`
- `agent_runtime.adapters.outbound.cache_redis.factory.CacheConfig` / `StandaloneConfig` / `ClusterConfig` / `CheckpointerConfig`
- `agent_runtime.ports.cache.RuntimeRedisClient`
- `agent_runtime.bootstrap.task_store_wiring.build_a2a_task_store`

## See also

- [配置驱动装配](config-driven-agent.md)
- [Task 状态与缓存](state-and-cache.md)
- [生命周期、Task 与外置状态](../architecture/02-agent-runtime-python技术架构.md)
