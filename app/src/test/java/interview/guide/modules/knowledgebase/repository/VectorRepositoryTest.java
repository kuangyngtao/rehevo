package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("向量仓储测试")
class VectorRepositoryTest {

    @Test
    @DisplayName("统计正式知识库向量分块")
    void countByKnowledgeBaseIdReturnsStoredChunkCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("42"), eq(42L)))
            .thenReturn(3);
        VectorRepository repository = new VectorRepository(jdbcTemplate);

        int chunkCount = repository.countByKnowledgeBaseId(42L);

        assertThat(chunkCount).isEqualTo(3);
    }

    @Test
    @DisplayName("统计查询失败时抛出可识别业务异常")
    void countByKnowledgeBaseIdWrapsDatabaseFailure() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("42"), eq(42L)))
            .thenThrow(new IllegalStateException("database unavailable"));
        VectorRepository repository = new VectorRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.countByKnowledgeBaseId(42L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("查询知识库向量分块数失败");
    }
}
