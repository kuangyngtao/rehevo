package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private Hybrid hybrid = new Hybrid();
    private Rerank rerank = new Rerank();
    private History history = new History();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = false;
    }

    @Data
    public static class Search {
        private RetrievalMode mode = RetrievalMode.HYBRID;
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class Hybrid {
        private int vectorCandidates = 20;
        private int lexicalCandidates = 20;
        private int fusionCandidates = 20;
        private int rrfK = 60;
        private double vectorWeight = 3.0;
        private double lexicalWeight = 1.0;
        private boolean initializeSchema = true;
    }

    @Data
    public static class Rerank {
        private boolean enabled = false;
        private String workspaceId = "";
        private String apiKey = "";
        private String model = "qwen3-rerank";
        private String instruct = "Given a web search query, retrieve relevant passages that answer the query.";
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }
}
