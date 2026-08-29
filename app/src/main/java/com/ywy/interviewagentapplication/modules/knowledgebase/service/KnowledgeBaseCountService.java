package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库计数服务：维护知识库的回答次数。
 *
 * <h3>为什么独立的计数服务？</h3>
 * 计数更新的职责很小但调用频繁（每次 RAG 查询都会触发）：
 * <ul>
 *   <li>独立服务使得事务边界清晰——计数更新是独立事务，
 *       不参与查询主流程的事务（查询失败不影响计数，计数失败不影响查询）</li>
 *   <li>后续如需改为异步计数（如消息队列批量累加），只需修改本服务</li>
 * </ul>
 *
 * <h3>性能优化：批量 UPDATE vs 逐条 save</h3>
 * 一次多知识库查询会同时增加多个知识库的计数。
 * 逐条 findById + save 需要 N 次查询 + N 次更新（2N 次数据库往返）；
 * 批量 JPQL UPDATE（{@code incrementQuestionCountBatch}）只需 1 条 SQL。
 * 多库查询场景下性能差距显著（N=10 时 20 次往返 vs 2 次）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCountService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 批量更新知识库提问计数（使用单条 SQL 批量更新）。
     * <p>
     * 每个知识库的 questionCount +1，表示"该知识库参与了一次回答"。
     *
     * <h4>处理流程</h4>
     * <ol>
     *   <li>去重——同一知识库在请求中可能重复出现（客户端误传）</li>
     *   <li>存在性校验——所有 ID 都必须存在，不存在则抛异常（fail-fast）</li>
     *   <li>批量 UPDATE——单条 SQL 完成所有计数更新</li>
     * </ol>
     *
     * @param knowledgeBaseIds 参与回答的知识库 ID 列表
     * @throws BusinessException 如果任一 知识库 ID 不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }

        // 去重
        List<Long> uniqueIds = knowledgeBaseIds.stream().distinct().toList();

        // 验证所有知识库是否存在
        Set<Long> existingIds = new HashSet<>(knowledgeBaseRepository.findAllById(uniqueIds)
                .stream().map(KnowledgeBaseEntity::getId).toList());

        for (Long id : uniqueIds) {
            if (!existingIds.contains(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
            }
        }

        // 批量更新（单条 SQL）
        int updated = knowledgeBaseRepository.incrementQuestionCountBatch(uniqueIds);
        log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated);
    }
}

