package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatSessionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库删除服务：删除知识库及其全部关联数据。
 *
 * <h3>删除的关联数据（四个层面）</h3>
 * 一个知识库的数据分布在多个存储中，删除需要清理：
 * <ol>
 *   <li><b>RAG 会话关联</b>（数据库）：多对多关联，必须先解除，否则外键约束阻止删除</li>
 *   <li><b>知识库记录</b>（数据库）：主记录本身</li>
 *   <li><b>向量数据</b>（PgVector）：knowledge_bases 删除后向量数据成为孤儿，必须清理</li>
 *   <li><b>原始文件</b>（RustFS）：对象存储中的文件</li>
 * </ol>
 *
 * <h3>删除顺序与容错策略</h3>
 * <pre>
 * 1. 数据库记录删除（事务）——先删 DB，保证 API 语义一致性
 * 2. 向量数据删除——独立步骤，失败仅记录日志（可通过 kbId 补偿）
 * 3. RustFS 文件删除——最后执行，失败仅记录日志（可通过 storageKey 补偿）
 * </pre>
 * 只有第一步是"必须成功"的（失败则整个删除操作失败）；
 * 向量和文件的清理失败<b>不阻塞删除</b>——数据已不可访问（无 DB 记录），
 * 物理资源可在后续补偿任务中清理。这是"逻辑删除优先，物理清理尽力而为"的设计，避免因为存储层故障导致用户无法删除知识库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;
    private final TransactionalExecutor transactionalExecutor;

    /**
     * 删除知识库（入口）
     * <p>1. 通过事务删除数据库记录</p>
     * <p>2. 删除向量数据，失败不阻塞</p>
     * <p>3. 删除 RustFS 文件，失败不阻塞</p>
     */
    public void deleteKnowledgeBase(Long id) {
        String storageKey = transactionalExecutor.call(() -> deleteKnowledgeBaseRecords(id));

        vectorService.deleteByKnowledgeBaseId(id);

        try {
            storageService.deleteKnowledgeBase(storageKey);
        } catch (Exception e) {
            log.warn(
                    "知识库数据库记录已删除，但RustFS文件清理失败，可后续按storageKey补偿: kbId={}, storageKey={}, error={}",
                    id, storageKey, e.getMessage(), e
            );
        }

        log.info("知识库已删除: id={}", id);
    }

    /**
     * 删除知识库的数据库记录（含会话关联清理）。
     * <p>
     * <b>为什么必须先清理会话关联？</b>
     * RAG 会话与知识库是多对多关系（中间表 rag_session_knowledge_bases）。
     * 如果不先移除关联，中间表的外键约束会阻止删除知识库记录（SQL 会报外键冲突错误）。
     * <p>
     * 关联清理后会话仍然存在，只是不再引用该知识库。会话的历史消息不受影响（消息内容已固化在消息表中）。
     *
     * @param id 知识库 ID
     * @return 知识库的 storageKey（供后续文件删除使用）
     * @throws BusinessException 如果知识库不存在
     */
    private String deleteKnowledgeBaseRecords(Long id) {
        // 1. 获取知识库信息
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        String storageKey = kb.getStorageKey();

        // 2. 删除所有RAG会话中的知识库关联（必须先删除关联，否则外键约束会阻止删除）
        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }

        // 3. 删除知识库记录
        knowledgeBaseRepository.deleteById(id);
        return storageKey;
    }
}

