package com.ywy.interviewagentapplication.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面试评估模块的配置属性。
 *
 * <h3>架构角色</h3>
 * 将评估相关的可调参数从代码中抽离到 application.yml，实现<b>配置即文档</b>。
 *
 * <h3>为什么使用 {@code @ConfigurationProperties} 而非 {@code @Value}？</h3>
 * <ul>
 *   <li><b>结构化绑定</b>：所有评估配置集中在 {@code app.interview.evaluation} 前缀下，
 *       避免了 {@code @Value} 的分散声明，便于管理和查找。</li>
 *   <li><b>类型安全 + 默认值</b>：字段直接使用 Java 类型（如 int 而非 String），
 *       Spring Boot 的宽松绑定规则支持将 YAML 的数值自动转换，类型错误在启动时即可发现。</li>
 *   <li><b>IDE 支持</b>：配合 spring-boot-configuration-processor 生成元数据，
 *       IDE 可以提供自动补全和文档提示。</li>
 * </ul>
 *
 * <h3>提示词模板路径设计</h3>
 * 提示词文件（.st 即 StringTemplate 格式）放在 classpath 而非外部路径，
 * 原因：提示词是代码逻辑的一部分，版本需要与代码同步管理（Git 版本控制），
 * 且部署时作为 jar 内资源打包，避免了文件路径问题和环境差异。
 *
 * @see UnifiedEvaluationService 属性的消费者
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {
    /**
     * LLM 评估的批次大小，默认 8 题/批次。
     */
    private int batchSize = 8;

    /**
     * 一轮评估的系统提示词模板路径。
     * 内容定义 LLM 的角色（面试官）、评分标准（Rubric）、输出格式等。
     */
    private String systemPromptPath = "classpath:prompts/interview-evaluation-system.st";

    /**
     * 一轮评估的用户提示词模板路径。
     * 内容包含待评估的问答记录、简历摘要等具体数据。
     * 与 systemPromptPath 分离是因为：system prompt 定义"如何评"（角色+规则），
     * user prompt 提供"评什么"（具体内容），符合 LLM API 的最佳实践。
     */
    private String userPromptPath = "classpath:prompts/interview-evaluation-user.st";
    /**
     * 二次汇总的系统提示词模板路径。
     * <p>
     * 为什么需要二次汇总？分批评估后，每个批次独立生成了一份总结（strengths/improvements/feedback），
     * 这些总结可能存在冗余（相同观点重复出现）、矛盾（A 批次说好 B 批次说差）、
     * 或缺失（某个维度在所有批次中都被忽略了）。二次汇总让 LLM 站在全局视角对
     * 所有批次的中间结果进行整合、去重、矛盾消解，生成一份统一的总评。
     * <p>
     * 与一轮评估分开提示词是因为：汇总任务的角色不同。不再是"逐题打分"，而是"综合归纳"。
     */
    private String summarySystemPromptPath = "classpath:prompts/interview-evaluation-summary-system.st";

    /**
     * 二次汇总的用户提示词模板路径。
     * 内容包含所有批次的中间结果（分类摘要、亮点、兜底反馈等）。
     */
    private String summaryUserPromptPath = "classpath:prompts/interview-evaluation-summary-user.st";
}

