# CI 集成：流水线接入与报告归档

本文是可选项：把 `run-pipeline.sh` 接进定时/CI 体系，并妥善归档报告。
日常手动跑法见 [quickstart.md](quickstart.md)。

## 1. 现成触发器

仓库自带 systemd 触发器样例（`scripts/triggers/`）：

| 文件 | 用途 |
| --- | --- |
| `sut-provision.service` / `sut-provision.timer` | 定时只做 Stage 1 供给（预热 .m2、跟进 SUT 分支） |
| `cron.example` | crontab 风格的完整流水线调用样例 |

要点：

- Stage 1 是**幂等**的（fingerprint = 源码 HEAD + 构建步骤 + JDK + m2），重复跑是廉价 no-op，
  适合高频 timer；真正测试流水线用 `--skip-provision` 或按需要放开。
- `sut-sources.yml` 里 CI 机器上的源码建议 `sync: hard`（fetch + reset 到 origin/ref，保证干净）；
  开发机保持默认 `sync: none`（不动开发树）。
- 报告阶段需要 `allure` CLI 在 PATH；脚本会自动 source `~/.nvm/nvm.sh`，
  所以 CI agent 用 nvm 装即可，systemd unit 里不用特配 PATH。

## 2. CI 接入范式

任何 CI（Jenkins / GitLab CI / GitHub Actions）本质上就一条命令：

```bash
./scripts/run-pipeline.sh --env sit      # 或 local / openjiuwen
```

- **退出码**：透传自测试阶段，非零即流水线失败，原生对接 CI 的红绿判定。
- **环境选择**：对部署态 SIT 环境跑用 `--env sit`（`mode: remote`，CI agent 无需 Docker）；
  跑全量本地拉起则用 `local` / `openjiuwen`（CI agent 需要 Docker 给 Testcontainers）。
- **密钥**：`LLM_API_KEY` 等通过 CI 的 secret 变量注入环境，yml 只引用 `${LLM_*}`，不落明文。

## 3. 产物归档

每次运行值得归档的东西：

| 路径 | 内容 | 建议 |
| --- | --- | --- |
| `target/allure-results/` | 原始结果（JSON） | **必归档**：报告可随时从它重建，也是历史趋势的数据源 |
| `target/allure-report/` | Awesome HTML 静态站点 | 作为 CI artifact 发布，或拷到静态服务器/Nginx 长期托管 |
| `target/allure-report-md/` | agent-inspect Markdown | 失败时喂给 AI 做归因分析，按需归档 |
| `target/sit-logs/` | agent stdout + wire 日志 | 失败排查用，建议仅失败时归档或短期保留 |

历史趋势：`allurerc.mjs` 预留了 `historyPath`（当前注释）。要启用跨运行趋势/flaky 图表，
取消注释并把该文件放到 **`target/` 之外**的路径（`mvn clean` 会清 target），
CI 里将其作为缓存/持久 artifact 跨构建传递。

## 4. 远端看报告

CI 机器上生成的报告想在本地浏览器看，不必暴露 HTTP 端口——
用 `ssh -L` 本地端口转发即可，操作步骤见
[quickstart.md §4.4](quickstart.md#44-远端-linux-跑测试本机-windows-看报告ssh--l)。
