# 语音 Turn 基线采集协议

## 目标与边界

本实验采集 20 个真实的“用户说话 → ASR → LLM → TTS”正常 Turn，建立 M0 的性能基线。必须使用真实浏览器麦克风输入和当前 DashScope 配置；不得用 Mock、手工写指标或把文本接口结果当作语音 Turn。

采集内容只记录序号、指标和结果，不保存录音、转写全文、`sessionId`、用户身份或 Prompt。

## 固定条件

- 后端：`http://localhost:8080`，Prometheus：`http://localhost:9091`
- 同一浏览器、网络与模型配置
- 每个 Turn 清晰说完一个回答，等待 TTS 播放完成后再开始下一轮
- 采集前确认 `up{job="rehevo-app"}=1`，并记录开始、结束时间

## 建议 20 Turn 组成

| 类别 | Turn 数 | 约束 |
| --- | ---: | --- |
| 自我介绍与项目背景 | 4 | 20—40 秒完整回答 |
| Java/Spring 技术题 | 8 | 含至少两句解释或例子 |
| Redis/异步任务题 | 4 | 明确说明 ACK、重试或幂等性 |
| 场景追问 | 4 | 回答后等待面试官追问与 TTS 完成 |

## 每个 Turn 的记录表

| Turn | ASR ready | 首 Token（秒） | 首音频（秒） | TTS（秒） | 整轮（秒） | 结果 | 备注 |
| ---: | --- | ---: | ---: | ---: | ---: | --- | --- |
| 1 | | | | | | | |
| 2 | | | | | | | |
| … | | | | | | | |
| 20 | | | | | | | |

## Prometheus 取数

在开始前、结束后分别保存 `/api/v1/query` 的原始 JSON。计算 20 个样本期间的差值；P50/P95/P99 只在样本数达到 20 后报告。

```text
app_voice_interview_asr_ready_total
app_voice_interview_turn_completed_total
histogram_quantile(0.95, sum(increase(app_voice_interview_llm_first_token_latency_seconds_bucket[窗口])) by (le))
histogram_quantile(0.95, sum(increase(app_voice_interview_first_audio_latency_seconds_bucket[窗口])) by (le))
histogram_quantile(0.95, sum(increase(app_voice_interview_tts_duration_seconds_bucket[窗口])) by (le))
histogram_quantile(0.95, sum(increase(app_voice_interview_turn_duration_seconds_bucket[窗口])) by (le))
```

## 通过与异常分类

- 正常：ASR ready、收到至少一个 TTS 音频帧、Turn 以 `success` 完成。
- ASR：未 ready、重连、丢音频。
- LLM/TTS：无首 Token、无首音频、TTS 空音频或超时。
- 传输：浏览器断连、用户取消、迟到结果。

任何异常都保留指标快照和时间窗口；不把取消、客户端离开或未完成 Turn 静默算作成功。完成 20 个正常样本后，才能进行 TTS 延迟、ASR 断连和异步失败的故障实验。
