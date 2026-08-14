package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试暂停与恢复测试")
class VoiceInterviewServicePauseTest {

  @Mock
  private VoiceInterviewSessionRepository sessionRepository;
  @Mock
  private VoiceInterviewMessageRepository messageRepository;
  @Mock
  private VoiceInterviewEvaluationRepository evaluationRepository;
  @Mock
  private RedissonClient redissonClient;
  @Mock
  private VoiceInterviewProperties properties;
  @Mock
  private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RBucket<VoiceInterviewSessionEntity> bucket;

  private VoiceInterviewService service;

  @BeforeEach
  void setUp() {
    service = new VoiceInterviewService(
        sessionRepository,
        messageRepository,
        evaluationRepository,
        redissonClient,
        properties,
        voiceEvaluateStreamProducer,
        llmProviderRegistry
    );
    lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
  }

  @Test
  @DisplayName("进行中的会话可以暂停并清除缓存")
  void pauseInProgressSession() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .status(VoiceInterviewSessionStatus.IN_PROGRESS)
        .build();
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

    service.pauseSession("1", "user_initiated");

    assertEquals(VoiceInterviewSessionStatus.PAUSED, session.getStatus());
    assertNotNull(session.getPausedAt());
    verify(sessionRepository).save(session);
    verify(bucket).delete();
  }

  @Test
  @DisplayName("非进行中会话不能暂停")
  void rejectPauseForCompletedSession() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .status(VoiceInterviewSessionStatus.COMPLETED)
        .build();
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

    assertThrows(BusinessException.class, () -> service.pauseSession("1", "user_initiated"));

    verify(sessionRepository, never()).save(session);
  }

  @Test
  @DisplayName("暂停会话可以恢复并重建一小时缓存")
  void resumePausedSession() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .roleType("java-backend")
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
        .status(VoiceInterviewSessionStatus.PAUSED)
        .plannedDuration(30)
        .build();
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
    when(sessionRepository.save(session)).thenReturn(session);
    when(messageRepository.countBySessionId(1L)).thenReturn(2L);

    var response = service.resumeSession("1");

    assertEquals(VoiceInterviewSessionStatus.IN_PROGRESS, session.getStatus());
    assertEquals("IN_PROGRESS", response.getStatus());
    assertNotNull(session.getResumedAt());
    verify(bucket).set(session, Duration.ofHours(1));
  }
}
