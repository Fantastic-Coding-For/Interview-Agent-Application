package com.ywy.interviewagentapplication.infrastructure.file;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件哈希服务：统一提供 SHA-256 文件哈希计算功能，用于文件去重。
 *
 * <h2>业务背景：为什么需要文件哈希去重</h2>
 * 用户的简历文件可能被重复上传（误操作、换设备重新上传同一份简历等）。
 * 如果每次上传都重新解析存储，会导致：
 * <ul>
 *   <li>冗余的 S3 存储占用（同一文件存多份）</li>
 *   <li>重复的 AI 分析调用（浪费 LLM token）</li>
 *   <li>数据库中的重复记录</li>
 * </ul>
 * 通过 SHA-256 哈希进行内容级去重，可以有效避免上述问题。
 *
 * <h2>为什么选择 SHA-256</h2>
 * <ul>
 *   <li><b>碰撞概率极低：</b>SHA-256 的输出空间为 2^256，
 *       在实际业务规模下碰撞概率可忽略不计</li>
 *   <li><b>Java 原生支持：</b>{@link MessageDigest} 内置 SHA-256，
 *       无需引入额外依赖（如 Guava 的 HashFunction）</li>
 *   <li><b>内容敏感：</b>文件的任意字节变化都会导致完全不同的哈希，
 *       适合内容级去重（而文件名/修改时间不适用）</li>
 * </ul>
 *
 * <h2>性能考虑</h2>
 * <ul>
 *   <li><b>小文件（&lt;10MB）：</b>直接使用 {@link #calculateHash(byte[])}，
 *       一次性加载到内存计算，代码最简单</li>
 *   <li><b>大文件（&gt;10MB）：</b>使用 {@link #calculateHash(InputStream)}，
 *       8KB 缓冲区流式计算，避免 IO 阻塞或者 OOM</li>
 * </ul>
 */
@Slf4j
@Service
public class FileHashService {
    /** 哈希算法名称（SHA-256 在 Java 中对应的标准名称） */
    private static final String HASH_ALGORITHM = "SHA-256";
    /** 流式计算的缓冲区大小：8KB（Java IO 的经典缓冲区大小，与操作系统页大小接近） */
    private static final int BUFFER_SIZE = 8192;

    /**
     * 计算 MultipartFile 文件的 SHA-256 哈希值。
     * 内部委托给 {@link #calculateHash(byte[])}。
     *
     * @param file 上传的文件
     * @return 十六进制格式的 SHA-256 哈希字符串（64 个字符，全小写）
     * @throws BusinessException 若文件读取失败
     */
    public String calculateHash(MultipartFile file) {
        try {
            return calculateHash(file.getBytes());
        } catch (IOException e) {
            log.error("读取文件内容失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 计算字节数组的 SHA-256 哈希值。
     * <p>对于大文件（&gt;10MB），应优先使用 {@link #calculateHash(InputStream)} 流式版本。</p>
     * @param data 原始字节数组
     * @return 十六进制格式的 SHA-256 哈希字符串
     * @throws BusinessException 若 SHA-256 算法不可用（理论上不会发生，Java 标准库内置）
     */
    public String calculateHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法不支持: {}", HASH_ALGORITHM, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 流式计算输入流的 SHA-256 哈希值（适用于大文件）。
     *
     * <h3>为什么用流式而非一次性读取</h3>
     * 假设一个 200MB 的简历附件（包含作品集 PDF）：
     * <ul>
     *   <li>{@code calculateHash(byte[])}：先分配 200MB 堆内存 → 触发 GC → 可能 OOM</li>
     *   <li>{@code calculateHash(InputStream)}：8KB 缓冲区循环读取，
     *       恒定内存占用，无 GC 压力</li>
     * </ul>
     *
     * <h3>注意</h3>
     * 调用方负责关闭 InputStream。本方法只读取流，不管理流的生命周期。
     *
     * @param inputStream 文件输入流
     * @return 十六进制格式的 SHA-256 哈希字符串
     * @throws BusinessException 若读取流失败或算法不可用
     */
    public String calculateHash(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("计算文件哈希失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 将字节数组转换为十六进制字符串。
     *
     * <h3>实现说明</h3>
     * <ul>
     *   <li>每个字节转换为 2 个十六进制字符（00-ff）</li>
     *   <li>使用 {@code String.format("%02x", b)} 而非手动查表，
     *       以牺牲微小的性能换取代码可读性</li>
     *   <li>对于性能敏感的场景，可替换为查表法
     *       （{@code "0123456789abcdef".charAt((b >> 4) & 0x0F)}）</li>
     * </ul>
     *
     * @param bytes 哈希算法的原始输出字节数组（SHA-256 为 32 字节）
     * @return 十六进制字符串（SHA-256 为 64 个字符）
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
