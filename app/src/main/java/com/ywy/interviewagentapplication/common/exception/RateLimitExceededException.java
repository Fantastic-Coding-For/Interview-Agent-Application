package com.ywy.interviewagentapplication.common.exception;

/**
 * 限流异常——请求超过限流阈值时抛出
 * 设计原因：
 *   1. 通过继承 BusinessException，使全局异常处理器 handleBusinessException() 统一拦截处理，无需再单独处理
 *   2. 固定使用 ErrorCode.RATE_LIMIT_EXCEEDED（8001）：前端可根据此 code 做限流提示
 *   3. 与 @RateLimit 注解 + RateLimitAspect 切面配合使用
 * 使用场景：
 *   RateLimitAspect 检测到 Redis 计数器超限 → throw new RateLimitExceededException()
 */
public class RateLimitExceededException extends BusinessException {

    /** 默认构造器——使用枚举默认消息 "请求过于频繁，请稍后再试" */
    public RateLimitExceededException() {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    /** 自定义消息构造器 */
    public RateLimitExceededException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
    }

    /** 带原始异常构造器 */
    public RateLimitExceededException(String message, Throwable cause) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message, cause);
    }
}
