package com.ywy.interviewagentapplication.modules.interview.skill;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.ai.PromptSanitizer;
import com.ywy.interviewagentapplication.common.ai.PromptSecurityConstants;
import com.ywy.interviewagentapplication.common.ai.StructuredOutputInvoker;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试方向 Skill 管理服务：预设 Skill 加载、面试维度定义、参考资料注入、JD 解析。
 *
 * <h3>架构定位</h3>
 * 本类负责面试 Skill 的<b>静态配置管理</b>和<b>动态内容注入</b>：
 * <ul>
 *   <li><b>启动时加载</b>：从 classpath:skills/{skillId}/SKILL.md 扫描并解析所有预设 Skill，
 *       构建面试→知识点参考文件的全局索引</li>
 *   <li><b>运行时注入</b>：根据题目分配方案，将相关的参考文件内容注入到 LLM 提示词中，
 *       让 LLM 有"参考答案"可依据</li>
 *   <li><b>JD 解析</b>：将职位描述提交给 LLM，自动提取面试考察方向</li>
 * </ul>
 *
 * <h3>与 SkillsTool 的分工</h3>
 * 项目中有另一个 SkillsTool 负责 LLM 按需加载 persona（SKILL.md body 内容）。
 * 本类与 SkillsTool 互补：
 * <ul>
 *   <li>SkillsTool → LLM 对话中的"技能发现"（让 LLM 自己决定加载哪个 Skill）</li>
 *   <li>本类 → 后端预加载 + 结构化解析 + 批量注入 ref（确定性逻辑）</li>
 * </ul>
 *
 * <h3>参考文件加载策略（多层 Fallback，允许参考文件灵活放置）</h3>
 * 查找参考文件时按以下顺序尝试（{@link #resolveReferenceLocations}）：
 * <ol>
 *   <li>shared 参考：classpath:skills/_shared/references/{ref}</li>
 *   <li>Skill 专属参考：classpath:skills/{skillId}/references/{ref}</li>
 *   <li>Skill 根目录：classpath:skills/{skillId}/{ref}</li>
 *   <li>非 shared 的回退：尝试 shared 路径</li>
 * </ol>
 *
 * @see InterviewSkillProperties Skill 数据模型定义
 * @see InterviewQuestionService 使用本类进行题目分配和参考注入
 */
@Slf4j
@Service
public class InterviewSkillService {

    public static final String CUSTOM_SKILL_ID = "custom";

    /** JD 解析的最短文本长度，太短的文本不足以提取有意义的面试维度 */
    private static final int MIN_JD_LENGTH = 50;
    /** 面试维度标签的最大长度，防止 LLM 生成过长的标签破坏 UI 布局 */
    private static final int MAX_CATEGORY_LABEL_LENGTH = 50;
    /** 面试维度 Key 的最大长度 */
    private static final int MAX_CATEGORY_KEY_LENGTH = 50;

    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final String SKILL_META_FILE = "skill.meta.yml";
    /** JD 解析的系统提示词路径 */
    private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/jd-parse-system.st";

    /** 出题阶段参考内容最大字符数。防止提示词过长导致 LLM 注意力稀释 */
    private static final int MAX_REFERENCE_SECTION_CHARS = 12000;
    /**
     * 评估阶段参考基线最大字符数
     */
    private static final int MAX_EVALUATION_REFERENCE_SECTION_CHARS = 6000;
    /** 单个参考文件的最大字符数。超长文件（如完整的 API 文档）截断保留最前面的内容 */
    private static final int MAX_SINGLE_REFERENCE_CHARS = 3000;

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final BeanOutputConverter<CategoryListDTO> jdOutputConverter;
    private final PromptTemplate jdSystemPromptTemplate;
    private final ResourceLoader resourceLoader;
    private final PromptSanitizer promptSanitizer;

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();

    /** 参考内容缓存（classpath 资源不可变，加载一次后复用） */
    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /** 分类（面试维度）的逻辑标识 → RefMapping 映射，启动时构建，之后只读 */
    private final Map<String, RefMapping> categoryRefIndex = new HashMap<>();

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private String cachedReferenceFileList;

    /** ref 文件映射记录：面试维度关联的物理文件名 + 是否共享 + 引用来源 skillId */
    record RefMapping(String ref, boolean shared, String sourceSkillId) {}

    public InterviewSkillService(LlmProviderRegistry llmProviderRegistry,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 ResourceLoader resourceLoader,
                                 PromptSanitizer promptSanitizer) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.promptSanitizer = promptSanitizer;
        this.jdOutputConverter = new BeanOutputConverter<>(CategoryListDTO.class) {};
        this.jdSystemPromptTemplate = new PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH));
    }

    /**
     * 应用启动后扫描并加载所有预设 Skill。
     * <p>
     * 扫描路径：classpath:skills/* /SKILL.md（PathMatchingResourcePatternResolver）。
     * 跳过 skillId 为 "_shared" 的目录（那是共享参考文件的存放位置，不是 Skill）。
     */
    @PostConstruct
    void loadPresetSkills() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            String skillId = extractSkillId(resource);
            if (skillId == null || "_shared".equals(skillId)) {
                continue;
            }

            InterviewSkillProperties.SkillDefinition def = parseSkillDefinition(skillId, resource, yaml);
            if (def.getName() == null || def.getName().isBlank()) {
                log.warn("跳过无效 Skill（缺少 name）: {}", skillId);
                continue;
            }

            presetRegistry.put(skillId, def);
            log.info("加载预设 Skill: {} ({})", skillId, def.getName());
        }

        log.info("共加载 {} 个预设 Skill", presetRegistry.size());

        buildCategoryRefIndex();
        cachedReferenceFileList = buildReferenceFileList();
    }

    /**
     * 构建 categoryRefIndex（分类的逻辑标识 → RefMapping 映射）
     * <p>
     * 遍历所有预设 Skill 的所有面试维度，将配置了 ref 字段的面试注册到索引中。
     * 使用 {@code putIfAbsent} 而非 {@code put}：如果多个 Skill 的同一 category key
     * 配置了不同的 ref，保留第一个（首次遇到）的映射。实际上同一 category key 在不同 Skill 中通常指向相同的 ref（因为有 shared 机制），
     * 但 putIfAbsent 提供了额外的防御性保证。
     */
    private void buildCategoryRefIndex() {
        categoryRefIndex.clear();
        for (var entry : presetRegistry.entrySet()) {
            InterviewSkillProperties.SkillDefinition def = entry.getValue();
            if (def.getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : def.getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank() && cat.getKey() != null) {
                    categoryRefIndex.putIfAbsent(cat.getKey(),
                            new RefMapping(cat.getRef(), Boolean.TRUE.equals(cat.getShared()), entry.getKey()));
                }
            }
        }
        log.info("构建 category→reference 映射: {} 个条目", categoryRefIndex.size());
    }

    /** 获取所有预设 Skill 的列表 */
    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
                .map(e -> toSkillDTO(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 获取指定 Skill 的详细信息。
     *
     * @throws BusinessException 如果 Skill 不存在
     */
    public SkillDTO getSkill(String skillId) {
        InterviewSkillProperties.SkillDefinition preset = presetRegistry.get(skillId);
        if (preset != null) {
            return toSkillDTO(skillId, preset);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
    }

    /**
     * 从 JD 解析结果构建自定义 Skill DTO。
     * <p>
     * <b>参考文件纠正逻辑</b>：
     * LLM 在 JD 解析时可能返回 ref 和 shared 信息（匹配到参考文件）。
     * 但 LLM 的匹配可能不准确，此时用本地 categoryRefIndex 进行纠正。
     * <p>
     * 纠正策略：如果 categoryRefIndex 中存在相同 key 的映射，
     * 优先使用本地映射（本地映射由开发者维护，比 LLM 判断更可靠）。
     * 如果 ref 或 shared 不同，记录 info 日志用于监控纠正频率。
     * 频繁地纠正说明 LLM 的匹配逻辑有问题，需要优化 JD 解析提示词。
     *
     * @param customCategories JD 解析返回的分类（面试维度）列表
     * @param jdText           原始 JD 文本
     * @return 自定义 Skill DTO
     */
    public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
        List<SkillCategoryDTO> categories = customCategories.stream()
                .filter(cat -> cat.key() != null && cat.label() != null)
                .map(cat -> {
                    String safeKey = sanitizeCategoryKey(cat.key());
                    String safeLabel = sanitizeCategoryLabel(cat.label());
                    RefMapping refMapping = categoryRefIndex.get(safeKey);
                    if (refMapping != null) {
                        if (!refMapping.ref().equals(cat.ref())
                                || refMapping.shared() != Boolean.TRUE.equals(cat.shared())) {
                            log.info("JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                                    safeKey, cat.ref(), cat.shared(), refMapping.ref(), refMapping.shared());
                        }
                        return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                                refMapping.ref(), refMapping.shared());
                    }
                    return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                            cat.ref(), Boolean.TRUE.equals(cat.shared()));
                })
                .toList();

        long matchedCount = categories.stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
        log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size(), matchedCount);

        return new SkillDTO(CUSTOM_SKILL_ID, "自定义面试（JD 解析）",
                "基于职位描述提取的面试方向", categories,
                false, jdText, null, null);
    }

    /**
     * 解析 JD 文本，由 LLM 提取面试考察方向。
     * <p>
     * <b>处理流程</b>：
     * <ol>
     *   <li>校验 JD 长度 ≥ MIN_JD_LENGTH（50 字符）</li>
     *   <li>构造系统提示词（含参考文件清单表格） + 用户提示词（含 JD 文本）</li>
     *   <li>调用 LLM 解析，获取分类（面试维度）列表（key、label、priority、ref、shared）</li>
     *   <li>返回结果。前端展示分类（面试维度）列表让用户确认或调整</li>
     * </ol>
     * <p>
     * 参考文件清单（cachedReferenceFileList）作为系统提示词的一部分注入，
     * 让 LLM 知道有哪些可用的参考文件，从而在解析分类时自动匹配 ref。
     *
     * @param jdText 职位描述文本（≥50 字符）
     * @return LLM 解析出的分类（面试维度）列表
     * @throws BusinessException 如果 JD 太短或解析失败
     */
    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.length() < MIN_JD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 " + MIN_JD_LENGTH + " 字），请补充后重试");
        }

        log.info("开始解析 JD，长度: {}", jdText.length());

        ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
        String systemPrompt = jdSystemPromptTemplate.render(Map.of(
                "referenceFileList", cachedReferenceFileList
        )) + "\n\n" + jdOutputConverter.getFormat();
        String userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
                "职位描述：\n" +
                promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(jdText));

        try {
            CategoryListDTO result = structuredOutputInvoker.invoke(
                    chatClient, systemPrompt, userPrompt, jdOutputConverter,
                    ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log
            );

            if (result == null || result.categories() == null || result.categories().isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试");
            }

            long refMatched = result.categories().stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
            log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", result.categories().size(), refMatched);
            return result.categories();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JD 解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题");
        }
    }

    /**
     * 构建参考文件清单 Markdown 表格（供 LLM 解析 JD 使用）。
     * <p>
     * 使用 {@link LinkedHashMap} 保证文件名去重的同时保持首次出现的顺序。
     */
    private String buildReferenceFileList() {
        // 收集所有去重 的参考文件（文件名 → Markdown 表格行）
        Map<String, String> refDescriptions = new LinkedHashMap<>();
        for (var entry : presetRegistry.entrySet()) {
            String skillName = entry.getValue().getDisplayName() != null
                    ? entry.getValue().getDisplayName() : entry.getValue().getName();
            if (entry.getValue().getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : entry.getValue().getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank()) {
                    refDescriptions.putIfAbsent(cat.getRef(),
                            "| " + cat.getRef()
                                    + " | " + (Boolean.TRUE.equals(cat.getShared()) ? "shared" : "skill-local")
                                    + " | " + skillName
                                    + " | " + cat.getLabel() + " |\n");
                }
            }
        }

        if (refDescriptions.isEmpty()) {
            return "（无可用参考文件）";
        }

        StringBuilder sb = new StringBuilder("| 文件名 | 范围 | 来源 Skill | 覆盖内容 |\n");
        sb.append("|--------|------|-------------|----------|\n");
        for (String row : refDescriptions.values()) {
            sb.append(row);
        }
        return sb.toString();
    }

    /**
     * 计算某个 Skill 的各个分类（面试维度）的题目分配方案。
     * @param skillId   Skill ID
     * @param totalQuestions 需要生成的总题目数
     * @return
     */
    public Map<String, Integer> calculateAllocation(String skillId, int totalQuestions) {
        return calculateAllocation(getSkill(skillId).categories(), totalQuestions);
    }

    /**
     * 计算各分类（面试维度）的题目分配方案。
     *
     * <h4>分配算法（三阶段）</h4>
     * <pre>
     * Phase 1: ALWAYS_ONE 分类各 1 题（保底）
     * Phase 2: 所有分类各 1 题（CORE 优先）——保证覆盖率
     * Phase 3: 剩余名额轮转分配（CORE 优先）——剩余名额向重要维度倾斜
     * </pre>
     *
     * <h4>为什么 CORE 优先于普通分类？</h4>
     * CORE 分类是面试的核心维度（如"Java 核心"之于 Java 面试），
     * 应该在题量上得到倾斜。普通分类（如"软技能"）有 1 题覆盖即可。
     * <p>
     * <h4>轮转分配的必要性</h4>
     * 如果直接按比例分配（如 CORE 50%），在题量少时会导致
     * 某些分类只分配到 0 题。轮转分配保证：每个有资格的分类至少 1 题，
     * 剩余名额在核心维度之间均匀分配。
     *
     * @param categories    分类维度列表
     * @param totalQuestions 需要生成的总题目数
     * @return category key → 分配题数的映射
     */
    public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions) {
        List<SkillCategoryDTO> alwaysOneCats = new ArrayList<>();
        List<SkillCategoryDTO> coreCats = new ArrayList<>();
        List<SkillCategoryDTO> normalCats = new ArrayList<>();

        for (SkillCategoryDTO cat : categories) {
            switch (cat.priority()) {
                case "ALWAYS_ONE" -> alwaysOneCats.add(cat);
                case "CORE" -> coreCats.add(cat);
                default -> normalCats.add(cat);
            }
        }

        Map<String, Integer> allocation = new LinkedHashMap<>();
        int remaining = totalQuestions;

        // Phase 1: ALWAYS_ONE 保底各 1 题
        for (SkillCategoryDTO cat : alwaysOneCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 2: 先给所有类目各 1 题（CORE 优先），保证覆盖率
        for (SkillCategoryDTO cat : coreCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }
        for (SkillCategoryDTO cat : normalCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 3: 剩余名额按 CORE 优先轮转分配
        while (remaining > 0) {
            for (SkillCategoryDTO cat : coreCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            for (SkillCategoryDTO cat : normalCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            if (coreCats.isEmpty() && normalCats.isEmpty()) break;
        }

        // 确保所有类目都出现在 allocation 中
        for (SkillCategoryDTO cat : coreCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }
        for (SkillCategoryDTO cat : normalCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }

        log.debug("题目分配: total={}, allocation={}", totalQuestions, allocation);
        return allocation;
    }

    /**
     * 构建题目分配逻辑的可读描述（用于 LLM 提示词）。
     * <p>
     * 输出 Markdown 表格行，如 "| Java 核心 | 3 题 | CORE |"
     */
    public String buildAllocationDescription(Map<String, Integer> allocation, List<SkillCategoryDTO> categories) {
        StringBuilder sb = new StringBuilder();
        for (SkillCategoryDTO cat : categories) {
            int count = allocation.getOrDefault(cat.key(), 0);
            if (count > 0) {
                sb.append("| ").append(cat.label()).append(" | ").append(count).append(" 题 | ").append(cat.priority()).append(" |\n");
            }
        }
        return sb.toString();
    }

    /**
     * 构建出题阶段的参考内容段落。
     * 仅包含已分配题目的分类（面试维度）的参考文件。因为如果某个分类没有分配到题目，其参考文件不应出现在提示词中（减少噪音、降低 token 消耗）。
     */
    public String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
        return buildReferenceSectionInternal(
                skill,
                category -> allocation.getOrDefault(category.key(), 0) > 0,
                MAX_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 构建评估阶段的参考基线。覆盖该 Skill 下所有分类（面试维度）的参考文件。
     * <p>
     * 评估阶段与出题阶段的不同：评估时 LLM 需要了解 <b>所有</b> 分类的参考标准，
     * 因为考生的回答可能超出出题范围，而评估需要全面性。
     */
    public String buildEvaluationReferenceSection(String skillId) {
        SkillDTO skill = getSkill(skillId);
        return buildReferenceSectionInternal(
                skill,
                category -> true,
                MAX_EVALUATION_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 安全版本的评估参考基线。skillId 为空或加载失败时返回空字符串，不抛异常。
     * <p>
     * 评估是重要的，即使参考基线加载失败，也不应阻断评估流程。
     * 没有参考基线的评估仍可基于简历和标准评分规则进行（只是可能不够精准）。
     */
    public String buildEvaluationReferenceSectionSafe(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        try {
            return buildEvaluationReferenceSection(skillId);
        } catch (Exception e) {
            log.warn("加载评估参考基线失败，降级为无参考: skillId={}, error={}", skillId, e.getMessage());
            return "";
        }
    }

    /**
     * 构建参考内容段落的内部实现。
     * <p>
     * <b>custom 模式的路径解析</b>：
     * 自定义 Skill 没有自己的目录，而它的分类（面试维度）来自 JD 解析。
     * 对于非 shared 的 reference，需要从 categoryRefIndex 查找
     * 原始 skillId 来拼出正确的资源路径。
     *
     * @param skill           Skill 对象
     * @param categoryFilter  分类过滤器（决定哪些分类的参考文件需要加载）
     * @param maxChars        总长度上限（超出则截断）
     * @return 格式化的参考内容段落
     */
    private String buildReferenceSectionInternal(SkillDTO skill,
                                                 Predicate<SkillCategoryDTO> categoryFilter,
                                                 int maxChars) {
        StringBuilder sb = new StringBuilder();

        for (SkillCategoryDTO category : skill.categories()) {
            if (!categoryFilter.test(category)) {
                continue;
            }
            if (category.ref() == null || category.ref().isBlank()) {
                continue;
            }

            // custom 模式下非 shared 的 ref 需要查原始 skillId 拼路径
            String effectiveSkillId = skill.id();
            if (CUSTOM_SKILL_ID.equals(skill.id()) && !category.shared() && category.ref() != null) {
                RefMapping mapping = categoryRefIndex.get(category.key());
                if (mapping != null) {
                    effectiveSkillId = mapping.sourceSkillId();
                }
            }
            String referenceContent = loadReferenceContent(effectiveSkillId, category.ref(), category.shared());
            if (referenceContent.isBlank()) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("### ").append(category.label()).append(" (").append(category.key()).append(")\n");
            sb.append(referenceContent);

            if (sb.length() >= maxChars) {
                sb.setLength(maxChars);
                sb.append("\n...（references 已截断）");
                break;
            }
        }

        return sb.isEmpty() ? "未配置 references。" : sb.toString();
    }

    /** 从 classpath 加载资源，将内容反序列化为 String 对象 */
    private String loadClasspathPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 解析 SKILL.md 文件：front matter + body + meta.yml 聚合。
     * <p>
     * <b>解析步骤</b>：
     * <ol>
     *   <li>用正则 FRONT_MATTER_PATTERN 分离 front matter（YAML）和 body（Markdown）</li>
     *   <li>用 SnakeYAML 将 front matter 解析为 SkillFrontMatterDefinition</li>
     *   <li>body 作为 persona（面试官角色设定）</li>
     *   <li>加载 skill.meta.yml 获取分类、展示等扩展属性</li>
     *   <li>聚合为 SkillDefinition</li>
     * </ol>
     *
     * @param skillId  Skill 标识
     * @param resource SKILL.md 文件资源
     * @param yaml     SnakeYAML 实例（复用，避免重复创建）
     * @return 聚合后的 SkillDefinition
     */
    private InterviewSkillProperties.SkillDefinition parseSkillDefinition(String skillId, Resource resource, Yaml yaml) {
        try {
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
            if (!matcher.matches()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Skill 文件格式错误（缺少 front matter）: " + resource.getDescription());
            }

            String frontMatter = matcher.group(1);
            String body = matcher.group(2) != null ? matcher.group(2).trim() : "";

            InterviewSkillProperties.SkillFrontMatterDefinition frontMatterDef =
                    yaml.loadAs(frontMatter, InterviewSkillProperties.SkillFrontMatterDefinition.class);

            InterviewSkillProperties.SkillDefinition definition = new InterviewSkillProperties.SkillDefinition();
            if (frontMatterDef != null) {
                definition.setName(frontMatterDef.getName());
                definition.setDescription(frontMatterDef.getDescription());
            }
            if (!body.isBlank()) {
                definition.setPersona(body);
            }

            InterviewSkillProperties.SkillMetaDefinition metaDef = loadSkillMetaDefinition(skillId, yaml);
            if (metaDef != null) {
                definition.setDisplayName(metaDef.getDisplayName());
                definition.setDisplay(metaDef.getDisplay());
                definition.setCategories(metaDef.getCategories());
            }

            if (definition.getCategories() == null) {
                definition.setCategories(List.of());
            }
            return definition;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "读取 Skill 文件失败: " + resource.getDescription());
        }
    }

    /**
     * 加载 skill.meta.yml（项目自定义字段）。
     * <p>
     * 文件不存在时返回 null，让调用方使用默认配置。
     * skill.meta.yml 是可选文件：一个 Skill 可以只有 SKILL.md
     * 而没有 meta.yml（此时分类列表为空，前端以纯文本模式展示）。
     *
     * @param skillId Skill 标识
     * @param yaml    SnakeYAML 实例
     * @return SkillMetaDefinition，文件不存在时返回 null
     */
    private InterviewSkillProperties.SkillMetaDefinition loadSkillMetaDefinition(String skillId, Yaml yaml) {
        String location = "classpath:skills/" + skillId + "/" + SKILL_META_FILE;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("skill meta 文件不存在，使用默认配置: skillId={}, location={}", skillId, location);
            return null;
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            InterviewSkillProperties.SkillMetaDefinition meta = yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition.class);
            return meta != null ? meta : new InterviewSkillProperties.SkillMetaDefinition();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: " + location);
        }
    }

    /**
     * 将 Skill 的资源 URL 的倒数第二个路径段提取为 String 对象作为其 ID。
     * <p>
     * 使用正则 SKILL_ID_PATTERN 匹配倒数第二个路径段。
     * 例如：file:/path/to/classes/skills/java-backend/SKILL.md → "java-backend"
     * <p>
     * URL 转义处理：{@code replace('\\', '/')} 统一 Windows 和 Unix 的路径分隔符。
     *
     * @param resource SKILL.md 的 Resource 对象
     * @return skillId，提取失败返回 null
     */
    private String extractSkillId(Resource resource) {
        try {
            String normalized = resource.getURL().toString().replace('\\', '/');
            Matcher matcher = SKILL_ID_PATTERN.matcher(normalized);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 加载指定 ref 文件的内容（带缓存）。
     * <p>
     * <b>路径解析优先级</b>：参见 {@link #resolveReferenceLocations}，
     * 依次尝试多个候选路径，返回第一个存在且非空的内容。
     *
     * @param skillId       Skill 标识
     * @param referenceFile 参考文件名（如 "java-core.md"）
     * @param shared        是否标记为共享
     * @return 参考文件内容（可能被截断），找不到返回空字符串
     */
    private String loadReferenceContent(String skillId, String referenceFile, boolean shared) {
        if (!isSafeReferencePath(referenceFile)) {
            log.warn("忽略不安全的 reference 路径: skillId={}, ref={}", skillId, referenceFile);
            return "";
        }

        List<String> candidateLocations = resolveReferenceLocations(skillId, referenceFile, shared);
        for (String location : candidateLocations) {
            String content = referenceCache.computeIfAbsent(location, this::readReferenceContent);
            if (!content.isBlank()) {
                return content;
            }
        }

        log.warn("未找到 reference: skillId={}, ref={}, shared={}, locations={}",
                skillId, referenceFile, shared, candidateLocations);
        return "";
    }

    /**
     * 解析参考文件的候选路径列表（按优先级排序）。
     * <p>
     * 使用 {@link LinkedHashSet} 保证顺序且自动去重。
     * shared 的 ref 优先查 _shared 目录；非 shared 的优先查 Skill 专属目录。
     * custom Skill 和 shared ref 还会遍历所有预设 Skill 目录查找。因为 custom Skill 没有自己的目录，需要从预设 Skill 中"借用"参考文件。
     */
    private List<String> resolveReferenceLocations(String skillId, String referenceFile, boolean shared) {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        if (shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        addSkillReferenceLocations(locations, skillId, referenceFile);

        if (!shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        if (CUSTOM_SKILL_ID.equals(skillId) || shared) {
            for (String presetSkillId : presetRegistry.keySet()) {
                addSkillReferenceLocations(locations, presetSkillId, referenceFile);
            }
        }

        return List.copyOf(locations);
    }

    /** 添加 Skill 专属的候选路径 */
    private void addSkillReferenceLocations(LinkedHashSet<String> locations, String skillId, String referenceFile) {
        if (skillId == null || skillId.isBlank() || CUSTOM_SKILL_ID.equals(skillId)) {
            return;
        }
        locations.add("classpath:skills/" + skillId + "/references/" + referenceFile);
        locations.add("classpath:skills/" + skillId + "/" + referenceFile);
    }

    /** 构建 _shared 目录下的候选路径 */
    private String buildSharedReferenceLocation(String referenceFile) {
        return "classpath:skills/_shared/references/" + referenceFile;
    }

    /**
     * 从指定路径读取单个参考文件的内容（实际 I/O 操作）。
     * <p>
     * 超过 MAX_SINGLE_REFERENCE_CHARS 时截断。超长的参考文件通常是完整的
     * API 文档或书籍章节，对 LLM 来说前 3000 字符已经足够建立上下文。
     *
     * @param location classpath 资源路径
     * @return 文件内容字符串（可能被截断），文件不存在返回空字符串
     */
    private String readReferenceContent(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            return "";
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.length() > MAX_SINGLE_REFERENCE_CHARS) {
                return content.substring(0, MAX_SINGLE_REFERENCE_CHARS) + "\n...（单文件内容已截断）";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取 reference 失败: location={}", location, e);
            return "";
        }
    }

    /**
     * 检查参考文件路径是否安全（防止路径遍历攻击）。
     * <p>
     * 拒绝以下情况：
     * <ul>
     *   <li>包含 ".."（可能跳出 classpath 目录）</li>
     *   <li>以 "/" 或 "\" 开头（绝对路径）</li>
     *   <li>包含非字母数字的非法字符（如 ";"、"|"、"$"）</li>
     * </ul>
     * <p>
     * 虽然 ref 来自开发者配置的 YAML（不是用户输入），但这层防御
     * 遵循"纵深防御"原则。万一 YAML 被篡改或解析出错，至少不会导致安全问题。
     *
     * @param referenceFile 参考文件名
     * @return true 如果路径安全
     */
    private boolean isSafeReferencePath(String referenceFile) {
        return !referenceFile.contains("..")
                && !referenceFile.startsWith("/")
                && !referenceFile.startsWith("\\")
                && referenceFile.matches("[a-zA-Z0-9._/-]+");
    }

    /**
     * 清洗 category key：截断长度，非法字符替换为下划线，转大写。
     * 首字符必须为字母，否则添加 "CAT_" 前缀。确保 key 在作为 Map key、CSS class、文件名时都不会遇到兼容性问题。
     */
    private String sanitizeCategoryKey(String key) {
        if (key == null || key.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_CATEGORY_KEY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_KEY_LENGTH);
        }
        String upper = trimmed.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (upper.isEmpty()) {
            return "UNKNOWN";
        }
        // 确保首字符为字母（匹配 category key 命名规范）
        if (!Character.isLetter(upper.charAt(0))) {
            upper = "CAT_" + upper;
        }
        return upper;
    }

    /**
     * 清洗 category label：截断长度，移除换行。
     * Label 用于前端展示和 LLM 提示词。换行符会破坏表格格式，过长会破坏 UI 布局，必须清理。
     */
    private String sanitizeCategoryLabel(String label) {
        if (label == null || label.isBlank()) {
            return "未命名";
        }
        String trimmed = label.trim().replaceAll("[\\r\\n]+", " ");
        if (trimmed.length() > MAX_CATEGORY_LABEL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_LABEL_LENGTH);
        }
        return trimmed;
    }

    /** 将后端内部使用的 SkillDefinition 转为前端需要的 SkillDTO */
    private SkillDTO toSkillDTO(String id, InterviewSkillProperties.SkillDefinition def) {
        String skillDisplayName = (def.getDisplayName() != null && !def.getDisplayName().isBlank())
                ? def.getDisplayName()
                : def.getName();

        InterviewSkillProperties.DisplayDef disp = def.getDisplay();
        DisplayDTO displayDTO = disp != null
                ? new DisplayDTO(disp.getIcon(), disp.getGradient(), disp.getIconBg(), disp.getIconColor())
                : null;

        List<SkillCategoryDTO> categories = def.getCategories() == null
                ? List.of()
                : def.getCategories().stream()
                .map(c -> new SkillCategoryDTO(
                        c.getKey(),
                        c.getLabel(),
                        c.getPriority(),
                        c.getRef(),
                        Boolean.TRUE.equals(c.getShared())
                ))
                .toList();

        return new SkillDTO(
                id,
                skillDisplayName,
                def.getDescription(),
                categories,
                true,
                null,
                def.getPersona(),
                displayDTO
        );
    }

    /**
     * Skill 数据传输对象。
     * <p>
     * isPreset = true 表示来自 classpath 预设；false 表示由 JD 解析动态构建。
     * sourceJd 仅在 JD 解析模式时有值，它是生成自定义 Skill 的原始 JD 文本。
     */
    public record SkillDTO(String id, String name, String description,
                           List<SkillCategoryDTO> categories,
                           boolean isPreset, String sourceJd, String persona, DisplayDTO display) {}

    public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

    /**
     * 预设 Skill 的面试维度（携带 ref 绑定信息）。
     * shared 为 true 表示该 ref 在 _shared 目录下，多个 Skill 共享。
     */
    public record SkillCategoryDTO(String key, String label, String priority, String ref, boolean shared) {}

    /**
     * LLM 解析 JD 返回的分类（携带 LLM 匹配的 ref/shared 信息）。
     * 与 SkillCategoryDTO 分开定义是因为：JD 解析返回的分类的 ref/shared 来自 LLM 猜测，
     * 可能不准确，后端会按 categoryRefIndex 纠正。
     */
    public record CategoryDTO(String key, String label, String priority,
                              String ref, Boolean shared) {}

    /** LLM 解析 JD 返回的分类列表 */
    private record CategoryListDTO(List<CategoryDTO> categories) {}
}

