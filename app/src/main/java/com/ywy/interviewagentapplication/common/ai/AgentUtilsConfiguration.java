package com.ywy.interviewagentapplication.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 将所有 Skills 通过 spring-ai-agent-utils（Spring ai） 社区库的 SkillsTool，
 * 转换为符合 AI 模型调用规范的标准单元（函数），使大模型能够通过函数调用的方式使用 Skill（外部工具），并能够返回结果供模型进一步推理
 * 为什么需要 ToolCallback？
 * 面试时 LLM 需要参考技能大纲出题，如果让 LLM 自由发挥可能偏离面试方向。
 * SkillsTool 将技能文件作为"工具"暴露给 LLM，LLM 在需要时调用工具查阅知识点。
 * 被依赖方：LlmProviderRegistry（将 ToolCallback 注入 ChatClient 的 defaultTools）
 */
@Configuration
@Slf4j
public class AgentUtilsConfiguration {

    private final ResourceLoader resourceLoader;
    private final AgentUtilsProperties agentUtilsProperties;

    public AgentUtilsConfiguration(
            ResourceLoader resourceLoader, AgentUtilsProperties agentUtilsProperties
    ) {
        this.resourceLoader = resourceLoader;
        this.agentUtilsProperties = agentUtilsProperties;
    }

    /**
     * 注册 SkillsTool Bean：面试技能知识库的 Function Calling 接口
     * Bean 名称为 "interviewSkillsToolCallback"，LlmProviderRegistry 通过 @Qualifier 注入
     */
    @Bean("interviewSkillsToolCallback")
    public ToolCallback interviewSkillsToolCallback() {
        String configuredSkillsRoot = agentUtilsProperties.getSkillsRoot();
        String normalizedSkillsRoot = normalizeSkillsRoot(configuredSkillsRoot);
        Resource skillsRootResource = resourceLoader.getResource(normalizedSkillsRoot);

        if (!skillsRootResource.exists()) {
            throw new IllegalStateException(
                    "未找到 skills 根目录，请检查配置: " + normalizedSkillsRoot);
        }

        log.info("AgentUtils SkillsTool 已启用，skillsRoot={}, configured={}",
                normalizedSkillsRoot, configuredSkillsRoot);

        return SkillsTool.builder()
                .addSkillsResource(skillsRootResource)
                .build();
    }

    /** 规范化 skillsRoot 路径：去通配符、去 SKILL.md 后缀、去尾部斜杠 */
    private String normalizeSkillsRoot(String raw) {
        if (raw == null || raw.isBlank()) return "classpath:skills";
        String normalized = raw.trim().replace('\\', '/');
        if (normalized.endsWith("/SKILL.md")) {
            normalized = normalized.substring(0, normalized.length() - "/SKILL.md".length());
        }
        int wildcardIndex = normalized.indexOf('*');
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "classpath:skills" : normalized;
    }
}
