---
用例编号: FEAT-031-studio-dsl-node-type-extension
测试标题: Studio DSL 节点类型扩展承载——21 种内置节点执行、Python 脚本执行、节点间数据传递、嵌套工作流、失败表面
story: S1
优先级: P0
自动化状态: DESIGN
适用环境: openjiuwen
作者: TBD
创建日期: 2026-08-27
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, studiodsl, feat-031]
---

# FEAT-031-studio-dsl-node-type-extension — Studio DSL 节点类型扩展承载

> **一句话总结**：把 Studio 低码平台的 21 种工作流节点 + Python 脚本执行能力 + 数据传递 + 嵌套工作流
> + 失败表面，全部移植到 Java 运行时，让 Studio 画布产出的工作流能在 Java 运行时上跑起来。
> 测试用例逐节点验证执行正确性。
>
> **本轮范围**：仅覆盖 §3 节点类型扩展承载（21 种 MUST 节点 + Python 脚本执行 + 节点间数据传递
> + 嵌套工作流 + 失败表面）。§4 高码工程生成（IR 拉取 → 代码生成 → 敏感剥离 → FEAT-002 接入）
> 本轮不测试，后续单独承接。
>
> **机制一句话**：FEAT-031 §3 定义 21 种内置节点类型执行能力（编排控制 8 + 模型推理 4 + 交互 4
> + 外部调用 5）与 Python 脚本执行、节点间数据传递、嵌套工作流调度、可区分失败表面。本用例覆盖
> §3 的全部 MUST 行。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-core-ext-studio-dsl** | 机制提供方 | 21 种节点执行器 + Python 执行机制（subprocess/sandbox/inprocess）+ 节点间数据模型（`NodePayload`/`MediaPart`）+ 变量存储（`ConversationValsStore`）+ 嵌套工作流引擎 + 失败表面（`NodeCauseCode`） |
| **载体层 · agent-solution** | 机制触发载体 | studio-dsl 模块（运行时库子模块），装配产物驱动 `agent-core-java` 的 `Workflow`/`Vertex`/`ComponentExecutable` |
| **测试数据层** | 载体 agent 的实现逻辑 | 使用 Flow*Node Java 类装配的工作流夹具，Python 脚本夹具，多模态输入夹具 |

## 关联特性

- **FEAT-031**：§3 节点类型扩展承载（21 种 MUST 节点 + Python 脚本 + 数据传递 + 嵌套 + 失败表面）。
- **FEAT-004**：agent 节点的远程 A2A 调用语义。
- **FEAT-008**：questioner 节点的 `INPUT_REQUIRED` 中断/恢复语义。

## 关联架构约束 / FEAT-031 事实要求

- FEAT-031 §3.2：21 种内置节点类型均为 MUST；代码节点支持 Python 脚本为 MUST；节点间数据传递、多模态传递、嵌套工作流结果回灌、嵌套深度上限、节点执行失败表面均为 MUST。
- FEAT-031 §3.5.2：Python 脚本必须支持环境隔离与超时处理。
- FEAT-031 §3.5.3：节点间数据传递语义——上游输出作为下游输入，数据模型承载多模态。
- FEAT-031 §3.5.4：嵌套工作流语义——独立执行上下文、结果回灌、嵌套深度上限。

## 前置条件

1. `agent-core-ext-studio-dsl` 模块已构建并 install 至 m2。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. Python 3.x 执行环境就绪（代码节点 Python 脚本执行）。
4. Redis 可用（start 会话 KV 持久化依赖 Redis，key 前缀 `global.vals.{workflow_id}.{conversation_id}`；非 setVariable 工作流变量）。
5. `CODE_BLACK_LIST` 环境变量可配置（代码节点黑名单测试）。
6. 知识库 / MCP server / 远程 Agent / 插件端点就绪（对应节点依赖）。
7. `A2A_STREAM` 协议（questioner 中断态可见性 + 状态序列断言）。

---

## §3 节点类型扩展承载

### 一、编排控制类节点（8 种）

#### 测试数据

