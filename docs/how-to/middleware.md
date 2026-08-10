---
title: 中间件配置：checkpointer / Redis 端点 / 长期记忆
description: 用 openjiuwen.service.middleware 配置会话状态持久化（in-memory / Redis checkpointer）、命名 Redis 端点与受治理的长期记忆 MemoryStore；区分已交付能力与 P2 占位
audience: ai-coding
status: verified
snippets:
  - snippets/middleware-checkpointer.yml
---

# 中间件配置：checkpointer / Redis 端点 / 长期记忆

agent-runtime-java 把「Agent 运行时依赖的基础设施」统一收敛到
`openjiuwen.service.middleware.*` 配置前缀下，由自动配置装配进 core Runner：

- **checkpointer**：会话/执行状态持久化（多轮对话记忆、中断续传依赖它），
  支持 `in_memory`（默认）与 `redis` 两种实现；
- **redis**：命名 Redis 端点清单（standalone / cluster），被 checkpointer 按
  `redis-ref` 引用；
- **memory**：长期记忆服务，开启后暴露受治理（超时/重试/熔断/审计）的
  `MemoryStore` Bean，由 Agent 或业务代码自行决定何时读写。

> ⚠️ 边界：`session-store` / `object-storage` / `vector-store` 当前是 **P2 占位配置项**——
> 写了不报错，但也不产生任何运行时效果（见「能力状态表」）。不要围绕它们设计代码。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 单实例开发/演示：默认 `in_memory` checkpointer，零配置直接用 |
| ✅ 适用 | 多实例部署或需要重启后恢复会话：切换 `redis` checkpointer + 配置命名端点 |
| ✅ 适用 | Agent 需要跨会话长期记忆：开启 `memory.enabled`，注入 `MemoryStore` 调用 |
| ✅ 适用 | 需要定制中间件装配（如换用自己的 Redis 客户端）：实现/替换公开 SPI Bean |
| ❌ 不适用 | 期望配置 `session-store` 等占位项获得分布式会话/对象存储/向量库能力——未交付 |
| ❌ 不适用 | 期望框架自动决定 Agent 何时读写长期记忆——`MemoryStore` 是数据门面，调用时机由消费方决定 |

**能力状态：**

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| checkpointer `in_memory` | ✅ 已交付（runtime jar） | 默认类型，进程内状态缓存 |
| checkpointer `redis` | ✅ 已交付（runtime jar） | Redis 持久化 + TTL，依赖命名端点与 `RuntimeRedisClient` |
| redis 命名端点（standalone / cluster） | ✅ 已交付（runtime jar） | 自动配置按端点类型创建 Jedis 客户端 |
| memory（长期记忆 `MemoryStore`） | ✅ 已交付（runtime jar） | provider 支持 `mem0` / `jiuwen`，自带治理策略 |
| Sandbox 沙箱（远程代码执行） | ✅ 已交付（runtime jar） | 属 `external.*` 配置域而非 `middleware.*`，见 [Sandbox 沙箱](sandbox.md) |
| SkillHub 中间件 | ✅ 已交付（solution 增量） | 技能包下载与注册注入，见 [SkillHub 技能注入](skillhub.md) |
| `session-store` / `object-storage` / `vector-store` | ⏳ P2 占位 | 仅占位，不对外声明可用 |

## 最小完整示例

