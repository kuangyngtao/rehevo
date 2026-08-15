package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试 LLM 服务测试")
class DashscopeLlmServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private VoiceInterviewPromptService promptService;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PromptSanitizer promptSanitizer;

  private DashscopeLlmService service;
  private VoiceInterviewSessionEntity session;

  @BeforeEach
  void setUp() {
    service = new DashscopeLlmService(
        llmProviderRegistry,
        promptService,
        resumeRepository,
        new VoiceInterviewProperties(),
        promptSanitizer
    );
    session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .skillId("java-backend")
        .llmProvider("dashscope")
        .build();
    when(promptService.generateSystemPromptWithContext("java-backend", null))
        .thenReturn("system prompt");
    when(promptSanitizer.sanitize("请介绍项目")).thenReturn("请介绍项目");
    when(promptSanitizer.wrapWithDelimiters("input", "请介绍项目"))
        .thenReturn("<input>请介绍项目</input>");
  }

  @Test
  @DisplayName("模型超时返回稳定的用户提示")
  void mapTimeoutToUserMessage() {
    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("request timeout"));

    String result = service.chat("请介绍项目", session, List.of());

    assertThat(result).isEqualTo("AI 服务响应超时，请稍后重试");
    verify(llmProviderRegistry).getVoiceChatClient("dashscope");
  }

  @Test
  @DisplayName("模型认证失败返回可操作提示")
  void mapAuthenticationFailureToUserMessage() {
    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("403 Authentication failed"));

    String result = service.chat("请介绍项目", session, List.of());

    assertThat(result).isEqualTo("AI 服务认证失败，请检查 API Key 配置");
  }

  @Test
  @DisplayName("流式聚合空 Chunk 异常允许降级为非流式调用")
  void identifiesStreamingAggregationFailure() {
    assertThat(DashscopeLlmService.isStreamingAggregationFailure(
        new IllegalStateException("stream failed", new NoSuchElementException("No value present"))
    )).isTrue();
    assertThat(DashscopeLlmService.isStreamingAggregationFailure(
        new IllegalStateException("request timeout")
    )).isFalse();

    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("request timeout"));
    assertThat(service.chat("请介绍项目", session, List.of()))
        .isEqualTo("AI 服务响应超时，请稍后重试");
  }
}
