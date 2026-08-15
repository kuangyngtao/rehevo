package interview.guide.common.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("应用指标契约测试")
class ApplicationMetricsTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ApplicationMetrics metrics = new ApplicationMetrics(meterRegistry);

  @AfterEach
  void tearDown() {
    meterRegistry.close();
  }

  @Test
  @DisplayName("RAG 指标记录名称、单位和成功口径")
  void recordsRagMetricsWithExpectedNamesAndTags() {
    metrics.recordRagVectorization(TimeUnit.MILLISECONDS.toNanos(25), 4, ApplicationMetrics.Outcome.SUCCESS);
    metrics.recordRagRetrieval(TimeUnit.MILLISECONDS.toNanos(8), ApplicationMetrics.RetrievalPath.FALLBACK,
        0, ApplicationMetrics.Outcome.SUCCESS);
    metrics.recordRagAnswer(TimeUnit.MILLISECONDS.toNanos(40), ApplicationMetrics.Interaction.STREAM,
        ApplicationMetrics.Outcome.SUCCESS);

    assertThat(meterRegistry.get(AppMetricNames.RAG_VECTORIZATION)
        .tag(AppMetricNames.TAG_STATUS, "success").timer().count()).isEqualTo(1);
    assertThat(meterRegistry.get(AppMetricNames.RAG_VECTORIZATION_CHUNKS)
        .tag(AppMetricNames.TAG_STATUS, "success").summary().totalAmount()).isEqualTo(4.0);
    assertThat(meterRegistry.get(AppMetricNames.RAG_RETRIEVAL_NO_HIT)
        .tag(AppMetricNames.TAG_PATH, "fallback").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.get(AppMetricNames.RAG_RETRIEVAL_FALLBACK)
        .tag(AppMetricNames.TAG_STATUS, "success").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.get(AppMetricNames.RAG_ANSWER)
        .tag(AppMetricNames.TAG_INTERACTION, "stream")
        .tag(AppMetricNames.TAG_STATUS, "success").timer().count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Stream 指标保留有限流类型并暴露队列 Gauge")
  void recordsStreamMetricsWithBoundedStreamTag() {
    AtomicLong backlog = new AtomicLong(7);
    AtomicLong pending = new AtomicLong(2);
    AtomicLong oldestPendingIdleMillis = new AtomicLong(1_500);

    metrics.registerStreamGauges(
        interview.guide.common.constant.AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
        backlog, pending, oldestPendingIdleMillis
    );
    metrics.recordStreamEnqueued(
        interview.guide.common.constant.AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
        ApplicationMetrics.Outcome.SUCCESS
    );
    metrics.recordStreamTask(
        interview.guide.common.constant.AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
        ApplicationMetrics.Outcome.RECOVERED,
        TimeUnit.MILLISECONDS.toNanos(12)
    );

    assertThat(meterRegistry.get(AppMetricNames.ASYNC_STREAM_BACKLOG)
        .tag(AppMetricNames.TAG_STREAM, "knowledgebase_vectorization").gauge().value()).isEqualTo(7.0);
    assertThat(meterRegistry.get(AppMetricNames.ASYNC_STREAM_PENDING)
        .tag(AppMetricNames.TAG_STREAM, "knowledgebase_vectorization").gauge().value()).isEqualTo(2.0);
    assertThat(meterRegistry.get(AppMetricNames.ASYNC_STREAM_OLDEST_PENDING_IDLE)
        .tag(AppMetricNames.TAG_STREAM, "knowledgebase_vectorization").gauge().value()).isEqualTo(1_500.0);
    assertThat(meterRegistry.get(AppMetricNames.ASYNC_STREAM_TASK)
        .tag(AppMetricNames.TAG_STATUS, "recovered").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("业务指标不允许高基数标签")
  void exposesOnlyTheApprovedLowCardinalityTagKeys() {
    metrics.recordVoiceLlmCall(true, ApplicationMetrics.Outcome.SUCCESS);
    metrics.recordVoiceTurnStage(
        ApplicationMetrics.VoiceTurnStage.FIRST_AUDIO,
        ApplicationMetrics.VoiceTurnMode.STREAM,
        TimeUnit.MILLISECONDS.toNanos(120),
        ApplicationMetrics.Outcome.SUCCESS
    );
    metrics.recordVoiceError(ApplicationMetrics.VoiceErrorStage.TURN);
    metrics.recordRagRewrite(ApplicationMetrics.Outcome.SKIPPED);

    Set<String> allowed = Set.of(
        AppMetricNames.TAG_STATUS,
        AppMetricNames.TAG_STREAM,
        AppMetricNames.TAG_PATH,
        AppMetricNames.TAG_INTERACTION,
        AppMetricNames.TAG_STREAMING,
        AppMetricNames.TAG_STAGE,
        AppMetricNames.TAG_MODE
    );
    assertThat(meterRegistry.getMeters())
        .extracting(Meter::getId)
        .flatExtracting(id -> id.getTags())
        .extracting(tag -> tag.getKey())
        .allMatch(allowed::contains);
    assertThat(meterRegistry.get(AppMetricNames.VOICE_TURN_STAGE_LATENCY)
        .tag(AppMetricNames.TAG_STAGE, "first_audio")
        .tag(AppMetricNames.TAG_MODE, "stream")
        .tag(AppMetricNames.TAG_STATUS, "success")
        .timer().count()).isEqualTo(1);
  }
}
