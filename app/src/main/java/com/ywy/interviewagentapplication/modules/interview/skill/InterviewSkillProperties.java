package com.ywy.interviewagentapplication.modules.interview.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试技能（Skill）的配置属性定义。
 *
 * <h3>两种 YAML 配置源</h3>
 * 一个面试 Skill 由两个 classpath 文件共同定义：
 * <ol>
 *   <li><b>SKILL.md front matter</b> → {@link SkillFrontMatterDefinition}：
 *       标准 Markdown 前置元数据（name、description），与 Skill 体系通用</li>
 *   <li><b>skill.meta.yml</b> → {@link SkillMetaDefinition}：
 *       项目自定义元数据（displayName、categories、display），专属于本应用的扩展</li>
 * </ol>
 * 两者在运行时被聚合到 {@link SkillDefinition} 中，提供统一的数据视图。
 *
 * <h3>YAML 解析</h3>
 * 使用 SnakeYAML 的 {@code loadAs()} 方法将 YAML 文本直接反序列化为这些 POJO。
 * 为什么用 SnakeYAML 而非 Spring 的 {@code @ConfigurationProperties}？
 * 因为 Skill 文件在 classpath 中动态发现和加载，不是固定的 application.yml 配置项，需要编程式解析。
 *
 * @see InterviewSkillService Skill 的加载和管理者
 */
public final class InterviewSkillProperties {

    private InterviewSkillProperties() {
    }

    /**
     * SKILL.md 的 front matter（标准字段）。
     * <p>
     * 对应 YAML 格式：
     * <pre>
     * ---
     * name: Java Backend
     * description: Java 后端开发面试
     * ---
     * </pre>
     * 这是与 Skill 体系（如 Claude Code 的 skills 目录）兼容的标准格式，
     * name 和 description 在 SKILL.md 的 YAML front matter 部分中定义。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFrontMatterDefinition {
        /** Skill 的标识名称，如 "Java Backend" */
        private String name;
        /** Skill 的功能描述，如 "Java 后端开发面试" */
        private String description;
    }

    /**
     * skill.meta.yml 的内容（项目自定义字段）。
     * <p>
     * 此文件存放 SKILL.md front matter 中不包含的项目特定元数据。
     * 分离为独立文件的原因是：SKILL.md 的 front matter 格式是标准化的，
     * 不应为了项目需求而添加非标准字段，那会破坏与 Skill 生态的兼容性。
     * <p>
     * categories 是此配置中最重要的字段，定义了面试的考察维度。
     * <p>
     * 使用 ArrayList 默认值而非 null：避免调用方遍历时需要 null 检查。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMetaDefinition {
        /** UI 展示用的名称（可能与 name 不同，支持中文） */
        private String displayName;
        /** UI 展示配置（图标、渐变色等） */
        private DisplayDef display;
        /** 该面试技能下的考察维度列表 */
        private List<CategoryDef> categories = new ArrayList<>();
    }

    /**
     * 运行时聚合结构：SKILL.md front matter + skill.meta.yml。
     * <p>
     * <b>为什么需要聚合？</b>
     * 调用方（QuestionService、Controller）不关心数据来自哪个文件，
     * 它只需要一个完整的 Skill 描述。聚合发生在加载阶段（Service 层），
     * 之后各模块只需要与这一个统一的 SkillDefinition 交互。
     * <p>
     * persona 字段来自 SKILL.md 的正文部分（front matter 之后的内容），
     * 用于注入到 LLM 提示词中定义面试官的角色风格。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillDefinition {
        /** 来自 SKILL.md front matter 的 name */
        private String name;
        /** 来自 SKILL.md front matter 的 description */
        private String description;
        /** 来自 SKILL.md body（front matter 之后的 Markdown 内容），面试官角色设定 */
        private String persona;
        /** 来自 skill.meta.yml 的展示名称 */
        private String displayName;
        /** 来自 skill.meta.yml 的 UI 展示配置 */
        private DisplayDef display;
        /** 来自 skill.meta.yml 的考察维度列表 */
        private List<CategoryDef> categories = new ArrayList<>();
    }

    /**
     * UI 展示配置。
     * <p>
     * 这些字段控制前端 Skill 选择器的卡片样式。
     * 每个字段都可以为 null，此时前端使用 CSS 默认值兜底。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayDef {
        /** 图标名称或 URL（如 "java"、"python"） */
        private String icon;
        /** CSS 渐变色（如 "linear-gradient(135deg, #667eea, #764ba2)"） */
        private String gradient;
        /** 图标背景色 */
        private String iconBg;
        /** 图标颜色 */
        private String iconColor;
    }

    /**
     * 分类/考察维度定义。
     * <p>
     * <b>priority 的三种取值</b>：
     * <ul>
     *   <li><b>ALWAYS_ONE</b>：核心维度，任何面试都必须覆盖，至少分配 1 题。</li>
     *   <li><b>CORE</b>：优先分配。重要维度，在满足基本覆盖后优先获得更多题目</li>
     *   <li>其他值（默认）：普通维度，在 CORE 之后按轮转方式分配</li>
     * </ul>
     * <p>
     * <b>ref 字段</b>：指向 classpath 下的参考文件（如 "java-core.md"），
     * 用于在 LLM 提示词中注入"参考答案/评分标准"。shared=true 表示该参考文件
     * 可被其他 Skill 共享使用（存放在 _shared/references/ 目录）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDef {
        /** 分类唯一标识（如 "JAVA_CORE"、"SYSTEM_DESIGN"），用于分配和统计 */
        private String key;
        /** 分类展示标签（如 "Java 核心"、"系统设计"） */
        private String label;
        /** 分配优先级：ALWAYS_ONE / CORE / 默认 */
        private String priority;
        /** 关联的参考文件名（如 "java-core.md"），用于 LLM 出题和评估 */
        private String ref;
        /** 该参考文件是否可在多个 Skill 之间共享 */
        private Boolean shared;
    }
}

