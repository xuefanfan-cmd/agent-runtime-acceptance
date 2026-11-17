---
feature_id: FRS-058
feature_title: 多模态能力展示智能体Demo（手机银行多模态问题定位智能体 POC Demo）
scope: v1115; docs/develop/01-scenarios/FRS-058（草稿 v0.2，RR2026101300534）
deployable_units: [multi-modal-FAQ-agent(faq-poc-agent Demo 装配面 SUT，含自有识图链 MultimodalInputRail), faq-stub(FAQ fixture)]
sut: multi-modal-FAQ-agent 0.1.2（faq-poc-agent，agent-solution@65efeecd）——custom-rest multipart 图文入站+SSE 投影、多模态识图判类、纯文本提炼、A2A 出站转发 FAQ、图文+url 方案卡片组织
status: draft
features: [FRS-058, FEAT-032, FEAT-036, FEAT-008, FEAT-004, FEAT-001, DFX-001]
updated: 2026-11-13
---

# FRS-058 多模态能力展示智能体 Demo — 测试设计

> 命名说明：feat-058 JUnit Tag 与场景 ID 前缀 F058- 已被 FEAT-058（模型调用超时分层，
> docs/cases/FEAT-058-model-call-timeout-tiering.md）占用。本特性编号为 FRS-058，测试
> Tag 使用 frs-058，场景矩阵 ID 使用 FRS058-xx，避免两套 058 互相误选。

## 1. 测试目标

验证 FRS-058 定义的"手机银行多模态问题定位智能体 POC Demo"端到端行为：用户以 multipart
提交截图（png/jpg，不超过 10MB）加可选文字后，Agent 完成多模态识图与问题类型判定、提炼
FAQ 可检索纯文本问题、以 A2A 出站转发 FAQ 工作流、接收文本/web url/图片三类方案并组织成
图文+url 回复；信息不足时按 FEAT-008 追问挂起并在同一 Task 续接；异常输入与下游失败显式
报错不静默；纯文本调用不回归。

观察 surface 限定为外部 wire 契约：multipart HTTP 入口响应、A2A JSON-RPC envelope、出站
wire 快照（FAQ fixture 捕获）、Task 状态机（INPUT_REQUIRED 等 A2A TaskState 全称）与最终
回复内容。不编译/反射调用 SUT 内部类、不读取 SUT 内部模型状态、不注入进程内对象。

SUT 交付现状（2026-11-12 更新）：FRS-058 专用"问题定位 Agent"装配模块已于 2026-11-10
交付——agent-solution@65efeecd 新增 common/example/multi-modal-FAQ-agent（multi-modal-FAQ-agent
0.1.2，faq-poc-agent）：DeepAgent 基线 + custom-rest multipart 入口（SSE 投影）+ 视觉模型
识图判类（ask_user 追问）+ 纯文本提炼 + A2A 出站 FAQ（faq-workflow 远程工具）+
FaqPocSolutionRail 结构化方案卡片。本文档 2026-11-09 摸底（agent-solution common@75e2a9b）
时"装配面缺失"的结论已失效，原"能力面 SUT 逐步逼近验证"的两层设计解除：
- 装配面 SUT（主被测对象，真实制品）：multi-modal-FAQ-agent 0.1.2，FRS058-01~17
  全部直接以该制品执行（wire 契约见模块 README §5：status/chunk/solution/input_required/
  error/final 六类 SSE 事件 + 非流式 Task 信封）；
- 识图断言面（2026-11-12 归属整改）：原计划的 multi-modal-tools-demo 进程内断言面
  **撤除**——实测装配面 SUT 的识图为自有实现（MultimodalInputRail：A2A FilePart →
  模型 image_url 注入 + FaqPocLlmProperties：VISION_* 三件套），pom 不依赖
  multi-modal-tools-demo，链路不经 MultimodalContentResolver；FRS058-02 收敛为 SUT
  端到端黑盒（Demo 主类新增用例），FRS058-11 迁至独立栈类
  Feat058NonVisionModelBlackboxTest（SUT 注入纯文本模型 VISION_MODEL）；demo 链自身
  的 wire/工具面断言归还 FEAT-032 测试域（该域已有 ObservationToolContractTest 等
  覆盖），原 Feat058VisionUnderstandingBlackboxTest 退役。
装配面交付后的实现缺陷按 issue 流程登记（issues/draft-frs058-*.md），不降级断言；
已登记：FRS058-07 取消不收敛、FRS058-15 history 恒空、FRS058-09/10 错误码透出缺失
（详见 §9）。2026-11-12 最新源码重建（runtime@8fc83a41）后新增登记：FRS058-16 追问续接轮
补充的新截图未进入模型请求（模型重复追问、FAQ 零出站，见
issues/draft-frs058-resume-supplement-image-dropped.md）。

## 2. 范围与非范围

### 范围

1. multipart 图文入站：单张 png 加可选文字经 multipart 提交，截图与文字完整到达 Agent，
   不丢图（FR-01/AC-01）。
2. 多模态识图与判定：真实视觉模型（C-01 候选 glm-5.3-flash，ark 端点，2026-11-09 实测
   具备图片理解）读图并判定问题类型；提炼为语义自洽的纯文本问题（FR-02/FR-04/AC-02）。
