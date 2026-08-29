package com.ywy.interviewagentapplication.modules.knowledgebase.repository;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 知识库题目 Repository。
 */
public interface KnowledgeBaseQuestionRepository extends JpaRepository<KnowledgeBaseQuestionEntity, Long> {
    /** 列出指定的知识库下所有题目（按更新时间倒序，最近编辑的在前） */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdOrderByUpdatedAtDesc(Long knowledgeBaseId);

    /** 列出指定的知识库下指定状态的题目（状态筛选） */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(
            Long knowledgeBaseId, KnowledgeBaseQuestionStatus status);

    /** 最近 50 道指定难度的题目（按更新时间倒序，最近编辑的在前） */
    List<KnowledgeBaseQuestionEntity> findTop50ByKnowledgeBase_IdAndSkillIdAndDifficultyOrderByUpdatedAtDesc(
            Long knowledgeBaseId, String skillId, String difficulty);

    /** 按知识库+难度+状态筛选 */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndSkillIdAndDifficultyAndStatusOrderByUpdatedAtDesc(
            Long knowledgeBaseId,
            String skillId,
            String difficulty,
            KnowledgeBaseQuestionStatus status
    );

    /** 按知识库+难度筛选 */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndSkillIdAndDifficulty(Long knowledgeBaseId,
                                                                                    String skillId,
                                                                                    String difficulty);

    /**
     * 同知识库内按难度筛选的 20 道题（不限定 skillId，因为知识库题目 skillId 都是默认值）。
     * 按更新时间倒序，最近编辑的在前。
     */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndDifficulty(Long knowledgeBaseId, String difficulty);

    List<KnowledgeBaseQuestionEntity> findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(
            Long knowledgeBaseId, String difficulty);

    /**
     * 同知识库 + 难度 + 方向（category）下的已启用题目，用于开始知识库面试抽题。
     * 按更新时间倒序，最近编辑的在前。
     */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
            Long knowledgeBaseId,
            String difficulty,
            String category,
            KnowledgeBaseQuestionStatus status
    );

    /**
     * 同知识库 + 难度下的已启用题目，category 为 null 时表示覆盖所有方向。
     * 按更新时间倒序，最近编辑的在前。
     */
    List<KnowledgeBaseQuestionEntity> findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
            Long knowledgeBaseId,
            String difficulty,
            KnowledgeBaseQuestionStatus status
    );

    /**
     * 列出某知识库下出现过的方向（含计数），用于前端下拉筛选的数据源。
     * 只统计 category 非 null 的题目，先按出现次数降序，然后按方向升序。
     */
    @Query("select q.category as category, count(q) as count "
            + "from KnowledgeBaseQuestionEntity q "
            + "where q.knowledgeBase.id = :kbId and q.category is not null and q.category <> '' "
            + "group by q.category order by count(q) desc, q.category asc")
    List<CategoryCount> findCategoryCounts(@Param("kbId") Long knowledgeBaseId);

    /**
     * JPQL 投影接口：聚合查询的结果类型。
     * Spring Data 自动生成代理实现，字段名对应查询中的别名。
     */
    interface CategoryCount {
        String getCategory();

        Long getCount();
    }

    /**
     * 删除某知识库下的所有题目（重新生成时的替换操作）。
     * <p>
     * JPQL 批量删除（@Modifying）：绕过 JPA 实体生命周期直接执行 DELETE，性能远优于逐条 deleteAll（一条 SQL vs N 条 SQL）。
     * <p>
     * 注意：调用后需要清理缓存中的已加载实体（本应用中生成流程无缓存实体，安全）。
     */
    @Modifying
    @Query("DELETE FROM KnowledgeBaseQuestionEntity q WHERE q.knowledgeBase.id = :kbId")
    int deleteByKnowledgeBaseId(@Param("kbId") Long knowledgeBaseId);
}

