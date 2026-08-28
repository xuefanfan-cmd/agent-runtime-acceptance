---
title: 验证你写的 Agent
description: 四层验证的组织方式——语义层、装配层、协议层、部署层；每层证明什么、用什么命令、哪些结论不能互相替代
audience: ai-coding
status: verified
snippets: ../snippets/verification-evidence.yml
---

# 验证你写的 Agent

## 适用场景 / 不适用场景

**适用**：写完一个 Agent 或改完一处装配，要系统地确认它真的能用，而不是"导入没报错"。

**不适用**：

- 起环境、跑起来看一眼 —— 用 [安装、检查与启动](setup-and-run.md)。
- 部署到服务并切流量 —— 见 [部署与切换](deployment.md)。

## 最小装配契约

四层验证，从内到外，每层证明的事不同：

| 层 | 回答的问题 | 需要凭据吗 |
|---|---|---|
| 语义层 | Agent 的卡片、模型配置、工具、Rail、DAG 构造对不对 | 构造期不需要；真实推理需要 |
| 装配层 | 运行资源登记、Handler 择取入口、组合根接线对不对 | 不需要 |
| 协议层 | 对外 wire 形态（卡片、Task、SSE 帧、错误信封）对不对 | 不需要 |
| 部署层 | 真进程、真 socket、取消断连恢复、共享状态对不对 | 视场景 |

前三层就是 `docs/examples/` 各工程 `tests/` 覆盖的范围，**全程不出网**。

## 能力点逐个展开

### 装配门禁：不需要凭据的那三层

```bash
export RUNTIME_ROOT=/path/to/agent-runtime
cd docs/examples/react && PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

它逐项断言：语义层不依赖 runtime 与 Web 框架（AST 检查分层红线）、工具执行体的纯函数行为、无凭据下能否完成构造、配置取值顺序、运行资源登记与重复登记语义、Handler 是否走对执行入口、A2A 卡片是否带上配置声明的技能项。

**这层能用占位端点跑通，是因为构造期不出网**：ReAct 的扁平模型字段不校验端点；Workflow 与 DeepAgent 的模型客户端配置会在构造期校验 `api_key` 与 `api_base`（DeepAgent 开启证书校验时还要证书路径），用占位值满足即可。

### 真实模型档：剩下的那一层

真实推理循环、真实工具调用、HITL 续接、远端联调都需要凭据与网络，按各指南的「端到端校验」执行，不进自动化门禁。

**不要把需要凭据的用例混进装配门禁**：CI 里没有凭据，混进去必挂，然后整套门禁就会被人加 skip 绕过去，最后什么都验不到。

### 四层不可互相替代

装配门禁全绿证明不了真实模型跑得通；一次端到端跑通也证明不了边界条件——那是单测的职责。宣称某个能力可用时，说清楚是哪一层的证据。

### 记录什么

每次验证记录：执行命令、通过与跳过数量、跳过原因、未覆盖面、环境（Python 版本、`openjiuwen` 版本、是否有真实端点）。跳过原因要具体到缺什么依赖，不能只写 skipped。可复制的记录形态见 [`snippets/verification-evidence.yml`](../snippets/verification-evidence.yml)。

## 配置项参考

本页无运行配置。与验证相关的环境开关：

- **`RUNTIME_ROOT`**：runtime 检出路径，各工程用它拼 `PYTHONPATH`。
- **`LLM_API_KEY` / `LLM_API_BASE`**：真实模型档需要；装配门禁用占位值。
- **`LLM_VERIFY_SSL` / `LLM_SSL_CERT`**：DeepAgent 的证书校验开关与证书路径。

## 坑位与排错

**注意：能力状态要分级表述。** 「有类」「有测试」「有装配」「真跑通」是四件事，用「已接线并验证」「已实现但需装配」「不由 runtime 承载」这类分档说法，不把孤立组件写成产品能力。

**注意：替身报告不能复用。** 两个框架用同一份确定性替身跑出的结果，不能说明两个框架都通过。

**排错：本机通过、CI 失败** —— 用例依赖了本机才有的凭据或服务，应移出装配门禁。

**排错：门禁全绿但真实环境报错** —— 正常，两层证明的事不同；去看对应指南的端到端校验小节。

## 端到端校验

八个源码用例一次跑完：

```bash
export RUNTIME_ROOT=/path/to/agent-runtime
for p in react workflow deepagent versatile a2a rest interactive deepseek; do
  (cd docs/examples/$p && PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests)
done
```

预期八个工程全部通过且无跳过。任一工程失败时，先看是分层红线被破（语义层引入了 runtime 依赖）还是签名漂移（`openjiuwen` 版本变了）。

## API 锚点（包内符号，按依赖可查）

本页不绑定公开 API。

## See also

- [安装、检查与启动](setup-and-run.md)
- [部署与切换](deployment.md)
- [Agent 开发路径](agent-development-path.md)
