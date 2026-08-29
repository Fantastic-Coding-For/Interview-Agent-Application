package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.file.FileHashService;
import com.ywy.interviewagentapplication.infrastructure.mapper.ResumeMapper;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeAnalysisEntity;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeAnalysisRepository;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * <h1>简历持久化服务</h1>
 *
 * <p>负责将简历文件在 RustFS 中的 CRUD，简历文本内容和 AI 评测结果等在数据库中的 CRUD</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>去重存储</b>：基于文件哈希（SHA-256）检测重复简历，避免相同文件被多次存储</li>
 *   <li><b>JSON 序列化</b>：将 AI 返回的列表字段（strengths、suggestions）序列化为 JSON 字符串存储</li>
 *   <li><b>JSON 反序列化</b>：读取时将 JSON 字符串还原为 Java 对象（使用泛型 TypeReference）</li>
 *   <li><b>级联删除</b>：删除简历时一并删除所有关联的分析记录</li>
 *   <li><b>访问计数</b>：重复上传时递增访问计数（用于热度排序）</li>
 * </ul>
 *
 * <h2>JSON 字段存储策略</h2>
 * <p>AI 分析结果中的 strengths 和 suggestions 是 List 类型，无法直接映射到单列。
 * 采用 Jackson 序列化为 JSON 字符串存入数据库（TEXT 列）：</p>
 * <pre>
 *  strengths: List&lt;String&gt;          →  strengthsJson: TEXT 列  → '["优势1","优势2"]'
 *  suggestions: List&lt;Suggestion&gt;    →  suggestionsJson: TEXT 列 → '[{"category":"...","priority":"..."}]'
 * </pre>
 * <p>读取时通过 {@code objectMapper.readValue(...)} 反序列化还原。</p>
 *
 * <h2>MapStruct 映射分工</h2>
 * <p>基础字段（如 overallScore、summary）使用 MapStruct 自动映射，
 * JSON 字段使用 Jackson 手动处理：</p>
 * <ul>
 *   <li>{@link ResumeMapper#toAnalysisEntity} → 映射基础字段</li>
 *   <li>手动序列化 → JSON 字段</li>
 *   <li>{@link ResumeMapper#toScoreDetail} → 映射评分明细</li>
 * </ul>
 *
 * @see FileHashService 文件哈希计算服务
 * @see ResumeMapper MapStruct 映射器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;

    /**
     * <h3>检查简历是否已存在（基于文件内容哈希去重）</h3>
     *
     * <p>通过计算文件的 SHA-256 哈希值判断是否已上传过相同内容的文件。
     * 如果已存在，在数据库中递增该简历的访问计数（用于排序和统计），并返回已存在的简历实体。</p>
     *
     * <p><b>异常处理：</b>哈希计算失败时返回空 Optional（而非抛出异常），
     * 让调用方按新文件处理。这是一种<b>降级策略</b>即使去重功能失效，
     * 核心上传流程不受影响。</p>
     *
     * @param file 上传的文件
     * @return 已存在的简历实体，或空 Optional（未找到/哈希计算失败）
     */
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            Optional<ResumeEntity> existing = resumeRepository.findByFileHash(fileHash);

            if (existing.isPresent()) {
                log.info("检测到重复简历: hash={}", fileHash);
                ResumeEntity resume = existing.get();
                resume.incrementAccessCount();
                resumeRepository.save(resume);
            }

            return existing;
        } catch (Exception e) {
            log.error("检查简历重复时出错: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * <h3>保存新简历到数据库</h3>
     *
     * <p>将解析后的简历信息（文本、存储信息、文件元数据）持久化到数据库。
     * 在事务内执行，失败时回滚并抛出 BusinessException。</p>
     *
     * @param file       原始上传文件（用于提取元数据）
     * @param resumeText 解析后的纯文本内容
     * @param storageKey RustFS 存储 Key
     * @param storageUrl RustFS 访问 URL
     * @return 保存后的简历实体（包含自动生成的 ID）
     * @throws BusinessException 保存失败时抛出（RESUME_UPLOAD_FAILED）
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl) {
        try {
            String fileHash = fileHashService.calculateHash(file);

            ResumeEntity resume = new ResumeEntity();
            resume.setFileHash(fileHash);
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setResumeText(resumeText);

            ResumeEntity saved = resumeRepository.save(resume);
            log.info("简历已保存: id={}, hash={}", saved.getId(), fileHash);

            return saved;
        } catch (Exception e) {
            log.error("保存简历失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败");
        }
    }

    /**
     * <h3>保存 AI 分析结果</h3>
     *
     * <p>将 AI 返回的 {@link ResumeAnalysisResponse} 转换为数据库实体并持久化。</p>
     *
     * <p><b>数据转换流程：</b></p>
     * <ol>
     *   <li>使用 MapStruct 映射基础字段（overallScore、summary 等）</li>
     *   <li>使用 Jackson 将 strengths（List&lt;String&gt;）序列化为 JSON 字符串</li>
     *   <li>使用 Jackson 将 suggestions（List&lt;Suggestion&gt;）序列化为 JSON 字符串</li>
     *   <li>保存到数据库</li>
     * </ol>
     *
     * <p><b>异常区分：</b></p>
     * <ul>
     *   <li>{@link JacksonException}：序列化失败（理论上不应发生）→ 抛出 BusinessException，仍会被事务处理</li>
     *   <li>其他异常：由 {@code @Transactional} 的 rollbackFor 处理，事务回滚</li>
     * </ul>
     *
     * @param resume   关联的简历实体
     * @param analysis AI 返回的分析结果
     * @return 保存后的分析实体
     * @throws BusinessException JSON 序列化失败时抛出（RESUME_ANALYSIS_FAILED）
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        try {
            // 使用 MapStruct 映射基础字段
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
            entity.setResume(resume);

            // JSON 字段需要手动序列化
            entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

            ResumeAnalysisEntity saved = analysisRepository.save(entity);
            log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
                    saved.getId(), resume.getId(), analysis.overallScore());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化评测结果失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败");
        }
    }

    /**
     * <h3>获取简历的最新分析结果 Entity，不包含简历基本信息</h3>
     *
     * <p>按分析时间降序排列，取第一条。由于分析结果的更新频率不高，
     * 直接查询数据库即可，无需缓存。</p>
     *
     * @param resumeId 简历 ID
     * @return 最新的分析实体，无记录时返回空 Optional
     */
    public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }

    /**
     * <h3>获取简历的最新分析结果 DTO，不包含简历基本信息</h3>
     *
     * <p>与 {@link #getLatestAnalysis} 的区别是直接返回业务 DTO（前端需要），
     * 调用方无需自行转换。</p>
     *
     * @param resumeId 简历 ID
     * @return 最新的分析结果 DTO，无记录时返回空 Optional
     */
    public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
        return getLatestAnalysis(resumeId).map(this::entityToDTO);
    }

    /**
     * 获取所有简历列表（不含分析结果，只有简历基本信息的 Entity）。
     */
    public List<ResumeEntity> findAllResumes() {
        return resumeRepository.findAll();
    }

    /**
     * 获取指定简历的历史分析结果（按时间降序）。
     */
    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }

    /**
     * <h3>将简历分析实体转换为业务 DTO（前端需要）</h3>
     *
     * <p>核心的数据转换方法，处理以下映射：</p>
     * <ul>
     *   <li>基础字段：通过 {@link ResumeMapper#toScoreDetail} 映射评分明细</li>
     *   <li>strengths JSON → List&lt;String&gt;：使用 Jackson + {@link TypeReference} 反序列化</li>
     *   <li>suggestions JSON → List&lt;Suggestion&gt;：同上</li>
     *   <li>originalText：从关联的 ResumeEntity 中获取</li>
     * </ul>
     *
     * <p><b>空值安全：</b>JSON 字段为 null 时使用 {@code "[]"} 作为默认值，
     * 确保反序列化不抛 NPE。</p>
     *
     * @param entity 数据库中的分析实体
     * @return 业务层 DTO
     * @throws BusinessException JSON 反序列化失败时抛出
     */
    public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
        try {
            List<String> strengths = objectMapper.readValue(
                    entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
                    new TypeReference<>() {
                    }
            );

            List<ResumeAnalysisResponse.Suggestion> suggestions = objectMapper.readValue(
                    entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
                    new TypeReference<>() {
                    }
            );

            return new ResumeAnalysisResponse(
                    entity.getOverallScore(),
                    resumeMapper.toScoreDetail(entity),  // 使用MapStruct自动映射
                    entity.getSummary(),
                    strengths,
                    suggestions,
                    entity.getResume().getResumeText()
            );
        } catch (JacksonException e) {
            log.error("反序列化评测结果失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败");
        }
    }

    /**
     * 根据 ID 查询简历（不含分析结果，只有简历基本信息的 Entity）。
     */
    public Optional<ResumeEntity> findById(Long id) {
        return resumeRepository.findById(id);
    }

    /**
     * <h3>删除数据库中指定 ID 的简历及其所有的分析数据</h3>
     * 级联删除，简历分析表，简历基本信息表对应 ID 的记录都会删除。
     *
     * <p>在事务内执行，确保删除操作的原子性。删除顺序：</p>
     * <ol>
     *   <li>查找并校验简历是否存在</li>
     *   <li>删除所有关联的<b>分析记录</b>（ResumeAnalysisEntity）</li>
     *   <li>删除<b>简历实体</b>本身</li>
     * </ol>
     *
     * <p><b>注意：</b>面试会话的删除不在此方法中处理，
     * 由调用方（{@code ResumeDeleteService}）在上层编排。</p>
     *
     * @param id 简历 ID
     * @throws BusinessException 简历不存在时抛出（RESUME_NOT_FOUND）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        Optional<ResumeEntity> resumeOpt = resumeRepository.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();

        // 1. 删除所有简历分析记录
        List<ResumeAnalysisEntity> analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id);
        if (!analyses.isEmpty()) {
            analysisRepository.deleteAll(analyses);
            log.info("已删除 {} 条简历分析记录", analyses.size());
        }

        // 2. 删除简历实体（面试会话会在服务层删除）
        resumeRepository.delete(resume);
        log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
    }
}

