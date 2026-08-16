package interview.guide.modules.knowledgebase.service;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {
  private final KnowledgeBaseVectorService vectorService;
  private final VectorRepository vectorRepository;
  private final QwenRerankService rerankService;
  private final KnowledgeBaseQueryProperties properties;
  private final ApplicationMetrics applicationMetrics;

  public List<Document> retrieve(String query, String originalQuestion, List<Long> knowledgeBaseIds,
                                 int topK, double minScore, RetrievalMode mode) {
    int vectorLimit = mode == RetrievalMode.VECTOR
        ? topK : Math.max(topK, properties.getHybrid().getVectorCandidates());
    long vectorStart = System.nanoTime();
    List<Document> vectorDocuments = vectorService.similaritySearch(
        query, knowledgeBaseIds, vectorLimit, minScore);
    applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.VECTOR,
        System.nanoTime() - vectorStart, vectorDocuments.size(), ApplicationMetrics.Outcome.SUCCESS);
    if (mode == RetrievalMode.VECTOR) {
      return addFinalRanks(addVectorRanks(vectorDocuments.stream().limit(topK).toList()));
    }

    List<Document> lexicalDocuments = lexicalSearch(query, knowledgeBaseIds);
    long fusionStart = System.nanoTime();
    List<Document> fused = reciprocalRankFusion(vectorDocuments, lexicalDocuments,
        properties.getHybrid().getRrfK(), properties.getHybrid().getVectorWeight(),
        properties.getHybrid().getLexicalWeight(), properties.getHybrid().getFusionCandidates());
    applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.FUSION,
        System.nanoTime() - fusionStart, fused.size(), ApplicationMetrics.Outcome.SUCCESS);
    List<Document> result = mode == RetrievalMode.HYBRID_RERANK
        ? rerankService.rerank(originalQuestion, fused, topK)
        : fused.stream().limit(topK).toList();
    return addFinalRanks(result);
  }

  private List<Document> lexicalSearch(String query, List<Long> knowledgeBaseIds) {
    long startNanos = System.nanoTime();
    try {
      List<Document> documents = vectorRepository.lexicalSearch(
          query, knowledgeBaseIds, properties.getHybrid().getLexicalCandidates());
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.LEXICAL,
          System.nanoTime() - startNanos, documents.size(), ApplicationMetrics.Outcome.SUCCESS);
      return documents;
    } catch (Exception e) {
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.LEXICAL,
          System.nanoTime() - startNanos, 0, ApplicationMetrics.Outcome.FAILURE);
      log.warn("字符三元组检索失败，本次仅使用向量候选: {}", e.getMessage());
      return List.of();
    }
  }

  static List<Document> reciprocalRankFusion(List<Document> vectorDocuments,
                                              List<Document> lexicalDocuments,
                                              int rrfK, double vectorWeight,
                                              double lexicalWeight, int limit) {
    Map<String, Candidate> candidates = new LinkedHashMap<>();
    addRanking(candidates, vectorDocuments, true, rrfK, vectorWeight);
    addRanking(candidates, lexicalDocuments, false, rrfK, lexicalWeight);
    return candidates.values().stream()
        .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
            .thenComparing(candidate -> candidate.document().getId()))
        .limit(limit)
        .map(Candidate::toDocument)
        .toList();
  }

  private static void addRanking(Map<String, Candidate> candidates, List<Document> documents,
                                 boolean vector, int rrfK, double weight) {
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      int rank = index + 1;
      Candidate candidate = candidates.computeIfAbsent(document.getId(), id -> new Candidate(document));
      candidate.addRank(vector, rank, weight / (rrfK + rank));
    }
  }

  private List<Document> addFinalRanks(List<Document> documents) {
    List<Document> ranked = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      var metadata = new HashMap<>(document.getMetadata());
      metadata.put("retrieval_final_rank", index + 1);
      ranked.add(Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(document.getScore()).build());
    }
    return ranked;
  }

  private List<Document> addVectorRanks(List<Document> documents) {
    List<Document> ranked = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      var metadata = new HashMap<>(document.getMetadata());
      metadata.put("retrieval_vector_rank", index + 1);
      metadata.put("retrieval_vector_score", document.getScore());
      metadata.put("retrieval_sources", List.of("vector"));
      ranked.add(Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(document.getScore()).build());
    }
    return ranked;
  }

  private static final class Candidate {
    private Document document;
    private Integer vectorRank;
    private Integer lexicalRank;
    private Double vectorScore;
    private double rrfScore;

    private Candidate(Document document) {
      this.document = document;
    }

    private void addRank(boolean vector, int rank, double contribution) {
      if (vector) {
        vectorRank = rank;
        vectorScore = document.getScore();
      } else {
        lexicalRank = rank;
      }
      rrfScore += contribution;
    }

    private double rrfScore() {
      return rrfScore;
    }

    private Document document() {
      return document;
    }

    private Document toDocument() {
      var metadata = new HashMap<>(document.getMetadata());
      if (vectorRank != null) {
        metadata.put("retrieval_vector_rank", vectorRank);
        metadata.put("retrieval_vector_score", vectorScore);
      }
      if (lexicalRank != null) {
        metadata.put("retrieval_lexical_rank", lexicalRank);
      }
      metadata.put("retrieval_rrf_score", rrfScore);
      List<String> sources = new ArrayList<>();
      if (vectorRank != null) {
        sources.add("vector");
      }
      if (lexicalRank != null) {
        sources.add("lexical");
      }
      metadata.put("retrieval_sources", sources);
      return Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(rrfScore).build();
    }
  }
}
