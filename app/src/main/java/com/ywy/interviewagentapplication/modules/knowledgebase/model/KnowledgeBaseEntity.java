package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库实体：知识库模块的核心数据模型。
 *
 * <h3>职责概述</h3>
 * 一条记录代表用户上传的一份文档（PDF/Word/Markdown 等），包含：
 * <ul>
 *   <li><b>文件元数据</b>：文件名、大小、类型、存储位置</li>
 *   <li><b>去重信息</b>：fileHash（SHA-256，唯一约束）</li>
 *   <li><b>使用统计</b>：访问次数、提问次数</li>
 *   <li><b>异步处理状态</b>：向量化状态、问题生成状态</li>
 * </ul>
 *
 * <h3>索引设计</h3>
 * <ul>
 *   <li><b>idx_kb_hash（唯一）</b>：fileHash 列——设计为数据库层面的唯一约束，去重查询的快速路径</li>
 *   <li><b>idx_kb_category</b>：category 列——分类筛选是列表页的高频操作</li>
 * </ul>
 *
 * 项目中的其他实体（RagChatMessageEntity 等）使用 Lombok——本实体是早期代码的遗留风格。功能上两者等价。
 *
 * <h3>@PrePersist 生命周期回调</h3>
 * 在实体插入到数据库表中之前，自动初始化 uploadedAt、lastAccessedAt、accessCount=1，保证这些统计字段永不为 null，避免调用方在保存前手动设置。
 */
@Entity
@Table(name = "knowledge_bases", indexes = {
        @Index(name = "idx_kb_hash", columnList = "fileHash", unique = true),
        @Index(name = "idx_kb_category", columnList = "category")
})
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文件内容的 SHA-256 哈希值（64 个十六进制字符）。
     * 用于内容级去重：同名不同内容的文件哈希不同，异名同内容的文件哈希相同。
     * 唯一约束保证数据库层面不可能出现两份相同内容的知识库。
     */
    @Column(nullable = false, unique = true, length = 64)
    private String fileHash;

    /**
     * 知识库名称（用户自定义或从文件名提取）
     */
    @Column(nullable = false)
    private String name;

    /** 分类/分组（如 "Java面试"、"项目文档"），null = 未分类 */
    @Column(length = 100)
    private String category;

    /**
     * 原始上传文件名
     */
    @Column(nullable = false)
    private String originalFilename;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /** MIME 类型（如 "application/pdf"），由内容检测服务识别 */
    private String contentType;

    /** RustFS 对象存储中的文件 Key（下载/删除时使用） */
    @Column(length = 500)
    private String storageKey;

    /** RustFS 存储的文件 URL */
    @Column(length = 1000)
    private String storageUrl;

    /**
     * 上传时间
     */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 访问次数，初始为 1（上传行为本身视为一次访问）
     */
    private Integer accessCount = 0;

    /** 参与回答的次数（该知识库被用于 RAG 查询的次数） */
    private Integer questionCount = 0;

    /**
     * 向量化任务的执行状态：新上传时为 PENDING，异步处理完成后变为 COMPLETED。
     * 状态迁移：PENDING → PROCESSING → COMPLETED / FAILED。
     * 只有 COMPLETED 状态的知识库才会参与 RAG 检索。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VectorStatus vectorStatus = VectorStatus.PENDING;

    /** 向量化错误信息（用于状态为 FAILED 时记录原因） */
    @Column(length = 500)
    private String vectorError;

    /** 向量分块数量（文本被切分为多少个 chunk） */
    private Integer chunkCount = 0;

    /** 问题生成任务的执行状态 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuestionGenStatus questionGenStatus = QuestionGenStatus.NONE;

    /**
     * 问题生成错误信息（失败时记录）
     */
    @Column(length = 500)
    private String questionGenError;

    /**
     * 问题生成任务 ID：用于幂等判断，防止旧任务的结果覆盖新任务。
     * <p>
     * 场景：用户对同一知识库发起两次问题生成请求，
     * 第一次任务还在执行时第二次任务启动。第二次任务完成后，
     * 第一次任务的迟到结果不应覆盖第二次的结果。
     */
    @Column(length = 36)
    private String questionGenTaskId;

    /**
     * 问题生成参数快照（不包含 Prompt、上下文或密钥）
     */
    @Column(columnDefinition = "TEXT")
    private String questionGenConfig;

    /**
     * 问题生成结果摘要
     */
    @Column(length = 500)
    private String questionGenMessage;

    /** 问题生成成功保存的题目数量 */
    private Integer questionGenSavedCount = 0;

    /** 问题生成时跳过（去重）的题目数量 */
    private Integer questionGenSkippedCount = 0;

    /** 问题生成任务的最后更新时间（用于检测卡死的任务） */
    private LocalDateTime questionGenUpdatedAt;

    /**
     * JPA 生命周期回调：将实体插入到数据库表前，自动初始化统计字段。
     * <p>
     * 保证 uploadedAt/lastAccessedAt 永不为 null，
     * accessCount 从 1 开始（上传行为本身视为一次访问）。
     */
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStorageUrl() {
        return storageUrl;
    }

    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Integer getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public VectorStatus getVectorStatus() {
        return vectorStatus;
    }

    public void setVectorStatus(VectorStatus vectorStatus) {
        this.vectorStatus = vectorStatus;
    }

    public String getVectorError() {
        return vectorError;
    }

    public void setVectorError(String vectorError) {
        this.vectorError = vectorError;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public QuestionGenStatus getQuestionGenStatus() {
        return questionGenStatus;
    }

    public void setQuestionGenStatus(QuestionGenStatus questionGenStatus) {
        this.questionGenStatus = questionGenStatus;
    }

    public String getQuestionGenError() {
        return questionGenError;
    }

    public void setQuestionGenError(String questionGenError) {
        this.questionGenError = questionGenError;
    }

    public String getQuestionGenTaskId() {
        return questionGenTaskId;
    }

    public void setQuestionGenTaskId(String questionGenTaskId) {
        this.questionGenTaskId = questionGenTaskId;
    }

    public String getQuestionGenConfig() {
        return questionGenConfig;
    }

    public void setQuestionGenConfig(String questionGenConfig) {
        this.questionGenConfig = questionGenConfig;
    }

    public String getQuestionGenMessage() {
        return questionGenMessage;
    }

    public void setQuestionGenMessage(String questionGenMessage) {
        this.questionGenMessage = questionGenMessage;
    }

    public Integer getQuestionGenSavedCount() {
        return questionGenSavedCount;
    }

    public void setQuestionGenSavedCount(Integer questionGenSavedCount) {
        this.questionGenSavedCount = questionGenSavedCount;
    }

    public Integer getQuestionGenSkippedCount() {
        return questionGenSkippedCount;
    }

    public void setQuestionGenSkippedCount(Integer questionGenSkippedCount) {
        this.questionGenSkippedCount = questionGenSkippedCount;
    }

    public LocalDateTime getQuestionGenUpdatedAt() {
        return questionGenUpdatedAt;
    }

    public void setQuestionGenUpdatedAt(LocalDateTime questionGenUpdatedAt) {
        this.questionGenUpdatedAt = questionGenUpdatedAt;
    }
}