**纯配置增量，Java 侧零改动**：在任一 agent 服务工程（装配机制见
[配置驱动 Agent](config-driven-agent.md)，完整用例见
[examples/workflow/](../examples/workflow/)）的 `application.yml` 中并入
**[snippets/middleware-checkpointer.yml](../snippets/middleware-checkpointer.yml)**——
任一由 runtime 托管的本地 core Agent 即可用 **Redis checkpointer** 持久化会话状态。关键配置摘录：

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis            # 默认 in_memory；改 redis 即切换持久化
        redis-ref: default     # 引用下方 redis 命名端点
        ttl-seconds: 604800    # 状态缓存 TTL，默认 7 天，必须 > 0
      redis:
        default:               # 命名端点：被 redis-ref 引用
          type: standalone     # standalone | cluster
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: ""
```

Maven 依赖同「配置驱动 Agent」（`agent-service-adapters-agentcore`，版本对照见
[版本兼容与上游锚点](../compatibility.md)）——中间件自动配置随该模块引入，
无需额外依赖。

## 能力点逐个展开

### 1. checkpointer：会话状态持久化

- `type: in_memory`（默认）：状态保存在进程内，重启即失；适合单实例开发与演示。
- `type: redis`：自动配置创建 `RuntimeRedisClient`（按端点类型选 Jedis
  standalone/cluster 实现），并把连接信息与 TTL 组装进 core
  `RunnerConfig.checkpointerConfig`；`redis-ref` 指向 `redis.*` 下的命名端点。
- 同一开关同时驱动 **A2A 任务存储**：`type: redis` 时 A2A TaskStore 也切换为
  Redis 实现（带写节流包装，避免流式帧打满网络）——多实例部署下的 A2A
  中断续传因此与对话状态共用同一 Redis。
- `ttl-seconds` 控制状态缓存过期时间，默认 604800（7 天）；内部按分钟粒度
  下发，读操作不刷新 TTL。
- 装配入口是公开 SPI `MiddlewareAdapterRegistrar`（默认实现
  `DefaultMiddlewareAdapterRegistrar` 把 checkpointer 配置写入 RunnerConfig），
  由中间件自动配置在**启动期**注册并应用——**与 handler 的声明方式无关**
  （agent-id 自动装配、手动声明 Bean 两种路径下同样生效）。**纯配置即可生效，
  正常不需要自己写 registrar**（需要定制时见能力点 4）。

### 2. redis：命名端点清单

- `openjiuwen.service.middleware.redis` 是「名称 → 端点」的 Map，名称被
  `checkpointer.redis-ref`（或其他中间件）引用，便于多环境只换端点定义。
- 端点类型 `standalone`：必填 `host`；`port` 默认 6379。
- 端点类型 `cluster`：用 `nodes` 列表（`host:port` 形式）代替 `host`。
- 公共字段：`database`（默认 0）、`timeout-ms`（默认 3000）、
  `encrypted-password`（密文口令，经凭证解密器还原后使用，默认空）。
- **替换 Redis 客户端**：自动配置仅在容器中没有 `RuntimeRedisClient` Bean、
  且 `checkpointer.type=redis` 时创建默认 Jedis 客户端。要换用自己的客户端，
  声明一个 `com.openjiuwen.service.spec.spi.RuntimeRedisClient` Bean 即可，
  默认实现自动让位。

### 3. memory：受治理的长期记忆

- `memory.enabled=true` 时暴露两个 Bean：
  - `com.openjiuwen.service.adapters.common.memory.MemoryStore`——数据门面，
    提供 `add` / `search` / `get` / `delete` 四个方法，**何时调用由你的
    Agent/业务代码决定**（框架不自动读写）；
  - core `MemoryProvider` 桥接 Bean——供 core 侧按 provider 发现记忆服务。
- `provider` 路由到具体实现：`mem0`（默认，连 `endpoint` 指向的 mem0 服务）
  或 `jiuwen`。
- `encrypted-api-key` 为必填密文（解密后为空会在启动期抛错）。
- 治理策略直接映射到运行时外部调用执行器：`timeout-ms`（默认 3000，必须 > 0）、
  `retry` / `circuit-breaker` / `audit` 子策略；另有 `request-scoped-session`、
  `rerank`、`auth-header-mode`（默认 `token`）、`path-style`（默认 `v3`）
  等 provider 适配项，按所连服务要求设置。

### 4. 自定义中间件装配（扩展点，非必需）

只有默认装配不满足时才需要。两个公开扩展点：

```java
// 方式一：替换 Redis 客户端（最常用）
@Bean
RuntimeRedisClient runtimeRedisClient() {
    return new MyRedisClient(...);   // 默认 Jedis 客户端自动让位
}

