---
feature_id: FEAT-062
test_type: reactagent
scope: v1030-direct-nacos
deployable_units: [mainplan, trip, hosted-hotel, registry-discovery-center, nacos]
sut: multi-react-travel-demo HTTP A2A direct with four discovery profiles
features: [FEAT-062]
updated: 2026-10-28
---

# multi-react-travel-demo 验收：直连模式支持 Agent 动态注册和发现

## 1. 测试目标

以FEAT062特性设计为起点，在Core develop、Runtime develop、Solution common的指定旅行Demo上验证：mainplan在静态、动态与两种混合配置下，经真实HTTP A2A直接委派trip和hosted hotel；注册发现变化不会破坏任务、流式、输入恢复或身份边界。测试对象为目录消费与注册组件及Runtime路由行为，RDC+nacos是真实控制面依赖。

本轮102项均已精确执行：81 PASS、14 FAIL、7 ERROR、0 SKIPPED。21项未通过归属13个已确认产品问题；ERROR保留原始报告类型，说明产品故障阻断后续断言，不计作通过。所有业务场景实际启动旅行Agent，验证行程委派、酒店库存工具调用和父任务汇总；四种配置24项主业务用例为21 PASS、3 FAIL，3项为动态流式能力缺失，业务本身已完成。历史动态流式缺失和跨namespace同名目标串扰作为复测线索；通过标准来自Feature/L2，不从历史脚本的豁免条件推导。

## 2. 范围与非范围

范围：四种配置的完整直连旅程；九行仲裁；动态目录增改删、TTL收敛与失败保留；标准/hosted逐实例发布及注销；同agentId多进程候选、加权与故障切换；两开关、静态冻结、地址和租户边界。状态、错误、隔离、幂等、恢复、配置上下界都逐行保留。

非范围：declared网关档及网关本体、BUS、多副本部署编排/守护、运行中增删hosted定义、BY_CAPABILITY、服务端推送、灰度双跑、跨候选任务迁移、动态候选TLS。用户本轮选定nacos，不展开self后端等价认证；这是本轮执行范围限制，不能声称FEAT062双后端全覆盖。业务规划质量、Hook定制、TaskStore本身、Nacos内部算法均不在本特性独立验收范围。已列协议兼容只证明引入注册发现没有破坏既有路径。

