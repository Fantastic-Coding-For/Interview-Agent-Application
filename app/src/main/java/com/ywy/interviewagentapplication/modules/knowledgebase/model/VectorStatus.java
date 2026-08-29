package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库文档向量化状态枚举。
 *
 * <h3>状态机</h3>
 * <pre>
 *   PENDING ──→ PROCESSING ──→ COMPLETED
 *      │            │
 *      └────────────┴──→ FAILED ──→(手动 revectorize)──→ PENDING
 * </pre>
 * <ul>
 *   <li><b>PENDING</b>：上传完成，向量化任务已入队但尚未被消费</li>
 *   <li><b>PROCESSING</b>：消费者正在执行向量化（分块 + Embedding + 存储）</li>
 *   <li><b>COMPLETED</b>：向量化成功，知识库可参与 RAG 检索</li>
 *   <li><b>FAILED</b>：向量化失败（记录 vectorError），可通过 /revectorize 接口手动重试</li>
 * </ul>
 *
 * <h3>持久化方式</h3>
 * 使用 {@code @Enumerated(EnumType.STRING)} 存储。枚举名直接存入数据库
 * （而非 ordinal 数字）。STRING 的优势：
 * <ul>
 *   <li>数据库值可读（"PENDING" vs "0"）</li>
 *   <li>枚举重排/插入新值时不会破坏已有数据（ordinal 会因为枚举顺序变化而错位）</li>
 * </ul>
 * 代价是占用更多存储空间（20 字符 vs 1 字节），对状态枚举来说完全可接受。
 */
public enum VectorStatus {
    /** 待处理——任务已入队，等待消费者 */
    PENDING,
    /** 处理中——消费者正在执行向量化 */
    PROCESSING,
    /** 完成——向量化成功，可用于检索 */
    COMPLETED,
    /** 失败——向量化出错，可手动重试 */
    FAILED
}

