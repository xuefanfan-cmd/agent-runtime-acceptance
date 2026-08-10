---
title: runtime-ext 接口文档（agent-runtime-ext-java）
description: 当前范围内的 versatile、agentcore-ext、SkillHub 与 Custom REST 扩展及其 artifact 归属
audience: ai-coding
---

# runtime-ext 接口文档

runtime-ext 是 agent-solution 仓内对 agent-runtime-java 的扩展模块集合：在不修改 runtime
内核的前提下，增加外部协议 adapter、增强版 handler、SkillHub 与 Custom REST 接入。
本页只覆盖当前文档范围，不展开 AgentScope、Bus、Gateway、Registry、Evolution、EDP 等模块。

## 扩展与 artifact 对照

| 能力 | Artifact | 关键公开类 | 一句话说明 |
| --- | --- | --- | --- |
| versatile adapter | `agent-service-adapters-versatile` | `com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler` | 把 versatile（HTTP+SSE）工作流端点包成标准 AgentHandler |
| agentcore-ext handler | `agent-service-adapters-agentcore-ext` | `com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler` | 基础 handler + 远端 A2A 工具注入 + SkillHub 注入点 |
| SkillHub SPI | `agent-service-spec-ext` | `com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider` | 自定义技能源与 SkillHub 配置契约 |
| Custom REST | `agent-service-app-custom-rest` | `com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter` | 宿主 REST/SSE 协议与 runtime A2A 契约双向桥接 |

当前推荐版本统一见 [版本兼容与上游锚点](../compatibility.md)。

## versatile adapter 速览

接入方式与本地 handler 一致：提供一个 `AgentHandler` Bean。

```java
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersatileProperties.class)
public class VersatileAgentConfiguration {

    @Bean
    AgentHandler versatileAgentHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}
```

配置前缀 `openjiuwen.service.versatile.*`，用户可设置项：

- **url-template**：会话端点模板，`{conversation_id}` 占位逐调用填充。必填。
- **result-node-name**：SSE 流中承载最终结果的节点名。
- **timeout**：默认 600s。**insecure-skip-verify**：默认 false。
- **headers-template / forward-header-whitelist**：出站静态 header / 入站透传白名单。
- **result-extractions[]**：结果后抽取规则（`get` → `match`）。**log-mask-sensitive**：日志脱敏，默认 true。

> 其余属性（意图路由、中断映射、歧义自愈等）为框架内部配置，不属于用户可设置边界。
> 完整装配示例与编排模式见 [Versatile 对接指南](../how-to/versatile-agent.md)。

## 远端 A2A 工具注入速览

`JiuwenCoreAgentExtHandler` 必须接收已构造的 Agent 实例并由应用手动声明 Bean；
`handler` 配置不会自动构造 ext handler。

```java
AgentHandler handler = new JiuwenCoreAgentExtHandler(agent);
```

```yaml
openjiuwen:
  service:
    a2a:
      remote-agents:
        - name: <远端 spring.application.name>
          url: ${REMOTE_CARD_URL:}
```

> ⚠️ `remote-agents → tool` 的自动注入**不是对所有 Agent 类型通用**。
> 当前实现只会解析 `BaseAgent`（包括 ReActAgent）或 `DeepAgent` 内部的 BaseAgent；
> `WorkflowAgent` 不能作为主控通过这段配置自动获得远端工具。WorkflowAgent 仍可作为
> A2A 被调用方；若它要主动调用远端 Agent，应在 workflow 内显式建模相应 Tool/组件。

注入发生在每次执行前；支持类型上的远端 skill 会变成本地工具，中断
（`INPUT_REQUIRED`）沿工具调用链透传。完整边界见
[A2A 跨智能体调用机制](../how-to/a2a.md)。

## API 锚点（jar 内类，按依赖可查）

- versatile：`com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler`、`...versatile.autoconfigure.VersatileProperties`
- ext handler：`com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler`
- SkillHub SPI：`com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider`、`...spec.ext.skillhub.SkillHubConfig`
- Custom REST SPI：`com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter`

## See also

- [agent-runtime-java 接口文档](agent-runtime-java.md)
- [A2A 跨智能体调用机制](../how-to/a2a.md)
- [Workflow ↔ Versatile 对接指南](../how-to/versatile-agent.md)
- [SkillHub 技能注入](../how-to/skillhub.md)
- [自定义 REST 入口](../how-to/custom-rest.md)
- [配置驱动 Agent](../how-to/config-driven-agent.md)
