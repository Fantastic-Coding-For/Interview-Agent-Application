package com.ywy.interviewagentapplication.common.exception;

import com.ywy.interviewagentapplication.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 设计原因：
 *   1. @RestControllerAdvice：覆盖所有 @RestController 的异常处理
 *   2. 所有异常统一返回 HTTP 200 + Result.error()：前端只需解析 JSON body 判断 code
 *   3. 分层处理：业务异常 → AI 网络异常 → 参数校验异常 → 兜底未知异常
 * 核心设计决策：
 *   - 不使用 HTTP 状态码区分错误，全部走 200 + 业务 code
 *   - 异常必须作为日志的最后一个参数传入（SLF4J 规范，保留完整调用栈）
 *   - 使用 Java 21 switch 表达式（pattern matching）处理不同异常消息
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常——所有 BusinessException 及子类
     * 日志级别：warn（业务预期内的异常，不需要 Error 级别）
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @Valid 校验失败异常
     * 提取所有字段的校验失败消息，用逗号拼接
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }

    /** 处理 GET 请求参数绑定异常（如 @RequestParam 枚举值非法） */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }

    /** 处理文件上传大小超限——MultipartFile 超过 max-file-size */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制");
    }

    /** 处理非法参数异常——如无效的枚举值 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * 处理 AI 服务网络异常（SSL 握手失败、连接超时等）
     * 使用 Java 21 switch 表达式 + pattern matching 区分不同类型的网络异常
     */
    @ExceptionHandler(ResourceAccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleResourceAccessException(ResourceAccessException e) {
        log.error("AI服务连接失败: {}", e.getMessage(), e);

        return switch (e.getCause()) {
            // SocketTimeoutException _  → AI 响应超时
            case SocketTimeoutException _ ->
                    Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试");
            // 其他原因（含 null）→ 检查消息内容进一步判断
            case null, default -> {
                String message = e.getMessage();
                if (message != null && message.contains("handshake")) {
                    // SSL 握手失败（常见：网络不稳定或代理问题）
                    yield Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE,
                            "AI服务连接失败（网络不稳定），请检查网络或稍后重试");
                }
                yield Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "AI服务暂时不可用，请稍后重试");
            }
        };
    }

    /**
     * 处理 AI 服务调用异常（HTTP 层面错误）
     * 通过检查异常消息中的 HTTP 状态码区分不同错误类型
     */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRestClientException(RestClientException e) {
        log.error("AI服务调用失败: {}", e.getMessage(), e);

        return switch (e.getMessage()) {
            case null -> Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试");
            // 401 → API Key 无效
            case String m when m.contains("401") || m.contains("Unauthorized") ->
                    Result.error(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请联系管理员");
            // 429 → 调用频率超限
            case String m when m.contains("429") || m.contains("Too Many Requests") ->
                    Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试");
            default -> Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试");
        };
    }

    /** 处理 404 — 访问不存在的 API 路径 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleNoResourceFoundException(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND, "API 接口不存在");
    }

    /** 处理请求方法不支持（如 GET 方式调用 POST 接口） */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleHttpRequestMethodNotSupportedException(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {} {}", e.getMethod(), e.getSupportedHttpMethods());
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /**
     * 兜底处理器——处理所有未被上述具体处理器覆盖的异常
     * 返回通用服务器错误，避免将 Java 异常栈暴露给前端
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }
}