| MUST/边界 | 对应场景域 | 外部判据 |
|---|---|---|
| 远端目录动态化、首拉与失败保留 | catalog.startup-*、online、cached-outage、recovery；extension.refresh-exception | 不重启可调、旧目标继续可调、恢复后对齐；意外异常单列SPI集成 |
| 条目并存仲裁九行 | #1→catalog.disabled-unknown；#2→static.sync；#3→dynamic.sync/online；#4→catalog.precedence；#5→catalog.empty-no-fallback；#6→catalog.not-found-fallback；#7→catalog.cached-outage；#8→catalog.uncached-static-outage；#9→catalog.uncached-no-route | 按 agentId 判定，不以全目录总开关代替；200 空候选与404分开 |
| 静态冻结与内置路径一致 | static-contract 全域 | 配置校验、拉卡、30s重试、永久缓存、timeout/streaming/TLS；无ext对照由测试侧隔离打包 |
| 目录变更传播 | catalog.card-update/card-delete；hosted-catalog 全域；extension.listener-recovery | 全局增改删生效；实例继承与局部覆盖分别可观察；公开扩展宿主补充触发，单独统计证据 |
| 上下线 TTL 与可度量性 | catalog.online；registration.graceful-stop/crash-stop；routing.scale-out/scale-in | 注册/服务端摘除时间至调用侧可见/不再选中 ≤TTL；记录测量区间 |
| 卡与实例生命周期独立 | catalog.empty-no-fallback/card-delete；isolation.mode-transition | 卡保留空实例不回静态；真正404可静态兜底 |
| 自动注册两形态 | registration.standard-card/hosted-cards | 标准单卡单endpoint、hosted多卡多endpoint，卡保真且可调 |
| 注册实例清单读取口 | contract.instance-readonly/instance-readiness | 正式 API 合同、只读、就绪；不能用HTTP通过替代此覆盖 |
| 优雅停机与异常摘除 | registration.graceful-stop/crash-stop/no-cross-delete | A3 与无A3分开，其他进程/身份不误删 |
| 多候选加权选择、单候选兼容 | routing.scale-*、equal-weight/weighted/invalid-weight；static-contract | 同agentId多进程，统计首发比例；非正权重经公开候选输入合同验证 |
| failover与协议兼容 | routing.connection-refused/request-timeout/all-exhausted/protocol-failure/business-failure/input-required/first-event-lock；四 Profile stream/*resume | 拒连/超时可切换；其他错误和首事件后不切；任务不跨候选迁移 |
| 服务不可用三态 | catalog.cached-outage/uncached-static-outage/uncached-no-route；registration.runtime-outage | 缓存、静态、显式失败分别判定；注册面与发现面故障分开 |
| 两开关、地址与架构约束 | switch 全域；catalog.resolve-*、handle-refresh；auth 全域；contract.architecture | 两开关正交、默认resolve、D4取址、租户不串、主制品依赖边界 |

以上短 ID 均以 `FEAT-062.` 为前缀，`*` 仅在追溯说明中表示同一域的已列场景；真正执行与统计按矩阵独立 ID。不存在 SHOULD 等级的新增承诺，不把建议性 TTL 默认值当作唯一合法值。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-062-direct-mode-agent-dynamic-registration-and-discovery.md`（v1030，updated 2026-10-21） | 主要判据：§2 的 12 项 MUST；§4.1 九行条目仲裁；§4.2 旅程；§5 行为、错误与 OUT；§6 静态冻结和指标。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-062-direct-mode-agent-dynamic-registration-and-discovery.md`（updated 2026-10-21） | 细化：首事件锁定、权重、两进程注册形态、公开扩展合同、TTL [1000,30000]、参数校验与错误处理。文中的 0.1.3 当前实现仅是历史基线。 |
| `develop/02-features/FEAT-048-agent-registry-spi-nacos.md`（仅引用 FEAT062 已声明依赖的统一 API 契约） | A1—A3/D1—D4、租户头、403 隔离、心跳窗口、routeHandle 不透明性；不扩大为 RDC 全功能验收。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-bus/Feat-Func-048-agent-registry-spi-nacos.md`（仅细化上述依赖的触发条件） | §2.4/§6.1 的可选X-Caller-Ref及caller→tenant绑定，用于在真实RDC上产生403；不要求两个合法tenant不同就自动拒绝，也不扩大为企业IAM验收。 |

读取日期 2026-10-28；文档仓修订 `147590c00c05a12fe49d53d53b59eb9cfa65c0d5`。矩阵中 F 指 FEAT062，L 指对应 L2。静态取址冲突已先记录：`FEAT062-特性设计文档&L2文档冲突点.md`；以 Feature 作为本轮起点，差异地址场景直接按Feature §6执行，不需用户另行裁决；不能按实现反向改写。

## 4. 部署拓扑

```text
测试调用方 -- A2A JSON-RPC / SSE --> mainplan（标准单 Agent）
                                       ├── HTTP A2A --> trip（标准单 Agent，/a2a）
                                       └── HTTP A2A --> hotel（同一 Runtime）
                                                          ├ /a2a/agents/travel-hotel
                                                          └ /a2a/agents/travel-hotel-alt
动态控制面：Runtime -- HTTP 统一 API --> RDC -- HTTP/gRPC --> Nacos 3.2.4 AI
业务数据面：mainplan 直接到 trip/hotel；RDC 只参与注册与发现
边界夹具：透明网络记录/故障代理、SSE 记录器、LLM 输入脚本/真实 LLM 记录器
多副本：额外启动相同 trip/hotel jar，相同 agentId、不同进程端口和工作目录
跨租户：A/B 两套上述完整链共享同一 RDC，namespace、端口、canary 各异
```

| 配置 Profile | mainplan 静态目标 | mainplan 发现/注册 | trip 发现/注册 | hotel 发现/注册 | 动态 D3 精确集合 |
|---|---|---|---|---|---|
| static | travel-trip、travel-hotel | false/false | false/false | false/false | 不请求 RDC；观察器确认零注册发现流量 |
| dynamic | 无 | true/false | false/true | false/true | travel-trip、travel-hotel、travel-hotel-alt |
| mixed-trip-static | travel-trip | true/false | false/false | false/true | travel-hotel、travel-hotel-alt |
| mixed-hotel-static | travel-hotel | true/false | false/true | false/false | travel-trip |

四种 Profile 每种均有六个独立场景：同步、SSE、主任务追问恢复、远端输入中断恢复、hosted 独立寻址、hosted 任务隔离。不能用一种混合模式替代另一种。其余共享用例针对同源合同参数化：涉及动态 trip 的用 dynamic/mixed-hotel-static，涉及动态 hotel 的用 dynamic/mixed-trip-static；静态合同用 static 和两个存在静态条目的混合 Profile。只改变被测条件，保留三 Runtime 拓扑；不能把所有故障机械乘四，或让不适用的静态路径承担动态 failover 预期。

全链保持 mainplan 分别直达 trip、hotel；trip 不再委派 hotel。hotel-alt 用于独立身份，不是 hotel 的负载均衡副本。常态静态组无需 RDC/Nacos；动态和两种混合组使用真实 RDC+nacos。网关、Bus、RocketMQ、PostgreSQL 不进入本轮拓扑；不使用 gateway 卡、gateway 认证或网关所有权索引判断直连行为。

记录器只能透传并观察；故障代理仅控制网络/远端输入，不能代替目录仲裁、路由、注册组件。RDC 真实响应与代理注入必须分开归档。对非正权重、意外目录源异常等需要扩展宿主的场景，单列合同或 SPI 集成，不宣称真实 Demo 全链黑盒通过。

## 5. 测试场景矩阵

每行已对应一个独立用例方法，通过的81项自动化为 `verified`，产品问题对应21项为 `known-gap`。前置条件中的 `runnable` 表示设计可执行性，不能代替实际结果。P0/P1在本表逐行定义。

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| `FEAT-062.static.sync` | 纯静态：同步双下游闭环<br>F§2、§4.1 #2/#3/#6、§5.1.1；L§2.4、§7.2 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 向 mainplan /a2a 发 SendMessage 完整差旅需求；随后 GetTask；读取同一 parentTaskId 的网络记录与调用日志 | 无动态注册流量；静态卡从配置地址拉取；实际 RPC 直达 trip 与 hosted hotel；两个下游均实际完成且结果回灌同一父任务；最终结果含本次库存 canary；叶子无继续委派；仅父任务 COMPLETED 不足以通过 | TRAVEL + WIRE + REG |
| `FEAT-062.static.stream` | 纯静态：SSE 与下游流式兼容<br>F§1、§5.1.1、§5.1.4；L§2.4、§3.4 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；两个下游卡声明 streaming=true；展示 Hook 关闭<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 对同一完整输入发 SendStreamingMessage；分别记录入口 SSE、下游实际 method 及最终 Task | 入口为 SSE 且任务完成；两个远端实际使用 SendStreamingMessage，并各有可归属的 delegation/远端状态或输出投影；父 task/context 一致；不固定帧数/文本；动态分支缺投影或改用 SendMessage 均保留失败 | TRAVEL + WIRE + SSE |
| `FEAT-062.static.parent-resume` | 纯静态：主任务追问恢复<br>F§1、§5.1.4；L§2.4、§3.4 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；输入缺少日期等必需信息<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | SendMessage 进入 INPUT_REQUIRED；补齐信息，message.taskId/contextId 使用原值；GetTask 至完成 | 原 taskId/contextId 不变；恢复后 trip、hotel 均成功并回灌；不重建父任务、不重复已完成的有效委派；不固定追问次数 | TRAVEL + WIRE |
| `FEAT-062.static.downstream-resume` | 纯静态：远端 INPUT_REQUIRED 恢复<br>F§1、§5.1.4；L§3.4、§4.5 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；LLM 输入脚本仅使首次 trip 委派缺日期，真实 trip 返回 INPUT_REQUIRED<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 入口流式请求触发远端等待；沿原父任务补齐日期；观察同一远端 Task 的续传与随后 hotel 调用 | 等待保持为等待，不视为故障换候选；续传仍绑定原远端 task/context；两个下游最终成功；无重复新建的远端任务；LLM 脚本不伪造 A2A 状态 | TRAVEL + MODEL + WIRE |
| `FEAT-062.static.hosted-addressing` | 纯静态：两 hosted 身份独立寻址<br>F§2 单进程自动注册、§5.1.3；L§2.3、§7.2 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 读取两个 hosted well-known Card；分别直接请求 /a2a/agents/travel-hotel 与 /a2a/agents/travel-hotel-alt；动态分支另核对注册卡与 D4 | 两身份、两卡、两独立 endpoint 与实例定义一致；各自可调用；动态分支按实例发布；静态分支无 A1/A2/A3；不能把两身份计作同 agentId 的两个候选 | TRAVEL + REG + WIRE |
| `FEAT-062.static.hosted-isolation` | 纯静态：hosted 任务与上下文隔离<br>F§1 协议保持、§5.1.3；L§2.4、§7.2 | static；trip、hotel 静态；两开关均关；RDC/Nacos 不启动；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；同一 contextId、不同业务 canary<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 在两个 hosted 路径各创建任务；分别查询本实例任务，再用另一实例路径交叉 GetTask；交错继续本实例会话 | 本实例可查可续；交叉读取/续传不得访问另一实例任务或 canary；按既有 task-not-found 语义拒绝，不绑定历史错误数字；注册/发现不破坏隔离 | TRAVEL + WIRE |
| `FEAT-062.dynamic.sync` | 纯动态：同步双下游闭环<br>F§2、§4.1 #2/#3/#6、§5.1.1；L§2.4、§7.2 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 向 mainplan /a2a 发 SendMessage 完整差旅需求；随后 GetTask；读取同一 parentTaskId 的网络记录与调用日志 | D3 精确为 travel-trip、travel-hotel、travel-hotel-alt；D1 每目标一个候选；D4 分别解析至 /a2a、两个 hosted 路径；两个下游均实际完成且结果回灌同一父任务；最终结果含本次库存 canary；叶子无继续委派；仅父任务 COMPLETED 不足以通过 | TRAVEL + WIRE + REG |
| `FEAT-062.dynamic.stream` | 纯动态：SSE 与下游流式兼容<br>F§1、§5.1.1、§5.1.4；L§2.4、§3.4 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；两个下游卡声明 streaming=true；展示 Hook 关闭<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题1）；自动化 known-gap | 对同一完整输入发 SendStreamingMessage；分别记录入口 SSE、下游实际 method 及最终 Task | 入口为 SSE 且任务完成；两个远端实际使用 SendStreamingMessage，并各有可归属的 delegation/远端状态或输出投影；父 task/context 一致；不固定帧数/文本；动态分支缺投影或改用 SendMessage 均保留失败 | TRAVEL + WIRE + SSE |
| `FEAT-062.dynamic.parent-resume` | 纯动态：主任务追问恢复<br>F§1、§5.1.4；L§2.4、§3.4 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；输入缺少日期等必需信息<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | SendMessage 进入 INPUT_REQUIRED；补齐信息，message.taskId/contextId 使用原值；GetTask 至完成 | 原 taskId/contextId 不变；恢复后 trip、hotel 均成功并回灌；不重建父任务、不重复已完成的有效委派；不固定追问次数 | TRAVEL + WIRE |
| `FEAT-062.dynamic.downstream-resume` | 纯动态：远端 INPUT_REQUIRED 恢复<br>F§1、§5.1.4；L§3.4、§4.5 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；LLM 输入脚本仅使首次 trip 委派缺日期，真实 trip 返回 INPUT_REQUIRED<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 入口流式请求触发远端等待；沿原父任务补齐日期；观察同一远端 Task 的续传与随后 hotel 调用 | 等待保持为等待，不视为故障换候选；续传仍绑定原远端 task/context；两个下游最终成功；无重复新建的远端任务；LLM 脚本不伪造 A2A 状态 | TRAVEL + MODEL + WIRE |
| `FEAT-062.dynamic.hosted-addressing` | 纯动态：两 hosted 身份独立寻址<br>F§2 单进程自动注册、§5.1.3；L§2.3、§7.2 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 读取两个 hosted well-known Card；分别直接请求 /a2a/agents/travel-hotel 与 /a2a/agents/travel-hotel-alt；动态分支另核对注册卡与 D4 | 两身份、两卡、两独立 endpoint 与实例定义一致；各自可调用；动态分支按实例发布；静态分支无 A1/A2/A3；不能把两身份计作同 agentId 的两个候选 | TRAVEL + REG + WIRE |
| `FEAT-062.dynamic.hosted-isolation` | 纯动态：hosted 任务与上下文隔离<br>F§1 协议保持、§5.1.3；L§2.4、§7.2 | dynamic；mainplan 无静态条目、仅开发现；trip 和 hotel 仅开注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；同一 contextId、不同业务 canary<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 在两个 hosted 路径各创建任务；分别查询本实例任务，再用另一实例路径交叉 GetTask；交错继续本实例会话 | 本实例可查可续；交叉读取/续传不得访问另一实例任务或 canary；按既有 task-not-found 语义拒绝，不绑定历史错误数字；注册/发现不破坏隔离 | TRAVEL + WIRE |
| `FEAT-062.mixed-trip-static.sync` | Trip 静态、Hotel 动态：同步双下游闭环<br>F§2、§4.1 #2/#3/#6、§5.1.1；L§2.4、§7.2 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 向 mainplan /a2a 发 SendMessage 完整差旅需求；随后 GetTask；读取同一 parentTaskId 的网络记录与调用日志 | D3 仅 hotel 两身份；trip 的 D1 为 404；trip 走配置，hotel 走 D4；来源不互换；两个下游均实际完成且结果回灌同一父任务；最终结果含本次库存 canary；叶子无继续委派；仅父任务 COMPLETED 不足以通过 | TRAVEL + WIRE + REG |
| `FEAT-062.mixed-trip-static.stream` | Trip 静态、Hotel 动态：SSE 与下游流式兼容<br>F§1、§5.1.1、§5.1.4；L§2.4、§3.4 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；两个下游卡声明 streaming=true；展示 Hook 关闭<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题1）；自动化 known-gap | 对同一完整输入发 SendStreamingMessage；分别记录入口 SSE、下游实际 method 及最终 Task | 入口为 SSE 且任务完成；两个远端实际使用 SendStreamingMessage，并各有可归属的 delegation/远端状态或输出投影；父 task/context 一致；不固定帧数/文本；动态分支缺投影或改用 SendMessage 均保留失败 | TRAVEL + WIRE + SSE |
| `FEAT-062.mixed-trip-static.parent-resume` | Trip 静态、Hotel 动态：主任务追问恢复<br>F§1、§5.1.4；L§2.4、§3.4 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；输入缺少日期等必需信息<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | SendMessage 进入 INPUT_REQUIRED；补齐信息，message.taskId/contextId 使用原值；GetTask 至完成 | 原 taskId/contextId 不变；恢复后 trip、hotel 均成功并回灌；不重建父任务、不重复已完成的有效委派；不固定追问次数 | TRAVEL + WIRE |
| `FEAT-062.mixed-trip-static.downstream-resume` | Trip 静态、Hotel 动态：远端 INPUT_REQUIRED 恢复<br>F§1、§5.1.4；L§3.4、§4.5 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；LLM 输入脚本仅使首次 trip 委派缺日期，真实 trip 返回 INPUT_REQUIRED<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 入口流式请求触发远端等待；沿原父任务补齐日期；观察同一远端 Task 的续传与随后 hotel 调用 | 等待保持为等待，不视为故障换候选；续传仍绑定原远端 task/context；两个下游最终成功；无重复新建的远端任务；LLM 脚本不伪造 A2A 状态 | TRAVEL + MODEL + WIRE |
| `FEAT-062.mixed-trip-static.hosted-addressing` | Trip 静态、Hotel 动态：两 hosted 身份独立寻址<br>F§2 单进程自动注册、§5.1.3；L§2.3、§7.2 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 读取两个 hosted well-known Card；分别直接请求 /a2a/agents/travel-hotel 与 /a2a/agents/travel-hotel-alt；动态分支另核对注册卡与 D4 | 两身份、两卡、两独立 endpoint 与实例定义一致；各自可调用；动态分支按实例发布；静态分支无 A1/A2/A3；不能把两身份计作同 agentId 的两个候选 | TRAVEL + REG + WIRE |
| `FEAT-062.mixed-trip-static.hosted-isolation` | Trip 静态、Hotel 动态：hosted 任务与上下文隔离<br>F§1 协议保持、§5.1.3；L§2.4、§7.2 | mixed-trip-static；mainplan 静态仅 trip、发现开启；trip 不注册、hotel 两实例注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；同一 contextId、不同业务 canary<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 在两个 hosted 路径各创建任务；分别查询本实例任务，再用另一实例路径交叉 GetTask；交错继续本实例会话 | 本实例可查可续；交叉读取/续传不得访问另一实例任务或 canary；按既有 task-not-found 语义拒绝，不绑定历史错误数字；注册/发现不破坏隔离 | TRAVEL + WIRE |
| `FEAT-062.mixed-hotel-static.sync` | Trip 动态、Hotel 静态：同步双下游闭环<br>F§2、§4.1 #2/#3/#6、§5.1.1；L§2.4、§7.2 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 向 mainplan /a2a 发 SendMessage 完整差旅需求；随后 GetTask；读取同一 parentTaskId 的网络记录与调用日志 | D3 仅 travel-trip；hotel 的 D1 为 404；trip 走 D4，hotel 走配置；来源不互换；两个下游均实际完成且结果回灌同一父任务；最终结果含本次库存 canary；叶子无继续委派；仅父任务 COMPLETED 不足以通过 | TRAVEL + WIRE + REG |
| `FEAT-062.mixed-hotel-static.stream` | Trip 动态、Hotel 静态：SSE 与下游流式兼容<br>F§1、§5.1.1、§5.1.4；L§2.4、§3.4 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；两个下游卡声明 streaming=true；展示 Hook 关闭<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题1）；自动化 known-gap | 对同一完整输入发 SendStreamingMessage；分别记录入口 SSE、下游实际 method 及最终 Task | 入口为 SSE 且任务完成；两个远端实际使用 SendStreamingMessage，并各有可归属的 delegation/远端状态或输出投影；父 task/context 一致；不固定帧数/文本；动态分支缺投影或改用 SendMessage 均保留失败 | TRAVEL + WIRE + SSE |
| `FEAT-062.mixed-hotel-static.parent-resume` | Trip 动态、Hotel 静态：主任务追问恢复<br>F§1、§5.1.4；L§2.4、§3.4 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；输入缺少日期等必需信息<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | SendMessage 进入 INPUT_REQUIRED；补齐信息，message.taskId/contextId 使用原值；GetTask 至完成 | 原 taskId/contextId 不变；恢复后 trip、hotel 均成功并回灌；不重建父任务、不重复已完成的有效委派；不固定追问次数 | TRAVEL + WIRE |
| `FEAT-062.mixed-hotel-static.downstream-resume` | Trip 动态、Hotel 静态：远端 INPUT_REQUIRED 恢复<br>F§1、§5.1.4；L§3.4、§4.5 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；LLM 输入脚本仅使首次 trip 委派缺日期，真实 trip 返回 INPUT_REQUIRED<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 入口流式请求触发远端等待；沿原父任务补齐日期；观察同一远端 Task 的续传与随后 hotel 调用 | 等待保持为等待，不视为故障换候选；续传仍绑定原远端 task/context；两个下游最终成功；无重复新建的远端任务；LLM 脚本不伪造 A2A 状态 | TRAVEL + MODEL + WIRE |
| `FEAT-062.mixed-hotel-static.hosted-addressing` | Trip 动态、Hotel 静态：两 hosted 身份独立寻址<br>F§2 单进程自动注册、§5.1.3；L§2.3、§7.2 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 读取两个 hosted well-known Card；分别直接请求 /a2a/agents/travel-hotel 与 /a2a/agents/travel-hotel-alt；动态分支另核对注册卡与 D4 | 两身份、两卡、两独立 endpoint 与实例定义一致；各自可调用；动态分支按实例发布；静态分支无 A1/A2/A3；不能把两身份计作同 agentId 的两个候选 | TRAVEL + REG + WIRE |
| `FEAT-062.mixed-hotel-static.hosted-isolation` | Trip 动态、Hotel 静态：hosted 任务与上下文隔离<br>F§1 协议保持、§5.1.3；L§2.4、§7.2 | mixed-hotel-static；mainplan 静态仅 hotel、发现开启；trip 注册、hotel 不注册；三 Runtime 同时部署；新 tenant/context/run canary；卡面及注册面就绪；同一 contextId、不同业务 canary<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 在两个 hosted 路径各创建任务；分别查询本实例任务，再用另一实例路径交叉 GetTask；交错继续本实例会话 | 本实例可查可续；交叉读取/续传不得访问另一实例任务或 canary；按既有 task-not-found 语义拒绝，不绑定历史错误数字；注册/发现不破坏隔离 | TRAVEL + WIRE |
| `FEAT-062.static-contract.default-equivalence` | 默认全关与无扩展静态等价<br>F§2、§6；L§3.1、§7.1 | static；显式删除两开关；测试侧在隔离构建目录用相同业务源码和依赖版本分别打包有ext/无ext两形态，保留构建POM与依赖图，不改原checkout或手删fat JAR<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 分别调用真实 mainplan，比较卡、超时、流式与目标落点 | 默认 false；有 ext/无 ext 外部行为等价，注册发现 A1—A3/D1—D4 零流量，不要求内部目录版本相等 | TRAVEL + PACK + WIRE |
| `FEAT-062.static-contract.retry` | 静态首拉失败后按 30s 周期重试<br>F§6 静态冻结；L§4.8 | static；用透明代理暂拒绝 trip 拉卡，hotel 正常<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 记录首拉失败；恢复 trip；观察后续拉卡及委派 | 首次失败不永久遗失目标；30s 周期重试成功后可调；不能随 5s 动态 TTL 拉卡；不要求操作系统调度零误差 | TRAVEL + WIRE |
| `FEAT-062.static-contract.permanent-cache` | 静态成功卡永久持有<br>F§5.1.1、§6；L§4.8 | 四种 Profile 中存在静态条目的目标；卡已拉取成功<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 改变远端卡声明但保留 RPC；跨过至少三轮动态 TTL 及两轮静态重试周期继续调用 | 不重新拉取成功静态卡；调用仍按已缓存参数；静态生命周期不受动态刷新牵引 | TRAVEL + WIRE |
| `FEAT-062.static-contract.card-url-normalization` | 静态 well-known 路径与尾斜杠<br>F§6；L§4.8 | static；URL 有/无尾斜杠；标准 trip 与 hosted hotel 路径<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动拉卡并触发委派，参数化等价 URL 变体 | GET 为去尾斜杠后的 base/.well-known/agent-card.json；保留 hosted 前缀；无双斜杠错误与根实例误路由 | TRAVEL + WIRE |
| `FEAT-062.static-contract.invalid-entry` | 静态必填字段与索引诊断<br>F§6；L§4.8 | 静态条目第 i 项 name/url 缺失、空或空白，其他项合法<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 参数化启动目标进程并收集退出与错误 | 拒绝非法配置，诊断指明 remote-agents[i] 及错误字段；无静默跳过或调用默认目标 | TRAVEL + CONFIG |
| `FEAT-062.static-contract.timeout` | 静态 timeoutSeconds 保真<br>F§6；L§2.4、§4.5 | 静态 trip 配置短超时；RPC 代理延迟响应；hotel 正常<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 委派 trip 并记录超时与有限重试 | 超时配置生效，产生既有 REMOTE_TIMEOUT 域失败；不无限等待，不借动态默认参数覆盖 | TRAVEL + WIRE |
| `FEAT-062.static-contract.streaming-disabled` | 静态 streaming=false 门控<br>F§6；L§4.5、§7.1 单候选等价 | static；父入口流式，trip 静态条目 streaming=false，hotel=true<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 发 SendStreamingMessage 并记录两条远端 method | trip 使用 SendMessage，无合成远端流投影；hotel 仍流式；父入口 SSE 可完成 | TRAVEL + WIRE + SSE |
| `FEAT-062.static-contract.tls` | 静态 TLS 字段保真<br>F§6；L§4.1、§5.4 | static的trip指向测试侧自动生成CA/证书的HTTPS透明代理；按正式tls字段配置信任；真实trip后端和另一下游正常<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 调用 trip 并记录 TLS 握手及真实后端结果 | 按所配信任/校验策略连接成功，实际仍到真实 trip；无静默明文降级；本例不推导动态 TLS 支持 | TRAVEL + TLS + WIRE |
| `FEAT-062.static-contract.address-source` | 静态配置地址与卡声明地址不同时取配置<br>F§6；L§5.1 与§7.1 冲突 | static；配置 URL 指可达真实代理 A，卡 url 声明诱饵 B，双方均可计数<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题2）；自动化 known-gap | 拉卡后委派目标 | RPC 发往配置 A；诱饵 B 的 RPC 数为零；按 Feature §6 确定预期，L2差异仅保留文档冲突记录，不等待产品修复或另行裁决 | TRAVEL + WIRE |
| `FEAT-062.switch.registration-only` | 仅开注册、调用侧仍保持静态<br>F§3、§5.1.3；L§3.1 | static 基础上只给 mainplan 开 registration；其静态 trip/hotel 不变<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动并完成差旅请求，观察本进程 A1/A2 与出站发现流量 | mainplan 注册可见且心跳；自身调用仍静态，零动态发现请求；关闭 discovery 不得关闭 registration | TRAVEL + REG + WIRE |
| `FEAT-062.switch.both-enabled` | 同一进程同时注册与发现<br>F§3；L§3.1 | dynamic 基础上给 mainplan 开 registration<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动并执行双下游业务，读取本进程卡与发现记录 | mainplan 标准卡注册且仍动态发现下游，两开关互不覆盖；动态目录新增 mainplan 不改变既定业务目标 | TRAVEL + REG + WIRE |
| `FEAT-062.switch.invalid-policy` | 非法取址策略快速失败<br>F§3；L§6.4 | 发现开启，call-address-policy 为非 resolve/declared 值<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动目标进程 | 进程在启动看门狗内失败，错误指向非法策略；不得默默按 resolve 启动 | TRAVEL + CONFIG |
| `FEAT-062.switch.ttl-low` | TTL 小于下界拒绝<br>F§3；L§6.1、§7.3 | TTL=999 或负数，ext 在场<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动目标进程 | 拒绝启动并指出 refresh-ttl-ms 越界；不静默截断 | TRAVEL + CONFIG |
| `FEAT-062.switch.ttl-high` | TTL 大于上界拒绝<br>F§3；L§6.1、§7.3 | TTL=30001，ext 在场<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动目标进程 | 拒绝启动并指出合法范围 [1000,30000] | TRAVEL + CONFIG |
| `FEAT-062.switch.ttl-boundaries` | TTL 合法边界与默认周期<br>F§3、§5.1.2；L§6.1、§6.2 | TTL=1000、30000、未配置三个等价合法变体；registry 健康<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动后观察公开发现请求周期并执行上线触发 | 均可服务；未配置采用 5000；对应上线收敛按各自 TTL 判定，不把调用的 LLM 耗时算刷新耗时 | TRAVEL + REG + WIRE |
| `FEAT-062.switch.public-url-missing` | 注册缺声明地址快速失败<br>F§5.1.3、§6；L§6.4 | 标准 trip/hosted hotel 启用注册；通过外置配置清空 public-url，移除环境默认回填<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 分别启动目标进程 | 启动失败且指出 public-url；不得从请求来源或网卡猜地址，也不得对外保持假 ready | TRAVEL + CONFIG |
| `FEAT-062.switch.connection-config-missing` | 接入参数缺失快速失败<br>F§3、§6；L§6.2、§6.4 | 仅开发现或仅开注册；分别缺 base-url/token/tenant-id；移除默认值<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 参数化启动目标进程 | 每个缺失参数均导致启动失败并指明检查项，不输出 token 内容 | TRAVEL + CONFIG |
| `FEAT-062.switch.registration-address-missing` | 注册路由地址必填<br>F§3、§6；L§6.2 | 标准/hosted 注册开启；address 缺失或 port 未提供<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动目标进程 | 拒绝缺失的路由地址配置；不自动探测网卡、不取 public-url 反推 | TRAVEL + CONFIG |
| `FEAT-062.switch.extension-missing` | 发现开启但未部署扩展<br>F§3；L§6.4 | 测试侧隔离构建同业务无ext合法启动包，依赖图确认扩展缺席；discovery=true；其他启动参数合法<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题6）；自动化 known-gap | 启动目标进程 | 启动失败且指引部署扩展；不能悄悄以静态模式继续服务 | TRAVEL + PACK + CONFIG |
| `FEAT-062.catalog.startup-fallback` | 首拉失败不阻断就绪、有静态兜底<br>F§5.1.2；L§3.2 | 混合 Profile；mainplan 仅开发现；代理阻断其发现请求，注册面正常<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动 mainplan，待静态目标可调后恢复发现通道 | 无需首次动态拉取成功即可服务静态目标；恢复后动态目标 TTL 内可委派，mainplan 不重启 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.startup-empty` | 纯动态首拉前未知目标显式失败<br>F§4.1 #9、§5.1.2；L§7.3 | dynamic；真实mainplan启动前阻断首轮发现，无静态/缓存；测试合同入口仅转调进程内正式RemoteAgentCaller，不补目录条目或重写结果<br>runnable；contract；P0<br>本轮执行：PASS；自动化 verified | 确认进程ready后，经合同入口调用指定目标并记录原始RemoteCallOutcome；恢复发现后同进程重试，再经mainplan业务入口复核 | 进程可 ready；未加载目标时结构化不可用且零猜测请求；恢复后同一进程可调，失败不能写成伪成功 | TRAVEL + API-HOST + REG + WIRE |
| `FEAT-062.catalog.disabled-unknown` | 未开发现且无静态目标显式失败<br>F§4.1 #1、§3；L§2.1、§2.4 | discovery=false；无静态目标；测试侧合同入口只转调真实RemoteAgentCaller的公开callOutcome，使用实际装配的空目录<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 由L2明确的RemoteAgentCaller公开合同触发不存在目标，不伪造动态候选 | 既有结构化不可用，零猜测端点；不以模型拒绝未知工具替代Caller错误语义 | API-HOST + WIRE |
| `FEAT-062.catalog.online` | 新下游上线 TTL 内可委派<br>F§4.2、§5.1.2；L§7.2 | dynamic 与两种混合模式的动态目标；mainplan 已运行，目标未注册<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题13）；自动化 known-gap | 启动真实 trip 或 hosted hotel，记录 RDC A1/D1 可见时刻并持续触发可观测目标委派 | 从注册可见到调用方首次命中目标的延迟 ≤TTL；不改配置不重启；hosted 两身份独立进入目录 | TRAVEL + REG + WIRE + CLOCK |
| `FEAT-062.catalog.card-update` | 动态卡参数更新<br>F§2 目录变更、§5.1.1；L§3.3、§5.1 | 动态目标已可调，两个版本仅修改可观察能力/描述 canary，地址保持不变<br>runnable；blackbox；P1<br>本轮执行：ERROR；product confirmed（问题3）；自动化 known-gap | 通过正式注册入口更新该真实目标卡，等待刷新并触发委派 | 新卡能力或目录工具描述在 TTL 内生效，原值不永久滞留；RPC 路由仍取 D4；不要求内部 version 数值 | TRAVEL + REG + MODEL + WIRE |
| `FEAT-062.catalog.card-delete` | 卡删除传播与 404 语义<br>F§2 卡生命周期、§5.1.2；L§3.3 | 动态目标无静态条目；已存在卡；以正式卡注销能力使 D2/D3 确认删除<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 触发目录刷新后再委派目标 | TTL 内不再动态委派该目标，显式失败；区别于卡保留但实例空；若有静态条目，按 404 兜底另外仲裁，不把卡删除等同空候选 | TRAVEL + REG + MODEL + WIRE |
| `FEAT-062.catalog.pagination` | D3 多页全量发现<br>F§2 启动全量；L§4.8 | 同 tenant 用正式 API 发布超过实际 pageSize 的卡与真实可调 trip 排在末页；保留游标原值<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动 mainplan；在末页目标触发委派 | 完整遍历 continuationToken，末页真实目标可调；无漏页重复遮蔽；不把预置数据当独立业务 SUT | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.precedence` | 同 agentId 动态遮蔽静态<br>F§4.1 #4、§5.1.1；L§7.2 | 两种混合 Profile 上临时给动态目标加同名静态诱饵；动态真实候选非空<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 完成一次委派并计数卡面、D4、两条 RPC 路径 | 卡面与候选整体取动态；静态诱饵零 RPC；不得把另一 agentId 的静态目标一并遮蔽 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.empty-no-fallback` | 升级窗口 200 空候选不回静态<br>F§4.1 #5、§5.1.5；L§7.2 | 动态与同名静态均存在；停掉或注销所有动态实例，卡保留；静态诱饵可用<br>runnable；blackbox；P0<br>本轮执行：ERROR；product confirmed（问题5）；自动化 known-gap | 确认 D1=200 空数组、D2 可读，等目录收敛后委派 | 无可用候选结构化失败，静态诱饵零 RPC；父任务不悬挂；不得假称酒店已完成 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.not-found-fallback` | D1 404 时静态兜底<br>F§4.1 #6；L§7.2 | 两种混合 Profile 静态目标从未注册，D1=404；动态另一目标正常<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 同一父任务分别请求两目标 | 静态目标成功走配置，动态目标仍走 D4；逐条目仲裁，不能以整份目录的动态状态决定全部目标 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.cached-outage` | RDC 不可用时缓存动态条目继续服务<br>F§4.1 #7、§5.1.5；L§3.2 | 动态目标已有有效缓存，实际 Runtime 仍健康；仅阻断 mainplan 到 RDC<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 跨多轮刷新委派动态目标 | 保留缓存目标并继续直连真实叶子；不清空目录、不回静态诱饵；记录故障窗口不越出已证实缓存有效期 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.uncached-static-outage` | RDC 不可用、无动态缓存时静态兜底<br>F§4.1 #8；L§3.2 | mainplan 新进程无动态缓存、有静态目标；RDC 发现链路不可达，叶子可达<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动并请求静态目标 | 静态兜底成功，不需等待 RDC；不复用上一进程缓存制造假阳性 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.uncached-no-route` | RDC 不可用且无任何本地信息<br>F§4.1 #9；L§7.3 | 目标曾在真实RDC发布；mainplan新进程无缓存/静态且RDC被隔离；测试侧合同入口仅转调进程内真实RemoteAgentCaller<br>runnable；contract；P0<br>本轮执行：PASS；自动化 verified | 经正式Caller合同调用指定目标，收集原始RemoteCallOutcome、RDC故障和实际RPC计数 | 既有结构化不可用；零猜测 URL、零诱饵 RPC；不能把模型没调用工具算此负向通过 | TRAVEL + API-HOST + REG + WIRE |
| `FEAT-062.catalog.recovery` | 注册发现服务恢复后的目录对齐<br>F§5.1.2、§5.1.5；L§7.2 | 故障期间缓存可用；服务端目标卡或候选已变化<br>runnable；blackbox；P1<br>本轮执行：ERROR；product confirmed（问题3）；自动化 known-gap | 恢复发现通道，持续观察调用落点 | 下一有效刷新按新快照对齐，不永久使用故障前目录；新调用不再命中已摘除目标 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.resolve-address` | 动态调用只取 D4 物理地址<br>F§5.1.1、§5.1.3、§6；L§7.1 | resolve 默认/显式两变体；卡声明地址与路由地址分别为诱饵和真实实例，业务卡能力相同<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 委派目标并记录 D4 返回与实际 RPC | RPC URL 等于 D4 endpointUrl，卡面诱饵零请求；同源注入的常规变体卡 URL 与 D4 一致 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.resolve-cache` | 相同 routeHandle 不重复解析<br>F§3 取址；L§4.8、§5.4 | 动态缓存已暖；RDC 实际 D1 的句柄在观察窗口保持不变<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 连续三轮成功刷新与多次调用，记录 D4 计数 | 该句柄暖缓存后 D4 增量为零，调用继续正常；句柄若已轮换，本试次不适用，不能计失败或假通过 | TRAVEL + REG + WIRE |
| `FEAT-062.catalog.handle-refresh` | 句柄失效后重新发现与解析<br>F§5.1.2、§3 继承048；L§4.8 | 动态目标已有缓存；正式重注册/路由变更使旧 D4=404、新 D1 有效<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 继续刷新并发起新调用 | 消费方重新取得 D1 句柄并 D4 解析新地址，TTL 内恢复；不能无限循环旧句柄；恢复成功前错误仍保留 | TRAVEL + REG + WIRE |
| `FEAT-062.registration.standard-card` | 标准进程单卡注册保真<br>F§2、§5.1.3；L§2.3 | trip 标准进程仅注册开启；hosted hotel 同时部署<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动 trip；比较 well-known 卡与 RDC D1/D2 rawJson，D4 后调用 | 单卡单 endpoint /a2a；agentId=标准卡 name；卡语义保真，无额外 hosted 影子条目；实际可调 | TRAVEL + REG + WIRE |
| `FEAT-062.registration.hosted-cards` | hosted 多卡多 endpoint 注册<br>F§2、§5.1.3；L§2.3、§7.2 | hotel 两实例仅注册开启；trip 同时部署<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动 hotel；核对逐实例 A1、D1/D2、D4 并分别调用 | travel-hotel 与 travel-hotel-alt 各一张定义卡、各一个独立路径；不多注册根别名；无部分清单被当作完成 | TRAVEL + REG + WIRE |
| `FEAT-062.registration.upsert` | 同键重注册幂等<br>F§5.1.3、§6；L§4.9 | 标准与 hosted 两种形态；同 tenant/agent/address/port，正式 API 可见现有注册<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 在运行期按原注册载荷 upsert，或通过心跳自愈触发同键 A1 | 同键 instanceId 稳定、候选不重复，不覆盖其他进程；instanceId 只作不透明相等比较，不重算散列 | TRAVEL + REG |
| `FEAT-062.registration.heartbeat` | 按服务端响应持续心跳保活<br>F§5.1.3、§6 指标；L§4.9 | A1 响应已有 intervalSeconds/ttlSeconds；两注册形态<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 跨越两个服务端 ttl 窗口观察 A2 与候选并继续调用 | 按下发节奏保活，健康实例不被错误摘除；不以固定本地常数替代服务端窗口；记录误摘实例次数/应在线实例采样次数，并另记健康观察时长 | TRAVEL + REG + WIRE + CLOCK |
| `FEAT-062.registration.heartbeat-not-found` | 心跳 404 自动重注册<br>F§5.1.3；L§4.9、§7.2 | 实例运行中，从 RDC 正式注销指定 instanceId 使 A2 返回 INSTANCE_NOT_FOUND<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 观察后续 A2→A1→A2 与候选恢复；标准/hosted 形态参数化 | 自动同键 A1，自愈后仅一个候选且 instanceId 稳定；其他实例保留；不手工重启 runtime | TRAVEL + REG + WIRE |
| `FEAT-062.registration.graceful-stop` | 优雅停机逐实例注销<br>F§4.2、§5.1.3；L§7.2 | dynamic/两混合模式中注册目标；WSL 中可 SIGTERM 的进程，记录退出前清单<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | SIGTERM 真实 trip 或 hotel；观察 A3、D1、调用方新请求落点 | 仅本进程全部实例逐一 A3；注册侧摘除后 ≤TTL 新调用不再选它；hosted 两身份均注销，其他进程不被误删 | TRAVEL + REG + WIRE + CLOCK |
| `FEAT-062.registration.crash-stop` | 进程崩溃摘除与目录收敛<br>F§4.2、§5.1.2；L§7.2 | 动态目标已注册；记录服务端摘除窗口；另一候选可用<br>runnable；blackbox；P0<br>本轮执行：ERROR；product confirmed（问题5）；自动化 known-gap | kill -9 真实标准/hosted 进程，无 A3；观察 RDC 与调用侧 | 服务端在其合同窗口摘除；从 D1 确认摘除再计调用侧 ≤TTL；故障窗口走 failover；不能把 stop-demo.ps1 硬停止当优雅注销 | TRAVEL + REG + WIRE + CLOCK |
| `FEAT-062.registration.no-cross-delete` | 一进程下线不误删同目标另一副本<br>F§5.1.3；L§7.2 | 相同 agentId 两个真实进程不同端口；或两组 hotel hosted 进程<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 优雅停掉其中一个进程，连续 D1/D4/委派 | 只移除其 instanceId；存活副本仍可寻址可调用，未收到另一进程的 A3；单独注销身份不会删兄弟身份 | TRAVEL + REPLICA + REG + WIRE |
| `FEAT-062.registration.startup-unreachable` | 注册启动期 RDC 不可达快速失败<br>F§5.1.3、§5.1.5；L§6.4 | 标准/hosted registration=true；只对待启动进程阻断 RDC<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动目标进程并记录退出/ready/诊断 | 在启动看门狗内失败，有网络检查指引；不持续对外 ready；区别于仅开发现允许静态兜底 | TRAVEL + CONFIG + WIRE |
| `FEAT-062.registration.runtime-outage` | 运行期注册通道断连及重放<br>F§5.1.3、§5.1.5；L§4.9 | 实例已启动；只隔离注册/心跳通道，调用方与叶子网络可达<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 跨过服务端摘除窗口后恢复通道 | 运行期不冒充保活；服务端摘除可见；自动恢复注册/心跳并重新可调，不依赖人工重启 | TRAVEL + REG + WIRE |
| `FEAT-062.routing.scale-out` | 同 agentId 跨进程扩容<br>F§2、§4.2；L§3.4 | dynamic/两混合模式的动态目标原有一个候选<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 启动同 jar 同 agentId 的第二进程，不同 port/工作目录；持续新调用 | 候选集在 TTL 内扩大为二，两真实副本均有首发流量；不改 mainplan，不将 hotel-alt 作为第二候选 | TRAVEL + REPLICA + REG + WIRE |
| `FEAT-062.routing.scale-in` | 同 agentId 跨进程缩容<br>F§4.2、§5.2；L§7.2 | 两个候选均可调，另有已进入健康副本的在途任务<br>runnable；blackbox；P0<br>本轮执行：ERROR；product confirmed（问题5）；自动化 known-gap | 优雅下线一副本，继续发新请求并观察健康副本在途任务 | 候选集 TTL 内收缩，新调用不再选被摘实例；健康副本在途任务继续完成；不要求被停进程任务自动迁移 | TRAVEL + REPLICA + REG + WIRE |
| `FEAT-062.routing.equal-weight` | 等权首发分布<br>F§2 多实例加权；L§3.4、§7.1 | 两个真实副本同 agentId 等权、均健康，确定性模型每父任务只委派一次<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 固定样本量 400 次相同业务输入的独立新调用（新task/context），记录实际首发 endpoint | 精确二项检验对 p=0.5 的双侧显著性阈值 0.001；无故障重试样本污染；结果不确定只记录一次预设统计结论，不反复抽到通过 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.weighted` | 3:1 权重首发分布<br>F§2、§4.2；L§3.4 | 同 agentId 两副本；通过正式 A1 配置 weight=300:100，D1 确认未被心跳改写<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 固定 400 次相同业务输入的独立新调用（新task/context），统计首发落点 | 以 p=0.75 作精确二项检验，双侧阈值 0.001；只统计首发；如 fixture 无法持续维持公开 weight 输入，本例阻塞，不能退化成任一副本被调用即通过 | TRAVEL + REPLICA + MODEL + REG + WIRE |
| `FEAT-062.routing.invalid-weight` | 权重缺省或非正值的等权降级<br>F§2；L§3.4、§5.4 | 测试侧正式RemoteCatalogSource SPI仅提供缺省/0/负权重候选输入；卡及地址指向真实trip双副本；真实Runtime负责消费、选路和调用<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 分别固定候选输入变体，每变体400次独立新调用，记录首发落点，使用预设双侧精确二项检验α=0.001 | 按L2等权降级，首发分布以p=0.5判定；不以A1拒绝非法注册替代消费端验证；仅形成候选消费合同结论，不证明RDC能产生该输入 | API-HOST + RDC-INPUT + TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.connection-refused` | 首事件前连接拒绝 failover<br>F§5.1.4；L§3.4、§4.5 | 两个有效动态候选；保留 D1 候选，对其中一副本 RPC 代理拒连<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 发新委派在最多20个独立新调用内找到确实首发故障候选的试次，否则记INCONCLUSIVE，记录该试次完整尝试链 | 自动切至健康候选，父任务成功且完整双下游结果；未首发故障候选的试次只作前置，不计 failover 通过 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.request-timeout` | 首事件前超时 failover<br>F§5.1.4；L§3.4 | 两个动态候选；故障代理保持连接但在首事件前超时<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题11）；自动化 known-gap | 触发实际命中故障候选的委派，观察后续健康副本 | 超时后切下一候选并成功，父任务不失败；失败端任务不跨实例迁移，不要求恰好固定重试间隔 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.all-exhausted` | 候选全部耗尽结构化失败<br>F§5.1.4、§5.1.5；L§7.3 | 两个候选仍在目录，但两个 RPC 通道均不可达<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 强制真实委派并观察有限尝试与父任务收敛 | 候选耗尽按既有失败域投影，all-settled 不悬挂；最终回答不能伪称目标已完成；不凭父 COMPLETED 掩盖成员失败 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.protocol-failure` | 协议错误不切换候选<br>F§5.1.4 协议不变；L§3.4 | 故障候选代理返回不合法 A2A/JSON-RPC 响应；另一候选正常<br>runnable；blackbox；P1<br>本轮执行：FAIL；product confirmed（问题8）；自动化 known-gap | 记录实际命中故障候选的委派 | 按协议错误收敛；无第二候选 RPC；不把可读 HTTP 200 一概视作业务成功 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.business-failure` | 业务失败不切换候选<br>F§5.1.4；L§3.4 | 真实mainplan Caller与双候选；受控A2A依赖在选中候选返回协议合法的FAILED/REJECTED业务终态，另一真实副本正常；不把空库存成功答复当失败<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 经API-HOST只转调真实Caller，触发确实命中失败候选的委派；分别给FAILED/REJECTED输入并记录尝试链与原始结果 | 原业务失败语义保留，无第二候选RPC，不重试为成功；本例验证调用方处理已收到的业务失败，不声称原始demo能自行生成该终态 | TRAVEL + API-HOST + REPLICA + WIRE |
| `FEAT-062.routing.input-required` | 等待输入不触发候选切换<br>F§5.1.4；L§3.4 | 两个真实副本；确定性模型使首选下游返回 INPUT_REQUIRED<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 触发等待并观察其他候选请求数 | 父任务按原协议等待，其他候选 RPC 为零；输入不足不归类连接失败 | TRAVEL + REPLICA + MODEL + WIRE |
| `FEAT-062.routing.first-event-lock` | 远端首事件后断流不得换实例<br>F§5.1.4、§5.2；L§3.4 | 两个真实候选；代理转发选中实例首个真实 Task/状态事件后截断 SSE<br>runnable；blackbox；P0<br>本轮执行：PASS；自动化 verified | 流式委派并捕获中断前后的两候选请求 | 首事件后不切另一副本、不重复建新 Task；原调用按既有中断/失败语义收敛，部分 artifact 不伪作成功 | TRAVEL + REPLICA + MODEL + WIRE + SSE |
| `FEAT-062.isolation.namespace-concurrent` | 同 RDC 跨 namespace 同名 Agent 并发<br>F§3 租户与048契约、§5.1.3；L§4.8 | A/B 两 namespace 同名 trip/hotel/hotel-alt，不同端口和 canary；共享同一 RDC<br>runnable；blackbox；P0<br>本轮执行：ERROR；product confirmed（问题10）；自动化 known-gap | 同时运行两套完整链，交错注册、D1/D2/D4、同步与 SSE 调用 | 每 tenant 候选与解析地址始终属于本 tenant；句柄可在本 tenant 成功解析；模型回灌不出现他域 canary；不能重启 RDC 或串行化来规避 | TRAVEL x2 + REG + WIRE |
| `FEAT-062.isolation.namespace-churn` | 另一 namespace 上下线不污染本域<br>F§3 继承租户隔离、§5.1.2；L§4.8 | A 完整链持续请求；B 同名 Agent 注册/注销/重注册<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题10）；自动化 known-gap | B 连续变更，A 同时做 D1/D4、业务与心跳观察 | A instanceId/路由/卡不被 B 端口替换；A 无由他域变更引起的 stale handle 或 404 自愈风暴 | TRAVEL x2 + REG + WIRE |
| `FEAT-062.isolation.namespace-resume` | 跨 namespace 并发时恢复仍完整<br>F§3、§5.1.4；L§2.4、§4.8 | A 主任务已 INPUT_REQUIRED；B 同名实例在线并持续注册；各自 canary<br>runnable；blackbox；P0<br>本轮执行：FAIL；product confirmed（问题10）；自动化 known-gap | 保持同一 RDC，不停 B，续传 A 原 task/context | A 续传 trip/hotel 均完成，路由和结果均属 A；即使父 COMPLETED，任一下游 REMOTE_UNAVAILABLE 仍失败 | TRAVEL x2 + REG + MODEL + WIRE |
| `FEAT-062.isolation.mode-transition` | 同 namespace 切换模式的残留卡语义<br>F§4.1 #5/#6、§2 卡生命周期；L§7.2 | 先 dynamic 注册，随后叶子关闭注册并切混合模式，保留同 tenant 卡；另备全新 tenant 对照<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 确认旧 tenant D1=200 空、D2 卡仍在；再启动混合 mainplan | 旧域按空候选不回静态，不把残留卡当 404；全新域 404 静态可用；不把预清理所有卡后的成功当作残留验证 | TRAVEL + REG + WIRE |
| `FEAT-062.auth.headers` | 注册与发现认证头、租户头保真<br>F§3 接入参数继承048；L§4.8、§4.9 | 两混合/dynamic 形态；入口外部 token 临时注入；透明记录器只存头存在性与 token 匹配布尔值<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 观察 A1/A2/A3、D1/D3/D4 | 都带正确 Bearer 与 X-Tenant-ID，不把 tenant 放 URL/body；日志/证据无明文 token；不因两个开关使用不同租户 | TRAVEL + REG + WIRE |
| `FEAT-062.auth.encrypted-token` | token 密文配置接入<br>F§3、§6 接入参数；L§6.4、§7.1 | 真实RDC使用本轮临时token；测试侧生成对应密文，经公开凭据解密SPI在测试宿主装配本地测试解密器；Runtime/ext配置密文并保留真实注册发现组件<br>runnable；spi-integration；P1<br>本轮执行：FAIL；product confirmed（问题4）；自动化 known-gap | 启动注册/发现并调用真实下游；记录解密SPI被调用的脱敏事实、认证匹配布尔值和日志泄漏检查，不输出密文/明文 | 产品通过支持的解密接入取得token，真实RDC认证成功；日志/证据无明文；若承诺的解密接线缺失则正常FAIL，不用明文覆盖或测试侧预解密绕过；不验收企业KMS安全性 | TRAVEL + SPI-HOST + CREDENTIAL + REG + CONFIG + WIRE |
| `FEAT-062.auth.unauthenticated` | 注册鉴权 401 拒绝<br>F§3、§5.1.3；L§7.3 | 标准/hosted 注册启用；错误 token，真实 RDC 返回401<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动待注册进程 | 明确鉴权失败，启动失败且无有效新增实例；不伪装网络抖动持续带病服务 | TRAVEL + REG + CONFIG |
| `FEAT-062.auth.tenant-mismatch` | 注册租户越界 403 拒绝<br>F§3、§5.1.3；L§7.3 | 测试侧给真实RDC配置caller-a→tenant-A绑定；待注册进程使用tenant-B；请求适配器仅增加X-Caller-Ref: caller-a以触发真实403，其他请求透传<br>runnable；blackbox；P1<br>本轮执行：FAIL；product confirmed（问题9）；自动化 known-gap | 启动待注册进程；保留真实RDC的403 TENANT_MISMATCH响应、原始tenant头与适配器增加头的证据，并查询两域注册结果 | 产品明确租户错误，启动失败且无他域新增实例；额外caller头只作依赖错误触发，不能声称Runtime原生会发送该头；两个合法tenant不同本身不是越界 | TRAVEL + REG + CONFIG + WIRE |
| `FEAT-062.auth.registration-disabled` | 注册 410 的路径指引<br>F§3 继承048；L§7.3 | 正式 RDC 注册路径禁用，已确认 A1 返回410<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 启动注册启用的真实标准/hosted 进程 | 不伪装成功，诊断包含注册路径禁用与修正指引，不能陷入无界重注册 | TRAVEL + REG + CONFIG |
| `FEAT-062.auth.rate-limited` | 运行期 429 退避恢复<br>F§3 继承048、§5.1.5；L§7.3 | 实例已注册；仅用外部代理短暂向 A2 返回429，随后透传真实 RDC<br>runnable；blackbox；P1<br>本轮执行：PASS；自动化 verified | 记录错误时间戳、后续请求与候选 | 有限退避，不忙循环；恢复后真实心跳成功，不伪造保活；精确退避系数未承诺则不写死 | TRAVEL + REG + WIRE |
| `FEAT-062.hosted-catalog.inherit-add` | 全局新增传播到 hosted 继承目录<br>F§2 变更传播、§5.1.1；L§3.3 | 测试侧以demo的hosted身份/配置建立隔离扩展宿主，通过正式远端工具装配及agents.{agentId}.remote-agents配置使两实例成为调用方；保留真实Runtime目录继承与Caller<br>runnable；spi-integration；P1<br>本轮执行：PASS；自动化 verified | 向全局目录新增真实 trip 并通过两 hosted 路径委派 | TTL 内两实例均可调用新增全局目标；本地条目不丢；不能只看 mainplan 调用证明 hosted 继承 | TRAVEL + HOSTED-CALLER + SPI-HOST + REG + WIRE |
| `FEAT-062.hosted-catalog.inherit-update` | 全局更新传播到 hosted 继承目录<br>F§2、§5.1.2；L§3.3 | 同一正式hosted调用扩展宿主；两身份有继承目标及差异化局部条目，真实trip为下游<br>runnable；spi-integration；P1<br>本轮执行：FAIL；product confirmed（问题12）；自动化 known-gap | 更新继承目标真实卡/候选，分别经 hosted 触发 | TTL 内两实例均使用更新卡和候选，本地条目保持原值 | TRAVEL + HOSTED-CALLER + REG + WIRE |
| `FEAT-062.hosted-catalog.inherit-delete` | 全局删除传播到 hosted 继承目录<br>F§2、§5.1.2；L§3.3 | 同一正式hosted调用扩展宿主；两身份继承真实trip目标，无同名局部配置<br>runnable；spi-integration；P1<br>本轮执行：FAIL；product confirmed（问题12）；自动化 known-gap | 从正式目录删除该目标，再经两 hosted 触发 | TTL 内两实例均不再向旧地址委派；结构化失败；不能只验证全局删卡 | TRAVEL + HOSTED-CALLER + REG + WIRE |
| `FEAT-062.hosted-catalog.local-priority` | 实例局部静态优先于全局动态<br>F§5.1.1；L§3.3 | 同一正式hosted调用扩展宿主；A局部同名静态目标，B仅继承全局动态目标；真实落点/canary不同<br>runnable；spi-integration；P1<br>本轮执行：PASS；自动化 verified | 分别经两 hosted 身份委派，再改变全局目标 | A 始终走局部配置，B 跟随全局动态；A 局部不被全局仲裁覆盖，且不泄漏至 B | TRAVEL + HOSTED-CALLER + REG + WIRE |
| `FEAT-062.extension.refresh-exception` | 目录源意外异常保留旧目录<br>F§2、§5.1.2；L§2.2、§4.2 | 测试侧实现文档公开RemoteCatalogSource，只控制load输入与一次抛异常；真实刷新器、目录和Caller已加载成功快照，目标仍为真实trip<br>runnable；spi-integration；P1<br>本轮执行：ERROR；product confirmed（问题7）；自动化 known-gap | 跨过一次异常周期继续调用，再令源恢复且版本变化 | 旧目标继续可调、连续失败读数递增；恢复后下一轮对齐；与真实 RDC 503 合法降级分别统计 | SPI-HOST + TRAVEL + WIRE |
| `FEAT-062.extension.listener-recovery` | 目录事件消费者异常不阻断后续刷新<br>F§5.1.2；L§3.3 | 测试侧按文档公开目录变更事件装配一次抛异常的消费者；真实发布者、hosted继承消费者与刷新器保持原装<br>runnable；spi-integration；P1<br>本轮执行：PASS；自动化 verified | 发布新增目录，触发一次消费异常，再恢复后继续刷新 | 目录主流程继续，下一轮消费者自愈；不把变更 version 未变化导致永不重发视作通过 | SPI-HOST + TRAVEL |
| `FEAT-062.extension.empty-registration` | 注册开启但实例清单为空快速失败<br>F§3 注册读取口；L§6.4 | 测试侧按正式InstanceCatalog SPI装配返回空清单的提供者；保留真实注册组件与Spring启动生命周期，其他配置合法<br>runnable；spi-integration；P1<br>本轮执行：PASS；自动化 verified | 开启registration并启动宿主 | 启动失败并指明无实例可注册；零伪成功注册；标准/hosted正常Demo不天然产生空清单，不能用其正常启动抵扣本例 | SPI-HOST + CONFIG |
| `FEAT-062.contract.instance-readonly` | 注册实例清单只读保真<br>F§2 注册读取口、§3；L§2.3 | 完整真实demo运行；测试侧同进程合同宿主只转调正式InstanceCatalog读取口，保留真实标准/hosted提供者<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 重复读清单，对比标准/hosted 外部卡及调用前后事实 | 标准一项、hosted 两项；字段与定义及公开卡一致；读取不新增注册、不改变托管实例集合或默认目标 | API-HOST + TRAVEL |
| `FEAT-062.contract.instance-readiness` | hosted 未就绪不返回部分清单<br>F§2 注册清单；L§2.3 | 测试侧合同宿主在真实hosted目录发布前设置生命周期屏障并调用真实InstanceCatalog；标准真实提供者作对照，不替换清单返回<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 启动阶段及发布后调用正式读取口 | hosted 发布前503 AGENT_NOT_READY而非部分列表，发布后完整；标准卡就绪即一项，无额外 hosted 503 窗口 | API-HOST |
| `FEAT-062.contract.architecture` | Runtime 注册中心依赖边界守护<br>F§6 仓归属；L§7.1 | 冻结 Runtime 正式主 jar 与 POM，排除测试依赖，依赖图已解析<br>runnable；contract；P1<br>本轮执行：PASS；自动化 verified | 静态检查主制品引用与生产 POM 依赖图 | 零统一 API 客户端/注册 SDK/ext 反向依赖；不把 HTTP 业务通过抵扣本合同；不检查线程/私有缓存实现 | PACK |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| TRAVEL：mainplan、trip、hotel | 真实 SUT | 指定 multi-react-travel-demo 的三个正式可执行 JAR；mainplan 为调用方，trip 为标准被注册方，hotel 为两身份 hosted 被注册方。测试专用覆盖配置在临时目录，不写入公共 example。 |
| REG：RDC + Nacos | 真实依赖 | RDC 0.1.3，backend=nacos；Nacos 3.2.4、AI开启、独立namespace。统一 API A1/A2/A3 与 D1/D2/D3/D4；不依赖 gateway/Bus/关系库。 |
| WIRE / SSE | 观察与故障 Fixture | 透明代理记录实际URL、method、task/context/correlation及状态；截断、延迟、拒连只制造外部故障，不执行被测路由。SSE保留原帧及顺序；凭据只存存在性和比较结果。 |
| MODEL | 输入 Fixture | 正式 ReActAgent 仍执行真实工具；脚本 OpenAI 兼容模型只控制 tool-call 输入/时机，不直接生成 A2A Task 或注册候选。主业务另以真实 LLM 复核四种 Profile；两种模型证据分别统计。 |
| REPLICA | 真实进程拓扑 Fixture | 用相同 trip 或 hotel JAR 启第二进程，同agentId不同端口；各有工作目录、日志、LLM会话。跨候选恢复不作为通过要求。 |
| CLOCK | 测量 Fixture | 单调时钟、100ms有界轮询、请求时间区间；RDC与调用侧两段时延分开，保留原始采样，不放宽TTL。 |
| CONFIG / PACK | 启动与制品 Fixture | 外置临时配置启动、看门狗、退出/ready判据；测试侧在隔离目录用相同业务源码生成有ext/无ext构建POM与依赖图，保留原checkout；不手删改正式fat JAR。静态架构检查独立计合同证据。 |
| TLS | 网络 Fixture | 自动生成一次性测试CA/证书与HTTPS透明代理；使用已有静态tls合同，不扩展动态TLS要求。 |
| HOSTED-CALLER | 公开扩展集成 Fixture | 测试侧基于demo的两hosted身份，在隔离宿主通过正式远端工具装配及局部配置增加调用入口；真实Runtime执行继承、局部优先和调用。四行单列spi-integration，只证明Runtime通用能力，不宣称原始hotel叶子已接入远端工具；完整demo四Profile业务组另行保留。 |
| API-HOST / SPI-HOST | 合同 / SPI 集成 Fixture | 由测试侧建立隔离宿主：API入口只转调文档明确的RemoteAgentCaller、InstanceCatalog并返回原始结果；SPI仅提供目录输入、空清单或单次事件故障。真实刷新、仲裁、Caller、清单提供者及注册生命周期按各场景保留，不重写结果。正式合同若缺失，记录产品交付疑似失败；测试宿主装配错误先修正，不能当产品缺陷。 |
| RDC-INPUT | 合同输入 Fixture | 经正式RemoteCatalogSource候选模型提供缺省/非正权重，目标仍为真实副本；只验证调用侧消费，不验收RDC接受非法注册。 |
| CREDENTIAL | 公开凭据解密集成 Fixture | 测试侧生成临时token及密文，通过公开凭据解密扩展装配本地测试解密器，真实registry客户端负责调用该接入并认证；先核对registry的正式密文格式/场景绑定，不把CredentialDecryptor与RDC服务端TokenCipher混同。无需企业KMS；只证明产品调用支持的解密接入，不证明企业密钥管理强度。 |

## 7. 关键链路断言

- **成功闭环**：完整输入必须有同一 parentTaskId 对应的 trip 与 hotel 实际请求、完成/回灌证据和最终库存标记。模型仅生成“已查询”或父任务 COMPLETED 都不算闭环。负向失败可由父任务完成后呈现远端错误，不强求父状态必须 FAILED；必须保留成员失败且不伪称成功。
- **分辨入口与远端流式**：分别记录入口 SSE media type、远端 SendStreamingMessage/SendMessage 与远端投影。两个支持 streaming 的目标在四种 Profile 下保持协议能力；动态目录把 streaming 硬关闭的历史现象不作为允许预期。静态 streaming=false 是单独的合法门控用例。不开启自定义投影 Hook，不断言固定帧数、chunk 文案或全套 FEAT086 Hook 行为。
- **仲裁逐条目**：D1非空→动态整体遮蔽；D1空数组→空候选不回静态；D1 404→静态；不可用→该条目有效缓存/静态/显式失败。用真实落点和诱饵零请求证明来源，不能只看卡面或父答复。
- **取址**：动态只认 D4 endpointUrl，卡面地址仅传协议能力；静态按 Feature 取配置地址，差异地址直接按该预期执行。routeHandle 和 instanceId 均为不透明值，只比对服务端返回与路由结果，不解码或重算 ID。
- **时间**：正常 TTL=5000ms，合法边界1000/30000ms。上线从A1成功响应区间开始记录总时延，D1首次可见另记，不能等待D1可见后抹去前段时延；下线 t0 取 A3 完成/D1 首次确认已摘除的区间，进程 kill 到 RDC 摘除另记服务端窗口。t1 取调用侧已接受有效目标委派/最后一次旧落点的日志或透明记录时间，不取最终 LLM 响应时间。轮询100ms，用单调时钟、同观察器记录前后区间；测量区间跨 TTL 边界则 INCONCLUSIVE 并改善探测，不把 TTL 放宽为两轮或加固定宽限。没有调用侧目录 HTTP 入口时，以低开销脚本模型触发真实委派、模型工具列表/公开诊断事实补充观察，不能以 D1/D4 自身就绪冒充调用侧已经收敛。
- **路由统计**：只统计首发，预设 n=400、双侧精确二项检验 α=0.001；二项概率0.5/0.75分别独立判定。权重≤0/缺省单列消费端合同，不用两个不同 hotel agentId 伪装均衡。统计不承诺每小组均匀，也不反复抽样至通过。
- **故障切换**：需要证明确实首先选择了故障候选。拒连与首事件前超时可切；协议/业务失败、INPUT_REQUIRED 不切；首个远端事件后锁定，部分结果不能成功化。有限候选耗尽且 all-settled 收敛；不要求跨实例续跑或没有共享 TaskStore 的随机副本恢复。
- **注册与隔离**：注册组件只发布本进程清单；upsert/404自愈不增重复候选；A3只删自身。两 namespace 在同一 RDC 同时保留并交错变更；D1/D4、实际落点、卡/结果 canary 均隔离。硬停、重启RDC、切换为串行仅作环境恢复动作，不是隔离测试的验收条件。
- **错误与不应断言**：有效前置下违反文档合同记FAIL；产品缺少本期承诺能力也保持正常预期并作为产品疑似失败调查，不以修复完成为执行门禁。测试夹具/打包错误记ERROR，未真正触发场景记INCONCLUSIVE，均不计PASS或产品缺陷；不能捕获任意异常充作负向成功。不读取数据库、缓存key、内部线程、私有类或反射；不绑定自然语言、追问次数、任务轮数、私有重试线程或历史数字错误码。

## 8. 执行策略

PR247/PR982复验的测试侧修正：卡面变更输入同时更新卡总描述和所有skill描述，保持URL、实例、权重与超时不变，从实际展示的工具描述验证卡面传播。上线采样看门狗为45秒，验收TTL仍为5000ms。协议错误抽样前须确认调用方公开目录已包含两个候选，不能只以RDC D1数量作为调用方就绪；跨namespace上下线用例对A域的即时路由和业务断言保持不变，B域重启后先在25秒看门狗内完成自身D4就绪再继续下一轮，B域上线时效由独立上线用例判定。原始失败、输入修正和补充复验结果分别保留，不回写历史执行统计。

设计状态 `designed-runnable`；共 **102** 个独立场景；测试类型：blackbox 86、contract 8、spi-integration 8；可执行性：runnable 102。102项均已实现且完成分组精确执行，无deferred占位；runnable仅表示执行路线已设计，不能把编译成功或启动就绪计为用例通过。设计阶段没有需要用户先确认/准备的阻塞项；测试侧准备完成后正常执行，产品能力缺失不得作为预先跳过理由。
- Smoke：`FEAT-062.static.sync`、`FEAT-062.dynamic.sync`、`FEAT-062.mixed-trip-static.sync`、`FEAT-062.mixed-hotel-static.sync`、`FEAT-062.dynamic.stream`、`FEAT-062.registration.standard-card`、`FEAT-062.registration.hosted-cards`、`FEAT-062.catalog.empty-no-fallback`、`FEAT-062.routing.connection-refused`、`FEAT-062.isolation.namespace-concurrent`。Smoke不替代四种配置的完整六项旅程。
- Full suite：矩阵全部ID为设计总集；102行均保留正常合同预期；完成测试侧Fixture后执行，不以产品能力尚未实现/修复为跳过理由。P0是矩阵中标明P0的全部行；存在任何P0未执行/未通过，发布结论必须明确不完整。P1同样不得悄悄删除。
- 分组顺序：先四种Profile各自隔离运行，再做共性仲裁/生命周期/路由；最后专门让两个namespace同名Agent同时运行。普通组可用独立RDC避免污染；隔离组必须共享同一RDC，不以逐场景运行来豁免。
- 配置与数据：每次生成runId、tenant/context/message/correlation和库存canary；普通用例自动新建namespace，残留卡用例有意复用同namespace并保留事实。完整输入使用库存资源实际存在的城市、日期区间和预算，由Fixture生成；不能把已过期的示例日期或固定自然语言作Oracle。
- 模型：四种配置的主链路用真实LLM复核；统计、故障与精确时序用确定性模型输入减少LLM随机性，核心Agent/Runtime/工具仍真实。模型未触发要求的委派是前置未建立/INCONCLUSIVE，不能当负向通过；实际完成的委派违反合同则FAIL。
- 看门狗：启动120s、业务单任务300s作为测试看门狗，不是产品SLA；TTL按合法配置原值评判。未观察到期望终态时保留最后Task、请求与日志；既有异步回调模式本轮不主动开启。
- 清理：先移除故障注入，再终止本轮进程并核对PID/启动时间；动态优雅路径使用SIGTERM，崩溃使用SIGKILL分别记录。确认本轮端口无监听、注册实例按服务端窗口消失，删除本轮临时命名空间/目录/容器；不碰已有用户Nacos、数据卷或无关进程。隔离/残留用例结束前保留所需卡面，不预清理破坏Given。
- 证据：配置合并结果、分支/HEAD/脏状态、源码与JAR/POM的SHA256、脱敏A1—A3/D1—D4、实际RPC/SSE、Task查询、父子关联、LLM工具回灌、时序原始采样、统计样本和准确选择器。历史失败只作线索，新疑似产品问题独立确定性复测并归因；未达confirmed不出正式问题单。

本轮输入：Core `develop@c2ab582be08d7790785b1942e03d1d1e430e2b75`、Runtime `develop@f7b763e7a12ba585582e7e9d1f9426f9ea8d8579`、Solution `common@e170e8cc8207781d0108909a5ff622aff5319ef1`；前两仓检查时clean，Solution保留本轮Demo未提交修改及无关文档修改。构建来源是该工作区快照，不只是HEAD。输入和现有JAR指纹保存在 `target/feature-acceptance-testing/feat062-design/input-snapshot.json`；未来执行前再次冻结影响集，不能把设计时JAR当作未来执行时制品。

现有依赖事实：POM有A2A SDK1.0.0.Final、Testcontainers2.0.5及Toxiproxy，运行依赖Runtime0.1.4；编译依赖中的RDC0.1.1不用于本轮服务。`application-openjiuwen.yml`已有mainplan/trip/hotel坐标，但旧链路参数和registry-center0.1.0并非本轮配置；`scripts/sut/sut-sources.yml`的Core930与Runtime0.1.3覆盖参数也不是本轮目标。默认技能配置指向memory20历史worktree，不能直接沿用。已在本轮临时 `execution.yml` 中指定当前三个源码目录；设计不修改共享配置、POM、TestConfig、基类或供给脚本。

Fixture已经实现；本轮已验证正式JAR、WSL Docker、Nacos 3.2.4 AI和真实RDC可启动，LLM配置已在运行期安全注入。每个用例创建独立进程与数据，再进行就绪探测及清理；服务ready不等于用例通过。

测试入口类共4个，共用一个 `Feat062Fixture`，全部放在既有 `src/test/java/com/huawei/ascend/sit/cases/integration/react_travel` 包：

| 计划文件 | 行映射 | 拆分原因 |
|---|---|---|
| `Feat062DirectRegistryReactAgentTest.java` | 除isolation域之外的全部blackbox行 | 四种Profile与共性故障共用完整旅行链，不为每种Profile另建类 |
| `Feat062NamespaceIsolationReactAgentTest.java` | isolation域四行 | 两套完整链同一RDC并行，以及有意残留的跨模式生命周期 |
| `Feat062RegistryApiContractTest.java` | kind=contract的全部行 | 空目录/未知目标Caller、业务失败输入、清单API、非正权重消费与主制品架构验证不能混算HTTP黑盒 |
| `Feat062RegistryExtensionIntegrationTest.java` | kind=spi-integration的全部行 | 正式源/事件/空清单、hosted调用入口和密文解密装配共用隔离宿主，独立SPI集成统计 |

行到方法/报告的确定映射（映射按既有规则维护）：矩阵ID按点/连字符切词，前缀 `FEAT-062` 替为 `feat062`，后续词首字母大写，即 `FEAT-062.dynamic.stream` → `feat062DynamicStream`；每行一个方法，不同Profile不得用同一方法替代。类/方法 `@Feature("FEAT-062: 直连模式支持 Agent 动态注册和发现")`；每行 `@Story("<矩阵ID>: <场景名>")`、`@DisplayName("Feat-062 <场景名>")`、唯一 `@Tag("story-" + ID转小写并将点改连字符)`。类保留 `feat-062`、`integration` 和实际 `blackbox`/`contract`；SPI类标签沿既有integration约定，分类在报告说明中写spi-integration。不新增reactagent/family选择标签。矩阵内同一错误语义的字段/上下界输入可以方法内参数化，保持独立场景不被压缩。

制品使用 `com.openjiuwen.example:travel-demo-mainplan:0.1.2`、`travel-demo-trip:0.1.2`、`travel-demo-hotel:0.1.2` 的Boot可执行主JAR；不能用plain/original瘦包。RDC是 `com.openjiuwen:registry-discovery-center:0.1.3`，源码target文件名为 `agent-rdc-0.1.3.jar`；是否有lib classifier以构建产物确认，服务只启动主可执行JAR。构建依赖Core `com.openjiuwen:agent-core-java:0.1.17`（已核对正式POM）；Runtime/ext坐标group为com.openjiuwen。JDK21、Maven缓存通过 `MAVEN_REPO` 与 `SUT_M2_REPO` 指定；WSL挂载使用同一缓存内容。

下列保留本轮制品的构建配方；测试已实现且精确执行，后续仅在源码快照变化时重构建。环境变量 CORE_SOURCE、RUNTIME_SOURCE、SOLUTION_SOURCE、MAVEN_REPO 指向指定快照与本地缓存。PowerShell构建；命令之间检查退出码：

```powershell
mvn -f "$env:CORE_SOURCE/core/pom.xml" clean install "-Dmaven.repo.local=$env:MAVEN_REPO" -Dmaven.test.skip=true
mvn -f "$env:RUNTIME_SOURCE/pom.xml" -pl :agent-service-app,:agent-service-adapters-agentcore -am clean install "-Dmaven.repo.local=$env:MAVEN_REPO" -Dmaven.test.skip=true
mvn -f "$env:SOLUTION_SOURCE/pom.xml" -Pmechanism -pl :agent-service-app-direct-registry,:agent-service-adapters-agentcore-ext -am clean install "-Dmaven.repo.local=$env:MAVEN_REPO" -Dmaven.test.skip=true -Dformatter.skip=true
mvn -f "$env:SOLUTION_SOURCE/common/agent-bus/registry-discovery-center/pom.xml" clean install "-Dmaven.repo.local=$env:MAVEN_REPO" -Dmaven.test.skip=true
mvn -f "$env:SOLUTION_SOURCE/common/example/multi-react-travel-demo/pom.xml" clean install "-Dmaven.repo.local=$env:MAVEN_REPO"
```

执行宿主统一在WSL Ubuntu-24.04：Runtime、RDC与测试均在WSL，Nacos走该WSL本地Docker Unix socket，避免混用Windows127.0.0.1和WSL端口。Java21运行；测试classpath不导入旧RDC编译库作为服务。静态组只启动三个Runtime；其他组先拉Nacos、创建本轮namespace、RDC真实/v2/agents探活、trip/hotel卡与注册就绪、再拉mainplan。首拉与负向用例有意改变该顺序。临时外置Spring配置及已有stack per-agent覆盖承载四Profile，已在测试侧完成；既有stack若不能表达，Fixture独立启动正式进程，不改框架。需要优雅停机的用例不可复用Windows Stop-Process。

真实LLM凭据只读执行进程环境 `LLM_API_KEY/LLM_API_BASE/LLM_MODEL/LLM_PROVIDER/LLM_SSL_VERIFY`，沿用已有配置，不再次索要或持久化密钥；测试侧生成本轮 `RDC_TOKEN` 与route-handle secret。密文用例单独生成密文并装配公开解密扩展，不以明文覆盖绕过；网络记录禁存Authorization。容器/namespace/输入脚本/证书/代理都由Fixture准备，不要求人工导入数据。

本轮全仓测试源已编译成功，验收由WSL精确执行。后续产品修复可使用同一runner选择受影响的方法，例：

```powershell
& .agents/skills/feature-acceptance-testing/scripts/run-wsl-tests.ps1 -ConfigPath target/feature-acceptance-testing/feat062-design/execution.yml -TestSelector 'Feat062DirectRegistryReactAgentTest#feat062DynamicStream' -Environment openjiuwen
& .agents/skills/feature-acceptance-testing/scripts/run-wsl-tests.ps1 -ConfigPath target/feature-acceptance-testing/feat062-design/execution.yml -TestSelector 'Feat062NamespaceIsolationReactAgentTest' -Environment openjiuwen
```

单类、feature与Story选择（以下在WSL的仓根执行，缓存由SUT_M2_REPO提供）：

```bash
./mvnw -Dmaven.repo.local="$SUT_M2_REPO" -Dtest.env=openjiuwen -Dtest=Feat062DirectRegistryReactAgentTest test
./mvnw -Dmaven.repo.local="$SUT_M2_REPO" -Dtest.env=openjiuwen '-Dtest=Feat062*Test' -Dgroups=feat-062 test
./mvnw -Dmaven.repo.local="$SUT_M2_REPO" -Dtest.env=openjiuwen -Dtest=Feat062DirectRegistryReactAgentTest -Dgroups=story-feat-062-dynamic-stream test
```

只在条件变化后重跑影响方法。全仓testCompile若被无关源阻断，记测试仓基线问题，不改POM、不加排除、不重复无条件编译。实际失败按测试/环境/产品疑似分层；product confirmed之后才出正式问题单。测试宿主只提供合同输入与观察，不替代被测产品能力；产品缺少文档承诺的接口/接线/行为时保留失败，排除测试/环境/制品错误并确认归因后登记产品缺陷。Fixture未完成或未触发目标路径不能登记产品缺陷。本轮测试代码、资源、原始报告与失败分析已交付；产品源码未修改。

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 静态地址来源冲突 | static-contract.address-source | 按Feature §6固定配置地址预期；不设执行阻塞 | 直接按正常用例执行；设计文档差异继续留档，不要求用户先裁决 |
| 合法无ext制品 | static-contract.default-equivalence、switch.extension-missing | 正常设计；测试侧已完成隔离打包，执行结果另计 | 测试侧生成相同业务的有ext/无ext依赖对照与可启动包；产品缺少误配置防护时记FAIL，不等产品修复 |
| hosted作为远端调用方 | hosted-catalog四行 | 正常设计；spi-integration；测试侧已实现，执行结果另计 | 正式扩展宿主和局部配置由测试侧准备；只证明Runtime继承能力，不抵扣原始demo黑盒集成 |
| 源/事件异常及空注册清单 | extension三行 | 正常设计；spi-integration；测试侧已实现，执行结果另计 | 测试侧按公开SPI输入一次异常/空清单，保留真实消费和启动生命周期；不以RDC503替代源异常 |
| 空目录/未知目标错误 | catalog.startup-empty、disabled-unknown、uncached-no-route | 正常设计；contract；测试侧已实现，执行结果另计 | 同进程入口仅转调真实Caller公开合同并返回原始结果；不要求模型猜不存在的工具，不计作demo业务入口黑盒覆盖 |
| 密文token接入 | auth.encrypted-token | 正常设计；spi-integration；测试侧已实现，执行结果另计 | 测试侧生成临时密文并装配公开解密扩展，核对registry受支持接入；产品解密接线缺失按承诺判FAIL，无需用户提供KMS |
| 清单API与非正权重 | contract.instance-*、routing.invalid-weight | 正常设计；contract；测试侧已实现，执行结果另计 | 只读入口转调真实提供者；候选输入用公开目录源合同；测试侧负责生命周期屏障与统计驱动 |
| 业务FAILED/REJECTED | routing.business-failure | 正常设计；调用方contract；测试侧已实现，执行结果另计 | 受控A2A依赖返回合法业务失败，真实Caller处理；不证明demo自身的失败产生逻辑，不需人工编造业务输入 |
| TLS与租户认证绑定 | static-contract.tls、auth.tenant-mismatch | 正常设计；测试侧环境Fixture已实现，执行结果另计 | 自动证书/代理；真实RDC caller→tenant绑定及受控caller头由测试侧配置，保留真实RDC结果；实际错配被接受，已登记产品问题 |
| 用户侧必需准备 | 真实LLM复核四Profile业务链 | 已安全注入并完成真实业务；无新增用户准备项 | 沿用已提供的LLM配置，在执行环境安全注入；端点/模型变化时才补充，不在聊天或文件重复提供密钥 |
| 企业KMS/IAM专用接入 | 当前通用本地验收不依赖 | 不是本轮必需前置 | 仅另行指定企业环境兼容验证时，再提供相应端点与访问条件 |
| 动态流式历史失败 | dynamic.stream、mixed-trip-static.stream、mixed-hotel-static.stream | product confirmed：3轮、9次一致失败，完整业务均成功 | 原判据实测，保留动态远端method和投影原证据；不沿用demo脚本对动态投影的豁免 |
| 同RDC跨namespace历史串扰 | isolation.namespace-concurrent/namespace-churn/namespace-resume | product confirmed：三个独立拓扑实测；历史错误夹具不计入 | A/B同时在线、同名目标交错变更，无重启RDC绕行；独立复测并归因RDC/Nacos消费边界 |

技能输入解析器仅匹配 `FEAT-062-*`，当前L2名为 `Feat-Func-062-*`，本轮运行返回“found 0”。已按准确路径完整读取、记录哈希和分支快照，未修改解析器；配置校验通过，过期清理只做dry-run。此生成工具限制不影响需求设计，也不算产品问题。

没有为了设计读取Core/Runtime/RDC/direct-registry产品Java源码。已读取指定Demo的README、三模块YAML、POM和历史验证记录，仅用于核实四Profile、入口、正式制品、触发与观察面；历史记录中关于内部实现的定位线索不成为新Oracle。与Feature/L2相冲突的历史测试豁免不继承。
