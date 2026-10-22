package com.yt.agent.platform.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 护栏判定结果。
 *
 * <p>三态而不是布尔值：Agent 的安全处理不只有"放行/拦截"，还有"改写后放行"（如 PII 脱敏）。
 * 只有三态才能表达"这句话可以进模型，但手机号要先打码"这种真实需求。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardDecision {

    /** 是否允许继续 */
    private boolean allowed;

    /** 处理后的文本（发生了脱敏时与原文不同） */
    private String text;

    /** 触发的规则名，便于归因与指标统计 */
    private String rule;

    /** 人类可读的原因，会返回给用户 */
    private String reason;

    /** 是否改写了文本 */
    private boolean modified;

    public static GuardDecision pass(String text) {
        return GuardDecision.builder().allowed(true).text(text).rule("none").reason("").modified(false).build();
    }

    public static GuardDecision modified(String text, String rule, String reason) {
        return GuardDecision.builder().allowed(true).text(text).rule(rule).reason(reason).modified(true).build();
    }

    public static GuardDecision block(String rule, String reason) {
        return GuardDecision.builder().allowed(false).text("").rule(rule).reason(reason).modified(false).build();
    }
}