// 方式二：自定义 registrar，向 RunnerConfig 写入额外中间件配置
@Bean
AgentHandler assistantHandler(ReActAgent agent, MiddlewareProperties props,
        CredentialDecryptor decryptor, RuntimeRedisClient redisClient) {
    MiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(
            props, decryptor, redisClient) {
        @Override
        public void applyToRunnerConfig(RunnerConfig runnerConfig) {
            super.applyToRunnerConfig(runnerConfig);   // 保留 checkpointer 装配
            // 追加自定义 RunnerConfig 项……
        }
    };
    return new JiuwenCoreAgentHandler(agent, registrar);
}
```

> 声明自定义 `AgentHandler` Bean 后，agent-id 纯配置自动装配会让位——
> 接线规则与「配置驱动 Agent」的能力点 2 相同。

## 配置项参考

- **openjiuwen.service.middleware.checkpointer.type**：`in_memory`（默认）/ `redis`；其他值在装配期抛 `IllegalArgumentException`。
- **openjiuwen.service.middleware.checkpointer.redis-ref**：引用的 redis 端点名，默认 `default`；端点不存在时启动期抛错。
- **openjiuwen.service.middleware.checkpointer.ttl-seconds**：状态缓存 TTL（秒），默认 604800；≤ 0 在配置绑定期抛错。
- **openjiuwen.service.middleware.redis.\<名称\>.\***：命名端点（`type` / `host` / `port` / `nodes` / `database` / `timeout-ms` / `encrypted-password`）。
- **openjiuwen.service.middleware.memory.\***：长期记忆（`enabled` / `provider` / `endpoint` / `encrypted-api-key` / `timeout-ms` / `retry` / `circuit-breaker` / `audit` / `request-scoped-session` / `rerank` / `auth-header-mode` / `path-style`）。

> ⚠️ 可设置属性边界：`session-store` / `object-storage` / `vector-store`
> 仅接受 `type`（默认 `none`），是 P2 占位——**配置它们没有任何运行时效果，
> 本文之外不要引用**。

## 坑位与排错

> ⚠️ **`type: redis` 但 `redis-ref` 指向不存在的端点**：启动期抛
> `IllegalArgumentException: openjiuwen.service.middleware.redis.<名称> is required
> for redis middleware`。修复：在 `redis.*` 下补齐同名端点，或把 `redis-ref`
> 改为已有端点名。

> ⚠️ **standalone 端点没填 `host`**：启动期抛
> `...redis.<名称>.host is required when type=standalone`。
> cluster 端点则用 `nodes` 列表，`host` 不生效。

> ⚠️ **`ttl-seconds` 配成 0 或负数**：配置绑定期抛
> `IllegalArgumentException`（值必须 > 0）。想「不过期」请设置一个足够大的值。

> ⚠️ **`memory.enabled=true` 但 `encrypted-api-key` 解密后为空**：启动期抛
> `IllegalStateException`。修复：配置有效密文，或先关闭 `memory.enabled`。

> ⚠️ **以为 `session-store` 等占位项已可用**：它们只绑定配置、不产生 Bean
> 或行为。分布式会话等需求当前只能基于 `redis` checkpointer + 自有代码实现。

## 端到端校验

1. 准备 Redis（本地 `127.0.0.1:6379` 或修改 `application.yml` 端点）。
2. 启动示例应用，确认启动日志无 redis 装配异常。
3. 发起两轮对话验证状态持久化：

   ```bash
   curl -X POST http://localhost:18093/v1/query \
     -H 'Content-Type: application/json' \
     -d '{"conversation_id":"m1","message":"记住：我的编号是 42","stream":false}'

   curl -X POST http://localhost:18093/v1/query \
     -H 'Content-Type: application/json' \
     -d '{"conversation_id":"m1","message":"我的编号是多少？","stream":false}'
   ```

   预期：第二轮能答出 42（同一 `conversation_id` 的状态经 checkpointer 恢复）。
4. 进一步验证 Redis 持久化：重启应用后用相同 `conversation_id` 再问，
   `redis` 类型下对话状态仍可恢复（`in_memory` 下则丢失）。

## API 锚点（jar 内类，按依赖可查）

- 配置绑定：`com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties`（前缀 `openjiuwen.service.middleware`）
- 装配 SPI：`com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar` / `DefaultMiddlewareAdapterRegistrar`
- Redis 客户端 SPI：`com.openjiuwen.service.spec.spi.RuntimeRedisClient`
- 长期记忆门面：`com.openjiuwen.service.adapters.common.memory.MemoryStore`
- 完整片段：[../snippets/middleware-checkpointer.yml](../snippets/middleware-checkpointer.yml)（本工程自有）

## See also

- [配置驱动 Agent](config-driven-agent.md)：agent 构造/注册与 handler 装配的三层职责（中间件是其「运行时配置」层的一部分）
- [A2A 跨智能体调用机制](a2a.md)：中断续传等跨调用状态同样落在 checkpointer 上
- [agent-runtime-java 接口文档](../api/agent-runtime-java.md)：Handler SPI 与配置速查
- [版本兼容与依赖坐标](../compatibility.md)：artifact 版本基线与坐标速查
