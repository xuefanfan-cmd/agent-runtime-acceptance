---
title: VersatileAgentHandler：把远端 versatile 工作流包成 Agent 参与编排
description: 用 agent-service-adapters-versatile 的 VersatileAgentHandler 将外部 versatile（HTTP+SSE）工作流包装为标准 OpenJiuwen Agent，并经 A2A 被 ReAct/主控编排
audience: ai-coding
status: verified
examples:
  - examples/versatile
---

# VersatileAgentHandler：把远端 versatile 工作流包成 Agent

「versatile」是外部工作流执行平台：协议为 **HTTP POST + SSE 流式应答**、按
`conversation_id` 有状态推进。`VersatileAgentHandler`（runtime-ext 的
`agent-service-adapters-versatile`）把一个远端 versatile 工作流**包装成标准
OpenJiuwen Agent**，使其可以像本地 agent 一样被托管（REST `/v1/query`）、以 A2A skill
暴露、并把 versatile 侧的「需要人工输入」翻译为框架标准中断/续传（`INPUT_REQUIRED`）。

> 红线：对接逻辑全部收在 adapter 层，**不侵入 core-java**；业务代码不直接拼 versatile 报文。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 已存在 versatile 平台上的工作流，需要纳入 OpenJiuwen 编排体系（被 ReAct 当工具、被网关代理） |
| ✅ 适用 | 需要把 versatile 的人工交互节点翻译成标准 INPUT_REQUIRED 中断 |
| ❌ 不适用 | 工作流逻辑可以在本地用代码表达——用 [WorkflowAgent 编排](workflow-agent.md)，少一层网络协议 |
| ❌ 不适用 | 对端已是标准 A2A agent——支持自动注入的 ReAct/BaseAgent 或 DeepAgent 内部 BaseAgent 可直接声明 `remote-agents`；WorkflowAgent 主控需显式建模 Tool/组件 |

### 在架构中的位置

```
caller ──REST/A2A──▶ agent-service-app ──▶ VersatileAgentHandler（本 adapter）
                                               │  信封重组 / SSE 消费 / 结果抽取 / 中断翻译
                                               ▼
                                     versatile 工作流端点
                              POST <url-template 渲染>（conversation_id 有状态）
```

## 最小完整示例

完整代码在 **[examples/versatile/](../examples/versatile/)**
（3 个文件：`VersatileAgentApplication.java` / `VersatileAgentConfiguration.java` /
`application.yml`）。装配只需**一个 Bean**（HttpClient / 请求抽取 / 响应抽取由
handler 内部组装）：

```java
@Bean
AgentHandler versatileAgentHandler(VersatileProperties properties) {
    return new VersatileAgentHandler(properties);   // 内部自组装 client/请求抽取/响应抽取
}
```

关键配置摘录（完整 yml 见示例目录）：

```yaml
openjiuwen:
  service:
    agent-id: versatile-agent
    versatile:
      # {conversation_id} 占位符逐调用填充；会话状态由 versatile 侧按 cid 维护
      url-template: ${VERSATILE_URL:http://127.0.0.1:31113/v1/<projectId>/agents/<agentId>/conversations/{conversation_id}}
      result-node-name: RESULTNODE    # 从 SSE 帧中抽取结果的节点名（按工作流定义填）
```

示例目录中的三个文件覆盖该适配器最小部署形态所需的框架侧代码与配置；依赖版本见兼容性页。

## 能力点逐个展开

### 中断/续传（框架内建，无需配置）

versatile 工作流中途需要人工输入时，adapter 自动把对应的 SSE 帧翻译为框架标准
中断：对上层（REST 调用方 / A2A 主控）表现为统一的 `INPUT_REQUIRED` 语义——
第一轮拿到中断帧 → 记录 conversation/task 上下文 → 续传时回灌，工作流继续推进。
该翻译由框架内建完成，**没有需要用户设置的属性**。

## 配置项参考（openjiuwen.service.versatile.*）

定义见 `VersatileProperties`：

- **url-template**：versatile 会话端点模板，`{conversation_id}` 占位符逐调用填充。必填。
- **timeout**：单次调用超时，默认 600s。
- **insecure-skip-verify**：跳过 TLS 校验，默认 false（仅调试用）。
- **headers-template**：发往 versatile 的静态 header 模板。
- **forward-header-whitelist**：允许从入站请求透传的 header 白名单（安全边界，默认空）。
- **result-node-name**：结果节点名；SSE 流中该节点的输出被抽取为 agent 答案。
- **result-extractions[]**：结果后抽取规则，`get`（JSON 路径）→ `match`（存入键）。
- **log-mask-sensitive**：DEBUG 日志是否脱敏 `messages[].content` 等字段，默认 true；
  仅在日志落点已受控的本地调试时设 false。

> ⚠️ **可设置边界**：只有上面列出的属性是支持用户设置的。`VersatileProperties`
> 中的其余属性（意图清单、消息取材、意图路由映射、中断映射、歧义自愈等）属于
> 框架内部配置，**不要设置**——设置后不保证生效，且可能随版本变化。

## 坑位与排错

> ⚠️ **会话有状态且 END 解绑**：同一业务场景全程共用同一 `conversation_id`，
> versatile 按每步 `intent` 推进；**场景走到 END 后绑定解除**，同 cid 再调会重新绑定。
> 续传报文必须匹配当前场景状态，不能由调用方随意编造。

> ⚠️ **抽不到结果先查 result-node-name**：答案落在该配置指定节点的 SSE 帧上，
> 与工作流定义不一致时表现为「调用成功但 content 为空」。

> ⚠️ **SSE 头缺失**：`headers-template` 中 `accept: text/event-stream` 与
> `stream: "true"` 缺失会导致拿不到流式帧。

> ⚠️ **name 一致性**：A2A 卡片 name 取自 `spring.application.name`。调用本服务的一方若使用
> `remote-agents` 自动注入，其 `remote-agents[].name` 必须与该值相等，否则发现/注入失败。

## 端到端校验

1. 准备一个可达的 versatile 端点（或 mock），把地址填入 `VERSATILE_URL`。
2. 启动服务，直接调 REST 面验证 adapter 翻译是否正确：

```bash
curl -X POST http://localhost:18091/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message": "<触发工作流的自然语言>", "conversation_id": "demo-c1", "stream": false}'
```

预期：响应 result 中 `content` 为 `result-node-name` 节点抽取出的结果。

3. 验证中断链路：触发一个会要求人工输入的工作流，预期流式帧含 `type=interrupt`；
   用同一 `conversation_id` 携带人工输入续传，工作流继续推进。
4. 验证 A2A 暴露：访问 agent 卡片端点确认 skill `versatile-step` 已发布，
   再从主控 agent（按 [A2A 指南](a2a.md) 的主控侧配置）发起调用。

## API 锚点（jar 内类，按依赖可查）

- Handler：`com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler`
- 配置：`com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties`
- 完整示例：[../examples/versatile/](../examples/versatile/)（本工程自有）

## See also

- [WorkflowAgent 编排指南](workflow-agent.md)：被本 agent 之类的 A2A 主控调用的「远端 Workflow」一侧
- [runtime-ext 接口文档](../api/runtime-ext.md)：adapter 清单与 remote-agents 速览