| 节点 | IR 标识 | 测试场景 |
|------|---------|----------|
| start | `jiuwen.start` | 最小工作流 `{start → end}`，验证入口初始化执行上下文 |
| end | `jiuwen.end` | 工作流终止，产出终态结果 |
| branch | `jiuwen.branch` | 条件表达式 `risk_level == "high"`，分别走 true/false 路径 |
| branch (多条件) | `jiuwen.branch` | 多条件分支：`risk==high`→approve、`risk==medium`→review、`true`→auto（兜底） |
| loop | `jiuwen.loop` | 列表 3 元素迭代，循环体内 setVariable 累加计数 |
| aggregate | `jiuwen.aggregate` | 两路并行分支后汇聚，验证聚合完整 |
| subWorkflow | `jiuwen.subWorkflow` | 父工作流引用子工作流，结果回灌父工作流 |
| setVariable | `jiuwen.setVariable` | 设置 `counter=0`，后续节点消费变量值 |
| setVariable (操作符) | `jiuwen.setVariable` | increment/decrement/empty/empty_str/empty_arr 操作符语义 |
| setVariable (会话级) | `jiuwen.setVariable` | **已移除**：会话级 Redis 持久化是 start 节点的会话 KV 能力，非 setVariable 工作流变量。setVariable 变量生命周期随工作流结束而销毁，不跨执行持久化。会话级 KV 持久化的验证改为在 start 节点用例中覆盖（A12） |
| exception | `jiuwen.exception` | 异常分支场景：exception 节点到达时终止工作流，携带 userFields 异常数据 |
| exception (互斥) | `jiuwen.exception` | 两个异常节点并行执行，仅第一个发送 `WORKFLOW_EXCEPTION`，第二个直接抛异常 |
| exception (defaultOutputs) | `jiuwen.exception` | 错误恢复 `defaultOutputs` 模式：节点失败时返回默认输出 + 异常信息 |
| exception (errorBranch) | `jiuwen.errorBranch` | 错误恢复 `errorBranch` 模式：节点失败时返回 result='1' 标识异常路径 |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 1 | 装配 `{start → end}` 最小工作流，执行 | start 初始化上下文 → end 产出终态 |
| 2 | 装配含 branch 节点的工作流，输入触发 `true` 路径 | 条件求值为 true → 走 true 分支；引擎不解释业务语义 |
| 3 | 同上工作流，输入触发 `false` 路径 | 条件求值为 false → 走 false 分支 |
| 3a | 装配含多条件 branch 的工作流（high/medium/兜底），分别触发三种输入 | 按条件优先级路由到对应分支；兜底条件 `true` 捕获未命中 |
| 4 | 装配含 loop 节点的工作流，列表 3 元素 | 循环体执行 3 次，每次 setVariable 累加 |
| 5 | 装配含 aggregate 节点的工作流，两路并行 | 两路结果聚合完整，不丢失分支结果 |
| 6 | 装配含 subWorkflow 节点的工作流，引用子工作流 | 子工作流独立执行 → 结果回灌父工作流 → 父继续后续节点 |
| 7 | 装配含 setVariable → 后续消费节点的工作流 | setVariable 写入变量 → 后续节点读取到正确值 |
| 7a | 装配含 setVariable(increment) 的工作流，counter 初值 0，increment 3 次 | counter 最终值为 3 |
| 7b | 装配含 setVariable(decrement) 的工作流，counter 初值 5，decrement 2 次 | counter 最终值为 3 |
| 7c | 装配含 setVariable(empty/empty_str/empty_arr) 的工作流 | 变量分别被置为 null / "" / [] |
| 7d | 装配含会话级变量（start 节点 memory）的工作流，同一会话执行两次 | 第二次执行时 start 节点从 Redis 读取到第一次写入的会话级变量（此为 start 节点的会话 KV 能力，非 setVariable 工作流变量） |
| 8 | 装配含 exception 节点的工作流，执行到 exception 节点 | 工作流终止（WorkflowAbortException），携带 userFields 异常数据；**不静默跳过** |
| 8a | 装配含两个并行 exception 节点的工作流 | 仅第一个发送 `WORKFLOW_EXCEPTION`，第二个直接抛异常（互斥守卫 `__abort__`） |
| 8b | 装配含异常恢复（defaultOutputs）的工作流，某节点执行失败 | 返回默认输出 + 异常信息（isSuccess=false, errorBody 含 errorMessage/errorCode） |
| 8c | 装配含异常恢复（errorBranch）的工作流，某节点执行失败 | 返回 result='1' 标识异常路径（不支持 message/card/end 节点的 errorBranch） |

#### 机制断言

- **A — start**：DSL 声明 start 节点 → 工作流启动 → start 初始化执行上下文 → 后续节点按连线执行。**FAIL**：start 未执行 / 未初始化上下文。
- **B — end**：DSL 声明 end 节点 → 执行到 end → 工作流终止 → 产出终态结果。**FAIL**：未终止 / 无终态结果。
- **C — branch**：DSL 声明 branch 节点 + 条件表达式 → 执行到 branch → 按条件求值路由 → 引擎不解释业务语义。**FAIL**：路由错误 / 引擎解释了业务语义。
- **C2 — branch (多条件)**：DSL 声明多条件 branch（high/medium/兜底）→ 按条件优先级路由 → 兜底条件 `true` 捕获未命中。**FAIL**：优先级错误 / 兜底失效。
- **D — loop**：DSL 声明 loop 节点 + 循环条件 → 执行到 loop → 循环体按条件迭代 → 迭代完成后继续后续节点。**FAIL**：迭代次数错误 / 循环体未执行。
- **E — aggregate**：DSL 声明 aggregate 节点 → 两路并行完成 → aggregate 聚合两路结果 → 不丢失分支数据。**FAIL**：丢失分支结果 / 聚合失败。
- **F — subWorkflow**：DSL 声明 subWorkflow 节点 → 执行到 subWorkflow → 子工作流独立上下文执行 → 结果回灌父工作流 → 父继续后续节点。**FAIL**：子工作流未执行 / 结果未回灌 / 父工作流中断。
- **G — setVariable**：DSL 声明 setVariable 节点 → 执行到 setVariable → 变量写入工作流执行上下文 → 后续节点读取到正确值。**FAIL**：变量未写入 / 后续节点读不到值。
- **G2 — setVariable (操作符)**：setVariable 支持 increment/decrement/empty/empty_str/empty_arr → 各操作符语义正确（increment: +1, decrement: -1, empty: null, empty_str: "", empty_arr: []）。**FAIL**：操作符未生效 / 值错误。
- **G3 — 会话级持久化（start 节点会话 KV）**：start 节点将 memory 变量写入 Redis → 第二次工作流执行时 start 节点从 Redis 读取到上次值。**注：此为 start 节点的会话 KV 能力，非 setVariable 工作流变量；setVariable 变量生命周期随工作流结束而销毁**。**FAIL**：未持久化 / 第二次读不到值。
- **H — exception**：DSL 声明 exception 节点 → 执行到 exception → 工作流终止（WorkflowAbortException）→ 携带 userFields 异常数据 → 不静默跳过。**FAIL**：未终止 / 未携带异常数据 / 静默跳过。
- **H2 — exception (互斥)**：两个异常节点并行执行 → 仅第一个发送 `WORKFLOW_EXCEPTION` → 第二个直接抛异常（互斥守卫 `__abort__`）。**FAIL**：两个都发送 / 无互斥。
- **H3 — exception (defaultOutputs)**：节点失败 → 错误恢复返回默认输出 + 异常信息（isSuccess=false, errorBody 含 errorMessage/errorCode）。**FAIL**：无默认输出 / 缺异常信息。
- **H4 — exception (errorBranch)**：节点失败 → 返回 result='1' 标识异常路径（message/card/end 节点不支持 errorBranch）。**FAIL**：未标识异常路径 / 不支持的节点类型未拒绝。