3. A2A 出站转发：提炼后的问题以纯文本 Part 出站送达 FAQ fixture，wire 中不携带截图
   （FR-05/AC-04，对应 FRS-058 第 2 节约束"检索只依赖纯文本"）。
4. FAQ 方案接收与回复组织：FAQ 返回文本/web url/图片三类内容，最终回复三类齐全、对应
   关系正确（FR-06/FR-07/AC-05，用例 6.4）。
5. 信息不足追问与同 Task 续接：INPUT_REQUIRED 挂起、补充答复后同 Task 续接、不要求重发
   截图（FR-03/AC-03，用例 6.2）；多轮追问仍不足时显式收敛（用例 7.6）；追问挂起期间取消。
6. 异常显式化：截图超限（协议层 -32602 InvalidParams）、非 png/jpg（业务层 EDP-FILE-003
   或等价）、模型无视觉能力、FAQ 无匹配/不可用、FAQ 图片不可访问（文本+url 降级）各自
   返回可区分结果（FR-08/AC-06，用例 7.1~7.5）。
7. 纯文本调用兼容：仅文字无截图，入口/Task/响应语义不变，不出现图片处理类报错
   （FR-09/AC-07，用例 6.3）。
8. 装配可复现与可观测：三类装配项以配置提供（FR-10/AC-08）；关键步骤轨迹可查询
   （FR-11/AC-09）。

### 非范围

1. FEAT-036 Part 协议全矩阵（32 场景）——由 docs/cases/FEAT-036-a2a-part-file-and-data-transfer.md
   承接，本设计只复用其 SUT 与 fixture 机制验证 FRS-058 场景。
2. FEAT-032 多模态契约包与工具细节（reason 字符串、限额、资产链）——由
   docs/cases/FEAT-032-multimodal-agent-support.md 承接。
3. FEAT-008 交互式中断通用机制——由 FEAT-008 设计承接；本设计只验证其在"截图信息不足
   追问"场景下的行为表现。
4. 外部网元（手机银行 APP、A2A 网关、FAQ 工作流）的真实改造与联调——FRS-058 明确 OUT，
   FAQ 以 fixture 替身。
5. 多图、视频、非 png/jpg 格式的正向处理——FRS-058 第 3.2 节远期演进。
6. 生产 SLA/容量/高可用——POC 边界外。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| docs/develop/01-scenarios/FRS-058-多模态能力展示智能体Demo.md（v0.2 草稿，2026-10-30） | FR-01~FR-11、用例 6.1~7.6、第 8 节异常处理、第 10 节契约、第 11 节验收标准、附录 A 配置项 C-01~C-03 |
| FEAT-036 测试设计（docs/cases/FEAT-036-a2a-part-file-and-data-transfer.md） | multipart 兼容入口行为、协议层校验错误码（-32602）、10MB raw 限额 |
| FEAT-032 测试设计（docs/cases/FEAT-032-multimodal-agent-support.md） | 多模态识图基座行为、模型不兼容错误语义 |
| FEAT-008 设计 | INPUT_REQUIRED 交互中断、同 Task SendMessage 续接语义 |
| multi-modal-FAQ-agent README（agent-solution@65efeecd，模块自述） | 装配面 SUT wire 契约（§5.1 入站字段、§5.2 六类 SSE 事件）、环境变量与装配项（C-01 视觉模型、FAQ_BASE_URL、APP_BASE_URL 深链基准）、已知限制 |
| SUT 交付记录（本文件第 1 节 SUT 交付现状） | multi-modal-FAQ-agent 0.1.2（agent-solution@65efeecd，2026-11-10）/ multi-modal-tools-demo 0.1.3 制品坐标与现状 |

## 4. 部署拓扑

```text
                 multipart(png+text) + SSE 响应               A2A message/send(纯文本问题)/结果回流
 [测试] ─────────────────────────────► [SUT multi-modal-FAQ-agent 0.1.2（faq-poc-agent :8290）]
        /v1/projects/{p}/conversations/{c}        │ DeepAgent（EDPA engine）
        Accept: text/event-stream                 │ VISION_MODEL=glm-5.3-flash 识图判类（ask_user 追问）
                                                  │ 纯文本提炼 / FaqPocSolutionRail 方案卡片
                                                  ▼
                                        [FAQ fixture（FaqStub：捕获 + 可控应答）]
                                        三形态方案 / 无匹配 / 503 / 40s 延迟 / 失效图片
                                                  │ 方案回流
                                                  ▼
                                     [SUT] ── status/chunk/solution/input_required/
                                             error/final SSE + 非流式 Task 信封 ──► [测试断言]
```

边界要求：
- 真实依赖（不 mock）：视觉模型服务（glm-5.3-flash@ark，网络可达）；Redis（SUT 持久化，
  容器托管）。
