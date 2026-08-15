# Rehevo 可观测性（0.3）

本目录把 0.2 固定的业务指标接入可启动、可审查的 Prometheus/Grafana 闭环。所有配置均可版本控制；不包含 API Key、用户标识、问题或文档内容。

## 启动与验收

```powershell
docker compose -f docker-compose.dev.yml up -d prometheus grafana
Invoke-RestMethod http://localhost:9091/api/v1/targets
```

Rehevo 后端运行在 Windows 宿主机 `8080`；Prometheus 容器通过 `host.docker.internal:8080` 抓取 `/actuator/prometheus`。若 3000 已被占用，设置 `GRAFANA_PORT` 后重启 Compose。Grafana 首次登录使用镜像默认管理员账户，必须立即设置本地管理员密码，不要把密码提交进仓库。

验收条件：`/api/v1/targets` 中 `rehevo-app` 为 `up`；Grafana 自动出现 “Rehevo” 文件夹内的三张看板；`http://localhost:9091/rules` 显示七条规则。

## 看板和告警

| 看板 | 量化指标 |
| --- | --- |
| 实时语音体验 | 活跃会话、首 Token/首音频 P95、Turn P95、ASR 重连、成功/失败速率 |
| 异步任务可靠性 | Stream backlog、最老 Pending idle、任务状态速率、处理 P95 |
| AI / RAG 质量与成本代理指标 | 回答成功率、回答/检索 P95、平均命中数、无结果、结构化输出失败 |

告警规则在 `alerts/rehevo-alerts.yml`。Prometheus 在每个告警满足条件时进入 `pending`，满足 `for` 窗口后为 `firing`；恢复到阈值内会自动变为 `inactive`。每条规则的处置动作写在告警 `action` 注释中。

## 实验记录

`experiments/rag-baseline.md` 记录固定知识库的可重复 RAG 基线。语音的 20 Turn 正常样本和 ASR/TTS 故障注入属于 0.4，尚未宣称完成。