#### 别名覆盖（归属 §4，本轮不测试）

> 别名归一是 §4 代码生成器/宿主装配期的职责，不属于 §3 运行时。
> L2 明确：运行时不做别名归一，由 §4/宿主映射到同一个 Java 节点类。
> SUT 已使用 Java 节点对象直接装配，再传入别名 IR 字符串无意义。
> 以下别名映射记录供 §4 后续承接参考。

---

### 二、模型推理类节点（4 种）

#### 测试数据

| 节点 | IR 标识 | 测试场景 |
|------|---------|----------|
| LLMComponent | `jiuwen.LLMComponent` | 配置 `modelName` + 提示词模板，输入用户问题 → LLM 返回回复 |
| intentDetection | `jiuwen.intentDetection` | 配置 intents 列表 `["退款","咨询","投诉"]`，输入 `"我要退款"` → 识别意图 `退款` |
| extractor | `jiuwen.extractor` | 配置提取字段 `["姓名","金额","日期"]`，输入 `"张三于2026年8月27日消费500元"` → 提取结构化字段 |
| knowledgeRetrieval | `jiuwen.knowledgeRetrieval` | 配置知识库引用，输入查询问题 → RAG 召回相关文档片段 |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 9 | 装配含 LLMComponent 节点的工作流，发送用户问题 | 模型节点调用 LLM 执行推理 → 结果回灌工作流；流式输出复用 `LLMExecutable.stream` |
| 10 | 装配含 intentDetection 节点的工作流，发送 `"我要退款"` | 意图分类结果为 `退款` → 结果回灌工作流（控制器编排属后续版本，本轮不验证路由） |
| 11 | 装配含 extractor 节点的工作流，发送含结构化信息的输入 | 提取 `{姓名:"张三", 金额:"500", 日期:"2026-08-27"}` → 回灌工作流 |
| 12 | 装配含 knowledgeRetrieval 节点的工作流，发送查询问题 | RAG 召回相关文档片段 → 回灌工作流 |

#### 机制断言

- **I — LLMComponent**：DSL 声明模型节点 → 执行到模型节点 → 调用 LLM 推理 → 结果回灌 → 流式输出复用 `LLMExecutable.stream`。**FAIL**：LLM 未调用 / 结果未回灌 / 流式缺失。
- **J — intentDetection**：DSL 声明意图识别节点 → 执行到节点 → 意图分类结果输出 → 结果回灌工作流。**FAIL**：分类错误 / 结果未输出。
- **K — extractor**：DSL 声明 extractor 节点 → 执行到节点 → 提取结构化字段 → 回灌工作流。**FAIL**：字段缺失 / 值错误。
- **L — knowledgeRetrieval**：DSL 声明知识检索节点 → 执行到节点 → RAG 召回 → 结果回灌工作流。**FAIL**：召回为空 / 结果未回灌。

#### 别名覆盖

| IR 标识 | 别名 | 说明 |
|---|---|---|
| `jiuwen.extractor` | `jiuwen.infoExtraction` | 同一组件多个别名，归一后执行同一节点 |

---

### 三、交互类节点（4 种）

#### 测试数据

| 节点 | IR 标识 | 测试场景 |
|------|---------|----------|
| input | `jiuwen.input` | 用户输入 `"我要订机票"` → input 节点接收并回灌工作流 |
| message | `jiuwen.message` | 配置消息内容 `"您的订单已提交"` → 向用户发送消息 |
| card | `jiuwen.card` | 配置卡片模板（标题+内容+按钮）→ 向用户发送卡片消息 |
| questioner | `jiuwen.questioner` | 配置问题 `"请输入审批意见"` → 向用户提问 → `INPUT_REQUIRED` → 续接 `"approved"` → 恢复 |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 13 | 装配含 input 节点的工作流，发送用户输入 | input 节点接收用户输入 → 回灌工作流 → 后续节点继续执行 |
| 14 | 装配含 message 节点的工作流，执行到 message 节点 | message 节点向用户发送消息内容 |
| 15 | 装配含 card 节点的工作流，执行到 card 节点 | card 节点向用户发送卡片消息（含标题+内容+按钮） |
| 16 | 装配含 questioner 节点的工作流，`A2A_STREAM` | questioner 向用户提问 → Task 进入 `INPUT_REQUIRED`（**非 COMPLETED**） |
| 17 | 续接 `"approved"` 恢复 | Task 恢复至 `COMPLETED`，续轮 `WORKING→COMPLETED` |

#### 机制断言

