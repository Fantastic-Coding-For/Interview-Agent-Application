package com.ywy.interviewagentapplication.common.async;

import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类，管理消费者完整生命周期与消息处理骨架。
 * <p>
 * <b>设计模式</b>：模板方法模式（Template Method Pattern）<br>
 * <b>核心职责</b>：
 * <ul>
 *   <li>统一消费者生命周期：初始化 → 消费循环 → 优雅关闭</li>
 *   <li>统一消息处理流程：解析 → 幂等检查 → 状态标记 → 任务执行 → 结果确认/重试/失败</li>
 *   <li>统一重试策略：< 3 次自动重试入队，≥ 3 次标记 FAILED</li>
 *   <li>统一线程模型：均使用单守护线程进行消费（单线程已够用，多线程反而可能发生争抢），JVM 退出时自动终止</li>
 *   <li>统一 Pending 消息回收：自动处理因消费者崩溃而卡住的消息</li>
 * </ul>
 * <p>
 * <b>生命周期</b>：
 * <ol>
 *   <li>{@link #init()}：Spring Bean 初始化时创建守护线程并启动消费循环</li>
 *   <li>{@link #startConsumer()}：创建消费者组（若不存在），进入主循环</li>
 *   <li>{@link #consumeLoop()}：持续拉取消息，每条消息由 {@link #processMessage(StreamMessageId, Map)} 处理</li>
 *   <li>{@link #shutdown()}：Spring Bean 销毁时停止循环并关闭线程池</li>
 * </ol>
 * <p>
 * <b>子类实现契约</b>：子类必须实现 12 个抽象方法，分为四类：
 * <ul>
 *   <li><b>配置类</b>：{@link #taskDisplayName()}、{@link #streamKey()}、{@link #groupName()}、{@link #consumerPrefix()}、{@link #threadName()}</li>
 *   <li><b>解析类</b>：{@link #parsePayload(StreamMessageId, Map)}、{@link #payloadIdentifier(Object)}</li>
 *   <li><b>状态更新类</b>：{@link #markProcessing(Object)}、{@link #markCompleted(Object)}、{@link #markFailed(Object, String)}、{@link #retryMessage(Object, int)}</li>
 *   <li><b>任务处理类</b>：{@link #processBusiness(Object)}</li>
 * </ul>
 * <p>
 * 此外，子类可选覆盖 {@link #shouldSkip(Object)} 实现幂等跳过，覆盖 {@link #tryMarkProcessing(Object)} 实现乐观锁抢占。
 *
 * @param <T> 任务类型（如 ResumePayload、KbPayload）
 * @see RedisService
 * @see AsyncTaskStreamConstants
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    // 唯一消费者名称（包含 UUID 尾缀防止冲突）
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    // ============================================================
    // 生命周期方法
    // ============================================================

    /**
     * 初始化消费者：生成唯一消费者名称，并启动单守护线程。
     * <p>
     * <b>执行时机</b>：Spring Bean 装配完成后自动调用（@PostConstruct）<br>
     * <b>线程模型</b>：使用单守护线程池，确保同一时刻只处理一条消息，避免并发冲突。
     */
    @PostConstruct
    public void init() {
        // 生成唯一消费者名称：前缀 + UUID 前 8 位
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        // 单线程池，避免并发消费同一条 Stream
        this.executorService = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, threadName());
                    t.setDaemon(true);  // 将线程标记为守护线程
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 优雅关闭消费者：停止消费循环并关闭线程池。
     * <p>
     * <b>执行时机</b>：Spring Bean 销毁前自动调用（@PreDestroy）<br>
     * <b>协作关系</b>：
     * <ul>
     *   <li>设置 running = false，使 {@link #consumeLoop()} 退出 while 循环</li>
     *   <li>调用 executorService.shutdown() 关闭消费者线程池</li>
     * </ul>
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown(); // 关闭当前的消费者线程池
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    // ============================================================
    // 消费循环骨架（基类私有方法）
    // ============================================================

    /**
     * 启动消费循环。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>调用 {@link RedisService#createStreamGroup(String, String)} 创建消费者组（若不存在）</li>
     *   <li>进入 {@link #consumeLoop()}</li>
     * </ol>
     */
    private void startConsumer() {
        // 1. 创建消费者组（若不存在）
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        // 2. 进入消费循环
        consumeLoop();
    }

    /**
     * 消费循环：持续从 Redis Stream 拉取消息。
     * <p>
     * <b>循环条件</b>：running == true<br>
     * <b>循环体内</b>：
     * <ul>
     *   <li>调用 {@code RedisService.streamConsumeMessages(...)} 批量拉取消息</li>
     *   <li>每拉取一批消息，每条消息会回调 {@link #processMessage(StreamMessageId, Map)}</li>
     *   <li>轮询间隔、批量大小、Pending 超时等常量从 {@link AsyncTaskStreamConstants} 读取</li>
     * </ul>
     * <p>
     * <b>异常处理</b>：若发生异常，记录错误日志，继续下一轮循环（除非线程被中断）。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                        streamKey(),
                        groupName(),
                        consumerName,
                        AsyncTaskStreamConstants.BATCH_SIZE,    // 每批 10 条
                        AsyncTaskStreamConstants.POLL_INTERVAL_MS,  // 轮询间隔 1s
                        AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,   // 5 分钟 pending 超时
                        AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,  // 每轮回收 10 条
                        this::processMessage    // 消息处理回调
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    // ============================================================
    // 单条消息处理（核心方法）
    // ============================================================

    /**
     * 处理单条消息的完整生命周期（模板方法）。
     * <p>
     * <b>执行顺序（状态机）</b>：
     * <ol>
     *   <li><b>解析</b>：调用 {@link #parsePayload(StreamMessageId, Map)} 将消息体转换为任务对象</li>
     *   <li><b>丢弃不可解析</b>：若解析失败或返回 null，直接 ACK 并丢弃（记录警告）</li>
     *   <li><b>幂等检查</b>：调用 {@link #shouldSkip(Object)}，若 true 则 ACK 跳过</li>
     *   <li><b>抢占处理</b>：调用 {@link #tryMarkProcessing(Object)}，若 false 则 ACK（乐观锁失败，被其他消费者处理）</li>
     *   <li><b>执行任务</b>：调用 {@link #processBusiness(Object)} ，只有执行任务失败才会重试，其他任何情况都是直接 ACK 抛弃消息</li>
     *   <li><b>成功</b>：调用 {@link #markCompleted(Object)}，然后 ACK</li>
     *   <li><b>失败</b>：
     *     <ul>
     *       <li>若 retryCount < MAX_RETRY → 调用 {@link #retryMessage(Object, int)} 重新入队（retryCount+1），然后 ACK</li>
     *       <li>若 retryCount >= MAX_RETRY → 调用 {@link #markFailed(Object, String)} 标记失败，然后 ACK</li>
     *     </ul>
     *   </li>
     * </ol>
     * <p>
     * <b>重试次数</b>：从消息体的 {@link AsyncTaskStreamConstants#FIELD_RETRY_COUNT} 字段读取，由 {@link #parseRetryCount(Map)} 解析。
     * <p>
     * <b>ACK 时机</b>：无论成功或失败，最终都会执行 ACK，确保消息从 Pending 队列移除。
     * 重试消息通过重新 XADD 入队，不影响当前消息的 ACK。
     * <p>
     * <b>协作关系</b>：
     * <ul>
     *   <li>被 {@link #consumeLoop()} 通过回调调用</li>
     *   <li>内部调用大量子类抽象方法，是连接基类骨架与子类逻辑执行的核心枢纽</li>
     * </ul>
     *
     * @param messageId Redis Stream 消息 ID
     * @param data      消息体（字段 Map）
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        // Step 1: 解析消息
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                    taskDisplayName(), messageId, fields, e);
            // 无法解析 → ACK 丢弃（不重试，防止死循环）
            ackMessage(messageId);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
                taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            // Step 2: 幂等检查，已删除的实体直接 ACK
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            // Step 3: 尝试标记 PROCESSING，可能失败
            if (!tryMarkProcessing(payload)) {
                ackMessage(messageId);
                log.info("{} task was not claimed: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            // Step 4: 执行任务逻辑
            processBusiness(payload);
            // Step 5: 标记 COMPLETED + ACK
            markCompleted(payload);
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            // Step 6: 重试或标记失败
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                // 重新入队
                retryMessage(payload, retryCount + 1);
            } else {
                // 标记 FAILED
                markFailed(payload, truncateError(
                        taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            ackMessage(messageId);
        }
    }

    // ============================================================
    // 辅助工具方法（基类提供）
    // ============================================================

    /**
     * 从消息体中解析重试次数。
     * <p>
     * <b>默认字段</b>：{@link AsyncTaskStreamConstants#FIELD_RETRY_COUNT}，若不存在或格式错误或解析失败则返回 0。
     *
     * @param data 消息体 Map
     * @return 重试次数（≥0）
     */
    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断错误消息，防止写入数据库时超出列长度限制。
     * <p>
     * <b>使用场景</b>：在 {@link #markFailed(Object, String)} 的实现中，
     * 当需要将错误原因持久化到任务表时，调用此方法保证字段安全。
     *
     * @param error 原始错误消息
     * @return 截断后的字符串（长度 ≤ 500），若输入为 null 则返回 null
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 确认消息（ACK），从 Pending 队列移除。
     * <p>
     * <b>异常处理</b>：ACK 失败仅记录错误，不抛异常（避免影响主流程）。
     *
     * @param messageId 消息 ID
     */
    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    /**
     * 提供 RedisService 给子类使用，帮助 retryMessage 实现重新入队）。
     * @return RedisService 实例
     */
    protected RedisService redisService() {
        return redisService;
    }

    // ===== 子类必须实现的 12 个抽象方法 =====

    /**
     * 任务类型名称（用于日志）。
     */
    protected abstract String taskDisplayName();

    /**
     * Redis Stream Key（与生产者一致）。
     */
    protected abstract String streamKey();
    /**
     * Consumer Group 名称
     */
    protected abstract String groupName();
    /**
     * Consumer 名称前缀
     */
    protected abstract String consumerPrefix();
    /**
     * 消费线程名称（用于监控和调试）。
     */
    protected abstract String threadName();
    /**
     * 解析消息体为任务对象。
     */
    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);
    /**
     * 任务对象标识符（用于日志追踪）。
     */
    protected abstract String payloadIdentifier(T payload);
    /**
     * 幂等检查（默认不跳过）。
     */
    protected boolean shouldSkip(T payload) {
        return false;
    }

    /**
     * 标记任务为 PROCESSING 状态（任务状态更新）。
     */
    protected abstract void markProcessing(T payload);

    /**
     * 尝试标记任务为 PROCESSING（支持乐观锁）。
     * <p>
     * <b>默认实现</b>：直接调用 {@link #markProcessing(Object)} 并返回 true。
     * <p>
     * <b>子类可覆盖</b>：当需要防止多个消费者同时处理同一任务时，
     * 可使用数据库乐观锁（如版本号）或 Redis 分布式锁，若更新失败则返回 false。
     * <p>
     * <b>协作</b>：在 {@link #processMessage(StreamMessageId, Map)} 中调用，
     * 若返回 false，当前消息将被 ACK 并跳过（意味着被其他消费者抢占）。
     *
     * @param payload 任务对象
     * @return true = 抢占成功，false = 抢占失败（被其他实例处理）
     */
    protected boolean tryMarkProcessing(T payload) {
        markProcessing(payload);
        return true;
    }

    /**
     * 任务核心处理逻辑（由子类实现）。
     * <p>
     * <b>执行位置</b>：在成功标记 PROCESSING 之后调用。
     * @param payload 任务对象
     */
    protected abstract void processBusiness(T payload);
    /**
     * 标记任务为 COMPLETED（成功）。
     */
    protected abstract void markCompleted(T payload);
    /**
     * 标记任务为 FAILED（最终失败）。
     */
    protected abstract void markFailed(T payload, String error);
    /**
     * 重试任务：将任务作为新消息重新发送到 Redis Stream。
     */
    protected abstract void retryMessage(T payload, int retryCount);
}

