package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务：文档分块、向量化、相似度检索的核心。
 *
 * <h3>RAG 管线的定位</h3>
 * 本 Service 是知识库 RAG 能力的技术核心，负责两个方向：
 * <ol>
 *   <li><b>写入链路</b>（vectorizeAndStore）：文本 → 分块 → Embedding → 向量库</li>
 *   <li><b>读取链路</b>（similaritySearch）：查询文本 → Embedding → 相似度检索 → 相关文档</li>
 * </ol>
 *
 * <h3>两阶段提交策略（防"半成品"向量数据）</h3>
 * 重新向量化时，如果先删旧数据再写新数据，写入失败会导致知识库暂时失去检索能力。两阶段提交解决此问题：
 * <ol>
 *   <li><b>阶段1（写入）</b>：新向量数据以<b>临时身份</b>写入
 *       （kb_id = "pending:{id}:{jobId}"），旧数据保持可用</li>
 *   <li><b>阶段2（激活）</b>：全部写入成功后，删除旧数据 +
 *       将临时数据的 kb_id 提升为正式 ID（activateVectorJob）</li>
 * </ol>
 * 失败时清理临时数据（cleanupPendingVectorJob），旧数据不受影响。
 *
 * <h3>Embedding 批量限制</h3>
 * 阿里云 DashScope Embedding API 单次请求最多 10 个文本。
 * 分块后按 10 个一批调用，超过限制的请求会被 API 拒绝。
 *
 * <h3>降级检索策略</h3>
 * 相似度检索优先使用向量库的元数据过滤（filterExpression）。
 * 某些向量存储实现可能不支持过滤器表达式，此时回退到
 * "检索更多 + 本地过滤"的方式（similaritySearchFallback）：
 * 取 topK×3 的结果后在内存中按 kb_id 过滤，保证功能可用。
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    /** 临时知识库 ID 前缀：两阶段提交期间，向量数据的 kb_id 标识 */
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    /** metadata 键名：知识库 ID */
    private static final String METADATA_KB_ID = "kb_id";
    /** metadata 键名：临时数据的目标知识库 ID */
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    /** metadata 键名：向量化任务 ID（标识哪批数据属于哪个任务） */
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;

    @Autowired
    public KnowledgeBaseVectorService(
            VectorStore vectorStore,
            VectorRepository vectorRepository,
            TransactionalExecutor transactionalExecutor
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        // 使用 TokenTextSplitter 默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
        this.textSplitter = TokenTextSplitter.builder().build();
    }

    public KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(vectorStore, vectorRepository, null);
    }

    /**
     * 将知识库内容向量化并存储。
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>生成任务 ID（UUID）——标识本次向量化任务</li>
     *   <li>文本分块——TokenTextSplitter 按 ~800 tokens 切分</li>
     *   <li>为每个 chunk 添加临时 metadata（kb_id 带 pending 前缀）</li>
     *   <li>分批调用 Embedding API + 写入向量库（每批 ≤ 10 个 chunk）</li>
     *   <li>向量化任务全部成功后激活：删除旧向量数据 + 临时数据提升为正式</li>
     * </ol>
     *
     * <h4>失败处理</h4>
     * 任何一步失败 → 清理已写入的临时向量数据 → 抛出 BusinessException。
     * 消费者的 markFailed 回调会将知识库状态标记为 FAILED。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param content         文档纯文本内容
     * @throws BusinessException 如果向量化失败
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                    knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            List<Document> chunks = textSplitter.apply(
                    List.of(new Document(content))
            );

            log.info("文本分块完成: {} 个chunks", chunks.size());

            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }
            activateVectorJob(knowledgeBaseId, jobId);
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                    knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "向量化知识库失败: " + e.getMessage());
        }
    }

    /**
     * 为每个 chunk 设置临时 metadata（两阶段提交的第一阶段）。
     * <p>
     * kb_id 使用 "pending:{id}:{jobId}" 格式：既标识了目标知识库，又标识了所属任务（同一知识库的不同向量化任务互不干扰）。
     * 且检索时临时数据不会混入结果。
     */
    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
        });
    }

    /**
     * 基于多个知识库进行相似度搜索。
     * <p>
     * <b>过滤策略</b>：
     * 优先使用向量库的 filterExpression（数据库层过滤，性能最优）。
     * 如果向量库不支持过滤器（抛异常），回退到
     * {@link #similaritySearchFallback}（本地内存过滤）。
     *
     * @param query            查询文本（会被 Embedding 后与向量库比对）
     * @param knowledgeBaseIds 知识库 ID 列表（空则搜索全部）
     * @param topK             返回结果数量上限
     * @param minScore         最低相似度阈值（低于此分数的结果丢弃）
     * @return 相关文档列表（按相似度降序）
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
                query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;

        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    /**
     * 回退检索：向量库不支持过滤表达式时的降级路径。
     * <p>
     * 策略：仍保留 topK/minScore，检索 topK×3 的结果，在内存中按 kb_id 过滤，最后截断到 topK。
     * <p>
     * 回退的代价：检索数据量是正常路径的 3 倍（Embedding 相似度计算不变，但传输和过滤开销增加）。功能上等价，性能略差。
     */
    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                        .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                        .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                    "向量搜索失败: " + e.getMessage());
        }
    }

    /**
     * 检查文档是否属于指定的知识库。
     * <p>
     * kb_id 的两种可能类型：Long（某些序列化路径）或 String（JSON 元数据）。
     * 兼容两种类型：Long 直接比较，String 解析为 Long 后比较。
     * 解析失败（非法值）返回 false，排除在结果之外（安全优先）。
     */
    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                    ? (Long) kbId
                    : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 构建向量库的过滤表达式（数据库层过滤）。
     * <p>
     * 格式示例：{@code kb_id in ['1', '2', '3']}
     * 注意：值带单引号，向量库的过滤器语法要求字符串值加引号
     * （kb_id 在 metadata 中存储为字符串）。
     * <p>
     * null 元素被过滤掉，防御性处理。
     */
    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    /**
     * 删除指定知识库的所有向量数据。
     * <p>
     * 底层操作在事务中执行，保证了删除操作的原子性，不会出现删除的过程中还能访问同一知识库。
     * <p>
     * <b>异常吞掉的考量</b>：删除知识库时，向量清理失败不应阻止其他删除步骤（DB 记录已删、文件清理等）。孤儿向量数据虽然浪费空间，
     * 但不会产生错误结果（kb_id 过滤保证了检索只命中现有知识库）。如需严格保证，可取消注释的 throw 语句。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /** 删除指定知识库的所有向量数据（在事务中执行）。供内部使用，异常向上抛 */
    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    /**
     * 激活向量任务（两阶段提交的第二阶段）。
     * <p>
     * 两个操作在同一事务中：
     * <ol>
     *   <li>删除旧向量数据（deleteByKnowledgeBaseId）</li>
     *   <li>临时数据提升为正式（promoteVectorJob：kb_id 从 pending 前缀改为正式 ID）</li>
     * </ol>
     * 事务保证两者原子，不会出现"旧数据删了但新数据没生效"的窗口期。
     */
    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    /**
     * 清理失败任务的临时向量数据（在事务中执行）。
     * <p>
     * 清理失败不抛异常，临时数据（pending 前缀）不影响检索，
     * 可通过 jobId 在后续补偿任务中清理。
     */
    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                    knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    /**
     * 事务启动器。
     * <p>
     * transactionalExecutor 为 null 时直接执行（测试场景中，构造器不注入事务执行器）。生产环境始终有值。
     */
    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}

