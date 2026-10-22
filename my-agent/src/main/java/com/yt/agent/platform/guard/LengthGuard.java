package com.yt.agent.platform.guard;

/**
 * 输入长度护栏。
 *
 * <p>为什么必须有：Agent 的输入会被拼进每一次模型调用，超长输入不仅直接推高成本，
 * 还会挤占历史与工具结果的空间，导致上下文压缩提前触发、推理质量下降。
 * 更现实的是，超长粘贴是提示词注入与"上下文淹没"攻击的常见载体。</p>
 */
public class LengthGuard implements InputGuard {

    private final int maxChars;

    public LengthGuard(int maxChars) {
        this.maxChars = maxChars > 0 ? maxChars : 8000;
    }

    @Override
    public String name() {
        return "length";
    }

    @Override
    public GuardDecision inspect(String text, GuardContext context) {
        if (text == null || text.isBlank()) {
            return GuardDecision.block("empty_input", "问题内容为空，请描述你的需求。");
        }
        if (text.length() > maxChars) {
            return GuardDecision.block("too_long",
                    String.format("输入过长（%d 字符，上限 %d）。请精简问题，或先上传文件再针对文件提问。",
                            text.length(), maxChars));
        }
        return GuardDecision.pass(text);
    }
}
