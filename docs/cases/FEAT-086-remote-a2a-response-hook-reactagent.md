---
feature_id: FEAT-086
test_type: reactagent
scope: v1030
deployable_units: [agent-runtime, multi-react-travel-demo, multi-react-travel-hosted-demo]
sut: real default and hosted ReAct travel agents
features: [FEAT-086]
updated: 2026-10-23
---

# ReAct Travel Runtime 验收：远程 A2A 响应投影与结果提取 Hook

## 1. 测试目标

从公开 Query SSE、A2A、原始模型 HTTP 请求及明确标注的公开 SPI 集成面验证 FEAT-086；展示编辑不改变原任务，成功结果精确回灌。Feature 与 L2 本轮比较未发现必须先解决的语义冲突。全部场景先由需求拆分，再核对实际 Fixture 能力。

## 2. 范围与非范围

范围：两个 Hook 独立可选、普通和 Hosted 装配、三类投影的编辑/隐藏、关联上下文、默认兼容、结果提取、四种流式组合、回调、错误、并行、恢复和隔离。所有能力表中的 MUST 均保留独立场景，状态变体仅在相同入口与 Oracle 下参数化。

非范围：修改 A2A SDK、solution 网关 Caller 或自定义 Transport wrapper；不把这些 OUT 组件的改造作为门禁。公开 SPI 探针验证属于集成验证，不能宣称为纯 HTTP 黑盒。模型生成的自然语言措辞不作逐字断言。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-086-remote-a2a-response-hook.md`（2026-10-19，v1030） | MUST 能力、隔离、错误与 OUT 边界。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-086-remote-a2a-response-hook.md`（2026-10-19） | §2—7 接口/装配/错误/流式；§8 默认字段、算法与生命周期兼容。 |

## 4. 部署拓扑

```text
Query/A2A driver -> mainplan -> trip -> hotel
Query/A2A driver -> Hosted agent-a / agent-b -> hotel
各真实 Agent -> 透明请求记录器 -> 用户指定真实 LLM
边界用例：受控 A2A 事件源提供输入；公开 SPI 探针提供自定义策略与调用记录
```

