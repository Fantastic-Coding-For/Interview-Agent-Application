package com.ywy.interviewagentapplication.common.async;

import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Redis Stream 异步任务生产者模板基类。
 * <p>
 * <b>设计模式</b>：模板方法模式（Template Method Pattern）<br>
 * <b>核心职责</b>：统一消息发送的骨架流程，将"变"的部分（业务构建逻辑）委派给子类实现，
 * 将"不变"的部分（发送、日志、异常处理）封装在基类中。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li><b>骨架统一</b>：所有异步任务的生产者遵循相同流程：构建消息 → XADD → 日志 → 失败回调</li>
 *   <li><b>钩子方法</b>：子类只需实现 5 个抽象方法即可完成差异化配置，无需关心 Redis 交互细节</li>
 *   <li><b>容量控制</b>：统一应用 {@link AsyncTaskStreamConstants#STREAM_MAX_LEN} 限制，防止 Stream 无限膨胀</li>
 *   <li><b>失败兜底</b>：发送失败时自动触发 {@link #onSendFailed(Object, String)}，由子类实现状态回滚</li>
 * </ul>
 * @param <T> 任务类型（如 ResumeId、KbId、SessionId 等）
 * @see RedisService
 * @see AsyncTaskStreamConstants
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    /**
     * Redis 服务实例（由子类构造器注入，保证线程安全）
     */
    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 发送异步任务到 Redis Stream（模板方法）。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>调用子类的 {@link #buildMessage(Object)} 构建消息体</li>
     *   <li>通过 {@link RedisService#streamAdd(String, Map, int)} 执行 XADD 命令，添加到消息队列</li>
     *   <li>若成功，记录 INFO 日志（含任务类型 + 任务标识 + Redis messageId）</li>
     *   <li>若失败，捕获异常 → 记录 ERROR 日志 → 调用 {@link #onSendFailed(Object, String)} 执行业务补偿</li>
     * </ol>
     * <p>
     * <b>事务边界</b>：本方法不包含事务，发送失败仅触发回调，由子类决定是否回滚业务状态。
     * <p>
     * <b>返回值语义</b>：true 表示 XADD 成功（消息已持久化到 Redis），
     * false 表示异常（可能网络超时/Redis 不可用/Stream 容量满等）
     *
     * @param payload 任务数据（如 resumeId、kbId、sessionId），由子类定义具体类型
     * @return true = 消息发送成功，false = 发送失败（已执行失败回调）
     */
    protected boolean sendTask(T payload) {
        try {
            // 2. 执行 Redis XADD（统一骨架）
            String messageId = redisService.streamAdd(
                    streamKey(),
                    buildMessage(payload),  // 1. 构建消息体
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            // 3. 成功日志（包含可追溯信息）
            log.info("{}任务已发送到Stream: {}, messageId={}",
                    taskDisplayName(), payloadIdentifier(payload), messageId);
            return true;
        } catch (Exception e) {
            // 4. 失败处理（日志 + 业务回调）
            log.error("发送{}任务失败: {}, error={}",
                    taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 截断错误消息，防止写入数据库时超出列长度限制。
     * <p>
     * <b>使用场景示例</b>：在 {@link #onSendFailed(Object, String)} 的实现中，
     * 当需要将错误原因持久化到业务表时，调用此方法保证字段安全。
     * <p>
     * <b>截断策略</b>：保留前 500 字符，超过部分丢弃（符合大多数 VARCHAR(500) 约束）。
     *
     * @param error 原始错误消息（可能包含堆栈信息）
     * @return 截断后的字符串（长度 ≤ 500），若输入为 null 则返回 null
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    // ===== 子类必须实现的 5 个抽象方法（钩子方法） =====

    /**
     * 获取任务名称（用于日志输出）。
     * @return 人类可读的任务名称（建议不超过 10 个中文字符）
     */
    protected abstract String taskDisplayName();

    /**
     * 获取 Redis Stream Key（生产者组订阅的消息队列）。
     * <p>
     * <b>示例</b>：`resume:analyze:stream`、`kb:build:stream`
     * <p>
     * <b>设计约束</b>：不同业务必须使用不同的 Stream Key，避免消息串扰。
     * <p>
     * <b>协作关系</b>：
     * <ul>
     *   <li>被 {@link #sendTask(Object)} 作为 XADD 的目标队列</li>
     *   <li>需与消费者端订阅的消息队列 key 保持一致</li>
     * </ul>
     *
     * @return Redis Key
     */
    protected abstract String streamKey();

    /**
     * 构建 Redis Stream 消息体（字段映射）。
     * <p>
     * <b>协作关系</b>：
     * <ul>
     *   <li>被 {@link #sendTask(Object)} 调用，作为 XADD 的 body 参数</li>
     * </ul>
     *
     * @param payload 业务对象
     * @return 消息字段 Map
     */
    protected abstract Map<String, String> buildMessage(T payload);

    /**
     * 获取业务标识符（用于日志追踪）。
     * <p>
     * <b>示例</b>："resumeId=123456"、"kbId=kb_789"
     * <p>
     * <b>协作关系</b>：
     * <ul>
     *   <li>被 {@link #sendTask(Object)} 用于成功/失败日志的关键字段</li>
     *   <li>被 {@link #onSendFailed(Object, String)} 用于标识哪个任务失败</li>
     * </ul>
     *
     * @param payload 业务对象
     * @return 可读的标识字符串（如 "resumeId=123"）
     */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 发送失败时的业务补偿回调。
     * <p>
     * <b>重要约束</b>：
     * <ul>
     *   <li>此方法内不应再抛出异常（基类 catch 已处理，若子类继续抛异常会丢失原始错误）</li>
     *   <li>必须保证幂等性（可能因网络重试导致多次回调）</li>
     *   <li>不建议在此处重试发送（避免死循环）</li>
     * </ul>
     * <p>
     * <b>协作关系</b>：
     * <ul>
     *   <li>仅在 {@link #sendTask(Object)} 的 catch 块中被调用</li>
     * </ul>
     *
     * @param payload 发送失败的业务对象
     * @param error   来自异常信息的错误描述
     */
    protected abstract void onSendFailed(T payload, String error);
}
