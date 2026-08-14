package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("语音面试提示词服务测试")
class VoiceInterviewPromptServiceTest {

  private final PromptSanitizer promptSanitizer = mock(PromptSanitizer.class);
  private final VoiceInterviewPromptService service = new VoiceInterviewPromptService(promptSanitizer);

  @Test
  @DisplayName("技能与简历上下文被安全写入系统提示词")
  void generatePromptWithSkillAndResume() {
    when(promptSanitizer.sanitize("原始简历")).thenReturn("安全简历");
    when(promptSanitizer.wrapWithDelimiters("resume", "安全简历"))
        .thenReturn("<resume>安全简历</resume>");

    String prompt = service.generateSystemPromptWithContext("java-backend", "原始简历");

    assertThat(prompt)
        .contains("java-backend")
        .contains("每轮只问 1 个主问题")
        .contains("<resume>安全简历</resume>")
        .endsWith(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
  }

  @Test
  @DisplayName("无技能和简历时仍保留语音约束与安全指令")
  void generateMinimalPrompt() {
    String prompt = service.generateSystemPromptWithContext(null, null);

    assertThat(prompt)
        .contains("语音面试输出约束")
        .doesNotContain("候选人简历内容")
        .endsWith(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
  }
}
