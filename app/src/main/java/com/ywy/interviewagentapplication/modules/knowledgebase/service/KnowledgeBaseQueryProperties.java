package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库 RAG 查询的配置属性。
 *
 * <h3>配置分组设计</h3>
 * 按 RAG 管线的三个环节分组（对应嵌套类）：
 * <ul>
 *   <li><b>Rewrite</b>：查询改写——将口语化/模糊的问题改写为更利于检索的形式</li>
 *   <li><b>Search</b>：向量检索参数——按问题长度动态调整 topK 和相似度阈值</li>
 *   <li><b>History</b>：多轮上下文——历史消息的加载策略</li>
 * </ul>
 *
 * <h3>动态检索参数的设计动机</h3>
 * 检索质量与问题长度强相关：
 * <ul>
 *   <li><b>短问题</b>（如"什么是RDB？"，≤4 字）：信息量少，检索需要放宽——
 *       更大的 topK（20）和更低的阈值（0.25）以增加命中机会</li>
 *   <li><b>长问题</b>（如"对比Redis的RDB和AOF持久化方式的优缺点"，>12字）：
 *       信息量足，检索可以收紧——更小的 topK（8）减少无关内容，提高回答精度</li>
 * </ul>
 * 阈值差异的理由：短问题改写后的查询向量往往更分散（歧义大），
 * 相似度整体偏低，需要降低门槛；长问题语义明确，向量集中，高阈值即可命中。
 *
 * <h3>为什么相似度阈值这么低（0.25-0.28）？</h3>
 * Embedding 模型的余弦相似度分布通常集中在 0.3-0.8 区间，
 * 0.25 是"弱相关"的下界。设得太高（如 0.6）会漏掉大量
 * "表述不同但内容相关"的文档；设得太低（如 0.1）会引入噪音。
 * 0.25-0.28 是经过测试的经验值，可根据实际 Embedding 模型调整。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {
    /** 查询改写配置 */
    private Rewrite rewrite = new Rewrite();
    /** 向量检索配置 */
    private Search search = new Search();
    /** 多轮上下文配置 */
    private History history = new History();
    /** 系统提示词模板路径（定义 LLM 角色和回答规则） */
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    /** 用户提示词模板路径（注入检索上下文和问题） */
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    /** 查询改写提示词模板路径（问题改写专用） */
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    /**
     * 查询改写配置。
     * <p>
     * 改写解决的问题：用户在多轮对话中的追问往往省略上下文——
     * 如第二轮问"那它的缺点呢？"（"它"指第一轮讨论的 Redis RDB）。
     * 不改写直接检索，"它的缺点"会命中大量无关文档。
     * 改写让 LLM 结合历史将问题补全为"Redis RDB 持久化方式的缺点"。
     */
    @Data
    public static class Rewrite {
        /** 是否启用查询改写。关闭后直接用原问题检索（省一次 LLM 调用，但多轮追问效果变差） */
        private boolean enabled = true;
    }

    /**
     * 向量检索参数配置，按问题长度分三档。
     */
    @Data
    public static class Search {
        /** 短问题的字符数阈值（≤4 字符视为短问题） */
        private int shortQueryLength = 4;
        /** 短问题的 topK，放宽检索量 */
        private int topkShort = 20;
        /** 中等问题（5-12 字）的 topK */
        private int topkMedium = 12;
        /** 长问题（>12 字）的 topK，收紧检索量 */
        private int topkLong = 8;
        /** 短问题的相似度阈值，放宽门槛 */
        private double minScoreShort = 0.25;
        /** 中/长问题的相似度阈值 */
        private double minScoreDefault = 0.28;
    }

    /**
     * 多轮上下文配置。
     * <p>
     * maxMessages = 10：只取最近 10 条历史消息作为上下文。
     * 上限设计的原因：
     * <ul>
     *   <li>控制 token 消耗——历史消息直接计入 LLM 输入</li>
     *   <li>对话越久远与当前问题的相关性越低，而10 条覆盖了绝大多数多轮追问场景（尤其跨知识库切换后）</li>
     * </ul>
     */
    @Data
    public static class History {
        /** 是否携带历史消息（多轮上下文） */
        private boolean enabled = true;
        /** 最多携带的历史消息数 */
        private int maxMessages = 10;
    }
}

