package com.ywy.interviewagentapplication.common.exception;

import lombok.Getter;

/**
 * 业务异常——项目中所有业务错误的唯一抛出类型
 * 设计原因：
 *   1. 统一携带 ErrorCode（数字 code + 中文 message）
 *   2. 全局异常处理器 @ExceptionHandler(BusinessException.class) 统一拦截，统一处理所有异常，而不需要在每个异常处显式捕获处理
 *   3. 禁止抛出非 BusinessException，例如：throw new RuntimeException("xxx")——这样会丢失业务错误码
 * 设计要点：
 *   - 继承 RuntimeException：非受检异常，避免强制显式处理，从而不做任何处理就能直接传播到全局异常处理器进行统一处理
 *   - 提供多个构造器：覆盖"仅ErrorCode"、"ErrorCode+描述"、"带原始异常"等使用场景
 *   - 通过 Lombok 提供的模板 get() 方法，覆盖 RuntimeException.getMessage()，返回业务消息而非异常类名
 * 使用方式示例：
 *   throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
 *   throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "PDF文件已损坏");
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码（来自 ErrorCode 枚举的 code 字段） */
    private final Integer code;
    /** 业务错误消息（可能覆盖 ErrorCode 枚举的默认 message） */
    private final String message;

    /** 构造器1：仅 ErrorCode——使用枚举默认消息 */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    /** 构造器2：ErrorCode + 自定义消息——最常用 */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** 构造器3：自定义 code + 消息——用于不预定义 ErrorCode 的临时错误 */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /** 构造器4：仅消息——默认使用 INTERNAL_ERROR */
    public BusinessException(String message) {
        this(ErrorCode.INTERNAL_ERROR.getCode(), message);
    }

    /** 构造器5：消息 + 原始异常——用于 try-catch 包裹场景 */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    /** 构造器6：ErrorCode + 消息 + 原始异常——最完整的异常信息 */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
    }
}
