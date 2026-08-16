package interview.guide.modules.knowledgebase.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 固定测评集的批量回答请求。评测调用不应写入用户提问计数。
 */
public record AnswerEvaluationRequest(
    @NotEmpty(message = "至少提供一个评测问题")
    @Size(max = 30, message = "单次最多评测30个问题")
    List<@Valid QueryRequest> queries,
    Boolean rewrite
) {
    public boolean useRewrite() {
        return rewrite == null || rewrite;
    }
}