- **M — input**：DSL 声明 input 节点 → 执行到 input → 接收用户输入 → 回灌工作流。**FAIL**：输入未接收 / 未回灌。
- **N — message**：DSL 声明 message 节点 → 执行到 message → 向用户发送消息内容。**FAIL**：消息未发送 / 内容错误。
- **O — card**：DSL 声明 card 节点 → 执行到 card → 向用户发送卡片消息（标题+内容+按钮）。**FAIL**：卡片未发送 / 结构缺失。
- **P — questioner**：DSL 声明 questioner 节点 → 执行到 questioner → 向用户提问 → Task 进入 `INPUT_REQUIRED`（不伪装 completed）→ 续接恢复 `COMPLETED`。**FAIL**：终态直接 `COMPLETED` / 缺 `INPUT_REQUIRED` / 续接未恢复。

#### 别名覆盖

| IR 标识 | 别名 | 说明 |
|---|---|---|
| `jiuwen.input` | `jiuwen.flowInput` | 同一组件多个别名 |
| `jiuwen.card` | `jiuwen.flowCard` | 同一组件多个别名 |

---

### 四、外部调用类节点（5 种，含 Python 脚本执行）

#### 测试数据

| 节点 | IR 标识 | 测试场景 |
|------|---------|----------|
| code | `jiuwen.code` | Python 脚本 `result = {"sum": input["a"] + input["b"]}`，输入 `{a:1, b:2}` → 输出 `{sum:3}` |
| code (隔离-三维) | `jiuwen.code` | 分别验证三个隔离维度：(a) 两个工作流实例并行执行 Python 代码节点，各自写临时文件到 cwd，另一个尝试读取；(b) 同一工作流内两个 code 节点，节点A写文件，节点B尝试读；(c) 不同租户的脚本写文件，另一租户尝试读。同时验证环境变量不互染 |
| code (超时+清理) | `jiuwen.code` | Python 脚本 `time.sleep(30)`，超时 5s → 超时被处理（`PYTHON_TIMEOUT`）；进程销毁、隔离工作目录清理；后续执行不受残留影响 |
| code (黑名单) | `jiuwen.code` | 设置 `CODE_BLACK_LIST=["os.system"]`，脚本含 `os.system("rm -rf /")` → 被拒绝 |
| code (stdout) | `jiuwen.code` | 脚本 `print("hello")`，`main()` 返回 dict → stdout 被捕获，不泄漏到控制台 |
| code (返回值校验) | `jiuwen.code` | 脚本 `main()` 返回非 dict（如返回字符串）→ 被拒绝，映射为失败表面 |
| plugin | `jiuwen.plugin` | 配置插件 API 端点 + 参数 → 调用外部插件 → 结果回灌 |
| mcp | `jiuwen.mcp` | 配置 MCP 工具名 + 参数 → 调用 MCP 工具 → 结果回灌 |
| agent | `jiuwen.agent` | 配置远程 Agent 引用 → 远程 A2A 调用 → 结果回灌 |
| streamTransform | `jiuwen.streamTransform` | 配置变换规则 → 上游流式输出经变换 → 下游消费 |
| streamTransform (模板) | `jiuwen.streamTransform` | JSON 模板 `{{var}}` 渲染 + 变量提取(src_path) + 跨帧拼接(concat) + 最终帧发射 + `is_last` 标记 |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 18 | 装配含 code 节点（Python 脚本）的工作流，输入 `{a:1, b:2}` | Java 运行时执行 Python 脚本 → 输出 `{sum:3}` → 回灌工作流 |
| 19 | 分别验证三个隔离维度：(a) 两个工作流实例并行执行含 Python 代码节点，各自写临时文件到 cwd，另一个尝试读取；(b) 同一工作流内两个 code 节点，节点A写文件，节点B尝试读；(c) 不同租户的脚本写文件，另一租户尝试读 | 三个维度均互不污染：临时文件不可跨实例/节点/租户读取，环境变量不互染，各自 cwd 独立 |
| 20 | 装配含 code 节点（Python 超时脚本），超时 5s | 超时被处理；超时后进程被销毁、隔离工作目录被清理；后续执行另一个 Python 脚本不受上次超时残留影响 |
| 20a | 设置 `CODE_BLACK_LIST=["os.system"]`，脚本含黑名单关键字 | 脚本被拒绝，映射为失败表面，不执行 |
| 20b | 脚本含 `print("hello")`，`main()` 返回 dict | stdout 被捕获，不泄漏到控制台；结果正确回灌 |
| 20c | 脚本 `main()` 返回非 dict（如字符串） | 被拒绝，映射为失败表面（"Code must return a dict from main()"） |
| 21 | 装配含 plugin 节点的工作流，配置插件 API | 插件节点调用外部插件 → 结果回灌工作流 |
| 22 | 装配含 mcp 节点的工作流，配置 MCP 工具 | mcp 节点调用 MCP 工具 → 结果回灌工作流 |
| 23 | 装配含 agent 节点的工作流，配置远程 Agent 引用 | agent 节点远程 A2A 调用 → 结果回灌工作流 |
| 24 | 装配含 streamTransform 节点的工作流，上游节点流式输出 | streamTransform 对流式输出做变换处理 → 下游消费变换后结果 |
| 24a | 装配含 streamTransform 节点的工作流，配置 JSON 模板 + 变量提取 + concat | 每帧按模板渲染 → 变量跨帧拼接 → 最终帧发射含拼接值 + `is_last=true` |

#### 机制断言

