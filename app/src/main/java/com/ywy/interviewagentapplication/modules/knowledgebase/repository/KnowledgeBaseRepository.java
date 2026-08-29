package com.ywy.interviewagentapplication.modules.knowledgebase.repository;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库 Repository。
 *
 * <h3>方法命名规则（Spring Data JPA 派生查询）</h3>
 * 大部分查询方法通过方法名自动生成 SQL，命名遵循
 * "findByXxxAndYyyOrderByZzzDesc" 的语法。这种方式的优势：
 * <ul>
 *   <li>零 SQL 编写——方法名即查询语义</li>
 *   <li>编译期可验证——属性名拼错会在启动时失败（而非运行时）</li>
 * </ul>
 * 复杂查询（JOIN、聚合、批量更新）使用 {@code @Query} 注解手写 JPQL。
 *
 * <h3>悲观锁的使用（findByIdForUpdate）</h3>
 * {@code @Lock(PESSIMISTIC_WRITE)} 对查询行加 SELECT ... FOR UPDATE——
 * 用于串行化同一知识库的题目生成状态迁移，防止两个并发任务同时
 * 读取 PENDING 状态并各自启动生成任务（双写覆盖）。
 *
 * @see KnowledgeBaseEntity 实体定义
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据 SHA-256 哈希查找知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByFileHash(String fileHash);

    /**
     * 悲观锁查询：锁定知识库行，用于串行化同一知识库的题目生成状态迁移。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE k.id = :id")
    Optional<KnowledgeBaseEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    /**
     * 按上传时间倒序查找所有知识库
     */
    List<KnowledgeBaseEntity> findAllByOrderByUploadedAtDesc();

    /**
     * 获取所有不同的分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    List<String> findAllCategories();

    /**
     * 根据分类查找知识库
     */
    List<KnowledgeBaseEntity> findByCategoryOrderByUploadedAtDesc(String category);

    /**
     * 查找未分类的知识库
     */
    List<KnowledgeBaseEntity> findByCategoryIsNullOrderByUploadedAtDesc();

    /**
     * 按名称或文件名模糊（不区分大小写）查找数据库
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY k.uploadedAt DESC")
    List<KnowledgeBaseEntity> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 查找数据库，按文件大小降序排列
     */
    List<KnowledgeBaseEntity> findAllByOrderByFileSizeDesc();

    /**
     * 查找数据库，按访问次数降序排列
     */
    List<KnowledgeBaseEntity> findAllByOrderByAccessCountDesc();

    /**
     * 查找数据库，按提问次数降序排列
     */
    List<KnowledgeBaseEntity> findAllByOrderByQuestionCountDesc();

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /**
     * 统计所有知识库的总提问次数
     * COALESCE 处理空表情况：SUM 对空集返回 NULL，COALESCE 转为 0
     */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    long sumQuestionCount();

    /**
     * 统计所有知识库的总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    long sumAccessCount();

    /** 按向量化状态统计知识库文档数量（如"3 个知识库文档在处理中"） */
    long countByVectorStatus(VectorStatus vectorStatus);

    /**
     * 按向量化状态查找知识库文档（上传时间降序排列）
     */
    List<KnowledgeBaseEntity> findByVectorStatusOrderByUploadedAtDesc(VectorStatus vectorStatus);

    /**
     * 查找 "卡死"（状态未变化且超过阈值时间未更新）的问题生成任务。
     * <p>
     * 应用崩溃重启后，PROCESSING 状态的任务永远无法完成。
     * 此查询找出这些遗留任务（状态匹配但更新时间早于阈值），由恢复任务重新处理或标记为失败。
     *
     * @param status    要查找的任务状态（通常是 PROCESSING 或 QUEUED）
     * @param threshold 时间阈值（早于此时间的任务视为卡死）
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k "
            + "WHERE k.questionGenStatus = :status "
            + "AND (k.questionGenUpdatedAt IS NULL OR k.questionGenUpdatedAt < :threshold)")
    List<KnowledgeBaseEntity> findStaleQuestionGenerationTasks(
            @Param("status") QuestionGenStatus status,
            @Param("threshold") LocalDateTime threshold);
}

