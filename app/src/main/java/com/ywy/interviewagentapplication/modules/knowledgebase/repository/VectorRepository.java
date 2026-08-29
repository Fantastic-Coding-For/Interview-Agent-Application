package com.ywy.interviewagentapplication.modules.knowledgebase.repository;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 向量存储 Repository：Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）。
 *
 * <h3>为什么用 JdbcTemplate 而非 JPA？</h3>
 * 向量数据由 Spring AI 的 PgVectorStore 管理，表结构（vector_store）
 * 不属于本项目的 JPA 实体体系。对这张表只需执行少量定制的
 * DELETE/UPDATE SQL，使用 JdbcTemplate 是最直接的方案：
 * <ul>
 *   <li>无需定义实体类（表结构由 Spring AI 框架管理）</li>
 *   <li>SQL 完全可控：metadata JSON 操作需要手写 SQL</li>
 *   <li>与 Spring AI 的写入路径互不干扰</li>
 * </ul>
 *
 * <h3>metadata JSON 结构</h3>
 * vector_store 表的 metadata 列是 JSON 类型，本应用写入以下键：
 * <ul>
 *   <li>{@code kb_id}：知识库 ID（正式数据）或 {@code pending:{id}:{jobId}}（临时数据）</li>
 *   <li>{@code kb_id_long}：知识库 ID 的数值形式（历史兼容字段）</li>
 *   <li>{@code kb_target_id}：临时数据的最终归属知识库 ID</li>
 *   <li>{@code kb_vector_job_id}：向量化任务 ID（区分不同批次写入的数据）</li>
 * </ul>
 *
 * <h3>临时数据的两阶段提交策略</h3>
 * 重新向量化时，为避免"新数据未写完就删了旧数据"导致的知识库短暂不可用：
 * <ol>
 *   <li>新向量数据先以临时身份写入（kb_id = "pending:..."）</li>
 *   <li>全部写入成功后，删除旧数据 + 将临时数据提升为正式数据</li>
 * </ol>
 * 本类提供 promoteVectorJob 和 deleteByVectorJobId 支持这一策略。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 删除指定知识库的所有向量数据。
     * <p>
     * <b>为什么用原生 SQL 而非 VectorStore API？</b>
     * Spring AI 的 PgVectorStore 提供的 delete 方法只支持按文档 ID 删除，
     * 而本应用需要按 metadata 中的自定义字段（kb_id）批量删除。
     * 直接使用 SQL 可以利用 JSON 字段的索引能力（metadata 上有 GIN 索引时）。
     * <p>
     * <b>为什么不用 jsonb 的 '?' 操作符？</b>
     * PostgreSQL 的 '?' 操作符（键存在性检查）与 JDBC 的占位符 '?' 冲突，
     * 使用 {@code ->>'key' IS NOT NULL} 完全避开此问题。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 删除的行数
     * @throws BusinessException 如果 SQL 执行失败（触发事务回滚）
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
     * 删除指定向量化任务写入的临时向量数据。
     * <p>
     * 调用场景：任务中途失败可能会留下部分已写入的临时向量数据（kb_id 带 pending 前缀），需要按 jobId 精确清理，避免污染后续检索。
     *
     * @param jobId 向量化任务 ID
     * @return 删除的行数
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
     * 将临时向量数据提升为正式数据（两阶段提交的第二阶段）。
     * <p>
     * SQL 的三个操作：
     * <ol>
     *   <li>{@code jsonb_set(..., '{kb_id}', ...)}：将临时 kb_id（pending:...）
     *       替换为正式的知识库 ID</li>
     *   <li>{@code - 'kb_vector_job_id'}：移除任务 ID 标记（正式数据不需要）</li>
     *   <li>{@code - 'kb_target_id'}：移除目标 ID 标记（已写入 kb_id）</li>
     * </ol>
     * 注意 json → jsonb 的类型转换：metadata 列是 json 类型，
     * jsonb_set 只支持 jsonb，所以需要先 {@code metadata::jsonb} 转换，操作完成后通过 {@code ::json} 转回。
     *
     * @param knowledgeBaseId 正式的知识库 ID
     * @param jobId           向量化任务 ID（标识哪批临时数据）
     * @return 更新的行数
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

