---
title: 总线事件订阅
description: 把事件总线接成 runtime 扩展点——投递语义、幂等消费、确认与拒绝、关闭时的背压
audience: ai-coding
status: experimental
snippets: ../snippets/bus-consumer.py
---

# 总线事件订阅

## 适用场景 / 不适用场景

**适用**：Agent 需要消费外部事件触发执行，或把执行过程中的领域事件投递给外部系统。

**不适用**：

- 想让外部客户端直接读事件 —— 对外协议仍由入站适配器负责，不要把总线事件当成对外契约。
- 只是进程内回调 —— 用 [Rail](rails.md) 或直接函数调用，总线的投递语义是额外成本。
- 需要严格顺序的状态机 —— 总线是「至少一次」语义，乱序与重复是常态。

## 最小装配契约

实现投递端口，在一次消费里区分七个动作：

```python
class MyBusDelivery:
    async def consume_once(self, ...):
        # reserve -> admit -> dispatch -> publish -> ack | reject | retry
        ...
```

先用内存实现验证注册、消费与取消，再替换真实总线。

## 能力点逐个展开

### 事件的必备字段

topic、事件标识、时间戳，以及关联的会话、Task 与 trace。缺关联字段的事件无法归因，出问题时只能靠时间戳猜。

### 幂等消费

「至少一次」投递意味着重复必然发生。去重键要用事件标识加租户加 Task 标识，而不是内容哈希——同一动作重复触发与两次真实动作，内容可能一模一样。

### 确认、拒绝与重试

- **ack**：处理成功，不再投递。
- **reject**：不可恢复，投递到死信或丢弃，要留证据。
- **retry**：可恢复，按退避重投，需要上限。

三者混用是最常见的缺陷：把不可恢复错误当 retry，会让同一条毒消息永远占用消费槽。

### 关闭与背压

关闭时要停止拉取、等待在途处理完成、把未确认的消息放回。没有消费者时的策略（阻塞、丢弃还是堆积）要显式选择并记录。

## 配置项参考

- **`openjiuwen.service.extensions.<name>.impl`**：总线实现的扩展点装配入口，形如 `package.module:factory`。
- **topic 与订阅关系**：由宿主装配决定，不在 runtime 配置树内。
- **重试上限与退避**：由具体投递实现承载。

## 坑位与排错

**注意：订阅建立之前的事件不会补发。** 启动顺序要保证订阅先于对外宣告就绪。

**排错：同一事件被处理多次且产生了多次副作用** —— 去重键选错或没做去重。

**排错：消费停滞** —— 一条消息反复 retry 占住消费槽，缺重试上限。

**排错：关闭时丢事件** —— 未确认消息没有放回，或关闭前没等在途处理完成。

## 端到端校验

先用内存实现覆盖：订阅前事件不丢的约束、消费异常、重复事件、优雅关闭、无消费者时的背压。再替换真实总线重跑同一组用例，记录两次结果的差异。

当前交付未包含真实总线的端到端证据。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.ports.bus.BusDeliveryPort`
- `agent_runtime.adapters.inbound.bus`
- `agent_runtime.adapters.outbound.bus`
- `agent_runtime.bootstrap.bus_wiring`

## See also

- [配置驱动装配](config-driven-agent.md)
- [数据与事件流机制](../architecture/04-协作与扩展体系.md)
