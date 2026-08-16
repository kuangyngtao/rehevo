package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("混合检索 RRF 融合测试")
class HybridRetrievalServiceTest {

  @Test
  @DisplayName("两路同时命中的文档应排在仅单路命中的文档之前")
  void documentHitByBothRankingsComesFirst() {
    List<Document> vector = List.of(document("a"), document("b"), document("c"));
    List<Document> lexical = List.of(document("b"), document("d"), document("a"));

    List<Document> result = HybridRetrievalService.reciprocalRankFusion(vector, lexical, 60, 3.0, 1.0, 4);

    assertThat(result).extracting(Document::getId).containsExactly("a", "b", "c", "d");
    assertThat(result.get(1).getMetadata())
        .containsEntry("retrieval_vector_rank", 2)
        .containsEntry("retrieval_lexical_rank", 1)
        .containsEntry("retrieval_sources", List.of("vector", "lexical"));
  }

  @Test
  @DisplayName("RRF 相同分数使用文档ID稳定排序")
  void equalScoresUseStableDocumentIdOrder() {
    List<Document> result = HybridRetrievalService.reciprocalRankFusion(
        List.of(document("b")), List.of(document("a")), 60, 1.0, 1.0, 2);

    assertThat(result).extracting(Document::getId).containsExactly("a", "b");
  }

  private Document document(String id) {
    return Document.builder().id(id).text("content-" + id).metadata(Map.of()).score(0.8).build();
  }
}
