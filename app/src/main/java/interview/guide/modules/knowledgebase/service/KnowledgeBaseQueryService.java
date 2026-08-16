package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.AnswerEvaluationRequest;
import interview.guide.modules.knowledgebase.model.AnswerEvaluationResponse;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RetrievalEvaluationRequest;
import interview.guide.modules.knowledgebase.model.RetrievalEvaluationResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;
    private static final int EVIDENCE_PREVIEW_MAX_CHARS = 240;

    private final LlmProviderRegistry llmProviderRegistry;
    private final HybridRetrievalService retrievalService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ApplicationMetrics applicationMetrics;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    private final RetrievalMode defaultRetrievalMode;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            HybridRetrievalService retrievalService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseRepository knowledgeBaseRepository,
            ApplicationMetrics applicationMetrics,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.retrievalService = retrievalService;
        this.listService = listService;
        this.countService = countService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.applicationMetrics = applicationMetrics;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
        this.defaultRetrievalMode = queryProperties.getSearch().getMode();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return executeSyncQuery(knowledgeBaseIds, question).answer();
    }

    private SyncQueryResult executeSyncQuery(List<Long> knowledgeBaseIds, String question) {
        return executeSyncQuery(knowledgeBaseIds, question, true, true);
    }

    private SyncQueryResult executeSyncQuery(
            List<Long> knowledgeBaseIds,
            String question,
            boolean countQuestion,
            boolean useRewrite) {
        return executeSyncQuery(knowledgeBaseIds, question, countQuestion, useRewrite, defaultRetrievalMode);
    }

    private SyncQueryResult executeSyncQuery(
            List<Long> knowledgeBaseIds,
            String question,
            boolean countQuestion,
            boolean useRewrite,
            RetrievalMode retrievalMode) {
        long startNanos = System.nanoTime();
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.SYNC, ApplicationMetrics.Outcome.SKIPPED
            );
            return new SyncQueryResult(NO_RESULT_RESPONSE, RetrievalResult.empty());
        }

        if (countQuestion) {
            countService.updateQuestionCounts(knowledgeBaseIds);
        }

        QueryContext queryContext = buildQueryContext(question, List.of(), useRewrite);
        RetrievalResult retrievalResult = retrieveRelevantDocs(queryContext, knowledgeBaseIds, retrievalMode);
        List<Document> relevantDocs = retrievalResult.documents();

        if (!hasEffectiveHit(relevantDocs)) {
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.SYNC, ApplicationMetrics.Outcome.SKIPPED
            );
            return new SyncQueryResult(NO_RESULT_RESPONSE, retrievalResult);
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.SYNC, ApplicationMetrics.Outcome.SUCCESS
            );
            return new SyncQueryResult(answer, retrievalResult);

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.SYNC, ApplicationMetrics.Outcome.FAILURE
            );
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        SyncQueryResult queryResult = executeSyncQuery(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(
            queryResult.answer(),
            primaryKbId,
            kbNamesStr,
            queryResult.retrievalResult().query(),
            buildEvidence(queryResult.retrievalResult().documents())
        );
    }

    /**
     * 批量执行当前的 Query Rewrite + 向量检索链路，不调用回答模型且不写入用户提问计数。
     * 该方法仅供固定评测集运行，评测脚本根据返回 evidence 计算检索指标。
     */
    public RetrievalEvaluationResponse evaluateRetrieval(RetrievalEvaluationRequest request) {
        List<RetrievalEvaluationResponse.RetrievalEvaluationItem> items = request.queries().stream()
            .map(query -> {
                QueryContext queryContext = buildQueryContext(query.question(), List.of(), request.useRewrite());
                RetrievalMode mode = request.retrievalMode() == null
                    ? defaultRetrievalMode : request.retrievalMode();
                RetrievalResult retrievalResult = retrieveRelevantDocs(queryContext, query.knowledgeBaseIds(), mode);
                return new RetrievalEvaluationResponse.RetrievalEvaluationItem(
                    query.question(),
                    retrievalResult.query(),
                    buildEvidence(retrievalResult.documents())
                );
            })
            .toList();
        return new RetrievalEvaluationResponse(items);
    }

    /**
     * 执行完整回答链路并返回未截断的检索片段，供独立 RAGAS 运行器离线评分。
     */
    public AnswerEvaluationResponse evaluateAnswers(AnswerEvaluationRequest request) {
        List<AnswerEvaluationResponse.AnswerEvaluationItem> items = request.queries().stream()
            .map(query -> {
                SyncQueryResult result = executeSyncQuery(
                    query.knowledgeBaseIds(), query.question(), false, request.useRewrite(),
                    request.retrievalMode() == null ? defaultRetrievalMode : request.retrievalMode()
                );
                return new AnswerEvaluationResponse.AnswerEvaluationItem(
                    query.question(),
                    result.retrievalResult().query(),
                    result.answer(),
                    buildEvaluationEvidence(result.retrievalResult().documents())
                );
            })
            .toList();
        return new AnswerEvaluationResponse(items);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        long startNanos = System.nanoTime();
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.STREAM, ApplicationMetrics.Outcome.SKIPPED
            );
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(
                queryContext, knowledgeBaseIds, defaultRetrievalMode).documents();

            if (!hasEffectiveHit(relevantDocs)) {
                applicationMetrics.recordRagAnswer(
                    System.nanoTime() - startNanos, ApplicationMetrics.Interaction.STREAM, ApplicationMetrics.Outcome.SKIPPED
                );
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> {
                    applicationMetrics.recordRagAnswer(
                        System.nanoTime() - startNanos,
                        ApplicationMetrics.Interaction.STREAM,
                        ApplicationMetrics.Outcome.SUCCESS
                    );
                    log.info("流式输出完成: kbIds={}", knowledgeBaseIds);
                })
                .onErrorResume(e -> {
                    applicationMetrics.recordRagAnswer(
                        System.nanoTime() - startNanos,
                        ApplicationMetrics.Interaction.STREAM,
                        ApplicationMetrics.Outcome.FAILURE
                    );
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            applicationMetrics.recordRagAnswer(
                System.nanoTime() - startNanos, ApplicationMetrics.Interaction.STREAM, ApplicationMetrics.Outcome.FAILURE
            );
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        return buildQueryContext(originalQuestion, history, true);
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history, boolean useRewrite) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = useRewrite ? rewriteQuestion(normalizedQuestion, history) : normalizedQuestion;
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索
    private RetrievalResult retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        return retrieveRelevantDocs(queryContext, knowledgeBaseIds, defaultRetrievalMode);
    }

    private RetrievalResult retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds,
                                                  RetrievalMode retrievalMode) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = retrievalService.retrieve(
                candidateQuery,
                queryContext.originalQuestion(),
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore(),
                retrievalMode
            );
            log.info("检索候选 query='{}'，mode={}，命中 {} 条", candidateQuery, retrievalMode, docs.size());
            if (hasEffectiveHit(docs)) {
                return new RetrievalResult(candidateQuery, docs);
            }
        }
        return RetrievalResult.empty();
    }

    private List<QueryResponse.RetrievalEvidence> buildEvidence(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<Long> knowledgeBaseIds = documents.stream()
            .map(document -> parseLongMetadata(document, "kb_id"))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> documentHashes = knowledgeBaseRepository.findAllById(knowledgeBaseIds).stream()
            .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getFileHash));

        return documents.stream()
            .map(document -> {
                Long knowledgeBaseId = parseLongMetadata(document, "kb_id");
                return new QueryResponse.RetrievalEvidence(
                    document.getId(),
                    knowledgeBaseId,
                    knowledgeBaseId == null ? null : documentHashes.get(knowledgeBaseId),
                    parseIntegerMetadata(document, "chunk_index"),
                    retrievalSimilarityScore(document),
                    parseIntegerMetadata(document, "retrieval_vector_rank"),
                    parseIntegerMetadata(document, "retrieval_lexical_rank"),
                    parseDoubleMetadata(document, "retrieval_rrf_score"),
                    parseDoubleMetadata(document, "retrieval_rerank_score"),
                    parseIntegerMetadata(document, "retrieval_final_rank"),
                    parseStringListMetadata(document, "retrieval_sources"),
                    abbreviate(document.getText(), EVIDENCE_PREVIEW_MAX_CHARS)
                );
            })
            .toList();
    }

    private List<AnswerEvaluationResponse.RetrievalEvidence> buildEvaluationEvidence(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<Long> knowledgeBaseIds = documents.stream()
            .map(document -> parseLongMetadata(document, "kb_id"))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> documentHashes = knowledgeBaseRepository.findAllById(knowledgeBaseIds).stream()
            .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getFileHash));

        return documents.stream()
            .map(document -> {
                Long knowledgeBaseId = parseLongMetadata(document, "kb_id");
                return new AnswerEvaluationResponse.RetrievalEvidence(
                    document.getId(),
                    knowledgeBaseId,
                    knowledgeBaseId == null ? null : documentHashes.get(knowledgeBaseId),
                    parseIntegerMetadata(document, "chunk_index"),
                    retrievalSimilarityScore(document),
                    parseIntegerMetadata(document, "retrieval_vector_rank"),
                    parseIntegerMetadata(document, "retrieval_lexical_rank"),
                    parseDoubleMetadata(document, "retrieval_rrf_score"),
                    parseDoubleMetadata(document, "retrieval_rerank_score"),
                    parseIntegerMetadata(document, "retrieval_final_rank"),
                    parseStringListMetadata(document, "retrieval_sources"),
                    document.getText()
                );
            })
            .toList();
    }

    private Long parseLongMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntegerMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDoubleMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double retrievalSimilarityScore(Document document) {
        Double vectorScore = parseDoubleMetadata(document, "retrieval_vector_score");
        return vectorScore == null ? document.getScore() : vectorScore;
    }

    private List<String> parseStringListMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            applicationMetrics.recordRagRewrite(ApplicationMetrics.Outcome.SKIPPED);
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                applicationMetrics.recordRagRewrite(ApplicationMetrics.Outcome.FAILURE);
                return question;
            }
            String normalized = rewritten.trim();
            applicationMetrics.recordRagRewrite(ApplicationMetrics.Outcome.SUCCESS);
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            applicationMetrics.recordRagRewrite(ApplicationMetrics.Outcome.FAILURE);
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }

    private record RetrievalResult(String query, List<Document> documents) {
        private static RetrievalResult empty() {
            return new RetrievalResult(null, List.of());
        }
    }

    private record SyncQueryResult(String answer, RetrievalResult retrievalResult) {
    }
}
