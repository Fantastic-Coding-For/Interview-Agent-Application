package com.ywy.interviewagentapplication.infrastructure.file;

import com.ywy.interviewagentapplication.common.config.S3Config;
import com.ywy.interviewagentapplication.common.config.StorageConfigProperties;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务：统一管理 S3 兼容对象存储（RustFS）的文件操作。
 *
 * <h2>职责范围</h2>
 * 本服务是对 S3 SDK 的门面封装，只关心"文件怎么存、怎么取"，
 * 不关心"存什么内容、用于什么业务"。业务语义由上层调用方赋予：
 * <ul>
 *   <li>简历文件 → {@code uploadResume() / deleteResume()}</li>
 *   <li>知识库文件 → {@code uploadKnowledgeBase() / deleteKnowledgeBase()}</li>
 *   <li>通用下载 → {@code downloadFile()}</li>
 * </ul>
 *
 * <h2>关键设计决策</h2>
 *
 * <h3>1. 文件名安全化：汉字 → 拼音转换</h3>
 * 中文文件名在 S3 协议中虽被支持，但可能引发以下问题：
 * <ul>
 *   <li>URL 编码不一致导致缓存命中失败</li>
 *   <li>某些 S3 兼容实现（如 MinIO 旧版）对非 ASCII key 处理不佳</li>
 *   <li>日志/监控系统中中文可读性差</li>
 * </ul>
 * 因此将汉字转为大驼峰拼音，保留原有英文/数字，特殊字符替换为下划线。
 * 详见 {@link #convertToPinyin(String)}。
 *
 * <h3>2. 文件键命名规范：{prefix}/{yyyy/MM/dd}/{uuid}_{safeName}</h3>
 * <pre>{@code
 * resumes/2026/08/09/a1b2c3d4_ZhangSanJianLi.pdf
 * knowledgebases/2026/08/09/e5f6g7h8_GongSiShouCe.docx
 * }</pre>
 * 按日期分目录便于：
 * <ul>
 *   <li>存储端按前缀扫描/清理过期文件</li>
 *   <li>人工排查问题时快速定位</li>
 *   <li>UUID（8 位前缀）防止同名文件覆盖</li>
 * </ul>
 *
 * <h3>3. 启动时自动建桶</h3>
 * 通过 {@link PostConstruct} + {@code autoCreateBucket} 开关实现。
 * 开发环境自动建桶减少配置步骤（尽力创建，但不保证成功。若创建失败会导致应用启动失败）；生产环境可关闭由运维统一管理。
 *
 * <h3>4. 同步 S3 客户端</h3>
 * 使用 {@code S3Client}（同步）而非 {@code S3AsyncClient}（异步），
 * 原因：当前业务场景中文件操作都在请求线程内完成，且文件量不大，
 * 同步调用更简单可靠，避免异步状态管理的复杂度。
 *
 * @see S3Config 客户端配置
 * @see StorageConfigProperties 存储配置属性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    /**
     * 应用启动后初始化：根据配置决定是否自动创建存储桶。
     * <p>
     * 使用 {@link PostConstruct} 而非 {@code ApplicationRunner}：
     * 此初始化只依赖配置和外部的 S3Client Bean（两者在此时已就绪），
     * 不需要完整的 ApplicationContext 启动完成信号。
     */
    @PostConstruct
    public void init() {
        if (!storageConfig.isAutoCreateBucket()) {
            log.info("存储桶启动检查已关闭: bucket={}", storageConfig.getBucket());
            return;
        }
        ensureBucketExists();
    }

    /**
     * 上传简历文件到 S3 存储。
     */
    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    /**
     * 删除简历文件
     */
    public void deleteResume(String fileKey) {
        deleteFile(fileKey);
    }

    /**
     * 上传知识库文件到 S3 存储。
     */
    public String uploadKnowledgeBase(MultipartFile file) {
        return uploadFile(file, "knowledgebases");
    }

    /**
     * 删除知识库文件
     */
    public void deleteKnowledgeBase(String fileKey) {
        deleteFile(fileKey);
    }

    /**
     * 从 S3 下载文件（通用方法，不区分业务类型）。
     *
     * <h3>实现细节</h3>
     * <ol>
     *   <li>先通过 {@code fileExists()} 检查文件是否存在，避免下载 404 时
     *       抛出难以区分的 S3Exception</li>
     *   <li>使用 {@code getObjectAsBytes()} 一次性读取全部内容到内存。适合简历/文档等小文件场景；大文件应改用流式下载</li>
     * </ol>
     *
     * @param fileKey 文件存储键
     * @return 文件字节数组
     * @throws BusinessException 若文件不存在或下载失败
     */
    public byte[] downloadFile(String fileKey) {
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + fileKey);
        }

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        } catch (S3Exception e) {
            log.error("下载文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage());
        }
    }

    /** 文件键中的日期路径格式器：yyyy/MM/dd（线程安全，全局复用） */
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 通用文件上传方法。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>根据原始文件名生成安全的存储键（拼音 + UUID + 日期路径）</li>
     *   <li>构建 PutObjectRequest，设置 Content-Type 和 Content-Length</li>
     *   <li>从 MultipartFile 的 InputStream 直接流式上传
     *       （避免将整个文件加载到内存再发送）</li>
     * </ol>
     *
     * <h3>异常处理策略</h3>
     * <ul>
     *   <li>{@link IOException}：文件读取层面问题（客户端侧），通常不可恢复</li>
     *   <li>{@link S3Exception}：网络/存储层面问题（服务端侧），可重试但当前直接失败</li>
     * </ul>
     *
     * @param file   上传的文件
     * @param prefix 业务前缀（如 "resumes" 或 "knowledgebases"）
     * @return 生成的文件存储键
     */
    private String uploadFile(MultipartFile file, String prefix) {
        String originalFilename = file.getOriginalFilename();
        String fileKey = generateFileKey(originalFilename, prefix);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey);
            return fileKey;
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败");
        } catch (S3Exception e) {
            log.error("上传文件到RustFS失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: " + e.getMessage());
        }
    }

    /**
     * 检查 S3 中指定 key 的文件是否存在。
     *
     * <h3>为什么用 HeadObject 而非 GetObject</h3>
     * {@code HeadObjectRequest} 只返回对象元数据（HTTP HEAD 语义），
     * 不传输文件内容，对于仅判断存在性的场景比 GetObject 高效得多
     * （省去文件体传输的带宽和时间）。
     *
     * <h3>返回值语义</h3>
     * 出于健壮性考虑，非 NoSuchKeyException 的 S3Exception（如网络超时、
     * 权限不足）也返回 {@code false} 而非向上抛异常。
     * 因为调用方通常将"不存在"等价于"可以继续操作"（如删除时跳过），
     * 抛异常反而会中断正常流程。
     *
     * @param fileKey 文件存储键
     * @return {@code true} 文件存在；{@code false} 文件不存在或网络异常
     */
    public boolean fileExists(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("检查文件存在性失败: {} - {}", fileKey, e.getMessage());
            return false;
        }
    }

    /**
     * 获取 S3 文件的大小（字节数）。
     *
     * <p>通过 HeadObject 响应中的 {@code Content-Length} 头部获取，
     * 不下载文件内容。
     *
     * @param fileKey 文件存储键
     * @return 文件大小（字节）
     * @throws BusinessException 若文件不存在或查询失败
     */
    public long getFileSize(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.headObject(headRequest).contentLength();
        } catch (S3Exception e) {
            log.error("获取文件大小失败: {} - {}", fileKey, e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败");
        }
    }

    /**
     * 通用文件删除方法。
     *
     * <h3>删除前检查的两层意义</h3>
     * <ol>
     *   <li><b>空键检查：</b>防止 null/空字符串导致 S3 SDK 抛出难以理解的错误</li>
     *   <li><b>存在性检查：</b>避免发送必然失败的 DeleteObjectRequest，
     *       同时避免"删除不存在对象"在某些 S3 实现中产生意外告警</li>
     * </ol>
     *
     * <h3>注意</h3>
     * S3 的 DeleteObject 在对象不存在时通常返回 204（成功），
     * 但此处仍做前置检查，不是功能需要，是运维友好：跳过时的日志
     * 比删除不存在对象时的安静成功更利于排查"为什么文件没了"这类问题。
     *
     * @param fileKey 待删除的文件存储键
     */
    private void deleteFile(String fileKey) {
        // 空键直接跳过
        if (fileKey == null || fileKey.isEmpty()) {
            log.debug("文件键为空，跳过删除");
            return;
        }

        // 检查文件是否存在，避免不必要地删除
        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: {}", fileKey);
            return;
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("文件删除成功: {}", fileKey);
        } catch (S3Exception e) {
            log.error("删除文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: " + e.getMessage());
        }
    }

    /**
     * 构建文件的外部访问 URL。
     *
     * <p><b>警告：</b>此方法拼接的是 S3 原始端点 URL，<b>未生成签名/预签名 URL</b>。
     * 如果桶不是公开读取的，该 URL 将无法直接访问。
     * 生产环境应使用 {@code S3Presigner} 或 CDN 地址替代。
     *
     * @param fileKey 文件存储键
     * @return 文件访问 URL（格式：{endpoint}/{bucket}/{fileKey}）
     */
    public String getFileUrl(String fileKey) {
        return String.format("%s/%s/%s", storageConfig.getEndpoint(), storageConfig.getBucket(), fileKey);
    }

    /**
     * 确保存储桶存在：若不存在则创建。
     *
     * <h3>并发安全性</h3>
     * 多个实例同时启动时可能同时尝试建桶。处理策略：
     * <ol>
     *   <li>先发 HeadBucket 检查存在性</li>
     *   <li>若返回 404（NoSuchBucketException 或 statusCode=404），调用 createBucket</li>
     *   <li>createBucket 中捕获 409（Conflict），说明已被其他实例抢先创建，
     *       视为成功。这是 S3 的幂等性保证</li>
     * </ol>
     *
     * <h3>为什么不用 @PostConstruct + 锁</h3>
     * 桶的创建是幂等的（S3 层面保证），不同实例之间的竞争不影响正确性，
     * 加锁只会增加复杂度而无实际收益。
     */
    public void ensureBucketExists() {
        try {
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            // 验证指定的存储桶是否存在，以及当前凭证是否拥有对该存储桶的 s3:ListBucket 权限。
            // 如果存储桶不存在或权限不足，该方法会直接抛出异常。
            s3Client.headBucket(headRequest);
            log.info("存储桶已存在: {}", storageConfig.getBucket());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket();
                return;
            }
            log.error("检查存储桶失败: bucket={}, error={}", storageConfig.getBucket(), e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    "检查存储桶失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * 创建 S3 存储桶。
     *
     * <p>捕获 409（Conflict）状态码处理并发创建场景：
     * 若多个应用实例同时启动且桶不存在，只有一个实例能成功创建，
     * 其余实例收到 409 → 视为成功（桶已由其他实例创建完毕）。
     */
    private void createBucket() {
        try {
            log.info("存储桶不存在，正在创建: {}", storageConfig.getBucket());
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.createBucket(createRequest);
            log.info("存储桶创建成功: {}", storageConfig.getBucket());
        } catch (S3Exception e) {
            if (e.statusCode() == 409) {
                log.info("存储桶已由其他进程创建: {}", storageConfig.getBucket());
                return;
            }
            log.error("创建存储桶失败: bucket={}, error={}", storageConfig.getBucket(), e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    "创建存储桶失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * 生成唯一的文件存储键。
     *
     * <h3>命名规范</h3>
     * <pre>{@code
     * {prefix}/{yyyy/MM/dd}/{8位uuid}_{安全文件名}
     *
     * 示例:
     *   resumes/2026/08/09/a1b2c3d4_ZhangSanJianLi.pdf
     *   knowledgebases/2026/08/09/e5f6g7h8_GongSiShouCe.docx
     * }</pre>
     *
     * <h3>设计考虑</h3>
     * <ul>
     *   <li><b>日期路径：</b>方便按天清理/归档/计费</li>
     *   <li><b>UUID（前 8 位）：</b>防止同名文件覆盖；只用前 8 位是
     *       在"唯一性"和"可读性"之间的平衡——完整 UUID 对日志太冗长</li>
     *   <li><b>安全文件名：</b>汉字转拼音，特殊字符替换为下划线</li>
     * </ul>
     *
     * @param originalFilename 用户上传的原始文件名
     * @param prefix           业务前缀（resumes / knowledgebases）
     * @return 生成的文件存储键
     */
    private String generateFileKey(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    /**
     * 清理文件名，移除不安全的字符。
     * <p>
     * 处理策略：
     * <ul>
     *   <li>汉字 → 大驼峰拼音（如"张三简历" → "ZhangSanJianLi"）</li>
     *   <li>字母、数字、点号、下划线、连字符 → 保留</li>
     *   <li>其他字符 → 替换为下划线</li>
     *   <li>空文件名 → 返回 "unknown"</li>
     * </ul>
     * <p>
     * 这样处理的原因是：S3 对象键虽然支持 UTF-8，但 URL 编码、
     * 日志可读性、跨平台兼容方面英文文件名远优于中文。
     *
     * @param filename 原始文件名
     * @return 清理后的安全文件名
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return convertToPinyin(filename);
    }

    /**
     * 将字符串中的汉字转换为大驼峰拼音，非汉字字符经安全过滤后保留。
     *
     * <h3>算法说明</h3>
     * 逐字符扫描输入：
     * <ul>
     *   <li>若字符是汉字（pinyin4j 返回非 null 拼音数组）→ 取第一个读音，
     *       首字母大写（大驼峰），后续汉字同理拼接</li>
     *   <li>若字符不是汉字 → 走 {@link #sanitizeChar(char)} 安全过滤</li>
     * </ul>
     *
     * <h3>为什么取第一个读音</h3>
     * 多音字（如"长"可读 chang/zhang）在文件名场景中无法精确判断上下文，
     * 取第一个读音是最简单的处理方式。如需精确转换，
     * 可引入词典分词（如 HanLP）进行词级别判断。
     *
     * <h3>依赖说明</h3>
     * 使用 pinyin4j 库（net.sourceforge.pinyin4j），
     *
     * @param input 可能包含汉字的输入字符串
     * @return 拼音转换后的字符串
     */
    private String convertToPinyin(String input) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

        StringBuilder result = new StringBuilder();
        for (char ch : input.toCharArray()) {
            try {
                String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                if (pinyins != null && pinyins.length > 0) {
                    // 首字母大写（大驼峰）
                    result.append(capitalize(pinyins[0]));
                } else {
                    // 非汉字字符直接保留，但特殊字符需要处理
                    result.append(sanitizeChar(ch));
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                result.append(sanitizeChar(ch));
            }
        }
        return result.toString();
    }

    /**
     * 处理单个非汉字字符字符：保留安全字符（字母、数字、点号、下划线、连字符），
     * 其他一律替换为下划线。
     * 例如：空格 → '_'，中文标点 → '_'，emoji → '_'。
     */
    private char sanitizeChar(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
            return ch;
        }
        if (ch == '.' || ch == '_' || ch == '-') {
            return ch;
        }
        return '_';
    }

    /**
     * 首字母大写（用于构建大驼峰拼音）。
     *
     * @param str 输入字符串
     * @return 首字母大写的字符串
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

