---
title: 部署与切换
description: 从本地宿主到部署级服务的配置、依赖、探针与排水验收——含存量兼容形态到 SDK 形态的切换判据
audience: both
status: verified
snippets: ../snippets/deployment-checklist.yml
---

# 部署与切换

## 适用场景 / 不适用场景

**适用**：把已经在本地跑通的宿主部署成服务，或把存量兼容形态切换到 SDK 形态。

**不适用**：

- 本地开发自测 —— 用 [安装、检查与启动](setup-and-run.md)。
- 系统地验证写出来的东西 —— 见 [验证你写的 Agent](verification.md)。

## 最小装配契约

部署前必须确认的四件事：

```text
1. 配置来源与优先级已记录（文件 -> secret 目录 -> 环境变量）
2. 状态后端不是意外的进程内实现
3. 反向代理支持 SSE 长连接且超时大于业务最长响应
4. 终止宽限期大于 shutdown_timeout_s
```

## 能力点逐个展开

### 配置装载

runtime 不读 `.env`。启动方显式装载：`set -a; . deploy/.env; set +a`，或容器的 `--env-file`。环境变量层级用双下划线表达。

### 探针

健康检查说明进程存活，就绪要求依赖到位。就绪端点由宿主自建，读 runtime 提供的就绪视图。两者不能用同一个实现。

### 长连接与超时

流式响应经过反向代理时，代理的读超时必须大于业务最长响应时间，且要关闭响应缓冲——否则客户端看到的是「一次性返回」而不是流。

### 停机排水

收到终止信号后停止接新请求，在途流在排水窗口内结束。容器的终止宽限期必须大于 `shutdown_timeout_s`，否则排水逻辑没跑完就被强杀。

### 存量兼容形态到 SDK 形态

存量形态用 `python -m agent_runtime.bootstrap.legacy_compat` 原位运行既有 Agent 代码，需要把存量应用目录放进模块搜索路径，并准备模型与状态后端前置。切换判据：SDK 形态下同一组 wire 判据全部通过后，才移除存量入口。

## 配置项参考

- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：排水上限，与容器终止宽限期成对设置。
- **`openjiuwen.service.middleware.*`**：状态后端端点，见 [中间件配置](middleware.md)。
- **`openjiuwen.service.a2a_access.public_url`**：卡片对外地址，反向代理场景必须显式配。
- **`RUNTIME_LEGACY_AGENT`**（宿主环境变量）：存量兼容形态下要装载的 Agent。

## 坑位与排错

**注意：卡片地址在反向代理后会变。** 不配 `public_url` 时按请求地址推导；代理没有正确透传原始 host 时，对端会拿到错误回连地址。

**排错：SSE 在生产变成一次性返回** —— 代理开了响应缓冲。

**排错：滚动更新期间请求失败** —— 就绪探针用的是健康检查，流量在依赖就绪前就进来了。

**排错：停机丢在途请求** —— 终止宽限期小于排水上限。

复核项按 [`snippets/deployment-checklist.yml`](../snippets/deployment-checklist.yml) 逐条记录。

## 端到端校验

```bash
curl -fsS "$BASE_URL/.well-known/agent-card.json"   # 卡片地址是否为公开地址
curl -N -X POST "$BASE_URL/query" -d '{"message":"hi"}' # SSE 是否逐帧到达
kill -TERM $PID                                      # 排水是否完整
```

逐项记录：配置优先级、入口路径探测结果、状态后端档位、代理超时值、终止宽限期与排水耗时。部署级 E2E 的脚本与判据见 [验证与交付判定](verification.md)。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.lifespan.runtime_lifespan`
- `agent_runtime.bootstrap.readiness`
- `agent_runtime.bootstrap.legacy_compat.host` / `entrypoint`
- `agent_runtime.bootstrap.config.loader.ConfigLoader`

## See also

- [Runtime 生命周期](lifecycle.md)
- [验证你写的 Agent](verification.md)
- [扩展体系与部署架构](../architecture/04-协作与扩展体系.md)