指定两个 Demo 都保留；普通完整链路与 Hosted 连接真实远端一起启动。记录器不伪造模型回复、不记录认证头。受控事件源只制造远端输入，不能替代父 Runtime 被测投影、聚合或结算。测试只通过公开接口、实际模型请求和探针输出判断；不访问内部状态、数据库或反射。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F086-01 | 普通装配：Artifact 编辑 | 普通父 Agent 启用投影前缀 | 完整差旅请求触发远程流式调用 | 远程 output 的文本带唯一前缀 | 真实旅行链 + SSE 观察器 |
| F086-02 | 普通装配：Status 编辑 | 普通父 Agent 启用投影前缀 | 观察远程 status | 状态文本带前缀，来源仍指向远端 | 真实旅行链 + SSE 观察器 |
| F086-03 | 普通装配：delegation 隐藏 | 投影关闭委派提示 | 调用 trip 并观察后续远端事件 | 没有父层 delegation；远程 output/status 继续出现 | 真实旅行链 + SSE 观察器 |
| F086-04 | 普通装配：空字符串精确回灌 | 结果 Hook 选择不存在的 Artifact ID | 完成远程调用，截取父 Agent 后续真实模型请求 | 对应 tool_call_id 存在且 content 精确为双引号空串 | 真实旅行链 + 透明 LLM 请求记录器 |
| F086-05 | Hosted 装配：Artifact 编辑 | Hosted 实例 A 启用投影前缀并连接 hotel | 实例 A 请求远程酒店服务 | 远程 output 文本带唯一前缀 | Hosted + 真实 hotel + SSE 观察器 |
| F086-06 | Hosted 装配：空字符串精确回灌 | Hosted 结果 Hook 选择不存在的 Artifact ID | 远程成功后观察真实模型请求 | 原工具调用 ID 对应空 content 保留 | Hosted + 真实 hotel + 透明 LLM 请求记录器 |
| F086-07 | Query SSE 出口裁剪 | 自定义投影显式复用默认投影 | 收集 remote_agent_output 帧 | 数据仅含 content 与 metadata.agentEvent，无内部终态标记 | 真实旅行链 + SSE 观察器 |
| F086-08 | 默认结果非空回灌 | trip 未启用自定义结果 Hook，hotel 成功 | 观察 trip 下一次模型请求 | hotel 的工具调用 ID 在真实后续模型请求中收到非空结果 | 真实旅行链 + 透明 LLM 请求记录器 |
| F086-09 | 仅投影 Hook | 只注入投影 Bean | 调用成功 Task | 展示自定义，结果保持默认算法 | 完整真实旅行链 + SSE/Task 查询 + 透明 LLM 记录器 |
| F086-10 | 仅结果 Hook | 只注入结果 Bean | 调用成功 Task | 展示保持默认，结果按 Hook 原值回灌 | 完整真实旅行链 + SSE/Task 查询 + 透明 LLM 记录器 |
| F086-11 | 重复投影 Bean | 注入两个同接口实现 | 启动普通和 Hosted 服务 | 启动失败且可识别装配歧义 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-12 | 重复结果 Bean | 注入两个结果实现 | 启动普通和 Hosted 服务 | 启动失败，不任意选择 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-13 | 旧手动构造兼容 | 使用文档公开旧构造入口 | 执行远程成功调用 | 默认展示与结果可用 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-14 | Artifact 隐藏 | 投影对 Artifact 返回 null | 交付带唯一正文 Artifact | 前端不展示远程 output；远端 Task 完成且 Artifact 保留，默认非空工具结果仍回灌 | 完整真实旅行链 + SSE/Task 查询 + 透明 LLM 记录器 |
| F086-15 | Status 隐藏 | 投影对 Status 返回 null | 交付 working/completed | 不展示本次直接远端 Status；级联远端 status 经 Artifact 转发由 Artifact 策略决定；仍按 completed 成功结算 | 完整真实旅行链 + SSE/Task 查询 + 透明 LLM 记录器 |
| F086-16 | delegation 编辑 | 返回独有委派文本 | 首次远程事件到达 | 只发送一次自定义文本并先于远端事件 | 完整真实旅行链 + SSE/Task 查询 + 透明 LLM 记录器 |
| F086-17 | 只读调用上下文 | 探针输出关联字段 | 并发两个父 Task 调用不同远端 | remoteAgentName/parentAgentId/parentTaskId/toolCallId 各自准确且不可变 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-18 | 自定义结果不二次装饰 | 投影返回合法独有 ID、metadata、parts | 通过公开流式 SPI 观察投影 Artifact | 字段原值保留，不补默认 source/remote ID | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-19 | null metadata | 投影返回合法 Parts、metadata=null | 请求 Query SSE | metadata.agentEvent 明确为 null，无序列化异常 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-20 | 投影运行时异常 | 投影在指定事件抛异常 | 触发该事件 | 当前调用/批次错误传播，无默认回退或该条展示 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-21 | delegation 一次消费 | 第一次委派投影返回 null | 连续多个远程事件 | 探针委派调用次数为一，后续不重试 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-22 | Status 去重含过滤 | 相同 state/parts 两次；第一次隐藏 | 连续交付事件 | 同内容第二次不再投影，timestamp/metadata 不影响去重 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-23 | Status 同状态新内容 | 相同 state 不同 parts | 连续交付事件 | 两条均投影 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-24 | 隐藏终态抑制后续 | 最终 Status 被隐藏 | 再交付 Artifact/Status | 后续均不投影，终态仍生效 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-25 | 默认 delegation 字段 | 无投影 Hook | 交付首次状态；输入 message 有值或空白 | message 优先，否则默认文案；ID/source/target/toolCallId 正确；append=false/lastChunk=true | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-26 | 默认 Artifact 字段 | 无 agentEvent 的 Artifact 含终态与业务 metadata | 流式转发 | 生成 output/source 与 remote ID；仅展示副本删除终态键，保留 Parts/其余字段/append | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-27 | 已有来源标签保留 | 已有非 null agentEvent 及原 ID | 链式转发 | 标签与 ID 原值保留，不累加链路数组 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-28 | 默认 Status Parts | 状态含空列表、空文本或正常 Parts 变体 | 投影状态 | 仅 null/空列表回退规范状态名，空文本不回退 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-29 | A2A 外层父上下文 | 远端 Task ID 与父 ID 不同 | 观察 A2A Artifact 更新 | 外层 taskId/contextId 为父值，内部 source 仍为远端 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-30 | 完整 Task 快照 | 流式 Caller 收到完整 TaskEvent | 快照含历史 artifacts | 仅投影 status，不补历史 artifacts | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-31 | Message-only | 远端返回独立 Message | 完成调用 | 投影与 Task 结果 Hook 均未触发；Message 结果沿用默认 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-32 | Artifact 业务提取 | 原 Task 包含带 agentEvent 的 Artifact | 自定义 Hook 读取原 artifacts | 未被默认展示标签预过滤，字符串精确回灌 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-33 | Status 业务提取 | 成功 Task 当前 Status 含指定文本 | result-source=status | 工具 content 精确为状态文本 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-34 | History 业务提取 | 原 Task history 含多条消息 | result-source=history | Hook 可读取历史并按策略输出 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-35 | 普通字符串精确回灌 | Hook 返回含空白/换行的字符串 | 成功结算 | 后续模型请求对应工具 content 原值不裁剪 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-36 | 双流式开启 | 用户流式、远端流式 | 调用成功 Task | 实际 SendStreamingMessage；投影和结果均生效 | 完整真实旅行链 + A2A 路由记录器 + 透明 LLM 记录器 |
| F086-37 | 入口关闭流式 | 用户非流式、远端流式 | 调用成功 Task | 实际 SendMessage；无合成投影/委派；结果生效 | 完整真实旅行链 + A2A 路由记录器 + 透明 LLM 记录器 |
| F086-38 | 远端关闭流式 | 用户流式、远端非流式 | 调用成功 Task | 实际 SendMessage；无合成投影/委派；结果生效 | 完整真实旅行链 + A2A 路由记录器 + 透明 LLM 记录器 |
| F086-39 | 双流式关闭 | 两端非流式 | 调用成功 Task | 实际 SendMessage；无合成投影/委派；结果生效 | 完整真实旅行链 + A2A 路由记录器 + 透明 LLM 记录器 |
| F086-40 | 普通 completed | 远端正常完成 | 结算成功 Task | 结果 Hook 仅一次且输入原始聚合 Task | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-41 | 回调 completed | 已注册回调 | 发送完成回调 | 结果 Hook 生效并一次恢复父 Agent | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-42 | 回调非终态成功提升 | null/UNRECOGNIZED/SUBMITTED/WORKING 状态，默认 Task 或 Status 文本非空 | 回调结算 | 局部成功，Hook 看到原状态；非 completed 不导出响应 metadata | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-43 | 回调不满足提升 | 非终态且默认文本空 | 发送回调 | 不试调用 Hook 探测成功，不提前恢复 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-44 | 失败不成功提取 | 远端 FAILED/REJECTED | 交付含部分 Artifact 的失败 Task | 无成功 Hook；保持失败错误协议 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-45 | 取消不成功提取 | 远端 CANCELED | 取消并交付终态 | 无成功 Hook，不变为成功 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-46 | 输入中断不成功提取 | INPUT_REQUIRED/AUTH_REQUIRED | 交付部分内容并等待 | 无成功回灌，保持输入提示协议 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-47 | 等待回调 | 回调模式非最终响应 | 收到等待状态 | 保持 Remote callback pending；无部分结果回灌 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-48 | 未完成流关闭 | working 与部分 Artifact 后断流/超时 | 观察错误 | 不把部分 Artifact 当成功，Hook 未触发 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-49 | 中断恢复 | 中断前后均有 Artifact | 用户提供输入后完成 | 按原 Task 聚合提取，不擅自裁剪旧内容 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-50 | 独立 Caller null | 结果 Hook 返回 null | 调用独立 Caller | Future 异常，无默认回退、不重发 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-51 | 独立 Caller 异常 | 结果 Hook 抛运行时异常 | 调用独立 Caller | Future 异常、异常可追溯 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-52 | 普通批次 null | 一成员结果 null，其余成功 | 等待批次 | 失败码 REMOTE_RESULT_HOOK_ERROR，无伪 remoteError/responseMetadata；其它成员 settled；原 Task 不变 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-53 | 普通批次异常 | 一成员结果抛异常 | 等待批次 | 本地结果处理失败，不归类远端不可用/限流/协议错误；不重发 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-54 | 回调批次 null | 回调成功 Task 的结果 Hook 返回 null | 回调结算 | 本地 REMOTE_RESULT_HOOK_ERROR，其他成员继续 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-55 | 回调批次异常 | 回调结果 Hook 抛异常 | 回调结算 | 本地结果处理失败，无回退或重复请求 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-56 | 多调用归位 | 同批不同 toolCallId 交错完成 | 观察公开 AgentHandler 恢复请求 | 所有结果按原 ID 归位，all-settled 后一次恢复 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-57 | 多片段单结果 | 同一远端多个流式 chunk | 完成后回灌 | SDK 聚合结果一次，不每帧生成工具结果 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-58 | 批次含等待成员 | 一成员完成另一成员等待输入 | 观察父 Agent | 先中断，不提前回灌已完成成员 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-59 | 替换 Caller 展示 | 自定义 Caller 交付可观察事件 | 触发调用 | 事件仍经投影 Hook | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-60 | 替换 Caller 私有结果 | 自定义 Caller 返回私有结果 | 普通成功结算 | 结果 Hook 不自动接管其私有提取 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-61 | 替换 Caller 回调 | 替换 Caller 发起回调路径 | 回调成功 | 仍应用结果 Hook | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-62 | 默认 agentEvent 过滤 | Artifact agentEvent 键存在，值含 null 变体 | 无结果 Hook 完成 | 整项被跳过，即使还带 terminal=true | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-63 | 默认终态选择 | 多个终态候选，最后候选空串变体 | 提取结果 | 最后候选胜出，包括空串；不回找前一非空 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-64 | 默认普通拼接 | 无终态的多个普通 Parts | 提取结果 | 按 SDK 列表顺序无分隔拼接 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-65 | 默认 Part 转换 | Text/Data/File 变体 | 成功提取 | Text 原样；Data 按 envelope/JSON；File 忽略 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-66 | 终态 envelope 优先级 | answer/workflow_final，payload/顶层字段混合 | 成功提取 | payload 优先；content/delta/output/response；跳过空白，保留 false/0/空集合 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-67 | 中间 envelope 过滤 | type 字符串/index/payload Map | 默认提取 | 中间数据跳过；非匹配普通数据保留 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-68 | 默认 Status 回退 | Task 结果空白，Status Text/Data 混合 | 完成提取 | 仅 Status Text 拼接；无合成 completed；不读 history | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-69 | SDK append/替换兼容 | 同 ID 替换与 append，新 ID append | 远端按序发事件 | 结果使用 SDK 聚合列表，不按到达时间重新排序 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-70 | 响应 metadata 分离 | completed Task 的保留 metadata 为 Map | 成功回灌 | 单独传递 metadata，不混入字符串；非 Map 不导出 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-71 | 直接回复合并 | 多个成功、失败、空与空白结果 | 使用不续接父 Agent 的直接回复 | 跳过失败/null/空串，其余按批次顺序换行连接 | 受控 A2A 事件源 + 公开 SPI 探针 |
| F086-72 | Hosted 实例隔离 | A/B 不同远端目录、并发请求 | 两实例执行 | 上下文、结果 toolCallId 与展示来源不串实例 | 受控 A2A 事件源 + 公开 SPI 探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| multi-react-travel-demo | 真实 SUT | mainplan 接收完整差旅请求；trip 规划并委派 hotel；hotel 提供酒店业务；三者均使用真实 LLM。 |
| multi_react_travel_hosted_demo | 真实 SUT | 单进程实例 A/B；按实例目录连接 hotel，验证 Hosted 装配。 |
| SSE/A2A 观察器 | Fixture | 记录公开原始事件，区分父层与级联来源。 |
| 透明 LLM 记录器 | Fixture | 原样转发请求正文到用户模型，记录不含凭据的正文和 HTTP 状态；以真实后续请求检查 tool_call_id/content。 |
| A2A 路由记录器 | Fixture | 只重定向 Agent Card 地址并透传真实远端协议，记录实际调用方法，不生成 Task 或结果。 |
| 受控 A2A 事件源及公开 SPI 探针 | 已实现 Fixture | 远端 HTTP/SSE 只提供精确输入；真实 SDK/Runtime 负责聚合、投影和结算。两个 Demo 的显式测试 profile 提供 null/throw/metadata/history 策略及原 Task 观测；公开 AgentHandler/Caller/Hook 探针覆盖批次与编程契约，不使用内部协调器或反射。 |

