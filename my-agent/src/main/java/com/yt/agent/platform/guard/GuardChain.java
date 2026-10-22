package com.yt.agent.platform.guard;

import com.yt.agent.platform.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 护栏链 —— 把一组独立规则串成有序流水线。
 *
 * <p>执行语义（按顺序、短路、可改写）：
 * <ol>
 *   <li>任一规则拒绝 → 立即中断，不再执行后续规则（既省资源，也避免泄露"哪条规则差点没拦住"）；</li>
 *   <li>规则返回改写 → 把改写后的文本交给下一条规则（例如先脱敏再检测注入）；</li>
 *   <li>全部通过 → 返回最终文本。</li>
 * </ol>
 * </p>
 *
 * <p>顺序是设计的一部分，不是随意的：
 * 长度 → 敏感信息 → 注入 → PII。先挡"太长/带密钥"的低成本判断，
 * 再挡高风险注入，最后做脱敏（脱敏不应影响前面的规则判断）。</p>
 */
@Slf4j
public class GuardChain {

    private final GuardProperties properties;
    private final List<InputGuard> guards;
    private final AgentMetrics metrics;

    /** 输出侧需要打码的模式：即便输入没拦住，也不能让模型把密钥复述出来 */
    private static final List<Pattern> OUTPUT_MASKS = List.of(
            Pattern.compile("(?i)\\b(sk-[A-Za-z0-9_\\-]{16,})"),
            Pattern.compile("(?i)\\b(AKIA[0-9A-Z]{16})"),
            Pattern.compile("(?i)\\b(api[_-]?key|access[_-]?key|secret[_-]?key|password|passwd|pwd)\\s*[:=]\\s*([^\\s,;]{4,})")
    );

    public GuardChain(GuardProperties properties, List<InputGuard> guards, AgentMetrics metrics) {
        this.properties = properties;
        this.guards = guards == null ? List.of() : List.copyOf(guards);
        this.metrics = metrics;
        log.info("[GuardChain] 已启用 {} 条输入护栏: {}", this.guards.size(), rules());
    }

    /**
     * 输入检查。返回的 {@code text} 是最终可送模型的文本（可能已被脱敏）。
     */
    public GuardDecision checkInput(String text, GuardContext context) {
        if (!properties.isEnabled()) {
            return GuardDecision.pass(text);
        }
        String current = text;
        List<String> applied = new ArrayList<>();
        for (InputGuard guard : guards) {
            GuardDecision decision;
            try {
                decision = guard.inspect(current, context);
            } catch (Exception e) {
                // 单条规则异常不应导致整个请求失败：护栏是增强能力，不是关键路径
                log.error("[GuardChain] 规则 {} 执行异常，已跳过: {}", guard.name(), e.getMessage());
                continue;
            }
            if (decision == null || !decision.isAllowed()) {
                metrics.guardBlocked("input", guard.name());
                return decision == null
                        ? GuardDecision.block(guard.name(), "内容安全检查未通过")
                        : decision;
            }
            if (decision.isModified() && decision.getText() != null) {
                current = decision.getText();
                metrics.guardModified("input", guard.name());
                applied.add(guard.name());
            }
        }
        if (!applied.isEmpty()) {
            return GuardDecision.modified(current, String.join(",", applied), "输入已按安全策略处理：" + applied);
        }
        return GuardDecision.pass(current);
    }

    /**
     * 输出脱敏：模型回答、任务摘要在外发前做一次兜底打码。
     * 只做"确定性可识别"的替换（密钥/口令/邮箱/身份证），不改写语义内容。
     */
    public String sanitizeOutput(String text) {
        if (!properties.isEnabled() || !properties.isScanOutput() || text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        // 1) 密钥/口令：保留键名，值打码，便于用户识别"这里原本有个密码"
        result = OUTPUT_MASKS.get(2).matcher(result)
                .replaceAll(m -> m.group(1) + "=***");
        for (int i = 0; i < 2; i++) {
            Pattern p = OUTPUT_MASKS.get(i);
            if (p.matcher(result).find()) {
                result = p.matcher(result).replaceAll("***");
                metrics.guardModified("output", "secret");
            }
        }
        // 2) PII：输出侧比输入侧更严格（回答里出现完整身份证没有正当理由）
        if (properties.isMaskPii()) {
            PiiMaskGuard pii = new PiiMaskGuard(true, properties.isMaskPhone());
            GuardDecision decision = pii.inspect(result, null);
            if (decision.isModified()) {
                result = decision.getText();
                metrics.guardModified("output", "pii");
            }
        }
        return result;
    }

    public List<String> rules() {
        List<String> names = new ArrayList<>();
        guards.forEach(g -> names.add(g.name()));
        return names;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }
}
