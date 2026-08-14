# InterviewGuide TODO

> 当前主线：先建立可信基线与可观测闭环，再依次推进评估可信度、RAG/PREP、实时 Turn 和性能优化。
>
> 策略来源：`C:\Users\yngtao\Documents\Codex\2026-07-28\wo\outputs\AI面试开源项目横向调研与InterviewGuide融合策略.md`
>
> 最近核对：2026-08-14。`[x]` 只表示当前源码或本次命令已验证；历史运行记录和设计文档不算当前完成证据。

## 当前结论

- 当前不是继续增加页面或引入新框架，而是把已有语音、评估、RAG 能力做成可验证闭环。
- 当前里程碑为 **M0：可信基线 + 可观测第一版**。
- M0 完成前，不并行改 Rubric 数据模型、RAG 检索算法、Turn 协议和 TTS 连接池。
- `docs/TECHNICAL_HIGHLIGHTS_AND_SAAS_EVOLUTION.md` 是另一条 SaaS 规划，不作为本轮实施顺序。

## 当前事实

- [x] 默认单测通过：197 个测试，0 失败，0 跳过；集成测试通过：21 个测试，0 失败，0 跳过。
- [x] 前端生产构建通过。
- [x] Actuator 已暴露 `health`、`info`、`metrics`、`prometheus`。
- [x] 语音 Handler 已记录部分 ASR、LLM、TTS、Turn 指标。
- [x] 测试基线可信：默认单测与需要 Redis/Spring 上下文的集成测试已分组，两组均无跳过项。
- [ ] RAG 运行可观测：知识库模块当前没有专属 Micrometer 指标。
- [ ] 监控闭环：仓库当前没有 `observability/`、Prometheus 抓取配置、Grafana Dashboard 和告警规则。
- [ ] RAG 来源可追溯：`QueryResponse` 当前只有答案、知识库 ID 和名称，没有来源片段与相似度。
- [ ] 实时 Turn 有明确协议：当前没有独立 `VoiceTurnCoordinator`，也没有统一的 `turnId/eventId/sequence` 约束。

## M0：可信基线 + 可观测第一版（现在做）

### 0.1 修复并冻结可信测试基线

- [x] 重写 `VoiceInterviewServiceTest` 的依赖装配，注入 `LlmProviderRegistry`、`VoiceEvaluateStreamProducer` 和评估 Repository，移除整类 `@Disabled`。
- [x] 逐项审计其余跳过测试，恢复占位测试，并把需要 Redis/Spring 上下文的场景纳入集成测试组。
- [x] 为外部依赖测试增加 `integration` Tag 和独立 `integrationTest` 命令，默认单测与集成测试边界清楚。
- [x] 使用 `--rerun-tasks` 重新执行后端测试，不依赖 `UP-TO-DATE` 结果。
- [x] 启动 PostgreSQL、Redis、RustFS 和后端，`/actuator/health` 返回 `UP`，`/actuator/prometheus` 可抓取。
- [x] 通过真实页面完成三条主链路：
  - [x] 文本面试提交回答并结束，会话最终为 `EVALUATED / COMPLETED`。
  - [x] 文档上传后状态为“已完成”，问答返回样本中的唯一答案 `Orchid-417`。
  - [x] 语音页面本轮已验证真实会话创建、开场 TTS、ASR ready、暂停/恢复/结束；用户确认此前人工“说话 → ASR 转写 → 提交”验收无异常。

本轮命令：`gradlew :app:test --no-daemon --rerun-tasks`；`gradlew :app:integrationTest --no-daemon --rerun-tasks`。

**0.1 状态：完成。** 默认单测无跳过，默认单测与集成测试边界明确，三条主链路均有自动化或人工验收依据。

完成门槛：默认单测无非预期跳过；三条主链路都有本轮终端或页面证据；失败路径不会被“构建成功”替代。

### 0.2 建立指标契约

- [ ] 盘点 Spring AI 2.0 已提供的模型、Token、工具和向量库 Observation，先复用框架指标。
- [ ] 新建 `common/metrics/`，集中管理指标名称、低基数标签和记录入口。
- [ ] 统一现有语音指标命名，并补齐：活跃会话、ASR ready、首音频、重连、丢音频和取消。
- [ ] 在 `AbstractStreamConsumer` 补齐任务吞吐、处理耗时、重试、恢复、Pending 年龄和 Stream backlog。
- [ ] 为 RAG 增加运行指标，但不改检索算法：
  - [ ] 向量化成功/失败、耗时和 Chunk 数；
  - [ ] 主检索/兜底检索耗时、命中数和无结果；
  - [ ] Query rewrite 成功/失败/跳过；
  - [ ] 回答耗时和失败。
- [ ] 为指标增加测试，验证名称、单位、状态口径和标签白名单。

标签红线：`sessionId`、`turnId`、`userId`、`knowledgeBaseId`、问题、Prompt、文档名和原文只能进入日志或 Trace，不能进入 Prometheus 标签。

完成门槛：每项指标都有名称、类型、单位、成功/失败口径、允许标签、Dashboard 面板和对应告警；不出现无界标签。

### 0.3 落地监控配置