## 7. 关键链路断言

- Feature §2—6：展示只读隔离、空串精确保留、成功入口、不成功入口、结果错误与不重发分别断言；不能用最终自然语言答案代替工具消息 Oracle。
- L2 §3.4：实际远程流式为入口与远端开关的逻辑与，三种非流式组合不得补发历史投影。
- L2 §8.1：区分展示 Artifact metadata、事件 metadata 与原始 Task；级联 delegation 可存在，隐藏父层 delegation 不等于禁止下游自己的 delegation。
- L2 §8.2：默认筛选与自定义 Hook 输入分离；SDK 聚合列表不是事件日志；空内容工具结果与直接回复拼接规则不同。
- Live LLM 若未产生目标远程工具调用则触发不足，结果为 INCONCLUSIVE，不能当作 Hook 产品失败；网络/认证错误归环境 ERROR。Hook 明确违反可达 Oracle 才进入产品疑似复测。

## 8. 执行策略

2026-10-23 最终场景结果：69 PASS、1 FAIL（F086-11，Hosted 重复投影 Bean 未拒绝启动，三次确认）、2 INCONCLUSIVE（F086-28/42 的 SDK 不可构造变体）。72 项均已执行，无未执行场景；方法层面 71 PASS、1 FAIL，不将合法变体通过等同于所有输入变体全覆盖。

