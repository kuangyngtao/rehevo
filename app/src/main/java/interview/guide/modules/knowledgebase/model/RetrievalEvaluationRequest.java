package interview.guide.modules.knowledgebase.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 仅用于离线 RAG 检索评测的批量请求，不触发回答生成或问题计数。
 */
public record RetrievalEvaluationRequest(
    @NotEmpty(message = "至少提供一条评测问题")
    @Size(max = 100, message = "单次最多评测100条问题")
    List<@Valid QueryRequest> queries,
    Boolean rewrite
) {
    public boolean useRewrite() {
        return rewrite == null || rewrite;
    }
}
