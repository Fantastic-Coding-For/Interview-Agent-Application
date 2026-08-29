package com.ywy.interviewagentapplication.common.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/**
 * 将需要事务管理的方法，抽象封装到另一个 Spring Bean 中，防止自调用
 * 这种方式符合单一职责原则，能让事务边界更清晰，也是长期维护中最推荐的方案
 * 使用方式：
 *   transactionalExecutor.run(() -> repository.save(entity));  // 无返回值
 *   transactionalExecutor.call(() -> repository.findById(id)); // 有返回值
 * 铁律：LLM 调用、S3 上传、外部 HTTP 不能放在事务内，这些操作禁止在 run()/call() 的 lambda 中执行
 */
@Service
public class TransactionalExecutor {

    /** 在默认事务中执行（无返回值），异常时回滚 */
    @Transactional(rollbackFor = Exception.class)
    public void run(Runnable action) {
        action.run();
    }

    /** 在默认事务中执行（有返回值），异常时回滚 */
    @Transactional(rollbackFor = Exception.class)
    public <T> T call(Supplier<T> action) {
        return action.get();
    }

    /** 挂起当前事务，开启新事务执行（无返回值），常用于独立日志/审计写入 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void runRequiresNew(Runnable action) {
        action.run();
    }

    /** 挂起当前事务，开启新事务执行（有返回值） */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public <T> T callRequiresNew(Supplier<T> action) {
        return action.get();
    }
}
