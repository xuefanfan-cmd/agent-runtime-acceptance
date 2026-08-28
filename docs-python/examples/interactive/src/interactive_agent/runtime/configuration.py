# coding: utf-8
"""本验收项目的配置与事实登记（interrupt → input_required → resume/continue 语义验证）。

本项目是独立 Pi Agent 用当前配置的 DeepSeek 模型（模型标识 pi-ds-v4-flash）开发的
**交互式 Agent 应用**，重点是验证 runtime 的
`interrupt → input_required → resume/continue` 语义（FEAT-008）。

本工程**只读引用** runtime，不修改它；唯一写入范围是本目录。runtime 检出路径由
环境变量 `RUNTIME_ROOT` 给出，指向 agent-solution 仓的 `common/agent-runtime-ext-python`。
"""

from __future__ import annotations

import os

#: 模型标识（记录用；验证的是 runtime 语义，模型本身不参与确定性 Handler 的输出）。
MODEL_ID = "pi-ds-v4-flash"

#: 本项目（唯一写入范围）。
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

#: 确定性 Handler 的身份。
AGENT_ID = "interactive-agent"
PRIORITY = 0

#: 交互式会话用 conversation_id（REST 路径段）。
CONVERSATION_ID = "conv-interactive-0001"

#: REST 路由模板：POST /v1/{project}/agents/{agent}/conversations/{conv}
PROJECT_ID = "demo"
REST_PATH = f"/v1/{PROJECT_ID}/agents/{AGENT_ID}/conversations/{CONVERSATION_ID}"

#: 第一轮中断帧的文案与恢复锚点（interaction_id）。
INTERRUPT_CONTENT = "需要用户输入：请提供您的账户ID"
INTERACTION_ID = "interact-0001"

#: 第二轮续接的补充输入（会成为 ServeRequest.for_resume 的 user_supplement）。
RESUME_SUPPLEMENT = "我的账户ID是ACC-123"
