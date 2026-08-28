---
title: 接入本地 Agent 框架
description: 将异构框架接入 AgentHandler，并保持领域、协议和生命周期边界。
audience: both
status: verified
examples: ../../docs/examples/react/
snippets: ../snippets/handler-fixture.py、../snippets/framework-adapter-contract.py
---

# 接入本地 Agent 框架

## 适用场景 / 不适用场景

**适用**：已有 AgentCore、AgentScope 或其他本地 Agent 执行引擎，需要同时暴露 A2A/REST、统一取消、Task 状态和生命周期时，实现 runtime adapter。

**不适用**：

- 只是调用模型一次并返回字符串 —— 不要为它伪造完整框架适配器；实现最小 Handler 并明确声明不支持流式、工具与恢复。
- 用的就是 agent-core 的 ReAct / Workflow / DeepAgent —— 不必自己写适配器，runtime 已提供通用适配器 `AgentCoreHandler`，见 [ReAct 指南](react-agent.md)。
- Agent 在另一个进程或主机 —— 用 [Versatile 对接](versatile-agent.md)。

## 最小装配契约

```python
class FrameworkHandler:
    agent_id = "framework-agent"
    priority = 0

    async def query(self, request):
        chunks = [item async for item in self.stream_query(request)]
        return aggregate_chunks(chunks, request.conversation_id)

    async def stream_query(self, request):
        async for native_event in self.engine.run(request.message):
            yield self.translator.to_query_chunk(native_event)

    async def start(self):
        await self.engine.start()

    async def stop(self):
        await self.engine.stop()

    async def clear_session(self, conversation_id):
        await self.engine.clear(conversation_id)
```

## 能力点逐个展开

### 输入归一

从 `ServeRequest` 读取 message、conversation、resume 和上下文；不要把整个 request 作为框架私有对象传递，避免 adapter 对象被序列化进 TaskStore。

### 输出归一

定义 native event → `QueryChunk` 的映射表：文本、thought、tool start/result、final answer、interrupt、error。未知事件必须记录并有明确的丢弃或失败策略。

### 工具和中断

工具调用要保留 call id、参数、结果和错误；框架的交互对象转换为 `QueryChunk.of_interrupt`，恢复时从 `ServeRequest.for_resume` 重新进入 adapter。

### 取消和异常

客户端停止消费时，adapter 必须停止上游迭代并释放 session、HTTP、线程或进程资源。不要捕获所有异常后产出空终答；错误类型和原始原因要进入可脱敏的 error data。

### 生命周期

`start` 只做依赖初始化和 readiness 所需检查，`stop` 负责关闭连接和等待在途执行。不要依赖框架对象自己的 global singleton 替代 runtime 生命周期。

### 两类框架的接入差异

| 框架 | 接入方式 | 验收重点 |
|---|---|---|
| agent-core（openjiuwen） | 不必自写：用通用适配器 `AgentCoreHandler(agent_id, Runner)`，它按运行资源登记形态择取执行入口，不区分推理型、深度型、工作流型 | 原生流的 chunk 投影、工具中断与续接、工作流分派 |
| AgentScope 等异构框架 | 自写适配器实现 `AgentHandler` 五方法 | 事件形态映射、同步与异步桥接、会话映射 |

框架依赖版本、可选能力与跳过条件必须记录在验证报告里，不能用同一份替身报告两个框架都通过。

## 配置项参考

| 项目 | 责任 |
|---|---|
| model/client | 宿主提供模型或框架 client |
| timeout | adapter 分别控制连接、读取、总执行超时 |
| session | 由 runtime context 映射到框架 session |
| tools | adapter 注册或委派到 runtime tool boundary |
| cancel | 由消费停止和 interrupt notification 传播 |

## 坑位与排错

- 只有 `query` 没有流：先实现真实 `stream_query`，不要在入口用线程阻塞伪造 SSE。
- 终答缺失：检查 translator 是否把 final event 转为 `of_final_answer`。
- 终态卡住：检查 async generator 是否正常结束。
- 中断无法恢复：检查 interaction/recovery point 是否持久化。
- 框架异常泄漏：检查 `format_error` 和 adapter 的异常边界。

可复制起点：确定性替身见 [`snippets/handler-fixture.py`](../snippets/handler-fixture.py)，不绑定框架版本的适配器契约见 [`snippets/framework-adapter-contract.py`](../snippets/framework-adapter-contract.py)。

## 端到端校验

执行最小单轮、流式、多轮、工具、取消、中断/恢复和关闭测试；再通过 A2A 或 REST socket 验证 wire。AgentCore/AgentScope 的真实接线状态分别看 validation 报告，不以本页示例替代。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime/ports/handler.py:AgentHandler`
- `agent_runtime/adapters/outbound/agentcore/handler.py`
- `agent_runtime/adapters/outbound/framework/agentscope.py`
- `agent_runtime/application/serve.py`

## See also

- [ReAct Agent 指南](react-agent.md)
- [Versatile 对接](versatile-agent.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [Runtime 公开接口](../api/agent-runtime-python.md)
