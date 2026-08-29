package com.ywy.interviewagentapplication.common.aspect;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.exception.RateLimitExceededException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 限流 AOP 切面。
 *
 * <h3>架构设计</h3>
 * 采用 <b>AOP + Redis Lua 脚本</b>的限流方案：
 * <ol>
 *   <li>{@code @RateLimit} 注解标记需要限流的方法（声明式，非侵入）</li>
 *   <li>AOP 环绕通知拦截方法调用，在方法执行前逐条检查限流规则</li>
 *   <li>Lua 脚本在 Redis 服务端原子执行滑动窗口算法，避免 race condition</li>
 * </ol>
 *
 * <h3>为什么使用 Lua 脚本而非 Redisson 的 RRateLimiter？</h3>
 * <ul>
 *   <li>Redisson 的 RRateLimiter 基于 Redis 的 Rate Limiter 实现（单 key），
 *       而我们需要滑动窗口算法（zset + key 组合），更精确</li>
 *   <li>Lua 脚本将"检查 → 扣减 → 设置过期"三个操作打包为一个原子操作，
 *       消除了并发请求间的竞态条件</li>
 *   <li>自定义脚本可以灵活控制令牌计算逻辑（如过期令牌回收）</li>
 * </ul>
 *
 * <h3>多维度限流的执行模型</h3>
 * 同一方法上可标注多个 {@code @RateLimit}（利用 Java 的 {@code @Repeatable} 机制）：
 * <pre>{@code
 * @RateLimit(dimension = GLOBAL, count = 100)  // 全局限流：每秒100次
 * @RateLimit(dimension = IP, count = 5)         // IP限流：每IP每秒5次
 * public Result query() { ... }
 * }</pre>
 * 所有规则 <b>AND</b> 关系，每个都必须通过。任何一个不通过即拒绝请求。
 * <p>
 *
 * <h3>NOSCRIPT 自动恢复机制</h3>
 * Redis 重启后脚本缓存（通过 SHA1 引用）会丢失。本类在捕获 NOSCRIPT 错误后
 * 自动重新加载脚本并重试。调用方无感知，无需手动干预。
 *
 * @see RateLimit 限流注解定义
 * @see "scripts/rate_limit_single.lua" 滑动窗口 Lua 脚本
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {
    private final RedissonClient redissonClient;

    /**
     * Lua 脚本正文内容。
     * 在 static 块中从 classpath 加载。如果脚本文件缺失，应用启动即失败（fail-fast），
     * 而非等到第一个限流请求时才报错。
     */
    private static final String LUA_SCRIPT;
    /**
     * Lua 脚本在 Redis 中的 SHA1 摘要。
     * 通过 SHA1 调用脚本（EVALSHA）而非每次发送脚本全文（EVAL），
     * 可以将网络传输从 KB 级降低到固定 40 字节。
     */
    private String luaScriptSha;
    /** Redisson 的 Lua 脚本执行器，线程安全 */
    private RScript rScript;

    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载限流 Lua 脚本失败", e);
        }
    }

    /**
     * 初始化：获取 Redisson 脚本执行器并预加载 Lua 脚本到 Redis，避免冷启动
     */
    @PostConstruct
    public void init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        loadScript();
    }

    /**
     * 加载 Lua 脚本到 Redis，并更新本地缓存的 SHA1。
     * <p>
     * scriptLoad 操作将脚本全文发送到 Redis，Redis 编译并缓存后返回 SHA1 摘要。
     * 后续调用只需传 SHA1，Redis 根据 SHA1 找到已编译的脚本执行。
     */
    private void loadScript() {
        this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 环绕通知：拦截带 {@code @RateLimit} 或 {@code @RateLimit.Container} 注解的方法。
     *
     * <h4>切点表达式说明</h4>
     * 使用两个 {@code @annotation} 的组合（OR 关系）：
     * <ul>
     *   <li>{@code @annotation(RateLimit)}：匹配单个注解的方法</li>
     *   <li>{@code @annotation(RateLimit.Container)}：匹配多个注解（Java 编译器自动包装为 Container）</li>
     * </ul>
     * 当同一个方法标注了 2+ 个 {@code @RateLimit} 时，Java 编译器会将它们包装在 {@code @RateLimit.Container} 中，
     * 此时方法上不存在直接的 {@code @RateLimit} 注解，只匹配前者会漏掉多规则场景。
     *
     * @param joinPoint 切点信息
     * @return 目标方法的返回值（如果限流通过）或降级方法的返回值（如果限流触发且有 fallback）
     * @throws Throwable 目标方法或限流异常
     */
    @Around("@annotation(com.ywy.interviewagentapplication.common.annotation.RateLimit) || " +
            "@annotation(com.ywy.interviewagentapplication.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        // getAnnotationsByType 会自动解包 Container，返回所有 @RateLimit 实例的数组
        RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
        long nowMs = System.currentTimeMillis();
        // UUID 作为请求唯一标识传入 Lua 脚本，用于区分同一毫秒内的不同请求
        String requestId = UUID.randomUUID().toString();

        // 为每条限流规则执行 lua 脚本进行检查：AND 关系，任何一条不通过即拒绝
        for (RateLimit rule : rules) {
            long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
            String key = generateKey(className, methodName, rule.dimension());

            // 执行限流 lua 脚本
            Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

            // result == 0 表示限流触发（令牌不足）
            if (result == null || result == 0) {
                // 处理限流触发的情况：优先尝试降级方法，否则抛异常。
                return handleRateLimitExceeded(joinPoint, rule, key);
            }
        }

        // 所有规则均通过，执行目标方法
        return joinPoint.proceed();
    }

    /**
     * 执行限流 Lua 脚本。
     *
     * <h4>返回值语义</h4>
     * <ul>
     *   <li>{@code 1}：令牌申请成功，请求放行</li>
     *   <li>{@code 0}：令牌不足，请求被拒绝</li>
     *   <li>{@code null}：脚本执行异常（类型转换失败等）</li>
     * </ul>
     *
     * <h4>NOSCRIPT 异常处理</h4>
     * 由于 Redis 重启后会导致编译缓存丢失，而丢失后首次执行 EVALSHA 会收到 NOSCRIPT 错误。
     * 此时重新加载脚本并重试一次。
     *
     * @param key        限流键（维度 + 方法标识）
     * @param nowMs      当前时间戳（毫秒）
     * @param requestId  请求唯一标识（用于记录令牌消耗记录）
     * @param intervalMs 时间窗口大小（毫秒）
     * @param count      窗口内允许的最大请求数
     * @return 1=放行, 0=限流, null=异常
     */
    private Long executeRateLimitScript(String key, long nowMs, String requestId, long intervalMs, double count) {
        List<Object> keysList = Collections.singletonList(key);
        Object[] args = {
                String.valueOf(nowMs),
                String.valueOf(1),
                String.valueOf(intervalMs),
                String.valueOf(count),
                requestId
        };

        try {
            Object resultObj = rScript.evalSha(
                    RScript.Mode.READ_WRITE,
                    luaScriptSha,
                    RScript.ReturnType.VALUE,
                    keysList,
                    args
            );
            return convertToLong(resultObj);
        } catch (org.redisson.client.RedisException e) {
            // Redis 重启后脚本缓存丢失，重新加载并重试
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                loadScript();
                Object resultObj = rScript.evalSha(
                        RScript.Mode.READ_WRITE,
                        luaScriptSha,
                        RScript.ReturnType.VALUE,
                        keysList,
                        args
                );
                return convertToLong(resultObj);
            }
            throw e;
        }
    }

    /**
     * 将注解中的时间单位 + 间隔值转为毫秒数。
     * <p>
     * 使用毫秒作为统一单位是因为：①Lua 脚本中使用毫秒时间戳；
     * ②System.currentTimeMillis() 返回毫秒；③避免浮点数运算的精度问题。
     * <p>
     * 使用 Java 14+ 的 switch 表达式（arrow syntax），编译器会检查是否覆盖了所有枚举值，编译安全。
     *
     * @param interval 时间间隔值
     * @param unit     时间单位枚举
     * @return 对应的毫秒数
     */
    private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> interval;
            case SECONDS -> interval * 1000;
            case MINUTES -> interval * 60 * 1000;
            case HOURS -> interval * 3600 * 1000;
            case DAYS -> interval * 86400 * 1000;
        };
    }

    /**
     * 将 Redisson 返回的对象转换为 Long。
     * <p>
     * Redisson 的 EVALSHA 返回值类型取决于使用的 Codec 和 Lua 返回值：
     * 可能返回 Integer、Long 或 String。此处做防御性处理，同时支持 Number 和 String 类型的转换。
     * <p>
     * 返回 null 的场景：结果类型既不是 Number 也不是可解析的 String。这是一种异常情况，调用方将 null 视为"异常，拒绝请求"以保护后端。
     *
     * @param obj Redisson 脚本执行返回值
     * @return Long 值，转换失败返回 null
     */
    private Long convertToLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException _) {
                log.warn("无法将字符串转换为Long: {}", obj);
                return null;
            }
        }
        log.warn("不支持的对象类型转换为Long: {}", obj != null ? obj.getClass().getName() : "null");
        return null;
    }

    /**
     * 生成限流键。
     *
     * <h4>Redis Cluster 兼容：Hash Tag 设计</h4>
     * Redis key 格式：{@code ratelimit:{className:methodName}:dimension:value}
     * <p>value：用户 ID，或者 IP（Global 维度没有 value）</p>
     * <p>
     * {@code {className:methodName}} 部分是 Redis Cluster 的 <b>Hash Tag</b>：
     * 花括号内的部分决定了 key 分配到哪个 slot。这样设计保证了：
     * <ul>
     *   <li>同一方法的所有限流维度（GLOBAL/IP/USER）key 落在同一个 Redis 节点上</li>
     *   <li>Lua 脚本可以同时操作多个 key 而不受 Cluster 跨节点限制</li>
     * </ul>
     * 如果未来需要跨节点 Lua 操作，需要改用 hash tag 确保所有相关 key 同节点。
     *
     * @param className  类名（用于区分不同 Controller/Service 的同名方法）
     * @param methodName 方法名
     * @param dimension  限流维度
     * @return 完整的 Redis key
     */
    private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
        String hashTag = "{" + className + ":" + methodName + "}";
        String keyPrefix = "ratelimit:" + hashTag;

        return switch (dimension) {
            case GLOBAL -> keyPrefix + ":global";
            case IP -> keyPrefix + ":ip:" + getClientIp();
            case USER -> keyPrefix + ":user:" + getCurrentUserId();
        };
    }

    /**
     * 处理限流触发的情况：优先尝试降级方法，否则抛异常。
     * <p>
     * <b>降级方法的两种签名</b>：
     * <ol>
     *   <li>与原方法参数完全一致：可以访问原请求数据，实现"有上下文的降级"</li>
     *   <li>无参：最简单的降级，返回默认/缓存结果</li>
     * </ol>
     * 查找顺序：先尝试匹配参数签名，再尝试无参。
     *
     * @param joinPoint 切点信息（用于获取目标对象和参数）
     * @param rateLimit 限流注解实例（获取 fallback 方法名）
     * @param key       限流键（用于日志）
     * @return 降级方法的返回值
     * @throws Throwable 如果没有降级方法或降级方法执行失败，抛出限流异常
     */
    private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String key)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();

        if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
            try {
                Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            methodName,
                            rateLimit.fallback());
                    if (fallbackMethod.getParameterCount() > 0) {
                        return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                    } else {
                        return fallbackMethod.invoke(joinPoint.getTarget());
                    }
                }
            } catch (Exception e) {
                log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
            }
        }

        log.debug("限流触发，拒绝请求: key={}, count={} per {} {}",
                key, rateLimit.count(), rateLimit.interval(), rateLimit.timeUnit());
        throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    }

    /**
     * 查找降级方法。
     * <p>
     * 两阶段查找策略：
     * <ol>
     *   <li>先查找<b>同参数签名</b>的降级方法（精确匹配）</li>
     *   <li>若未找到，再查找<b>无参</b>的降级方法（兜底匹配）</li>
     * </ol>
     * 使用 {@code getDeclaredMethod} 而非 {@code getMethod}：
     * 降级方法通常是 private 的（不对外暴露），getDeclaredMethod 可以找到 private 方法。
     * 找到后调用 {@code setAccessible(true)} 使之可调用。
     * <p>
     * 注意：查找范围限定在当前类，不搜索父类。因为降级方法应定义在同一个 Controller/Service 中。
     *
     * @param joinPoint    切点信息
     * @param fallbackName 降级方法名
     * @return 找到的 Method 对象，未找到返回 null
     */
    private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] parameterTypes = signature.getParameterTypes();

        try {
            Method method = targetClass.getDeclaredMethod(fallbackName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            try {
                Method method = targetClass.getDeclaredMethod(fallbackName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                log.warn("未找到降级方法: {}.{} (需无参或参数列表一致)",
                        targetClass.getSimpleName(), fallbackName);
                return null;
            }
        }
    }

    /**
     * 获取客户端真实 IP 地址。
     *
     * <h4>为什么需要链式检查多个 Header？</h4>
     * 在反向代理（Nginx/Cloudflare/CDN）环境中，{@code request.getRemoteAddr()}
     * 返回的是代理服务器的 IP 而非客户端 IP。因此需要按优先级逐层检查：
     * <ol>
     *   <li><b>X-Forwarded-For</b>：最标准的代理转发头，取第一个 IP（客户端原始 IP）</li>
     *   <li><b>X-Real-IP</b>：Nginx 常用，只存单个 IP</li>
     *   <li><b>Proxy-Client-IP</b>：Apache 代理用</li>
     *   <li><b>WL-Proxy-Client-IP</b>：WebLogic 代理用</li>
     *   <li><b>RemoteAddr</b>：最后兜底——直连场景或所有代理头都不可信时</li>
     * </ol>
     *
     * <h4>安全注意事项</h4>
     * X-Forwarded-For 可以被客户端伪造。在生产环境中应确保：
     * <ul>
     *   <li>Nginx/网关已正确配置，覆盖了客户端可能伪造的 X-Forwarded-For 头</li>
     *   <li>或使用 Spring 的 ForwardedHeaderFilter 处理标准化的 Forwarded 头</li>
     * </ul>
     *
     * @return 客户端 IP 地址，无法获取时返回 "unknown"
     */
    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "unknown";
    }

    /**
     * 获取当前登录用户 ID（用于用户维度的限流）。
     * <p>
     * 同样的多层查找策略，因为认证方式可能不同：
     * <ol>
     *   <li>{@code request.getAttribute("userId")}：过滤器/拦截器中设置的属性（最可靠）</li>
     *   <li>{@code request.getHeader("X-User-Id")}：网关透传的用户标识（内部服务调用场景）</li>
     *   <li>{@code "anonymous"}：兜底——未登录用户统一归入匿名组限流</li>
     * </ol>
     * <p>
     * 未登录用户使用 "anonymous" 而非 null，是为了确保限流仍然生效。
     * 如果返回 null，generateKey 会产生 {@code ratelimit:...:user:null}，
     * 所有未登录用户共享同一个 key，达到"匿名用户组限流"的效果。
     *
     * @return 用户 ID 字符串，无法获取时返回 "anonymous"
     */
    private String getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous";
        }

        HttpServletRequest request = attributes.getRequest();

        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return userId.toString();
        }

        userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return userId.toString();
        }

        return "anonymous";
    }
}

