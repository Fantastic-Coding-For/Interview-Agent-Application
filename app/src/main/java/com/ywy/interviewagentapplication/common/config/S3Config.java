package com.ywy.interviewagentapplication.common.config;

import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 客户端配置类（用于 RustFS / 兼容 S3 协议的对象存储）。
 *
 * <h2>技术选型：为什么用 AWS SDK v2 的 S3Client</h2>
 * RustFS 实现了 S3 兼容 API，因此可以直接复用 AWS S3 SDK 作为客户端。
 * 这避免了引入额外的 RustFS 专用 SDK，降低了依赖复杂度。
 * SDK v2 相比 v1 的优势：异步支持、超时控制优化、模块化依赖。
 *
 * <h2>关键设计决策</h2>
 *
 * <h3>1. forcePathStyle = true</h3>
 * 硬编码使用路径风格访问（{@code endpoint/bucket/key}），而非虚拟主机风格
 * （{@code bucket.endpoint/key}）。原因是 RustFS/自建存储通常不支持
 * 虚拟主机风格的 DNS 解析，使用路径风格可避免
 * {@code UnknownHostException}。
 *
 * <h3>2. 自定义超时配置</h3>
 * 通过 {@link ClientOverrideConfiguration} 将
 * {@link StorageConfigProperties} 中的 Duration 配置注入 SDK，
 * 实现细粒度的超时控制（总时限 + 单次尝试时限双层保护）。
 *
 * <h3>3. StaticCredentialsProvider</h3>
 * 使用静态凭证提供者，启动时一次性加载 AccessKey/SecretKey。
 * 如需支持凭证轮转，可替换为
 * {@code DefaultCredentialsProvider} 或自定义 Provider。
 *
 * @see StorageConfigProperties
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageConfigProperties storageConfig;

    /**
     * 创建并配置 S3 客户端 Bean（单例，整个应用共享）。
     *
     * <h3>初始化参数说明</h3>
     * <ul>
     *   <li>{@code endpointOverride}：指向 RustFS 服务地址（而非 AWS 公网地址）</li>
     *   <li>{@code region}：使用配置中的区域标识，RustFS 通常用 {@code us-east-1}</li>
     *   <li>{@code credentialsProvider}：静态凭证提供者，使用配置中的 AK/SK</li>
     *   <li>{@code forcePathStyle(true)}：<b>关键配置</b>，硬编码使用路径风格 URI，
     *       避免 SDK 尝试将 bucket 解析为子域名导致 DNS 失败</li>
     *   <li>{@code overrideConfiguration}：注入自定义超时配置</li>
     * </ul>
     *
     * @return 已配置的 S3Client 实例
     */
    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                storageConfig.getAccessKey(),
                storageConfig.getSecretKey()
        );

        return S3Client.builder()
                .endpointOverride(URI.create(storageConfig.getEndpoint()))
                .region(Region.of(storageConfig.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .overrideConfiguration(clientOverrideConfiguration())
                // 使用路径风格访问，避免 SDK 使用 bucket.endpoint 导致 DNS 解析失败。
                .forcePathStyle(true)
                .build();
    }

    private ClientOverrideConfiguration clientOverrideConfiguration() {
        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder();
        Duration apiCallTimeout = storageConfig.getApiCallTimeout();
        Duration apiCallAttemptTimeout = storageConfig.getApiCallAttemptTimeout();
        if (apiCallTimeout != null) {
            builder.apiCallTimeout(apiCallTimeout);
        }
        if (apiCallAttemptTimeout != null) {
            builder.apiCallAttemptTimeout(apiCallAttemptTimeout);
        }
        return builder.build();
    }
}

