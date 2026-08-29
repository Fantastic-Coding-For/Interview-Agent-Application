package com.ywy.interviewagentapplication.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目规范：所有配置统一使用 @ConfigurationProperties，禁止在 Service 中散落 @Value。
 * Spring AI Agent Utils 配置属性
 * 绑定前缀：app.ai.agent-utils
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.agent-utils")
public class AgentUtilsProperties {

    /** 技能文件的根路径 */
    private String skillsRoot = "classpath:skills";
}
