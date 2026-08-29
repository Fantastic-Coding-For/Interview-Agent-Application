package com.ywy.interviewagentapplication.modules.interview.skill;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试方向 Skill 管理控制器。
 *
 * <h3>职责</h3>
 * 提供面试方向的查询和 JD 解析接口：
 * <ul>
 *   <li><b>Skill 列表/详情</b>：供前端展示可选的面试方向（如"Java 后端"、"前端开发"），
 *       每个方向包含分类维度、UI 展示配置等</li>
 *   <li><b>JD 解析</b>：将职位描述文本提交给 LLM，自动提取面试考察方向（分类维度），
 *       实现"粘贴 JD 即可开始面试"的零配置体验</li>
 * </ul>
 *
 * @see InterviewSkillService 核心业务逻辑
 * @see InterviewSkillProperties Skill 数据模型定义
 */
@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    public InterviewSkillController(InterviewSkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 获取所有可用的面试方向技能列表。
     */
    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    /**
     * 获取单个面试方向技能的详细信息。
     */
    @GetMapping("/{id}")
    public Result<InterviewSkillService.SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }

    /**
     * 解析职位描述（JD），由 LLM 自动提取面试考察方向。
     * <p>
     * <b>业务场景</b>：用户粘贴一段招聘 JD，系统自动分析出应该考哪些技术方向。
     * 例如：JD 中提到"Spring Boot、MySQL、Redis" → 分类为 [Java核心, 数据库, 缓存]。
     * <p>
     * <b>限流</b>：每 IP 每分钟最多 5 次，LLM 调用成本较高。
     * <p>
     * <b>校验</b>：使用 {@code @Valid} + {@code @NotBlank} 确保 JD 文本不为空。
     *
     * @param request 包含 jdText 的请求体
     * @return LLM 解析出的分类维度列表（key、label、priority、ref）
     */
    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<List<InterviewSkillService.CategoryDTO>> parseJd(@Valid @RequestBody ParseJdRequest request) {
        return Result.success(skillService.parseJd(request.jdText()));
    }

    /**
     * JD 解析请求体。
     */
    public record ParseJdRequest(@NotBlank String jdText) {}
}

