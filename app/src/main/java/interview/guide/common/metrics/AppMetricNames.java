package interview.guide.common.metrics;

/**
 * 应用业务指标的唯一命名目录。
 *
 * <p>Micrometer Timer 在 Prometheus 中会自动暴露为 {@code _seconds}，因此 Java
 * 指标名不重复添加 seconds 后缀。业务主键、用户输入和错误详情只能进入日志或 Trace，
 * 不得作为标签。</p>
 */
public final class AppMetricNames {

  private AppMetricNames() {
  }

  public static final String TAG_STATUS = "status";
  public static final String TAG_STREAM = "stream";
  public static final String TAG_PATH = "path";
  public static final String TAG_INTERACTION = "interaction";
  public static final String TAG_STREAMING = "streaming";
  public static final String TAG_STAGE = "stage";

  public static final String RAG_VECTORIZATION = "app.rag.vectorization";
  public static final String RAG_VECTORIZATION_TOTAL = "app.rag.vectorization.total";
  public static final String RAG_VECTORIZATION_CHUNKS = "app.rag.vectorization.chunks";
  public static final String RAG_RETRIEVAL = "app.rag.retrieval";
  public static final String RAG_RETRIEVAL_TOTAL = "app.rag.retrieval.total";
  public static final String RAG_RETRIEVAL_HITS = "app.rag.retrieval.hits";
  public static final String RAG_RETRIEVAL_NO_HIT = "app.rag.retrieval.no_hit";
  public static final String RAG_RETRIEVAL_FALLBACK = "app.rag.retrieval.fallback";
  public static final String RAG_QUERY_REWRITE = "app.rag.query_rewrite";
  public static final String RAG_ANSWER = "app.rag.answer";
  public static final String RAG_ANSWER_TOTAL = "app.rag.answer.total";

  public static final String ASYNC_STREAM_ENQUEUED = "app.async.stream.enqueued";
  public static final String ASYNC_STREAM_TASK = "app.async.stream.task";
  public static final String ASYNC_STREAM_PROCESSING = "app.async.stream.processing";
  public static final String ASYNC_STREAM_BACKLOG = "app.async.stream.backlog";
  public static final String ASYNC_STREAM_PENDING = "app.async.stream.pending";
  public static final String ASYNC_STREAM_OLDEST_PENDING_IDLE = "app.async.stream.oldest_pending_idle";

  public static final String VOICE_ACTIVE_SESSIONS = "app.voice.interview.active_sessions";
  public static final String VOICE_ASR_READY = "app.voice.interview.asr.ready";
  public static final String VOICE_ASR_RECONNECT = "app.voice.interview.asr.reconnect";
  public static final String VOICE_ASR_DROPPED_AUDIO = "app.voice.interview.asr.dropped_audio";
  public static final String VOICE_ASR_FINAL_SEGMENTS = "app.voice.interview.asr.final_segments";
  public static final String VOICE_ASR_MERGE_WAIT = "app.voice.interview.asr.merge_wait";
  public static final String VOICE_LLM_FIRST_TOKEN = "app.voice.interview.llm.first_token_latency";
  public static final String VOICE_FIRST_AUDIO = "app.voice.interview.first_audio_latency";
  public static final String VOICE_LLM_DURATION = "app.voice.interview.llm.duration";
  public static final String VOICE_LLM_CALLS = "app.voice.interview.llm.calls";
  public static final String VOICE_TTS_DURATION = "app.voice.interview.tts.duration";
  public static final String VOICE_TTS_EMPTY_AUDIO = "app.voice.interview.tts.empty_audio";
  public static final String VOICE_TURN_DURATION = "app.voice.interview.turn.duration";
  public static final String VOICE_TURN_COMPLETED = "app.voice.interview.turn.completed";
  public static final String VOICE_TURN_CANCELLED = "app.voice.interview.turn.cancelled";
  public static final String VOICE_ERRORS = "app.voice.interview.errors";
}
