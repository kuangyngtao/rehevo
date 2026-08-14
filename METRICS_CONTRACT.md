# Rehevo 指标契约（0.2）

本文件定义业务指标的名称、单位、成功/失败口径和允许标签。实现入口为
`common/metrics/AppMetricNames` 与 `ApplicationMetrics`；框架提供的 Spring AI
Observation、JVM、HTTP、数据库连接池指标保持由 Actuator/Micrometer 自动暴露。

## 标签红线

只允许 `status`、`stream`、`path`、`interaction`、`streaming`、`stage` 六类有限标签。
`sessionId`、`turnId`、`userId`、`knowledgeBaseId`、问题、Prompt、文档名、原文和错误详情只可进入日志或 Trace。

## 语音

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_voice_interview_active_sessions` | Gauge/会话数 | 无 | 当前 WebSocket 会话数 |
| `app_voice_interview_asr_ready_total` | Counter/次 | `status` | ASR ready 回调成功 |
| `app_voice_interview_asr_reconnect_total` | Counter/次 | `status` | ASR 重连尝试 |
| `app_voice_interview_asr_dropped_audio_total` | Counter/帧 | `status` | AI 播放冷却或 ASR 未 ready 时丢弃的音频帧 |
| `app_voice_interview_llm_first_token_latency_seconds` | Timer/秒 | `status` | 从提交回答到首个 LLM Token |
| `app_voice_interview_first_audio_latency_seconds` | Timer/秒 | `status` | 从提交回答到向前端发送首个音频帧 |
| `app_voice_interview_tts_duration_seconds` | Timer/秒 | `status` | 一次 TTS 合成耗时 |
| `app_voice_interview_turn_duration_seconds` | Timer/秒 | `status` | LLM + TTS 整轮耗时 |
| `app_voice_interview_turn_cancelled_total` | Counter/次 | `status` | 处理中的 WebSocket 断开而取消的 Turn |

## Redis Stream

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_async_stream_enqueued_total` | Counter/条 | `stream`,`status` | 入队成功或失败 |
| `app_async_stream_task_total` | Counter/条 | `stream`,`status` | 完成、失败、跳过、重试或恢复完成 |
| `app_async_stream_processing_seconds` | Timer/秒 | `stream`,`status` | 单条任务处理耗时 |
| `app_async_stream_backlog` | Gauge/条 | `stream` | Redis consumer group lag |
| `app_async_stream_pending` | Gauge/条 | `stream` | Redis consumer group Pending 数 |
| `app_async_stream_oldest_pending_idle` | Gauge/毫秒 | `stream` | 最久 Pending 消息的未确认 idle 时长 |

## RAG

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_rag_vectorization_total` / `app_rag_vectorization_seconds` | Counter、Timer | `status` | 文本切块、Embedding、写入和提升成功/失败 |
| `app_rag_vectorization_chunks` | Summary/Chunk 数 | `status` | 成功任务的切块数量 |
| `app_rag_retrieval_total` / `app_rag_retrieval_seconds` | Counter、Timer | `status`,`path` | 主检索或兜底检索耗时及结果 |
| `app_rag_retrieval_hits` | Summary/文档数 | `path` | 每次成功检索命中数 |
| `app_rag_retrieval_no_hit_total` | Counter/次 | `path` | 成功检索但无命中 |
| `app_rag_retrieval_fallback_total` | Counter/次 | `status` | 兜底检索被执行并成功或失败 |
| `app_rag_query_rewrite_total` | Counter/次 | `status` | 改写成功、失败或跳过 |
| `app_rag_answer_total` / `app_rag_answer_seconds` | Counter、Timer | `status`,`interaction` | 同步或 SSE 回答耗时、失败或无模型调用跳过 |

Dashboard 和告警阈值属于 0.3；本契约只固定数据口径与标签边界。
