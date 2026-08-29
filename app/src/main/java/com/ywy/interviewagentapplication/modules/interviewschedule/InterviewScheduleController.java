package com.ywy.interviewagentapplication.modules.interviewschedule;

import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.CreateInterviewRequest;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewScheduleDTO;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewStatus;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.ParseRequest;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.ParseResponse;
import com.ywy.interviewagentapplication.modules.interviewschedule.service.InterviewParseService;
import com.ywy.interviewagentapplication.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理控制器
 *
 * <h3>「解析 + CRUD」的双域控制器</h3>
 * 两个服务聚合在此：
 * <ul>
 *   <li><b>/parse</b>：面试邀约文本解析（InterviewParseService 的规则+AI 两级解析）</li>
 *   <li><b>其余端点</b>：日程记录的标准 CRUD 与状态管理</li>
 * </ul>
 *
 * <h3>查询接口的二维过滤</h3>
 * GET / 支持 status（状态）过滤、start+end（时间范围）组合过滤。
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

    private final InterviewScheduleService scheduleService;
    private final InterviewParseService parseService;

    /**
     * 解析面试邀约文本。
     *
     * @param request 解析请求
     * @return 解析结果
     */
    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        log.info("接收到解析请求，来源: {}", request.getSource());
        ParseResponse response = parseService.parse(request.getRawText(), request.getSource());
        return Result.success(response);
    }

    /**
     * 创建面试记录
     *
     * @param request 创建请求
     * @return 创建的面试记录
     */
    @PostMapping
    public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("创建面试记录: {} - {}", request.getCompanyName(), request.getPosition());
        InterviewScheduleDTO dto = scheduleService.create(request);
        return Result.success(dto);
    }

    /**
     * 根据ID获取面试记录
     *
     * @param id 面试记录ID
     * @return 面试记录详情
     */
    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
        InterviewScheduleDTO dto = scheduleService.getById(id);
        return Result.success(dto);
    }

    /**
     * 获取面试记录列表（支持筛选）
     *
     * @param status 状态过滤（可选）
     * @param start 开始时间（可选）
     * @param end 结束时间（可选）
     * @return 面试记录列表
     */
    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        List<InterviewScheduleDTO> list = scheduleService.getAll(status, start, end);
        return Result.success(list);
    }

    /**
     * 更新面试记录（忽略 id 与 status 的拷贝，其他字段整体覆盖）。
     *
     * @param id 面试记录ID
     * @param request 更新请求
     * @return 更新后的面试记录
     */
    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateInterviewRequest request
    ) {
        log.info("更新面试记录: ID={}", id);
        InterviewScheduleDTO dto = scheduleService.update(id, request);
        return Result.success(dto);
    }

    /**
     * 删除面试记录
     *
     * @param id 面试记录ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除面试记录: ID={}", id);
        scheduleService.delete(id);
        return Result.success(null);
    }

    /**
     * 更新面试状态
     *
     * @param id 面试记录ID
     * @param status 新状态
     * @return 更新后的面试记录
     */
    @RequestMapping(path = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<InterviewScheduleDTO> updateStatus(
            @PathVariable Long id,
            @RequestParam InterviewStatus status
    ) {
        log.info("更新面试状态: ID={}, status={}", id, status);
        InterviewScheduleDTO dto = scheduleService.updateStatus(id, status);
        return Result.success(dto);
    }
}