- 真实 SUT（正式制品）：multi-modal-FAQ-agent 0.1.2（com.openjiuwen.example.faqpoc，
  运行框架 agent-runtime-java 0.1.4 + agent-service-app-custom-rest 0.1.3）。FRS058-11
  的独立栈（Feat058NonVisionModelBlackboxTest）拉起同制品，仅 VISION_MODEL 覆盖为
  纯文本型号（默认 glm-5.2，经 FRS058_NON_VISION_MODEL 环境变量可换）。
- 观察 surface 限定：HTTP 状态码、multipart 入口响应（SSE 六类事件/非流式 Task 信封）、
  JSON-RPC code/message、出站 wire 快照（FAQ stub 侧捕获）、最终回复文本与 Part 形态。
  不断言 SUT 内部日志格式与私有字段。全场景维持黑盒边界（2026-11-12 起，原
  FRS058-02/11 进程内断言面例外已随归属整改撤除）。
- 隔离策略：每用例独立 conversation_id/project_id；FAQ stub wire 快照用例间 reset。
- 故障注入手段：FAQ stub 进程外可控应答（503/无匹配/40s 延迟/图片 url 失效），
  禁止修改产品对象。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| FRS058-01 | multipart 图文入站不丢图（用例 6.1 步骤 1~2，FR-01/AC-01） | SUT 就绪；视觉模型可达 | (1) multipart 提交单张 png（内容含唯一可判定印刷标识）+文字"转账时报错怎么办" (2) 等 Task 终态 (3) 取 Agent 对截图内容的表述 | 入口返回 200 且响应含 answer 字段；Agent 回答中引用截图内唯一标识（证明截图完整进入且未被丢弃） | 程序生成 png 样张（含唯一文本标识） |
