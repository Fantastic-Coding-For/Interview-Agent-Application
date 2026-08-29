package com.ywy.interviewagentapplication.common.result;

import com.ywy.interviewagentapplication.common.constant.CommonConstants;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 统一业务响应结果
 * 设计原因：
 *   1. 全项目 API 统一返回此格式：{ code: 200, message: "success", data: {...} }
 *   2. 异常返回也走此格式：{ code: 400, message: "参数错误", data: null }
 *   3. 将业务结果和异常情况统一封装，前端只需解析一种业务响应结构，通过 code 判断业务执行情况
 * 设计要点：
 *   - 私有构造器：强制通过静态工厂方法创建，语义清晰
 *   - 所有字段 final：不可变对象，线程安全，可安全缓存
 *   - 泛型 T：不同 API 返回不同 data 类型，编译期类型安全
 * 被依赖方：GlobalExceptionHandler、所有 Controller
 *
 * @param <T> 响应数据的类型（如 ResumeDTO、List<KnowledgeBaseDTO> 等）
 */
@Getter
public class Result<T> {

    /** HTTP 状态码（200=成功，其他=业务错误码） */
    private final Integer code;
    /** 响应消息（成功时为 "success"，失败时为具体错误描述） */
    private final String message;
    /** 响应数据（成功时可能有值，失败时为 null） */
    private final T data;

    /**
     * 私有构造器——外部只能通过静态工厂方法创建实例
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功响应工厂方法 ==========

    /** 成功（无数据）——用于 DELETE / PUT 等不需要返回数据的操作 */
    public static <T> Result<T> success() {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", null);
    }

    /** 成功（带数据）——最常用的方法 */
    public static <T> Result<T> success(T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", data);
    }

    /** 成功（自定义消息 + 带数据） */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, message, data);
    }

    // ========== 失败响应工厂方法 ==========

    /** 失败（仅消息）——使用默认 SERVER_ERROR 状态码 */
    public static <T> Result<T> error(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null);
    }

    /** 失败（自定义 code + 消息） */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败（ErrorCode 枚举）——最推荐的方式，错误码和消息统一管理 */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败（ErrorCode + 自定义消息）——覆盖错误码枚举中的默认消息 */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    // ========== 辅助方法 ==========

    /**
     * 判断响应是否成功
     */
    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
