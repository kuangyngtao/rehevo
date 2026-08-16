package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchSchemaInitializer implements ApplicationRunner {
  private final JdbcTemplate jdbcTemplate;
  private final VectorRepository vectorRepository;
  private final KnowledgeBaseQueryProperties properties;

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.getHybrid().isInitializeSchema() || !isPostgreSql()) {
      return;
    }
    try {
      vectorRepository.initializeLexicalSearchSchema();
      log.info("RAG 字符三元组检索索引已就绪");
    } catch (Exception e) {
      log.warn("RAG 字符三元组检索索引初始化失败，向量检索仍可使用: {}", e.getMessage());
    }
  }

  private boolean isPostgreSql() {
    try (var connection = jdbcTemplate.getDataSource().getConnection()) {
      return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    } catch (Exception e) {
      log.warn("无法识别数据库类型，跳过字符三元组索引初始化: {}", e.getMessage());
      return false;
    }
  }
}