- [ ] 新建 `observability/`：
  - [ ] `prometheus/`：抓取配置和规则加载；
  - [ ] `grafana/provisioning/`：数据源和 Dashboard 自动装载；
  - [ ] `grafana/dashboards/`：三张可导入看板；
  - [ ] `alerts/`：5—7 条带恢复动作的告警；
  - [ ] `experiments/`：故障实验步骤与结果。
- [ ] 在开发 Compose 中接入 Prometheus 和 Grafana，不影响现有应用依赖启动。
- [ ] 建立三张 Dashboard：实时语音体验、异步任务可靠性、AI/RAG 质量与成本。
- [ ] 告警至少覆盖 Turn 错误率、首音频 P95、ASR 重连、Stream backlog、最老 Pending、结构化输出失败和 RAG 错误。

完成门槛：重启环境后自动加载数据源、看板和规则；Prometheus target 为 UP；每条告警都有触发条件、持续窗口和恢复动作。

### 0.4 记录基线并完成故障闭环

- [ ] 使用同一配置完成至少 20 次正常语音 Turn，记录 ASR ready、首 Token、首音频、TTS、整轮耗时和成功率。
- [ ] 记录评估任务耗时、向量化耗时、RAG 检索耗时与无结果比例。
- [ ] 人为增加 TTS 延迟，验证首音频 P95/P99 和告警同步变化。
- [ ] 恢复 TTS，验证告警恢复且指标回落。
- [ ] 再完成 ASR 断连、异步任务失败两项实验，验证重连、重试、最终失败和积压可被定位。
- [ ] 保存命令、配置、时间窗口、截图、原始数据和结论；不只保存成功截图。

M0 最终验收：打开 Grafana 能定位一次完整语音面试的 ASR → LLM → TTS → 异步评估耗时；TTS 故障能触发告警并在恢复后回落；RAG 向量化失败和无结果比例可见。

## 后续里程碑（M0 验收后按顺序进入）

### M1：评估可信度

- [ ] 题目持久化 `competency`、0—4 级 Rubric、关键点、追问方向和来源。
- [ ] 每题评价保存命中 Rubric、回答证据、缺失点、事实风险和可执行建议。
- [ ] 单题失败隔离并使用有限并发；失败项不拖垮整份报告。
- [ ] 分离 Coverage 与总分，未回答题目不静默计零参与平均。
- [ ] 报告生成 3—5 个有依据和完成标准的下一轮训练任务。
- [ ] 用固定问答样例验证评分稳定性。

进入 M2 的条件：报告中的每个结论都能定位到回答证据，并明确展示覆盖率和失败项。

### M2：可信 RAG + PREP

- [ ] 新增 `RagSourceDTO`，让 `QueryResponse` 返回来源、Chunk、摘要和分数。
- [ ] Chunk metadata 补齐文档名、序号、章节和来源类型。
- [ ] 前端显示来源卡片、引用编号和可展开原文。
- [ ] 建立 30—50 个固定问题，验证前排命中、正确拒答、答案忠实和引用支持。
- [ ] 对比原问题、Query rewrite、动态 `topK`、阈值和字段过滤。
- [ ] 将用户资料用于 PREP 出题、Rubric 和追问；LIVE 不执行重型 RAG。

进入 M3 的条件：正常回答至少有一个可核验来源，无来源时明确拒答，固定评测集可重复运行。

### M3：实时 Turn 状态与 Fake Provider

- [ ] 抽取 `VoiceTurnCoordinator`、`VoiceSessionContext`、`VoiceOutboundWriter`、统一事件和指标组件。
- [ ] 建立 `LISTENING → READY_TO_SUBMIT → THINKING → SPEAKING → COMPLETED` 状态机，支持 `CANCELLED/FAILED`。
- [ ] 事件统一携带 `sessionId/turnId/eventId/sequence/eventType/createdAt`。
- [ ] 所有异步回调校验 `turnId`；取消或新 Turn 后丢弃旧结果。
- [ ] 实现 Fake ASR/LLM/TTS，覆盖超时、重复 final、乱序、空音频、断连和迟到返回。

进入 M4 的条件：不使用真实 API Key 也能稳定复现并验证取消、超时、乱序、断连和迟到结果。

### M4：实时性能

- [ ] 在 M0 基线和 M3 测试保护下实验二进制音频帧，并保留协议兼容窗口。
- [ ] 只有数据证明建连是长尾来源时，才实现有界 TTS 连接复用。
- [ ] 分句 TTS 采用有限并发、有界重排缓冲和按 sequence 发送。
- [ ] 用同一负载比较 P50/P95/P99、整轮 P95、网络字节与错误率。

完成门槛：取消和断连测试仍通过，音频无乱序，性能结论来自前后对照数据。

### M5：由证据触发的可靠性增强

- [ ] 仅在指标、实验或真实需求证明必要时选择：VAD、barge-in、断线恢复、Dead Letter、Outbox/Inbox、混合召回或 PREP/POST LangGraph。
- [ ] LangGraph 不进入实时 Turn 主路径。

## 本周期明确不做

- 不同时引入 RabbitMQ 和 Redis Stream。
- 不拆 Java/Python 微服务，不为技术名词引入多智能体。
- 不在基线前宣称性能提升，不在来源和测试集前宣称 RAG 更准确。
- 不把通用知识库聊天继续扩成与面试流程无关的独立产品。
- 不在 M0 期间并行推进 SaaS 租户、支付、Avatar、多语言和完整求职工作台。
