package com.ywy.interviewagentapplication.infrastructure.redis;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis 服务封装（基于 Redisson 客户端）。
 *
 * <h3>架构定位</h3>
 * 本类是应用中所有 Redis 操作的<b>唯一入口</b>。上层业务代码不直接依赖
 * {@link RedissonClient}，而是通过本类进行间接操作。这样做的理由：
 * <ul>
 *   <li><b>统一异常处理</b>：所有 Redis 异常在一个地方被捕获和转换</li>
 *   <li><b>统一序列化策略</b>：Stream 操作强制使用 StringCodec 自动处理 Java 对象的序列化/反序列化，并保证可读性</li>
 *   <li><b>版本兼容适配</b>：Redisson 4.0.0 的已知 bug（NOSCRIPT、空结果类型转换）
 *       在本层被统一 workaround，上层代码无需关心版本差异</li>
 *   <li><b>可替换性</b>：未来如需切换 Redis 客户端（如 Lettuce），只需修改本类</li>
 * </ul>
 *
 * <h3>Redisson 版本兼容说明</h3>
 * 当前适配 Redisson 4.0.0，已知问题：
 * <ul>
 *   <li>{@code stream.readGroup} / {@code stream.autoClaim} 在空结果时可能抛出
 *       {@link ClassCastException}（Redisson 内部返回 EmptyList 而非空 Map），
 *       两处均已 catch 并做空结果处理。</li>
 *   <li>升级 Redisson 后如不再抛此异常，对应的 catch 块可安全删除（日志级别为 debug，不影响功能）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedissonClient redissonClient;
    /**
     * Stream 消息回收（Auto Claim）的游标缓存（每个消费者组一个游标）。
     * <p>
     * Key：{@code streamKey:groupName}，Value：下次扫描 pending 列表的起始位置。
     * <p>
     * 使用 {@link ConcurrentHashMap} 而非 Redis 自身存储游标的原因：
     * 游标仅在当前应用实例内有意义，且游标丢失的影响很小（最多导致几秒钟的消息延迟重新回收），没必要为此增加 Redis 的读写开销。
     * <p>
     * 使用 {@code ConcurrentMap} 接口声明而非直接使用 {@code ConcurrentHashMap}，
     * 是为了保留将来替换为 Caffeine Cache 等支持过期策略的缓存实现的可能性。
     */
    private final ConcurrentMap<String, StreamMessageId> streamReclaimCursors = new ConcurrentHashMap<>();

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     */
    public <T> void set(String key, T value) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value);
    }

    /**
     * 设置值（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    /**
     * 获取值
     */
    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    /**
     * 获取值，如果不存在则通过 loader 加载并缓存（Cache-Aside 模式）。
     * <p>
     * <b>这不是原子操作</b>：在高并发下，多个线程可能同时发现 cache miss，
     * 各自执行 loader 并写入 Redis。后写入的值会覆盖先写入的，所以这种行为
     * 通常是安全的（写入相同的内容）。但如果 loader 有副作用（如调用外部 API），
     * 可能需要在外层加分布式锁保护。
     * <p>
     * <b>适用场景</b>：读取频繁、计算/查询成本适中、允许短时间不一致的数据。
     * <b>不适用场景</b>：需要强一致性的数据、loader 有不可重复的副作用。
     *
     * @param key    Redis 键
     * @param ttl    缓存过期时间
     * @param loader 数据加载函数（仅在缓存未命中时调用）
     * @return 值对象，如果 loader 也返回 null 则返回 null
     */
    public <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        T value = bucket.get();
        if (value == null) {
            value = loader.apply(key);
            if (value != null) {
                bucket.set(value, ttl);
            }
        }
        return value;
    }

    /**
     * 删除键
     */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /**
     * 设置/更新键的过期时间。
     */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    /**
     * 获取键的剩余存活时间（毫秒）。
     * -1 表示 key 存在但没有过期时间；-2 表示 key 不存在
     */
    public long getTimeToLive(String key) {
        return redissonClient.getBucket(key).remainTimeToLive();
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 中的单个字段（键值对）。
     * <p>
     * Hash 适合存储"一个实体的多个属性"。如用户会话的多个字段。
     * 与多次 set 相比，Hash 的优势：①使用更少的内存（Redis 对 Hash 有特殊编码优化）；
     * ②可以原子性地获取/删除整个实体。
     *
     * @param key   Hash 的键
     * @param field Hash 内的字段名
     * @param value 字段值
     */
    public <K, V> void hSet(String key, K field, V value) {
        RMap<K, V> map = redissonClient.getMap(key);
        map.put(field, value);
    }

    /**
     * 获取 Hash 中的单个字段的值。
     * @param key   Hash 的键
     * @param field 字段名
     * @return 字段值，不存在时返回 null
     */
    public <K, V> V hGet(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.get(field);
    }

    /**
     * 获取整个 Hash 的所有字段（键值对）。
     * @param key Hash 的键
     * @return 所有字段的 Map 视图，空 Hash 返回空 Map
     */
    public <K, V> Map<K, V> hGetAll(String key) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.readAllMap();
    }

    /**
     * 删除 Hash 中的指定字段（键值对）。
     * @param key   Hash 的键
     * @param field 要删除的字段名
     * @return true 如果字段存在并被删除
     */
    public <K, V> boolean hDelete(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.remove(field) != null;
    }

    /**
     * 检查 Hash 中是否存在指定字段（键值对）。
     * @param key   Hash 的键
     * @param field 字段名
     * @return true 如果字段存在
     */
    public <K> boolean hExists(String key, K field) {
        RMap<K, Object> map = redissonClient.getMap(key);
        return map.containsKey(field);
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取 Redisson 分布式锁对象（不加锁）。
     * <p>
     * 返回的 RLock 只是锁的"句柄"，需要调用 {@code lock()} 或 {@code tryLock()} 才会实际加锁。
     * 适合需要自定义加锁逻辑的场景。
     * <p>
     * <b>Redisson 分布式锁的原理</b>：基于 Redis 的 Hash + PubSub 实现。
     * 加锁时用 Lua 脚本原子写入锁信息（线程ID + 重入计数），
     * 解锁时用 Lua 脚本原子检查并删除。底层使用 Redisson 的 Watchdog
     * 机制自动续期（默认每 10 秒续期到 30 秒），防止业务执行时间超过锁租期。
     *
     * @param lockKey 锁的 Redis key
     * @return 分布式锁对象
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 获取 Redisson 分布式锁对象，然后加锁（阻塞等待，超时则返回 false）。
     * <p>
     * 与 {@link #getLock} + {@code lock.lock()} 的区别：
     * 本方法会在 waitTime 内定期重试，超时则返回 false。不会无限阻塞。
     *
     * @param lockKey   锁的 key
     * @param waitTime  最大等待时间（等待不到锁则返回 false）
     * @param leaseTime 锁的持有时间（超过后自动释放），设 0 使用 Watchdog 自动续期
     * @param unit      时间单位
     * @return true 如果成功获取锁，false 如果超时或被中断
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁。
     * <p>
     * <b>安全检查</b>：释放前通过 {@code isHeldByCurrentThread()} 检查当前线程
     * 是否持有此锁——防止线程 A 误释放了线程 B 持有的锁。
     * 如果 unlock 非本线程持有的锁，Redisson 会抛出 IllegalMonitorStateException。
     * 如果确实不是本线程持有的锁，那么静默跳过，因为锁已经被其他线程持有，等同于当前线程持有的锁已被释放。
     *
     * @param lockKey 锁的 key
     */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 在分布式锁保护下执行操作（模板方法模式）。
     * <p>
     * 封装了"获取锁 → 执行业务 → 释放锁"的标准流程，
     * 确保锁在 finally 中释放（即使业务操作抛异常）。
     * <p>
     * <ul>
     *   <li>waitTime：等待获取锁的时间（等别人释放）——设置较短，避免请求堆积</li>
     *   <li>leaseTime：持有锁的最长时间（自己执行）——设置足够完成业务操作</li>
     * </ul>
     * 如果 leaseTime 太短，业务还没执行完锁就自动释放了（会导致并发问题）；
     * 如果太长，当前线程异常退出会导致锁长时间不释放（Watchdog 机制可缓解）。
     *
     * @param lockKey   锁的 key
     * @param waitTime  等待锁的最大时间
     * @param leaseTime 持有锁的最大时间（设为 -1 使用 Watchdog 自动续期）
     * @param unit      时间单位
     * @param operation 受锁保护的业务操作
     * @return 业务操作的返回值
     * @throws BusinessException 如果获取锁超时、被中断、或业务操作抛出异常
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                 TimeUnit unit, LockedOperation<T> operation) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                try {
                    return operation.execute();
                } finally {
                    lock.unlock();
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: " + lockKey, e);
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    // ==================== Stream 消息队列 ====================

    /**
     * Stream 消息处理器接口
     */
    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    /**
     * 消费 Stream 消息（不启用 Pending 消息回收，仅阻塞读取新消息）
     *
     * @param streamKey      Stream 键
     * @param groupName      消费者组名
     * @param consumerName   消费者名
     * @param count          每次读取数量
     * @param blockTimeoutMs 阻塞等待超时时间（毫秒），0 表示无限等待
     * @param processor      消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            StreamMessageProcessor processor) {
        return streamConsumeMessages(
                streamKey,
                groupName,
                consumerName,
                count,
                blockTimeoutMs,
                0L,
                processor
        );
    }

    /**
     * 消费 Stream 消息。
     * <p>优先回收超时 Pending，只要成功回收就不再读取新消息。最大消息回收数=每次读取新消息数量</p>
     *
     * @param streamKey            Stream 键
     * @param groupName            消费者组名
     * @param consumerName         消费者名
     * @param count                每次读取新消息的数量/最多回收的 Pending 消息数
     * @param blockTimeoutMs       阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs Pending 消息超过此时间后可被当前消费者回收（<=0 表示不回收）
     * @param processor            消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            StreamMessageProcessor processor) {
        return streamConsumeMessages(
                streamKey,
                groupName,
                consumerName,
                count,
                blockTimeoutMs,
                pendingIdleTimeoutMs,
                count,
                processor
        );
    }

    /**
     * 消费 Stream 消息（完整参数版本，所有重载最终调用此方法）。
     * <p>Redis 的 BLOCK 机制让服务端在无消息时挂起连接等待，而非客户端轮询（polling）。这大幅减少了网络往返次数和 CPU 空转。</p>
     * <p>Pending 消息是已被读取但未收到 ACK 的消息，通常是因为消费者在处理消息时崩溃了。通过回收（Auto Claim）机制，新消费者可以接管这些遗留消息，确保"至少一次"有效处理。</p>
     * <h4>消费流程（两阶段）</h4>
     * <ol>
     *   <li><b>回收阶段</b>：先尝试回收超时的 Pending 消息（别人拿走了但没 ACK 的）；
     *       如果回收到了 pending 消息，处理完后直接返回 true，不再读取新消息。
     *       这样做是为了优先处理"遗留消息"，它们通常比新消息更老、等待时间更长。</li>
     *   <li><b>读取阶段</b>：如果没有可回收的 Pending 消息，使用 BLOCK 模式
     *       读取从未被投递过的新消息（neverDelivered）。</li>
     * </ol>
     *
     * @param streamKey              Stream 键
     * @param groupName              消费者组名
     * @param consumerName           消费者名
     * @param count                  每次读取新消息的数量
     * @param blockTimeoutMs         阻塞等待超时（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs   Pending 消息空闲超过此时间后可被当前消费者回收（<=0 表示不回收）
     * @param pendingClaimBatchSize  最多回收的 Pending 消息数
     * @param processor              消息处理器
     * @return true 如果处理了消息，false 如果超时无消息（或仅处理了回收消息也返回 true）
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            int pendingClaimBatchSize,
            StreamMessageProcessor processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        Map<StreamMessageId, Map<String, String>> messages = reclaimPendingMessages(
                stream,
                streamKey,
                groupName,
                consumerName,
                pendingClaimBatchSize,
                pendingIdleTimeoutMs
        );
        if (processMessages(messages, processor)) {
            return true;
        }

        // 使用阻塞读取，让 Redis 服务端等待消息
        try {
            messages = stream.readGroup(
                    groupName,
                    consumerName,
                    StreamReadGroupArgs.neverDelivered()
                            .count(count)
                            .timeout(Duration.ofMillis(blockTimeoutMs))
            );
        } catch (ClassCastException e) {
            // Redisson 4.0.0 bug: 无消息时返回 EmptyList 而非空 Map，内部强转失败。
            // 等价于"本次无消息"，静默返回即可。
            log.debug("Redisson 4.0.0 内部类型转换异常（空结果时触发），等价于本批无消息: stream={}, group={}",
                    streamKey, groupName);
            return false;
        }

        if (messages == null || messages.isEmpty()) {
            return false;
        }

        return processMessages(messages, processor);
    }

    /**
     * 回收超时的 Pending 消息（不处理）。
     * <p>
     * <b>Auto Claim 的工作原理</b>：
     * Redis 的 XAUTOCLAIM 命令扫描 Pending 列表，找到空闲超过
     * pendingIdleTimeoutMs 的消息，将它们的所有权转移给当前消费者。
     * <p>
     * <b>游标（Cursor）机制</b>：
     * XAUTOCLAIM 返回一个 nextId，指示下次扫描的起始位置。
     * 本方法将游标缓存在内存中（而非 Redis），不同消费者组各自维护游标。
     * 游标到达末尾（MIN）时自动重置，实现循环扫描。
     * <p>
     * <b>消息被裁剪（Trim）的处理</b>：
     * getDeletedIds() 返回已被 Redis Stream 修剪删除的消息 ID，
     * Pending 队列中这些消息变成孤儿。记录 warn 日志用于监控和排查。
     *
     * @param stream              Redisson Stream 对象
     * @param streamKey           Stream 键（流对象在 redis 服务端中的键名）
     * @param groupName           消费者组名
     * @param consumerName        消费者名
     * @param count               每轮最多回收的消息数
     * @param pendingIdleTimeoutMs 空闲超时阈值
     * @return 回收到的消息 Map，无消息时返回空 Map
     */
    private Map<StreamMessageId, Map<String, String>> reclaimPendingMessages(
            RStream<String, String> stream,
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long pendingIdleTimeoutMs) {
        if (pendingIdleTimeoutMs <= 0 || count <= 0) {
            return Map.of();
        }

        String cursorKey = streamKey + ":" + groupName;
        StreamMessageId startId = streamReclaimCursors.getOrDefault(cursorKey, StreamMessageId.MIN);
        AutoClaimResult<String, String> result;
        try {
            result = stream.autoClaim(
                    groupName,
                    consumerName,
                    pendingIdleTimeoutMs,
                    TimeUnit.MILLISECONDS,
                    startId,
                    count
            );
        } catch (ClassCastException e) {
            // Redisson 4.0.0 空结果可能触发内部类型转换异常，等价于本轮无可回收消息。
            log.debug("Redisson 4.0.0 内部类型转换异常（无可回收消息）: stream={}, group={}",
                    streamKey, groupName);
            return Map.of();
        }

        // 如果整个流都扫完了，没有更多消息可扫描，Redis 会返回 -1（即 StreamMessageId.MIN）
        StreamMessageId nextId = result.getNextId();
        if (nextId == null || StreamMessageId.MIN.equals(nextId)) {
            streamReclaimCursors.remove(cursorKey);
        } else {
            streamReclaimCursors.put(cursorKey, nextId);
        }

        List<StreamMessageId> deletedIds = result.getDeletedIds();
        if (deletedIds != null && !deletedIds.isEmpty()) {
            log.warn("Stream pending messages were trimmed before reclaim: stream={}, group={}, ids={}",
                    streamKey, groupName, deletedIds);
        }

        Map<StreamMessageId, Map<String, String>> messages = result.getMessages();
        if (messages != null && !messages.isEmpty()) {
            log.info("Reclaimed Redis Stream pending messages: stream={}, group={}, consumer={}, count={}",
                    streamKey, groupName, consumerName, messages.size());
        }
        return messages == null ? Map.of() : messages;
    }

    /**
     * 批量处理消息。
     * <p>
     * 遍历消息 Map，逐条调用处理器的 process 方法。
     * <b>注意</b>：目前是同步逐条处理，如果单条消息处理耗时长（如调用外部 API），
     * 会阻塞后续消息。未来可改为并行处理或提交到线程池。
     *
     * @param messages  消息 Map（messageId → field/value）
     * @param processor 消息处理器
     * @return true 如果至少处理了一条消息
     */
    private boolean processMessages(
            Map<StreamMessageId, Map<String, String>> messages,
            StreamMessageProcessor processor) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }

        return true;
    }

    /**
     * 创建消费者组（如果不存在）。
     * <p>
     * <b>幂等性设计</b>：通过捕获 BUSYGROUP 错误实现幂等。多次调用不会报错。
     * 这很重要：在多应用实例部署中，每个应用实例启动时都会尝试创建消费者组，
     * 只有第一个实例真正创建，后续实例收到 BUSYGROUP 后静默忽略。
     * <p>
     * 注意：使用 makeStream() 确保 Stream 不存在时自动创建（MKSTREAM 选项）。
     *
     * @param streamKey Stream 键
     * @param groupName 消费者组名称
     */
    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // 组已存在，忽略
            if (e instanceof org.redisson.client.RedisException
                    && e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}", streamKey, groupName, e.getMessage());
        }
    }

    /**
     * 发送消息到 Stream（不限制长度）。
     *
     * @param streamKey Stream 键
     * @param message   消息内容（field → value）
     * @return 生成的消息 ID（格式：{@code timestamp-sequence}）
     */
    public String streamAdd(String streamKey, Map<String, String> message) {
        return streamAdd(streamKey, message, 0);
    }

    /**
     * 发送消息到 Stream（带长度限制）
     *
     * @param streamKey Stream 键
     * @param message   消息内容
     * @param maxLen    最大长度，超过时自动裁剪旧消息，0 表示不限制
     * @return 消息ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    /**
     * 从 Stream 读取消息（消费者组模式，不阻塞）。
     * <p>
     * 与 {@link #streamConsumeMessages} 的区别：本方法不回收 Pending 消息，
     * 非阻塞读取新消息。
     * <p>适合定时任务场景（"每 5 秒拉一次"）而非持续消费场景。</p>
     *
     * @param streamKey    Stream 键
     * @param groupName    消费者组名
     * @param consumerName 消费者名
     * @param count        每次拉取的最大消息数
     * @return 消息 Map，无消息时返回空 Map
     */
    public Map<StreamMessageId, Map<String, String>> streamReadGroup(
            String streamKey, String groupName, String consumerName, int count) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.readGroup(groupName, consumerName,
                StreamReadGroupArgs.neverDelivered().count(count));
    }

    /**
     * 确认消息已处理（ACK）。
     * <p>
     * <b>重要</b>：消费者必须 ACK 已处理的消息，否则它们会永远留在 Pending 列表中，
     * 直到被其他消费者通过 XAUTOCLAIM 回收并处理。
     *
     * @param streamKey Stream 键
     * @param groupName 消费者组名
     * @param ids       要确认的消息 ID（可变参数）
     */
    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

    /**
     * 获取 Stream 中当前的消息总数。
     * <p>
     * 注意：此数量包含所有未被裁剪的消息，不区分"已投递"和"未投递"。
     * 在高吞吐场景下此值的时效性很差（读到的时候可能已经变了）。
     *
     * @param streamKey Stream 键
     * @return 消息总数
     */
    public long streamLen(String streamKey) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.size();
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     */
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    /**
     * 原子自增 1 并返回新值。
     */
    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    /**
     * 原子自减 1 并返回新值。
     */
    public long decrement(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    // ==================== 列表操作 ====================

    /**
     * 在列表尾部添加元素（RPUSH）。
     */
    public <T> void listRightPush(String key, T value) {
        RList<T> list = redissonClient.getList(key);
        list.add(value);
    }

    /**
     * 获取列表中所有元素。
     * <p>
     * <b>性能警告</b>：与 Hash 的 hGetAll 类似，会一次加载到内存。
     * 大列表（>1000 元素）场景应使用 {@code list.range()} 分页获取。
     *
     * @param key 列表的 Redis key
     * @return 列表所有元素，空列表返回空 List
     */
    public <T> List<T> listGetAll(String key) {
        RList<T> list = redissonClient.getList(key);
        return list.readAll();
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     */
    public RedissonClient getClient() {
        return redissonClient;
    }

    /**
     * 按模式（Pattern）批量删除键。
     * <p>
     * 支持 Redis 的 glob 风格通配符：{@code *}（任意字符序列）、{@code ?}（单个字符）、
     * {@code [abc]}（字符集合）。
     * <p>
     * <b>性能注意</b>：在大型 Redis 实例中，{@code KEYS pattern} + 逐个删除
     * 会阻塞 Redis 主线程。生产环境更推荐使用 {@link #findKeysByPattern}
     * 配合 SCAN 命令（非阻塞）。
     *
     * @param pattern Redis key 匹配模式，如 {@code "cache:user:*"}
     * @return 删除的键数量
     */
    public long deleteByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.deleteByPattern(pattern);
    }

    /**
     * 按模式查找键（使用 SCAN 命令，非阻塞）。
     * <p>
     * 与 KEYS 命令不同，SCAN 使用游标分批遍历，不会阻塞 Redis 主线程。
     * 适合生产环境中查找匹配特定模式的 key。
     * <p>
     * 返回的 Iterable 是懒加载的，每次迭代都可能发起一次 SCAN 命令。
     * 如需一次性获取所有结果，使用 {@code forEach} 或 {@code stream().toList()}。
     *
     * @param pattern Redis key 匹配模式
     * @return 匹配的 key 的 Iterable（懒加载，每次迭代都会扫描）
     */
    public Iterable<String> findKeysByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern));
    }
}


