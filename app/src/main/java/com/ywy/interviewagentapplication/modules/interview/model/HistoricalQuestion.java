package com.ywy.interviewagentapplication.modules.interview.model;

/**
 * 历史面试题目摘要：用于新面试出题时的<b>知识点去重</b>。
 *
 * <h3>业务场景</h3>
 * 当候选人进行多轮面试时，后面的面试不应重复考察前面已问过的知识点。
 * 本 record 携带历史题目的核心信息（题面 + 类型 + 知识点），
 * 在出题前注入 LLM 提示词中，告知 LLM "这些已经问过了，不要再出相似的"。
 *
 * <h3>为什么用 topicSummary 而非直接用 question 原文去重？</h3>
 * 两个措辞不同的问题可能考察同一个知识点（如"谈谈你对 Redis 持久化的理解"
 * 和"Redis 的 RDB 和 AOF 有什么区别"），用知识点摘要去重比用原文匹配更精准。
 * LLM 能理解"持久化对比"和"Redis RDB/AOF"是同一个主题，但字符串匹配做不到。
 * <p>
 *
 * @param question      原始题面文本
 * @param type          Skill 分类 key（如 "MYSQL"、"JAVA"），用于分组归类
 * @param topicSummary  知识点摘要（如 "Redis RDB/AOF 持久化对比"）；可为 null
 */
public record HistoricalQuestion(String question, String type, String topicSummary) {}