| FRS058-02 | 识图判类与纯文本提炼（用例 6.1 步骤 3~4，FR-02/FR-04/AC-02） | SUT 就绪；真实视觉模型 glm-5.3-flash 可达 | (1) multipart 提交预置异常页面样张+文字"转账时报错怎么办" (2) 等 Task 终态 (3) 取最终回复与 FAQ 出站 wire | 最终回复为纯文本（不含 base64/data:image）；回复引用样张实际异常（唯一错误码 TX-9527，判类 transfer_error 域）；以纯文本问题委托 FAQ（出站 wire 与样张异常一致）。2026-11-12 归属整改：断言面从 multi-modal-tools-demo 进程内驱动收敛到 SUT 端到端黑盒 | 预置异常样张（唯一报错码） |
| FRS058-03 | A2A 出站转发为纯文本问题（FR-05/AC-04） | SUT 就绪；FAQ stub 捕获 wire | (1) multipart 提交 png+文字（意图落在场景规划域内） (2) 等 Task 终态 (3) 读 FAQ stub 捕获的出站 wire | 出站消息 Part 为文本 Part；wire 中不含截图 base64 图片数据；出站文本非空且与提交意图相关 | FAQ stub（捕获模式） |
| FRS058-04 | FAQ 三形态方案组织图文+url 回复（用例 6.4，FR-06/FR-07/AC-05） | FRS-058 装配面已装配（FAQ 编排与回复组织） | (1) multipart 提交 png+文字 (2) FAQ stub 按文本+url+图片三形态应答 (3) 等 Task 终态 (4) 检查最终回复 | 回复包含方案文本、FAQ 返回的 web url 字符串、图片（与 FAQ 返回一致）；三类不缺失、不错位 | FAQ stub（三形态应答模式） |
| FRS058-05 | 信息不足追问后同 Task 续接（用例 6.2，FR-03/AC-03） | FRS-058 装配面已装配（识图判类、INPUT_REQUIRED 追问分支） | (1) 提交无法唯一判定问题的截图+文字"用不了" (2) 观察 Task 状态 (3) 同 Task 补充答复 (4) 等待终态 | Task 进入 INPUT_REQUIRED 且回复为澄清提问；补充后同一 taskId 续接至终态并给出方案；不要求重发原截图 | 信息不足样张 |
| FRS058-06 | 多轮追问仍信息不足显式收敛（用例 7.6） | 同 FRS058-05 前置 | (1) 提交信息不足输入 (2) 多轮补充仍无法判定 (3) 观察最终结果 | 收到明确的"无法定位问题"类结论与下一步建议（更换提问方式/转人工）；无伪造方案；Task 为终态而非持续挂起 | 信息不足样张 |
| FRS058-07 | 追问挂起期间用户取消（FRS-058 第 8.1 节）【暂缓执行：SUT 缺陷待修复，见 §9 暂缓登记】 | 同 FRS058-05 前置 | (1) 触发 INPUT_REQUIRED 挂起 (2) 对原 Task 发起取消 (3) 查询 Task 状态 | Task 进入 CANCELED；后续续接不再推进；无残留挂起态。【暂缓：A2aJsonRpcController 未分发 CancelTask（-32601）+ 挂起终态写回缺失（issues/draft-frs058-cancel-input-required-not-propagated.md，P1），缺陷修复后恢复执行】 | — |
| FRS058-08 | 纯文本调用兼容（用例 6.3，FR-09/AC-07） | SUT 就绪 | (1) 仅提交文字"忘记登录密码怎么办"（无文件 Part） (2) 等 Task 终态 | 200 且 answer 正常返回；响应不含图片处理类报错（无 -32602、无 EDP-FILE-、无 FORMAT_UNSUPPORTED 类错误码/文案） | — |
| FRS058-09 | 截图超限或非法（用例 7.1，FR-08/AC-06） | SUT 就绪；FAQ stub 捕获 wire | (1) multipart 提交大于 10MB 的文件 (2) 读响应错误表面 (3) 查 FAQ stub wire (4) 子分支②：提交文件 Part 中途截断的 multipart 报文（文件字段无法完整解析；完全无 boundary 结构的报文会被解析为空表单而非解析失败，run2 取证，故不采用该形态）读错误表面 | 分支①：-32602（InvalidParams）或等价参数错误；分支②：协议层 4xx/5xx 显式拒绝；两分支 FAQ stub 均零新增捕获（未进入识图与 FAQ 链路）；不静默成功。基线偏差登记：当前制品错误码位为空（通用 HTTP 400，业务码无法透出），见 issues/draft-frs058-file-error-surface-not-projected.md——修复前断言暂按"显式错误表面（HTTP≥400 或 SSE error 帧）+ 零出站"执行，修复后恢复基线断言 | 大于 10MB 填充文件；Part 截断 multipart 报文 |
| FRS058-10 | 文件类型不在白名单（用例 7.2，FR-08/AC-06） | SUT 就绪 | (1) multipart 提交非 png/jpg 文件（gif 内容，png 扩展名规避协议层） (2) 读响应错误表面 | 返回 EDP-FILE-003 或等价"类型不支持"业务错误，可与协议层参数错误区分；不进入识图链路。基线偏差登记：同 FRS058-09（错误码透出缺失 issue），修复前断言暂按"显式错误表面 + 不出站"执行，修复后恢复"可区分"断言 | gif 内容文件 |
| FRS058-11 | 模型不具备视觉能力（用例 7.3，FR-08/AC-06） | 独立 SUT 栈，VISION_MODEL 覆盖为纯文本模型（deepseek-v3.2 或 glm-5.2——后者为 ark coding 网关实测对 image_url 显式拒绝的纯文本型号，经 FRS058_NON_VISION_MODEL 环境变量可覆盖） | (1) 向该 SUT multipart 提交截图+文字 (2) 读响应错误表面 (3) 查 FAQ stub wire | 返回明确的失败/不兼容错误表面（非 completed 正常作答、无 error 帧掩码）；不静默丢图、不静默降级为纯文本作答；FAQ stub 零出站（不进入 FAQ 链路）。2026-11-12 归属整改：被测对象从 multi-modal-tools-demo 进程内驱动改为 SUT 本体（Feat058NonVisionModelBlackboxTest） | 纯文本模型注入（独立栈） |
| FRS058-12 | FAQ 无匹配 vs FAQ 不可用（用例 7.4，FR-08/AC-06） | SUT 就绪；FAQ stub 可控 | (1) FAQ stub 应答"无匹配方案"态走全链路 (2) FAQ stub 改 503 故障注入重走全链路 (3) 分支③（§8.1）：stub 应答前延迟 40s（> 注入的远程调用预算 20s，经 env 收紧 remote-agents[0].timeout-seconds），由客户端侧超时收敛重走全链路 (4) 比较三类结果 | 无匹配："暂未找到对应方案"类提示；不可用/超时："服务暂时不可用/可重试"类提示；三类结果可区分；均不伪造方案；超时分支 Task 终态收敛不悬挂 | FAQ stub（无匹配/503/延迟三种模式）；remote-agents[0].timeout-seconds=20 env 注入 |
| FRS058-13 | FAQ 图片不可访问降级（用例 7.5，FR-08/AC-06） | SUT 就绪；FAQ stub 返回文本+url+失效图片引用 | (1) FAQ stub 返回方案（图片 url 指向 404） (2) 等终态 (3) 检查最终回复 | 文本与 url 方案保留在回复中；图片失效有明确占位/提示（非静默裂图）；整体答复不阻断（Task 终态非失败） | FAQ stub（失效图片模式） |
| FRS058-14 | 装配配置化可复现（FR-10/AC-08，附录 A） | 取得 Demo 装配 | (1) 检查 C-01 视觉模型型号、C-02 multipart 入口、C-03 FAQ A2A 目标地址是否均以配置提供；补充验证项 C-04 深链补全基准（APP_BASE_URL，出处为模块 README §4，超出 FRS-058 附录 A 三类项口径，见 §9 登记） (2) 以 managed stack 环境变量注入近似复现"按配置装配"（干净环境整机重装未纳入自动化，见 §9 登记），重跑 FRS058-01 全链路 | 三类装配项存在对应配置键且修改配置无需改框架代码；C-03 静态图相对 url 按 FAQ_BASE_URL、C-04 深链相对 url 按 APP_BASE_URL 补全；装配后 FRS058-01 复现通过 | — |
| FRS058-15 | 多模态处理过程可观测（FR-11/AC-09，DFX-001） | POC 可观测能力就绪 | (1) 执行 FRS058-01 全链路 (2) 按 Task 查询轨迹 | 轨迹覆盖 multipart 接收解析、识图判类、文本提炼、FAQ 转发与回传、回复组织各关键步骤；失败场景可定位到失败阶段 | DFX-001 轨迹查询面 |
| FRS058-16 | 追问答复补充新截图纳入识图续接（FR-03/AC-03，§5.2 分支"追问答复补充图片"） | 信息不足样张 + 新异常样张 | (1) 提交模糊截图+文字"用不了"触发 INPUT_REQUIRED 挂起 (2) 同一会话补充答复【携带新截图】（含唯一错误码 TX-9527 的异常样张，不重发原截图） (3) 等待终态 (4) 查 FAQ stub wire | 同一会话续接至 completed；出站提炼问题含新截图唯一锚点 TX-9527（新截图作为新增图片 Part 纳入识图）；不要求重发原截图；FAQ 命中给出方案 | 模糊样张 + 异常样张（TX-9527） |
| FRS058-17 | 跨用户会话上下文隔离（§8.3 / §11.3） | SUT 就绪；FAQ stub 捕获 wire | (1) 用户A独立会话提交异常样张（TX-9527）完成排障 (2) 用户B独立 project+conversation 提交无关纯文本问题 (3) 比较B的答复与B出站 wire | B 正常收敛且作答非空；B 答复不含 A 样张唯一锚点 TX-9527；B 出站 wire 不含 A 会话截图内容（上下文不跨用户串用） | 异常样张（TX-9527） |