- **Q — code (Python 执行)**：DSL 声明代码节点 + Python 脚本 → 执行到代码节点 → Java 运行时执行 Python 脚本 → 结果回灌工作流。**FAIL**：Python 未执行 / 结果错误 / 未回灌。
- **R — code (环境隔离-三维)**：三个隔离维度验证：(a) 不同工作流实例各自写临时文件，另一个尝试读；(b) 同一工作流内两个 code 节点，节点A写文件节点B尝试读；(c) 不同租户的脚本写文件，另一租户尝试读。同时验证环境变量不互染。**FAIL**：临时文件可跨实例/节点/租户读取 / 环境变量互染 / cwd 共享。
- **S — code (超时处理+清理)**：Python 脚本超时 → 超时被处理（`PYTHON_TIMEOUT`）→ 进程被销毁 → 隔离工作目录被清理 → 后续执行不受残留影响。**FAIL**：超时未处理 / 进程未销毁 / 目录未清理 / 后续执行受污染。
- **S2 — code (黑名单)**：脚本含 `CODE_BLACK_LIST` 关键字 → 被拒绝 → 映射为失败表面 → 不执行。**FAIL**：黑名单未生效 / 脚本被执行。
- **S3 — code (stdout 捕获)**：脚本 `print()` 输出 → stdout 被捕获 → 不泄漏到控制台 → 结果正确回灌。**FAIL**：stdout 泄漏 / 结果未回灌。
- **S4 — code (返回值校验)**：`main()` 返回非 dict → 被拒绝 → 映射为失败表面。**FAIL**：非 dict 返回值被接受。
- **T — plugin**：DSL 声明插件节点 → 执行到插件节点 → 调用外部插件 API → 结果回灌。**FAIL**：插件未调用 / 结果未回灌。
- **U — mcp**：DSL 声明 mcp 节点 → 执行到 mcp 节点 → 调用 MCP 工具 → 结果回灌。**FAIL**：MCP 未调用 / 结果未回灌。
- **V — agent**：DSL 声明 agent 节点 → 执行到 agent 节点 → 远程 A2A 调用 → 结果回灌。**FAIL**：调用失败 / 结果未回灌。
- **W — streamTransform**：DSL 声明 streamTransform 节点 → 上游流式输出到达 → 变换处理 → 下游消费变换后结果。**FAIL**：变换失败 / 下游未收到变换结果。
- **W2 — streamTransform (模板+拼接)**：JSON 模板 `{{var}}` 渲染 → 变量按 `src_path` 提取 → concat 变量跨帧拼接 → 最终帧发射含拼接值 + `is_last=true` → `prune_none_fields` 裁剪未解析占位符。**FAIL**：模板未渲染 / 拼接缺失 / 无最终帧 / `is_last` 缺失。

#### 别名覆盖

| IR 标识 | 别名 | 说明 |
|---|---|---|
| `jiuwen.plugin` | `jiuwen.api` / `jiuwen.flowApi` | 同一组件 `FlowApi` 多个别名 |
| `jiuwen.mcp` | `jiuwen.flowMcp` | 同一组件多个别名 |
| `jiuwen.agent` | `jiuwen.flowAgent` | 同一组件多个别名 |

---

### 四-B、Java 扩展节点（3 种）

> FEAT-031 §3.2 的 21 种 MUST 节点之外的扩展节点。Java `agent-core-ext-studio-dsl` 已实现，
> Python `workflow_node/` 有对应实现（QA）或功能等价物。虽非 FEAT-031 MUST，但已承载，需验证执行正确性。

#### 测试数据

| 节点 | IR 标识 | 测试场景 |
|------|---------|----------|
| QA | `EI.qa` | 配置 options `["选项A","选项B"]` + qaStrategy `random` + needReply `false` → 向用户展示问题 |
| QA (中断) | `EI.qa` | 配置 needReply `true` → 向用户展示问题 → Task 进入 `INPUT_REQUIRED` → 续接恢复 |
| QA (index 策略) | `EI.qa` | 配置 qaStrategy `index` + indexKey `index` + options 3 项 → 按 index 选择问题 |
| QA (结构化消息) | `EI.qa` | 配置 isStructMessage `true` + structOutputTemplate `{{answer}}` + structInputSchemas → 结构化消息输出，message_end 事件含结构化 answer |
| QA (会话历史) | `EI.qa` | 配置 enableHistory `true` → 写入 `workflow_chat_history` → 后续 QA 节点读取历史 |
| ComplexIntentDetection | `EI.ComplexIntentDetection` | 配置 branches + groups + aggMode + LLM → 复杂意图分类（多分支+聚合）→ 结果回灌工作流 |
| ComplexIntentDetection (知识库) | `EI.ComplexIntentDetection` | 配置 enableKnowledge `true` + recallThreshold → 知识召回参与意图分类 |
| ComplexIntentDetection (会话历史) | `EI.ComplexIntentDetection` | 配置 enableHistory `true` + chatHistoryMaxTurn → 读取最近 K 轮历史参与分类 |
| ParamOutput | `EI.ParamOutput` | 节点接收 inputs → 透传 userFields/systemFields → 作为参数输出供下游消费 |
| ParamOutput (空输入) | `EI.ParamOutput` | 节点接收空输入 → 返回空 Map，不抛异常 |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 24b | 装配含 QA 节点（random 策略, needReply=false）的工作流 | QA 节点从 options 中随机选择问题 → 向用户展示 → 工作流继续执行 |
| 24c | 装配含 QA 节点（needReply=true）的工作流，`A2A_STREAM` | QA 节点展示问题 → Task 进入 `INPUT_REQUIRED` → 续接 → 恢复 `COMPLETED` |
| 24d | 装配含 QA 节点（index 策略）的工作流，输入 `index=1` | QA 节点按 index 选择 options[1] → 展示对应问题 |
| 24e | 装配含 QA 节点（isStructMessage=true）的工作流 | QA 节点输出 `partial_content` + `message_end` 两帧；`message_end` 含结构化 answer（模板渲染） |
| 24f | 装配含两个 QA 节点（enableHistory=true）的工作流 | 第一个 QA 写入 `workflow_chat_history` → 第二个 QA 读取历史 |
| 24g | 装配含 ComplexIntentDetection 节点的工作流，配置 branches + LLM | 复杂意图分类执行 → 按 branches 分支 + aggMode 聚合 → 结果回灌工作流 |
| 24h | 装配含 ComplexIntentDetection 节点（enableKnowledge=true）的工作流 | 知识召回参与意图分类 → 召回结果阈值过滤（recallThreshold）→ 分类结果回灌 |
| 24i | 装配含 ComplexIntentDetection 节点（enableHistory=true, chatHistoryMaxTurn=3）的工作流 | 读取最近 3 轮会话历史 → 参与意图分类 |
| 24j | 装配含 ParamOutput 节点的工作流，上游节点输出 userFields | ParamOutput 节点透传 userFields/systemFields → 下游节点消费 |
| 24k | 装配含 ParamOutput 节点的工作流，输入为空 | ParamOutput 节点返回空 Map → 不抛异常 → 工作流继续 |

