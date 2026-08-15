package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 批量检索评测响应，保留逐题证据以便离线计算 Recall、MRR 和 nDCG。
 */
public record RetrievalEvaluationResponse(
    List<RetrievalEvaluationItem> items
) {
    public record RetrievalEvaluationItem(
        String question,
        String retrievalQuery,
        List<QueryResponse.RetrievalEvidence> evidence
    ) {}
}
