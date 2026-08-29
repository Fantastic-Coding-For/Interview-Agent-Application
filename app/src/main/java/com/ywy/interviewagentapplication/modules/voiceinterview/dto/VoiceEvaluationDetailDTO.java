package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语音面试评估详情 DTO。
 *
 * <h3>与文本面试 InterviewDetailDTO 对齐的动机</h3>
 * 字段结构刻意与文本面试的评估详情保持一致，使前端能<b>复用同一个 InterviewDetailPanel 组件</b>
 * 渲染两种面试形态的评估报告——「语音面试」对前端来说只是换了一个数据源。
 *
 * <h3>AnswerDetail 的组装语义</h3>
 * 逐题评估（分数/反馈）与参考回答（要点）原本在评估引擎中是两份列表，
 * 在 Service 层按 questionIndex 对齐后合成 AnswerDetail——
 * 前端拿到的每一题都是「问题+回答+分数+反馈+参考答案+要点」的完整单元。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

    private Long sessionId;
    /** 总题数（answers 的大小，冗余字段便于前端直接展示「共 N 题」） */
    private int totalQuestions;
    /** 总评分。空评估记录（无对话）时为 null，前端据此隐藏分数展示 */
    private Integer overallScore;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<AnswerDetail> answers;

    /**
     * 单题评估明细（评估 + 参考回答的合并视图）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDetail {
        /** 题目序号（与评估引擎的 questionIndex 一致，0 起） */
        private int questionIndex;
        /** 面试官提问原文 */
        private String question;
        /** 题目类别 */
        private String category;
        /** 候选人回答（ASR 转写文本） */
        private String userAnswer;
        /** 该题得分 */
        private int score;
        /** 该题反馈 */
        private String feedback;
        /** 参考回答（可能为 null：评估引擎未生成时） */
        private String referenceAnswer;
        /** 参考回答要点（可能为 null） */
        private List<String> keyPoints;
    }
}