#### 机制断言

- **X1 — QA (random 策略)**：DSL 声明 QA 节点（`EI.qa`）+ options + qaStrategy=random → 从 options 随机选择问题 → 展示给用户。**FAIL**：未选择 / options 为空未报错。
- **X2 — QA (中断/恢复)**：QA 节点 needReply=true → 展示问题 → Task `INPUT_REQUIRED` → 续接恢复 `COMPLETED`。**FAIL**：终态直接 `COMPLETED` / 续接未恢复。
- **X3 — QA (index 策略)**：qaStrategy=index → 按 indexKey 从 options 选择 → index 超界报错。**FAIL**：index 未生效 / 超界未报错。
- **X4 — QA (结构化消息)**：isStructMessage=true + structOutputTemplate → 输出 `partial_content` + `message_end` 两帧 → `message_end` 含模板渲染的结构化 answer → structInputSchemas 归一化（补空值字段、规范顺序）。**FAIL**：无双帧 / 结构化 answer 缺失 / 归一化未生效。
- **X5 — QA (会话历史)**：enableHistory=true → 写入 `workflow_chat_history` → 后续 QA 读取。**FAIL**：未写入 / 未读取。
- **X6 — ComplexIntentDetection**：DSL 声明复杂意图检测节点（`EI.ComplexIntentDetection`）+ branches + groups + aggMode + LLM → 多分支意图分类 + 聚合 → 结果回灌工作流。**FAIL**：分类失败 / 结果未回灌。
- **X7 — ComplexIntentDetection (知识库)**：enableKnowledge=true → 知识召回参与分类 → recallThreshold 过滤。**FAIL**：召回未参与 / 阈值未过滤。
- **X8 — ComplexIntentDetection (会话历史)**：enableHistory=true + chatHistoryMaxTurn=K → 读取最近 K 轮历史。**FAIL**：未读取历史 / 轮数错误。
- **X9 — ParamOutput**：DSL 声明 ParamOutput 节点（`EI.ParamOutput`）→ 透传 userFields/systemFields → 下游消费。**FAIL**：未透传 / 字段丢失。
- **X10 — ParamOutput (空输入)**：输入为空 → 返回空 Map → 不抛异常 → 工作流继续。**FAIL**：抛异常 / 工作流中断。

#### 别名覆盖

| IR 标识 | 别名 | 说明 |
|---|---|---|
| `EI.qa` | — | 对应 Python `flow_qa.py` 的 `FlowQA`（IR `jiuwen.qa` / `EI.qa`） |
| `EI.ComplexIntentDetection` | — | Java 扩展，无 Python 对应独立文件，但功能等价于 intentDetection 的增强版 |
| `EI.ParamOutput` | — | Java 扩展，参数输出节点 |

---

### 五、节点间数据传递与多模态

#### 测试数据

- **结构化数据传递**：工作流 `{start → LLMComponent → message → end}`，LLM 输出文本 → message 节点消费。
- **多模态数据传递**：工作流 `{start → input(含图片) → LLMComponent(消费图片) → message → end}`，input 节点接收图片 → LLM 节点消费图片。
- **变量作用域**：工作流 `{start → setVariable(counter=0) → loop(读 counter) → end}`，counter 在工作流内可见。
- **变量生命周期**：工作流执行结束后，变量 `counter` 不再可被外部访问。

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 25 | 装配含链式节点的工作流，上游节点输出文本 | 上游输出 → 下游节点输入正确 |
| 26 | 装配含 input(图片) → LLMComponent 的工作流，输入含图片 | 图片经 input → LLM 节点消费多模态输入 |
| 27 | 装配含 setVariable → loop(读变量) 的工作流 | setVariable 写入变量 → loop 节点读取到正确值 |
| 28 | 工作流执行结束后，尝试访问变量 `counter` | 变量生命周期结束，不可访问 |

#### 机制断言