### 5.1 显式 deferred / 白盒联动项

- FRS058-07 暂缓执行（2026-11-16 登记）：对应用例
  Feat058MultimodalDemoBlackboxTest#frs05807CancelDuringSuspension 已加 @Disabled 跳过——
  SUT 缺陷（issues/draft-frs058-cancel-input-required-not-propagated.md，P1）为双层根因
  （A2aJsonRpcController 方法 switch 无 CancelTask 分支返回 -32601 + 挂起路径 EventQueue
  已关闭致 CANCELED 终态无处写回），缺陷持续复现（run14/17/18/20），修复前执行必然失败。
  缺陷修复（新增 CancelTask 分支 + 挂起任务终态直写 TaskStore）后移除 @Disabled 恢复执行，
  并回归 FRS058-05（挂起-续接不受影响）。断言本体已加强到信封层
  （doesNotContain("\"error\"")），恢复后无需再改断言。
- 原"FRS058-04/05/06/07/12/13/14/15 依赖 Demo 装配面、装配面缺失按实现缺失 FAIL"的
  deferred 前提已解除（装配面已于 2026-11-10 交付，见第 1 节 SUT 交付现状），上述场景
  直接按矩阵执行。
- FRS058-03 出站 Part 精确结构（raw/text/data kind 断言）由 FEAT-036 矩阵 #23~#25 承接，
  本设计只断言"纯文本出站、无截图数据"。
- 2026-11-12 归属整改后：FRS058-01~17 全部为 SUT 黑盒面，无进程内白盒联动项——原
  "FRS058-02/11 驱动 multi-modal-tools-demo MultimodalToolsAgent 进程内断言面"已撤除
  （实测装配面 SUT 识图为自有实现，不依赖 demo 链；例外登记随撤除同步解除，见 §9）。

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| multi-modal-FAQ-agent 0.1.2（faq-poc-agent：custom-rest multipart 入口 + DeepAgent 装配 + SSE 投影） | 真实 SUT | agent-solution@65efeecd（2026-11-10）交付的 FRS-058 Demo 装配面；承载 multipart 入站、识图判类（自有 MultimodalInputRail）、提炼、A2A 出站 FAQ 与方案卡片组织；无 Redis 依赖（模块自述） |
| 视觉模型 glm-5.3-flash@ark（C-01 候选之一） | 真实依赖 | 2026-11-09 实测具备图片理解；凭据经环境变量注入，不落库 |
| 纯文本模型 glm-5.2（默认）/deepseek-v3.2（可覆盖） | 真实依赖 | FRS058-11 无视觉能力场景注入 |
| FAQ stub（FaqStub：A2A 捕获 + 可控应答：三形态/无匹配/503/40s 延迟/失效图片 + AgentCard discovery） | Fixture | 测试进程内 HTTP 服务，捕获出站 wire；故障注入均为 stub 侧行为 |
| Redis（redis:7-alpine 容器） | Fixture | SUT 持久化依赖，栈托管 |
| 程序生成样张（png/jpg，含唯一可判定标识 TX-9527） | Fixture | 保证识图断言确定性；超限样张用填充字节生成 |

## 7. 关键链路断言

- 主门禁（任一失败即特性不通过）：FRS058-01、FRS058-02、FRS058-03、FRS058-08、FRS058-09。
- 失败语义与基线同构：FRS058-09 错误表面必须与 FEAT-036 协议层校验基线一致
  （-32602 InvalidParams），不得被 SUT 私有错误码替代。基线偏差登记（2026-11-12）：
  当前制品因 custom-rest 错误投影缺口（适配器 ResponseStatusException 未被映射，
  通用 HTTP 400，业务码 FILE_TYPE_UNSUPPORTED/FILE_TOO_LARGE 无法透出），该断言
  在 run6 实测失败后暂按"显式错误表面 + 零出站"降级执行——已按流程开
  issues/draft-frs058-file-error-surface-not-projected.md，issue 修复后恢复基线断言。
