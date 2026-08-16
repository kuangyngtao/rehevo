package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
public class QwenRerankService {
  private final KnowledgeBaseQueryProperties properties;
  private final ApplicationMetrics applicationMetrics;
  private final RestClient.Builder restClientBuilder;

  public QwenRerankService(KnowledgeBaseQueryProperties properties,
                           ApplicationMetrics applicationMetrics,
                           RestClient.Builder restClientBuilder) {
    this.properties = properties;
    this.applicationMetrics = applicationMetrics;
    this.restClientBuilder = restClientBuilder;
  }

  public List<Document> rerank(String originalQuestion, List<Document> candidates, int topK) {
    if (candidates.isEmpty()) {
      return candidates;
    }
    KnowledgeBaseQueryProperties.Rerank config = properties.getRerank();
    if (!config.isEnabled() || config.getWorkspaceId().isBlank() || config.getApiKey().isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
          "交叉编码器重排未配置：需要启用 rerank 并提供百炼 WorkspaceId 与 API Key");
    }

    long startNanos = System.nanoTime();
    try {
      RerankRequest request = new RerankRequest(
          config.getModel(), candidates.stream().map(Document::getText).toList(),
          originalQuestion, Math.min(topK, candidates.size()), config.getInstruct());
      URI endpoint = URI.create("https://" + config.getWorkspaceId()
          + ".cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks");
      RerankResponse response = restClientBuilder.clone()
          .defaultHeader("Authorization", "Bearer " + config.getApiKey())
          .build()
          .post()
          .uri(endpoint)
          .body(request)
          .retrieve()
          .body(RerankResponse.class);
      if (response == null || response.results() == null) {
        throw new IllegalStateException("重排服务返回空结果");
      }
      List<Document> reranked = response.results().stream()
          .filter(result -> result.index() >= 0 && result.index() < candidates.size())
          .map(result -> withRerankMetadata(candidates.get(result.index()), result.relevanceScore()))
          .toList();
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.RERANK,
          System.nanoTime() - startNanos, reranked.size(), ApplicationMetrics.Outcome.SUCCESS);
      return reranked;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.RERANK,
          System.nanoTime() - startNanos, 0, ApplicationMetrics.Outcome.FAILURE);
      log.error("qwen3-rerank 调用失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "交叉编码器重排失败");
    }
  }

  private Document withRerankMetadata(Document document, double score) {
    var metadata = new HashMap<>(document.getMetadata());
    metadata.put("retrieval_rerank_score", score);
    return Document.builder().id(document.getId()).text(document.getText())
        .metadata(metadata).score(score).build();
  }

  private record RerankRequest(String model, List<String> documents, String query,
                               int top_n, String instruct) {
  }

  private record RerankResponse(List<RerankResult> results) {
  }

  private record RerankResult(int index,
                              @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore) {
  }
}
