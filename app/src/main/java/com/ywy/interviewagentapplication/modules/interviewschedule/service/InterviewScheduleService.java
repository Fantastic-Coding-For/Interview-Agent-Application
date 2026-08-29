package com.ywy.interviewagentapplication.modules.interviewschedule.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.CreateInterviewRequest;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewScheduleDTO;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewScheduleEntity;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewStatus;
import com.ywy.interviewagentapplication.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试日程管理服务：标准 CRUD + 查询过滤。
 * <p>
 * 类顶部的 COPYABLE_FIELDS 常量声明了「允许拷贝的字段清单」，
 * 是文档性质的约束说明（当前实现用 BeanUtils 而非逐字段 set）。
 *
 * <h3>updateStatus 独立接口的原因</h3>
 * 状态流转是业务语义最重的操作（PENDING→COMPLETED/CANCELLED 等），
 * 与「改个时间/链接」的编辑语义分离：独立接口便于后续加状态机校验
 * （如禁止 COMPLETED→PENDING）、审计日志、消息通知，而不影响编辑接口。
 */
@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    /**
     * 允许从请求体拷贝到实体的字段清单（id/status 等管理字段除外）。
     */
    private static final String[] COPYABLE_FIELDS = {
            "companyName", "position", "interviewTime", "interviewType",
            "meetingLink", "roundNumber", "interviewer", "notes"
    };

    /**
     * 创建日程：状态强制置 PENDING（忽略请求中可能带的任何状态）。
     */
    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    /**
     * 更新日程：忽略 id 与 status 的拷贝，其他字段整体覆盖。
     *
     * <p>整体覆盖（非部分更新）语义：请求体的字段即最终值——
     * 面试日程是简单结构，前端提交完整表单比 PATCH 语义更直观，
     * 也避免「null 到底是不改还是清空」的歧义。
     */
    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    /**
     * 删除日程（不存在也静默成功——删除是幂等操作）。
     */
    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * 更新状态。
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    /**
     * 查询面试日程 DTO 列表。
     *
     * <p>过滤优先级设计：时间范围与状态是互斥的过滤维度（时间范围优先）。start+end 同时给定时按时间范围查
     * （日历视图的典型用法），否则按状态查，都为空则全量。
     * 状态字符串非法（valueOf 抛异常）由全局异常处理器兜底。
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;

        if (start != null && end != null) {
            entities = repository.findByInterviewTimeBetween(start, end);
        } else if (status != null) {
            entities = repository.findByStatus(InterviewStatus.valueOf(status));
        } else {
            entities = repository.findAll();
        }

        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 按 ID 查询（不存在抛业务异常）。
     */
    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    /**
     * 统一「查不到即抛异常」的语义：避免每个方法重复 orElseThrow 样板。
     */
    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
    }

    /**
     * 面试日程实体 → 面试日程 DTO 的映射（同名字段整体拷贝）。
     */
    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}