- 负向场景同时命中"目标错误 + 前进阻断"：FRS058-09/10/11/12 除断言目标错误码/文案外，
  必须同时断言链路未继续推进（FAQ stub 零捕获或 Task 非成功终态），防"报错但仍转发"假阴性。
- 纯文本回归：FRS058-08 不得因多模态装配引入语义漂移（入口/Task/响应结构与存量一致）。
- 跨用户隔离：FRS058-17 断言用户B答复与出站 wire 均不含用户A样张唯一锚点（§8.3 会话上下文不跨用户串用，锚定唯一标识防误判）。
- 识图真实性：FRS058-01/02 断言锚定样张内程序生成的唯一标识，禁止"回答非空"类弱断言
  替代（防模型幻觉通过）。

## 8. 执行策略

- Smoke 集合（必跑子集）：FRS058-01、FRS058-08、FRS058-09。
- Full suite：FRS058-01 ~ FRS058-17 全量（FRS058-07 因 SUT 缺陷待修复暂以 @Disabled
  跳过，见 §9 暂缓登记，修复后恢复）。
- P0 必须全绿：FRS058-01/02/03/08/09（主门禁）。
- 依赖门禁：SUT 制品缺失（multi-modal-FAQ-agent / 相关 ext 构件不在 .m2）→ 整类
  dependency-gated SKIPPED（不计 PASS）；视觉模型凭据未注入
  （VISION_API_KEY/VISION_BASE_URL）→ Demo 主类与 FRS058-11 独立栈类
  （Feat058NonVisionModelBlackboxTest）的 buildStack 均对凭据缺失抛
  TestAbortedException，整类 dependency-gated SKIPPED 而非 ERROR（2026-11-12 整改口径，
  原"识图断言面测试类 assumeTrue 门禁"随进程内断言面撤除而废止）。
  Redis 容器不可用 → SUT 链路用例 dependency-gated SKIPPED。
- 推荐 agent：feat_test.yaml 已配置 recommended_agent 为 multi-modal-FAQ-agent 模块
  路径（D:\code\test\agent-solution\common\example\multi-modal-FAQ-agent，2026-11-10
  SUT 交付后填入）。
- 执行命令：

```bash
export SUT_SOURCE_DIR='D:\code\test\agent-solution'
export AGENT_CORE_DIR='D:\code\test\agent-core-java'
export AGENT_RUNTIME_DIR='D:\code\test\agent-runtime-java'
./scripts/sut/provision-sut.sh --env openjiuwen

./mvnw test -Dtest.env=openjiuwen -Dgroups=frs-058 -Dmaven.test.failure.ignore=true
```

## 9. 差异、门禁与待澄清项（附录）

- 标签冲突登记：feat-058/F058- 前缀已被 FEAT-058（模型调用超时分层）占用，本特性使用
  frs-058/FRS058-，执行过滤请使用 -Dgroups=frs-058。
- FAQ 截图随发待澄清：FRS-058 第 3.3 节明确"未确认前不假设 FAQ 消费图片"，FRS058-03
  断言出站纯文本即该口径的可执行化；若需求方后续确认截图随发，需修订本场景。
- 模型无视觉能力场景（FRS058-11）：FRS-058 用例 7.3 前置为"POC 装配或切换到无视觉能力
  的模型"；C-01 四个候选型号（千问/GLM 5.3 Flash/Qwen 3.8 Flash/Kimi 3）均为视觉型号，
  执行时以独立纯文本模型注入识图链路构造前置，属环境构造而非 SUT 改造。2026-11-12 更新：
  实测默认取 ark coding 网关对 image_url 显式拒绝的纯文本型号 glm-5.2（可经
  frs058.nonVisionModel / FRS058_NON_VISION_MODEL 覆盖为 deepseek-v3.2 等）。
- FRS058-09 分支②报文形态（2026-11-12 登记）：矩阵初稿"结构非法的 multipart 报文"
  按取证实测收敛为"文件 Part 中途截断"形态——完全无 boundary 结构的报文会被容器解析为
  空表单而非解析失败（run2 取证），不构成"文件字段无法解析"前置；截断形态在用例中留有
  取证注释。
- 补充验证项 C-04（2026-11-12 登记）：FRS058-14 断言的深链补全基准 APP_BASE_URL
  不在 FRS-058 附录 A（附录 A 仅 C-01~C-03 三类装配项），出处为模块 README §4
  （模块自述装配项）；该断言属超出特性档口径的补充验证，不影响 AC-08 判定，
  特性档如后续吸纳 C-04 应回填附录 A。
- 干净环境复现近似（2026-11-12 登记）：FRS058-14 矩阵步骤 (2)"干净环境按配置装配后
  重跑"以 managed stack 每轮拉起 SUT + 环境变量注入（C-01/C-03/C-04 三件套）近似覆盖；
  整机干净环境重装未纳入自动化，属执行环境能力边界而非断言降级。