- **X — 结构化数据传递**：上游节点输出 → 下游节点接收为输入 → 数据传递正确。**FAIL**：下游收不到上游输出 / 数据丢失。
- **Y — 多模态数据传递**：input 节点接收图片 → LLM 节点消费多模态输入 → 输出结果。**FAIL**：图片丢失 / 模型节点未消费图片。
- **Z — 变量作用域**：setVariable 写入变量 → 后续节点读取到正确值 → 变量在工作流执行上下文内可见。**FAIL**：变量不可见 / 值错误。
- **AA — 变量生命周期**：工作流执行结束 → 变量生命周期结束 → 不可访问。**FAIL**：变量在工作流结束后仍可访问。

---

### 六、嵌套工作流深度与失败表面

#### 测试数据

- **嵌套深度超限**：配置嵌套深度上限为 5，装配 6 层嵌套的工作流 → 超限被拒绝。
- **节点执行失败**：某节点执行抛异常 → 失败映射为可区分的失败表面，含节点类型、节点 ID、失败原因。

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 29 | 装配嵌套深度 6 层的工作流，配置上限 5 | 调度被拒绝，映射为可区分的失败表面，指出超限的嵌套深度 |
| 30 | 装配含会抛异常的节点的工作流，触发异常 | 失败映射为可区分的失败表面，含节点类型、节点 ID、失败原因；不静默跳过 |

#### 机制断言

- **BB — 嵌套深度超限**：DSL 声明嵌套深度超过配置上限 → 调度被拒绝 → 映射为可区分的失败表面 → 指出超限的嵌套深度。**FAIL**：未被拒绝 / 无失败表面 / 未指出超限深度。
- **CC — 节点执行失败表面**：节点执行抛异常 → 失败映射为可区分的失败表面 → 含节点类型、节点 ID、失败原因 → 不静默跳过。**FAIL**：静默跳过 / 失败表面缺失节点类型/ID/原因。

---

### 七、异常场景与失败表面覆盖

> 逐节点验证异常/边界场景下的失败表面映射。Java `NodeCauseCode` 枚举值：
> `NODE_CONFIG_INVALID`、`NODE_INVOKE_FAILED`、`PYTHON_TIMEOUT`、`PYTHON_NON_ZERO`、
> `PYTHON_IO`、`NESTING_DEPTH_EXCEEDED`、`SUBWORKFLOW_REF_INVALID`。
> （注：`UNKNOWN_NODE_TYPE` 归属 §4 代码生成器，非 §3 运行时职责；`CODE_PATH_AMBIGUOUS` 为预留枚举，当前无抛出点，本轮不单独验收。）

#### 测试数据

| 节点 | 异常场景 | 预期 NodeCauseCode |
|------|---------|-------------------|
| branch | 无匹配条件且无兜底 | `NODE_CONFIG_INVALID` 或工作流终止 |
| loop | 循环列表为空 | 循环体不执行，工作流继续（正常边界） |
| subWorkflow | 子工作流引用不存在 | `SUBWORKFLOW_REF_INVALID` |
| LLMComponent | LLM API 调用失败（网络错误/超时） | `NODE_INVOKE_FAILED` |
| intentDetection | intents 列表为空 | `NODE_CONFIG_INVALID` |
| extractor | 提取字段配置缺失 | `NODE_CONFIG_INVALID` |
| code | Python 脚本运行时异常（如 KeyError） | `PYTHON_NON_ZERO` |
| code | Python IO/解析失败（如 JSON 解析错误） | `PYTHON_IO` |
| plugin | 插件 API 不可达 / 返回错误 | `NODE_INVOKE_FAILED` |
| mcp | MCP 工具不存在 / 配置错误 | `NODE_CONFIG_INVALID` 或 `NODE_INVOKE_FAILED` |
| agent | agent 引用配置缺失（agentId/url 为空） | `NODE_CONFIG_INVALID` |
| streamTransform | 变换配置无效 | `NODE_CONFIG_INVALID` |
| errorBranch 约束 | message/card/end 节点配置 errorBranch | `NODE_CONFIG_INVALID`（不支持 errorBranch） |
| exception 约束 | loop/exception 节点配置异常恢复 | `NODE_CONFIG_INVALID`（不支持异常恢复） |

#### 测试步骤与预期

| # | 动作 | 预期 |
|---|------|------|
| 30a | 装配含 branch 节点的工作流，输入不匹配任何条件且无兜底 | 工作流终止或映射为失败表面，含节点类型/ID/原因；不静默跳过 |
| 30b | 装配含 loop 节点的工作流，循环列表为空 | 循环体不执行 → 工作流继续后续节点（正常边界，非失败） |
| 30c | 装配含 subWorkflow 节点的工作流，引用不存在的子工作流 | 失败表面 `SUBWORKFLOW_REF_INVALID`，含缺失的引用路径 |
| 30d | 装配含 LLMComponent 节点的工作流，LLM 端点不可达 | 失败表面 `NODE_INVOKE_FAILED`，含节点类型/ID/错误信息 |
| 30e | 装配含 intentDetection 节点的工作流，intents 列表为空 | 失败表面 `NODE_CONFIG_INVALID`，含"intents must not be empty"类原因 |
| 30f | 装配含 extractor 节点的工作流，提取字段配置缺失 | 失败表面 `NODE_CONFIG_INVALID`，含配置校验错误原因 |
| 30g | 装配含 code 节点的工作流，Python 脚本 `raise KeyError("missing")` | 失败表面 `PYTHON_NON_ZERO`，含脚本异常信息 |
| 30h | 装配含 code 节点的工作流，Python 脚本 `json.loads("invalid")` | 失败表面 `PYTHON_IO`，含解析错误信息 |
| 30i | 装配含 plugin 节点的工作流，插件 API 端点不可达 | 失败表面 `NODE_INVOKE_FAILED`，含连接错误信息 |
| 30j | 装配含 mcp 节点的工作流，MCP 工具名不存在 | 失败表面 `NODE_CONFIG_INVALID` 或 `NODE_INVOKE_FAILED`，含工具名 |
| 30k | 装配含 agent 节点的工作流，agentId/url 为空 | 失败表面 `NODE_CONFIG_INVALID`，含配置缺失字段 |
| 30l | 装配含 streamTransform 节点的工作流，变换配置无效 | 失败表面 `NODE_CONFIG_INVALID`，含配置错误原因 |
| 30n | 装配含 ComplexIntentDetection 节点的工作流，branches 为空 | 失败表面 `NODE_CONFIG_INVALID`，含"branches must not be empty" |
| 30o | 装配含 message 节点（配置 errorBranch）的工作流 | 失败表面 `NODE_CONFIG_INVALID`，含"message 节点不支持 errorBranch"（同样验证 card/end） |
| 30p | 装配含 loop 节点（配置异常恢复 defaultOutputs）的工作流 | 失败表面 `NODE_CONFIG_INVALID`，含"loop 节点不支持异常恢复"（同样验证 exception 节点） |

