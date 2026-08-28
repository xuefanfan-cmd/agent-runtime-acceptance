---
title: Runtime 生命周期
description: 启动、就绪、服务、排水、关闭五个阶段的责任划分与验收判据——宿主义务、就绪视图与在途流排水
audience: ai-coding
status: verified
snippets: ../snippets/lifecycle-hook.py
---

# Runtime 生命周期

## 适用场景 / 不适用场景

**适用**：把 runtime 嵌进宿主进程，需要明确谁负责启动依赖、何时开始接流量、停机时怎么排水。

**不适用**：

- 只跑单元测试 —— 不必驱动完整生命周期。
- 容器编排层的存活与就绪探针策略 —— 那是部署面，见 [部署与切换](deployment.md)；本页只讲 runtime 提供什么、宿主要做什么。

## 最小装配契约

```python
app = create_a2a_app(
    handler, name=agent_id, config=config,
    init_hooks=(init_dependencies,),   # 启动期钩子：注册运行资源、连库建表
    readiness=readiness,               # 就绪视图由 runtime 提供，端点由宿主自建
)
```

生命周期挂在应用生命周期上。**不要再用框架的启动事件装饰器**：组合根已挂生命周期，二者互斥，用事件装饰器注册的逻辑会静默不执行。

## 能力点逐个展开

### 五个阶段

```text
configure -> initialize -> ready -> serve -> drain -> close
```

- **configure**：加载并绑定配置，此时还没有外部连接。
- **initialize**：执行 `init_hooks`，注册运行资源、建立依赖连接。工作流注册必须在这一步（编译要事件循环）。
- **ready**：就绪视图翻转，宿主的探针端点此时才应报就绪。
- **serve**：接收请求。
- **drain**：停止接新请求，等待在途流结束，上限由 `shutdown_timeout_s` 控制。
- **close**：释放连接与存储。

### 宿主义务

就绪**视图由 runtime 提供、端点由宿主自建**。注入自己的就绪对象时，runtime 会把同一个实例既交给生命周期又导出到应用状态——各建一个的话，宿主读到的永远是初始值。

### 启动失败的两种处理

`lifecycle.init_fail_fast` 为真时，启动钩子失败即让进程启动失败；为假时记录并继续。选择取决于该依赖是不是「没有它服务就没有意义」。

### 健康与就绪不是一回事

健康检查只说明进程存活；就绪要求配置、依赖与运行资源都到位。用健康检查当就绪探针，会在依赖没起来时就放流量进来。

## 配置项参考

- **`openjiuwen.service.lifecycle.init_fail_fast`**：启动钩子失败时是否立即失败。
- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：排水上限，默认 30。长任务循环要评估这个值是否够一轮收尾。
- **函数参数 `init_hooks`**：启动钩子序列，按序执行。
- **函数参数 `readiness`**：就绪视图实例，宿主与 runtime 必须共用同一个。

## 坑位与排错

**注意：工作流注册放在模块导入期会失败。** 编译过程使用异步原语，必须挂进 `init_hooks`。

**注意：排水窗口内被强杀等于丢在途请求。** 编排层的停机时限要与容器的终止宽限期对齐，后者必须大于前者。

**排错：启动钩子没执行** —— 用了框架的启动事件装饰器而不是 `init_hooks`。

**排错：就绪端点永远是初始值** —— 宿主自己新建了就绪对象，没有用注入的那个。

**排错：停机后仍有模型请求** —— 在途流的生成器没有在 `finally` 里释放上游。

## 端到端校验

```bash
# 启动 -> 探就绪 -> 发一个长流 -> 发 SIGTERM -> 观察排水
kill -TERM $PID
```

判据：收到终止信号后不再接受新请求；在途流在 `shutdown_timeout_s` 内正常结束或明确失败；进程退出码正常；无残留后台任务。对每个阶段记录失败方式与可重试性。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.lifespan.runtime_lifespan`
- `agent_runtime.bootstrap.readiness`
- `agent_runtime.bootstrap.config.runtime_config.LifecycleConfig`
- `agent_runtime.application.active_streams`

## See also

- [部署与切换](deployment.md)
- [配置驱动装配](config-driven-agent.md)
- [生命周期、Task 与外置状态](../architecture/02-agent-runtime-python技术架构.md)