- 识图断言面归属整改（2026-11-12 登记并落地）：原 §9"进程内断言面例外"（FRS058-02/11
  驱动 multi-modal-tools-demo MultimodalToolsAgent）**撤除**——实测装配面 SUT 的识图
  为自有实现（MultimodalInputRail + FaqPocLlmProperties，pom 不依赖
  multi-modal-tools-demo，链路不经 MultimodalContentResolver），进程内驱动的断言对象
  是 FEAT-032 demo 链而非本特性 SUT，属断言面错位。整改内容：FRS058-02 收敛为 SUT
  端到端黑盒（Demo 主类新增用例，锚定 TX-9527 + 纯文本性 + FAQ 委托）；FRS058-11 迁至
  独立栈类 Feat058NonVisionModelBlackboxTest（SUT 注入 VISION_MODEL=纯文本型号，
  黑盒断言显式失败表面 + FAQ 零出站）；原 Feat058VisionUnderstandingBlackboxTest
  退役（demo 链 wire/工具面断言归还 FEAT-032 测试域，该域已有
  ObservationToolContractTest / MultimodalEntryContractTest / Pr404EntryDirectTest 覆盖）；
  FRS058-02 波动性缺陷（base64 回显截断）随归属重登记为
  issues/draft-feat032-vision-tool-args-base64-truncation.md。
- FRS058-12 "两类结果可区分"断言（2026-11-12 登记并整改，同日两轮）：原 doesNotContain("X|X")
  仅在文案恰好逐字等于固定串时生效，第一轮整改为类别词集互斥断言；run14 实证词集
  仍过宽——模型复述样张内嵌错误文案（"转账渠道连接失败，请稍后重试"）与"稍后/重试"
  词集碰撞，误判失败。第二轮整改锚定 SUT rail 规范收尾后缀（FaqPocSolutionRail.java:47-48，
  NO_MATCH="暂未找到对应方案，请换个问法或转人工" / UNAVAILABLE="FAQ 服务暂时不可用，
  请稍后重试"）互斥判定：后缀只由对应分支追加，模型自由文本不参与判定。run15 实证通过。
- 覆盖缺口整改（2026-11-12 登记并整改）：FRS058-02 已补"模型调用 wire 含图片块"观察行为
  断言（wire 字面量按 agent-core MultimodalContentResolver 的 Chat Completions 投影为
  type=image_url + {url:"data:image/..."}，run15 证实 image 字面量是入口块形态——两类均
  接受并以 data:image/ 前缀校验内联字节，run15c 复跑通过）；FRS058-07 已补"取消后补发
  续接消息、原 Task 仍保持 CANCELED（不再推进）"断言，并于 run15 再加强受理断言到
  JSON-RPC 信封层（HTTP 200 会掩盖信封内 -32601，run14/15 实证）；FRS058-15 已补 SSE
  status 帧阶段覆盖断言（received/vision）+ FAQ 转发出站留痕 + 回复组织留痕，"按 Task
  查询轨迹"侧 history/artifacts 断言保留（history 恒空挂 issues/draft-frs058-task-history-
  empty-dfx.md）；"失败场景可定位到失败阶段"待 SUT 补齐 faq/compose 阶段流帧约定后落
  断言（模块 README §6 注明该约定联调后补充）。
