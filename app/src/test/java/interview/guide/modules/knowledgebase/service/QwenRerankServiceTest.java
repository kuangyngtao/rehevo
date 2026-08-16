package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.metrics.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("千问交叉编码器重排测试")
class QwenRerankServiceTest {

  @Test
  @DisplayName("使用原问题调用重排接口并按返回索引映射候选")
  void reranksCandidatesByReturnedIndexes() {
    KnowledgeBaseQueryProperties properties = properties(true);
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    QwenRerankService service = new QwenRerankService(
        properties, new ApplicationMetrics(new SimpleMeterRegistry()), builder);
    server.expect(requestTo(
            "https://test-workspace.cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(content().json("""
            {
              "model": "qwen3-rerank",
              "documents": ["候选A", "候选B"],
              "query": "原始问题",
              "top_n": 2
            }
            """, false))
        .andRespond(withSuccess("""
            {
              "results": [
                {"index": 1, "relevance_score": 0.91},
                {"index": 0, "relevance_score": 0.42}
              ]
            }
            """, MediaType.APPLICATION_JSON));

    List<Document> result = service.rerank(
        "原始问题", List.of(document("a", "候选A"), document("b", "候选B")), 2);

    assertThat(result).extracting(Document::getId).containsExactly("b", "a");
    assertThat(result.getFirst().getScore()).isEqualTo(0.91);
    assertThat(result.getFirst().getMetadata()).containsEntry("retrieval_rerank_score", 0.91);
    server.verify();
  }

  @Test
  @DisplayName("缺少运行配置时拒绝伪装成已执行重排")
  void rejectsRerankWhenRuntimeConfigurationIsMissing() {
    QwenRerankService service = new QwenRerankService(
        properties(false), new ApplicationMetrics(new SimpleMeterRegistry()), RestClient.builder());

    assertThatThrownBy(() -> service.rerank("问题", List.of(document("a", "候选")), 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("交叉编码器重排未配置");
  }

  private KnowledgeBaseQueryProperties properties(boolean enabled) {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getRerank().setEnabled(enabled);
    properties.getRerank().setWorkspaceId(enabled ? "test-workspace" : "");
    properties.getRerank().setApiKey(enabled ? "test-key" : "");
    return properties;
  }

  private Document document(String id, String text) {
    return Document.builder().id(id).text(text).metadata(Map.of()).score(0.5).build();
  }
}
