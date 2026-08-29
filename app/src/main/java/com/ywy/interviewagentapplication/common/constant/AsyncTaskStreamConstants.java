package com.ywy.interviewagentapplication.common.constant;

/**
 * 异步任务 Redis Stream 通用常量
 */
public final class AsyncTaskStreamConstants {

    /**
     * 私有构造函数，防止实例化
     */
    private AsyncTaskStreamConstants() {}

    // ========== 通用消息字段 ==========

    /**
     * 当前重试次数
     */
    public static final String FIELD_RETRY_COUNT = "retryCount";
    /**
     * 文档内容
     */
    public static final String FIELD_CONTENT = "content";

    // ========== 通用消费者配置 ==========
    /**
     * 最大重试 3 次
     */
    public static final int MAX_RETRY_COUNT = 3;
    /**
     * 每批次拉取 10 条消息
     */
    public static final int BATCH_SIZE = 10;
    /**
     * Pending 消息空闲超过该时间后允许被其他消费者认领（5分钟）
     */
    public static final long PENDING_IDLE_TIMEOUT_MS = 5 * 60 * 1000;
    /**
     * 每轮回收 10 条 pending
     */
    public static final int PENDING_CLAIM_BATCH_SIZE = 10;
    /**
     * 消费者轮询间隔 1 秒
     */
    public static final long POLL_INTERVAL_MS = 1000;
    /**
     * Stream 最大长度（自动裁剪旧消息，防止无限增长）
     */
    public static final int STREAM_MAX_LEN = 1000;

    // ========== 知识库向量化 Stream ==========
    /**
     * 知识库向量化 Stream Key
     */
    public static final String KB_VECTORIZE_STREAM_KEY = "knowledgebase:vectorize:stream";
    /**
     * 知识库向量化 Consumer Group 名称
     */
    public static final String KB_VECTORIZE_GROUP_NAME = "vectorize-group";
    /**
     * 知识库向量化 Consumer 名称前缀
     */
    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";
    /**
     * 知识库ID字段
     */
    public static final String FIELD_KB_ID = "kbId";

    // ========== 简历分析 Stream ==========
    /**
     * 简历分析 Stream Key
     */
    public static final String RESUME_ANALYZE_STREAM_KEY = "resume:analyze:stream";
    /**
     * 简历分析 Consumer Group 名称
     */
    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";
    /**
     * 简历分析 Consumer 名称前缀
     */
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";
    /**
     * 简历ID字段
     */
    public static final String FIELD_RESUME_ID = "resumeId";

    // ========== 面试评估 Stream ==========
    /**
     * 面试评估 Stream Key
     */
    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview:evaluate:stream";
    /**
     * 面试评估 Consumer Group 名称
     */
    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";
    /**
     * 面试评估 Consumer 名称前缀
     */
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";
    /**
     * 面试会话ID字段
     */
    public static final String FIELD_SESSION_ID = "sessionId";

    // ========== 语音面试评估 Stream ==========
    /**
     * 语音面试评估 Stream Key
     */
    public static final String VOICE_EVALUATE_STREAM_KEY = "voice:evaluate:stream";
    /**
     * 语音面试评估 Consumer Group 名称
     */
    public static final String VOICE_EVALUATE_GROUP_NAME = "voice-evaluate-group";
    /**
     * 语音面试评估 Consumer 名称前缀
     */
    public static final String VOICE_EVALUATE_CONSUMER_PREFIX = "voice-evaluate-consumer-";
    /**
     * 语音面试会话ID字段
     */
    public static final String FIELD_VOICE_SESSION_ID = "voiceSessionId";

    // ========== 知识库问题生成 Stream ==========
    /**
     * 知识库问题生成 Stream Key
     */
    public static final String KB_QUESTION_GEN_STREAM_KEY = "knowledgebase:question-gen:stream";
    /**
     * 知识库问题生成 Consumer Group 名称
     */
    public static final String KB_QUESTION_GEN_GROUP_NAME = "question-gen-group";
    /**
     * 知识库问题生成 Consumer 名称前缀
     */
    public static final String KB_QUESTION_GEN_CONSUMER_PREFIX = "question-gen-consumer-";
    /**
     * 任务ID字段（用于幂等判断）
     */
    public static final String FIELD_TASK_ID = "taskId";
    /**
     * 难度字段
     */
    public static final String FIELD_DIFFICULTY = "difficulty";
    /**
     * 题目数量字段
     */
    public static final String FIELD_QUESTION_COUNT = "questionCount";
    /**
     * 追问数量字段
     */
    public static final String FIELD_FOLLOW_UP_COUNT = "followUpCount";
    /**
     * 方向数量字段
     */
    public static final String FIELD_CATEGORY_LIMIT = "categoryLimit";
    /**
     * LLM Provider字段
     */
    public static final String FIELD_LLM_PROVIDER = "llmProvider";
}
