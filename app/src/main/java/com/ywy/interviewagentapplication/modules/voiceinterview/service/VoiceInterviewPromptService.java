package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.ywy.interviewagentapplication.common.ai.PromptSanitizer;
import com.ywy.interviewagentapplication.common.ai.PromptSecurityConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音面试系统提示词组装服务。
 *
 * <h3>系统提示词的三层结构</h3>
 * <ol>
 *   <li><b>技能工具指令（SKILL_TOOL_INSTRUCTION）</b>：让 LLM 通过 Skill 工具
 *       按需加载技能模板的 SKILL.md——面试官人设与出题规则不进主提示词，
 *       而是「用到时再加载」。这样提示词主体短小（每次调用的 token 开销低），
 *       而技能详情（可能很长）只在 LLM 认为需要时拉取，且工具返回可被记忆系统缓存</li>
 *   <li><b>语音输出约束（VOICE_RESPONSE_CONSTRAINTS）</b>：语音场景特有的行为约束——
 *       回复要被 TTS 读出来，因此要求 2-4 句、禁 Markdown/列表/代码块；
 *       每轮只问 1 个主问题，避免一轮播报多个问题让候选人记不住</li>
 *   <li><b>简历上下文 + 防注入指令</b>：简历文本消毒后以分隔符包裹注入，
 *       末尾追加全局防提示注入指令（PromptSecurityConstants）</li>
 * </ol>
 *
 * <h3>「不要重复开场白/上一轮问题」约束的意义</h3>
 * 上下文压缩后 LLM 只能看到摘要+窗口，可能「忘记」刚问过什么；
 * 明确禁止复述能显著减少面试官反复问同一问题的故障模式。
 */
@Service
@Slf4j
public class VoiceInterviewPromptService {

    private final PromptSanitizer promptSanitizer;

    public VoiceInterviewPromptService(PromptSanitizer promptSanitizer) {
        this.promptSanitizer = promptSanitizer;
    }

    /**
     * 语音面试输出约束。
     *
     * <p>每条约束对应一个真实故障模式：
     * 1-2 控制单轮信息量（语音不可回看，问多了记不住）；
     * 3 防止压缩上下文后重复提问；4 防止面试官对模糊回答敷衍了事；
     * 5 赋予候选人换题主动权；6 保持口语化（避免书面语被 TTS 读出违和感）。
     */
    private static final String VOICE_RESPONSE_CONSTRAINTS = """
            【语音面试输出约束】
            1. 每轮只问 1 个主问题，必要时最多补 1 个短追问。
            2. 总长度控制在 2-4 句，避免长段落、列表、Markdown、代码块。
            3. 不要重复开场白，不要复述上一轮已问过的完整问题。
            4. 若候选人回答过短或含糊，直接追问一个具体的技术细节或给出提示引导，不要简单确认后停止。
            5. 当候选人明确要求换题时，立即切换到新的技术方向，不要停留在当前话题。
            6. 语气简洁直接，适配口语对话。
            """;

    /**
     * 技能工具指令模板。
     *
     * <p>%s 两处都填 skillId：第一处告诉 LLM「你是 XX 方向的面试官」，
     * 第二处是 Skill 工具的 command 参数（按技能ID加载对应的 SKILL.md）。
     * 「如果尚未加载」的条件措辞允许 LLM 在记忆系统中已加载过该技能时跳过工具调用，
     * 减少重复的工具往返延迟。
     */
    private static final String SKILL_TOOL_INSTRUCTION = """
            你是一位 %s 方向的面试官。
            如果尚未加载完整的角色设定，请调用 Skill 工具（command: %s）加载该技能的 SKILL.md。
            工具输出包含完整的面试官角色和出题规则，后续对话应基于该角色进行。
            """;

    /**
     * 生成带简历上下文的系统提示词。
     *
     * @param skillId    技能模板ID（决定面试官人设与出题方向）
     * @param resumeText 简历文本（可为 null，无简历时不注入）
     * @return 完整系统提示词
     */
    public String generateSystemPromptWithContext(String skillId, String resumeText) {
        StringBuilder prompt = new StringBuilder();

        if (skillId != null && !skillId.isBlank()) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId));
        }

        prompt.append("\n\n").append(VOICE_RESPONSE_CONSTRAINTS);

        if (resumeText != null && !resumeText.isEmpty()) {
            // 简历是「不可信的外部输入」，先消毒再以分隔符包裹注入，
            // 防止简历中的恶意文本劫持系统提示词
            String safeResume = promptSanitizer.sanitize(resumeText);
            prompt.append("\n\n【实时语音面试 - 候选人简历内容】\n")
                    .append("你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。\n\n")
                    .append("【简历解析文本】\n")
                    .append(promptSanitizer.wrapWithDelimiters("resume", safeResume));
        }

        // 全局防提示注入指令收尾（所有用户可控输入的处理规则）
        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
        return prompt.toString();
    }
}

