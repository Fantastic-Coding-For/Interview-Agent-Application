package com.ywy.interviewagentapplication.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {
    /** 每道题的最大追问题数量 */
    private int followUpCount = 1;
    /** 根据面试维度生成问题的系统提示词 */
    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    /** 根据面试维度生成问题的用户提示词 */
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    /** 根据简历生成问题的系统提示词 */
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    /** 根据简历生成问题的用户提示词 */
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";
}