完整验收集保持 F086-01—72，每项独立方法与 Oracle。2026-10-23 已补齐此前 55 项的实现，并按最小影响集执行；测试执行结果与可达边界单独记录，不以方法存在、编译成功或 Maven BUILD SUCCESS 代替通过证据。

Smoke：F086-01、F086-04、F086-05、F086-06。2026-10-22 clean 制品的 17 项有效证据保留复用；新增测试 profile 默认关闭，不改变既有业务配置。新 profile 制品另做 clean 构建与嵌入产品依赖哈希验证。

证据分三层：真实 Demo 加真实 LLM 的公开 HTTP/A2A 事务；真实 Caller 加 SDK HTTP 的受控远端输入；公开 AgentHandler/Caller/Hook 的编程契约。SPI 恢复请求只证明编程契约，HTTP 回调自动恢复由 F086-41 单独证明。普通 null/异常结果同时检查真实模型工具消息和双成员批次；回调 null/异常检查双等待成员依次结算。

F086-28 的空 Parts 与 F086-42 的 null status/state 在当前 SDK 的公开构造入口被拒绝，不能伪造为 Runtime 分支已覆盖；保留场景及不可达变体，分别执行其余合法变体。F086-62 的 agentEvent=null 在 SDK HTTP 解析层受限，改用公开回调 SPI 交付原 Task 验证键存在过滤，HTTP 限制单独记录。SDK 属于本特性修改范围外，不通过反射或修改 SDK 绕过。

