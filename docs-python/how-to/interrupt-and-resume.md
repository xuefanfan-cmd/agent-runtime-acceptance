---
title: 取消、中断与续接
description: 区分客户端取消、用户交互中断和远端委派，并实现可恢复的任务流程。
audience: both
status: verified
examples: ../examples/interactive/
snippets: ../snippets/interrupt-resume-flow.py
---

# 取消、中断与续接

## 适用场景 / 不适用场景

**适用**：用户需要补充信息、确认工具操作、远端 Agent 要求继续输入，或客户端在流式响应中断开时，使用 runtime 的 interrupt/resume 语义。

**不适用**：普通模型失败应使用 `error`；不需要暂停执行的普通多轮对话不要伪装成 `input-required`；客户端断连是取消，不等同于用户中断。

## 最小完整示例

```python
from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk

async def run(handler, request):
    async for chunk in handler.stream_query(request):
        if chunk.type == QueryChunk.TYPE_INTERRUPT:
            return chunk

async def resume(handler, conversation_id, resume_input):
    request = ServeRequest.for_resume(
        conversation_id=conversation_id,
        resume_input=resume_input,
    )
    return [chunk async for chunk in handler.stream_query(request)]
```

## 能力点逐个展开

### 用户中断

Handler 产出 `QueryChunk.of_interrupt(content=..., interaction_id=...)`。编排层将 Task 置为 `input-required`，保存 recovery point，入口把可恢复信息投影给客户端。

### 客户端取消

消费侧停止请求下一帧；实现通过 async generator 的 `finally` 释放资源。不要给 `AgentHandler` 添加自定义 `cancel()`，当前端口没有这个方法。

### 远端委派

远端委派载荷仍是 `interrupt`，由 `ServeOrchestrator`/batch runner 消费；远端业务输出是 `remote_agent_output`。不要把这两种帧都投影为用户追问。

### 恢复

恢复必须携带原 conversation/task/recovery point 和用户输入，adapter 再翻译为框架原生 resume。恢复请求不能直接调用模型绕过 Handler。

## 配置项参考

| 状态 | 产生条件 | 下一步 |
|---|---|---|
| `working` | 普通 chunk/远端业务输出 | 继续消费 |
| `input-required` | 用户交互 interrupt | 保存状态，等待 resume |
| `completed` | 流正常结束且有终答 | 固化结果 |
| `failed` | error/不可恢复异常 | 固化错误 |
| cancelled | 客户端停止消费 | 清理在途资源 |

TaskStore、SessionStore 和框架 session 必须分别定义恢复边界；单进程 Memory 不能证明多副本恢复能力。

## 坑位与排错

- 没有 `interaction_id` 时，确认是否走的是 raw input 恢复路径。
- 恢复后重复执行时，检查 task/recovery key 是否幂等。
- Task 进入 completed 但没有正文时，检查终答是否被 completion sentinel 吞掉。
- 断连后进程仍有模型请求时，检查 `finally`、`aclose()` 和上游取消传播。

## 端到端校验

覆盖：首次请求得到 `input-required`、提交恢复、正常终答、空恢复输入、重复恢复、客户端断连、进程重启和共享 store 缺失。

## API 锚点（包内符号，按依赖可查）

- 编排：`agent_runtime/application/serve.py`
- 活跃流：`agent_runtime/application/active_streams.py`
- 中断端口：`agent_runtime/ports/interrupt.py`
- 领域帧：`agent_runtime/domain/result.py:QueryChunk.of_interrupt`
- 设计：`Feat-Func-008b-user-interaction-interrupt-and-response.md`

## See also

- [Task 状态与缓存](state-and-cache.md)
- [Workflow 的人工审批分支](workflow-agent.md)
- [生命周期、Task 与外置状态](../architecture/02-agent-runtime-python技术架构.md)
