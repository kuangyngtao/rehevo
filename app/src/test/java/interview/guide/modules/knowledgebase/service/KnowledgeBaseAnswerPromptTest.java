package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库回答 Prompt 证据约束")
class KnowledgeBaseAnswerPromptTest {

  @Test
  @DisplayName("系统 Prompt 要求每项结论有依据并禁止无依据扩展")
  void shouldConstrainEveryClaimToRetrievedContext() throws IOException {
    String prompt = new ClassPathResource("prompts/knowledgebase-query-system.st")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(prompt)
        .contains("每个事实、因果关系、风险和建议都必须能在知识库内容中找到直接依据")
        .contains("禁止无依据扩展")
        .contains("优先使用 1 个短段落或 2-4 个要点")
        .doesNotContain("准确、详尽")
        .doesNotContain("多角度分析");
  }

  @Test
  @DisplayName("用户 Prompt 保留上下文边界并要求直接回答")
  void shouldKeepContextBoundaryAndDirectAnswerInstruction() throws IOException {
    String prompt = new ClassPathResource("prompts/knowledgebase-query-user.st")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(prompt)
        .contains("---文档内容开始---")
        .contains("---文档内容结束---")
        .contains("省略无依据的常识、推测和延伸建议")
        .contains("先给问题直接要求的结论");
  }
}
