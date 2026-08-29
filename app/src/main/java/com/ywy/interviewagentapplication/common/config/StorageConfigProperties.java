package com.ywy.interviewagentapplication.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS（S3 兼容）存储配置属性类。
 *
 * <h2>设计意图</h2>
 * 将 S3 兼容对象存储的所有连接参数集中管理，通过 Spring Boot 的
 * {@code @ConfigurationProperties} 机制从 application.yml 中自动绑定，
 * 避免配置散落各处，便于不同环境（dev/staging/prod）切换。
 *
 * <h2>为什么用 Duration 类型而不是 long</h2>
 * Spring Boot 2.x+ 原生支持将 {@code 60s}、{@code 20s} 这样的
 * 人类可读格式自动转换为 {@link java.time.Duration} 对象，
 * 比用 long 毫秒值更直观且不易出错。
 *
 * <h2>配置示例（application.yml）</h2>
 * <pre>{@code
 * app:
 *   storage:
 *     endpoint: http://rustfs:9000
 *     access-key: admin
 *     secret-key: admin123
 *     bucket: interview-bucket
 *     region: us-east-1
 *     api-call-timeout: 60s
 *     api-call-attempt-timeout: 20s
 *     auto-create-bucket: true
 * }</pre>
 *
 * @see S3Config
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {

    /**
     * S3 兼容存储的服务端点 URL。
     * 例如：{@code http://rustfs:9000}（RustFS）或 {@code https://s3.amazonaws.com}（AWS S3）。
     */
    private String endpoint;
    /**
     * S3 Access Key（访问密钥 ID）。
     * 在 RustFS 中通常对应用户名；在 AWS S3 中对应 IAM 用户的 Access Key。
     */
    private String accessKey;
    /**
     * S3 Secret Key（访问密钥密码）。
     * 与 accessKey 配对使用，用于 HMAC 签名认证。
     * <p>
     * <b>安全提醒：</b>生产环境应通过环境变量或密钥管理服务注入，
     * 切勿硬编码在配置文件中。
     */
    private String secretKey;
    /**
     * 存储桶名称，应用的所有文件操作（上传/下载/删除）都在此桶内进行。
     * <h4>必须显式配置</h4>
     * 若没有显式配置：
     * <p>1. 若未开启应用启动时自动创建存储桶 {@code autoCreateBucket = false}，则应用启动后无法存储文件，相应操作只会抛业务异常 </p>
     * <p>2. 若开启应用启动时自动创建存储桶 {@code autoCreateBucket = true}，由于非法桶名称（null）而抛出未捕获异常，直接导致应用启动失败 </p>
     */
    private String bucket;
    /**
     * S3 区域标识，默认值为 {@code "us-east-1"}。
     * RustFS 通常使用默认值即可；AWS S3 需与实际区域匹配。
     */
    private String region = "us-east-1";
    /**
     * 单次 API 调用的总超时时间，默认 60 秒。
     * <p>
     * 这包括连接建立、请求发送和响应接收的完整时间。
     * 对大文件上传尤其重要。
     * 如果文件超过 100MB，60 秒可能不够，需要根据实际场景调大。
     */
    private Duration apiCallTimeout = Duration.ofSeconds(60);
    /**
     * 单次 API 调用尝试的超时时间，默认 20 秒。
     * <p>
     * 与 {@link #apiCallTimeout} 的区别：
     * <ul>
     *   <li>{@code apiCallTimeout}：整个调用的总时限（含重试）</li>
     *   <li>{@code apiCallAttemptTimeout}：单次网络尝试的时限（不含重试）</li>
     * </ul>
     * SDK 会在总时限内自动重试，每次尝试受此值限制。
     */
    private Duration apiCallAttemptTimeout = Duration.ofSeconds(20);
    /**
     * 是否在应用启动时自动创建存储桶，默认 {@code true}。
     * <p>
     * 开发环境建议开启，免去手动建桶步骤；
     * 生产环境可根据运维策略关闭（桶通常由 IaC 工具预先创建）。
     * <p>
     * 对应的启动检查逻辑见 {@link com.ywy.interviewagentapplication.infrastructure.file.FileStorageService#init()}。
     */
    private boolean autoCreateBucket = true;
}

