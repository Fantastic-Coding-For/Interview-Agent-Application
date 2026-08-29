package com.ywy.interviewagentapplication.infrastructure.redis;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话 Redis 缓存服务。
 *
 * <h3>架构定位</h3>
 * 本服务是面试模块的<b>二级缓存层</b>，介于 Controller/Service 与 Redis 之间。
 * 它不直接操作数据库，数据库的持久化由 Repository 层完成。
 * Redis 中的会话数据是数据库的"热副本"，提供：
 * <ul>
 *   <li><b>低延迟读取</b>：Redis 内存读取 < 1ms，数据库查询通常 > 5ms</li>
 *   <li><b>自然过期</b>：TTL 24 小时后自动清理，无需定时任务扫表</li>
 *   <li><b>数据库减压</b>：面试过程中的频繁状态更新落在 Redis 而非 DB</li>
 * </ul>
 *
 * <h3>两种 Redis 键的设计</h3>
 * <ol>
 *   <li><b>会话键</b>（{@code interview:session:{sessionId}}）：
 *       存储完整的 CachedSession 对象（通过 Java 序列化）。</li>
 *   <li><b>简历映射键</b>（{@code interview:resume:{resumeId}}）：
 *       存储 sessionId 字符串。用于"通过简历ID查找未完成会话"的场景——
 *       同一份简历可能有"上次没做完的面试"，允许候选人继续。</li>
 * </ol>
 *
 * <h3>TTL 24 小时的设计依据</h3>
 * <ul>
 *   <li>典型的面试场景：候选人上传简历 → 被问到问题 → 思考 → 回答，全过程不超过 2 小时</li>
 *   <li>24 小时覆盖了"上午开始、下午完成"的跨时段场景</li>
 *   <li>超过 24 小时未完成的面试视为已废弃，缓存自动回收避免内存泄漏</li>
 * </ul>
 *
 * @see RedisService Redis 操作封装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionCache {

    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    /**
     * 缓存键前缀
     */
    private static final String SESSION_KEY_PREFIX = "interview:session:";

    /**
     * 简历ID到会话ID的映射键前缀（用于查找未完成会话）
     */
    private static final String RESUME_SESSION_KEY_PREFIX = "interview:resume:";

    /**
     * 会话默认过期时间（24小时）
     */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /**
     * 缓存的会话数据内部类。
     *
     * <h3>设计决策</h3>
     * <ul>
     *   <li><b>为什么是静态内部类？</b>
     *       CachedSession 仅在 InterviewSessionCache 的上下文中使用，
     *       设置为内部类可以明确表达"这是缓存的内部数据结构"的语义。</li>
     *   <li><b>为什么实现 Serializable？</b>
     *       Redisson 默认使用 Java 原生序列化（{@code FstCodec} / {@code SerializationCodec}）
     *       将对象写入 Redis。实现 Serializable 是使用这些 Codec 的前提。</li>
     *   <li><b>为什么 questions 序列化为 JSON 字符串而非直接存 List？</b>
     *       直接存 List 也可以序列化，但 JSON 字符串的优势在于：①可以用 redis-cli 直接查看内容
     *       （调试友好）；②避免了 Redisson 序列化 InterviewQuestionDTO 类时的版本兼容问题。</li>
     * </ul>
     */
    @Data
    public static class CachedSession implements Serializable {
        /** 会话唯一标识 */
        private String sessionId;
        /** 简历文本（供 LLM 评估时读取，避免每次从 DB 查询） */
        private String resumeText;
        /** 关联的简历 ID（可为 null：无简历的面试场景） */
        private Long resumeId;
        /** 关联的知识库 ID（可为 null：不使用知识库的场景） */
        private Long knowledgeBaseId;
        /** 面试类别标识（如"技术面"、"HR面"），用于评估时选择合适的评分标准 */
        private String interviewCategory;
        private String questionsJson;  // 序列化的问题列表
        /** 当前回答到第几题（0-based），用于断点续答 */
        private int currentIndex;
        /** 会话状态，决定是否允许继续答题 */
        private SessionStatus status;

        /** 无参构造器，Java 反序列化使用*/
        public CachedSession() {
        }

        public CachedSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                             String interviewCategory,
                             List<InterviewQuestionDTO> questions, int currentIndex,
                             SessionStatus status, ObjectMapper objectMapper) {
            this.sessionId = sessionId;
            this.resumeText = resumeText;
            this.resumeId = resumeId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.interviewCategory = interviewCategory;
            this.currentIndex = currentIndex;
            this.status = status;
            try {
                this.questionsJson = objectMapper.writeValueAsString(questions);
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
            }
        }

        public List<InterviewQuestionDTO> getQuestions(ObjectMapper objectMapper) {
            try {
                return objectMapper.readValue(questionsJson, new TypeReference<>() {});
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化问题列表失败");
            }
        }
    }

    /**
     * 保存会话到 Redis 缓存（全量写入）。
     * <p>
     * <b>副作用</b>：如果 resumeId 非 null 且会话状态为未完成，
     * 会同时建立/更新简历→会话的映射关系。
     * 这个副作用是刻意设计的，业务上需要"通过简历找到未完成会话"的能力。
     *
     * @param sessionId         会话ID
     * @param resumeText        简历文本
     * @param resumeId          简历ID（可为 null）
     * @param knowledgeBaseId   知识库ID（可为 null）
     * @param interviewCategory 面试类别
     * @param questions         问题列表
     * @param currentIndex      当前进度
     * @param status            会话状态
     */
    public void saveSession(String sessionId, String resumeText, Long resumeId, Long knowledgeBaseId,
                            String interviewCategory,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status) {
        String key = buildSessionKey(sessionId);
        CachedSession cachedSession = new CachedSession(
                sessionId, resumeText, resumeId, knowledgeBaseId, interviewCategory,
                questions, currentIndex, status, objectMapper
        );

        redisService.set(key, cachedSession, SESSION_TTL);

        // 如果有 resumeId，建立映射关系（用于查找未完成会话）
        if (resumeId != null && isUnfinishedStatus(status)) {
            saveResumeSessionMapping(resumeId, sessionId);
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, kbId={}, status={}",
                sessionId, resumeId, knowledgeBaseId, status);
    }

    /**
     * 从 Redis 获取缓存的会话。
     * <p>
     * 返回 {@link Optional} 而非直接返回 null，强制调用方处理"会话不存在"的情况，
     * 避免 NPE。
     *
     * @param sessionId 会话ID
     * @return 包含 CachedSession 的 Optional，未命中时为空 Optional
     */
    public Optional<CachedSession> getSession(String sessionId) {
        String key = buildSessionKey(sessionId);
        CachedSession session = redisService.get(key);
        if (session != null) {
            log.debug("从缓存获取会话: sessionId={}", sessionId);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    /**
     * 更新会话状态（只修改 status 字段）。
     * <p>
     * <b>性能考量</b>：这里采用了"读-改-写"模式（Read-Modify-Write），
     * 先将整个 CachedSession 从 Redis 读出，修改 status 后再整体写回。
     * 这种模式在并发场景下有丢失更新的风险（两个线程同时读，各自修改后写回），
     * 但此处可以接受，因为同一个 session 的并发状态更新在业务上几乎不会发生
     * （一个候选人在同一时刻只能进行一次面试操作）。
     * <p>
     * <b>映射清理逻辑</b>：当会话从"未完成"变为"已完成"时，移除简历映射，
     * 因为已完成会话不应再通过"继续面试"被找到。
     *
     * @param sessionId 会话ID
     * @param status    新状态
     */
    public void updateSessionStatus(String sessionId, SessionStatus status) {
        getSession(sessionId).ifPresent(session -> {
            session.setStatus(status);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);

            // 如果会话已完成，移除映射
            if (!isUnfinishedStatus(status) && session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }

            log.debug("更新会话状态: sessionId={}, status={}", sessionId, status);
        });
    }

    /**
     * 更新当前答题进度（currentIndex 字段）。
     * <p>
     * 每次候选人完成一道题的作答后调用，记录"回答到第几题"。
     *
     * @param sessionId    会话ID
     * @param currentIndex 新的进度索引（0-based）
     */
    public void updateCurrentIndex(String sessionId, int currentIndex) {
        getSession(sessionId).ifPresent(session -> {
            session.setCurrentIndex(currentIndex);
            String key = buildSessionKey(sessionId);
            redisService.set(key, session, SESSION_TTL);
            log.debug("更新会话进度: sessionId={}, currentIndex={}", sessionId, currentIndex);
        });
    }

    /**
     * 更新问题列表（通常是保存/更新了某道题的答案后触发）。
     * <p>
     * <b>为什么即使会话不存在也静默忽略？</b>
     * 如果缓存中不存在该 session（可能已过期或从未缓存），
     * 业务上仍可以继续。因为数据在数据库中，缓存只是加速层。
     * 静默忽略而非抛异常是为了不阻断正常业务流程（优雅降级）。
     *
     * @param sessionId 会话ID
     * @param questions 更新后的问题列表
     */
    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        getSession(sessionId).ifPresent(session -> {
            try {
                session.setQuestionsJson(objectMapper.writeValueAsString(questions));
                String key = buildSessionKey(sessionId);
                redisService.set(key, session, SESSION_TTL);
                log.debug("更新会话问题: sessionId={}", sessionId);
            } catch (JacksonException e) {
                log.error("序列化问题列表失败", e);
            }
        });
    }

    /**
     * 删除会话缓存（面试结束或被取消时调用）。
     * <p>
     * 同时清理关联的简历映射。操作顺序：先获取映射信息（需要读取 session），
     * 再删除映射键，最后删除会话键。确保即使删除会话失败也不会留下孤儿映射。
     * 但实际上，先删映射后删会话的顺序不那么重要，因为 findUnfinishedSessionId
     * 在映射指向不存在/已完成的会话时会自动清理。
     *
     * @param sessionId 会话ID
     */
    public void deleteSession(String sessionId) {
        getSession(sessionId).ifPresent(session -> {
            if (session.getResumeId() != null) {
                removeResumeSessionMapping(session.getResumeId(), sessionId);
            }
        });

        String key = buildSessionKey(sessionId);
        redisService.delete(key);
        log.debug("删除会话缓存: sessionId={}", sessionId);
    }

    /**
     * 根据简历ID查找未完成的会话ID（用于"继续面试"功能）。
     * <p>
     * <b>验证步骤</b>：
     * <ol>
     *   <li>通过 resumeId 找到映射的 sessionId</li>
     *   <li>验证该 session 确实存在且未完成</li>
     * </ol>
     * 验证的必要性：映射可能已过时（会话在被映射后独立完成了），
     * 直接返回映射值而不验证可能导致客户端跳转到一个不存在的会话。
     * <p>
     * 如果验证失败（会话不存在或已完成），自动清理映射。这是"读时修复"策略，
     * 避免了单独的定时清理任务。
     *
     * @param resumeId 简历ID
     * @return 包含未完成会话ID的 Optional，不存在时为空 Optional
     */
    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        String key = buildResumeSessionKey(resumeId);
        String sessionId = redisService.get(key);
        if (sessionId != null) {
            // 验证会话是否仍然存在且未完成
            Optional<CachedSession> sessionOpt = getSession(sessionId);
            if (sessionOpt.isPresent() && isUnfinishedStatus(sessionOpt.get().getStatus())) {
                return Optional.of(sessionId);
            } else {
                // 会话已不存在或已完成，清理映射
                redisService.delete(key);
            }
        }
        return Optional.empty();
    }

    /**
     * 刷新会话的 TTL（续期）。
     * <p>
     * 每次用户与面试交互时调用（提交答案、切换题目等），
     * 确保活跃会话不会在 24 小时后过期。只有在用户超过 24 小时不操作时才会被清理。
     * 这是一种"滑动过期"策略：每次交互重置计时器。
     *
     * @param sessionId 会话ID
     */
    public void refreshSessionTTL(String sessionId) {
        String key = buildSessionKey(sessionId);
        redisService.expire(key, SESSION_TTL);
    }

    /**
     * 检查会话是否存在于 Redis 中。
     */
    public boolean exists(String sessionId) {
        String key = buildSessionKey(sessionId);
        return redisService.exists(key);
    }

    // ==================== 私有方法 ====================

    /**
     * 构建 Redis 会话键。
     * <p>
     * 格式：{@code interview:session:{sessionId}}
     * 使用冒号分隔符是 Redis 的命名惯例，许多 Redis GUI 工具会自动按冒号分组展示。
     */
    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    /** 构建简历→会话的映射键 */
    private String buildResumeSessionKey(Long resumeId) {
        return RESUME_SESSION_KEY_PREFIX + resumeId;
    }

    /**
     * 保存简历→会话的映射关系。
     * <p>
     * 使用与会话数据相同的 TTL（24小时）。
     * 如果映射已存在（同一份简历发起了新地面试），会被覆盖。
     */
    private void saveResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        redisService.set(key, sessionId, SESSION_TTL);
    }

    /**
     * 移除简历→会话的映射关系。
     * <p>
     * <b>CAS（Compare-And-Swap）保护</b>：
     * 只有当映射指向当前这个 sessionId 时才删除。
     * 这是为了防止：删除了简历对会话 A 的映射，但该映射已被会话 B 覆盖，不检查就删除会导致会话 B 的映射被错误清除。
     *
     * @param resumeId  简历ID
     * @param sessionId 要解除关联的会话ID
     */
    private void removeResumeSessionMapping(Long resumeId, String sessionId) {
        String key = buildResumeSessionKey(resumeId);
        String currentSessionId = redisService.get(key);
        // 只有当前映射的是这个 sessionId 时才删除
        if (sessionId.equals(currentSessionId)) {
            redisService.delete(key);
        }
    }

    /**
     * 判断会话状态是否表示"未完成"。
     * <p>
     * CREATED（刚创建）和 IN_PROGRESS（进行中）视为未完成，
     * COMPLETED 和 EVALUATED 视为已完成。
     * <p>
     */
    private boolean isUnfinishedStatus(SessionStatus status) {
        return status == SessionStatus.CREATED || status == SessionStatus.IN_PROGRESS;
    }
}

