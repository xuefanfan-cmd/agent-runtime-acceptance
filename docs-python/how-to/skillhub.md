---
title: SkillHub 技能注入：启动期下载技能包，请求时装配进 Agent
description: 用 openjiuwen.service.skill_hub 开启技能中间件——Provider 与 Installer 两个替换边界、启用判据、鉴权与解密、失败分类与重试
audience: ai-coding
status: verified
---

# SkillHub 技能注入：启动期下载技能包，请求时装配进 Agent

## 适用场景 / 不适用场景

**适用**：Agent 的技能集合由外部技能中心统一管理，需要在启动期下载材料、在请求期装配进 Agent，且要能换技能中心而不动框架适配。

**不适用**：

- 技能就是几个本地函数 —— 用 [Tool](tools.md)，不要为它引入技能中心。
- 技能实质是另一个 Agent —— 用 [A2A](a2a.md) 远端调用。
- 部署环境没有技能中心 —— 保持 `enabled` 为假，整条链路不装配，对象图与没有本特性时逐字相同。

## 最小完整示例

```yaml
openjiuwen:
  service:
    skill_hub:
      enabled: true
      endpoint: https://skillhub.internal
      auth_type: bearer            # bearer | system-token
      local_dir: /var/lib/agent/skills
      fetch:
        page_size: 200
        concurrency: 4
        download_timeout_s: 600.0
      retry:
        initial_delay_s: 5.0
        period_s: 30.0
        max_attempts: 120
```

```python
coordinator = build_skill_hub_coordinator(
    config.skill_hub, installer=installer, decryptor=decryptor, discovered=discovered
)
```

`enabled` 为假时返回空，调用方据此**不套装饰层**。

## 能力点逐个展开

### 两个替换边界

- **Provider**：管「去哪儿取材」——访问技能中心、分页拉清单、下载材料。
- **Installer**：管「材料交给谁」——把落盘的材料移交给框架适配件。

二者分开的理由是：换技能中心不该动框架适配，换框架不该动技能中心客户端。合成一个接口时，为了换客户端就得连带重写移交逻辑，而移交逻辑与技能中心毫无关系。

第三个协议 `SkillTargetResolver` 是**可选能力**：不是每种框架适配件都拿得到可接收技能的实例。它用独立协议表达，而不是往 `AgentHandler` 端口上加方法——后者会让所有实现被迫应付一个多数用不上的方法。

### 自定义 Provider

`provider` 指定实现名，由扩展点发现机制加载。指定了但扩展点下没有该名字、或实例化失败，都会在**启动期**报错并指出是哪一项配置——不是等到第一次请求才失败。

### 鉴权与凭据

`auth_type` 取 `bearer` 或 `system-token`，取值非法即启动期报错。`encrypted_token` 经凭据解密器解密后使用；不配解密器即按明文取用。令牌在类型层面被掩码，不进普通日志。

### 失败分类

技能中心访问失败按九类枚举区分（网络、鉴权、找不到、校验失败等）。取值集合与上游逐字一致，不按「我方暂时用不上」裁剪——它是跨语言互通的词汇表，裁剪会让两侧在同一个失败上说不同的话。

### 落盘与解压上限

`local_dir` 是材料落盘目录，`fetch.max_extracted_bytes` 限制解压后的体积上限（默认 512 MiB），防止一个畸形包撑爆磁盘。

## 配置项参考

- **`skill_hub.enabled`**：总开关，默认 `false`。为假时整条链路不装配。
- **`skill_hub.endpoint`**：技能中心地址。`enabled` 为真时必填。
- **`skill_hub.local_dir`**：材料落盘目录。`enabled` 为真时必填。
- **`skill_hub.auth_type`**：`bearer` 或 `system-token`，默认 `bearer`。
- **`skill_hub.encrypted_token`**：密文令牌，经凭据解密器解密。
- **`skill_hub.provider`**：自定义 Provider 的实现名，留空即用内建实现。
- **`skill_hub.fetch.page_size` / `concurrency` / `connect_timeout_s` / `request_timeout_s` / `download_timeout_s` / `max_extracted_bytes`**：拉取与下载参数。
- **`skill_hub.retry.initial_delay_s` / `period_s` / `max_attempts`**：启动期重试节奏与上限。

## 坑位与排错

**注意：装配错误在启动期暴露，不是请求期。** 缺 `endpoint`、缺 `local_dir`、`auth_type` 非法、`provider` 找不到，四类都在装配时报错并带上配置项的属性路径。这是刻意的——技能缺失在请求期才发现会表现成「模型能力莫名其妙变弱」。

**注意：`enabled` 为假不是「装了但不用」。** 协调器返回空，调用方不套装饰层，性能与对象图都回到没有本特性的状态。

**注意：技能描述不等于代码执行授权。** 选择技能前要检查宿主允许的能力集合；执行期要传递 Task、trace 与取消上下文；敏感参数不进普通日志。

**排错：启动期反复重试后失败** —— 检查 `retry.max_attempts` 与技能中心可达性；重试上限到达后应当让启动失败，而不是带着空技能集合起来。

## 端到端校验

装配面：

```bash
python -c "
from agent_runtime.adapters.outbound.skillhub.factory import build_skill_hub_coordinator
from agent_runtime.ports.skill_hub import SkillHubConfig
print(build_skill_hub_coordinator(SkillHubConfig(enabled=False), installer=None))
"
```

预期输出空——未启用时不装配。启用后的端到端校验需要可达的技能中心：观察启动日志里的下载条目数、`local_dir` 下的材料，以及第一次请求后 Agent 能力清单里出现的技能项。

当前交付未包含真实技能中心的端到端证据。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.ports.skill_hub.SkillHubConfig` / `SkillHubFetchConfig` / `SkillHubRetryConfig`
- `agent_runtime.ports.skill_hub.SkillHubErrorCategory` / `SkillHubError` / `LocalSkillEntry`
- `agent_runtime.adapters.outbound.skillhub.factory.build_skill_hub_coordinator`
- `agent_runtime.adapters.outbound.skillhub.coordinator.SkillHubCoordinator`
- `agent_runtime.adapters.outbound.skillhub.openjiuwen.OpenJiuwenSkillHubProvider`

## See also

- [Tool 定义与跨类型注册](tools.md)
- [配置驱动装配](config-driven-agent.md)
