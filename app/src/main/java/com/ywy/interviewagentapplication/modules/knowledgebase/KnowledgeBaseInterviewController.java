package com.ywy.interviewagentapplication.modules.knowledgebase;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatusResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionStatusRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseInterviewService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseQuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 知识库题库与知识库面试 REST API 控制器。
 *
 * <h3>API 分组</h3>
 * <ul>
 *   <li><b>题库管理</b>：题目列表（筛选）、方向统计、手动增删改</li>
 *   <li><b>题目生成</b>：异步生成任务提交 + 状态轮询</li>
 *   <li><b>知识库面试</b>：从题库抽题创建面试 + 容量查询</li>
 * </ul>
 *
 * <h3>限流策略</h3>
 * 仅对 generateQuestions（LLM 批量生成，最昂贵）限流 2次/秒——
 * 其他接口是数据库 CRUD 或题库抽取（无 LLM 调用），无需限流。
 *
 * <h3>题目生成的前端交互模式</h3>
 * submitGenerationTask 返回 QUEUED 状态 → 前端轮询
 * generation-status 接口 → COMPLETED 后刷新题目列表。
 * 这是"提交-轮询"异步模式（生成耗时 1-3 分钟，不适合同步等待）。
 */
@RestController
@Validated
@RequiredArgsConstructor
public class KnowledgeBaseInterviewController {

    private final KnowledgeBaseQuestionService questionService;
    private final KnowledgeBaseInterviewService interviewService;

    /**
     * 列出知识库下的题目（支持状态/方向/难度/关键词四维筛选）。
     * <p>
     * 数据库只按状态过滤（两种查询路径），其余三维在内存过滤——
     * 关键词匹配覆盖题干、参考答案、评分规则、摘要、方向五个文本字段（不区分大小写）。
     */
    @GetMapping("/api/knowledgebase/{id}/questions")
    public Result<List<KnowledgeBaseQuestionDTO>> listQuestions(
            @PathVariable Long id,
            @RequestParam(value = "status", required = false) KnowledgeBaseQuestionStatus status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "difficulty", required = false) String difficulty,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(questionService.listQuestions(id, status, category, difficulty, keyword));
    }

    /**
     * 列出指定知识库下出现过的方向（含题目计数，按频次降序）。 用于前端下拉筛选和"开始面试"弹窗的方向选择。
     */
    @GetMapping("/api/knowledgebase/{id}/questions/categories")
    public Result<List<CategoryCount>> listCategories(@PathVariable Long id) {
        return Result.success(questionService.listCategories(id));
    }

    /**
     * 提交异步题目生成任务（LLM 基于知识库内容批量生成题目）。
     * <p>
     * <b>限流</b>：全局 + IP 各 2次/秒——LLM 批量生成是知识库模块最贵的操作
     * （单次生成 30 题消耗大量 token，耗时 1-3 分钟）。
     * <p>
     * 返回 QUEUED 状态响应——实际生成在 Redis Stream 消费者中异步执行，
     * 前端通过 generation-status 接口轮询进度。
     */
    @PostMapping("/api/knowledgebase/{id}/questions/generate")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<QuestionGenStatusResponse> generateQuestions(
            @PathVariable Long id,
            @Valid @RequestBody GenerateKnowledgeBaseQuestionsRequest request) {
        return Result.success(questionService.submitGenerationTask(id, request));
    }

    /**
     * 查询题目生成任务的当前状态（前端轮询接口）。
     */
    @GetMapping("/api/knowledgebase/{id}/questions/generation-status")
    public Result<QuestionGenStatusResponse> getQuestionGenerationStatus(@PathVariable Long id) {
        return Result.success(questionService.getGenerationStatus(id));
    }

    /**
     * 手动创建题目（补充/修正 LLM 生成的题库）。
     * 默认状态 DRAFT，因为手动创建的题目同样走审核流程。
     */
    @PostMapping("/api/knowledgebase/{id}/questions")
    public Result<KnowledgeBaseQuestionDTO> createQuestion(
            @PathVariable Long id,
            @Valid @RequestBody CreateKnowledgeBaseQuestionRequest request) {
        return Result.success(questionService.createQuestion(id, request));
    }

    /**
     * 更新题目（部分更新语义，null 字段表示不修改）。
     * 注意无 @Valid，因为部分更新请求的校验在 Service 层逐字段进行
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}")
    public Result<KnowledgeBaseQuestionDTO> updateQuestion(
            @PathVariable Long questionId,
            @RequestBody UpdateKnowledgeBaseQuestionRequest request) {
        return Result.success(questionService.updateQuestion(questionId, request));
    }

    /**
     * 更新题目状态（审核操作：DRAFT → ACTIVE 等）。
     * 独立接口而非合并到 updateQuestion——状态切换是高频操作，
     * 轻量请求体 + 明确的语义让前端审核面板的调用更简洁。
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}/status")
    public Result<KnowledgeBaseQuestionDTO> updateQuestionStatus(
            @PathVariable Long questionId,
            @Valid @RequestBody UpdateKnowledgeBaseQuestionStatusRequest request) {
        return Result.success(questionService.updateStatus(questionId, request.status()));
    }

    /** 删除指定的题目（物理删除） */
    @DeleteMapping("/api/knowledgebase/questions/{questionId}")
    public Result<Void> deleteQuestion(@PathVariable Long questionId) {
        questionService.deleteQuestion(questionId);
        return Result.success(null);
    }

    /**
     * 创建知识库面试：从题库抽取已启用题目组建面试会话。
     * <p>
     * 与普通面试（LLM 实时出题）的区别：题目来自预生成题库，
     * 创建过程无 LLM 出题调用（毫秒级完成），题目质量经过人工审核。
     * 但面试后续的答案评估仍使用 LLM。
     */
    @PostMapping("/api/knowledgebase-interviews/sessions")
    public Result<InterviewSessionDTO> createInterviewSession(
            @Valid @RequestBody CreateKnowledgeBaseInterviewRequest request) {
        return Result.success(interviewService.createSession(request));
    }

    /**
     * 查询知识库面试在指定方向、难度和主问题数下，不同追问题数策略的可用性（包含题目容量）。
     * <p>
     * 返回各方向的可用题目数和各追问档位的可选性，
     * 前端据此渲染/禁用选项（见 KnowledgeBaseInterviewCapacityResponse 注释）。
     * <p>
     * mainQuestionCount 的 @Min/@Max 校验依赖类级 @Validated。
     */
    @GetMapping("/api/knowledgebase/{id}/interview-capacity")
    public Result<KnowledgeBaseInterviewCapacityResponse> getInterviewCapacity(
            @PathVariable Long id,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "difficulty", defaultValue = "mid") String difficulty,
            @RequestParam(value = "mainQuestionCount", defaultValue = "5")
            @Min(value = 1, message = "主问题数量最少1题")
            @Max(value = 20, message = "主问题数量最多20题")
            int mainQuestionCount) {
        return Result.success(
                interviewService.getCapacity(id, category, difficulty, mainQuestionCount));
    }
}

