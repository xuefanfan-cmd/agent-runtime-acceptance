---
title: agent-solution 技术架构
description: 扩展方案仓八个模块的定位与语言归属，以及 Python 侧最重要的一条——runtime-ext 与 runtime 合仓；含本文档树的收录边界
audience: both
---

# agent-solution 技术架构

## 一、工程定位

`agent-solution` 是 OpenJiuwen 的**扩展方案仓**：把 core 与 runtime 之外的协议扩展、总线、端侧 SDK、自演进引擎与具体 Agent 实现收在一处。本文档树只展开其中与 Python runtime 相邻的模块，其余只记边界。

## 二、模块总览与语言归属

| 模块 | 语言 | 与本文档树的关系 |
|---|---|---|
| `agent-runtime-ext-python` | Python | **Python 侧的 runtime 本体**——目录名的 `ext` 是历史命名，不是谁的扩展 |
| `agent-runtime-ext-java` | Java | Java 运行时的扩展构件，与 Python 侧无关 |
| `agent-core-ext-java` | Java | Python 侧直接用原生 agent-core，无对应包 |
| `agent-bus` | Java | Python runtime 侧只提供端口与适配位 |
| `agent-client` | Java | Python 侧无对应包 |
| `agent-evolve` | Python | 当前 runtime 不承载 |
| `agents` | 混合 | 含存量宿主 Agent 的 Python 与 Java 两版实现 |
| `example` | 混合 | 示例拓扑，不属本文档树收录范围 |

## 三、agent-runtime-ext-python：Python 侧的 runtime 本体

**这个目录不是扩展，它就是 Python 的 runtime。** 目录名里的 `ext` 是历史命名，runtime 本体直接落在 solution 仓里；它与 `openJiuwen/agent-runtime`（早期 Python 仓）也没有承继关系。

扩展能力不另发包：Versatile、异构框架适配、技能中心客户端、自定义 REST 通道全在 `openjiuwen-agent-runtime` 包内的 `adapters/` 下，装一个包就都有。

后果有两条，装配时必须知道：

1. **没有「按需追加扩展包」这一步**。能力开关在**配置段**（如 `skill_hub.enabled`），不在依赖清单。
2. **可选依赖仍分得开**。状态外置需要的 redis 客户端是可选依赖组，不配缓存段就不需要装，见[共享最小工程模板](../examples/minimal-agent-service-pyproject.toml)。

模块内的适配器矩阵见 [Runtime 扩展与适配器接口](../api/runtime-ext.md)。

## 四、agent-core-ext-java：认知能力扩展（Python 无对应包）

该模块是 Java 实现，给 ReAct 补 verify、replan、self-heal 三条认知 rail。**Python 侧直接使用 `openjiuwen` 原生 agent-core，没有扩展包**，这三条 rail 无对应实现。需要同类能力时用 `AgentRail` 钩子链自行实现，见 [Rail 指南](../how-to/rails.md)。

## 五、agent-bus：Agent 总线与入口平面

该模块是 Java 实现，承担 Agent 之间的事件分发与统一入口。Python runtime 侧只提供**总线端口与适配位**（`ports/bus.py`、`adapters/{inbound,outbound}/bus`），具体总线实现由宿主装配决定。当前交付没有真实总线的端到端证据，见 [总线事件订阅](../how-to/bus-events.md)。

## 六、agent-client：端侧访问 SDK

该模块是 Java 实现的端侧 SDK，供调用方接入 Agent 服务。**Python 侧无对应包**：调用方按 A2A 标准协议或宿主自定义 REST 协议直接接入，协议面见 [A2A](../how-to/a2a.md) 与[自定义 REST 入口](../how-to/custom-rest.md)。

## 七、agent-evolve：自演进引擎

Python 实现（`evoagent`、`evoagent-adapter`、`toolkits`）。**当前 runtime 不承载**：没有托管接线，也没有配置面。业务需要时在 agent-core 层直接使用，不要期待 runtime 配置树里出现它的开关。

## 八、agents：具体 Agent 实现

含存量宿主 Agent 的两版实现（Python 与 Java）。本仓的 runtime 提供**存量兼容入口**原位装载 Python 版（`bootstrap.legacy_compat`），作为迁移过渡形态；判据是 SDK 形态下同一组 wire 判据全部通过后才移除存量入口，见[部署与切换](../how-to/deployment.md)。

存量代码的读法有纪律：只读**对外行为面**（wire 契约、共享键面与值格式、错误信封），不评价其内部设计。

## 九、example：示例拓扑

总线消费、端侧工具、远端 A2A 工具等拓扑示例。**不属本文档树收录范围**——本树的可复制源码只有 [`examples/`](../examples/overview.md) 下的八个目录。

## 十、本文档树的收录边界

| 收录 | 不收录 |
|---|---|
| Python runtime 及其 `adapters/` 下的全部扩展 | 其余模块的内部设计 |
| 存量 Agent 的对外行为面（兼容分析所需） | 存量 Agent 的内部实现好坏 |
| 自演进引擎的边界说明 | 自演进引擎的落地方案 |

不收录不等于不重要，是**不在本 runtime 的责任面内**；需要时回各自仓库读。

## See also

- [agent-runtime Python 技术架构](02-agent-runtime-python技术架构.md)
- [协作与扩展体系](04-协作与扩展体系.md)
- [Runtime 扩展与适配器接口](../api/runtime-ext.md)