- 执行记录（2026-11-12，-Dtest.env=openjiuwen -Dgroups=frs-058）：run13 13F+1E——环境
  缺陷（shell LLM_API_KEY 为 modelarts 端点凭据，SUT 规划模型按 C-01 回退打到 ark
  coding 端点 401 "API key format is incorrect"，识图依赖全数快速失败）；经 moa-a2a-
  testenv/.env 注入 VISION_* 三件套（凭据仅经环境变量传递不落库）后 run14 收敛为
  13P/4F，用例整改后 run15 为 14P/3F（FRS058-02 的失败为断言解析形态误判，run15c
  单类复跑 2P/0F 证实）。
  **run16（归属整改后终态）**：识图断言面收敛到 SUT 黑盒（FRS058-02 新增 SUT 用例、
  FRS058-11 迁独立栈类、原 Vision 类退役），17 用例（16+1）= **14P/3F**：FRS058-07
  （-32601）、FRS058-15（history 恒空）为既有代码缺陷；FRS058-11 在 SUT 上揭示
  **新缺陷**——引擎侧模型失败被吞（completed + 空答案，无 error 帧），挂
  draft-frs058-model-failure-silent-empty-answer.md。无断言弱化。
  **run17（2026-11-13，SUT 源码更新后回归）**：SUT 源码升至 agent-solution common@9106160e
  （multi-modal-FAQ-agent 0.1.2，16:17 从源码重建；新增 e7691b08 图片输入改走 runtime 原生
  桥接撤除 MultimodalInputRail 兜底、9106160e SSE FAILED 终态投影修复），runtime 仍为
  8fc83a41。17 用例 = **14P/3F**：FRS058-11 **转 PASS**（9106160e 修复验证通过，
  issue draft-frs058-model-failure-silent-empty-answer.md 已按验收条件关闭）；FRS058-07/15
  既有缺陷持续；FRS058-16 转失败（run17b 单用例复跑同败：续接轮 completed=false、
  FAQ 零出站）——既有 issue draft-frs058-resume-supplement-image-dropped.md 持续，
  且 e7691b08 撤除 Rail 兜底使续接路径失去唯一注入手段（续接轮图片缺失根因在 runtime
  JiuwenCoreAgentHandler.buildInputs:818-825 remoteToolResults 分支提前 return，修复优先级
  建议上调，证据已登记至该 issue）。无断言弱化，无新增 issue。
  **run18（2026-11-13，三仓最新源码全量重建后回归）**：provision 8 步全 ✓（17:48~17:52
  落 .m2）——agent-core-java@63df7112（0.1.17，fix: preserve multimodal input on
  ask_user resume，正对 FRS058-16 缺陷域）+ agent-runtime-java@8fc83a41（0.1.4）+
  agent-solution common@9106160e（0.1.2）。17 用例 = **15P/2F**：FRS058-16 **转 PASS**
  （core 63df7112 修复验证通过，issue draft-frs058-resume-supplement-image-dropped.md
  已按验收条件关闭；首轮/纯文本续接回归 FRS058-01/02/03/05 同轮全数通过）；FRS058-07
  （CancelTask -32601）、FRS058-15（history 恒空）既有缺陷持续，已各自追加 run18 复跑
  登记。无断言弱化，无新增 issue。
  **run25（2026-11-16，三仓最新源码全量重建后回归）**：provision 8 步全 ✓（17:37~17:38
  落 .m2）——agent-core-java@edee2312（0.1.17）/ agent-runtime-java@c977cb18（0.1.4，
  fix(a2a): preserve task input and reply history）/ agent-solution common@7e24a644
  （multi-modal-FAQ-agent 0.1.2）。frs-058 组 17 用例 = **16P/0F/1Skip**（skip 为
  @Disabled 的 FRS058-07，见上方暂缓登记）：FRS058-15 **转 PASS**（c977cb18
  A2AReplyHistory 修复验证通过，issue draft-frs058-task-history-empty-dfx.md 按验收
  条件关闭）；FRS058-11/16 延续 PASS（9106160e / core 63df7112 修复回归通过）；
  FRS058-01~06/08~14/17 全数 PASS，主门禁（01/02/03/08/09）全绿。无断言弱化，
  无新增 issue。
- FRS058-07 暂缓登记（2026-11-16）：SUT 缺陷
  issues/draft-frs058-cancel-input-required-not-propagated.md（P1，双层根因：A2aJsonRpcController
  未分发 CancelTask——探针实证 CancelTask/tasks/cancel 两种拼写均 -32601 且被 HTTP 200 掩盖；
  叠加根因：INPUT_REQUIRED 挂起路径已关闭 EventQueue，CANCELED 终态无处持久化）历经
  run14/run17/run18/run20 多轮复跑持续存在，缺陷修复前该场景执行必然失败。处置：
  测试用例 Feat058MultimodalDemoBlackboxTest#frs05807CancelDuringSuspension 以 @Disabled
  跳过（理由字段已指向本 issue），矩阵行标注【暂缓执行】；缺陷修复验收即复跑本用例
  （断言已下探到 JSON-RPC 信封层，恢复执行无需改断言），并回归 FRS058-05。
- FRS058-07 暂缓处置更新（2026-11-16，run25 轮确认）：runtime@c977cb18（"fix(a2a):
  preserve task input and reply history"）提交说明明确 "Keep CancelTask unavailable at
  the public JSON-RPC entrypoints **in accordance with the current design scope**（refs
  agent-solution issue #642）"——产品侧按当前设计范围**有意不实现** CancelTask，与
  FRS-058 第 8.1 节"取消传播"口径构成特性档 ↔ 设计范围的分歧，待需求方裁决（修订
  特性档第 8.1 节，或取消功能排期）。裁决前 FRS058-07 维持 @Disabled 暂缓，不计失败。
- 已开 issue 清单（2026-11-12）：issues/draft-frs058-cancel-input-required-not-propagated.md
  （FRS058-07 取消不收敛；同日勘误并下探根因：A2aJsonRpcController 未分发 CancelTask，
  探针实证 -32601，挂起终态写回为叠加根因）、issues/draft-frs058-task-history-empty-dfx.md
  （FRS058-15 history 恒空）、issues/draft-frs058-file-error-surface-not-projected.md
  （FRS058-09/10 错误码透出缺失，§7 基线偏差；同日复核探针实证缺陷仍在）、
  issues/draft-frs058-model-failure-silent-empty-answer.md（FRS058-11 揭示的新缺陷：
  引擎侧模型调用失败被吞——Task 以 completed + 空 answer 收敛，SSE error 帧不投出，
  run16 实证 + run13 同族旁证）。
  非 FRS-058 归属：issues/draft-feat032-vision-tool-args-base64-truncation.md
  （FEAT-032 域，multi-modal-tools-demo——发现于 FRS058-02 执行轮次 run15b，
  2026-11-12 随识图断言面归属整改移出本特性清单）。