隔离：每次运行唯一 conversation_id、证据目录及调用 ID；请求与轮询均有界。使用指定真实模型，凭据只传进程环境。模型未调用目标工具属于触发不足；前置错误先分类并修复，仅复测影响集。只有权威 Oracle 与可达产品行为不一致且三次稳定复现，才登记正式缺陷。

实现追踪：Feat086RemoteA2aResponseHookReactAgentTest 包含 32 项 Demo 装配/HTTP/模型验证；Feat086RemoteCallerContractTest 包含 18 项 Caller/SDK HTTP 验证；Feat086ProjectionBatchContractTest 包含 22 项公开 SPI 验证。三类通过 f086_NN、Story、tag、DisplayName 与场景一一对应。F086-41/47 共享回调事务，分别检查完成与等待阶段。实际选择器与 Surefire 证据按运行独立归档。

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| SDK 输入约束 | F086-28 空 Parts、F086-42 null 状态变体不可达 | known-gap，其他变体执行 | SDK 提供合法可构造入口或文档明确适用性；不修改既有 Oracle |
| SDK HTTP null metadata | F086-62 null 标记在传输解码前失败 | 公开回调 SPI 验证，HTTP 限制保留 | SDK 修复 null 值解析后补 HTTP 变体 |
| 本地已有 solution 修改 | 制品包含用户原有修改及显式测试 profile | 保留工作区并记录哈希 | 非门禁 |
| L2 文件名 | resolver 前缀不兼容 | 已手工锁定文件与哈希 | 无需改名或修改公共脚本 |
