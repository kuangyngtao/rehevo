package interview.guide.common.metrics;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

/**
 * 业务指标记录入口。
 *
 * <p>这里仅暴露具有固定标签集合的方法。调用方无法把 sessionId、问题、文档名等高基数字段
 * 写入 Prometheus 标签。</p>
 */
@Component
public class ApplicationMetrics {

  private final MeterRegistry meterRegistry;
  private final Map<String, Boolean> registeredGauges = new ConcurrentHashMap<>();

  public ApplicationMetrics(@Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordRagVectorization(long elapsedNanos, int chunks, Outcome outcome) {
    Tags tags = statusTags(outcome);
    increment(AppMetricNames.RAG_VECTORIZATION_TOTAL, tags);
    record(AppMetricNames.RAG_VECTORIZATION, tags, elapsedNanos, TimeUnit.NANOSECONDS);
    if (meterRegistry != null && outcome == Outcome.SUCCESS) {
      meterRegistry.summary(AppMetricNames.RAG_VECTORIZATION_CHUNKS, tags).record(Math.max(0, chunks));
    }
  }

  public void recordRagRetrieval(long elapsedNanos, RetrievalPath path, int hits, Outcome outcome) {
    Tags tags = Tags.of(AppMetricNames.TAG_STATUS, outcome.value(), AppMetricNames.TAG_PATH, path.value());
    increment(AppMetricNames.RAG_RETRIEVAL_TOTAL, tags);
    record(AppMetricNames.RAG_RETRIEVAL, tags, elapsedNanos, TimeUnit.NANOSECONDS);
    if (meterRegistry != null && outcome == Outcome.SUCCESS) {
      meterRegistry.summary(AppMetricNames.RAG_RETRIEVAL_HITS, Tags.of(AppMetricNames.TAG_PATH, path.value()))
          .record(Math.max(0, hits));
      if (hits == 0) {
        increment(AppMetricNames.RAG_RETRIEVAL_NO_HIT, Tags.of(AppMetricNames.TAG_PATH, path.value()));
      }
    }
    if (path == RetrievalPath.FALLBACK) {
      increment(AppMetricNames.RAG_RETRIEVAL_FALLBACK, statusTags(outcome));
    }
  }

  public void recordRagRewrite(Outcome outcome) {
    increment(AppMetricNames.RAG_QUERY_REWRITE, statusTags(outcome));
  }

  public void recordRagAnswer(long elapsedNanos, Interaction interaction, Outcome outcome) {
    Tags tags = Tags.of(AppMetricNames.TAG_STATUS, outcome.value(), AppMetricNames.TAG_INTERACTION, interaction.value());
    increment(AppMetricNames.RAG_ANSWER_TOTAL, tags);
    record(AppMetricNames.RAG_ANSWER, tags, elapsedNanos, TimeUnit.NANOSECONDS);
  }

  public void recordStreamEnqueued(String streamKey, Outcome outcome) {
    increment(AppMetricNames.ASYNC_STREAM_ENQUEUED, streamTags(streamKey).and(AppMetricNames.TAG_STATUS, outcome.value()));
  }

  public void recordStreamTask(String streamKey, Outcome outcome, long elapsedNanos) {
    Tags tags = streamTags(streamKey).and(AppMetricNames.TAG_STATUS, outcome.value());
    increment(AppMetricNames.ASYNC_STREAM_TASK, tags);
    record(AppMetricNames.ASYNC_STREAM_PROCESSING, tags, elapsedNanos, TimeUnit.NANOSECONDS);
  }

  public void registerStreamGauges(String streamKey, AtomicLong backlog, AtomicLong pending, AtomicLong oldestPendingIdleMillis) {
    Tags tags = streamTags(streamKey);
    registerGauge(AppMetricNames.ASYNC_STREAM_BACKLOG, tags, backlog, AtomicLong::doubleValue);
    registerGauge(AppMetricNames.ASYNC_STREAM_PENDING, tags, pending, AtomicLong::doubleValue);
    registerGauge(AppMetricNames.ASYNC_STREAM_OLDEST_PENDING_IDLE, tags, oldestPendingIdleMillis, AtomicLong::doubleValue);
  }

  public void registerVoiceActiveSessionsGauge(Map<?, ?> sessions) {
    registerGauge(AppMetricNames.VOICE_ACTIVE_SESSIONS, Tags.empty(), sessions, value -> value.size());
  }

  public void recordVoiceAsrReady() {
    increment(AppMetricNames.VOICE_ASR_READY, statusTags(Outcome.SUCCESS));
  }

  public void recordVoiceAsrReconnect(Outcome outcome) {
    increment(AppMetricNames.VOICE_ASR_RECONNECT, statusTags(outcome));
  }

  public void recordVoiceDroppedAudio() {
    increment(AppMetricNames.VOICE_ASR_DROPPED_AUDIO, statusTags(Outcome.DISCARDED));
  }

  public void recordVoiceTimer(VoiceTimer timer, long elapsed, TimeUnit unit, Outcome outcome) {
    record(timer.metricName(), statusTags(outcome), elapsed, unit);
  }

  public void recordVoiceFinalSegment() {
    increment(AppMetricNames.VOICE_ASR_FINAL_SEGMENTS, statusTags(Outcome.SUCCESS));
  }

  public void recordVoiceLlmCall(boolean streaming, Outcome outcome) {
    increment(AppMetricNames.VOICE_LLM_CALLS,
        Tags.of(AppMetricNames.TAG_STATUS, outcome.value(), AppMetricNames.TAG_STREAMING, Boolean.toString(streaming)));
  }

  public void recordVoiceEmptyAudio() {
    increment(AppMetricNames.VOICE_TTS_EMPTY_AUDIO, statusTags(Outcome.DISCARDED));
  }

  public void recordVoiceTurn(Outcome outcome) {
    increment(AppMetricNames.VOICE_TURN_COMPLETED, statusTags(outcome));
  }

  /**
   * 记录语音回合关键阶段。turnId 仅写日志关联，绝不作为 Prometheus 标签。
   */
  public void recordVoiceTurnStage(VoiceTurnStage stage, VoiceTurnMode mode,
                                   long elapsedNanos, Outcome outcome) {
    Tags tags = Tags.of(
        AppMetricNames.TAG_STAGE, stage.value(),
        AppMetricNames.TAG_MODE, mode.value(),
        AppMetricNames.TAG_STATUS, outcome.value()
    );
    increment(AppMetricNames.VOICE_TURN_STAGE_TOTAL, tags);
    if (elapsedNanos >= 0) {
      record(AppMetricNames.VOICE_TURN_STAGE_LATENCY, tags, elapsedNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void recordVoiceCancellation() {
    increment(AppMetricNames.VOICE_TURN_CANCELLED, statusTags(Outcome.DISCARDED));
  }

  public void recordVoiceError(VoiceErrorStage stage) {
    increment(AppMetricNames.VOICE_ERRORS, Tags.of(AppMetricNames.TAG_STAGE, stage.value()));
  }

  private void increment(String metricName, Tags tags) {
    if (meterRegistry != null) {
      Counter.builder(metricName).tags(tags).register(meterRegistry).increment();
    }
  }

  private void record(String metricName, Tags tags, long elapsed, TimeUnit unit) {
    if (meterRegistry != null) {
      Timer.builder(metricName).tags(tags).register(meterRegistry).record(Math.max(0, elapsed), unit);
    }
  }

  private <T> void registerGauge(String metricName, Tags tags, T state, ToDoubleFunction<T> valueFunction) {
    if (meterRegistry == null) {
      return;
    }
    String key = metricName + tags;
    if (registeredGauges.putIfAbsent(key, Boolean.TRUE) == null) {
      Gauge.builder(metricName, state, valueFunction).tags(tags).register(meterRegistry);
    }
  }

  private Tags statusTags(Outcome outcome) {
    return Tags.of(AppMetricNames.TAG_STATUS, outcome.value());
  }

  private Tags streamTags(String streamKey) {
    return Tags.of(AppMetricNames.TAG_STREAM, StreamType.from(streamKey).value());
  }

  public enum Outcome {
    SUCCESS("success"),
    FAILURE("failure"),
    SKIPPED("skipped"),
    RETRY("retry"),
    RECOVERED("recovered"),
    DISCARDED("discarded");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum RetrievalPath {
    PRIMARY("primary"),
    FALLBACK("fallback");

    private final String value;

    RetrievalPath(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum Interaction {
    SYNC("sync"),
    STREAM("stream");

    private final String value;

    Interaction(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum VoiceTimer {
    ASR_MERGE_WAIT(AppMetricNames.VOICE_ASR_MERGE_WAIT),
    LLM_FIRST_TOKEN(AppMetricNames.VOICE_LLM_FIRST_TOKEN),
    FIRST_AUDIO(AppMetricNames.VOICE_FIRST_AUDIO),
    LLM_DURATION(AppMetricNames.VOICE_LLM_DURATION),
    TTS_DURATION(AppMetricNames.VOICE_TTS_DURATION),
    TURN_DURATION(AppMetricNames.VOICE_TURN_DURATION);

    private final String metricName;

    VoiceTimer(String metricName) {
      this.metricName = metricName;
    }

    public String metricName() {
      return metricName;
    }
  }

  public enum VoiceErrorStage {
    TURN("turn");

    private final String value;

    VoiceErrorStage(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  private enum StreamType {
    KNOWLEDGEBASE_VECTORIZATION(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY, "knowledgebase_vectorization"),
    RESUME_ANALYSIS(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY, "resume_analysis"),
    INTERVIEW_EVALUATION(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY, "interview_evaluation"),
    VOICE_EVALUATION(AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY, "voice_evaluation"),
    UNKNOWN("", "unknown");

    private final String streamKey;
    private final String value;

    StreamType(String streamKey, String value) {
      this.streamKey = streamKey;
      this.value = value;
    }

    static StreamType from(String streamKey) {
      for (StreamType type : values()) {
        if (type.streamKey.equals(streamKey)) {
          return type;
        }
      }
      return UNKNOWN;
    }

    String value() {
      return value;
    }
  }

  public enum VoiceTurnStage {
    ASR_FINAL_TO_SUBMIT("asr_final_to_submit"),
    LLM_FIRST_TOKEN("llm_first_token"),
    FIRST_AUDIO("first_audio"),
    TURN_FINISHED("turn_finished");

    private final String value;

    VoiceTurnStage(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum VoiceTurnMode {
    STREAM("stream"),
    FALLBACK("fallback"),
    NON_STREAMING("non_streaming"),
    UNKNOWN("unknown");

    private final String value;

    VoiceTurnMode(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }
}
