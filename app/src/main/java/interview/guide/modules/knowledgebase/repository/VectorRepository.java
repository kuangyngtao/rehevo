package interview.guide.modules.knowledgebase.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<Document> lexicalSearch(String query, List<Long> knowledgeBaseIds, int topK) {
        if (query == null || query.isBlank() || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        String placeholders = String.join(",", knowledgeBaseIds.stream().map(id -> "?").toList());
        String sql = """
            SELECT id::text, content, metadata::text, 1 - (? <<-> content) AS lexical_score
            FROM vector_store
            WHERE metadata->>'kb_id' IN (%s)
            ORDER BY ? <<-> content, id
            LIMIT ?
            """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query);
        knowledgeBaseIds.stream().map(String::valueOf).forEach(parameters::add);
        parameters.add(query);
        parameters.add(topK);
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> {
            Map<String, Object> metadata = parseMetadata(resultSet.getString("metadata"));
            double lexicalScore = resultSet.getDouble("lexical_score");
            metadata.put("retrieval_lexical_score", lexicalScore);
            return Document.builder()
                .id(resultSet.getString("id"))
                .text(resultSet.getString("content"))
                .metadata(metadata)
                .score(lexicalScore)
                .build();
        }, parameters.toArray());
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("无法解析向量文档元数据", e);
        }
    }

    public void initializeLexicalSearchSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS vector_store_content_trgm_idx
            ON vector_store USING GIST (content gist_trgm_ops(siglen=64))
            """);
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 查询指定知识库当前已提升为正式状态的向量分块数。
     *
     * <p>向量化任务使用临时 {@code kb_id} 写入，只有提升成功后才会变成正式知识库 ID；
     * 因此这里只统计正式记录，避免将失败任务的临时分块写进页面统计。</p>
     *
     * @param knowledgeBaseId 知识库ID
     * @return 正式向量分块数
     */
    public int countByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = """
            SELECT COUNT(*)
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        try {
            Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, knowledgeBaseId.toString(), knowledgeBaseId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("查询知识库向量分块数失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "查询知识库向量分块数失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败");
        }
    }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败");
        }
    }
}