#### 机制断言

- **DD — branch 无匹配**：branch 条件全不匹配且无兜底 → 工作流终止或失败表面 → 不静默跳过。**FAIL**：静默跳过 / 无失败表面。
- **EE — loop 空列表**：循环列表为空 → 循环体不执行 → 工作流继续（正常边界）。**FAIL**：抛异常 / 工作流中断。
- **FF — subWorkflow 引用无效**：子工作流引用不存在 → `SUBWORKFLOW_REF_INVALID` → 含缺失路径。**FAIL**：静默跳过 / 无失败表面 / 缺引用路径。
- **GG — LLMComponent 调用失败**：LLM API 不可达 → `NODE_INVOKE_FAILED` → 含节点类型/ID/错误。**FAIL**：静默跳过 / 无失败表面。
- **HH — intentDetection 配置无效**：intents 为空 → `NODE_CONFIG_INVALID` → 含校验原因。**FAIL**：静默跳过 / 无失败表面。
- **II — extractor 配置无效**：提取字段配置缺失 → `NODE_CONFIG_INVALID` → 含校验原因。**FAIL**：静默跳过 / 无失败表面。
- **JJ — code Python 异常**：脚本 `raise KeyError` → `PYTHON_NON_ZERO` → 含异常信息。**FAIL**：静默跳过 / 无失败表面 / 错误码不匹配。
- **KK — code Python IO 失败**：脚本 `json.loads("invalid")` → `PYTHON_IO` → 含解析错误。**FAIL**：静默跳过 / 无失败表面 / 错误码不匹配。
- **LL — plugin API 不可达**：插件端点不可达 → `NODE_INVOKE_FAILED` → 含连接错误。**FAIL**：静默跳过 / 无失败表面。
- **MM — mcp 工具不存在**：MCP 工具名不存在 → `NODE_CONFIG_INVALID` 或 `NODE_INVOKE_FAILED` → 含工具名。**FAIL**：静默跳过 / 无失败表面。
- **NN — agent 配置缺失**：agentId/url 为空 → `NODE_CONFIG_INVALID` → 含缺失字段。**FAIL**：静默跳过 / 无失败表面。
- **OO — streamTransform 配置无效**：变换配置无效 → `NODE_CONFIG_INVALID` → 含配置错误。**FAIL**：静默跳过 / 无失败表面。
- **QQ — ComplexIntentDetection 配置无效**：branches 为空 → `NODE_CONFIG_INVALID` → 含"branches must not be empty"。**FAIL**：静默跳过 / 无失败表面。
- **RR — errorBranch 约束**：message/card/end 节点配置 errorBranch → `NODE_CONFIG_INVALID` → 含"不支持 errorBranch"原因。**FAIL**：未拒绝 / 无失败表面。
- **SS — exception 约束**：loop/exception 节点配置异常恢复 → `NODE_CONFIG_INVALID` → 含"不支持异常恢复"原因。**FAIL**：未拒绝 / 无失败表面。

---

## 不覆盖

- **§4 高码工程生成**（IR 拉取 → 代码生成 → 敏感剥离 → FEAT-002 接入 → 保真对齐）——本轮不测试，后续单独承接。
- Python 执行的具体机制选型（sidecar / embedded / subprocess）（FEAT-031 OUT：由 L2 细化）。
- Python 沙箱具体实现（FEAT-031 OUT：沙箱实现在其他模块）。
- Python 依赖声明与加载、超时回退返回固定值、DSL 级超时配置（FEAT-031 OUT：由后续版本承接）。
- 节点 I/O 日志、流式输出横切语义（FEAT-031 OUT：复用 `agent-core-java` 已有实现，不重复定义）。
- 意图识别节点的编排语义（FEAT-031 §3.2：由后续版本承接）。
- 节点 SPI 插件化加载、自定义节点类型扩展、代码节点 Java SPI 实现路径（FEAT-031 OUT：由后续特性承接）。
- 跨语言脚本通用执行（Node.js / Shell / SQL 等）（FEAT-031 OUT：只承诺 Python）。
- 跨工作流（非父子嵌套）的变量共享（FEAT-031 OUT）。
- agent 节点远程 A2A 调用完整语义（→ FEAT-004 承接）。
- questioner 中断/恢复完整状态序列（→ [FEAT-001-input-required](FEAT-001-input-required.md) 已覆盖 FEAT-008 机制层）。
